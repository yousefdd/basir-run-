package com.basir.run.engine

import com.basir.run.vision.Side
import run.basir.engine.CommandKind
import run.basir.engine.GuidanceCommand
import run.basir.engine.Level

/**
 * Turns the final decision into a command for the engine's haptic language
 * (run.basir.engine.HapticLanguage, played by its HapticScheduler), so there is one vibration
 * vocabulary for everything:
 * - any critical decision (path lost, camera, angle, obstacle or drop ahead) → STOP, which
 *   cuts any other pattern and stays latched until the danger has cleared;
 * - a correction at the edge → strong correction toward the path;
 * - other warnings (obstacle near, path unclear, slow processing) → three quick pulses;
 * - a correction → one pulse left / two pulses right, stronger with the drift;
 * - a bend ahead → the same rhythm, long and soft;
 * - obstacle with a verified way around → three quick pulses, then the side to pass on;
 * - back on the path after a correction → three quick soft taps, then silence while on the path.
 * Silence (a short dropout, pause) sends nothing, so no direction is given without a path.
 */
object HapticBridge {

    fun command(d: Decision): GuidanceCommand? {
        val conf = d.confidence.toDouble()
        return when {
            d.severity == Severity.Silent -> null
            d.cue == Cue.TurnStop -> GuidanceCommand(CommandKind.STOP, side = engineSide(d.moveTo), confidence = conf)
            d.severity == Severity.Critical -> GuidanceCommand(CommandKind.STOP, confidence = conf)
            d.cue == Cue.NearEdge -> steer(CommandKind.CORRECT, d.moveTo, Level.HIGH, conf)
            d.cue == Cue.PathUnclear || d.cue == Cue.EdgeOnlyCaution -> GuidanceCommand(CommandKind.CAUTION, confidence = conf)
            d.cue == Cue.ObstacleNear && d.moveTo != null -> GuidanceCommand(CommandKind.OBSTACLE, side = engineSide(d.moveTo), level = Level.HIGH, confidence = conf)
            // level 3 = within stopping distance: keeps reminding while it lasts (HapticScheduler, round 8)
            d.severity == Severity.Warning -> GuidanceCommand(CommandKind.OBSTACLE, level = if (d.level >= 3 && d.cue == Cue.ObstacleNear) Level.HIGH else null, confidence = conf)
            d.cue == Cue.Correct -> steer(CommandKind.CORRECT, d.moveTo, level(d.level), conf)
            d.cue == Cue.CurveAhead && d.corner != null -> steer(CommandKind.CURVE, d.curve, Level.HIGH, conf)?.copy(corner = d.corner)
            d.cue == Cue.CurveAhead -> steer(CommandKind.CURVE, d.curve, level(d.level), conf)
            d.cue == Cue.BackOnPath -> GuidanceCommand(CommandKind.ON_PATH, confidence = conf, confirm = true)
            d.cue == Cue.Centered -> GuidanceCommand(CommandKind.ON_PATH, confidence = conf)
            else -> null
        }
    }

    private fun engineSide(side: Side?) = when (side) {
        Side.Left -> run.basir.engine.Side.LEFT
        Side.Right -> run.basir.engine.Side.RIGHT
        null -> null
    }

    private fun level(n: Int) = when {
        n >= 3 -> Level.HIGH
        n == 2 -> Level.MEDIUM
        else -> Level.LOW
    }

    private fun steer(kind: CommandKind, side: Side?, level: Level, conf: Double): GuidanceCommand? {
        val s = when (side) {
            Side.Left -> run.basir.engine.Side.LEFT
            Side.Right -> run.basir.engine.Side.RIGHT
            null -> return null
        }
        return GuidanceCommand(kind, s, level, confidence = conf)
    }
}

/**
 * HapticBridge with the engine's GuidanceTemporalStabilizer (research round 6b): the decision of each frame is turned
 * into a command, then given temporal rules (warnings start at once and end only after evidence, no side flip within
 * a second, STOP untouched) before it reaches the HapticScheduler. Use one instance per run and call [reset] on pause.
 */
class StableHapticBridge(private val stabilizer: run.basir.engine.GuidanceTemporalStabilizer = run.basir.engine.GuidanceTemporalStabilizer()) {
    fun command(d: Decision, nowMs: Long): GuidanceCommand? = stabilizer.update(HapticBridge.command(d), nowMs)
    fun reset() = stabilizer.reset()
}

/**
 * Round 8: HapticBridge with the engine's GuidanceDecisionStabilityEngine (dominant command by weighted temporal voting,
 * direction persistence with side memory, adaptive cooldown, the four guidance states). Safety commands (STOP, obstacle
 * warnings) pass on the frame they are decided. One instance per run; [reset] on pause.
 */
class DecisionStabilityHapticBridge(val engine: run.basir.engine.GuidanceDecisionStabilityEngine = run.basir.engine.GuidanceDecisionStabilityEngine()) {
    /** Last output with its guidance state and reason (developer overlay, logs). */
    var last: run.basir.engine.StableGuidance? = null
        private set

    fun command(d: Decision, nowMs: Long, speedMps: Double = 2.5, trackConfidence: Double = 1.0): GuidanceCommand? =
        engine.update(mapped(d), nowMs, speedMps, trackConfidence).also { last = it }.command

    /**
     * Round 8: slow processing is "slow down, no direction" (the caution pattern), not the obstacle pattern it used to
     * share with real obstacles (HapticBridge maps every other warning to OBSTACLE).
     */
    private fun mapped(d: Decision): GuidanceCommand? =
        if (d.cue == Cue.LatencySlow) GuidanceCommand(CommandKind.CAUTION, confidence = d.confidence.toDouble()) else HapticBridge.command(d)

    fun reset() { engine.reset(); last = null }
}
