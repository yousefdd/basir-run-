package com.basir.run.engine

import com.basir.run.vision.FrameQuality
import com.basir.run.vision.PathEstimate
import com.basir.run.vision.PathSource
import com.basir.run.vision.PathStatus
import com.basir.run.vision.TrackedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {

    private val good = FrameQuality(120f, 40f, 12f, 0f, 0f)
    private val path = TrackedPath(PathEstimate(PathStatus.Tracked, PathSource.PaintedLines, confidence = 0.8f), 0f, 0f, true)
    private val allGood = ReadinessInput(24f, 60f, good, path, 40f, 0.2f, true, true, 300L, true, 400L)

    @Test
    fun allChecksPass() {
        assertTrue(Readiness.evaluate(allGood).all { it.passed })
    }

    @Test
    fun eachProblemFailsItsOwnCheck() {
        fun failed(i: ReadinessInput) = Readiness.evaluate(i).filter { !it.passed }.map { it.check }
        assertEquals(emptyList<Check>(), failed(allGood.copy(framesPerSecond = 6f)))
        assertEquals(emptyList<Check>(), failed(allGood.copy(processingP90Ms = 300f)))
        assertEquals(emptyList<Check>(), failed(allGood.copy(cameraPitchDeg = 2f))) // no angle check (owner's request)
        assertEquals(emptyList<Check>(), failed(allGood.copy(shakeRadPerS = 2f)))
        assertEquals(listOf(Check.Obstacles), failed(allGood.copy(obstacleDetectorReady = false)))
        assertEquals(listOf(Check.Path), failed(allGood.copy(path = path.copy(stable = false))))
        assertEquals(listOf(Check.Depth), failed(allGood.copy(depthReady = false)))
        assertEquals(listOf(Check.Depth), failed(allGood.copy(depthAgeMs = null)))
        assertEquals(listOf(Check.Depth), failed(allGood.copy(depthAgeMs = 2500L)))
        assertEquals(listOf(Check.Depth), failed(allGood.copy(floorVisible = false)))
    }

    @Test
    fun unevenGroundSeenByTheSurfaceModelPassesTheDepthCheck() {
        // Stones: no single floor plane in the depth map, but walkable ground ahead in the image
        val stones = allGood.copy(floorVisible = false, walkableAhead = true)
        assertTrue(Readiness.evaluate(stones).all { it.passed })
        assertEquals(listOf(Check.Depth), Readiness.evaluate(stones.copy(walkableAhead = false)).filter { !it.passed }.map { it.check })
    }

    @Test
    fun aTrackedGuideLineIsEnoughForThePathCheck() {
        val noImagePath = allGood.copy(path = path.copy(stable = false), lineTracked = true, lineDetail = "خط إرشادي")
        assertTrue(Readiness.evaluate(noImagePath).all { it.passed })
    }

    @Test
    fun runningWaitsForTheVibrationQuiz() {
        val failed = Readiness.evaluate(allGood.copy(hapticsReady = false)).filter { !it.passed }.map { it.check }
        assertEquals(listOf(Check.Haptics), failed)
    }

    @Test
    fun readyOnlyAfterHoldingForThreeSeconds() {
        val timer = ReadinessTimer()
        val ok = Readiness.evaluate(allGood)
        val bad = Readiness.evaluate(allGood.copy(quality = null))
        assertFalse(timer.update(ok, 0))
        assertFalse(timer.update(ok, 2000))
        assertFalse(timer.update(bad, 2500))
        assertFalse(timer.update(ok, 3000))
        assertTrue(timer.update(ok, 6100))
    }
}
