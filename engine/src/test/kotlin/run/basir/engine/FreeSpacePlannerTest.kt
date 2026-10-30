package run.basir.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeSpacePlannerTest {
    private val config = FreeSpaceConfig(lookaheadM = 6.0)

    @Test
    fun `matches the Python reference planner waypoint by waypoint`() {
        val fixtures = JSONArray(File(TestImages.dir, "fixtures/routes.json").readText())
        for (n in 0 until fixtures.length()) {
            val fx = fixtures.getJSONObject(n)
            val grid = GroundGrid(fx.getInt("rows"), fx.getInt("cols"), 0.1)
            fx.getString("free").forEachIndexed { k, ch -> grid.free[k] = ch == '1' }
            fx.getString("occupied").forEachIndexed { k, ch -> grid.occupied[k] = ch == '1' }
            val route = FreeSpacePlanner(config).plan(grid, fx.getDouble("goal"))
            val ref = fx.optJSONObject("route")
            val name = fx.getString("name")
            if (ref == null) { assertNull(route, name); continue }
            assertNotNull(route, name)
            assertEquals(ref.getDouble("steer_deg"), route.steerDeg, 0.2, name)
            assertEquals(ref.getDouble("progress_m"), route.progressM, 0.02, name)
            assertEquals(ref.getDouble("min_clearance_m"), route.minClearanceM, 0.02, name)
            val wps = ref.getJSONArray("waypoints")
            assertEquals(wps.length(), route.waypoints.size, name)
            for (i in 0 until wps.length()) {
                assertEquals(wps.getJSONArray(i).getDouble(0), route.waypoints[i].forward, 0.02, "$name wp $i")
                assertEquals(wps.getJSONArray(i).getDouble(1), route.waypoints[i].lateral, 0.02, "$name wp $i")
            }
            println("parity ok: $name steer=${"%.1f".format(route.steerDeg)}° progress=${"%.1f".format(route.progressM)} m")
        }
    }

    private fun openGrid(): GroundGrid {
        val g = GroundGrid(61, 61, 0.1)
        g.free.fill(true)
        return g
    }

    private fun wall(g: GroundGrid, f0: Double, f1: Double, l0: Double, l1: Double) {
        var f = f0
        while (f <= f1) {
            var l = l0
            while (l <= l1) { g.index(f, l)?.let { g.occupied[it] = true; g.free[it] = false }; l += 0.05 }
            f += 0.05
        }
    }

    @Test
    fun `stays in the middle of a corridor`() {
        val g = openGrid()
        wall(g, 0.0, 6.0, -3.0, -0.8)
        wall(g, 0.0, 6.0, 1.2, 3.0)   // corridor center at +0.2 m
        val r = FreeSpacePlanner(config).plan(g)
        assertNotNull(r)
        val far = r.waypoints.filter { it.forward > 3.0 }
        assertTrue(far.all { abs(it.lateral - 0.2) <= 0.15 }, far.toString())
    }

    @Test
    fun `no route when the way is blocked within the body radius`() {
        val g = openGrid()
        wall(g, 0.3, 0.5, -3.0, 3.0)
        assertNull(FreeSpacePlanner(config).plan(g))
    }

    @Test
    fun `never squeezes through a gap narrower than the runner`() {
        val g = openGrid()
        wall(g, 2.0, 2.3, -3.0, -0.3)
        wall(g, 2.0, 2.3, 0.3, 3.0)   // 0.6 m gap, runner needs 0.8 m
        val r = FreeSpacePlanner(config).plan(g)
        assertNotNull(r)
        assertTrue(r.waypoints.maxOf { it.forward } < 2.0, "route went through the gap")
    }
}
