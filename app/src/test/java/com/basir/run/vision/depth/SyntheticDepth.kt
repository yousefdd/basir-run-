package com.basir.run.vision.depth

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Ray-casts small synthetic scenes (flat floor, boxes, holes) into a disparity map, so the
 * planner can be tested against known geometry without any model. Port of the blind-navigation
 * tool's tests/synthetic.py. World: X right, Y up, Z forward (metres); camera at (0, height, 0)
 * tilted down by pitchDeg.
 */
data class Scene(
    /** (x0, x1, y0, y1, z0, z1) solid boxes. */
    val boxes: List<DoubleArray> = emptyList(),
    /** (x0, x1, z0, z1) floor regions that drop by [holeDepth]. */
    val holes: List<DoubleArray> = emptyList(),
    val holeDepth: Double = 0.5,
    val scale: Double = 1.0,
    val shift: Double = 0.0,
    val pitchDeg: Double = 20.0,
    val camHeight: Double = 1.3,
    val hfovDeg: Double = 70.0,
)

object SyntheticDepth {
    const val WIDTH = 160
    const val HEIGHT = 120
    private const val MAX_DIST = 60.0

    fun render(s: Scene, width: Int = WIDTH, height: Int = HEIGHT): FloatArray {
        val f = (width / 2.0) / tan(Math.toRadians(s.hfovDeg) / 2)
        val cx = (width - 1) / 2.0
        val cy = (height - 1) / 2.0
        val th = Math.toRadians(s.pitchDeg)
        val out = FloatArray(width * height)
        for (v in 0 until height) for (u in 0 until width) {
            val x = (u - cx) / f
            val y = (v - cy) / f
            val dx = x
            val dy = -y * cos(th) - sin(th)
            val dz = -y * sin(th) + cos(th)
            fun floorHit(level: Double): Double {
                val tt = (level - s.camHeight) / dy
                return if (dy < 0 && tt > 0) tt else Double.POSITIVE_INFINITY
            }
            var t = MAX_DIST
            val tFloor = floorHit(0.0)
            val px = dx * tFloor
            val pz = dz * tFloor
            val inHole = s.holes.any { (x0, x1, z0, z1) -> px >= x0 && px <= x1 && pz >= z0 && pz <= z1 }
            t = min(t, if (inHole) floorHit(-s.holeDepth) else tFloor)
            for (b in s.boxes) {
                var tmin = Double.NEGATIVE_INFINITY
                var tmax = Double.POSITIVE_INFINITY
                for ((d, o, lo, hi) in listOf(
                    Quad(dx, 0.0, b[0], b[1]), Quad(dy, s.camHeight, b[2], b[3]), Quad(dz, 0.0, b[4], b[5]),
                )) {
                    val near: Double
                    val far: Double
                    if (abs(d) < 1e-12) {
                        val inside = o >= lo && o <= hi
                        near = if (inside) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                        far = if (inside) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
                    } else {
                        val t1 = (lo - o) / d
                        val t2 = (hi - o) / d
                        near = min(t1, t2)
                        far = max(t1, t2)
                    }
                    tmin = max(tmin, near)
                    tmax = min(tmax, far)
                }
                if (tmax >= tmin && tmin > 0) t = min(t, tmin)
            }
            out[v * width + u] = (s.scale / t + s.shift).toFloat()
        }
        return out
    }

    private data class Quad(val a: Double, val b: Double, val c: Double, val d: Double)
}
