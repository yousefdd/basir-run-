package run.basir.engine

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A path on the ground as lateral = c0 + c1·f + c2·f² (+ c3·f³), f = meters ahead.
 * Valid between [nearM] and [farM]; outside that it is extrapolated and should not be trusted far.
 */
data class PathCurve(
    val coeffs: DoubleArray,
    val nearM: Double,
    val farM: Double,
    val rmsM: Double = 0.0,
) {
    fun lateralAt(f: Double): Double {
        var x = 0.0
        var p = 1.0
        for (c in coeffs) {
            x += c * p
            p *= f
        }
        return x
    }

    private fun d1(f: Double): Double {
        var x = 0.0
        for (i in 1 until coeffs.size) x += i * coeffs[i] * f.pow(i - 1)
        return x
    }

    private fun d2(f: Double): Double {
        var x = 0.0
        for (i in 2 until coeffs.size) x += i * (i - 1) * coeffs[i] * f.pow(i - 2)
        return x
    }

    /** Direction of the path at [f], degrees (positive = heading right). */
    fun headingDegAt(f: Double): Double = atan(d1(f)).toDegrees()

    /** Signed curvature (1/m) at [f]: positive bends right, negative bends left. */
    fun curvatureAt(f: Double): Double {
        val s = d1(f)
        return d2(f) / (1 + s * s).pow(1.5)
    }

    fun sample(stepM: Double = 0.25, from: Double = nearM, to: Double = farM): List<GroundPoint> {
        val out = ArrayList<GroundPoint>()
        var f = from
        while (f <= to + 1e-9) {
            out += GroundPoint(f, lateralAt(f))
            f += stepM
        }
        return out
    }

    override fun equals(other: Any?) = other is PathCurve && coeffs.contentEquals(other.coeffs) &&
        nearM == other.nearM && farM == other.farM

    override fun hashCode() = coeffs.contentHashCode()

    companion object {
        /**
         * Weighted least-squares polynomial fit of lateral(forward). The degree drops for short
         * spans so a few close points cannot produce a wild curve.
         */
        fun fit(points: List<GroundPoint>, weights: List<Double>? = null, maxDegree: Int = 2): PathCurve? {
            if (points.size < 3) return null
            val near = points.minOf { it.forward }
            val far = points.maxOf { it.forward }
            val span = far - near
            val degree = when {
                span < 1.5 || points.size < 5 -> 1
                span < 4.0 || points.size < 8 -> min(2, maxDegree)
                else -> maxDegree
            }
            val n = degree + 1
            val ata = Array(n) { DoubleArray(n) }
            val atb = DoubleArray(n)
            points.forEachIndexed { k, p ->
                val w = weights?.get(k) ?: 1.0
                val row = DoubleArray(n) { p.forward.pow(it) }
                for (i in 0 until n) {
                    atb[i] += w * row[i] * p.lateral
                    for (j in 0 until n) ata[i][j] += w * row[i] * row[j]
                }
            }
            // Tiny ridge term keeps the system well conditioned
            for (i in 0 until n) ata[i][i] += 1e-6
            val c = solve(ata, atb) ?: return null
            val curve = PathCurve(c, near, far)
            var sse = 0.0
            var sw = 0.0
            points.forEachIndexed { k, p ->
                val w = weights?.get(k) ?: 1.0
                val r = p.lateral - curve.lateralAt(p.forward)
                sse += w * r * r
                sw += w
            }
            return curve.copy(rmsM = sqrt(sse / max(sw, 1e-9)))
        }

        /** Gaussian elimination with partial pivoting. */
        internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
            for (col in 0 until n) {
                val piv = (col until n).maxBy { abs(m[it][col]) }
                if (abs(m[piv][col]) < 1e-12) return null
                val tmp = m[piv]; m[piv] = m[col]; m[col] = tmp
                for (r in 0 until n) {
                    if (r == col) continue
                    val k = m[r][col] / m[col][col]
                    for (c in col..n) m[r][c] -= k * m[col][c]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
