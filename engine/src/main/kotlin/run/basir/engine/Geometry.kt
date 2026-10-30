package run.basir.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A point on the ground in the runner frame: meters ahead and meters to the right (negative = left). */
data class GroundPoint(val forward: Double, val lateral: Double)

/** Per-pixel probability map (row-major), e.g. a segmentation model's line or walkable-surface output. */
class ProbabilityMask(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(data.size == width * height) { "mask size ${data.size} != $width x $height" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]
    operator fun set(x: Int, y: Int, value: Float) {
        data[y * width + x] = value
    }
}

fun Double.toRadians() = this * PI / 180.0
fun Double.toDegrees() = this * 180.0 / PI

/**
 * Pinhole camera on the runner's body looking forward and tilted down by [pitchDeg]
 * (negative = tilted up), at [heightM] above flat ground. Maps image pixels to ground
 * points and back. Pitch should come from the phone's motion sensor; height is calibrated.
 */
data class CameraModel(
    val width: Int,
    val height: Int,
    val hfovDeg: Double,
    val heightM: Double,
    val pitchDeg: Double,
) {
    val focalPx: Double = (width / 2.0) / tan(hfovDeg.toRadians() / 2.0)
    private val cx = (width - 1) / 2.0
    private val cy = (height - 1) / 2.0
    private val sinT = sin(pitchDeg.toRadians())
    private val cosT = cos(pitchDeg.toRadians())

    /** Same camera at another image resolution (e.g. the model's mask size). */
    fun resized(newWidth: Int, newHeight: Int) = copy(width = newWidth, height = newHeight)

    /** Image row of the horizon; rows below it see the ground. */
    val horizonRow: Double get() = cy - focalPx * tan(pitchDeg.toRadians())

    /** Camera depth (along the optical axis) of the ground seen at row [v]; null at/above the horizon. */
    fun groundDepth(v: Double): Double? {
        val down = (v - cy) / focalPx * cosT + sinT
        return if (down > 1e-4) heightM / down else null
    }

    fun imageToGround(u: Double, v: Double): GroundPoint? {
        val y = (v - cy) / focalPx
        val x = (u - cx) / focalPx
        val t = groundDepth(v) ?: return null
        return GroundPoint(forward = t * (cosT - y * sinT), lateral = t * x)
    }

    /** Pixel (u, v) of a ground point, or null if it is behind the camera. */
    fun groundToImage(p: GroundPoint): Pair<Double, Double>? {
        val z = heightM * sinT + p.forward * cosT
        if (z <= 1e-6) return null
        val y = heightM * cosT - p.forward * sinT
        return Pair(cx + focalPx * p.lateral / z, cy + focalPx * y / z)
    }

    /** Ground meters covered by one pixel horizontally at row [v]. */
    fun lateralMetersPerPixel(v: Double): Double? = groundDepth(v)?.let { it / focalPx }
}
