package com.basir.run.engine

import com.basir.run.vision.FrameQuality
import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel
import com.basir.run.vision.PathStatus
import com.basir.run.vision.Side
import com.basir.run.vision.TrackedPath
import kotlin.math.abs
import run.basir.engine.CommandKind
import run.basir.engine.CornerPhase
import run.basir.engine.CornerState
import run.basir.engine.GuidanceCommand
import run.basir.engine.GuidanceMode
import run.basir.engine.Level
import run.basir.engine.StopReason

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
    /** Back on the path after a correction: one quiet confirmation, then silence. */
    BackOnPath,
    Correct,
    NearEdge,
    AtEdgeStop,
    CurveAhead,
    Obstacle,
    /** Something straight ahead: stop, then turn to the side where the ground goes on ([Decision.moveTo]). */
    TurnStop,
    ObstacleNear,
    HeadLevelStop,
    DropStop,
    PathUnclear,
    EdgeOnlyCaution,
    EdgeNearStop,
    LostStop,
    CameraStop,
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
    /** How much the path behind this decision can be trusted, 0..1; scales vibration strength. */
    val confidence: Float = 1f,
    /** Round 8: set with [Cue.CurveAhead] when the bend is a sharp corner: PREPARE_TURN or TURNING (side in [curve]). */
    val corner: CornerPhase? = null,
) {
    /** Round 8: the decision as one of the four guidance states (EMERGENCY_STOP only for critical decisions). */
    val mode: GuidanceMode get() = when {
        severity == Severity.Critical -> GuidanceMode.EMERGENCY_STOP
        cue == Cue.PathUnclear || cue == Cue.EdgeOnlyCaution || cue == Cue.LatencySlow || (cue == Cue.ObstacleNear && moveTo == null) ->
            GuidanceMode.GUIDANCE_CAUTION
        cue == Cue.Correct || cue == Cue.NearEdge || cue == Cue.CurveAhead || cue == Cue.ObstacleNear -> GuidanceMode.GUIDANCE_CORRECTION
        else -> GuidanceMode.GUIDANCE_ACTIVE
    }
}

data class EngineInput(
    val nowMs: Long,
    val paused: Boolean = false,
    /** Time since the last processed frame. */
    val frameAgeMs: Long,
    val quality: FrameQuality?,
    val path: TrackedPath?,
    /** Obstacle in the running corridor, as confirmed over recent detector runs. */
    val obstacle: ObstacleLevel = ObstacleLevel.None,
    val processingP90Ms: Float = 0f,
    /** Camera angle below the horizon from the gravity sensor, or null if unknown. */
    val cameraPitchDeg: Float? = null,
    /** What raised [obstacle]: something on the ground, at head height, or a drop. */
    val obstacleKind: HazardKind = HazardKind.Object,
    /** The depth channel is loaded but its results stopped arriving in time. */
    val depthStale: Boolean = false,
    /** The Virtual Path Engine's command for this frame, or null when it is not running. */
    val line: GuidanceCommand? = null,
    /** Round 8: the corner engine's state for this frame (LineResult.corner), or null. */
    val corner: CornerState? = null,
)

data class EngineConfig(
    /** Round 8: frames older than this give the caution cue (no direction from stale data) ... */
    val maxFrameAgeMs: Long = 700,
    /** ... and only a camera frozen this long is a stop (one or a few late frames used to stop the runner at 700 ms). */
    val frozenFrameStopMs: Long = 1500,
    /**
     * Round 8: processing must stay above [latencyStopMs] this long before it is a stop. Owner: as few commands as
     * possible; a slow spell is covered by one "slow down" cue and silence (no guidance from stale data), and only a
     * system that stays this slow for 3 s (effectively stalled: new obstacles would be seen too late) stops the runner.
     */
    val latencyStopHoldMs: Long = 3000,
    /** Round 8: obstacle warnings are judged before "slow down" (false = 0.14.0: slowness could hide them). */
    val obstaclesOverSlowness: Boolean = true,
    /**
     * Round 8: a head-level obstacle or a drop gives the obstacle warning on its first frame and the STOP once it has
     * lasted this long (5 frames at 15 fps; owner: a stop of a fraction of a second must not count). Measured: on SANPO
     * these hazards came mostly as 1-2 frame blips of the depth channel while the recorded walker went on. At 2.5 m/s the
     * confirmation costs ~0.65 m; head level stops only within 2.5 m and drops are seen up to 3 m ahead.
     */
    val criticalConfirmMs: Long = 260,
    val minConfidence: Float = 0.5f,
    val unclearCautionMs: Long = 600,
    val lostStopMs: Long = 1500,
    val latencyWarnMs: Float = 180f,
    val latencyStopMs: Float = 350f,
    // No angle check stops guidance (owner's request): the line and free-space engines project with
    // the current tilt of every frame, and a camera that cannot see the ground finds no path, which
    // the path engine already handles (held quietly, then stopped only if it lasts).
    val slight: Float = 0.2f,
    val nearEdge: Float = 0.45f,
    val atEdge: Float = 0.75f,
    val curveThreshold: Float = 0.15f,
    /** EdgeOnly: stop when the single visible edge is this close to the runner (frame fraction). */
    val edgeStopDistance: Float = 0.12f,
    /** Owner, round 5 (field test): false = an unclear path, a lone edge or the runner at the edge give
     *  warnings, not stops. Stops stay for a closed way (> 1 s), head-level obstacles, drops and camera faults. */
    val stopWhenUncertain: Boolean = false,
)

/**
 * Turns measurements into one state and one instruction. Independent of the screen, the speaker
 * and the camera, so every rule can be tested. Rules are checked in order of danger; the first
 * that applies wins. Sideways directions are given only while the path itself is tracked with
 * confidence, so the side the runner is sent to is part of the visible runnable area.
 */
class DecisionEngine(private val cfg: EngineConfig = EngineConfig()) {

    private var unclearSince = -1L
    private var slowSince = -1L
    private var criticalSince = -1L

    fun reset() {
        unclearSince = -1L
        slowSince = -1L
        criticalSince = -1L
    }

    fun decide(i: EngineInput): Decision {
        if (i.paused) {
            reset()
            return Decision(SystemState.Paused, Severity.Silent, Cue.Paused)
        }
        // Round 8 (stop audit): a late frame is stale data, not a danger. Late -> caution without a direction;
        // frozen (no new frame for frozenFrameStopMs) -> stop, as before for a frozen camera.
        if (i.frameAgeMs > cfg.frozenFrameStopMs) return Decision(SystemState.TrackingLost, Severity.Critical, Cue.CameraStop)
        val q = i.quality
        if (q != null && q.blocked) return Decision(SystemState.CameraBlocked, Severity.Critical, Cue.CameraStop)
        if (i.frameAgeMs > cfg.maxFrameAgeMs) return Decision(SystemState.TrackingLost, Severity.Warning, Cue.PathUnclear)

        // Round 8: very slow processing is a stop only when it lasts; before that it slows the runner (no steering).
        if (i.processingP90Ms > cfg.latencyStopMs) {
            if (slowSince < 0) slowSince = i.nowMs
            if (i.nowMs - slowSince >= cfg.latencyStopHoldMs) return Decision(SystemState.HighLatency, Severity.Critical, Cue.LatencyStop)
            if (!cfg.obstaclesOverSlowness) return Decision(SystemState.HighLatency, Severity.Warning, Cue.LatencySlow)
            // Not stalled yet: no early return. Obstacles and closed ways are judged first (below); "slow down" is given
            // only where no obstacle decision applies (fromLine / trusted path). Before, slowness hid obstacle warnings.
        } else slowSince = -1L
        // An object on the ground close ahead, but the path engine has seen a way around it for
        // several frames (a detour side): guide the turn instead of stopping. The owner wants a
        // stop only in extreme cases; head-level obstacles and drops stay stops.
        val detourSide = i.line?.takeIf { it.kind == CommandKind.OBSTACLE }?.side
        if (i.obstacle == ObstacleLevel.Hazard && i.obstacleKind == HazardKind.Object && detourSide != null) {
            val to = if (detourSide == run.basir.engine.Side.LEFT) Side.Left else Side.Right
            return Decision(SystemState.ObstacleAhead, Severity.Warning, Cue.ObstacleNear, moveTo = to, level = 3)
        }
        // Head-level obstacles and drops (stairs down, a hole) stay stops: the camera cannot show a way
        // past them. A close object on the ground is a warning (slow down): the owner's field-test rule
        // (round 5) is to stop only when the way is closed for over a second, which the path engine decides.
        val critical = i.obstacle == ObstacleLevel.Hazard && i.obstacleKind != HazardKind.Object
        if (!critical) criticalSince = -1L
        if (i.obstacle == ObstacleLevel.Hazard) {
            // Round 8: warning at once, stop once confirmed (criticalConfirmMs); never a stop from a single depth blip
            if (critical) {
                if (criticalSince < 0) criticalSince = i.nowMs
                if (i.nowMs - criticalSince < cfg.criticalConfirmMs) return Decision(SystemState.ObstacleAhead, Severity.Warning, Cue.ObstacleNear, level = 3)
            }
            return when (i.obstacleKind) {
                HazardKind.HeadLevel -> Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.HeadLevelStop)
                HazardKind.Drop -> Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.DropStop)
                // level 3: within stopping distance (its reminders are not capped, round 8)
                HazardKind.Object -> Decision(SystemState.ObstacleAhead, Severity.Warning, Cue.ObstacleNear, level = 3)
            }
        }
        // A warning is "close" (level 3) when ANY source sees it close: the depth channel may still say "possible" while
        // the path engine already reports the way closed within stopping distance (found on turn90_b).
        val pathClose = i.line?.let { it.kind == CommandKind.OBSTACLE && it.level == Level.HIGH } == true
        if (i.obstacle == ObstacleLevel.Possible) return Decision(SystemState.ObstacleAhead, Severity.Warning, Cue.ObstacleNear,
            level = if (pathClose) 3 else 2)

        // The Virtual Path Engine steers whenever it follows a line, a lane or runs alongside a
        // line. It finds no line → the image-space path below is the fallback.
        val cmd = i.line
        // The path engine stops only when the way has been closed for over a second (an object with no way
        // around, or the walkable ground ends within stopping distance); with a side where the ground
        // clearly goes on, it says where to turn.
        if (cmd != null && cmd.kind == CommandKind.STOP &&
            (cmd.stopReason == StopReason.OBSTACLE_NO_ALTERNATIVE || cmd.stopReason == StopReason.PATH_UNSAFE)
        ) {
            val turn = when (cmd.side) {
                run.basir.engine.Side.LEFT -> Side.Left
                run.basir.engine.Side.RIGHT -> Side.Right
                null -> null
            }
            return if (turn != null) Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.TurnStop, moveTo = turn)
            else Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.Obstacle)
        }
        // Round 8: a sharp corner (CornerNavigationEngine) is one clear cue that outranks corrections and bends of
        // the same side and slight ones of the other; a strong correction the other way (about to leave the path) wins.
        cornerDecision(cmd, i)?.let { unclearSince = -1L; return it }
        if (cmd != null && cmd.kind != CommandKind.STOP) {
            unclearSince = -1L
            return fromLine(cmd, i)
        }

        val p = i.path
        val trusted = p != null && p.estimate.status == PathStatus.Tracked && p.stable &&
            p.estimate.confidence >= cfg.minConfidence
        if (!trusted) {
            if (unclearSince < 0) unclearSince = i.nowMs
            val unclearFor = i.nowMs - unclearSince
            val estimate = p?.estimate
            // Owner, round 5: an unclear or lost path slows the runner down, it never stops them.
            if (cfg.stopWhenUncertain) {
                if (estimate?.status == PathStatus.EdgeOnly && estimate.edgeDistance < cfg.edgeStopDistance) {
                    return Decision(SystemState.StopAndCheck, Severity.Critical, Cue.EdgeNearStop)
                }
                if (q != null && !q.usable && unclearFor >= cfg.unclearCautionMs) {
                    return Decision(SystemState.PoorVisibility, Severity.Critical, Cue.VisibilityStop)
                }
                if (unclearFor >= cfg.lostStopMs) return Decision(SystemState.TrackingLost, Severity.Critical, Cue.LostStop)
            }
            return when {
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
        // Without fresh depth results obstacles of unknown kind may be missed: slow down, no steering.
        if (i.processingP90Ms > cfg.latencyWarnMs || i.depthStale) {
            return Decision(SystemState.HighLatency, Severity.Warning, Cue.LatencySlow, curve = curve)
        }
        val off = p.offset
        val magnitude = abs(off)
        // Positive offset: the runner is right of centre, so the way back is to the left.
        val moveTo = if (off > 0) Side.Left else Side.Right
        return when {
            magnitude >= cfg.atEdge && cfg.stopWhenUncertain -> Decision(SystemState.StopAndCheck, Severity.Critical, Cue.AtEdgeStop, moveTo, 3, curve)
            magnitude >= cfg.atEdge -> Decision(SystemState.Drifting, Severity.Warning, Cue.NearEdge, moveTo, 3, curve)
            magnitude >= cfg.nearEdge -> Decision(SystemState.Drifting, Severity.Warning, Cue.NearEdge, moveTo, 2, curve)
            magnitude >= cfg.slight -> Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, moveTo, 1, curve)
            curve != null -> Decision(SystemState.PathCurving, Severity.Info, Cue.CurveAhead, curve = curve)
            else -> Decision(SystemState.PathTracked, Severity.Info, Cue.Centered)
        }
    }

    private fun cornerDecision(cmd: GuidanceCommand?, i: EngineInput): Decision? {
        val c = i.corner ?: return null
        if (c.exitUnverified) {
            // A turn ended without a verified way out: no direction, slow down (unless the path is clearly followed again)
            if (cmd != null && (cmd.kind == CommandKind.ON_PATH || cmd.kind == CommandKind.CORRECT) && cmd.confidence >= 0.5) return null
            return Decision(SystemState.BoundariesUnclear, Severity.Warning, Cue.PathUnclear)
        }
        val cs = c.side
        if (!c.active) {
            // Turn done (exit / new straight): the new direction is held. A slight or medium correction toward the side
            // the runner came from is a reading of the old path in the new view, not a real change (strong ones pass).
            // Only while exiting, and not when a next corner is already being seen (consecutive turns: the next one may
            // well be to the other side).
            if (cs != null && c.phase == CornerPhase.EXIT_CORNER && c.nextSide == null && cmd != null &&
                cmd.kind == CommandKind.CORRECT && cmd.level != Level.HIGH && cmd.side != null && cmd.side != cs) {
                return Decision(SystemState.PathTracked, Severity.Info, Cue.Centered, confidence = cmd.confidence.toFloat().coerceIn(0f, 1f))
            }
            return null
        }
        // Safety outranks the corner: an obstacle warning or a closed way from the path engine is never replaced by it.
        if (cmd != null && (cmd.kind == CommandKind.OBSTACLE || cmd.kind == CommandKind.STOP)) return null
        val side = when (c.side) { run.basir.engine.Side.LEFT -> Side.Left; run.basir.engine.Side.RIGHT -> Side.Right; null -> return null }
        if (cmd != null && cmd.kind == CommandKind.CORRECT && cmd.level == Level.HIGH && cmd.side != c.side) return null
        if (i.processingP90Ms > cfg.latencyWarnMs || i.depthStale) return null
        // Approaching the corner and the path turns unclear: the caution is the honest cue. Inside the turn the corner's
        // geometry and the measured turn carry the guidance for its (bounded) duration.
        // (Close to the corner the line is expected to leave the view: that gap is part of the corner, not a caution.)
        val atCorner = (c.distanceM ?: Double.MAX_VALUE) <= 2.5
        if (c.phase == CornerPhase.PREPARE_TURN && !atCorner && (cmd == null || cmd.kind == CommandKind.CAUTION || cmd.kind == CommandKind.STOP)) return null
        if (cmd != null && cmd.kind == CommandKind.STOP) return null
        return Decision(SystemState.PathCurving, Severity.Correction, Cue.CurveAhead, level = 3, curve = side,
            confidence = (cmd?.confidence ?: c.confidence).toFloat().coerceIn(0.3f, 1f), corner = c.phase)
    }

    private fun fromLine(cmd: GuidanceCommand, i: EngineInput): Decision {
        val conf = cmd.confidence.toFloat().coerceIn(0f, 1f)
        val bend = when {
            cmd.curveInM == null -> null
            cmd.curveCurvature > 0 -> Side.Right
            cmd.curveCurvature < 0 -> Side.Left
            else -> null
        }
        // Slowness never hides an obstacle warning of the path engine (it outranks "slow down")
        if ((i.processingP90Ms > cfg.latencyWarnMs || i.depthStale) && (cmd.kind != CommandKind.OBSTACLE || !cfg.obstaclesOverSlowness)) {
            return Decision(SystemState.HighLatency, Severity.Warning, Cue.LatencySlow, curve = bend, confidence = conf)
        }
        val side = when (cmd.side) {
            run.basir.engine.Side.LEFT -> Side.Left
            run.basir.engine.Side.RIGHT -> Side.Right
            null -> null
        }
        val level = when (cmd.level) {
            Level.HIGH -> 3
            Level.MEDIUM -> 2
            else -> 1
        }
        return when (cmd.kind) {
            // Something on the path, not yet at stopping distance: slow down, no way around offered.
            // With a way around that the engine has seen for several frames, the side to pass on.
            CommandKind.OBSTACLE -> Decision(SystemState.ObstacleAhead, Severity.Warning, Cue.ObstacleNear, moveTo = side,
                level = if (cmd.level == Level.HIGH) 3 else 2, confidence = conf)
            CommandKind.CORRECT -> if (level == 3) {
                Decision(SystemState.Drifting, Severity.Warning, Cue.NearEdge, side, 3, bend, conf)
            } else {
                Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, side, level, bend, conf)
            }
            CommandKind.CURVE -> Decision(SystemState.PathCurving, Severity.Info, Cue.CurveAhead, level = level, curve = side, confidence = conf)
            // Path briefly unclear: slow down, no direction (level 3 of the owner's four levels).
            CommandKind.CAUTION -> Decision(SystemState.BoundariesUnclear, Severity.Warning, Cue.PathUnclear, confidence = conf)
            else -> if (cmd.confirm) Decision(SystemState.PathTracked, Severity.Info, Cue.BackOnPath, curve = bend, confidence = conf)
            else Decision(SystemState.PathTracked, Severity.Info, Cue.Centered, curve = bend, confidence = conf)
        }
    }
}
