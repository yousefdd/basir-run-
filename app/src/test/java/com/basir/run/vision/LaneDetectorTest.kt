package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logic tests on drawn frames. They check the geometry and the safety rules of the detector;
 * they are not evidence of how it performs on real camera footage.
 */
class LaneDetectorTest {

    private val w = 320
    private val h = 240

    private fun frame(vararg lines: (Float) -> Float, paintPx: Int = 6, background: Int = 60): CameraFrame {
        val data = ByteArray(w * h)
        for (v in 0 until h) {
            val y = v.toFloat() / h
            for (u in 0 until w) data[v * w + u] = (background + (u * 7 + v * 13) % 11).toByte()
            for (line in lines) {
                val center = (line(y) * w).toInt()
                for (u in center - paintPx / 2 until center + paintPx / 2) if (u in 0 until w) data[v * w + u] = 220.toByte()
            }
        }
        return CameraFrame(data, w, w, h, 0)
    }

    private fun lane(shift: Float = 0f, bend: Float = 0f): Array<(Float) -> Float> = arrayOf(
        { y -> 0.5f - 0.32f * y + shift + bend * (1 - y) * (1 - y) },
        { y -> 0.5f + 0.32f * y + shift + bend * (1 - y) * (1 - y) },
    )

    @Test
    fun straightLaneCentred() {
        val r = LaneDetector().detect(frame(*lane()))
        assertEquals(PathStatus.Tracked, r.status)
        assertEquals(0f, r.offset, 0.1f)
        assertEquals(0f, r.curvature, 0.12f)
        assertTrue("confidence ${r.confidence}", r.confidence > 0.6f)
    }

    @Test
    fun runnerRightOfCentreHasPositiveOffset() {
        // Lane drawn to the left of the camera means the runner is to the right of its centre.
        val r = LaneDetector().detect(frame(*lane(shift = -0.12f)))
        assertEquals(PathStatus.Tracked, r.status)
        assertTrue("offset ${r.offset}", r.offset > 0.2f)
    }

    @Test
    fun runnerLeftOfCentreHasNegativeOffset() {
        val r = LaneDetector().detect(frame(*lane(shift = 0.12f)))
        assertTrue("offset ${r.offset}", r.offset < -0.2f)
    }

    @Test
    fun bendsAreSignedByDirection() {
        assertTrue(LaneDetector().detect(frame(*lane(bend = 0.25f))).curvature > 0.12f)
        assertTrue(LaneDetector().detect(frame(*lane(bend = -0.25f))).curvature < -0.12f)
    }

    @Test
    fun calibrationMovesTheRunnerReference() {
        // Harness mounted off-centre: while standing in the middle, the lane centre appears at 0.6.
        val detector = LaneDetector()
        detector.calibration = Calibration(runnerX = 0.6f, halfWidth = 0.32f)
        val r = detector.detect(frame(*lane(shift = 0.1f)))
        assertEquals(PathStatus.Tracked, r.status)
        assertEquals(0f, r.offset, 0.1f)
    }

    @Test
    fun oneEdgeDoesNotInventACentre() {
        val r = LaneDetector().detect(frame({ y -> 0.5f - 0.32f * y }))
        assertEquals(PathStatus.EdgeOnly, r.status)
        assertEquals(Side.Left, r.edgeSide)
        assertEquals(null, r.center)
    }

    @Test
    fun crowdedParallelLinesAreAmbiguous() {
        val r = LaneDetector().detect(
            frame(
                { y -> 0.5f - 0.32f * y }, { y -> 0.5f - 0.32f * y - 0.035f },
                { y -> 0.5f + 0.32f * y }, { y -> 0.5f + 0.32f * y + 0.035f },
            )
        )
        assertEquals(PathStatus.Unclear, r.status)
        assertTrue(r.ambiguous)
    }

    @Test
    fun widthFarFromCalibrationIsRejected() {
        val detector = LaneDetector()
        detector.calibration = Calibration(runnerX = 0.5f, halfWidth = 0.15f)
        assertEquals(PathStatus.Unclear, detector.detect(frame(*lane())).status)
    }

    @Test
    fun widePatchOfSunIsNotPaint() {
        // A bright band 20% of the frame wide, like sunlight between shadows.
        val r = LaneDetector().detect(frame({ 0.5f }, paintPx = 64))
        assertEquals(PathStatus.Unclear, r.status)
    }

    @Test
    fun emptyGroundIsUnclear() {
        assertEquals(PathStatus.Unclear, LaneDetector().detect(frame()).status)
    }

    @Test
    fun rotatedSensorFrameIsReadUpright() {
        val data = ByteArray(12) { it.toByte() }
        val f = CameraFrame(data, 4, 4, 3, 90)
        assertEquals(3, f.uprightWidth)
        assertEquals(4, f.uprightHeight)
        assertEquals(8, f.luma(0, 0))
        assertEquals(0, f.luma(2, 0))
    }
}
