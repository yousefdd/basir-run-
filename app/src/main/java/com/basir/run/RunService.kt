package com.basir.run

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.basir.run.tracking.DistanceTracker
import com.basir.run.tracking.Fix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Runs the tracking as a foreground service, so it keeps going with the screen off
// or another app open. Location comes from the Fused Location Provider.
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = DistanceTracker()

    private lateinit var client: FusedLocationProviderClient
    private lateinit var speaker: Speaker
    private lateinit var prefs: Prefs
    private lateinit var notifications: NotificationManager

    private var startedAtElapsed = 0L
    private var startedAtWall = 0L
    private var nextAnnouncementM = 0
    private var tickJob: Job? = null
    private var shutdownJob: Job? = null
    // During a guided session the guidance owns the voice; distance is only recorded.
    private var quiet = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        speaker = Speaker(this)
        prefs = Prefs(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "الجري", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                quiet = intent.getBooleanExtra(EXTRA_QUIET, false)
                start()
            }
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        shutdownJob?.cancel()
        if (RunSession.state.value.active) return

        tracker.reset()
        startedAtElapsed = SystemClock.elapsedRealtime()
        startedAtWall = System.currentTimeMillis()
        nextAnnouncementM = prefs.announceEveryM
        RunSession.state.value = RunState(active = true)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        tickJob = scope.launch {
            while (isActive) {
                publish()
                delay(1000L)
            }
        }
        announce("بدأ الجري")
    }

    private fun stop() {
        if (!RunSession.state.value.active) {
            stopSelf()
            return
        }
        client.removeLocationUpdates(callback)
        tickJob?.cancel()

        val record = RunRecord(startedAtWall, tracker.distanceM.toFloat(), elapsed())
        if (record.durationMs >= MIN_SAVED_RUN_MS) RunStore(this).add(record)
        RunSession.state.value = RunState(finished = record)
        announce("انتهى الجري. ${spokenDistance(record.distanceM)} في ${spokenDuration(record.durationMs)}")

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Give the voice time to finish before the service and its speech engine go away.
        shutdownJob = scope.launch {
            delay(8000L)
            stopSelf()
        }
    }

    private fun onLocation(location: Location) {
        tracker.accept(
            Fix(
                timeNanos = location.elapsedRealtimeNanos,
                lat = location.latitude,
                lon = location.longitude,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            )
        )
        RunSession.state.value = RunSession.state.value.copy(
            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            gps = tracker.diagnostics,
        )
        if (tracker.distanceM >= nextAnnouncementM) {
            announce("${nextAnnouncementM} متر. الزمن ${spokenDuration(elapsed())}")
            if (prefs.vibrateOn && !quiet) Haptics.pulse(this)
            nextAnnouncementM += prefs.announceEveryM
        }
        publish()
    }

    private fun publish() {
        val current = RunSession.state.value
        if (!current.active) return
        RunSession.state.value = current.copy(
            elapsedMs = elapsed(),
            distanceM = tracker.distanceM.toFloat(),
            paceSecPerKm = tracker.currentPaceSecPerKm(),
        )
        notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAtElapsed

    private fun announce(text: String) {
        if (prefs.voiceOn && !quiet) speaker.say(text)
    }

    private fun buildNotification(): Notification {
        val state = RunSession.state.value
        val (number, unit) = distanceParts(state.distanceM)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_run)
            .setContentTitle("بصير: الجري مستمر")
            .setContentText("$number $unit · ${clock(state.elapsedMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "إنهاء الجري", stop)
            .build()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.basir.run.START"
        const val ACTION_STOP = "com.basir.run.STOP"
        const val EXTRA_QUIET = "quiet"
        private const val CHANNEL_ID = "run"
        private const val NOTIFICATION_ID = 7
        private const val MIN_SAVED_RUN_MS = 10_000L
    }
}
