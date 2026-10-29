package com.basir.run.engine

import com.basir.run.vision.Side

sealed interface Output {
    data class Say(val phrase: Phrase, val interrupt: Boolean) : Output
    data class Buzz(val haptic: Haptic) : Output
    /** A short tone in the ear on the side to move towards; louder in pitch with level. */
    data class Tone(val side: Side, val level: Int) : Output
}

/**
 * Decides when to speak and vibrate, so instructions are short, not constant, and stops come
 * first. Stops interrupt anything being said and repeat every 3 s while they last; warnings
 * repeat every 5 s; corrections when they change or every 4 s; "centred" is said once after a
 * correction and otherwise only as a 20 s sign that guidance is still running.
 */
class AnnouncementPolicy {

    private var lastKey: Any? = null
    private var lastSeverity = Severity.Silent
    private var lastSpokenAt = NEVER
    private var lastSteerAt = NEVER
    private var lastCurve: Side? = null
    private var lastImportant: Phrase? = null

    fun reset() {
        lastKey = null
        lastSeverity = Severity.Silent
        lastSpokenAt = NEVER
        lastSteerAt = NEVER
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
                out += Output.Buzz(if (d.cue == Cue.Obstacle) Haptic.Hazard else Haptic.Stop)
            }
            Severity.Warning -> if (phrase != null && ((changed && (rising || since >= MIN_GAP_MS)) || since >= WARNING_REPEAT_MS)) {
                say(phrase, interrupt = rising, important = true)
                out += Output.Buzz(if (d.cue == Cue.ObstacleNear) Haptic.Hazard else Haptic.Attention)
            }
            Severity.Correction -> if (phrase != null && ((changed && since >= MIN_GAP_MS) || since >= CORRECTION_REPEAT_MS)) {
                say(phrase, interrupt = false, important = true)
            }
            Severity.Info -> if (phrase != null) {
                val confirmsReturn = phrase == Phrase.Centered && changed &&
                    lastSeverity.ordinal >= Severity.Correction.ordinal && since >= MIN_GAP_MS
                val newBend = d.cue == Cue.CurveAhead && changed && since >= MIN_GAP_MS
                if (confirmsReturn || newBend || since >= HEARTBEAT_MS) say(phrase, interrupt = false, important = false)
                if (newBend) out += Output.Buzz(Haptic.Attention)
            }
            Severity.Silent -> Unit
        }

        // A bend first seen while correcting is still worth announcing once.
        if (d.curve != null && d.curve != lastCurve && d.severity == Severity.Correction && out.none { it is Output.Say }) {
            Phrase.curve(d.curve)?.let { say(it, interrupt = false, important = false) }
        }
        lastCurve = d.curve

        // Steady steering pulses while drifting; a stop gets its own long pattern instead.
        val side = d.moveTo
        if (side != null && d.level in 1..2 && d.severity != Severity.Critical && now - lastSteerAt >= STEER_PERIOD_MS[d.level]) {
            out += Output.Buzz(if (d.level == 1) Haptic.Correction else Haptic.Attention)
            out += Output.Tone(side, d.level)
            lastSteerAt = now
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
        private const val HEARTBEAT_MS = 20_000L
        private const val MIN_GAP_MS = 1500L
        private val STEER_PERIOD_MS = longArrayOf(0, 1000, 650)
    }
}
