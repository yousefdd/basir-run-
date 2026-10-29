package com.basir.run.vision

/**
 * One camera frame in YUV 4:2:0, read as if the phone were upright. All coordinates used by the
 * vision code are upright pixels (u to the right, v down) or fractions of the upright frame.
 * rotationDegrees is how far the sensor image must turn clockwise to stand upright.
 */
class CameraFrame(
    private val y: ByteArray,
    private val yRowStride: Int,
    private val width: Int,
    private val height: Int,
    val rotationDegrees: Int,
    private val u: ByteArray? = null,
    private val v: ByteArray? = null,
    private val uvRowStride: Int = 0,
    private val uvPixelStride: Int = 1,
) {
    val uprightWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val uprightHeight: Int get() = if (rotationDegrees % 180 == 0) height else width
    val hasColor: Boolean get() = u != null && v != null

    private fun sensorX(px: Int, py: Int) = when (rotationDegrees) {
        90 -> py
        180 -> width - 1 - px
        270 -> width - 1 - py
        else -> px
    }

    private fun sensorY(px: Int, py: Int) = when (rotationDegrees) {
        90 -> height - 1 - px
        180 -> height - 1 - py
        270 -> px
        else -> py
    }

    fun luma(px: Int, py: Int): Int = y[sensorY(px, py) * yRowStride + sensorX(px, py)].toInt() and 0xFF

    /** Blue-difference chroma at an upright pixel; 128 means no colour. */
    fun chromaU(px: Int, py: Int): Int {
        val plane = u ?: return 128
        return plane[(sensorY(px, py) / 2) * uvRowStride + (sensorX(px, py) / 2) * uvPixelStride].toInt() and 0xFF
    }

    /** Red-difference chroma at an upright pixel; 128 means no colour. */
    fun chromaV(px: Int, py: Int): Int {
        val plane = v ?: return 128
        return plane[(sensorY(px, py) / 2) * uvRowStride + (sensorX(px, py) / 2) * uvPixelStride].toInt() and 0xFF
    }
}
