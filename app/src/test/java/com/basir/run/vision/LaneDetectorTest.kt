package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneDetectorTest {

    private val w = 320
    private val h = 240

    // Dark ground with bright painted lines; each line gives its x (as a frame fraction) for a y fraction.
    private fun frame(vararg lines: (Float) -> Float, paintPx: Int = 6): LumaFrame {
        val data = ByteArray(w * h)
        for (v in 0 until h) {
            val y = v.toFloat() / h
            for (u in 0 until w) data[v * w + u] = (60 + (u * 7 + v * 13) % 11).toByte() // a little texture
            for (line in lines) {
                val center = (line(y) * w).toInt()
                for (u in center - paintPx / 2 until center + paintPx / 2) {
                    if (u in 0 until w) data[v * w + u] = 220.toByte()
                }
            }
        }
        return LumaFrame(data, w, h, w, 0)
    }

    private fun lane(shift: Float = 0f, bend: Float = 0f): Array<(Float) -> Float> = arrayOf(
        { y -> 0.5f - 0.32f * y + shift + bend * (1 - y) * (1 - y) },
        { y -> 0.5f + 0.32f * y + shift + bend * (1 - y) * (1 - y) },
    )

    @Test
    fun centredOnStraightLane() {
        val result = LaneDetector(PathMode.Lane).detect(frame(*lane()))
        assertTrue(result.found)
        assertEquals(0f, result.offset, 0.1f)
        assertEquals(PathShape.Straight, result.shape)
        assertEquals(2, result.lines.size)
    }

    @Test
    fun runnerLeftOfCentreIsToldToMoveRight() {
        // The lane sits to the right in the frame, so the runner is left of its centre.
        val result = LaneDetector(PathMode.Lane).detect(frame(*lane(shift = 0.12f)))
        assertTrue(result.found)
        assertTrue("offset was ${result.offset}", result.offset < -0.2f)
    }

    @Test
    fun runnerRightOfCentreIsToldToMoveLeft() {
        val result = LaneDetector(PathMode.Lane).detect(frame(*lane(shift = -0.12f)))
        assertTrue(result.found)
        assertTrue("offset was ${result.offset}", result.offset > 0.2f)
    }

    @Test
    fun bendToTheRightIsRecognised() {
        val result = LaneDetector(PathMode.Lane).detect(frame(*lane(bend = 0.25f)))
        assertTrue(result.found)
        assertEquals("curvature was ${result.curvature}", PathShape.CurveRight, result.shape)
    }

    @Test
    fun bendToTheLeftIsRecognised() {
        val result = LaneDetector(PathMode.Lane).detect(frame(*lane(bend = -0.25f)))
        assertTrue(result.found)
        assertEquals("curvature was ${result.curvature}", PathShape.CurveLeft, result.shape)
    }

    @Test
    fun singleGuideLineToTheLeft() {
        val result = LaneDetector(PathMode.SingleLine).detect(frame({ 0.4f }))
        assertTrue(result.found)
        assertEquals(0.4f, result.offset, 0.08f)
    }

    @Test
    fun noPaintMeansNoPath() {
        val result = LaneDetector(PathMode.Lane).detect(frame())
        assertFalse(result.found)
    }

    @Test
    fun rotatedSensorFrameIsReadUpright() {
        // A 4x3 sensor image that must turn 90 degrees clockwise to stand upright (3 wide, 4 tall).
        val data = ByteArray(12) { it.toByte() }
        val f = LumaFrame(data, 4, 3, 4, 90)
        assertEquals(3, f.uprightWidth)
        assertEquals(4, f.uprightHeight)
        // Upright top-left comes from the sensor's bottom-left pixel (x 0, y 2 -> index 8).
        assertEquals(8, f.luma(0, 0))
        // Upright top-right comes from the sensor's top-left pixel.
        assertEquals(0, f.luma(2, 0))
    }
}
