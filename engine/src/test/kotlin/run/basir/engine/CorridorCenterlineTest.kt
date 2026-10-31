package run.basir.engine

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The strip's centreline keeps a bend as curvature where the shortest route cuts it. */
class CorridorCenterlineTest {
    private val cfg = FreeSpaceConfig()

    /** A walkable strip [width] wide whose middle bends with radius [radius] (+ right), drawn on the grid. */
    private fun stripGrid(radius: Double?, width: Double = 1.6, offset: Double = 0.0): GroundGrid {
        val rows = (cfg.lookaheadM / cfg.cellM).toInt() + 1
        val half = (cfg.halfWidthM / cfg.cellM).toInt()
        val g = GroundGrid(rows, 2 * half + 1, cfg.cellM)
        for (i in 0 until rows) {
            val f = i * cfg.cellM
            val mid = offset + (radius?.let { r -> (if (r > 0) 1 else -1) * (abs(r) - sqrt(maxOf(0.0, r * r - f * f))) } ?: 0.0)
            for (j in 0 until g.cols) {
                val l = (j - half) * cfg.cellM
                if (abs(l - mid) <= width / 2) g.free[i * g.cols + j] = true
            }
        }
        return g
    }

    @Test
    fun `the centreline of a bending strip carries the bend as curvature, the route does not`() {
        val g = stripGrid(radius = 8.0)
        val route = FreeSpacePlanner(cfg).plan(g, 0.0)
        assertNotNull(route)
        val cl = CorridorCenterline.extract(g, route)
        assertNotNull(cl)
        val k = cl.curve.curvatureAt(2.0)
        assertTrue(k > 0.07, "centreline curvature $k for a radius-8 m bend")
        val kRoute = route.toCurve()!!.curvatureAt(2.0)
        assertTrue(k > kRoute + 0.03, "route curvature $kRoute should be flatter (it cuts the corner)")
        assertEquals(0.8, cl.halfWidthM, 0.15)
    }

    @Test
    fun `left bend gives negative curvature`() {
        val g = stripGrid(radius = -8.0)
        val cl = CorridorCenterline.extract(g, FreeSpacePlanner(cfg).plan(g, 0.0)!!)
        assertNotNull(cl)
        assertTrue(cl.curve.curvatureAt(2.0) < -0.07)
    }

    @Test
    fun `an open area wider than the grid gives no centreline`() {
        val rows = (cfg.lookaheadM / cfg.cellM).toInt() + 1
        val half = (cfg.halfWidthM / cfg.cellM).toInt()
        val g = GroundGrid(rows, 2 * half + 1, cfg.cellM).also { it.free.fill(true) }
        assertNull(CorridorCenterline.extract(g, FreeSpacePlanner(cfg).plan(g, 0.0)!!))
    }

    @Test
    fun `on a wide strip a runner off its middle but clear of the edges is not corrected`() {
        val c = StableGuidanceController()
        val p = PathCurve(doubleArrayOf(-0.7, 0.0, 0.0), 0.8, 6.0) // middle 0.7 m to the left
        val wide = PathDecision(PathMode.FREE_PATH, p, 0.9, stripHalfWidthM = 1.6)
        val evs = (1..30).map { c.update(wide, 1.4, 1.0 / 15) }
        assertTrue(evs.all { it.state == GuidanceState.TRACK_CENTERED }, "0.7 m off the middle of a 3.2 m strip is fine")
        val narrow = StableGuidanceController()
        val n = (1..30).map { narrow.update(PathDecision(PathMode.FREE_PATH, p, 0.9, stripHalfWidthM = 0.8), 1.4, 1.0 / 15) }
        assertEquals(GuidanceState.CORRECT_LEFT, n.last().state, "on a 1.6 m strip it is a correction")
    }
}
