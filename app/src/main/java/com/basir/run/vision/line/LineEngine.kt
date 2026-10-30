package com.basir.run.vision.line

import android.os.SystemClock
import com.basir.run.vision.CameraFrame
import com.basir.run.vision.Detection
import com.basir.run.vision.FrameQuality
import run.basir.engine.CameraModel
import run.basir.engine.ContrastLineDetector
import run.basir.engine.FrameInput
import run.basir.engine.GuidanceCommand
import run.basir.engine.GuidanceController
import run.basir.engine.IntelligentPathPlanner
import run.basir.engine.LineCandidate
import run.basir.engine.LineExtractor
import run.basir.engine.ObstacleDetection
import run.basir.engine.PathDecision
import run.basir.engine.RgbImage
import kotlin.math.max

/** What the Virtual Path Engine made of one frame. */
class LineResult(
    /** Camera model at the analysis resolution; maps ground points to analysis pixels. */
    val camera: CameraModel,
    val lines: List<LineCandidate>,
    val decision: PathDecision,
    val command: GuidanceCommand,
    val processingMs: Long,
) {
    val width get() = camera.width
    val height get() = camera.height
}

/**
 * Runs the Virtual Path Engine (engine module, run.basir.engine) on each camera frame:
 * color-agnostic line detection → line candidates on the ground → path planner (guide line,
 * lane centre, or parallel to a lone side line, with obstacles checked against the path) →
 * guidance command (pure pursuit, bend preview, no left-right flicker).
 *
 * The frame is read straight from the YUV planes into a small upright RGB image, [WIDTH]
 * pixels wide, which keeps the work per frame low. The camera tilt comes from the gravity
 * sensor and the height from the runner's settings, so distances are in metres on the ground.
 */
class LineEngine {

    private val detector = ContrastLineDetector()
    private val extractor = LineExtractor()
    private var planner = IntelligentPathPlanner()
    private var guidance = GuidanceController()
    private var obstacles = run.basir.engine.ObstacleTracker()
    private var lastMs = -1L

    /** Height of the camera above the ground in metres (chest mount). */
    @Volatile var cameraHeightM = DEFAULT_HEIGHT_M

    fun reset() {
        planner = IntelligentPathPlanner()
        guidance = GuidanceController()
        obstacles = run.basir.engine.ObstacleTracker()
        lastMs = -1L
    }

    /**
     * [freshDetections] are object detections from a detector run that finished since the last
     * frame (empty otherwise), so one detection is never counted twice by the obstacle tracker.
     */
    fun process(frame: CameraFrame, hfovDeg: Double, pitchDeg: Double, quality: FrameQuality, freshDetections: List<Detection>?): LineResult {
        val start = SystemClock.elapsedRealtime()
        val dt = if (lastMs < 0) 1.0 / 15 else ((start - lastMs) / 1000.0).coerceIn(0.01, 0.5)
        lastMs = start
        val image = toRgb(frame, WIDTH)
        val cam = CameraModel(image.width, image.height, hfovDeg, cameraHeightM, pitchDeg)
        val lines = extractor.extract(detector.detect(image, cam), cam)
        val tracked = obstacles.update(freshDetections.orEmpty().mapNotNull { toGround(it, cam) }, dt)
        val visibility = when {
            quality.blocked || quality.tooDark || quality.glare -> 0.2
            quality.blurry -> 0.3
            else -> 1.0
        }
        val decision = planner.update(FrameInput(lines, null, null, tracked, visibility, dt = dt))
        val command = guidance.update(decision)
        return LineResult(cam, lines, decision, command, SystemClock.elapsedRealtime() - start)
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
