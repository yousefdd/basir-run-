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
 *
 * [rollDeg] is how far the picture is turned counter-clockwise because the phone leans to one
 * side on the chest (positive when the phone's top leans right). Pixels are turned back before
 * they are projected, so a straight line ahead stays straight on the ground while the phone
 * rocks from side to side. Row-based helpers ([horizonRow], [groundDepth]) stay those of the
 * level camera; they are only used to bound searches.
 */
data class CameraModel(
    val width: Int,
    val height: Int,
    val hfovDeg: Double,
    val heightM: Double,
    val pitchDeg: Double,
    val rollDeg: Double = 0.0,
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

    private val sinR = sin(rollDeg.toRadians())
    private val cosR = cos(rollDeg.toRadians())

    fun imageToGround(u: Double, v: Double): GroundPoint? {
        // Turn the pixel back clockwise by the roll: where a level camera would have seen it
        val du = u - cx
        val dv = v - cy
        val lu = cx + du * cosR - dv * sinR
        val lv = cy + du * sinR + dv * cosR
        val y = (lv - cy) / focalPx
        val x = (lu - cx) / focalPx
        val t = groundDepth(lv) ?: return null
        return GroundPoint(forward = t * (cosT - y * sinT), lateral = t * x)
    }

    /** Pixel (u, v) of a ground point, or null if it is behind the camera. */
    fun groundToImage(p: GroundPoint): Pair<Double, Double>? {
        val z = heightM * sinT + p.forward * cosT
        if (z <= 1e-6) return null
        val y = heightM * cosT - p.forward * sinT
        val du = focalPx * p.lateral / z
        val dv = focalPx * y / z
        // The picture of a rolled phone is turned counter-clockwise (image rows grow downwards)
        return Pair(cx + du * cosR + dv * sinR, cy - du * sinR + dv * cosR)
    }

    /** Ground meters covered by one pixel horizontally at row [v]. */
    fun lateralMetersPerPixel(v: Double): Double? = groundDepth(v)?.let { it / focalPx }
}

/**
 * Camera tilt from the phone's gravity sensor (device axes: x to the right of the screen, y up the
 * screen, z out of the screen; the reading points up, +z when the phone lies face up). Valid for the
 * phone held upright (portrait), which the app enforces.
 *  - pitch: degrees the rear camera looks below the horizon (0 = straight ahead, 90 = straight down)
 *  - roll: degrees the phone is turned clockwise as the runner sees its screen; the image then shows
 *    the world turned counter-clockwise, which [CameraModel.rollDeg] undoes (same sign).
 */
object GravityTilt {
    fun pitchBelowHorizonDeg(x: Double, y: Double, z: Double): Double? {
        val g = kotlin.math.sqrt(x * x + y * y + z * z)
        if (g < 1.0) return null
        return Math.toDegrees(kotlin.math.asin((z / g).coerceIn(-1.0, 1.0)))
    }

    fun rollDeg(x: Double, y: Double, z: Double): Double? {
        val g = kotlin.math.sqrt(x * x + y * y + z * z)
        if (g < 1.0) return null
        return Math.toDegrees(kotlin.math.atan2(-x, y))
    }
}
