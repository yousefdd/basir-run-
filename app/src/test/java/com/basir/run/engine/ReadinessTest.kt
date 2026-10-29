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
    private val allGood = ReadinessInput(24f, 60f, good, path, 40f, 0.2f, true)

    @Test
    fun allChecksPass() {
        assertTrue(Readiness.evaluate(allGood).all { it.passed })
    }

    @Test
    fun eachProblemFailsItsOwnCheck() {
        fun failed(i: ReadinessInput) = Readiness.evaluate(i).filter { !it.passed }.map { it.check }
        assertEquals(listOf(Check.Speed), failed(allGood.copy(framesPerSecond = 6f)))
        assertEquals(listOf(Check.Speed), failed(allGood.copy(processingP90Ms = 300f)))
        assertEquals(listOf(Check.Angle), failed(allGood.copy(cameraPitchDeg = 5f)))
        assertEquals(listOf(Check.Steady), failed(allGood.copy(shakeRadPerS = 2f)))
        assertEquals(listOf(Check.Obstacles), failed(allGood.copy(obstacleDetectorReady = false)))
        assertEquals(listOf(Check.Path), failed(allGood.copy(path = path.copy(stable = false))))
    }

    @Test
    fun readyOnlyAfterHoldingForThreeSeconds() {
        val timer = ReadinessTimer()
        val ok = Readiness.evaluate(allGood)
        val bad = Readiness.evaluate(allGood.copy(cameraPitchDeg = 5f))
        assertFalse(timer.update(ok, 0))
        assertFalse(timer.update(ok, 2000))
        assertFalse(timer.update(bad, 2500))
        assertFalse(timer.update(ok, 3000))
        assertTrue(timer.update(ok, 6100))
    }
}
