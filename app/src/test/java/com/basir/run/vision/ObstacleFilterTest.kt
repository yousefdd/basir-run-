package com.basir.run.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleFilterTest {

    private val lane = PathEstimate(
        PathStatus.Tracked, PathSource.PaintedLines,
        left = Curve(0f, -0.3f, 0.5f), right = Curve(0f, 0.3f, 0.5f),
    )

    private fun person(left: Float, right: Float, top: Float = 0.3f, bottom: Float = 0.8f, score: Float = 0.8f) =
        Detection("person", score, left, top, right, bottom)

    @Test
    fun personInsideTheLaneMatters() {
        assertTrue(ObstacleFilter.isRelevant(person(0.4f, 0.55f), lane, 0.5f))
    }

    @Test
    fun personBesideTheLaneDoesNot() {
        // At y 0.8 the lane spans 0.26 to 0.74.
        assertFalse(ObstacleFilter.isRelevant(person(0.8f, 0.95f), lane, 0.5f))
    }

    @Test
    fun farAwayOrSmallObjectsAreIgnored() {
        assertFalse(ObstacleFilter.isRelevant(person(0.45f, 0.5f, top = 0.3f, bottom = 0.4f), lane, 0.5f))
        assertFalse(ObstacleFilter.isRelevant(person(0.48f, 0.52f, top = 0.6f, bottom = 0.68f), lane, 0.5f))
    }

    @Test
    fun unsupportedOrUnsureDetectionsAreIgnored() {
        assertFalse(ObstacleFilter.isRelevant(Detection("tv", 0.9f, 0.4f, 0.3f, 0.6f, 0.9f), lane, 0.5f))
        assertFalse(ObstacleFilter.isRelevant(person(0.4f, 0.6f, score = 0.3f), lane, 0.5f))
    }

    @Test
    fun withoutAPathACentralCorridorIsUsed() {
        assertTrue(ObstacleFilter.isRelevant(person(0.45f, 0.6f), null, 0.5f))
        assertFalse(ObstacleFilter.isRelevant(person(0.85f, 0.95f), null, 0.5f))
    }

    @Test
    fun confirmationNeedsTwoOfThreeRuns() {
        val c = ObstacleConfirmation()
        assertFalse(c.update(true))
        assertTrue(c.update(true))
        assertTrue(c.update(false))
        assertFalse(c.update(false))
    }
}
