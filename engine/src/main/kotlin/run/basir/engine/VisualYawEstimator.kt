package run.basir.engine

import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Light optical flow for the camera's turn (research round 6b): how far the far scene shifted sideways between two
 * frames gives the yaw, with no gyroscope and almost no cost. The upper band of the analysis image (above and just
 * below the horizon: trees, buildings, the far end of the path) is collapsed into one brightness profile per column;
 * the profile is high-passed (illumination changes removed), and the horizontal shift that best aligns it with the
 * previous frame's profile (normalised cross-correlation, sub-pixel by a parabola) is the image shift. A scene moving
 * left means the runner turned right: yaw = -atan(shift / focal). Far points move with the turn and hardly with the
 * runner's advance, which is why only the upper band is used. Returns null when the match is weak (flat sky, blur).
 */
class VisualYawEstimator(private val maxShiftPx: Int = 20, private val minCorrelation: Double = 0.5) {
    private var prev: DoubleArray? = null

    fun reset() { prev = null }

    /** Turn since the previous frame, rad (+ right), or null. [camera] must match the image size. */
    fun update(img: RgbImage, camera: CameraModel): Double? {
        val prof = profile(img, camera)
        val p = prev
        prev = prof
        if (p == null || p.size != prof.size) return null
        val n = prof.size
        var bestS = 0; var bestC = -2.0
        val cs = DoubleArray(2 * maxShiftPx + 1)
        for (s in -maxShiftPx..maxShiftPx) {
            var sab = 0.0; var saa = 0.0; var sbb = 0.0
            val from = max(0, -s); val to = min(n, n - s)
            for (x in from until to) { val a = p[x]; val b = prof[x + s]; sab += a * b; saa += a * a; sbb += b * b }
            val c = if (saa > 1e-9 && sbb > 1e-9) sab / kotlin.math.sqrt(saa * sbb) else -1.0
            cs[s + maxShiftPx] = c
            if (c > bestC) { bestC = c; bestS = s }
        }
        if (bestC < minCorrelation || kotlin.math.abs(bestS) == maxShiftPx) return null
        val i = bestS + maxShiftPx
        val l = cs[i - 1]; val r = cs[i + 1]
        val den = l - 2 * cs[i] + r
        val sub = if (kotlin.math.abs(den) > 1e-9) 0.5 * (l - r) / den else 0.0
        val shift = bestS + sub.coerceIn(-0.5, 0.5)
        return -atan(shift / camera.focalPx)
    }

    private fun profile(img: RgbImage, camera: CameraModel): DoubleArray {
        val w = img.width; val h = img.height
        val horizon = camera.resized(w, h).horizonRow
        val y1 = (horizon + 0.08 * h).toInt().coerceIn(max(4, h / 6), h / 2)
        val col = DoubleArray(w)
        for (y in 0 until y1) {
            val row = y * w
            for (x in 0 until w) col[x] += 0.299 * img.r[row + x] + 0.587 * img.g[row + x] + 0.114 * img.b[row + x]
        }
        for (x in 0 until w) col[x] /= y1
        // high-pass: minus a 15-column moving average
        val out = DoubleArray(w)
        for (x in 0 until w) {
            val a = max(0, x - 7); val b = min(w - 1, x + 7)
            var s = 0.0
            for (k in a..b) s += col[k]
            out[x] = col[x] - s / (b - a + 1)
        }
        return out
    }
}
