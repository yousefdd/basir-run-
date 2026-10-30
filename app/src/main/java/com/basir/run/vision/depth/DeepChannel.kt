package com.basir.run.vision.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import android.util.Log
import com.basir.run.vision.Detection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan
import kotlin.math.roundToInt

/** One result of the slower, model-based channel, computed on a background thread. */
data class DeepResult(
    /** When the frame it was computed from arrived. */
    val frameTimeMs: Long,
    val analysis: DepthAnalysis?,
    /** D-FINE-N objects in upright frame fractions; empty when that model is unavailable. */
    val detections: List<Detection>,
    val hazard: DepthHazard,
    val depthMs: Long,
    val detectMs: Long,
)

/**
 * Runs the models on the phone, away from the camera thread: D-FINE-N names the objects
 * (in place of the blind-navigation tool's YOLO26n) and Depth Anything V2 gives relative depth, from which [FloorPlanner] finds
 * the floor, obstacles of any kind (poles and trunks included), head-level hazards and drops.
 * Only one frame is processed at a time; frames arriving meanwhile are skipped, so lane
 * tracking keeps its full rate. Each result carries the time of its frame, and the caller
 * treats an old result as missing.
 */
class DeepChannel(context: Context) {

    private val depth = DepthEstimator(context)
    private val objects = ObjectDetectorOnnx(context)
    private val fov = CameraFov.of(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val confirm = DepthConfirm()

    @Volatile var latest: DeepResult? = null
        private set

    /** Height of the camera above the ground in metres; distances scale with it. */
    @Volatile var cameraHeightM = CAMERA_HEIGHT_M

    /** Horizontal field of view of the upright frame, from the camera's own focal length and sensor. */
    fun hfovDeg(rotationDegrees: Int): Double {
        val sideways = rotationDegrees == 90 || rotationDegrees == 270
        return (if (sideways) fov?.second else fov?.first) ?: DEFAULT_HFOV
    }

    val depthReady: Boolean get() = depth.ready
    val detectorReady: Boolean get() = objects.ready
    val idle: Boolean get() = !busy.get()

    fun reset() {
        latest = null
        confirm.reset()
    }

    /**
     * Hands a frame to the worker if it is free; returns false when it is busy (the frame is
     * then simply not analysed by this channel). [pitchDeg] is the camera tilt from the gravity
     * sensor; without it the planner estimates the tilt from the depth map itself.
     */
    fun offer(sensorBitmap: Bitmap, rotationDegrees: Int, frameTimeMs: Long, pitchDeg: Float?): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val upright = if (rotationDegrees == 0) sensorBitmap else Bitmap.createBitmap(
                    sensorBitmap, 0, 0, sensorBitmap.width, sensorBitmap.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
                )
                val t0 = SystemClock.elapsedRealtime()
                val detections = if (objects.ready) objects.detect(upright) else emptyList()
                val t1 = SystemClock.elapsedRealtime()
                val analysis = if (depth.ready) {
                    val h = (WORK_WIDTH * upright.height.toFloat() / upright.width).roundToInt()
                    depth.disparity(upright, WORK_WIDTH, h)?.let { disp ->
                        FloorPlanner(FloorPlanner.Geometry(cameraHeightM, pitchDeg?.toDouble(), hfovDeg(rotationDegrees)), DepthHazards.RUNNING)
                            .analyze(disp, WORK_WIDTH, h)
                    }
                } else null
                val t2 = SystemClock.elapsedRealtime()
                val hazard = confirm.update(DepthHazards.assess(analysis))
                latest = DeepResult(frameTimeMs, analysis, detections, hazard, t2 - t1, t1 - t0)
            } catch (e: Exception) {
                Log.w("DeepChannel", "Analysis failed", e)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    fun close() {
        executor.shutdown()
        depth.close()
        objects.close()
    }

    companion object {
        /** Width of the map the planner works on, as in the reference tool. */
        const val WORK_WIDTH = 160
        /** Chest mount of a runner of average height; replaced by the runner's own setting. */
        const val CAMERA_HEIGHT_M = 1.3
        /** Typical horizontal field of view of a rear camera held upright. */
        const val DEFAULT_HFOV = 50.0
    }
}

/** Horizontal field of view of the back camera from its focal length and sensor size. */
object CameraFov {
    /** (across the sensor's long side, across its short side) in degrees, or null if unknown. */
    fun of(context: Context): Pair<Double, Double>? = try {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val c = manager.getCameraCharacteristics(id)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.minOrNull()!!.toDouble()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
        fun deg(side: Float) = Math.toDegrees(2 * atan(side / (2 * focal)))
        deg(maxOf(sensor.width, sensor.height)) to deg(minOf(sensor.width, sensor.height))
    } catch (e: Exception) {
        null
    }
}
