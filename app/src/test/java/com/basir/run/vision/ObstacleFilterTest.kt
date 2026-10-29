package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObstacleFilterTest {

    private val lane = PathEstimate(
        PathStatus.Tracked, PathSource.PaintedLines,
        left = Curve(0f, -0.3f, 0.5f), right = Curve(0f, 0.3f, 0.5f),
    )

    private fun person(left: Float, right: Float, top: Float = 0.3f, bottom: Float = 0.8f, score: Float = 0.8f) =
        Detection("person", score, left, top, right, bottom)

    private fun level(d: Detection, path: PathEstimate? = lane) = ObstacleAssessment.assess(d, path, 0.5f)?.level

    @Test
    fun largeCentralPersonIsAHazard() {
        assertEquals(ObstacleLevel.Hazard, level(person(0.4f, 0.55f)))
    }

    @Test
    fun personAtTheLaneEdgeIsOnlyPossible() {
        // At y 0.8 the lane spans 0.26 to 0.74; this box is mostly outside it.
        assertEquals(ObstacleLevel.Possible, level(person(0.7f, 0.9f)))
    }

    @Test
    fun personBesideTheLaneIsIgnored() {
        assertNull(level(person(0.8f, 0.95f)))
    }

    @Test
    fun farAwayOrSmallObjectsAreIgnored() {
        assertNull(level(person(0.45f, 0.5f, top = 0.3f, bottom = 0.4f)))
        assertNull(level(person(0.48f, 0.52f, top = 0.6f, bottom = 0.68f)))
    }

    @Test
    fun midDistanceObjectInTheLaneIsPossible() {
        assertEquals(ObstacleLevel.Possible, level(person(0.45f, 0.6f, top = 0.5f, bottom = 0.65f)))
    }

    @Test
    fun unsupportedOrUnsureDetectionsAreIgnored() {
        assertNull(level(Detection("tv", 0.9f, 0.4f, 0.3f, 0.6f, 0.9f)))
        assertNull(level(person(0.4f, 0.6f, score = 0.3f)))
    }

    @Test
    fun withoutAPathACentralCorridorIsUsed() {
        assertEquals(ObstacleLevel.Hazard, level(person(0.45f, 0.6f), path = null))
        assertNull(level(person(0.85f, 0.95f), path = null))
    }

    private fun finding(level: ObstacleLevel, score: Float = 0.6f, bottom: Float = 0.7f) =
        ObstacleFinding(Detection("person", score, 0.4f, 0.3f, 0.6f, bottom), 0.8f, level)

    @Test
    fun strongNearHazardIsReportedOnTheFirstRun() {
        assertEquals(ObstacleLevel.Hazard, ObstacleTracker().update(listOf(finding(ObstacleLevel.Hazard, score = 0.85f, bottom = 0.9f))))
    }

    @Test
    fun weakerDetectionsNeedTwoOfThreeRuns() {
        val t = ObstacleTracker()
        assertEquals(ObstacleLevel.None, t.update(listOf(finding(ObstacleLevel.Hazard))))
        assertEquals(ObstacleLevel.Hazard, t.update(listOf(finding(ObstacleLevel.Hazard))))
        assertEquals(ObstacleLevel.Hazard, t.update(listOf(finding(ObstacleLevel.Possible))))
        assertEquals(ObstacleLevel.Possible, t.update(emptyList()))
        assertEquals(ObstacleLevel.None, t.update(emptyList()))
    }
}
