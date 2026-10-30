package com.basir.run.vision.depth

import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The running thresholds on synthetic scenes with known geometry (chest height, 20° tilt). */
class DepthHazardsTest {

    private fun hazard(scene: Scene): DepthHazard {
        val a = FloorPlanner(FloorPlanner.Geometry(pitchDeg = 20.0), DepthHazards.RUNNING)
            .analyze(SyntheticDepth.render(scene), SyntheticDepth.WIDTH, SyntheticDepth.HEIGHT)
        return DepthHazards.assess(a)
    }

    private fun box(near: Double, far: Double, x0: Double = -0.3, x1: Double = 0.3, y0: Double = 0.0, y1: Double = 1.0) =
        Scene(boxes = listOf(doubleArrayOf(x0, x1, y0, y1, near, far)))

    @Test
    fun openFloorIsNoHazard() {
        val h = hazard(Scene())
        assertEquals(ObstacleLevel.None, h.level)
        assertTrue(h.floorVisible)
    }

    @Test
    fun obstacleWithinStoppingRangeIsAHazard() {
        val h = hazard(box(1.5, 1.9))
        assertEquals(ObstacleLevel.Hazard, h.level)
        assertEquals(HazardKind.Object, h.kind)
    }

    @Test
    fun obstacleFurtherAheadIsPossible() {
        assertEquals(ObstacleLevel.Possible, hazard(box(3.0, 3.4)).level)
    }

    @Test
    fun obstacleBesideTheCorridorIsIgnored() {
        assertEquals(ObstacleLevel.None, hazard(box(1.5, 1.9, x0 = -1.6, x1 = -0.9)).level)
    }

    @Test
    fun headLevelObstacleIsNamed() {
        val h = hazard(box(1.5, 1.7, x0 = -0.5, x1 = 0.5, y0 = 1.3, y1 = 1.9))
        assertEquals(ObstacleLevel.Hazard, h.level)
        assertEquals(HazardKind.HeadLevel, h.kind)
    }

    @Test
    fun wallWithoutVisibleFloorReportsNoFloor() {
        val h = hazard(Scene(boxes = listOf(doubleArrayOf(-5.0, 5.0, 0.0, 3.0, 0.6, 0.8))))
        assertFalse(h.floorVisible)
        assertEquals(ObstacleLevel.None, h.level)
    }

    @Test
    fun missingAnalysisIsNeverReadAsClear() {
        assertFalse(DepthHazards.assess(null).floorVisible)
    }

    @Test
    fun possibleNeedsTwoResultsButAHazardCountsAtOnce() {
        val c = DepthConfirm()
        val possible = DepthHazard(ObstacleLevel.Possible, HazardKind.Object, true)
        val stop = DepthHazard(ObstacleLevel.Hazard, HazardKind.Object, true)
        assertEquals(ObstacleLevel.None, c.update(possible).level)
        assertEquals(ObstacleLevel.Possible, c.update(possible).level)
        c.reset()
        assertEquals(ObstacleLevel.Hazard, c.update(stop).level)
    }
}
