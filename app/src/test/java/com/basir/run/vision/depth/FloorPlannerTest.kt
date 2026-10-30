package com.basir.run.vision.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Checks the Kotlin port against the Python reference of the blind-navigation tool.
 * The expected values were produced by its scripts/path_planner.py on the same synthetic
 * scenes (tests/synthetic.py, ported in [SyntheticDepth]); tolerances match its own JS test.
 */
class FloorPlannerTest {

    data class Golden(
        val name: String, val scene: Scene, val auto: Boolean, val floorValid: Boolean, val action: String, val target: Int,
        val raised: Boolean, val pitchSource: String, val pitch: Double?, val clear: Double?, val headLevel: Double?,
        val drop: Double?, val nearest: Double?, val left: Double?, val center: Double?, val right: Double?,
    )

    private val goldens = listOf(
        Golden("empty", Scene(), auto = false, floorValid = true, action = "forward", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 5.0, headLevel = null, drop = null, nearest = null, left = null, center = null, right = null),
        Golden("box_ahead", Scene(boxes = listOf(doubleArrayOf(-0.3, 0.3, 0.0, 1.0, 1.5, 1.9))), auto = false, floorValid = true, action = "slow", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 1.5, headLevel = null, drop = null, nearest = 1.5, left = null, center = 1.5, right = null),
        Golden("box_left", Scene(boxes = listOf(doubleArrayOf(-1.2, -0.45, 0.0, 1.0, 1.5, 1.9))), auto = false, floorValid = true, action = "forward", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 5.0, headLevel = null, drop = null, nearest = 1.5, left = 1.5, center = null, right = null),
        Golden("detour", Scene(boxes = listOf(doubleArrayOf(-0.45, 0.15, 0.0, 1.0, 1.8, 2.1))), auto = false, floorValid = true, action = "slight_right", target = 20, raised = false, pitchSource = "config", pitch = 20.0, clear = 1.8, headLevel = null, drop = null, nearest = 1.8, left = 1.8, center = 1.8, right = null),
        Golden("stop", Scene(boxes = listOf(doubleArrayOf(-0.35, 0.35, 0.0, 1.5, 1.0, 1.3))), auto = false, floorValid = true, action = "stop", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 1.0, headLevel = null, drop = null, nearest = 1.0, left = 1.0, center = 1.0, right = 1.0),
        Golden("wall", Scene(boxes = listOf(doubleArrayOf(-5.0, 5.0, 0.0, 3.0, 0.6, 0.8))), auto = false, floorValid = false, action = "caution", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = null, headLevel = null, drop = null, nearest = null, left = null, center = null, right = null),
        Golden("head_level", Scene(boxes = listOf(doubleArrayOf(-0.5, 0.5, 1.3, 1.9, 1.5, 1.7))), auto = false, floorValid = true, action = "slow", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 1.5, headLevel = 1.5, drop = null, nearest = 1.5, left = 1.5, center = 1.5, right = 1.5),
        Golden("drop", Scene(holes = listOf(doubleArrayOf(-1.0, 1.0, 1.6, 3.5))), auto = false, floorValid = true, action = "stop", target = 0, raised = true, pitchSource = "config", pitch = 20.0, clear = 0.86, headLevel = null, drop = null, nearest = 0.86, left = 0.86, center = 0.86, right = 0.86),
        Golden("affine", Scene(boxes = listOf(doubleArrayOf(-0.3, 0.3, 0.0, 1.0, 2.5, 2.9)), scale = 37.0, shift = 4.0), auto = false, floorValid = true, action = "forward", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 2.5, headLevel = null, drop = null, nearest = 2.5, left = null, center = 2.5, right = null),
        Golden("table_top", Scene(boxes = listOf(doubleArrayOf(-0.8, 0.8, 0.7, 0.75, 0.3, 1.6))), auto = false, floorValid = true, action = "stop", target = 0, raised = true, pitchSource = "config", pitch = 20.0, clear = 0.5, headLevel = null, drop = null, nearest = 0.5, left = 0.5, center = 0.5, right = 0.5),
        Golden("low_table", Scene(boxes = listOf(doubleArrayOf(-0.6, 0.6, 0.4, 0.45, 0.9, 1.8))), auto = false, floorValid = true, action = "stop", target = 0, raised = false, pitchSource = "config", pitch = 20.0, clear = 0.9, headLevel = null, drop = null, nearest = 0.9, left = 0.9, center = 0.9, right = 0.9),
        Golden("deep_drop", Scene(holes = listOf(doubleArrayOf(-1.0, 1.0, 1.6, 3.5)), holeDepth = 0.3), auto = false, floorValid = true, action = "stop", target = 0, raised = true, pitchSource = "config", pitch = 20.0, clear = 0.97, headLevel = null, drop = null, nearest = 0.97, left = 0.97, center = 0.97, right = 0.97),
        Golden("empty_auto", Scene(pitchDeg = 12.0), auto = true, floorValid = true, action = "forward", target = 0, raised = false, pitchSource = "image", pitch = 12.0, clear = 5.0, headLevel = null, drop = null, nearest = null, left = null, center = null, right = null),
        Golden("box_ahead_auto", Scene(boxes = listOf(doubleArrayOf(-0.3, 0.3, 0.0, 1.0, 1.5, 1.9)), pitchDeg = 12.0), auto = true, floorValid = true, action = "slow", target = 0, raised = false, pitchSource = "image", pitch = 12.0, clear = 1.5, headLevel = null, drop = null, nearest = 1.5, left = null, center = 1.5, right = null),
        Golden("box_left_auto", Scene(boxes = listOf(doubleArrayOf(-1.2, -0.45, 0.0, 1.0, 1.5, 1.9)), pitchDeg = 12.0), auto = true, floorValid = true, action = "forward", target = 0, raised = false, pitchSource = "image", pitch = 12.0, clear = 5.0, headLevel = null, drop = null, nearest = 1.5, left = 1.5, center = null, right = null),
        Golden("detour_auto", Scene(boxes = listOf(doubleArrayOf(-0.45, 0.15, 0.0, 1.0, 1.8, 2.1)), pitchDeg = 12.0), auto = true, floorValid = true, action = "slight_right", target = 20, raised = false, pitchSource = "image", pitch = 12.0, clear = 1.8, headLevel = null, drop = null, nearest = 1.8, left = 1.8, center = 1.8, right = null),
        Golden("stop_auto", Scene(boxes = listOf(doubleArrayOf(-0.35, 0.35, 0.0, 1.5, 1.0, 1.3)), pitchDeg = 12.0), auto = true, floorValid = true, action = "stop", target = 0, raised = false, pitchSource = "image", pitch = 12.0, clear = 1.0, headLevel = null, drop = null, nearest = 1.0, left = 1.0, center = 1.0, right = 1.0),
        Golden("wall_auto", Scene(boxes = listOf(doubleArrayOf(-5.0, 5.0, 0.0, 3.0, 0.6, 0.8)), pitchDeg = 12.0), auto = true, floorValid = false, action = "caution", target = 0, raised = false, pitchSource = "default", pitch = 20.0, clear = null, headLevel = null, drop = null, nearest = null, left = null, center = null, right = null),
        Golden("head_level_auto", Scene(boxes = listOf(doubleArrayOf(-0.5, 0.5, 1.3, 1.9, 1.5, 1.7)), pitchDeg = 12.0), auto = true, floorValid = true, action = "slow", target = 0, raised = false, pitchSource = "image", pitch = 12.0, clear = 1.5, headLevel = 1.5, drop = null, nearest = 1.5, left = 1.5, center = 1.5, right = 1.5),
        Golden("drop_auto", Scene(holes = listOf(doubleArrayOf(-1.0, 1.0, 1.6, 3.5)), pitchDeg = 12.0), auto = true, floorValid = true, action = "stop", target = 0, raised = true, pitchSource = "image", pitch = 12.0, clear = 1.14, headLevel = null, drop = null, nearest = 1.14, left = 1.14, center = 1.14, right = 1.14),
        Golden("table_top_auto", Scene(boxes = listOf(doubleArrayOf(-0.8, 0.8, 0.7, 0.75, 0.3, 1.6)), pitchDeg = 12.0), auto = true, floorValid = true, action = "stop", target = 0, raised = true, pitchSource = "image", pitch = 12.0, clear = 0.67, headLevel = null, drop = null, nearest = 0.67, left = 0.67, center = 0.67, right = 0.67),
        Golden("low_table_auto", Scene(boxes = listOf(doubleArrayOf(-0.6, 0.6, 0.4, 0.45, 0.9, 1.8)), pitchDeg = 12.0), auto = true, floorValid = true, action = "stop", target = 0, raised = false, pitchSource = "image", pitch = 12.0, clear = 1.03, headLevel = null, drop = null, nearest = 1.03, left = 1.03, center = 1.03, right = 1.03),
        Golden("deep_drop_auto", Scene(holes = listOf(doubleArrayOf(-1.0, 1.0, 1.6, 3.5)), holeDepth = 0.3, pitchDeg = 12.0), auto = true, floorValid = true, action = "slow", target = 0, raised = true, pitchSource = "image", pitch = 12.0, clear = 1.28, headLevel = null, drop = null, nearest = 1.28, left = 1.28, center = 1.28, right = 1.28),
    )

    private fun actionName(a: DepthAction) = when (a) {
        DepthAction.Forward -> "forward"
        DepthAction.SlightLeft -> "slight_left"
        DepthAction.Left -> "left"
        DepthAction.SlightRight -> "slight_right"
        DepthAction.Right -> "right"
        DepthAction.Slow -> "slow"
        DepthAction.Stop -> "stop"
        DepthAction.Caution -> "caution"
    }

    private fun close(name: String, what: String, actual: Double?, expected: Double?, tol: Double = 0.1) {
        if (actual == null || expected == null) assertEquals("$name $what", expected, actual)
        else assertTrue("$name $what: $actual vs $expected", abs(actual - expected) <= tol)
    }

    @Test
    fun matchesThePythonReferenceOnEverySyntheticScene() {
        val failures = ArrayList<String>()
        for (g in goldens) {
            try {
                val disp = SyntheticDepth.render(g.scene)
                val a = FloorPlanner(FloorPlanner.Geometry(pitchDeg = if (g.auto) null else 20.0))
                    .analyze(disp, SyntheticDepth.WIDTH, SyntheticDepth.HEIGHT)
                assertEquals("${g.name} floor", g.floorValid, a.floorValid)
                assertEquals("${g.name} action", g.action, actionName(a.action))
                assertEquals("${g.name} target", g.target, a.targetHeadingDeg)
                assertEquals("${g.name} raised", g.raised, a.raisedSurface)
                assertEquals("${g.name} pitch source", g.pitchSource, a.pitchSource.name.lowercase())
                close(g.name, "pitch", a.pitchDeg, g.pitch, 0.3)
                close(g.name, "clear", a.clearDistanceM, g.clear)
                close(g.name, "head level", a.headLevelM, g.headLevel)
                close(g.name, "drop", a.dropM, g.drop, 0.2)
                close(g.name, "nearest", a.nearestObstacle?.distanceM, g.nearest)
                close(g.name, "left", a.sectors.left, g.left)
                close(g.name, "center", a.sectors.center, g.center)
                close(g.name, "right", a.sectors.right, g.right)
            } catch (e: AssertionError) {
                failures += e.message ?: g.name
            }
        }
        assertTrue("${failures.size} of ${goldens.size} scenes differ:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
