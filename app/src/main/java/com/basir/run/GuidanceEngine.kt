package com.basir.run

import android.content.Context
import com.basir.run.vision.LaneResult
import com.basir.run.vision.PathShape
import kotlin.math.abs

data class GuidanceState(
    val found: Boolean = false,
    /** Smoothed position across the path: 0 centred, +1 at the right edge, -1 at the left edge. */
    val offset: Float = 0f,
    /** 0 on course, 1 slight drift, 2 clear drift, 3 near the edge. */
    val level: Int = 0,
    val moveLeft: Boolean = false,
    val shape: PathShape = PathShape.Straight,
)

/**
 * Turns each camera result into cues. The further the runner drifts, the stronger, longer
 * and more frequent the vibration. A phone vibrates as a whole, so direction is coded by
 * rhythm (one pulse = move left, two pulses = move right) and by a tone in that ear.
 * The Basir Touch belt will later carry the same levels to a motor on the correct side.
 */
class GuidanceEngine(
    private val context: Context,
    private val tone: ToneGuide,
    private val speaker: Speaker,
    private val prefs: Prefs,
) {
    private var smoothed = 0f
    private var lastCueAt = 0L
    private var lostSince = 0L
    private var lastLostAlertAt = 0L
    private var shapeCandidate = PathShape.Straight
    private var shapeFrames = 0
    private var announcedShape = PathShape.Straight

    fun update(result: LaneResult, now: Long): GuidanceState {
        if (!result.found) {
            if (lostSince == 0L) lostSince = now
            if (now - lostSince > LOST_ALERT_MS && now - lastLostAlertAt > LOST_REPEAT_MS) {
                lastLostAlertAt = now
                if (prefs.vibrateOn) Haptics.wave(context, LOST_TIMINGS, LOST_AMPLITUDES)
                if (prefs.voiceOn) speaker.say("لا أرى الخط. تمهّل")
            }
            return GuidanceState(found = false, offset = smoothed, shape = announcedShape)
        }
        if (lostSince != 0L && now - lostSince > LOST_ALERT_MS && prefs.voiceOn) speaker.say("وجدت الخط")
        lostSince = 0L

        smoothed += SMOOTHING * (result.offset - smoothed)
        val magnitude = abs(smoothed)
        val level = when {
            magnitude < LEVEL_1 -> 0
            magnitude < LEVEL_2 -> 1
            magnitude < LEVEL_3 -> 2
            else -> 3
        }
        val moveLeft = smoothed > 0f
        if (level > 0 && now - lastCueAt >= PERIOD_MS[level]) {
            cue(level, moveLeft)
            lastCueAt = now
        }
        trackShape(result.shape)
        return GuidanceState(true, smoothed, level, moveLeft, announcedShape)
    }

    private fun cue(level: Int, moveLeft: Boolean) {
        if (prefs.vibrateOn) {
            val on = PULSE_MS[level]
            val amp = AMPLITUDE[level]
            if (moveLeft) Haptics.wave(context, longArrayOf(0, on), intArrayOf(0, amp))
            else Haptics.wave(context, longArrayOf(0, on, PULSE_GAP_MS, on), intArrayOf(0, amp, 0, amp))
        }
        if (prefs.guideTones) tone.play(if (moveLeft) -1f else 1f, durationMs = TONE_MS, frequency = TONE_HZ[level])
    }

    // Announce a bend only once it has been seen for several frames in a row.
    private fun trackShape(shape: PathShape) {
        if (shape == shapeCandidate) shapeFrames++ else {
            shapeCandidate = shape
            shapeFrames = 1
        }
        if (shapeFrames == SHAPE_STABLE_FRAMES && shape != announcedShape) {
            announcedShape = shape
            if (prefs.voiceOn) speaker.say(
                when (shape) {
                    PathShape.CurveLeft -> "منحنى لليسار"
                    PathShape.CurveRight -> "منحنى لليمين"
                    PathShape.Straight -> "المسار مستقيم"
                }
            )
        }
    }

    companion object {
        private const val SMOOTHING = 0.35f
        private const val LEVEL_1 = 0.15f
        private const val LEVEL_2 = 0.35f
        private const val LEVEL_3 = 0.6f
        private val PERIOD_MS = longArrayOf(0, 1000, 550, 280)
        private val PULSE_MS = longArrayOf(0, 70, 110, 160)
        private val AMPLITUDE = intArrayOf(0, 90, 170, 255)
        private const val PULSE_GAP_MS = 70L
        private const val TONE_MS = 110
        private val TONE_HZ = doubleArrayOf(0.0, 520.0, 660.0, 880.0)
        private const val LOST_ALERT_MS = 1500L
        private const val LOST_REPEAT_MS = 4000L
        private val LOST_TIMINGS = longArrayOf(0, 250, 100, 250, 100, 250)
        private val LOST_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
        private const val SHAPE_STABLE_FRAMES = 8
    }
}
