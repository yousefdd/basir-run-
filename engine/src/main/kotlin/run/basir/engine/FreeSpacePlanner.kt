package run.basir.engine

import java.util.PriorityQueue
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class FreeSpaceConfig(
    val cellM: Double = 0.1,
    val halfWidthM: Double = 3.0,
    val lookaheadM: Double = 6.0,
    val bodyRadiusM: Double = 0.4,         // half the space a runner needs, arms swinging
    val comfortClearanceM: Double = 0.9,
    val clearanceWeight: Double = 2.0,
    val progressGain: Double = 1.6,
    val minPointsPerCell: Int = 2,
    val pursuitM: Double = 2.0,
    val minProgressM: Double = 0.3,
    val waypointStepM: Double = 0.25,
)

/** Top-down grid of the ground ahead. Row i = i·cell meters ahead; column halfCols = straight ahead. */
class GroundGrid(val rows: Int, val cols: Int, val cellM: Double) {
    val free = BooleanArray(rows * cols)
    val occupied = BooleanArray(rows * cols)
    val halfCols = (cols - 1) / 2

    fun index(forward: Double, lateral: Double): Int? {
        val i = (forward / cellM).roundToInt()
        val j = (lateral / cellM).roundToInt() + halfCols
        return if (i in 0 until rows && j in 0 until cols) i * cols + j else null
    }

    companion object {
        /**
         * Builds the grid from a walkable-surface mask (segmentation model or depth floor plane)
         * and obstacle points on the ground. Cells under the camera's blind strip (between the
         * feet and the first visible ground row) are assumed free; obstacles seen there still block.
         */
        fun build(
            walkable: ProbabilityMask,
            camera: CameraModel,
            obstacles: List<GroundPoint>,
            config: FreeSpaceConfig,
        ): GroundGrid {
            val cam = camera.resized(walkable.width, walkable.height)
            val rows = ceil(config.lookaheadM / config.cellM).toInt() + 1
            val half = (config.halfWidthM / config.cellM).roundToInt()
            val grid = GroundGrid(rows, 2 * half + 1, config.cellM)
            val top = max(0, (cam.horizonRow + 1).toInt())
            for (y in top until walkable.height) for (x in 0 until walkable.width) {
                if (walkable[x, y] < 0.5f) continue
                // Each pixel covers a patch of ground from its near to its far edge — sample it
                val near = cam.imageToGround(x + 0.5, y + 1.0) ?: continue
                val far = cam.imageToGround(x + 0.5, y.toDouble()) ?: GroundPoint(config.lookaheadM, near.lateral)
                for (s in 0..4) {
                    val t = s / 4.0
                    val f = near.forward + t * (min(far.forward, config.lookaheadM + 0.5) - near.forward)
                    val l = near.lateral * (f / max(near.forward, 1e-6))
                    grid.index(f, l)?.let { grid.free[it] = true }
                }
            }
            val firstVisible = cam.imageToGround(cam.width / 2.0, cam.height.toDouble())?.forward ?: 0.0
            val tanHalf = tan(cam.hfovDeg.toRadians() / 2)
            for (i in 0 until rows) {
                val f = i * config.cellM
                if (f >= firstVisible) break
                val reach = max(0.4, f * tanHalf)
                val j0 = max(0, half - (reach / config.cellM).toInt())
                val j1 = min(grid.cols - 1, half + (reach / config.cellM).toInt())
                for (j in j0..j1) grid.free[i * grid.cols + j] = true
            }
            val count = IntArray(rows * grid.cols)
            for (p in obstacles) grid.index(p.forward, p.lateral)?.let { count[it]++ }
            for (k in count.indices) if (count[k] >= config.minPointsPerCell) {
                grid.occupied[k] = true
                grid.free[k] = false
            }
            return grid
        }
    }
}

data class FreeRoute(
    val waypoints: List<GroundPoint>,
    val lengthM: Double,
    val progressM: Double,
    val steerDeg: Double,
    val minClearanceM: Double,
) {
    /** The route as a smooth curve for the guidance (same representation as a followed line). */
    fun toCurve(): PathCurve? = PathCurve.fit(waypoints)
}

/**
 * Virtual path when there is no line on the ground: the best route through the walkable
 * space — Dijkstra over [GroundGrid] for a runner of radius [FreeSpaceConfig.bodyRadiusM],
 * with a cost that grows near obstacles (keeps to the middle of the path) and a reward for
 * progress toward the goal direction. Mirrors scripts/route_planner.py of blind-navigation.
 */
class FreeSpacePlanner(private val config: FreeSpaceConfig = FreeSpaceConfig()) {

    fun plan(grid: GroundGrid, goalBearingDeg: Double = 0.0): FreeRoute? {
        val nf = grid.rows
        val nl = grid.cols
        val cell = grid.cellM
        val half = grid.halfCols
        val clearance = distanceTransform(grid.occupied, nf, nl, cell)
        fun passable(k: Int) = grid.free[k] && clearance[k] >= config.bodyRadiusM
        val start = half
        if (!passable(start)) return null

        val cost = DoubleArray(nf * nl) { Double.POSITIVE_INFINITY }
        val parent = IntArray(nf * nl) { -1 }
        cost[start] = 0.0
        val heap = PriorityQueue<Pair<Double, Int>>(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second })
        heap += 0.0 to start
        val comfort = config.comfortClearanceM
        while (heap.isNotEmpty()) {
            val (c, idx) = heap.poll()
            if (c > cost[idx]) continue
            val i = idx / nl
            val j = idx % nl
            for (di in -1..1) for (dj in -1..1) {
                if (di == 0 && dj == 0) continue
                val ni = i + di
                val nj = j + dj
                if (ni !in 0 until nf || nj !in 0 until nl) continue
                val n = ni * nl + nj
                if (!passable(n)) continue
                val penalty = 1 + config.clearanceWeight * max(0.0, (comfort - clearance[n]) / comfort)
                val nc = c + cell * (if (di != 0 && dj != 0) 1.4142135623730951 else 1.0) * penalty
                if (nc < cost[n]) {
                    cost[n] = nc
                    parent[n] = idx
                    heap += nc to n
                }
            }
        }

        val g = goalBearingDeg.toRadians()
        fun progress(k: Int) = (k / nl) * cell * cos(g) + (k % nl - half) * cell * sin(g)
        var best = -1
        var bestScore = Double.POSITIVE_INFINITY
        for (k in cost.indices) {
            if (cost[k].isInfinite()) continue
            val s = cost[k] - config.progressGain * progress(k)
            if (s < bestScore) { bestScore = s; best = k }
        }
        if (best < 0 || progress(best) < config.minProgressM) return null

        val cells = ArrayList<Int>()
        var idx = best
        while (idx >= 0) { cells += idx; idx = parent[idx] }
        cells.reverse()
        var pts = cells.map { GroundPoint((it / nl) * cell, (it % nl - half) * cell) }
        if (pts.size > 4) {
            pts = pts.indices.map { k ->
                if (k == 0 || k == pts.size - 1) pts[k] else {
                    val lo = max(0, k - 2); val hi = min(pts.size, k + 3)
                    val sub = pts.subList(lo, hi)
                    GroundPoint(sub.sumOf { it.forward } / sub.size, sub.sumOf { it.lateral } / sub.size)
                }
            }
        }
        val arc = DoubleArray(pts.size)
        for (k in 1 until pts.size) arc[k] = arc[k - 1] + hypot(pts[k].forward - pts[k - 1].forward, pts[k].lateral - pts[k - 1].lateral)
        val length = arc.last()
        var k = arc.indexOfFirst { it >= min(config.pursuitM, length) }
        if (k < 0) k = arc.size
        k = k.coerceIn(1, max(1, pts.size - 1))
        val steer = if (pts.size > 1) atan2(pts[k].lateral, pts[k].forward).toDegrees() else 0.0
        val nWp = max(2, (length / config.waypointStepM).toInt() + 1)
        val waypoints = (0 until nWp).map { q ->
            val s = length * q / (nWp - 1)
            GroundPoint(interp(s, arc, pts.map { it.forward }), interp(s, arc, pts.map { it.lateral }))
        }
        val minClear = cells.minOf { min(clearance[it], config.halfWidthM) }
        return FreeRoute(waypoints, length, progress(best), steer, minClear)
    }

    private fun interp(x: Double, xs: DoubleArray, ys: List<Double>): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs.last()) return ys.last()
        var k = 1
        while (xs[k] < x) k++
        val t = (x - xs[k - 1]) / max(xs[k] - xs[k - 1], 1e-12)
        return ys[k - 1] + t * (ys[k] - ys[k - 1])
    }

    companion object {
        /** Distance (m) from each cell to the nearest occupied one (two-pass vector sweep). */
        fun distanceTransform(occ: BooleanArray, nf: Int, nl: Int, cell: Double): DoubleArray {
            val big = 1e9
            val dx = DoubleArray(nf * nl) { if (occ[it]) 0.0 else big }
            val dy = DoubleArray(nf * nl) { if (occ[it]) 0.0 else big }
            fun relax(i: Int, j: Int, ni: Int, nj: Int) {
                if (ni !in 0 until nf || nj !in 0 until nl) return
                val n = ni * nl + nj
                val c = i * nl + j
                if (dx[n] >= big) return
                val cx = dx[n] + kotlin.math.abs(j - nj)
                val cy = dy[n] + kotlin.math.abs(i - ni)
                if (cx * cx + cy * cy < dx[c] * dx[c] + dy[c] * dy[c]) { dx[c] = cx; dy[c] = cy }
            }
            for (i in 0 until nf) {
                for (j in 0 until nl) { relax(i, j, i - 1, j - 1); relax(i, j, i - 1, j); relax(i, j, i - 1, j + 1); relax(i, j, i, j - 1) }
                for (j in nl - 1 downTo 0) relax(i, j, i, j + 1)
            }
            for (i in nf - 1 downTo 0) {
                for (j in nl - 1 downTo 0) { relax(i, j, i + 1, j + 1); relax(i, j, i + 1, j); relax(i, j, i + 1, j - 1); relax(i, j, i, j + 1) }
                for (j in 0 until nl) relax(i, j, i, j - 1)
            }
            return DoubleArray(nf * nl) { sqrt(dx[it] * dx[it] + dy[it] * dy[it]) * cell }
        }
    }
}
