package com.basir.run.engine

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The vibration language, independent of the vision code. Vibration is the main channel of
 * guidance; speech is kept for the start, for hazards and for losing the path.
 *
 * Starting design, to be checked with users and taught before the first run:
 * - one short pulse: move a little to the left
 * - two short pulses: move a little to the right
 * - a rising swell: a bend is coming
 * - three quick pulses: obstacle or something that needs attention now
 * - one long vibration: stop running now
 * - a soft tick-tock: confirmation (calibration done, session started or ended)
 *
 * A higher priority pattern cuts a lower one short; a steering pulse never interrupts a stop.
 */
enum class Haptic(val priority: Int) {
    SteerLeft(1),
    SteerRight(1),
    BendAhead(2),
    Confirm(2),
    Attention(3),
    Stop(4),
}

class Waveform(val timings: LongArray, val amplitudes: IntArray) {
    val totalMs: Long get() = timings.sum()
}

object HapticPatterns {

    const val MIN_STRENGTH = 0.3f
    const val MAX_STRENGTH = 1f
    const val MIN_DURATION = 0.6f
    const val MAX_DURATION = 1.6f

    /**
     * timings alternate off and on, starting with an off delay; consecutive "on" segments with
     * no gap make one continuous vibration with changing strength (the swell).
     * [strength] scales amplitude, [durationScale] the length of each pulse; gaps stay fixed so
     * the rhythm is kept. [level] 2 makes steering pulses firmer for a larger drift.
     */
    fun waveform(h: Haptic, strength: Float, durationScale: Float, level: Int = 1): Waveform {
        // (duration ms, amplitude 0..255); amplitude 0 is a gap.
        val segments: List<Pair<Long, Int>> = when (h) {
            Haptic.SteerLeft -> listOf(90L to 160)
            Haptic.SteerRight -> listOf(90L to 160, 110L to 0, 90L to 160)
            Haptic.BendAhead -> listOf(150L to 70, 150L to 140, 150L to 230)
            Haptic.Attention -> listOf(70L to 255, 70L to 0, 70L to 255, 70L to 0, 70L to 255)
            Haptic.Stop -> listOf(900L to 255)
            Haptic.Confirm -> listOf(40L to 120, 90L to 0, 140L to 180)
        }
        val gentle = h.priority == 1 && level < 2
        val s = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH) * if (gentle) LEVEL_ONE_SCALE else 1f
        val d = durationScale.coerceIn(MIN_DURATION, MAX_DURATION)
        val timings = LongArray(segments.size + 1)
        val amplitudes = IntArray(segments.size + 1)
        segments.forEachIndexed { i, (ms, amp) ->
            timings[i + 1] = if (amp > 0) (ms * d).roundToLong() else ms
            amplitudes[i + 1] = if (amp > 0) (amp * s).roundToInt().coerceIn(1, 255) else 0
        }
        return Waveform(timings, amplitudes)
    }

    /** Steering at level 1 is gentler than at level 2; other patterns use full strength. */
    private const val LEVEL_ONE_SCALE = 0.8f
}

/** Decides whether a new pattern may play now, so stops always win over corrections. */
class HapticScheduler {
    private var busyUntil = 0L
    private var busyPriority = 0

    sealed interface Verdict {
        data object Skip : Verdict
        /** cancelCurrent: stop what is vibrating before starting this pattern. */
        data class Play(val cancelCurrent: Boolean) : Verdict
    }

    fun decide(h: Haptic, durationMs: Long, now: Long): Verdict {
        val busy = now < busyUntil
        if (busy && h.priority < busyPriority) return Verdict.Skip
        busyUntil = now + durationMs
        busyPriority = h.priority
        return Verdict.Play(cancelCurrent = busy)
    }
}
