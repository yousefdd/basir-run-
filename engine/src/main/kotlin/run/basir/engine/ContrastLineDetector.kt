package run.basir.engine

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An RGB frame (row-major, 0–255 per channel). */
class RgbImage(val width: Int, val height: Int, val r: IntArray, val g: IntArray, val b: IntArray)

/**
 * Color-agnostic line detector without a neural network — the baseline and the fallback when
 * no trained line model is available. A guide line or lane line is a thin stripe that differs
 * from the ground on BOTH sides, whatever its color. For each row the expected line width in
 * pixels follows from the camera geometry; a pixel scores high when its CIELAB color differs
 * from the ground a line-width or two to its left and right, while those two sides match each
 * other (so the edge between a path and grass, a step edge, does not count).
 */
class ContrastLineDetector(
    private val expectedLineWidthM: Double = 0.10,
    private val minDeltaE: Double = 12.0, // CIELAB difference that starts to count as a line
) {
    fun detect(image: RgbImage, camera: CameraModel): ProbabilityMask {
        val cam = camera.resized(image.width, image.height)
        val w = image.width
        val h = image.height
        val lab = toLab(image)
        // Colorless frames (night, monochrome sensors): lightness is the only cue, use it fully
        var chroma = 0.0
        for (i in lab[1].indices step 7) chroma += kotlin.math.hypot(lab[1][i].toDouble(), lab[2][i].toDouble())
        lightnessWeight = if (chroma / (lab[1].size / 7 + 1) < 6.0) 1.0 else 0.25
        val out = ProbabilityMask(w, h)
        val top = max(0, (cam.horizonRow + 2).toInt())
        for (y in top until h) {
            val mpp = cam.lateralMetersPerPixel(y.toDouble()) ?: continue
            val lineW = expectedLineWidthM / mpp
            if (lineW < 1.5) continue // too far: the line is thinner than a pixel
            val off = max(2, (1.5 * lineW).roundToInt())
            val row = y * w
            for (x in off until w - off) {
                val c = row + x
                val l = row + x - off
                val r = row + x + off
                val dl = deltaE(lab, c, l)
                val dr = deltaE(lab, c, r)
                val sides = deltaE(lab, l, r)
                // Stripe: center differs from both sides, sides agree with each other
                val response = min(dl, dr) - 0.5 * sides
                out.data[c] = (1.0 / (1.0 + exp(-(response - minDeltaE) / 3.0))).toFloat()
            }
        }
        return keepThinRuns(out, cam)
    }

    /** Remove runs much wider than a line (a large colored area is a surface, not a line). */
    private fun keepThinRuns(mask: ProbabilityMask, cam: CameraModel): ProbabilityMask {
        for (y in 0 until mask.height) {
            val mpp = cam.lateralMetersPerPixel(y.toDouble()) ?: continue
            val maxRun = max(3, (3.0 * expectedLineWidthM / mpp).roundToInt())
            var x = 0
            while (x < mask.width) {
                if (mask[x, y] < 0.5f) { x++; continue }
                var e = x
                while (e < mask.width && mask[e, y] >= 0.5f) e++
                if (e - x > maxRun) for (i in x until e) mask[i, y] = 0f
                x = e
            }
        }
        return mask
    }

    private var lightnessWeight = 0.25

    private fun deltaE(lab: Array<FloatArray>, i: Int, j: Int): Double {
        val dl = (lab[0][i] - lab[0][j]).toDouble()
        val da = (lab[1][i] - lab[1][j]).toDouble()
        val db = (lab[2][i] - lab[2][j]).toDouble()
        // Lightness counts half (in color images): shadows change L* far more than a line's color
        return sqrt(lightnessWeight * dl * dl + da * da + db * db)
    }

    companion object {
        /** sRGB → CIELAB (D65). */
        fun toLab(img: RgbImage): Array<FloatArray> {
            val n = img.width * img.height
            val L = FloatArray(n)
            val A = FloatArray(n)
            val B = FloatArray(n)
            fun lin(c: Int): Double {
                val v = c / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
            for (i in 0 until n) {
                val r = lin(img.r[i]); val g = lin(img.g[i]); val b = lin(img.b[i])
                val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
                val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
                val fx = f(x); val fy = f(y); val fz = f(z)
                L[i] = (116 * fy - 16).toFloat()
                A[i] = (500 * (fx - fy)).toFloat()
                B[i] = (200 * (fy - fz)).toFloat()
            }
            return arrayOf(L, A, B)
        }
    }
}
