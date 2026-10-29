package com.basir.run.engine

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Vibration patterns, independent of the vision code. Default meanings (to be checked with
 * users, and taught before the first run):
 * - one short pulse: small correction of direction
 * - two short pulses: attention, a bend or the edge of the path is near
 * - three quick pulses: possible hazard ahead (always with a spoken warning)
 * - one long vibration: stop now
 * Higher priority patterns cut lower ones short; a stop is never interrupted by a correction.
 */
enum class Haptic(val priority: Int) {
    Correction(1),
    Attention(2),
    Hazard(3),
    Stop(4),
    SessionStart(2),
    Calibrated(2),
    SessionEnd(2),
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
     * timings alternate off and on, starting with an off delay. [strength] scales amplitude,
     * [durationScale] scales the length of each pulse; the gaps stay fixed so rhythm is kept.
     */
    fun waveform(h: Haptic, strength: Float, durationScale: Float): Waveform {
        val (pulses, base) = when (h) {
            Haptic.Correction -> listOf(0L, 90L) to 150
            Haptic.Attention -> listOf(0L, 90L, 110L, 90L) to 190
            Haptic.Hazard -> listOf(0L, 70L, 70L, 70L, 70L, 70L) to 255
            Haptic.Stop -> listOf(0L, 900L) to 255
            Haptic.SessionStart -> listOf(0L, 150L, 100L, 400L) to 180
            Haptic.Calibrated -> listOf(0L, 400L, 120L, 150L) to 180
            Haptic.SessionEnd -> listOf(0L, 400L, 200L, 400L) to 180
        }
        val s = strength.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        val d = durationScale.coerceIn(MIN_DURATION, MAX_DURATION)
        val timings = LongArray(pulses.size) { i -> if (i % 2 == 1) (pulses[i] * d).roundToLong() else pulses[i] }
        val amplitudes = IntArray(pulses.size) { i -> if (i % 2 == 1) (base * s).roundToInt().coerceIn(1, 255) else 0 }
        return Waveform(timings, amplitudes)
    }
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
