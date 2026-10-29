package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualPathTest {

    private val w = 320
    private val h = 240

    private fun frame(vararg lines: (Float) -> Float, rowsFrom: Float = 0f): CameraFrame {
        val data = ByteArray(w * h)
        for (v in 0 until h) {
            val y = v.toFloat() / h
            for (u in 0 until w) data[v * w + u] = (60 + (u * 7 + v * 13) % 11).toByte()
            if (y < rowsFrom) continue
            for (line in lines) {
                val c = (line(y) * w).toInt()
                for (u in c - 3 until c + 3) if (u in 0 until w) data[v * w + u] = 220.toByte()
            }
        }
        return CameraFrame(data, w, w, h, 0)
    }

    private fun track(detector: LaneDetector, f: CameraFrame): TrackedPath {
        val t = PathTracker()
        var last = t.update(detector.detect(f))
        repeat(9) { last = t.update(detector.detect(f)) }
        return last
    }

    @Test
    fun clearLaneIsConfirmedWithObservedPointsAndACentre() {
        val p = VirtualPath.from(track(LaneDetector(), frame({ y -> 0.5f - 0.32f * y }, { y -> 0.5f + 0.32f * y })), 0.5f)
        assertEquals(PathConfidence.Confirmed, p.confidence)
        assertEquals(VirtualPath.POINTS, p.points.size)
        assertTrue(p.points.all { it.center != null })
        assertTrue("observed ${p.observedShare}", p.observedShare > 0.9f)
    }

    @Test
    fun bendKeepsItsShapeAcrossPoints() {
        val bend = { y: Float -> 0.25f * (1 - y) * (1 - y) }
        val p = VirtualPath.from(track(LaneDetector(), frame({ y -> 0.5f - 0.32f * y + bend(y) }, { y -> 0.5f + 0.32f * y + bend(y) })), 0.5f)
        val far = p.points.first().center!!
        val near = p.points.last().center!!
        assertTrue("far $far near $near", far > near + 0.03f)
    }

    @Test
    fun linesVisibleOnlyNearTheFeetLeaveTheFarPointsEstimated() {
        // Paint only on the lowest 25% of the frame; the far part of the curve is extrapolated.
        val p = VirtualPath.from(track(LaneDetector(), frame({ y -> 0.5f - 0.32f * y }, { y -> 0.5f + 0.32f * y }, rowsFrom = 0.75f)), 0.5f)
        assertTrue(p.points.last().leftObserved)
        assertTrue(!p.points.first().leftObserved)
    }

    @Test
    fun oneEdgeHasNoCentreAndIsNotConfirmed() {
        val p = VirtualPath.from(track(LaneDetector(), frame({ y -> 0.5f - 0.32f * y })), 0.5f)
        assertEquals(PathConfidence.Uncertain, p.confidence)
        assertTrue(p.points.all { it.center == null && it.right == null })
    }

    @Test
    fun nothingSeenGivesNoPoints() {
        val p = VirtualPath.from(track(LaneDetector(), frame()), 0.5f)
        assertEquals(PathConfidence.None, p.confidence)
        assertTrue(p.points.isEmpty())
    }
}
