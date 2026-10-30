package run.basir.engine

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A vibration waveform in the shape Android's VibrationEffect.createWaveform(timings, amplitudes,
 * -1) takes: alternating segment durations (ms) with an amplitude (0 = off, 1..255) each.
 */
data class HapticPattern(
    val name: String,
    val timingsMs: LongArray,
    val amplitudes: IntArray,
    val priority: Int,
) {
    val durationMs: Long get() = timingsMs.sum()
    override fun equals(other: Any?) = other is HapticPattern && name == other.name &&
        timingsMs.contentEquals(other.timingsMs) && amplitudes.contentEquals(other.amplitudes)
    override fun hashCode() = name.hashCode()
}

/** Where patterns are played: the Android vibrator in the app, a recorder in tests. */
interface HapticOutput {
    val hasAmplitudeControl: Boolean
    fun play(pattern: HapticPattern)
    fun cancel()
}

/**
 * The Basir-Run haptic language — learnable, non-contradictory patterns:
 *
 * | cue                 | pattern                                   |
 * |---------------------|-------------------------------------------|
 * | correct LEFT        | 1 short pulse                             |
 * | correct RIGHT       | 2 short pulses                            |
 * |   low/medium/high   | stronger (or longer without amplitude)    |
 * | bend LEFT / RIGHT   | 1 / 2 long soft pulses (calm repeat)      |
 * | obstacle            | 3 quick strong pulses                     |
 * | STOP                | long strong buzz + 2-pulse confirmation   |
 * | on path (optional)  | faint tick                                |
 *
 * Left = one, right = two everywhere, so a bend and a correction toward the same side share
 * their rhythm; short = correct now, long and soft = prepare. Amplitude scales with the level
 * and the analysis confidence; phones without amplitude control get the level through pulse
 * length instead.
 */
class HapticLanguage(private val amplitudeControl: Boolean = true) {

    companion object {
        const val P_STOP = 5
        const val P_OBSTACLE = 4
        const val P_CORRECT = 3
        const val P_CURVE = 2
        const val P_ON_PATH = 1
        private val AMP = mapOf(Level.LOW to 90, Level.MEDIUM to 170, Level.HIGH to 255)
        private val CURVE_AMP = mapOf(Level.LOW to 70, Level.MEDIUM to 110, Level.HIGH to 160)
        private val LEN_SCALE = mapOf(Level.LOW to 0.6, Level.MEDIUM to 1.0, Level.HIGH to 1.6) // no amplitude control
    }

    fun forCommand(cmd: GuidanceCommand): HapticPattern? {
        return when (cmd.kind) {
            CommandKind.STOP -> stop()
            CommandKind.OBSTACLE -> obstacle()
            CommandKind.CORRECT -> correct(cmd.side ?: return null, cmd.level ?: Level.LOW, cmd.confidence)
            CommandKind.CURVE -> curve(cmd.side ?: return null, cmd.level ?: Level.LOW, cmd.confidence)
            CommandKind.ON_PATH -> onPath()
        }
    }

    fun stop() = pattern("stop", P_STOP, listOf(1200L to 255, 250L to 0, 200L to 255, 150L to 0, 200L to 255))

    fun obstacle() = pattern("obstacle", P_OBSTACLE, listOf(120L to 255, 80L to 0, 120L to 255, 80L to 0, 120L to 255))

    fun correct(side: Side, level: Level, confidence: Double = 1.0): HapticPattern {
        val amp = scaled(AMP.getValue(level), confidence)
        val len = if (amplitudeControl) 150L else (160 * LEN_SCALE.getValue(level)).toLong()
        val pulses = if (side == Side.LEFT) 1 else 2
        return pattern("correct_${side.name.lowercase()}_${level.name.lowercase()}", P_CORRECT, pulses(pulses, len, 150L, amp))
    }

    fun curve(side: Side, level: Level, confidence: Double = 1.0): HapticPattern {
        val amp = scaled(CURVE_AMP.getValue(level), confidence)
        val len = if (amplitudeControl) 400L else (360 * LEN_SCALE.getValue(level)).toLong()
        val pulses = if (side == Side.LEFT) 1 else 2
        return pattern("curve_${side.name.lowercase()}_${level.name.lowercase()}", P_CURVE, pulses(pulses, len, 220L, amp))
    }

    fun onPath() = pattern("on_path", P_ON_PATH, listOf(60L to 50))

    /** Every cue with its meaning, for the learning screen and the recognition quiz. */
    fun catalog(): List<Triple<HapticPattern, String, String>> = buildList {
        add(Triple(correct(Side.LEFT, Level.LOW), "تصحيح بسيط إلى اليسار", "Small correction left"))
        add(Triple(correct(Side.RIGHT, Level.LOW), "تصحيح بسيط إلى اليمين", "Small correction right"))
        add(Triple(correct(Side.LEFT, Level.MEDIUM), "تصحيح متوسط إلى اليسار", "Medium correction left"))
        add(Triple(correct(Side.RIGHT, Level.HIGH), "تصحيح قوي إلى اليمين", "Strong correction right"))
        add(Triple(curve(Side.LEFT, Level.LOW), "انحناء تدريجي إلى اليسار قادم", "Gentle bend left ahead"))
        add(Triple(curve(Side.RIGHT, Level.HIGH), "منعطف حاد إلى اليمين قادم", "Sharp bend right ahead"))
        add(Triple(obstacle(), "عائق محتمل أمامك", "Possible obstacle ahead"))
        add(Triple(stop(), "توقّف فوراً", "Stop now"))
    }

    private fun scaled(amp: Int, confidence: Double) =
        if (!amplitudeControl) 255 else (amp * (0.6 + 0.4 * confidence.coerceIn(0.0, 1.0))).roundToInt().coerceIn(1, 255)

    private fun pulses(n: Int, on: Long, gap: Long, amp: Int) = buildList {
        repeat(n) { i ->
            if (i > 0) add(gap to 0)
            add(on to amp)
        }
    }

    private fun pattern(name: String, priority: Int, segs: List<Pair<Long, Int>>): HapticPattern {
        // createWaveform starts with an "off" segment
        val t = LongArray(segs.size + 1)
        val a = IntArray(segs.size + 1)
        segs.forEachIndexed { i, (ms, amp) -> t[i + 1] = ms; a[i + 1] = if (amplitudeControl || amp == 0) amp else 255 }
        return HapticPattern(name, t, a, priority)
    }
}

data class SchedulerConfig(
    val stopRepeatMs: Long = 2400,
    val stopReleaseFrames: Int = 8,       // STOP holds until the danger is gone for this many frames
    val obstacleRepeatMs: Long = 1200,
    val correctRepeatMs: Map<Level, Long> = mapOf(Level.LOW to 1100, Level.MEDIUM to 800, Level.HIGH to 650),
    val curveRepeatMs: Long = 1600,
    val onPathRepeatMs: Long = 3500,
    val enableOnPathTick: Boolean = true,
    val oppositeSideGuardMs: Long = 500,  // no left right after right (and back) unless HIGH
)

/**
 * Decides what actually vibrates and when: priorities (STOP > obstacle > correction > bend >
 * on-path), STOP pre-empts anything playing and is latched until the danger has cleared for a
 * while, repeats are rate-limited per cue, a pattern is never cut short by a lower-priority
 * one, and opposite-side cues are not fired back to back.
 */
class HapticScheduler(
    private val output: HapticOutput,
    private val language: HapticLanguage = HapticLanguage(output.hasAmplitudeControl),
    private val config: SchedulerConfig = SchedulerConfig(),
) {
    private var playingUntil = 0L
    private var playingPriority = 0
    private val lastPlayed = HashMap<String, Long>()
    private var stopLatched = false
    private var clearFrames = 0
    private var lastSide: Side? = null
    private var lastSideAt = Long.MIN_VALUE / 2

    /** Feed one guidance command per frame; returns the pattern started now, if any. */
    fun onCommand(cmd: GuidanceCommand, nowMs: Long): HapticPattern? {
        if (cmd.kind == CommandKind.STOP) {
            clearFrames = 0
            if (!stopLatched || since("stop", nowMs) >= config.stopRepeatMs) {
                stopLatched = true
                return start(language.stop(), nowMs, preempt = true)
            }
            return null
        }
        if (stopLatched) {
            if (++clearFrames < config.stopReleaseFrames) return null
            stopLatched = false
        }
        val pattern = language.forCommand(cmd) ?: return null
        val busy = nowMs < playingUntil
        if (busy && pattern.priority <= playingPriority) return null
        val key = when (cmd.kind) {
            CommandKind.CORRECT, CommandKind.CURVE -> "${cmd.kind}_${cmd.side}"
            else -> cmd.kind.name
        }
        val repeat = when (cmd.kind) {
            CommandKind.OBSTACLE -> config.obstacleRepeatMs
            CommandKind.CORRECT -> config.correctRepeatMs.getValue(cmd.level ?: Level.LOW)
            CommandKind.CURVE -> config.curveRepeatMs
            CommandKind.ON_PATH -> if (config.enableOnPathTick) config.onPathRepeatMs else return null
            CommandKind.STOP -> config.stopRepeatMs
        }
        if (since(key, nowMs) < repeat) return null
        if (cmd.side != null && cmd.side != lastSide && nowMs - lastSideAt < config.oppositeSideGuardMs && cmd.level != Level.HIGH) {
            return null
        }
        if (cmd.side != null) { lastSide = cmd.side; lastSideAt = nowMs }
        lastPlayed[key] = nowMs
        return start(pattern, nowMs, preempt = busy)
    }

    private fun since(key: String, now: Long) = now - (lastPlayed[key] ?: Long.MIN_VALUE / 2)

    private fun start(p: HapticPattern, now: Long, preempt: Boolean): HapticPattern {
        if (preempt) output.cancel()
        output.play(p)
        if (p.name == "stop") lastPlayed["stop"] = now
        playingUntil = now + max(1L, p.durationMs)
        playingPriority = p.priority
        return p
    }
}
