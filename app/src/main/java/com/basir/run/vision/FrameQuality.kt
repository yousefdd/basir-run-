package com.basir.run.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Image conditions that make path detection unreliable. Thresholds are initial values and
 * have not yet been tuned on recorded runs.
 */
data class FrameQuality(
    val meanLuma: Float,
    val contrast: Float,
    val sharpness: Float,
    val darkFraction: Float,
    val glareFraction: Float,
) {
    val tooDark: Boolean get() = meanLuma < 40f || darkFraction > 0.6f
    val glare: Boolean get() = glareFraction > 0.25f
    val blurry: Boolean get() = sharpness < 3f
    /** A finger, the harness or clothing over the lens: almost no detail at all. */
    val blocked: Boolean get() = contrast < 8f
    val usable: Boolean get() = !tooDark && !glare && !blurry && !blocked

    companion object {
        fun of(frame: CameraFrame): FrameQuality {
            val w = frame.uprightWidth
            val h = frame.uprightHeight
            val step = max(2, w / 64)
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            var dark = 0
            var bright = 0
            var lap = 0.0
            var lapN = 0
            var py = step
            while (py < h - step) {
                var px = step
                while (px < w - step) {
                    val c = frame.luma(px, py)
                    sum += c; sumSq += c * c; n++
                    if (c < 30) dark++
                    if (c > 245) bright++
                    // Focus measure on the ground half only: the sky is smooth even when sharp.
                    if (py > h / 2) {
                        lap += abs(4 * c - frame.luma(px - 1, py) - frame.luma(px + 1, py) - frame.luma(px, py - 1) - frame.luma(px, py + 1))
                        lapN++
                    }
                    px += step
                }
                py += step
            }
            val mean = sum / n
            val variance = max(0.0, sumSq / n - mean * mean)
            return FrameQuality(
                meanLuma = mean.toFloat(),
                contrast = sqrt(variance).toFloat(),
                sharpness = if (lapN == 0) 0f else (lap / lapN).toFloat(),
                darkFraction = dark.toFloat() / n,
                glareFraction = bright.toFloat() / n,
            )
        }
    }
}
