package com.basir.run.vision

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import kotlin.math.ceil

/** Everything measured on one frame, handed from the camera thread to the screen. */
data class FrameSnapshot(
    val timeMs: Long,
    val quality: FrameQuality,
    val path: TrackedPath,
    /** The multi-point running path built from this frame only; nothing is carried over from older frames. */
    val virtualPath: VirtualPath,
    /** Which kind of scene the path came from (painted lines or a walkable surface), or null if none yet. */
    val environment: PathSource?,
    val detections: List<Detection>,
    val findings: List<ObstacleFinding>,
    val obstacle: ObstacleLevel,
    val obstacleDetectorReady: Boolean,
    val shaky: Boolean,
    val framesPerSecond: Float,
    val processingP50Ms: Float,
    val processingP90Ms: Float,
)

/**
 * The Virtual Running Path engine, run on the camera analysis thread for every frame:
 * 1. copy the frame and measure its quality;
 * 2. run both path detectors (painted lines and walkable surface) and let the environment
 *    selector keep the one that fits this scene, with no mode chosen by the user;
 * 3. smooth the path over time, damped while the gyroscope reports a sharp jolt;
 * 4. on every third frame, look for obstacles: the object model plus surface changes inside the
 *    corridor, with motion between runs and a short confirmation;
 * 5. build the multi-point virtual path from this frame only.
 * Processing time per frame is measured here and reported, not assumed.
 */
class GuidancePipeline(
    private val obstacles: ObstacleDetector,
    /** Current rotation rate of the phone in rad/s, or null if there is no gyroscope. */
    private val rotationRate: () -> Float?,
) {

    @Volatile var calibration = Calibration()
        set(value) {
            field = value
            lanes.calibration = value
            surfaces.calibration = value
        }

    private val lanes = LaneDetector()
    private val surfaces = PathRegionDetector()
    private val selector = EnvironmentSelector()
    private val tracker = PathTracker()
    private val intrusions = IntrusionDetector()
    private val motion = ObstacleMotionTracker()
    private val obstacleTracker = ObstacleTracker()
    private val copier = FrameCopier()
    private val durations = ArrayDeque<Long>()
    private val arrivals = ArrayDeque<Long>()
    private var frameIndex = 0L
    private var lastDetections = emptyList<Detection>()
    private var lastFindings = emptyList<ObstacleFinding>()
    private var obstacle = ObstacleLevel.None

    fun reset() {
        lanes.reset()
        selector.reset()
        tracker.reset()
        motion.reset()
        obstacleTracker.reset()
        lastDetections = emptyList()
        lastFindings = emptyList()
        obstacle = ObstacleLevel.None
    }

    fun process(image: ImageProxy): FrameSnapshot {
        val start = SystemClock.elapsedRealtime()
        val frame = copier.copy(image)
        val quality = FrameQuality.of(frame)
        val estimate = selector.choose(lanes.detect(frame), surfaces.detect(frame))
        val shaky = (rotationRate() ?: 0f) > JOLT_RAD_PER_S
        val path = tracker.update(estimate, shaky)

        if (frameIndex % OBSTACLE_EVERY == 0L) {
            lastDetections = if (obstacles.ready) obstacles.detect(image.toBitmap(), image.imageInfo.rotationDegrees) else emptyList()
            val runnerX = calibration.runnerX
            val modelFindings = lastDetections.mapNotNull { ObstacleAssessment.assess(it, path.estimate, runnerX) }
            val surfaceFindings = if (path.stable) intrusions.detect(frame, path.estimate, runnerX) else emptyList()
            val centre = path.estimate.center?.x(1f) ?: runnerX
            lastFindings = motion.annotate(modelFindings, lastDetections, centre) + surfaceFindings
            obstacle = obstacleTracker.update(lastFindings)
        }
        frameIndex++

        val end = SystemClock.elapsedRealtime()
        durations.addLast(end - start)
        while (durations.size > WINDOW) durations.removeFirst()
        arrivals.addLast(end)
        while (arrivals.size > 1 && end - arrivals.first() > 2000) arrivals.removeFirst()
        val fps = if (arrivals.size < 2) 0f else (arrivals.size - 1) * 1000f / (arrivals.last() - arrivals.first()).coerceAtLeast(1)
        val sorted = durations.sorted()

        return FrameSnapshot(
            timeMs = end,
            quality = quality,
            path = path,
            virtualPath = VirtualPath.from(path, MIN_GUIDE_CONFIDENCE),
            environment = selector.active,
            detections = lastDetections,
            findings = lastFindings,
            obstacle = obstacle,
            obstacleDetectorReady = obstacles.ready,
            shaky = shaky,
            framesPerSecond = fps,
            processingP50Ms = percentile(sorted, 0.5),
            processingP90Ms = percentile(sorted, 0.9),
        )
    }

    private fun percentile(sorted: List<Long>, p: Double): Float =
        if (sorted.isEmpty()) 0f else sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)].toFloat()

    companion object {
        private const val OBSTACLE_EVERY = 3L
        private const val WINDOW = 60
        /** Rotation faster than this blurs the frame and swings the view; such frames count less. */
        private const val JOLT_RAD_PER_S = 2.5f
        /** Same threshold the decision engine uses before giving any direction. */
        const val MIN_GUIDE_CONFIDENCE = 0.5f
    }
}

/** Copies the Y, U and V planes into reused buffers so the image can be released quickly. */
private class FrameCopier {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    fun copy(image: ImageProxy): CameraFrame {
        val planes = image.planes
        y = read(planes[0].buffer, y)
        u = read(planes[1].buffer, u)
        v = read(planes[2].buffer, v)
        return CameraFrame(
            y = y,
            yRowStride = planes[0].rowStride,
            width = image.width,
            height = image.height,
            rotationDegrees = image.imageInfo.rotationDegrees,
            u = u,
            v = v,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
        )
    }

    private fun read(buffer: java.nio.ByteBuffer, into: ByteArray): ByteArray {
        buffer.rewind()
        val size = buffer.remaining()
        val target = if (into.size < size) ByteArray(size) else into
        buffer.get(target, 0, size)
        return target
    }
}
