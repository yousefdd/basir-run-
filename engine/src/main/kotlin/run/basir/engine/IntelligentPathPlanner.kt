package run.basir.engine

import kotlin.math.abs
import kotlin.math.max

enum class PathMode { LINE, LINE_PARALLEL, LANE_CENTER, FREE_PATH, DETOUR, STOP }

enum class StopReason { NONE, NO_PATH, LOW_CONFIDENCE, OBSTACLE_NO_ALTERNATIVE, POOR_VISIBILITY, PATH_UNSAFE }

/** Everything the vision side produced for one frame. */
data class FrameInput(
    val lines: List<LineCandidate>,
    val freeRoute: FreeRoute?,          // virtual path through walkable space (already avoids obstacles)
    val freeGrid: GroundGrid? = null,   // to check that a followed line stays on walkable ground
    val obstacles: List<TrackedObstacle>,
    val visibility: Double,             // 0..1 scene quality (lighting, blur, ground visible)
    val runnerSpeedMps: Double = 2.5,
    val dt: Double = 1.0 / 15,
)

data class PathDecision(
    val mode: PathMode,
    val path: PathCurve?,               // the virtual path to follow (runner frame)
    val confidence: Double,
    val stopReason: StopReason = StopReason.NONE,
    val obstacleAheadM: Double? = null, // an obstacle on the path, not yet close enough to stop
    val blocking: TrackedObstacle? = null,
)

data class PlannerConfig(
    val runnerRadiusM: Double = 0.4,
    val obstacleMarginM: Double = 0.2,
    val reactionTimeS: Double = 1.5,     // feel the cue, understand it, slow down
    val minStopDistanceM: Double = 2.0,
    val warnDistanceM: Double = 6.0,
    val lineMaxOffsetM: Double = 0.6,    // a lone line this close can be followed
    val onLineM: Double = 0.3,           // the line is under the runner (guide-line running)
    val laneMinWidthM: Double = 0.8,
    val laneMaxWidthM: Double = 4.0,
    val minVisibility: Double = 0.4,
    val minFreeProgressM: Double = 2.0,
    val minPathConfidence: Double = 0.5,
    val modeHoldFrames: Int = 5,         // hysteresis before switching between modes
)

/**
 * Chooses and maintains the virtual path. Order of preference: a line under the runner, the
 * center of a lane between two boundary lines, then a virtual path through walkable space.
 * Obstacles are checked against the chosen path (including where moving ones will be when the
 * runner gets there). A detour is taken only when the free-space route around the obstacle is
 * visible and verified; otherwise the decision is STOP. Low confidence never yields a direction.
 */
class IntelligentPathPlanner(private val config: PlannerConfig = PlannerConfig()) {
    private val tracker = PathTracker()
    private var trackedMode: PathMode? = null
    private var lastMode = PathMode.STOP
    private var pendingMode: PathMode? = null
    private var pendingCount = 0
    private var parallelOffset: Double? = null   // locked distance to a line followed alongside

    fun update(input: FrameInput): PathDecision {
        if (input.visibility < config.minVisibility) {
            tracker.update(null)
            return commit(PathDecision(PathMode.STOP, null, 0.0, StopReason.POOR_VISIBILITY))
        }

        // 1. Line or lane measurement, preferring the kind already being tracked
        val line = input.lines.firstOrNull { abs(it.lateralAtRunner(1.0)) <= config.lineMaxOffsetM }
        val laneFound = findLane(input.lines)
        val lane = laneFound?.first
        // A line that bounds the runner's lane is an edge, never the path; the lane center is.
        // A single line is followed only when it is a guide line under the runner (not a lane
        // boundary) or when it is the only guide in view.
        val isBoundary = line != null && laneFound != null && (line === laneFound.second || line === laneFound.third)
        val onLine = line != null && !isBoundary && abs(line.lateralAtRunner(1.0)) <= config.onLineM
        val (measurement, kind) = when {
            trackedMode == PathMode.LANE_CENTER && lane != null && !onLine -> lane to PathMode.LANE_CENTER
            lane != null && !onLine -> lane to PathMode.LANE_CENTER
            onLine -> line!!.curve to PathMode.LINE
            // A lone line beside the runner: run parallel to it at the distance it was first seen,
            // never onto it (it may be a lane edge) and without guessing how wide the path is
            line != null -> {
                val offset = parallelOffset.takeIf { trackedMode == PathMode.LINE_PARALLEL } ?: line.lateralAtRunner(1.0)
                parallelOffset = offset
                shifted(line.curve, -offset) to PathMode.LINE_PARALLEL
            }
            else -> null to null
        }
        if (kind != null && kind != trackedMode && tracker.state == TrackState.TRACKING) tracker.reset()
        tracker.update(measurement)
        if (kind != null) trackedMode = kind

        // 2. Candidate path: tracked line/lane if trustworthy and on walkable ground, else free space
        var decision: PathDecision? = null
        val tracked = tracker.curve
        if (tracked != null && tracker.confidence >= config.minPathConfidence && trackedMode != null) {
            if (input.freeGrid == null || staysWalkable(tracked, input.freeGrid)) {
                decision = PathDecision(trackedMode!!, tracked, tracker.confidence)
            }
        }
        val free = input.freeRoute
        if (decision == null && free != null && free.progressM >= config.minFreeProgressM) {
            decision = free.toCurve()?.let { PathDecision(PathMode.FREE_PATH, it, 0.7 * input.visibility) }
        }
        if (decision == null) {
            val reason = if (tracked != null || free != null) StopReason.LOW_CONFIDENCE else StopReason.NO_PATH
            return commit(PathDecision(PathMode.STOP, null, 0.0, reason))
        }

        // 3. Obstacles on the chosen path
        val path = decision.path!!.sample(0.2, 0.0, max(decision.path.farM, 3.0))
        val stopAt = max(config.minStopDistanceM, input.runnerSpeedMps * config.reactionTimeS + 0.5)
        var nearest: Pair<TrackedObstacle, Double>? = null
        for (o in input.obstacles) {
            if (!o.confirmed && o.position.forward > 1.5) continue // single-frame blips far away
            val (dNow, alongNow) = distanceToPath(o.position, path)
            val tReach = alongNow / max(input.runnerSpeedMps, 0.5)
            val (dLater, alongLater) = distanceToPath(o.predict(tReach), path)
            val clearance = config.runnerRadiusM + o.radiusM + config.obstacleMarginM
            val hit = when {
                dNow < clearance -> alongNow
                o.speed > 0.3 && dLater < clearance -> alongLater // moving into the path
                else -> null
            }
            if (hit != null && (nearest == null || hit < nearest.second)) nearest = o to hit
        }
        if (nearest == null) return commit(decision)
        val (obstacle, along) = nearest
        if (along > stopAt) {
            return commit(decision.copy(obstacleAheadM = along, blocking = obstacle))
        }
        // Close: detour only if the free-space route is visible, long enough and clears the obstacle
        if (free != null && decision.mode != PathMode.FREE_PATH &&
            free.progressM >= along + 2.0 &&
            free.minClearanceM >= config.runnerRadiusM + 0.1 &&
            distanceToPath(obstacle.position, free.waypoints).first >= config.runnerRadiusM + obstacle.radiusM + config.obstacleMarginM
        ) {
            free.toCurve()?.let {
                return commit(PathDecision(PathMode.DETOUR, it, 0.6 * input.visibility, obstacleAheadM = along, blocking = obstacle))
            }
        }
        if (decision.mode == PathMode.FREE_PATH && free != null &&
            distanceToPath(obstacle.position, free.waypoints).first >= config.runnerRadiusM + obstacle.radiusM + config.obstacleMarginM
        ) {
            return commit(decision.copy(obstacleAheadM = along, blocking = obstacle)) // free route already goes around
        }
        return commit(PathDecision(PathMode.STOP, null, 0.0, StopReason.OBSTACLE_NO_ALTERNATIVE, along, obstacle))
    }

    /** Mode hysteresis: STOP is immediate; other mode changes must persist for a few frames. */
    private fun commit(d: PathDecision): PathDecision {
        if (d.mode == PathMode.STOP || d.mode == lastMode) {
            lastMode = d.mode; pendingMode = null; pendingCount = 0
            return d
        }
        if (lastMode == PathMode.STOP || d.mode == PathMode.DETOUR) { // resuming or avoiding: act now
            lastMode = d.mode
            return d
        }
        if (d.mode == pendingMode) pendingCount++ else { pendingMode = d.mode; pendingCount = 1 }
        if (pendingCount >= config.modeHoldFrames) {
            lastMode = d.mode; pendingMode = null; pendingCount = 0
        }
        return d.copy(mode = if (lastMode == d.mode) d.mode else d.mode) // path content is always current
    }

    /**
     * The lane the runner is in: two neighbouring, roughly parallel lines with a plausible
     * spacing that contain the runner (who may drift a little past one of them). Returns the
     * center line and the two boundaries.
     */
    private fun findLane(lines: List<LineCandidate>): Triple<PathCurve, LineCandidate, LineCandidate>? {
        val cands = lines.filter { it.coverageM >= 2.0 }.sortedBy { it.lateralAtRunner(1.0) }
        var best: Pair<LineCandidate, LineCandidate>? = null
        var bestScore = 0.0
        for (i in cands.indices) for (j in i + 1 until cands.size) {
            val l = cands[i]
            val r = cands[j]
            val ll = l.lateralAtRunner(1.0)
            val rl = r.lateralAtRunner(1.0)
            if (0.0 !in (ll - 0.3)..(rl + 0.3)) continue            // runner not in this lane
            // Neighbours only: no other well-supported line in between
            if (cands.subList(i + 1, j).any { it.coverageM >= 3.0 && it.score > 0.5 * minOf(l.score, r.score) }) continue
            val near = max(l.curve.nearM, r.curve.nearM)
            val far = minOf(l.curve.farM, r.curve.farM)
            if (far - near < 1.5) continue
            val widths = (0..4).map { k -> val f = near + k * (far - near) / 4; r.curve.lateralAt(f) - l.curve.lateralAt(f) }
            val mean = widths.average()
            val spread = widths.maxOf { abs(it - mean) }
            if (mean !in config.laneMinWidthM..config.laneMaxWidthM || spread > 0.3) continue
            val center = (ll + rl) / 2
            val score = (l.score + r.score) * kotlin.math.exp(-abs(center) / mean)
            if (score > bestScore) { bestScore = score; best = l to r }
        }
        val (l, r) = best ?: return null
        val n = max(l.curve.coeffs.size, r.curve.coeffs.size)
        val c = DoubleArray(n) { (l.curve.coeffs.getOrElse(it) { 0.0 } + r.curve.coeffs.getOrElse(it) { 0.0 }) / 2 }
        val curve = PathCurve(c, max(l.curve.nearM, r.curve.nearM), minOf(l.curve.farM, r.curve.farM), (l.curve.rmsM + r.curve.rmsM) / 2)
        return Triple(curve, l, r)
    }

    private fun shifted(c: PathCurve, dLat: Double) =
        c.copy(coeffs = c.coeffs.copyOf().also { it[0] += dLat })

    /** A line is only a guide if the ground along it is walkable and free of obstacles. */
    private fun staysWalkable(curve: PathCurve, grid: GroundGrid): Boolean {
        var seen = 0
        var bad = 0
        for (p in curve.sample(0.2, max(curve.nearM, 0.5), minOf(curve.farM, 5.0))) {
            val k = grid.index(p.forward, p.lateral) ?: continue
            seen++
            if (grid.occupied[k]) bad++
        }
        return seen == 0 || bad.toDouble() / seen < 0.1
    }
}
