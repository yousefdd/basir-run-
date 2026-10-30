package com.basir.run.engine

import com.basir.run.vision.Side

sealed interface Output {
    data class Say(val phrase: Phrase, val interrupt: Boolean) : Output
    /** level 2 makes steering pulses firmer for a larger drift. */
    data class Buzz(val haptic: Haptic, val level: Int = 1) : Output
    /** A short tone in the ear on the side to move towards (optional). */
    data class Tone(val side: Side, val level: Int) : Output
}

/**
 * Decides when to vibrate and when to speak. Vibration carries the guidance; speech is kept for
 * the start, hazards and losing the path, unless the user turns on extra spoken guidance.
 * - Stops interrupt anything and repeat every 3 s with the long vibration (three quick pulses
 *   for an obstacle).
 * - Warnings repeat every 5 s with three quick pulses.
 * - Steering pulses repeat while the drift lasts: every 1.2 s for a slight drift, every 0.6 s
 *   for a larger one. A reversal of direction is held back briefly so left and right never
 *   alternate within a moment.
 * - A bend is signalled once with the rising swell when it first appears.
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
                out += Output.Buzz(if (d.cue in OBSTACLE_CUES) Haptic.Attention else Haptic.Stop)
            }
            Severity.Warning -> {
                // A larger drift is a steering matter: firmer pulses, words only on request.
                val spoken = d.cue != Cue.NearEdge || verbose
                if (phrase != null && spoken && ((changed && (rising || since >= MIN_GAP_MS)) || since >= WARNING_REPEAT_MS)) {
                    say(phrase, interrupt = rising, important = true)
                    out += Output.Buzz(Haptic.Attention)
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

        // A bend gets the rising swell once, when it first appears.
        if (d.curve != null && d.curve != lastCurve && d.severity != Severity.Critical) out += Output.Buzz(Haptic.BendAhead)
        lastCurve = d.curve

        // Steering pulses, only while the visible path supports a direction.
        val side = d.moveTo
        if (side != null && d.level in 1..2 && d.severity != Severity.Critical) {
            val reversal = lastSteerSide != null && side != lastSteerSide && now - lastSteerAt < REVERSAL_HOLD_MS
            if (!reversal && now - lastSteerAt >= STEER_PERIOD_MS[d.level]) {
                out += Output.Buzz(if (side == Side.Left) Haptic.SteerLeft else Haptic.SteerRight, d.level)
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
        private val STEER_PERIOD_MS = longArrayOf(0, 1200, 600)
        /** Hazards in front get the obstacle pattern (three quick pulses); everything else critical gets the stop pattern. */
        private val OBSTACLE_CUES = setOf(Cue.Obstacle, Cue.HeadLevelStop, Cue.DropStop)
    }
}
