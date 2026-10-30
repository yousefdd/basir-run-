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
 * - on the path → a faint tick now and then.
 * Silence (a short dropout, pause) sends nothing, so no direction is given without a path.
 */
object HapticBridge {

    fun command(d: Decision): GuidanceCommand? {
        val conf = d.confidence.toDouble()
        return when {
            d.severity == Severity.Silent -> null
            d.severity == Severity.Critical -> GuidanceCommand(CommandKind.STOP, confidence = conf)
            d.cue == Cue.NearEdge -> steer(CommandKind.CORRECT, d.moveTo, Level.HIGH, conf)
            d.severity == Severity.Warning -> GuidanceCommand(CommandKind.OBSTACLE, confidence = conf)
            d.cue == Cue.Correct -> steer(CommandKind.CORRECT, d.moveTo, level(d.level), conf)
            d.cue == Cue.CurveAhead -> steer(CommandKind.CURVE, d.curve, level(d.level), conf)
            d.cue == Cue.Centered -> GuidanceCommand(CommandKind.ON_PATH, confidence = conf)
            else -> null
        }
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
