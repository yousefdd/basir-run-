package run.basir.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The middle of the walkable strip the runner is on, row by row on the ground grid.
 *
 * The free-space route is a shortest path: through a bend it cuts the corner as a nearly straight
 * chord, so the bend shows up as a heading (read as "correct") and its curvature is lost (measured
 * on real curved walks from SANPO: median curvature 0.000 1/m on a 92° turn). The centreline
 * follows the strip itself, so the path's own bend becomes curvature and the runner's position on
 * the strip stays offset.
 *
 * Per row (every [stepM] from [fromM]): the free interval that holds the route there (or, past the
 * route's end, the one nearest the previous centre) is taken; if both its edges are inside the grid
 * the centre is their middle, otherwise the strip is wider than the grid on that side and the route
 * point is kept. Rows where the strip is narrower than the body stop the line (the way ends). A
 * centreline is returned only if enough rows had both edges (the strip is really bounded),
 * otherwise null and the caller keeps the route.
 */
/** The centreline and how far the strip reaches on either side of it (median over bounded rows). */
data class Centerline(val curve: PathCurve, val halfWidthM: Double)

object CorridorCenterline {
    fun extract(
        grid: GroundGrid,
        route: FreeRoute,
        bodyRadiusM: Double = 0.4,
        fromM: Double = 0.8,
        stepM: Double = 0.2,
        minBoundedShare: Double = 0.5,
        minSpanM: Double = 1.5,
        maxHalfWidthM: Double = Double.MAX_VALUE,
        /** tan(hfov/2): an interval that reaches the edge of the camera's view has no seen edge on that side. */
        fovTanHalf: Double? = null,
    ): Centerline? {
        val wp = route.waypoints
        if (wp.size < 2) return null
        fun routeLateral(f: Double): Double? {
            if (f > wp.last().forward + 1e-9) return null
            for (k in 1 until wp.size) {
                val a = wp[k - 1]; val b = wp[k]
                if (f <= b.forward || k == wp.size - 1) {
                    val t = if (b.forward == a.forward) 0.0 else ((f - a.forward) / (b.forward - a.forward)).coerceIn(0.0, 1.0)
                    return a.lateral + t * (b.lateral - a.lateral)
                }
            }
            return null
        }
        val pts = ArrayList<GroundPoint>()
        val weights = ArrayList<Double>()
        val halfWidths = ArrayList<Double>()
        var bounded = 0
        var prev: Double? = routeLateral(fromM) ?: 0.0
        var f = fromM
        val maxF = (grid.rows - 1) * grid.cellM
        while (f <= maxF) {
            val i = (f / grid.cellM).toInt()
            val want = routeLateral(f) ?: prev ?: break
            val j0 = (want / grid.cellM).let { Math.round(it).toInt() } + grid.halfCols
            if (j0 !in 0 until grid.cols) break
            fun freeAt(j: Int) = grid.free[i * grid.cols + j] && !grid.occupied[i * grid.cols + j]
            // the free interval at (or right next to) the wanted point
            var seed = -1
            for (d in 0..3) {
                if (j0 - d >= 0 && freeAt(j0 - d)) { seed = j0 - d; break }
                if (j0 + d < grid.cols && freeAt(j0 + d)) { seed = j0 + d; break }
            }
            if (seed < 0) break
            var lo = seed; var hi = seed
            while (lo - 1 >= 0 && freeAt(lo - 1)) lo--
            while (hi + 1 < grid.cols && freeAt(hi + 1)) hi++
            val width = (hi - lo + 1) * grid.cellM
            if (width < 2 * bodyRadiusM) break
            // An edge at the grid border or at the border of what the camera sees is not a path edge.
            val viewHalf = fovTanHalf?.let { f * it } ?: Double.MAX_VALUE
            val loM = (lo - grid.halfCols) * grid.cellM; val hiM = (hi - grid.halfCols) * grid.cellM
            val leftOpen = lo == 0 || loM <= -viewHalf + 1.5 * grid.cellM
            val rightOpen = hi == grid.cols - 1 || hiM >= viewHalf - 1.5 * grid.cellM
            val centre = if (!leftOpen && !rightOpen) {
                bounded++
                halfWidths += width / 2
                ((lo + hi) / 2.0 - grid.halfCols) * grid.cellM
            } else want
            pts += GroundPoint(f, centre)
            weights += if (!leftOpen && !rightOpen) 1.0 else 0.3
            prev = centre
            f += stepM
        }
        if (pts.size < 6) return null
        if (pts.last().forward - pts.first().forward < minSpanM) return null
        if (bounded < minBoundedShare * pts.size) return null
        // A centre that jumps sideways by more than the strip could bend is a different strip: cut there.
        var end = pts.size
        for (k in 1 until pts.size) if (abs(pts[k].lateral - pts[k - 1].lateral) > max(0.3, 2.5 * stepM)) { end = k; break }
        if (end < 6) return null
        val half = halfWidths.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        if (half > maxHalfWidthM) return null // an open area, not a strip: no centre to follow
        return PathCurve.fit(pts.subList(0, end), weights.subList(0, end))?.let { c ->
            // keep the curve within the rows it was measured on
            Centerline(c.copy(nearM = pts.first().forward, farM = min(pts[end - 1].forward, c.farM)), half)
        }
    }
}
