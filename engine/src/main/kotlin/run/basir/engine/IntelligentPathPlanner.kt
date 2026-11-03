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
    /** Routes through the walkable ground aimed to the left and right, to say which way to turn after a stop. */
    val sideRoutes: List<FreeRoute> = emptyList(),
    /** Middle of the walkable strip along the route ([CorridorCenterline]); followed instead of the route when present. */
    val freeCenterline: Centerline? = null,
    /** Frame time (ms); when null the planner advances its own clock by [dt]. */
    val timeMs: Long? = null,
    /** How the runner moved since the previous frame (gyroscope yaw, speed); used by the temporal tracker. */
    val ego: EgoMotion? = null,
)

data class PathDecision(
    val mode: PathMode,
    val path: PathCurve?,               // the virtual path to follow (runner frame)
    val confidence: Double,
    val stopReason: StopReason = StopReason.NONE,
    val obstacleAheadM: Double? = null, // an obstacle on the path, not yet close enough to stop
    val blocking: TrackedObstacle? = null,
    /** With a STOP for something ahead: the side where walkable ground goes on, if one clearly does. */
    val turnSide: Side? = null,
    /** Half the width of the walkable strip around the path, when the strip's edges were seen. */
    val stripHalfWidthM: Double? = null,
)

data class PlannerConfig(
    val runnerRadiusM: Double = 0.4,
    val obstacleMarginM: Double = 0.2,
    val walkableEndToleranceM: Double = 0.3, // a still object this close to (or past) the walkable end is what ends it: left to the path-end rule
    /** Only this close does a blocked way stop the runner when a side is open; further, the runner is turned toward it. */
    val emergencyStopM: Double = 1.2,   // still obstacles further than this past the walkable end are left to the path-end rule
    val reactionTimeS: Double = 1.5,     // feel the cue, understand it, slow down
    val minStopDistanceM: Double = 2.0,
    val warnDistanceM: Double = 6.0,
    val lineMaxOffsetM: Double = 0.6,    // a lone line this close can be followed
    /** Round 7 (sports grounds): a STRONG lone line up to this far to the side (a football touchline, the edge line of a
     *  track or court) can be run beside at the distance where it was first seen. Weak lines keep [lineMaxOffsetM]. */
    val followDistantLines: Boolean = true,
    val parallelMaxOffsetM: Double = 2.0,
    val strongLineCoverageM: Double = 3.0,
    val strongLineContinuity: Double = 0.6,
    val strongLineNearM: Double = 2.0,
    /** While running beside a line, the next frames' line is the one nearest the locked offset (within this gate),
     *  not the best-scored line near the runner: no jumping between parallel lines (lanes, pitch markings). */
    val parallelGateM: Double = 0.5,
    val onLineM: Double = 0.3,           // the line is under the runner (guide-line running)
    val laneMinWidthM: Double = 0.8,
    val laneMaxWidthM: Double = 4.0,
    val minVisibility: Double = 0.4,
    /** Research round 6: a blurred or dark frame stops guidance only when this many come in a row (0.2 s at 15 fps).
     *  A single one (a stride's jolt while running) is a frame without measurements: the tracked path coasts and
     *  obstacles are still checked. On SANPO jogging clips one blurred frame in 16 kept the runner on "path unclear"
     *  half the time (each one forced a stop and a 10-frame resume). 1 = the 0.12.2 behaviour. */
    val poorVisibilityFrames: Int = 3,
    /** Research round 6: track the path with [TemporalPathTracker] (time-based, ego-motion compensated, association gate,
     *  challenger confirmation, confidence decay, dropped after 1 s without evidence). false = the 0.12.2 [PathTracker]. */
    val temporalTracking: Boolean = true,
    val minFreeProgressM: Double = 2.0,
    val minPathConfidence: Double = 0.5,
    val modeHoldFrames: Int = 5,         // hysteresis before switching between modes
    /** Owner, round 5: while the followed source (line, lane, parallel line, free path) is still seen, another
     *  source must be the preferred one for longer than this before the path switches to it. */
    val sourceSwitchS: Double = 1.0,
    val freeEndWarnMarginM: Double = 1.0, // warn when walkable ground ends within stop distance + this
    val freeEndHysteresisM: Double = 0.5, // leaving a stop or a warning needs this much more ground
    val resumeFrames: Int = 10,          // ~0.7 s of a usable path before guidance resumes after a stop
    val suddenMps: Double = 1.5,         // how fast the end of the ground may come closer beyond the runner's own speed
    val progressConfirmFrames: Int = 6,  // a sudden much shorter way ahead must repeat this many frames to count
    val turnMinDeg: Double = 15.0,       // a side route must bend at least this much to be a turn
    val turnPreferenceM: Double = 1.5,   // with both sides open, one must go this much further to be named
)

/**
 * Chooses and maintains the virtual path. Order of preference: a line under the runner, the
 * center of a lane between two boundary lines, then a virtual path through walkable space.
 * Obstacles are checked against the chosen path (including where moving ones will be when the
 * runner gets there). A detour is taken only when the free-space route around the obstacle is
 * visible and verified; otherwise the decision is STOP. Low confidence never yields a direction.
 */
class IntelligentPathPlanner(private val config: PlannerConfig = PlannerConfig()) {
    /** This frame's raw path measurement fed to the tracker (before temporal tracking), and its kind; for diagnostics. */
    var lastMeasurement: PathCurve? = null
        private set
    var lastMeasurementKind: PathMode? = null
        private set

    private val tracker: PathTrack = if (config.temporalTracking) TemporalPathTracker() else LegacyPathTrack()
    private var clockMs = 0L

    val trackState: TrackState get() = tracker.state
    val trackConfidence: Double get() = tracker.confidence

    /** The temporal tracker, when used (diagnostics: prediction-only frames, path switches). */
    val temporal: TemporalPathTracker? get() = tracker as? TemporalPathTracker
    private var trackedMode: PathMode? = null
    private var lastMode = PathMode.STOP
    private var pendingMode: PathMode? = null
    private var switchKind: PathMode? = null
    private var switchS = 0.0
    private var pendingCount = 0
    private var parallelOffset: Double? = null   // locked distance to a line followed alongside
    private var resumeCount = 0
    private var guided = false                   // guidance has started once; later stops resume slowly
    private var freeEndStopped = false
    private var freeEndWarned = false
    private var gatedProgress: Double? = null
    private var shortFrames = 0
    private var lowVisibilityFrames = 0
    private var coasting = false

    fun update(input: FrameInput): PathDecision {
        if (!coasting) {
            lastMeasurement = null; lastMeasurementKind = null
            clockMs = input.timeMs ?: (clockMs + (input.dt * 1000).toLong())
        }
        if (input.visibility < config.minVisibility) {
            lowVisibilityFrames++
            val canCoast = lowVisibilityFrames < config.poorVisibilityFrames && trackedMode != null &&
                (tracker.state == TrackState.TRACKING || tracker.state == TrackState.COASTING) && tracker.confidence >= config.minPathConfidence
            if (!canCoast) {
                tracker.update(null, clockMs, input.ego)
                return commit(PathDecision(PathMode.STOP, null, 0.0, StopReason.POOR_VISIBILITY))
            }
            // Nothing measured in this frame: the tracked path coasts, the obstacles are checked against it
            val keepGate = gatedProgress
            coasting = true
            val d = try {
                update(input.copy(lines = emptyList(), freeRoute = null, freeGrid = null, sideRoutes = emptyList(),
                    freeCenterline = null, visibility = config.minVisibility))
            } finally { coasting = false }
            gatedProgress = keepGate
            return d
        }
        if (!coasting) lowVisibilityFrames = 0

        // 1. Line or lane measurement, preferring the kind already being tracked
        // Within lineMaxOffsetM exactly the 0.13.0 choice. Only when no line is that close: the line nearest the locked
        // offset while running beside a distant line (no jump to another marking), else a strong line up to 2 m away.
        // Measured: giving the locked line priority over near lines kept a parallel lock where the runner was on the line.
        val locked = parallelOffset.takeIf { trackedMode == PathMode.LINE_PARALLEL && config.followDistantLines }
        val line = input.lines.firstOrNull { abs(it.lateralAtRunner(1.0)) <= config.lineMaxOffsetM }
            ?: locked?.let { off ->
                input.lines.filter { abs(it.lateralAtRunner(1.0)) <= config.parallelMaxOffsetM && abs(it.lateralAtRunner(1.0) - off) <= config.parallelGateM }
                    .minByOrNull { abs(it.lateralAtRunner(1.0) - off) }
            }
            ?: input.lines.firstOrNull { config.followDistantLines && isStrongLine(it) && abs(it.lateralAtRunner(1.0)) <= config.parallelMaxOffsetM }
        val laneFound = findLane(input.lines)
        val lane = laneFound?.first
        // A line that bounds the runner's lane is an edge, never the path; the lane center is.
        // A single line is followed only when it is a guide line under the runner (not a lane
        // boundary) or when it is the only guide in view.
        val isBoundary = line != null && laneFound != null && (line === laneFound.second || line === laneFound.third)
        val onLine = line != null && !isBoundary && abs(line.lateralAtRunner(1.0)) <= config.onLineM
        var (measurement, kind) = when {
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
            // Lines lost for a moment while a lane/line was being tracked, and the free route is short:
            // keep coasting the tracked lane (the tracker drops it after its coast frames) rather
            // than trust a worse source. Owner's athletics clip: one frame without lines switched to
            // free space, the segmentation (which calls tartan "wall") ended the way at 3 m and the
            // runner was stopped on an empty track. A free route shorter than emergencyStopM is never
            // overridden (that may be a real block), and obstacles are still checked on the coasted path.
            trackedMode in LINE_MODES && (tracker.state == TrackState.TRACKING || tracker.state == TrackState.COASTING) &&
                input.freeRoute != null && input.freeRoute.progressM >= config.emergencyStopM &&
                input.freeRoute.progressM < max(config.minStopDistanceM, input.runnerSpeedMps * config.reactionTimeS + 0.5) + config.freeEndWarnMarginM ->
                null to null
            // No line: the route through walkable ground is smoothed by the same tracker, so the
            // runner does not feel it jump between frames (raw routes jump 15–35° at p90 on
            // SANPO clips because the planner's goal cell changes from frame to frame).
            // The strip's centreline keeps a bend as curvature; the route (a shortest path) cuts it.
            input.freeRoute != null && input.freeRoute.progressM >= config.minFreeProgressM ->
                (input.freeCenterline?.curve ?: input.freeRoute.toCurve()) to PathMode.FREE_PATH
            else -> null to null
        }
        // Source hysteresis: keep following the current source while it is still seen, until the new one
        // has been preferred for sourceSwitchS (a lane edge that flickers in and out does not switch the path).
        val current = trackedMode
        val stay: PathCurve? = if (kind != null && current != null && kind != current && tracker.state == TrackState.TRACKING) when (current) {
            PathMode.LANE_CENTER -> lane
            PathMode.LINE -> line?.takeIf { !isBoundary }?.curve
            PathMode.LINE_PARALLEL -> line?.let { l -> parallelOffset?.let { shifted(l.curve, -it) } }
            PathMode.FREE_PATH -> input.freeRoute?.takeIf { it.progressM >= config.minFreeProgressM }?.let { input.freeCenterline?.curve ?: it.toCurve() }
            else -> null
        } else null
        if (stay != null) {
            switchS = if (kind == switchKind) switchS + input.dt else input.dt
            switchKind = kind
            if (switchS <= config.sourceSwitchS) { measurement = stay; kind = current }
        } else { switchKind = null; switchS = 0.0 }
        if (kind != null && kind != trackedMode && tracker.state == TrackState.TRACKING) tracker.reset()
        lastMeasurement = measurement
        lastMeasurementKind = kind
        tracker.update(measurement, clockMs, input.ego)
        if (kind != null) trackedMode = kind

        // 2. Candidate path: tracked line/lane if trustworthy and on walkable ground, else free space
        var decision: PathDecision? = null
        val tracked = tracker.curve
        if (tracked != null && tracker.confidence >= config.minPathConfidence && trackedMode != null) {
            if (input.freeGrid == null || staysWalkable(tracked, input.freeGrid)) {
                val conf = if (trackedMode == PathMode.FREE_PATH) 0.7 * input.visibility * tracker.confidence else tracker.confidence
                decision = PathDecision(trackedMode!!, tracked, conf,
                    stripHalfWidthM = if (trackedMode == PathMode.FREE_PATH) input.freeCenterline?.halfWidthM else null)
            }
        }
        val free = input.freeRoute
        if (decision == null && free != null && free.progressM >= config.minFreeProgressM) {
            decision = (input.freeCenterline?.curve ?: free.toCurve())?.let { PathDecision(PathMode.FREE_PATH, it, 0.7 * input.visibility, stripHalfWidthM = input.freeCenterline?.halfWidthM) }
        }
        if (decision == null) {
            // The ground ahead is too short to follow, but a side clearly goes on: turn, don't stop.
            if (free != null) {
                val stopAtShort = max(config.minStopDistanceM, input.runnerSpeedMps * config.reactionTimeS + 0.5)
                turnAway(input, stopAtShort, free.progressM)?.let { return commit(it) }
            }
            // Walkable ground under the runner that ends within stopping distance, with no side open, is a
            // closed way (it used to be called low confidence); no ground and no line at all is NO_PATH.
            if (free != null) {
                val stopAtShort = max(config.minStopDistanceM, input.runnerSpeedMps * config.reactionTimeS + 0.5)
                if (free.progressM < stopAtShort) return commit(PathDecision(PathMode.STOP, null, 0.0, StopReason.PATH_UNSAFE, obstacleAheadM = free.progressM))
            }
            val reason = if (tracked != null || free != null) StopReason.LOW_CONFIDENCE else StopReason.NO_PATH
            return commit(PathDecision(PathMode.STOP, null, 0.0, reason))
        }

        val stopAt = max(config.minStopDistanceM, input.runnerSpeedMps * config.reactionTimeS + 0.5)

        // 2b. Without a line, the way ahead is only as long as the walkable ground: when it ends
        // (a bench, a wall, a hedge across the path) before the runner could stop, stop now; a
        // little further, warn. Found on the owner's night video: the path ran into a bench.
        // Both thresholds have hysteresis, so ground that wavers around them does not make the
        // runner feel stop, go, stop.
        if (decision.mode == PathMode.FREE_PATH && free != null) {
            val progress = gateProgress(free.progressM, input)
            val stopLimit = stopAt + if (freeEndStopped) config.freeEndHysteresisM else 0.0
            freeEndStopped = progress < stopLimit
            if (freeEndStopped) {
                freeEndWarned = true
                // The owner wants stops only in extreme cases: where the ground clearly goes on to
                // one side, follow that side instead of stopping, unless the end is right there.
                turnAway(input, stopAt, progress)?.let { return commit(it) }
                return commit(PathDecision(PathMode.STOP, null, 0.0, StopReason.PATH_UNSAFE, obstacleAheadM = progress, turnSide = turnSide(input, stopAt)))
            }
            val warnLimit = stopAt + config.freeEndWarnMarginM + if (freeEndWarned) config.freeEndHysteresisM else 0.0
            freeEndWarned = progress < warnLimit
            if (freeEndWarned) decision = decision.copy(obstacleAheadM = progress)
        } else {
            freeEndStopped = false
            freeEndWarned = false
            if (free == null) gatedProgress = null
        }

        // 3. Obstacles on the chosen path
        val path = decision.path!!.sample(0.2, 0.0, max(decision.path.farM, 3.0))
        var nearest: Pair<TrackedObstacle, Double>? = null
        for (o in input.obstacles) {
            if (!o.confirmed && o.position.forward > 1.5) continue // single-frame blips far away
            if (Receding.isReceding(o, input.runnerSpeedMps)) continue // walking ahead at the runner's pace: the gap is not closing
            val (dNow, alongNow) = distanceToPath(o.position, path)
            val tReach = alongNow / max(input.runnerSpeedMps, 0.5)
            val (dLater, alongLater) = distanceToPath(o.predict(tReach), path)
            val clearance = config.runnerRadiusM + o.radiusM + config.obstacleMarginM
            val hit = when {
                dNow < clearance -> alongNow
                o.speed > 0.3 && dLater < clearance -> alongLater // moving into the path
                else -> null
            }
            // Where the walkable ground ends the path-end rule already warns, turns or stops; a still
            // object standing at that end or past it (a bench the ground runs up to, something behind
            // the grass edge) is the reason the ground ends, not a second warning. If segmentation
            // missed the object and calls the ground under it walkable, the object lies inside the
            // walkable part and is still warned. Something moving is always kept.
            val walkableEnd = if (decision.mode == PathMode.FREE_PATH) free?.progressM else null
            if (hit != null && walkableEnd != null && o.speed <= 0.3 && hit >= walkableEnd - config.walkableEndToleranceM) continue
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
        turnAway(input, stopAt, along, obstacle)?.let { return commit(it) }
        return commit(PathDecision(PathMode.STOP, null, 0.0, StopReason.OBSTACLE_NO_ALTERNATIVE, along, obstacle, turnSide(input, stopAt)))
    }

    /**
     * Instead of a stop: the way ahead is blocked or ends at [aheadM], but the ground clearly goes on
     * to one side ([turnSide]); the runner is guided along that side's route (a detour with the
     * obstacle warning, so the cue is "obstacle, pass on the left/right"). Null when no side is
     * clearly open or the block is closer than [PlannerConfig.emergencyStopM] (then it is a stop).
     */
    private fun turnAway(input: FrameInput, stopAt: Double, aheadM: Double, obstacle: TrackedObstacle? = null): PathDecision? {
        if (aheadM < config.emergencyStopM) return null
        val side = turnSide(input, stopAt) ?: return null
        val route = input.sideRoutes.filter { (it.steerDeg < 0) == (side == Side.LEFT) }.maxByOrNull { it.progressM } ?: return null
        val curve = route.toCurve() ?: return null
        return PathDecision(PathMode.DETOUR, curve, 0.6 * input.visibility, obstacleAheadM = aheadM, blocking = obstacle, turnSide = side)
    }

    /**
     * Mode hysteresis: STOP is immediate; resuming after a stop needs [PlannerConfig.resumeFrames]
     * usable frames in a row (a brief clear frame between stops must not be felt as "go"); other
     * mode changes must persist for a few frames.
     */
    private fun commit(d: PathDecision): PathDecision {
        if (d.mode == PathMode.STOP) {
            lastMode = d.mode; pendingMode = null; pendingCount = 0; resumeCount = 0
            return d
        }
        if (lastMode == PathMode.STOP) {
            // The first start is already held back by the readiness check (and the tracker's
            // confirmation); a stop during the run must not be followed by a flickering "go".
            if (guided && ++resumeCount < config.resumeFrames) {
                return PathDecision(PathMode.STOP, null, 0.0, StopReason.LOW_CONFIDENCE, d.obstacleAheadM, d.blocking, d.turnSide)
            }
            lastMode = d.mode; resumeCount = 0; guided = true
            return d
        }
        if (d.mode == lastMode) {
            pendingMode = null; pendingCount = 0
            return d
        }
        if (d.mode == PathMode.DETOUR) { // avoiding: act now
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
    /** Long, continuous and starting near the runner: a painted line worth running beside even 1–2 m away. */
    private fun isStrongLine(l: LineCandidate) = l.coverageM >= config.strongLineCoverageM &&
        l.continuity >= config.strongLineContinuity && l.curve.nearM <= config.strongLineNearM

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

    /**
     * How far the walkable ground goes, gated by physics: it cannot come closer faster than the
     * runner moves (plus [PlannerConfig.suddenMps]). A much shorter reading in one frame is
     * almost always a missed patch of ground (night, a band of shadow; the owner's video lost the
     * ground right ahead for ~10 frames in a fence shadow), so it counts only after it has
     * repeated for [PlannerConfig.progressConfirmFrames] frames in a row. Things that move into
     * the path are the object tracker's job and are not gated here.
     */
    private fun gateProgress(raw: Double, input: FrameInput): Double {
        val prev = gatedProgress
        val floor = prev?.let { it - (input.runnerSpeedMps + config.suddenMps) * input.dt }
        val out = if (floor == null || raw >= floor) {
            shortFrames = 0
            raw
        } else if (++shortFrames >= config.progressConfirmFrames) {
            shortFrames = 0
            raw
        } else floor
        gatedProgress = out
        return out
    }

    /**
     * After a stop for something straight ahead: the side to turn to, when walkable ground clearly
     * goes on there (well past the stopping distance, at a real angle) and not on the other side
     * as well; otherwise none, and the runner only stops.
     */
    private companion object {
        val LINE_MODES = setOf(PathMode.LANE_CENTER, PathMode.LINE, PathMode.LINE_PARALLEL)
    }

    private fun turnSide(input: FrameInput, stopAt: Double): Side? {
        val open = input.sideRoutes.filter { it.progressM >= stopAt + config.freeEndWarnMarginM && abs(it.steerDeg) >= config.turnMinDeg }
        val left = open.filter { it.steerDeg < 0 }.maxOfOrNull { it.progressM }
        val right = open.filter { it.steerDeg > 0 }.maxOfOrNull { it.progressM }
        return when {
            left != null && right == null -> Side.LEFT
            right != null && left == null -> Side.RIGHT
            left != null && right != null && abs(left - right) >= config.turnPreferenceM -> if (left > right) Side.LEFT else Side.RIGHT
            else -> null
        }
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
