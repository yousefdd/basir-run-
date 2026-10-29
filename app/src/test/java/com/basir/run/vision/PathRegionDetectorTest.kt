package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logic tests on drawn frames of a grey path through green grass; not real footage. */
class PathRegionDetectorTest {

    private val w = 320
    private val h = 240

    private data class Yuv(val y: Int, val u: Int, val v: Int)

    private val grass = Yuv(110, 100, 105)
    private val pavement = Yuv(140, 126, 130)

    // left/right give the path edges as frame fractions for a y fraction.
    private fun frame(
        left: (Float) -> Float,
        right: (Float) -> Float,
        surface: Yuv = pavement,
        outside: Yuv = grass,
        shadow: ((Float, Float) -> Boolean)? = null,
    ): CameraFrame {
        val yp = ByteArray(w * h)
        val up = ByteArray((w / 2) * (h / 2))
        val vp = ByteArray((w / 2) * (h / 2))
        for (v in 0 until h) for (u in 0 until w) {
            val fy = v.toFloat() / h
            val fx = u.toFloat() / w
            val c = if (fx >= left(fy) && fx <= right(fy)) surface else outside
            var luma = c.y + (u * 7 + v * 13) % 9 - 4
            if (shadow?.invoke(fx, fy) == true) luma -= 45
            yp[v * w + u] = luma.coerceIn(0, 255).toByte()
            if (u % 2 == 0 && v % 2 == 0) {
                up[(v / 2) * (w / 2) + u / 2] = c.u.toByte()
                vp[(v / 2) * (w / 2) + u / 2] = c.v.toByte()
            }
        }
        return CameraFrame(yp, w, w, h, 0, up, vp, w / 2, 1)
    }

    @Test
    fun straightPathCentred() {
        val r = PathRegionDetector().detect(frame({ y -> 0.5f - 0.3f * y }, { y -> 0.5f + 0.3f * y }))
        assertEquals(PathStatus.Tracked, r.status)
        assertEquals(0f, r.offset, 0.15f)
        assertTrue("confidence ${r.confidence}", r.confidence > 0.55f)
    }

    @Test
    fun runnerNearTheRightSide() {
        val r = PathRegionDetector().detect(frame({ 0.1f }, { 0.6f }))
        assertEquals(PathStatus.Tracked, r.status)
        assertTrue("offset ${r.offset}", r.offset > 0.15f)
    }

    @Test
    fun pathBendingLeft() {
        val bend = { y: Float -> -0.25f * (1 - y) * (1 - y) }
        val r = PathRegionDetector().detect(frame({ y -> 0.5f - 0.3f * y + bend(y) }, { y -> 0.5f + 0.3f * y + bend(y) }))
        assertEquals(PathStatus.Tracked, r.status)
        assertTrue("curvature ${r.curvature}", r.curvature < -0.1f)
    }

    @Test
    fun tolerantOfTreeShadowStripes() {
        val r = PathRegionDetector().detect(
            frame({ y -> 0.5f - 0.3f * y }, { y -> 0.5f + 0.3f * y }, shadow = { _, y -> ((y * 20).toInt() % 3) == 0 })
        )
        assertEquals(PathStatus.Tracked, r.status)
    }

    @Test
    fun pathThatBlendsIntoSurroundingsIsUnclear() {
        // Surface and surroundings the same colour: there are no edges to report.
        val r = PathRegionDetector().detect(frame({ y -> 0.5f - 0.3f * y }, { y -> 0.5f + 0.3f * y }, outside = pavement))
        assertEquals(PathStatus.Unclear, r.status)
    }

    @Test
    fun noColourMeansNoRegionEstimate() {
        val frame = CameraFrame(ByteArray(w * h) { 100 }, w, w, h, 0)
        assertEquals(PathStatus.Unclear, PathRegionDetector().detect(frame).status)
    }
}
