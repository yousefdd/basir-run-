package com.basir.run.vision

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.basir.run.engine.RouteMode
import kotlin.math.ceil

/** Everything measured on one frame, handed from the camera thread to the screen. */
data class FrameSnapshot(
    val timeMs: Long,
    val quality: FrameQuality,
    val path: TrackedPath,
    /** The multi-point running path built from this frame only; nothing is carried over from older frames. */
    val virtualPath: VirtualPath,
    val detections: List<Detection>,
    val findings: List<ObstacleFinding>,
    val obstacle: ObstacleLevel,
    val obstacleDetectorReady: Boolean,
    val framesPerSecond: Float,
    val processingP50Ms: Float,
    val processingP90Ms: Float,
)

/**
 * Runs on the camera analysis thread: copies the frame, checks its quality, finds the path for
 * the chosen route mode, smooths it over time and, on every third frame, looks for obstacles.
 * Processing time per frame is measured here and reported, not assumed.
 */
class GuidancePipeline(private val obstacles: ObstacleDetector) {

    @Volatile var mode: RouteMode = RouteMode.Track
    @Volatile var calibration = Calibration()
        set(value) {
            field = value
            lanes.calibration = value
            surfaces.calibration = value
        }

    private val lanes = LaneDetector()
    private val surfaces = PathRegionDetector()
    private val tracker = PathTracker()
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
        tracker.reset()
        obstacleTracker.reset()
        obstacle = ObstacleLevel.None
    }

    fun process(image: ImageProxy): FrameSnapshot {
        val start = SystemClock.elapsedRealtime()
        val frame = copier.copy(image)
        val quality = FrameQuality.of(frame)
        val estimate = when (mode) {
            RouteMode.Track, RouteMode.Indoor -> lanes.detect(frame)
            RouteMode.Park -> surfaces.detect(frame)
            RouteMode.Unknown -> {
                // Use whichever method gives a tracked path with more confidence; never mix them.
                val a = lanes.detect(frame)
                val b = surfaces.detect(frame)
                listOf(a, b).filter { it.status == PathStatus.Tracked }.maxByOrNull { it.confidence } ?: a
            }
        }
        val path = tracker.update(estimate)

        if (obstacles.ready && frameIndex % OBSTACLE_EVERY == 0L) {
            lastDetections = obstacles.detect(image.toBitmap(), image.imageInfo.rotationDegrees)
            lastFindings = lastDetections.mapNotNull { ObstacleAssessment.assess(it, path.estimate, calibration.runnerX) }
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
            detections = lastDetections,
            findings = lastFindings,
            obstacle = obstacle,
            obstacleDetectorReady = obstacles.ready,
            framesPerSecond = fps,
            processingP50Ms = percentile(sorted, 0.5),
            processingP90Ms = percentile(sorted, 0.9),
        )
    }

    private fun percentile(sorted: List<Long>, p: Double): Float =
        if (sorted.isEmpty()) 0f else sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)].toFloat()

    companion object {
        private const val OBSTACLE_EVERY = 3L
        /** Same threshold the decision engine uses before giving any direction. */
        const val MIN_GUIDE_CONFIDENCE = 0.5f
        private const val WINDOW = 60
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
