package com.basir.run

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basir.run.engine.AnnouncementPolicy
import com.basir.run.engine.Cue
import com.basir.run.engine.Decision
import com.basir.run.engine.DecisionEngine
import com.basir.run.engine.EngineInput
import com.basir.run.engine.Haptic
import com.basir.run.engine.Output
import com.basir.run.engine.Phrase
import com.basir.run.engine.Severity
import com.basir.run.engine.SystemState
import com.basir.run.sensors.MotionMonitor
import com.basir.run.vision.FrameSnapshot
import com.basir.run.vision.Side

data class SessionSummary(
    val durationMs: Long,
    val trackedMs: Long,
    val unclearMs: Long,
    val stopAlerts: Int,
    val obstacleAlerts: Int,
    val frames: Int,
    val processingP50Ms: Float,
    val processingP90Ms: Float,
) {
    val trackedShare: Float get() = if (durationMs <= 0) 0f else trackedMs.toFloat() / durationMs
}

/**
 * One guided run. Feeds every camera result and a regular timer tick into the decision engine,
 * so a stalled camera is noticed even when no frames arrive, and turns decisions into speech,
 * vibration and tones. Measures what happened for the summary.
 */
class GuidanceSession(
    context: Context,
    private val haptics: HapticEngine,
    private val voice: Voice,
    private val tone: ToneGuide,
    private val prefs: Prefs,
    private val motion: MotionMonitor,
    /** Camera angle measured when the path was confirmed; the angle check is relative to it. */
    private val calibratedPitchDeg: Float?,
) {
    private val engine = DecisionEngine()
    private val policy = AnnouncementPolicy(verbose = prefs.verboseVoice)

    var snapshot by mutableStateOf<FrameSnapshot?>(null)
        private set
    var decision by mutableStateOf(Decision(SystemState.PathTracked, Severity.Silent, Cue.None))
        private set
    var paused by mutableStateOf(false)
        private set

    private var startedAt = -1L
    private var lastEvalAt = -1L
    private var trackedMs = 0L
    private var unclearMs = 0L
    private var stopAlerts = 0
    private var obstacleAlerts = 0
    private var frames = 0
    private val p50 = ArrayList<Float>()
    private val p90 = ArrayList<Float>()
    private var lastCue = Cue.None
    private val log = SessionLog(context)
    var logFile: java.io.File? = null
        private set

    /** The run screen has already confirmed the path with the confirmation pulse and voice. */
    fun start(now: Long) {
        startedAt = now
        lastEvalAt = now
        engine.reset()
        policy.reset()
        logFile = log.open(now)
    }

    fun onSnapshot(s: FrameSnapshot, now: Long) {
        snapshot = s
        frames++
        if (frames % 30 == 0) {
            p50 += s.processingP50Ms
            p90 += s.processingP90Ms
        }
        evaluate(now)
    }

    fun tick(now: Long) = evaluate(now)

    fun pause() {
        if (paused) return
        paused = true
        voice.say(Phrase.SessionPaused, interrupt = true)
    }

    fun resume(now: Long) {
        if (!paused) return
        paused = false
        engine.reset()
        policy.reset()
        lastEvalAt = now
        voice.say(Phrase.SessionResumed, interrupt = true)
    }

    fun repeatLast() {
        policy.repeatLast()?.let { voice.say(it.phrase, interrupt = true) }
    }

    /** Closes the log if the screen goes away without a normal finish (for example an emergency). */
    fun close() = log.close()

    fun finish(now: Long): SessionSummary {
        voice.say(Phrase.SessionEnded, interrupt = true)
        haptics.play(Haptic.Confirm, now = now)
        log.close()
        return SessionSummary(
            durationMs = (now - startedAt).coerceAtLeast(0),
            trackedMs = trackedMs,
            unclearMs = unclearMs,
            stopAlerts = stopAlerts,
            obstacleAlerts = obstacleAlerts,
            frames = frames,
            processingP50Ms = p50.average().toFloat().takeIf { !it.isNaN() } ?: 0f,
            processingP90Ms = p90.maxOrNull() ?: 0f,
        )
    }

    private fun evaluate(now: Long) {
        if (startedAt < 0) return
        val s = snapshot
        val d = engine.decide(
            EngineInput(
                nowMs = now,
                paused = paused,
                frameAgeMs = if (s == null) NO_FRAME_AGE else now - s.timeMs,
                quality = s?.quality,
                path = s?.path,
                obstacle = s?.obstacle ?: com.basir.run.vision.ObstacleLevel.None,
                obstacleKind = s?.hazardKind ?: com.basir.run.vision.HazardKind.Object,
                depthStale = s?.depthStale ?: false,
                processingP90Ms = s?.processingP90Ms ?: 0f,
                cameraPitchDeg = motion.pitchBelowHorizonDeg,
                calibratedPitchDeg = calibratedPitchDeg,
            )
        )
        decision = d
        log.write(now, d, s, paused)

        val dt = (now - lastEvalAt).coerceIn(0, 1000)
        lastEvalAt = now
        if (!paused) {
            if (d.state in TRACKED_STATES) trackedMs += dt else unclearMs += dt
        }
        if (d.cue != lastCue) {
            if (d.severity == Severity.Critical) stopAlerts++
            if (d.cue in setOf(Cue.Obstacle, Cue.ObstacleNear, Cue.HeadLevelStop, Cue.DropStop)) obstacleAlerts++
            lastCue = d.cue
        }

        for (out in policy.next(d, now)) when (out) {
            // Stops and warnings are spoken even with voice guidance turned off.
            is Output.Say -> voice.say(out.phrase, out.interrupt)
            is Output.Buzz -> haptics.play(out.haptic, out.level, now)
            is Output.Tone -> if (prefs.guideTones) {
                tone.play(if (out.side == Side.Left) -1f else 1f, durationMs = 110, frequency = TONE_HZ[out.level.coerceIn(1, 3)])
            }
        }
    }

    companion object {
        private const val NO_FRAME_AGE = 999_999L
        private val TRACKED_STATES = setOf(SystemState.PathTracked, SystemState.PathCurving, SystemState.Drifting)
        private val TONE_HZ = doubleArrayOf(0.0, 520.0, 660.0, 880.0)
    }
}
