package com.basir.run.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class Pt(val x: Float, val y: Float)

class Fit(val curve: Curve, val rms: Float, val used: Int)

/** Quadratic least squares with one round of outlier removal, so stray points do not bend the line. */
object CurveFit {

    fun fit(points: List<Pt>, minPoints: Int = 6, minYSpan: Float = 0.2f, outlierFloor: Float = 0.03f): Fit? {
        if (points.size < minPoints) return null
        if (points.maxOf { it.y } - points.minOf { it.y } < minYSpan) return null
        val first = leastSquares(points) ?: return null
        val residuals = points.map { abs(it.x - first.x(it.y)) }
        val limit = max(outlierFloor, rms(residuals) * 2.5f)
        val kept = points.filterIndexed { i, _ -> residuals[i] <= limit }
        if (kept.size < minPoints) return null
        val curve = if (kept.size == points.size) first else leastSquares(kept) ?: return null
        return Fit(curve, rms(kept.map { abs(it.x - curve.x(it.y)) }), kept.size)
    }

    private fun rms(values: List<Float>) = sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()

    private fun leastSquares(points: List<Pt>): Curve? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in points) {
            val y = p.y.toDouble()
            val x = p.x.toDouble()
            val y2 = y * y
            s0 += 1; s1 += y; s2 += y2; s3 += y2 * y; s4 += y2 * y2
            t0 += x; t1 += x * y; t2 += x * y2
        }
        val d = det(s4, s3, s2, s3, s2, s1, s2, s1, s0)
        if (abs(d) < 1e-12) {
            val dl = s0 * s2 - s1 * s1
            if (abs(dl) < 1e-12) return null
            val b = (s0 * t1 - s1 * t0) / dl
            return Curve(0f, b.toFloat(), ((t0 - b * s1) / s0).toFloat())
        }
        val a = det(t2, s3, s2, t1, s2, s1, t0, s1, s0) / d
        val b = det(s4, t2, s2, s3, t1, s1, s2, t0, s0) / d
        val c = det(s4, s3, t2, s3, s2, t1, s2, s1, t0) / d
        return Curve(a.toFloat(), b.toFloat(), c.toFloat())
    }

    private fun det(
        a11: Double, a12: Double, a13: Double,
        a21: Double, a22: Double, a23: Double,
        a31: Double, a32: Double, a33: Double,
    ) = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31) + a13 * (a21 * a32 - a22 * a31)
}
