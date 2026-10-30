package run.basir.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class Side { LEFT, RIGHT }
enum class Level { LOW, MEDIUM, HIGH }

enum class CommandKind {
    STOP,          // stop now (no path, obstacle without verified alternative, poor visibility)
    OBSTACLE,      // something on the path ahead, not yet at stopping distance
    CORRECT,       // steer back toward the virtual path
    CURVE,         // the path bends ahead — prepare to follow it
    ON_PATH,       // on the path, nothing to do (quiet confirmation)
}

data class GuidanceCommand(
    val kind: CommandKind,
    val side: Side? = null,
    val level: Level? = null,
    val steerDeg: Double = 0.0,         // pure-pursuit angle toward the path (+ right)
    val curveCurvature: Double = 0.0,   // 1/m at the upcoming bend (+ right)
    val curveInM: Double? = null,       // how far ahead the bend starts
    val confidence: Double = 0.0,
    val stopReason: StopReason = StopReason.NONE,
    val mode: PathMode = PathMode.STOP,
)

data class GuidanceConfig(
    val deadbandDeg: Double = 4.0,       // no correction inside this
    val switchSideDeg: Double = 6.0,     // a correction must exceed this to flip sides
    val confirmFrames: Int = 2,          // frames a new side must persist
    val mediumDeg: Double = 10.0,
    val highDeg: Double = 20.0,
    val curveMinCurvature: Double = 0.04, // radius < 25 m counts as a bend
    val curveMediumCurvature: Double = 0.08,
    val curveSharpCurvature: Double = 0.15, // radius < ~7 m
    val previewMaxM: Double = 8.0,
)

/**
 * Turns the planner's virtual path into one guidance command per frame. Steering uses pure
 * pursuit toward a look-ahead point on the path (farther at higher speed), so the cue reflects
 * where the path goes, not just the point under the feet. Bends are announced from the path's
 * curvature ahead, before the runner reaches them. Side changes need a margin and a few frames
 * (no left-right flicker), and a bend cue that would contradict the current correction is
 * held back.
 */
class GuidanceController(private val config: GuidanceConfig = GuidanceConfig()) {
    private var lastSide: Side? = null
    private var pendingSide: Side? = null
    private var pendingCount = 0

    fun update(decision: PathDecision, speedMps: Double = 2.5): GuidanceCommand {
        val path = decision.path
        if (decision.mode == PathMode.STOP || path == null) {
            reset()
            return GuidanceCommand(CommandKind.STOP, stopReason = decision.stopReason, mode = decision.mode)
        }
        val lookahead = (speedMps * 0.8).coerceIn(1.5, 3.0)
        val target = min(lookahead, max(path.farM, 1.0))
        val steer = atan2(path.lateralAt(target), target).toDegrees()

        // Correction with deadband and side hysteresis
        var side: Side? = null
        if (abs(steer) >= config.deadbandDeg) {
            val wanted = if (steer > 0) Side.RIGHT else Side.LEFT
            side = when {
                lastSide == null || wanted == lastSide -> wanted
                abs(steer) < config.switchSideDeg -> null           // small flip: hold quiet
                else -> {
                    if (wanted == pendingSide) pendingCount++ else { pendingSide = wanted; pendingCount = 1 }
                    if (pendingCount >= config.confirmFrames) wanted else null
                }
            }
        }
        if (side != null) { lastSide = side; pendingSide = null; pendingCount = 0 }
        val level = when {
            abs(steer) >= config.highDeg -> Level.HIGH
            abs(steer) >= config.mediumDeg -> Level.MEDIUM
            else -> Level.LOW
        }

        // Upcoming bend from the curvature ahead (only where the path was actually seen)
        var curveAt: Double? = null
        var kappa = 0.0
        val from = target + 0.5
        val to = min(path.farM, config.previewMaxM)
        var f = from
        while (f <= to - 1.0) {
            val k = (path.curvatureAt(f) + path.curvatureAt(f + 0.5) + path.curvatureAt(f + 1.0)) / 3
            if (abs(k) >= config.curveMinCurvature) { curveAt = f; kappa = k; break }
            f += 0.25
        }

        val base = GuidanceCommand(CommandKind.ON_PATH, steerDeg = steer, confidence = decision.confidence, mode = decision.mode,
            curveCurvature = kappa, curveInM = curveAt)
        if (decision.obstacleAheadM != null) return base.copy(kind = CommandKind.OBSTACLE, side = side, level = Level.HIGH)
        if (side != null) {
            return base.copy(kind = CommandKind.CORRECT, side = side, level = level)
        }
        if (curveAt != null) {
            val curveSide = if (kappa > 0) Side.RIGHT else Side.LEFT
            val curveLevel = when {
                abs(kappa) >= config.curveSharpCurvature -> Level.HIGH
                abs(kappa) >= config.curveMediumCurvature -> Level.MEDIUM
                else -> Level.LOW
            }
            // Never announce a bend opposite to the side we are steering toward right now
            if (abs(steer) < config.deadbandDeg || sign(steer) == sign(kappa)) {
                return base.copy(kind = CommandKind.CURVE, side = curveSide, level = curveLevel)
            }
        }
        return base
    }

    fun reset() {
        lastSide = null; pendingSide = null; pendingCount = 0
    }
}
