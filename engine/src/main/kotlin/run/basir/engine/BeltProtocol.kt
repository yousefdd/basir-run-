package run.basir.engine

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Basir-Run belt protocol v1: the phone writes one frame per message to the belt's command characteristic
 * (BLE write without response). Little-endian. Specified in docs/belt/BELT_PROTOCOL.md and implemented a second time,
 * byte for byte, in firmware/basir_belt/belt_core.h; the two are checked against each other by golden vectors.
 *
 * ```
 * offset size field
 *  0     1    magic 0xB5
 *  1     1    version 1
 *  2     1    type (PLAY 1, SILENCE 2, HEARTBEAT 3, CONFIG 4)
 *  3     2    seq          increases by 1 per frame (wraps); older or repeated seq numbers are dropped
 *  5     4    phoneMs      phone's elapsed-realtime clock, low 32 bits, when the frame was made
 *  9     2    ttlMs        PLAY only: the pattern must START within ttlMs of phoneMs, or it is dropped (stale)
 * 11     1    priority     PLAY only: BeltPriority; a lower priority never interrupts a higher one that is playing
 * 12     1    n            PLAY: number of steps (1..12); CONFIG: 7 (payload length); otherwise 0
 * 13   6*n    steps        PLAY: mask u8 (bit0 = M1 .. bit4 = M5), amplitude u8, onMs u16, offMs u16
 * 13     7    config       CONFIG: intensity %, trim % for M1..M5, max duty % (per motor) ; then linkTimeoutMs u16
 * last   1    crc8         CRC-8 (poly 0x07, init 0x00) over every byte before it
 * ```
 * A PLAY frame with one step is 20 bytes, so it fits even the smallest BLE packet (MTU 23); longer patterns need the
 * MTU the app requests (185).
 */
object BeltProtocol {
    const val MAGIC = 0xB5
    const val VERSION = 1
    const val PLAY = 1
    const val SILENCE = 2
    const val HEARTBEAT = 3
    const val CONFIG = 4
    const val HEADER = 13
    const val MAX_STEPS = 12
    /** Status notifications from the belt start with this byte. */
    const val STATUS_MAGIC = 0xB6

    /** How long each class of message stays worth starting, from the moment the phone made it. */
    fun ttlFor(priority: Int): Int = when (priority) {
        BeltPriority.EMERGENCY -> 700     // a stop is still right a little later
        BeltPriority.DANGER -> 450
        else -> 300                       // guidance older than ~1 camera frame pair is not worth playing
    }

    fun crc8(b: ByteArray, len: Int = b.size): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    private fun header(out: ByteArray, type: Int, seq: Int, phoneMs: Long, ttl: Int, priority: Int, n: Int) {
        out[0] = MAGIC.toByte(); out[1] = VERSION.toByte(); out[2] = type.toByte()
        put16(out, 3, seq and 0xFFFF); put32(out, 5, phoneMs and 0xFFFFFFFFL)
        put16(out, 9, ttl.coerceIn(0, 65535)); out[11] = priority.toByte(); out[12] = n.toByte()
    }

    fun play(p: BeltPattern, seq: Int, phoneMs: Long, ttlMs: Int = ttlFor(p.priority), maxSteps: Int = MAX_STEPS): ByteArray {
        val steps = p.steps.take(maxSteps.coerceIn(1, MAX_STEPS))
        val out = ByteArray(HEADER + 6 * steps.size + 1)
        header(out, PLAY, seq, phoneMs, ttlMs, p.priority, steps.size)
        steps.forEachIndexed { i, s ->
            val o = HEADER + 6 * i
            out[o] = s.mask.toByte(); out[o + 1] = s.amplitude.toByte(); put16(out, o + 2, s.onMs); put16(out, o + 4, s.offMs)
        }
        out[out.size - 1] = crc8(out, out.size - 1).toByte()
        return out
    }

    fun silence(seq: Int, phoneMs: Long): ByteArray = simple(SILENCE, seq, phoneMs)
    fun heartbeat(seq: Int, phoneMs: Long): ByteArray = simple(HEARTBEAT, seq, phoneMs)

    private fun simple(type: Int, seq: Int, phoneMs: Long): ByteArray {
        val out = ByteArray(HEADER + 1)
        header(out, type, seq, phoneMs, 0, 0, 0)
        out[HEADER] = crc8(out, HEADER).toByte()
        return out
    }

    fun config(c: BeltConfig, seq: Int, phoneMs: Long): ByteArray {
        val out = ByteArray(HEADER + 9 + 1)
        header(out, CONFIG, seq, phoneMs, 0, 0, 7)
        out[HEADER] = c.intensityPct.coerceIn(10, 100).toByte()
        c.trimPct.forEachIndexed { i, t -> out[HEADER + 1 + i] = t.coerceIn(50, 150).toByte() }
        out[HEADER + 6] = c.maxDutyPct.coerceIn(20, 100).toByte()
        put16(out, HEADER + 7, c.linkTimeoutMs.coerceIn(300, 10000))
        out[out.size - 1] = crc8(out, out.size - 1).toByte()
        return out
    }

    /** Parsed frame (tests, and the reference player). Null when the frame is malformed or its CRC is wrong. */
    data class Frame(val type: Int, val seq: Int, val phoneMs: Long, val ttlMs: Int, val priority: Int,
                     val steps: List<BeltStep>, val config: BeltConfig?)

    fun parse(b: ByteArray): Frame? {
        if (b.size < HEADER + 1 || (b[0].toInt() and 0xFF) != MAGIC || b[1].toInt() != VERSION) return null
        if (crc8(b, b.size - 1) != (b[b.size - 1].toInt() and 0xFF)) return null
        val type = b[2].toInt(); val n = b[12].toInt() and 0xFF
        val seq = get16(b, 3); val phone = get32(b, 5); val ttl = get16(b, 9); val pri = b[11].toInt() and 0xFF
        return when (type) {
            PLAY -> {
                if (n !in 1..MAX_STEPS || b.size != HEADER + 6 * n + 1) return null
                val steps = (0 until n).map { i ->
                    val o = HEADER + 6 * i
                    val mask = b[o].toInt() and 0xFF
                    if (mask > Motor.ALL) return null
                    BeltStep(mask, b[o + 1].toInt() and 0xFF, get16(b, o + 2), get16(b, o + 4))
                }
                Frame(type, seq, phone, ttl, pri, steps, null)
            }
            CONFIG -> {
                if (n != 7 || b.size != HEADER + 10) return null
                Frame(type, seq, phone, 0, 0, emptyList(), BeltConfig(
                    b[HEADER].toInt() and 0xFF, (1..5).map { b[HEADER + it].toInt() and 0xFF },
                    b[HEADER + 6].toInt() and 0xFF, get16(b, HEADER + 7)))
            }
            SILENCE, HEARTBEAT -> if (b.size == HEADER + 1) Frame(type, seq, phone, 0, 0, emptyList(), null) else null
            else -> null
        }
    }

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0..3) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte() }
    private fun get16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun get32(b: ByteArray, o: Int): Long = (0..3).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
}

/**
 * Settings the phone sends to the belt. [maxDutyPct] caps every motor's PWM duty (100 for the 5 V motor modules on a
 * 5 V supply; 65 % keeps a 3 V coin motor near 3 V on 5 V). [trimPct] evens out motors that feel stronger or weaker
 * where they sit (a flank feels less than the navel). [linkTimeoutMs]: no frame for this long -> all motors off.
 */
data class BeltConfig(
    val intensityPct: Int = 100,
    val trimPct: List<Int> = listOf(100, 100, 100, 100, 100),
    val maxDutyPct: Int = 100,
    val linkTimeoutMs: Int = 1500,
) {
    init { require(trimPct.size == 5) }
}

/**
 * Reference model of the belt firmware's decisions (firmware/basir_belt/belt_core.h does the same in C++): which
 * frames are accepted, what each motor's duty is at a given moment, and the safety rules
 *  - malformed / wrong CRC / old or repeated seq -> dropped;
 *  - stale: a PLAY that can no longer start within its ttl (phone clock -> belt clock offset estimated from the
 *    smallest observed delay, so a late frame after a Bluetooth stall is recognised) -> dropped, never queued;
 *  - a PLAY of lower priority than the one playing -> dropped (not queued); equal or higher -> replaces it;
 *  - SILENCE stops everything; no frame for linkTimeoutMs (or a disconnect) -> everything off.
 */
class BeltPlayerModel(var config: BeltConfig = BeltConfig()) {
    enum class Result { PLAYING, CONFIGURED, SILENCED, HEARTBEAT, DROP_CRC, DROP_OLD_SEQ, DROP_STALE, DROP_LOWER_PRIORITY }

    private var lastSeq: Int? = null
    private var offset: Long? = null          // beltMs - phoneMs, the smallest seen (= the fastest delivery)
    private var lastFrameAt = Long.MIN_VALUE / 4
    private var playing: List<BeltStep> = emptyList()
    private var playPriority = 0
    private var startedAt = 0L
    var drops = 0; private set

    fun onConnect() { lastSeq = null; offset = null; lastFrameAt = Long.MIN_VALUE / 4; stopAll() }
    fun onDisconnect() = stopAll()

    fun receive(bytes: ByteArray, beltMs: Long): Result {
        val f = BeltProtocol.parse(bytes) ?: return drop(Result.DROP_CRC)
        val last = lastSeq
        if (last != null && ((f.seq - last) and 0xFFFF).let { it == 0 || it > 0x7FFF }) return drop(Result.DROP_OLD_SEQ)
        lastSeq = f.seq
        // clock offset: the minimum of (belt - phone) over the frames seen, allowed to creep up 1 ms per second (two
        // crystal clocks drift by far less), so one slow delivery never makes later on-time frames look stale and a
        // stall does not quickly become the new normal
        val sample = beltMs - (f.phoneMs and 0xFFFFFFFFL)
        offset = offset?.let { min(it + (beltMs - lastFrameAt).coerceAtLeast(0) / 1000, sample) } ?: sample
        lastFrameAt = beltMs
        return when (f.type) {
            BeltProtocol.HEARTBEAT -> Result.HEARTBEAT
            BeltProtocol.SILENCE -> { stopAll(); Result.SILENCED }
            BeltProtocol.CONFIG -> { config = f.config!!; Result.CONFIGURED }
            else -> {
                val age = sample - offset!!
                if (age > f.ttlMs) return drop(Result.DROP_STALE)
                if (isPlaying(beltMs) && f.priority < playPriority) return drop(Result.DROP_LOWER_PRIORITY)
                playing = f.steps; playPriority = f.priority; startedAt = beltMs
                Result.PLAYING
            }
        }
    }

    fun isPlaying(beltMs: Long) = playing.isNotEmpty() && beltMs - startedAt < playing.sumOf { (it.onMs + it.offMs).toLong() }

    /** PWM duty (0..255) of each motor M1..M5 at [beltMs]; checks the link watchdog first. */
    fun duties(beltMs: Long): IntArray {
        if (beltMs - lastFrameAt > config.linkTimeoutMs) stopAll()
        val out = IntArray(5)
        if (!isPlaying(beltMs)) return out
        var t = beltMs - startedAt
        for (s in playing) {
            if (t < s.onMs) {
                for (i in 0 until 5) if (s.mask and (1 shl i) != 0) out[i] = duty(s.amplitude, i)
                return out
            }
            t -= s.onMs
            if (t < s.offMs) return out
            t -= s.offMs
        }
        return out
    }

    fun duty(amplitude: Int, motor: Int): Int {
        val v = amplitude * config.intensityPct / 100.0 * config.trimPct[motor] / 100.0
        return min(v, 255.0 * config.maxDutyPct / 100.0).roundToInt().coerceIn(0, 255)
    }

    private fun stopAll() { playing = emptyList(); playPriority = 0 }
    private fun drop(r: Result): Result { drops++; return r }
}
