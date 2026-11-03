package com.basir.run.belt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.basir.run.Prefs
import java.util.UUID
import run.basir.engine.BeltConfig
import run.basir.engine.BeltPattern
import run.basir.engine.BeltProtocol
import run.basir.engine.BeltTransmitter

/** What the belt link is doing, for the screen and the voice. */
enum class BeltState(val ar: String) {
    OFF("الحزام غير متصل"),
    NO_PERMISSION("يحتاج التطبيق إذن الأجهزة القريبة للاتصال بالحزام"),
    BLUETOOTH_OFF("البلوتوث مطفأ في الهاتف"),
    SCANNING("أبحث عن الحزام"),
    CONNECTING("أتصل بالحزام"),
    CONNECTED("الحزام متصل"),
    NOT_FOUND("لم أجد الحزام. تأكد أنه مشغّل وقريب"),
    LOST("انقطع الاتصال بالحزام. أحاول من جديد"),
}

/**
 * Bluetooth Low Energy link to the Basir-Run belt (firmware/basir_belt). Everything runs on one handler thread so the
 * [BeltTransmitter] (latest-wins outbox, ttl, heartbeats, config after reconnect) is never touched concurrently.
 * Writes are "without response" and one at a time; a write that gets no callback within 300 ms is given up (the
 * frame is gone, never resent later). Losing the link stops the belt by itself (firmware watchdog); the app then
 * vibrates the phone instead (HapticEngine) and reconnects in the background.
 */
@SuppressLint("MissingPermission") // every entry point checks [hasPermission] first
class BeltLink(private val context: Context, private val prefs: Prefs) {
    private val thread = HandlerThread("belt-link").apply { start() }
    private val handler = Handler(thread.looper)
    private val tx = BeltTransmitter(configFromPrefs())
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var maxSteps = 1
    private var writeStartedAt = 0L
    private var wantConnected = false
    private var scanning = false

    @Volatile var state: BeltState = BeltState.OFF
        private set
    @Volatile var deviceName: String? = null
        private set
    /** Called (on the link thread) when a connected belt is lost; the app says so once. */
    var onLost: (() -> Unit)? = null

    val connected: Boolean get() = state == BeltState.CONNECTED

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Connects to the saved belt, or scans for one (the first belt found is saved). */
    fun connect() = handler.post {
        wantConnected = true
        prefs.beltEnabled = true
        if (!hasPermission()) { state = BeltState.NO_PERMISSION; return@post }
        val a = adapter
        if (a == null || !a.isEnabled) { state = BeltState.BLUETOOTH_OFF; return@post }
        val saved = prefs.beltAddress
        if (saved != null && runCatching { a.getRemoteDevice(saved) }.isSuccess) open(a.getRemoteDevice(saved)) else scan()
    }

    /** Forgets nothing but stops using the belt (the phone vibrates again). */
    fun disconnect() = handler.post {
        wantConnected = false
        prefs.beltEnabled = false
        stopScan()
        if (connected) send { tx.silence(now()) }
        handler.postDelayed({ closeGatt(); state = BeltState.OFF }, 150)
    }

    /** Finds a belt again, forgetting the saved one (another belt, or a new board). */
    fun forgetAndScan() = handler.post { prefs.beltAddress = null; closeGatt(); connect() }

    fun play(p: BeltPattern) = handler.post { if (connected) send { tx.play(p, now(), maxSteps) } }
    fun silence() = handler.post { if (connected) send { tx.silence(now()) } }
    fun applyConfig() = handler.post { tx.setConfig(configFromPrefs(), now()); pump() }

    fun close() { handler.post { closeGatt(); thread.quitSafely() } }

    // ---- internals (link thread only) --------------------------------------------------------------------------

    private fun configFromPrefs() = BeltConfig(intensityPct = prefs.beltIntensityPct, linkTimeoutMs = 1500)
    private fun now() = SystemClock.elapsedRealtime()
    private inline fun send(block: () -> Unit) { block(); pump() }

    private val ticker = object : Runnable {
        override fun run() {
            if (connected) {
                val t = now()
                if (writeStartedAt != 0L && t - writeStartedAt > 300) { writeStartedAt = 0L; tx.onWriteDone() }
                tx.tick(t)
                pump()
            }
            handler.postDelayed(this, 100)
        }
    }
    init { handler.post(ticker) }

    private fun pump() {
        val g = gatt ?: return
        val c = command ?: return
        val bytes = tx.takeNext(now()) ?: return
        writeStartedAt = now()
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = bytes; g.writeCharacteristic(c) }
        }
        if (!ok) { writeStartedAt = 0L; tx.onWriteDone() }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!scanning) return@post
                stopScan()
                prefs.beltAddress = result.device.address
                open(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) { handler.post { scanning = false; state = BeltState.NOT_FOUND } }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { state = BeltState.BLUETOOTH_OFF; return }
        state = BeltState.SCANNING
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }.onFailure { scanning = false; state = BeltState.NO_PERMISSION; return }
        handler.postDelayed({ if (scanning) { stopScan(); state = BeltState.NOT_FOUND } }, 12_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun open(device: BluetoothDevice) {
        closeGatt()
        state = BeltState.CONNECTING
        deviceName = runCatching { device.name }.getOrNull()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        tx.onDisconnected()
        command = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!g.requestMtu(185)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = state == BeltState.CONNECTED
                    closeGatt()
                    if (wantConnected) {
                        state = BeltState.LOST
                        if (was) onLost?.invoke()
                        handler.postDelayed({ if (wantConnected && !connected && state == BeltState.LOST) connect() }, 2000)
                    } else state = BeltState.OFF
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                // payload = mtu - 3; a frame is 14 bytes + 6 per step
                maxSteps = ((mtu - 3 - BeltProtocol.HEADER - 1) / 6).coerceIn(1, BeltProtocol.MAX_STEPS)
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val c = g.getService(SERVICE)?.getCharacteristic(COMMAND)
                if (c == null) { closeGatt(); state = BeltState.NOT_FOUND; prefs.beltAddress = null; return@post }
                command = c
                state = BeltState.CONNECTED
                tx.onConnected(now())     // config first; nothing from before is resent
                pump()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { writeStartedAt = 0L; tx.onWriteDone(); pump() }
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("6b1e0001-6f2b-4c1a-9a2e-8f6d2a7b0b01")
        val COMMAND: UUID = UUID.fromString("6b1e0002-6f2b-4c1a-9a2e-8f6d2a7b0b01")
    }
}
