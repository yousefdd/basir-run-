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
) {
    val width get() = camera.width
    val height get() = camera.height
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
    private var planner = IntelligentPathPlanner()
    private var guidance = StableGuidanceController()
    private var obstacles = run.basir.engine.ObstacleTracker()
    private var lastMs = -1L

    /** Height of the camera above the ground in metres (chest mount). */
    @Volatile var cameraHeightM = DEFAULT_HEIGHT_M

    /** Expected speed of the runner (walking or running setting); stopping distances follow it. */
    @Volatile var speedMps = RUNNING_MPS

    fun reset() {
        planner = IntelligentPathPlanner()
        guidance = StableGuidanceController()
        obstacles = run.basir.engine.ObstacleTracker()
        lastMs = -1L
        lastFreeSteerDeg = 0.0
        walkableMemory.reset()
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
    ): LineResult {
        val start = SystemClock.elapsedRealtime()
        val dt = if (lastMs < 0) 1.0 / 15 else ((start - lastMs) / 1000.0).coerceIn(0.01, 0.5)
        lastMs = start
        val image = toRgb(frame, WIDTH)
        val cam = CameraModel(image.width, image.height, hfovDeg, cameraHeightM, pitchDeg, rollDeg)
        // The guide-line model is far more precise on the line it knows; the contrast detector
        // covers every other kind of line and phones without the model.
        val modelLines = surface?.line?.let { extractor.extract(it, cam) }.orEmpty()
        val lines = modelLines.ifEmpty { extractor.extract(detector.detect(image, cam), cam) }
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
        val route = grid?.let { freePlanner.plan(it, lastFreeSteerDeg.coerceIn(-MAX_GOAL_DEG, MAX_GOAL_DEG)) }
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
        val decision = planner.update(FrameInput(lines, route, grid, tracked, visibility, speedMps, dt, sideRoutes))
        // States with hysteresis, dwell and curve-vs-offset separation (engine/StableGuidance.kt)
        val command = guidance.update(decision, speedMps, dt).command
        return LineResult(cam, lines, decision, command, SystemClock.elapsedRealtime() - start, route, modelLines.isNotEmpty())
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
