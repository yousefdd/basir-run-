package com.basir.run.engine

import com.basir.run.vision.FrameQuality
import com.basir.run.vision.PathStatus
import com.basir.run.vision.Side
import com.basir.run.vision.TrackedPath
import kotlin.math.abs

enum class SystemState {
    ReadyToCalibrate,
    CalibrationIncomplete,
    PathTracked,
    PathCurving,
    Drifting,
    ObstacleAhead,
    PoorVisibility,
    BoundariesUnclear,
    CameraBlocked,
    HighLatency,
    TrackingLost,
    StopAndCheck,
    Paused,
    Emergency,
}

enum class Severity { Silent, Info, Correction, Warning, Critical }

/** What the runner should be told. Each cue maps to one fixed phrase (see Phrases). */
enum class Cue {
    None,
    Centered,
    Correct,
    NearEdge,
    AtEdgeStop,
    CurveAhead,
    Obstacle,
    PathUnclear,
    EdgeOnlyCaution,
    EdgeNearStop,
    LostStop,
    CameraStop,
    AngleStop,
    VisibilityStop,
    LatencySlow,
    LatencyStop,
    Paused,
}

data class Decision(
    val state: SystemState,
    val severity: Severity,
    val cue: Cue,
    /** Side the runner should move towards; only set when the visible path supports it. */
    val moveTo: Side? = null,
    /** 1 slight, 2 clear, 3 at the edge. */
    val level: Int = 0,
    /** Direction of a bend ahead, when one is seen. */
    val curve: Side? = null,
)

data class EngineInput(
    val nowMs: Long,
    val paused: Boolean = false,
    /** Time since the last processed frame. */
    val frameAgeMs: Long,
    val quality: FrameQuality?,
    val path: TrackedPath?,
    /** An obstacle confirmed inside the runnable area on recent frames. */
    val obstacleAhead: Boolean = false,
    val processingP90Ms: Float = 0f,
    /** Camera angle below the horizon from the gravity sensor, or null if unknown. */
    val cameraPitchDeg: Float? = null,
)

data class EngineConfig(
    val maxFrameAgeMs: Long = 700,
    val minConfidence: Float = 0.5f,
    val unclearCautionMs: Long = 600,
    val lostStopMs: Long = 1500,
    val latencyWarnMs: Float = 180f,
    val latencyStopMs: Float = 350f,
    val pitchMinDeg: Float = 15f,
    val pitchMaxDeg: Float = 75f,
    val angleGraceMs: Long = 1000,
    val slight: Float = 0.2f,
    val nearEdge: Float = 0.45f,
    val atEdge: Float = 0.75f,
    val curveThreshold: Float = 0.15f,
    /** EdgeOnly: stop when the single visible edge is this close to the runner (frame fraction). */
    val edgeStopDistance: Float = 0.12f,
)

/**
 * Turns measurements into one state and one instruction. Independent of the screen, the speaker
 * and the camera, so every rule can be tested. Rules are checked in order of danger; the first
 * that applies wins. Sideways directions are given only while the path itself is tracked with
 * confidence, so the side the runner is sent to is part of the visible runnable area.
 */
class DecisionEngine(private val cfg: EngineConfig = EngineConfig()) {

    private var unclearSince = -1L
    private var angleBadSince = -1L

    fun reset() {
        unclearSince = -1L
        angleBadSince = -1L
    }

    fun decide(i: EngineInput): Decision {
        if (i.paused) {
            reset()
            return Decision(SystemState.Paused, Severity.Silent, Cue.Paused)
        }
        if (i.frameAgeMs > cfg.maxFrameAgeMs) return Decision(SystemState.TrackingLost, Severity.Critical, Cue.CameraStop)
        val q = i.quality
        if (q != null && q.blocked) return Decision(SystemState.CameraBlocked, Severity.Critical, Cue.CameraStop)

        val pitch = i.cameraPitchDeg
        if (pitch != null && (pitch < cfg.pitchMinDeg || pitch > cfg.pitchMaxDeg)) {
            if (angleBadSince < 0) angleBadSince = i.nowMs
            if (i.nowMs - angleBadSince >= cfg.angleGraceMs) {
                return Decision(SystemState.CameraBlocked, Severity.Critical, Cue.AngleStop)
            }
        } else {
            angleBadSince = -1L
        }

        if (i.processingP90Ms > cfg.latencyStopMs) return Decision(SystemState.HighLatency, Severity.Critical, Cue.LatencyStop)
        if (i.obstacleAhead) return Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.Obstacle)

        val p = i.path
        val trusted = p != null && p.estimate.status == PathStatus.Tracked && p.stable &&
            p.estimate.confidence >= cfg.minConfidence
        if (!trusted) {
            if (unclearSince < 0) unclearSince = i.nowMs
            val unclearFor = i.nowMs - unclearSince
            val estimate = p?.estimate
            if (estimate?.status == PathStatus.EdgeOnly && estimate.edgeDistance < cfg.edgeStopDistance) {
                return Decision(SystemState.StopAndCheck, Severity.Critical, Cue.EdgeNearStop)
            }
            if (q != null && !q.usable && unclearFor >= cfg.unclearCautionMs) {
                return Decision(SystemState.PoorVisibility, Severity.Critical, Cue.VisibilityStop)
            }
            return when {
                unclearFor >= cfg.lostStopMs -> Decision(SystemState.TrackingLost, Severity.Critical, Cue.LostStop)
                unclearFor >= cfg.unclearCautionMs -> Decision(
                    SystemState.BoundariesUnclear, Severity.Warning,
                    if (estimate?.status == PathStatus.EdgeOnly) Cue.EdgeOnlyCaution else Cue.PathUnclear,
                )
                // A short dropout: say nothing yet, and give no direction.
                else -> Decision(SystemState.BoundariesUnclear, Severity.Silent, Cue.None)
            }
        }
        unclearSince = -1L

        val curve = when {
            p!!.curvature > cfg.curveThreshold -> Side.Right
            p.curvature < -cfg.curveThreshold -> Side.Left
            else -> null
        }
        if (i.processingP90Ms > cfg.latencyWarnMs) {
            return Decision(SystemState.HighLatency, Severity.Warning, Cue.LatencySlow, curve = curve)
        }
        val off = p.offset
        val magnitude = abs(off)
        // Positive offset: the runner is right of centre, so the way back is to the left.
        val moveTo = if (off > 0) Side.Left else Side.Right
        return when {
            magnitude >= cfg.atEdge -> Decision(SystemState.StopAndCheck, Severity.Critical, Cue.AtEdgeStop, moveTo, 3, curve)
            magnitude >= cfg.nearEdge -> Decision(SystemState.Drifting, Severity.Warning, Cue.NearEdge, moveTo, 2, curve)
            magnitude >= cfg.slight -> Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, moveTo, 1, curve)
            curve != null -> Decision(SystemState.PathCurving, Severity.Info, Cue.CurveAhead, curve = curve)
            else -> Decision(SystemState.PathTracked, Severity.Info, Cue.Centered)
        }
    }
}
