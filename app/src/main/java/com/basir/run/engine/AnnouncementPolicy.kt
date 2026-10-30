package com.basir.run.engine

import com.basir.run.vision.Side

sealed interface Output {
    data class Say(val phrase: Phrase, val interrupt: Boolean) : Output
    /** A short tone in the ear on the side to move towards (optional). */
    data class Tone(val side: Side, val level: Int) : Output
}

/**
 * Decides when to speak, and when to play the optional direction tone. Vibration is decided
 * separately (HapticBridge and the engine's HapticScheduler); speech is kept for hazards and
 * losing the path, unless the user turns on extra spoken guidance.
 * - Stops interrupt anything and repeat every 3 s.
 * - Warnings repeat every 5 s.
 * - Tones follow the steering: every 1.2 s for a slight drift, faster for a larger one, and a
 *   reversal of direction is held back briefly so left and right never alternate within a moment.
 */
class AnnouncementPolicy(var verbose: Boolean = false) {

    private var lastKey: Any? = null
    private var lastSeverity = Severity.Silent
    private var lastSpokenAt = NEVER
    private var lastSteerAt = NEVER
    private var lastSteerSide: Side? = null
    private var lastCurve: Side? = null
    private var lastImportant: Phrase? = null

    fun reset() {
        lastKey = null
        lastSeverity = Severity.Silent
        lastSpokenAt = NEVER
        lastSteerAt = NEVER
        lastSteerSide = null
        lastCurve = null
    }

    fun next(d: Decision, now: Long): List<Output> {
        val out = ArrayList<Output>(3)
        val key = Triple(d.cue, d.moveTo, d.level)
        val changed = key != lastKey
        val since = now - lastSpokenAt
        val rising = d.severity.ordinal > lastSeverity.ordinal
        val phrase = Phrase.forCue(d)

        fun say(p: Phrase, interrupt: Boolean, important: Boolean) {
            out += Output.Say(p, interrupt)
            lastSpokenAt = now
            if (important) lastImportant = p
        }

        when (d.severity) {
            Severity.Critical -> if (phrase != null && (changed || since >= CRITICAL_REPEAT_MS)) {
                say(phrase, interrupt = true, important = true)
            }
            Severity.Warning -> {
                // A larger drift is a steering matter: firmer pulses, words only on request.
                val spoken = d.cue != Cue.NearEdge || verbose
                if (phrase != null && spoken && ((changed && (rising || since >= MIN_GAP_MS)) || since >= WARNING_REPEAT_MS)) {
                    say(phrase, interrupt = rising, important = true)
                }
                if (d.cue == Cue.NearEdge && phrase != null) lastImportant = phrase
            }
            Severity.Correction -> {
                if (phrase != null) lastImportant = phrase
                if (verbose && phrase != null && ((changed && since >= MIN_GAP_MS) || since >= CORRECTION_REPEAT_MS)) {
                    say(phrase, interrupt = false, important = true)
                }
            }
            Severity.Info -> if (verbose && phrase != null) {
                val confirmsReturn = phrase == Phrase.Centered && changed &&
                    lastSeverity.ordinal >= Severity.Correction.ordinal && since >= MIN_GAP_MS
                if (confirmsReturn || (d.cue == Cue.CurveAhead && changed && since >= MIN_GAP_MS)) say(phrase, interrupt = false, important = false)
            }
            Severity.Silent -> Unit
        }

        lastCurve = d.curve

        // Direction tones, only while the visible path supports a direction.
        val side = d.moveTo
        if (side != null && d.level in 1..3 && d.severity != Severity.Critical) {
            val reversal = lastSteerSide != null && side != lastSteerSide && now - lastSteerAt < REVERSAL_HOLD_MS
            if (!reversal && now - lastSteerAt >= STEER_PERIOD_MS[d.level]) {
                out += Output.Tone(side, d.level)
                lastSteerAt = now
                lastSteerSide = side
            }
        }

        lastKey = key
        lastSeverity = d.severity
        return out
    }

    /** The last instruction that mattered, for the "repeat" action. */
    fun repeatLast(): Output.Say? = lastImportant?.let { Output.Say(it, interrupt = true) }

    companion object {
        private const val NEVER = -1_000_000L
        private const val CRITICAL_REPEAT_MS = 3000L
        private const val WARNING_REPEAT_MS = 5000L
        private const val CORRECTION_REPEAT_MS = 4000L
        private const val MIN_GAP_MS = 1500L
        private const val REVERSAL_HOLD_MS = 1200L
        private val STEER_PERIOD_MS = longArrayOf(0, 1200, 800, 550)
    }
}
