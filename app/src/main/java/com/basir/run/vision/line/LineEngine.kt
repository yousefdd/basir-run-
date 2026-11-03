package com.basir.run.vision.line

import android.os.SystemClock
import com.basir.run.vision.CameraFrame
import com.basir.run.vision.Detection
import com.basir.run.vision.FrameQuality
import run.basir.engine.CameraModel
import run.basir.engine.ContrastLineDetector
import run.basir.engine.FreeRoute
import run.basir.engine.FreeSpaceConfig
import run.basir.engine.FreeSpacePlanner
import run.basir.engine.FrameInput
import run.basir.engine.GroundGrid
import run.basir.engine.GuidanceCommand
import run.basir.engine.StableGuidanceController
import run.basir.engine.IntelligentPathPlanner
import run.basir.engine.LineCandidate
import run.basir.engine.LineExtractor
import run.basir.engine.ObstacleDetection
import run.basir.engine.PathDecision
import run.basir.engine.RgbImage
import run.basir.engine.WalkableMemory
import com.basir.run.vision.seg.SurfaceResult
import kotlin.math.max

/** What the Virtual Path Engine made of one frame. */
class LineResult(
    /** Camera model at the analysis resolution; maps ground points to analysis pixels. */
    val camera: CameraModel,
    val lines: List<LineCandidate>,
    val decision: PathDecision,
    val command: GuidanceCommand,
    val processingMs: Long,
    /** Route through the walkable ground when the surface model is available (drawn for the developer). */
    val freeRoute: FreeRoute? = null,
    /** The lines came from the guide-line model rather than the colour-contrast detector. */
    val lineFromModel: Boolean = false,
    /** This frame's raw path measurement before temporal tracking, and its kind (developer view, diagnostics). */
    val rawPath: run.basir.engine.PathCurve? = null,
    val rawKind: run.basir.engine.PathMode? = null,
    /** The tracked path before the stability controller (obstacles were checked against this one). */
    val trackedPath: run.basir.engine.PathCurve? = null,
    val trackState: run.basir.engine.TrackState = run.basir.engine.TrackState.SEARCHING,
    val trackConfidence: Double = 0.0,
    /** No measurement this frame: the path is the tracker's ego-motion prediction (drawn as "predicted"). */
    val predictedOnly: Boolean = false,
    /** Age of the surface masks used (ms) and the motion that was compensated for it. */
    val surfaceAgeMs: Long = 0,
    val ego: run.basir.engine.EgoMotion? = null,
    /** Round 8: sharp-corner phase (Corner Detection & Navigation Engine); NONE when there is no corner or it is off. */
    val corner: run.basir.engine.CornerState = run.basir.engine.CornerState(),
) {
    val width get() = camera.width
    val height get() = camera.height

    /**
     * The followed path as a strip lying on the ground, in pixels of [camera] (scale by view width / [width] to draw):
     * it narrows with distance, tilts with the phone and covers only the measured part of the path. Draw this
     * instead of FrameSnapshot.virtualPath (image-space edges of the fallback detectors). Null without a path.
     */
    val ribbon: run.basir.engine.GroundRibbon? by lazy { decision.path?.let { run.basir.engine.GroundRibbon.build(it, camera) } }
}

/**
 * Runs the Virtual Path Engine (engine module, run.basir.engine) on each camera frame:
 * line detection (the guide-line model when it sees a line, else the colour-contrast detector)
 * → line candidates on the ground → walkable ground from the surface model → a route through it
 * (free-space planner) → path planner (guide line, lane centre, parallel to a lone side line,
 * or the free route when there is no line; obstacles checked against the path) → guidance
 * command (pure pursuit, bend preview, no left-right flicker). Without a line and without
 * walkable ground the planner stops; it never guesses a side.
 *
 * The frame is read straight from the YUV planes into a small upright RGB image, [WIDTH]
 * pixels wide, which keeps the work per frame low. The camera tilt comes from the gravity
 * sensor and the height from the runner's settings, so distances are in metres on the ground.
 */
class LineEngine {

    private val detector = ContrastLineDetector()
    private val extractor = LineExtractor()
    private val freeConfig = FreeSpaceConfig()
    private val freePlanner = FreeSpacePlanner(freeConfig)
    private var lastFreeSteerDeg = 0.0
    private val walkableMemory = WalkableMemory()
    /** Path Stability Controller settings (the research replay switches them to compare with 0.13.0). */
    var stabilityConfig = run.basir.engine.StabilityConfig()
        set(value) { field = value; stability = run.basir.engine.PathStabilityController(value) }
    private var stability = run.basir.engine.PathStabilityController(stabilityConfig)
    private val egoHistory = run.basir.engine.EgoHistory()
    private val visualYaw = run.basir.engine.VisualYawEstimator()
    /** Turn from the video when there is no gyroscope reading (light optical flow of the far scene). */
    @Volatile var visualEgo = true
    /** The turn used for this frame (rad, + right), for diagnostics. */
    var lastYawRad = 0.0
        private set
    private var lastSwitches = 0
    private var lastMode: run.basir.engine.PathMode? = null

    /** Research round 6: draw and steer by the stability-controlled path (false = the 0.12.2 behaviour). */
    @Volatile var stabilize = true

    /** Research round 6: follow the middle of the walkable strip (CorridorCenterline) on paths without lines, so a bend
     *  stays a bend instead of the route's straight chord across it (false = the 0.12.2 behaviour: route only). */
    @Volatile var useCenterline = true
    /** Planner settings (the research replay switches them to compare with the 0.12.2 behaviour). */
    var plannerConfig = run.basir.engine.PlannerConfig()
        set(value) { field = value; planner = IntelligentPathPlanner(value) }
    private var planner = IntelligentPathPlanner(plannerConfig)
    /** Guidance state-machine settings (the research replay switches them to compare with 0.14.0). */
    var guidanceConfig = run.basir.engine.StableGuidanceConfig()
        set(value) { field = value; guidance = StableGuidanceController(value) }
    private var guidance = StableGuidanceController(guidanceConfig)
    /** Round 8: Corner Detection & Navigation Engine (false = the 0.14.0 behaviour, for the research replay). */
    @Volatile var cornerNavigation = true
    private val corners = run.basir.engine.CornerNavigationEngine()
    /** Diagnostics of the corner engine for the last frame (developer overlay, research replay). */
    val cornerDebug: String get() = corners.lastDebug + (corners.lastObservation?.let { " | obs ${it.side} ${"%.1f".format(it.distanceM)}m ${it.source} ${"%.2f".format(it.strength)}" } ?: "")
    private var obstacles = run.basir.engine.ObstacleTracker()
    private var lastMs = -1L

    /** Height of the camera above the ground in metres (chest mount). */
    @Volatile var cameraHeightM = DEFAULT_HEIGHT_M

    /** Expected speed of the runner (walking or running setting); stopping distances follow it. */
    @Volatile var speedMps = RUNNING_MPS

    fun reset() {
        planner = IntelligentPathPlanner(plannerConfig)
        guidance = StableGuidanceController(guidanceConfig)
        obstacles = run.basir.engine.ObstacleTracker()
        lastMs = -1L
        lastFreeSteerDeg = 0.0
        walkableMemory.reset()
        stability.reset()
        corners.reset()
        egoHistory.clear()
        visualYaw.reset()
        lastSwitches = 0
        lastMode = null
    }

    /**
     * [freshDetections] are object detections from a detector run that finished since the last
     * frame (empty otherwise), so one detection is never counted twice by the obstacle tracker.
     */
    fun process(
        frame: CameraFrame,
        hfovDeg: Double,
        pitchDeg: Double,
        rollDeg: Double,
        quality: FrameQuality,
        freshDetections: List<Detection>?,
        surface: SurfaceResult? = null,
        /** Turn rate about the vertical (rad/s, + right) from the gyroscope; null without one. */
        yawRateRadPerS: Double? = null,
    ): LineResult {
        val start = SystemClock.elapsedRealtime()
        val dt = if (lastMs < 0) 1.0 / 15 else ((start - lastMs) / 1000.0).coerceIn(0.01, 0.5)
        lastMs = start
        val image = toRgb(frame, WIDTH)
        val cam = CameraModel(image.width, image.height, hfovDeg, cameraHeightM, pitchDeg, rollDeg)
        // How the runner moved since the last frame: advance from the speed setting, turn from the gyroscope or,
        // without one, from the far scene's shift in the picture (light optical flow)
        val flowYaw = if (visualEgo) visualYaw.update(image, cam) else null
        lastYawRad = yawRateRadPerS?.let { it * dt } ?: flowYaw ?: 0.0
        val ego = run.basir.engine.EgoMotion(speedMps * dt, 0.0, lastYawRad)
        egoHistory.add(start, ego)
        // The guide-line model is far more precise on the line it knows; the contrast detector
        // covers every other kind of line and phones without the model.
        val modelLines = surface?.line?.let { extractor.extract(it, cam) }.orEmpty()
        var lineMask = if (modelLines.isNotEmpty()) surface?.line else null
        val lines = modelLines.ifEmpty { detector.detect(image, cam).also { lineMask = it }.let { extractor.extract(it, cam) } }
        // The runner's own advance is removed, so a still bench is still (it used to look as if it
        // came toward the runner at running speed, and was then predicted to step into the path).
        val tracked = obstacles.update(freshDetections.orEmpty()
            .filter { run.basir.engine.DetectionFilter.keep(it.label, it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble(), it.score.toDouble()) }
            .mapNotNull { toGround(it, cam) }, dt, runnerAdvanceM = speedMps * dt)
        // Walkable ground → grid (objects block their cells) → best route through it
        // A short memory of the ground, so one dark frame does not look like a wall
        val walkable = surface?.walkable?.let { walkableMemory.update(it) }
        // People walking ahead at the runner's pace are not carved out of the way (the gap is not closing)
        val grid = walkable?.let { GroundGrid.build(it, cam, footprints(tracked.filterNot { o -> run.basir.engine.Receding.isReceding(o, speedMps) }), freeConfig) }
        // The masks come from a worker thread and are older than this frame: bring what is measured on them to the present
        val surfaceAge = surface?.let { (start - it.frameTimeMs).coerceAtLeast(0) } ?: 0L
        val ageEgo = if (stabilize && surfaceAge > 0) egoHistory.since(surface!!.frameTimeMs) else null
        val route = grid?.let { freePlanner.plan(it, lastFreeSteerDeg.coerceIn(-MAX_GOAL_DEG, MAX_GOAL_DEG)) }?.let { compensate(it, ageEgo) }
        lastFreeSteerDeg = route?.let { 0.7 * it.steerDeg } ?: 0.0
        // When the way ahead ends soon, look left and right: where does the ground go on?
        val sideRoutes = if (grid != null && (route == null || route.progressM < SIDE_LOOK_M)) {
            listOfNotNull(freePlanner.plan(grid, -SIDE_GOAL_DEG), freePlanner.plan(grid, SIDE_GOAL_DEG))
        } else emptyList()
        val visibility = when {
            quality.blocked || quality.tooDark || quality.glare -> 0.2
            quality.blurry -> 0.3
            else -> 1.0
        }
        val centre = if (useCenterline && grid != null && route != null)
            run.basir.engine.CorridorCenterline.extract(grid, route, fovTanHalf = kotlin.math.tan(Math.toRadians(hfovDeg) / 2))
                ?.let { c -> if (ageEgo != null) c.copy(curve = ageEgo.apply(c.curve)) else c }
        else null
        val decision = planner.update(FrameInput(lines, route, grid, tracked, visibility, speedMps, dt, sideRoutes.map { compensate(it, ageEgo) },
            freeCenterline = centre, timeMs = start, ego = ego))
        // Stable path for drawing and direction cues; obstacle decisions above already used the tracked path
        val switches = planner.temporal?.switches ?: 0
        val newTrack = switches != lastSwitches || decision.mode != lastMode
        lastSwitches = switches; lastMode = decision.mode
        val stable = if (stabilize) stability.update(decision.path, start, ego, decision.stripHalfWidthM, newTrack) else null
        val steered = if (stable != null) decision.copy(path = stable.curve, stripHalfWidthM = stable.halfWidthM) else decision
        // Sharp corners: their own evidence (line mask on the ground, path geometry, walkable turn) and phases
        val corner = if (cornerNavigation) {
            // Lines across the image (a corner's perpendicular line) are found by a column-wise stripe test, only while a
            // line is followed; the guide-line model's own mask already sees every orientation.
            val lineMode = steered.mode == run.basir.engine.PathMode.LINE || steered.mode == run.basir.engine.PathMode.LINE_PARALLEL ||
                steered.mode == run.basir.engine.PathMode.LANE_CENTER
            val cornerMask = if (lineMode) mergeMax(lineMask, detector.detectAcross(image, cam)) else lineMask
            corners.update(run.basir.engine.CornerInput(dt, speedMps, lastYawRad, steered.path, steered.mode, planner.trackConfidence,
                cornerMask, cam, route))
        } else run.basir.engine.CornerState()
        // States with hysteresis, dwell and curve-vs-offset separation (engine/StableGuidance.kt). Round 8: at a confirmed
        // corner the line leaving the view is expected, not a closed way (no obstacle warning, no stop from it).
        // Only a corner confirmed by the line itself (its perpendicular continuation seen) covers a "way ends" reading.
        val command = guidance.update(steered, speedMps, dt,
            expectedGap = corner.active && corner.source == run.basir.engine.CornerSource.LINE_INTERSECTION).command
        return LineResult(cam, lines, steered, command, SystemClock.elapsedRealtime() - start, route, modelLines.isNotEmpty(),
            planner.lastMeasurement, planner.lastMeasurementKind, decision.path, planner.trackState, planner.trackConfidence,
            planner.temporal?.predictedOnly == true, surfaceAge, ego, corner)
    }

    private fun mergeMax(a: run.basir.engine.ProbabilityMask?, b: run.basir.engine.ProbabilityMask): run.basir.engine.ProbabilityMask {
        if (a == null) return b
        if (a.width != b.width || a.height != b.height) {
            // resample a onto b's grid (nearest)
            val out = run.basir.engine.ProbabilityMask(b.width, b.height, b.data.copyOf())
            for (y in 0 until b.height) for (x in 0 until b.width) {
                val v = a[(x * a.width / b.width).coerceIn(0, a.width - 1), (y * a.height / b.height).coerceIn(0, a.height - 1)]
                if (v > out[x, y]) out[x, y] = v
            }
            return out
        }
        return run.basir.engine.ProbabilityMask(b.width, b.height, FloatArray(b.data.size) { maxOf(a.data[it], b.data[it]) })
    }

    /** A route measured on an older frame, seen from where the runner is now. */
    private fun compensate(r: FreeRoute, e: run.basir.engine.EgoMotion?): FreeRoute {
        if (e == null || (e.forwardM == 0.0 && e.yawRad == 0.0 && e.lateralM == 0.0)) return r
        val wp = r.waypoints.map { e.apply(it) }.filter { it.forward >= -0.5 }
        if (wp.size < 2) return r
        return r.copy(waypoints = wp, progressM = (r.progressM - e.forwardM).coerceAtLeast(0.0),
            steerDeg = r.steerDeg - Math.toDegrees(e.yawRad))
    }

    /**
     * Ground points covering each tracked object's footprint, two per grid cell, so the grid
     * (which needs [FreeSpaceConfig.minPointsPerCell] points to call a cell occupied) blocks it.
     */
    private fun footprints(obstacles: List<run.basir.engine.TrackedObstacle>): List<run.basir.engine.GroundPoint> {
        val step = freeConfig.cellM
        val out = ArrayList<run.basir.engine.GroundPoint>()
        for (o in obstacles) {
            val r = o.radiusM
            var df = -r
            while (df <= r) {
                var dl = -r
                while (dl <= r) {
                    if (df * df + dl * dl <= r * r) {
                        val p = run.basir.engine.GroundPoint(o.position.forward + df, o.position.lateral + dl)
                        out += p
                        out += p
                    }
                    dl += step
                }
                df += step
            }
        }
        return out
    }

    /** An object's foot point (bottom centre of its box) on the ground, with its width as size. */
    private fun toGround(d: Detection, cam: CameraModel): ObstacleDetection? {
        val v = (d.bottom * cam.height).toDouble().coerceAtMost(cam.height - 1.0)
        val u = ((d.left + d.right) / 2 * cam.width).toDouble()
        val p = cam.imageToGround(u, v) ?: return null
        val mpp = cam.lateralMetersPerPixel(v) ?: return null
        val radius = ((d.right - d.left) * cam.width * mpp / 2).coerceIn(0.15, 1.5)
        return ObstacleDetection(p, radius, d.score.toDouble(), d.label)
    }

    companion object {
        /** Analysis width in pixels; the reference photo gives the same path at 320 as at 640. */
        const val WIDTH = 240
        /** The free route aims where it went last frame, within this bearing, so it follows bends. */
        private const val MAX_GOAL_DEG = 30.0
        /** Side routes are looked for when the way ahead is shorter than this. */
        private const val SIDE_LOOK_M = 6.0
        private const val SIDE_GOAL_DEG = 60.0
        const val RUNNING_MPS = 2.5
        const val WALKING_MPS = 1.4
        const val DEFAULT_HEIGHT_M = 1.3

        /** Upright RGB at [width] pixels, sampled from the YUV planes (BT.601). */
        fun toRgb(frame: CameraFrame, width: Int): RgbImage {
            val scale = frame.uprightWidth.toFloat() / width
            val height = max(1, (frame.uprightHeight / scale).toInt())
            val n = width * height
            val r = IntArray(n)
            val g = IntArray(n)
            val b = IntArray(n)
            for (y in 0 until height) {
                val py = ((y + 0.5f) * scale).toInt().coerceIn(0, frame.uprightHeight - 1)
                for (x in 0 until width) {
                    val px = ((x + 0.5f) * scale).toInt().coerceIn(0, frame.uprightWidth - 1)
                    val l = frame.luma(px, py)
                    val cu = frame.chromaU(px, py) - 128
                    val cv = frame.chromaV(px, py) - 128
                    val i = y * width + x
                    r[i] = (l + 1.402f * cv).toInt().coerceIn(0, 255)
                    g[i] = (l - 0.344f * cu - 0.714f * cv).toInt().coerceIn(0, 255)
                    b[i] = (l + 1.772f * cu).toInt().coerceIn(0, 255)
                }
            }
            return RgbImage(width, height, r, g, b)
        }
    }
}
