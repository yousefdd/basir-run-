package com.basir.run.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Environment selection, jolt handling, surface intrusions and obstacle motion, on drawn data. */
class EngineUpgradesTest {

    private fun tracked(source: PathSource, confidence: Float) =
        PathEstimate(PathStatus.Tracked, source, confidence = confidence, left = Curve(0f, -0.3f, 0.5f), right = Curve(0f, 0.3f, 0.5f))

    private val noLines = PathEstimate.unclear(PathSource.PaintedLines, 0.75f)
    private val noSurface = PathEstimate.unclear(PathSource.SurfaceRegion, 0.75f)

    @Test
    fun selectorPicksWhicheverDetectorTracksThePath() {
        val s = EnvironmentSelector()
        assertEquals(PathSource.SurfaceRegion, s.choose(noLines, tracked(PathSource.SurfaceRegion, 0.7f)).source)
        assertEquals(PathSource.SurfaceRegion, s.active)
    }

    @Test
    fun selectorDoesNotFlipOnOneBetterFrame() {
        val s = EnvironmentSelector()
        s.choose(tracked(PathSource.PaintedLines, 0.6f), noSurface)
        repeat(EnvironmentSelector.SWITCH_FRAMES - 1) {
            assertEquals(PathSource.PaintedLines, s.choose(tracked(PathSource.PaintedLines, 0.6f), tracked(PathSource.SurfaceRegion, 0.9f)).source)
        }
        assertEquals(PathSource.SurfaceRegion, s.choose(tracked(PathSource.PaintedLines, 0.6f), tracked(PathSource.SurfaceRegion, 0.9f)).source)
    }

    @Test
    fun selectorKeepsASingleEdgeWhenNothingIsTracked() {
        val edge = PathEstimate(PathStatus.EdgeOnly, PathSource.PaintedLines, edgeSide = Side.Left, edgeDistance = 0.05f)
        assertEquals(PathStatus.EdgeOnly, EnvironmentSelector().choose(edge, noSurface).status)
    }

    private fun estimate(offset: Float) = PathEstimate(PathStatus.Tracked, PathSource.PaintedLines, confidence = 0.9f, offset = offset)

    @Test
    fun aJoltDoesNotBreakStabilityOrSwingTheEstimate() {
        val t = PathTracker()
        repeat(10) { t.update(estimate(0f)) }
        val jolted = t.update(estimate(0.9f), shaky = true)
        assertTrue(jolted.stable)
        assertTrue("offset ${jolted.offset}", jolted.offset < 0.15f)
    }

    @Test
    fun losingThePathIsNotSmoothedAwayEvenDuringAJolt() {
        val t = PathTracker()
        repeat(10) { t.update(estimate(0f)) }
        assertFalse(t.update(PathEstimate.unclear(PathSource.PaintedLines, 0.75f), shaky = true).stable)
    }

    // Grey track surface in a lane, with an optional dark block (a pole or bag) in the corridor.
    private fun scene(block: Boolean): CameraFrame {
        val w = 320
        val h = 240
        val y = ByteArray(w * h) { 130 }
        val u = ByteArray((w / 2) * (h / 2)) { 120 }
        val v = ByteArray((w / 2) * (h / 2)) { 150 }
        if (block) {
            for (py in (0.62f * h).toInt() until (0.82f * h).toInt()) for (px in (0.44f * w).toInt() until (0.56f * w).toInt()) {
                y[py * w + px] = 40
                if (px % 2 == 0 && py % 2 == 0) {
                    u[(py / 2) * (w / 2) + px / 2] = 128.toByte()
                    v[(py / 2) * (w / 2) + px / 2] = 128.toByte()
                }
            }
        }
        return CameraFrame(y, w, w, h, 0, u, v, w / 2, 1)
    }

    private val corridor = PathEstimate(
        PathStatus.Tracked, PathSource.PaintedLines, confidence = 0.9f,
        left = Curve(0f, -0.3f, 0.5f), right = Curve(0f, 0.3f, 0.5f),
    )

    @Test
    fun objectOnTheSurfaceIsFoundWithoutAModelClass() {
        val found = IntrusionDetector().detect(scene(block = true), corridor, 0.5f)
        assertEquals(1, found.size)
        assertEquals(IntrusionDetector.LABEL, found.single().detection.label)
        assertTrue(found.single().detection.left < 0.5f && found.single().detection.right > 0.5f)
    }

    @Test
    fun plainSurfaceGivesNoIntrusion() {
        assertTrue(IntrusionDetector().detect(scene(block = false), corridor, 0.5f).isEmpty())
    }

    @Test
    fun noCorridorMeansNoIntrusionSearch() {
        assertTrue(IntrusionDetector().detect(scene(block = true), PathEstimate.unclear(PathSource.PaintedLines, 0.75f), 0.5f).isEmpty())
    }

    private fun box(left: Float, top: Float, bottom: Float, width: Float = 0.12f) = Detection("person", 0.8f, left, top, left + width, bottom)

    @Test
    fun approachingPossibleObstacleBecomesAHazard() {
        val m = ObstacleMotionTracker()
        val first = box(0.45f, 0.5f, 0.66f)
        m.annotate(listOf(ObstacleFinding(first, 0.8f, ObstacleLevel.Possible)), listOf(first), 0.5f)
        val nearer = box(0.44f, 0.48f, 0.72f, 0.14f)
        val out = m.annotate(listOf(ObstacleFinding(nearer, 0.8f, ObstacleLevel.Possible)), listOf(nearer), 0.5f).single()
        assertEquals(ObstacleMotion.Approaching, out.motion)
        assertEquals(ObstacleLevel.Hazard, out.level)
    }

    @Test
    fun crossingTowardsTheCorridorIsNoticed() {
        val m = ObstacleMotionTracker()
        val a = box(0.70f, 0.5f, 0.66f)
        m.annotate(emptyList(), listOf(a), 0.5f)
        val b = box(0.65f, 0.5f, 0.66f)
        val out = m.annotate(listOf(ObstacleFinding(b, 0.3f, ObstacleLevel.Possible)), listOf(b), 0.5f).single()
        assertEquals(ObstacleMotion.Crossing, out.motion)
    }

    @Test
    fun stillObjectKeepsItsLevel() {
        val m = ObstacleMotionTracker()
        val a = box(0.45f, 0.5f, 0.66f)
        m.annotate(emptyList(), listOf(a), 0.5f)
        val out = m.annotate(listOf(ObstacleFinding(a, 0.8f, ObstacleLevel.Possible)), listOf(a), 0.5f).single()
        assertEquals(ObstacleMotion.Still, out.motion)
        assertEquals(ObstacleLevel.Possible, out.level)
    }
}
