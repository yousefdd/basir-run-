package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathTrackerTest {

    private fun tracked(offset: Float, curvature: Float = 0f) =
        PathEstimate(PathStatus.Tracked, PathSource.PaintedLines, confidence = 0.9f, offset = offset, curvature = curvature)

    private val unclear = PathEstimate.unclear(PathSource.PaintedLines, 0.75f)

    @Test
    fun becomesStableAfterConsistentFrames() {
        val t = PathTracker()
        repeat(7) { assertFalse(t.update(tracked(0.1f)).stable) }
        assertTrue(t.update(tracked(0.1f)).stable)
    }

    @Test
    fun singleJumpBarelyMovesTheEstimate() {
        val t = PathTracker()
        repeat(10) { t.update(tracked(0f)) }
        val after = t.update(tracked(0.9f))
        assertTrue("offset ${after.offset}", after.offset < 0.15f)
        assertFalse(after.stable)
    }

    @Test
    fun lostFramesBreakStability() {
        val t = PathTracker()
        repeat(10) { t.update(tracked(0f)) }
        assertFalse(t.update(unclear).stable)
    }

    @Test
    fun followsAGradualDrift() {
        val t = PathTracker()
        var last = t.update(tracked(0f))
        for (i in 1..30) last = t.update(tracked(i * 0.02f))
        assertEquals(0.6f, last.offset, 0.08f)
        assertTrue(last.stable)
    }
}
