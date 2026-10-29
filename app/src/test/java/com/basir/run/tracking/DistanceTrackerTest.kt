package com.basir.run.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceTrackerTest {

    // About 1 m of latitude.
    private val metre = 1.0 / 111_195.0

    private fun fix(second: Int, north: Double, accuracy: Float?, speed: Float? = null, speedAcc: Float? = null) =
        Fix(second * 1_000_000_000L, 24.7 + north * metre, 46.6, accuracy, speed, speedAcc)

    @Test
    fun dopplerSpeedCountsDistanceEvenWithPoorAccuracy() {
        val t = DistanceTracker()
        // 25 m accuracy used to be rejected outright; speed keeps counting.
        for (s in 0..60) t.accept(fix(s, s * 3.0, 25f, speed = 3f, speedAcc = 0.5f))
        assertEquals(180.0, t.distanceM, 5.0)
        assertTrue(t.diagnostics.usedSpeed > 50)
    }

    @Test
    fun positionsCountWhenNoSpeedIsReported() {
        val t = DistanceTracker()
        for (s in 0..60) t.accept(fix(s, s * 3.0, 15f))
        assertEquals(180.0, t.distanceM, 10.0)
    }

    @Test
    fun standingStillAddsNothing() {
        val t = DistanceTracker()
        for (s in 0..60) t.accept(fix(s, if (s % 2 == 0) 0.0 else 2.0, 12f, speed = 0.2f, speedAcc = 0.4f))
        assertEquals(0.0, t.distanceM, 0.01)
    }

    @Test
    fun gpsJumpIsRejected() {
        val t = DistanceTracker()
        t.accept(fix(0, 0.0, 5f))
        t.accept(fix(1, 4.0, 5f))
        t.accept(fix(2, 300.0, 5f)) // 296 m in one second
        assertEquals(4.0, t.distanceM, 0.5)
        assertEquals(1, t.diagnostics.rejectedJump)
    }

    @Test
    fun veryPoorAccuracyIsCountedAsRejected() {
        val t = DistanceTracker()
        for (s in 0..10) t.accept(fix(s, s * 3.0, 80f))
        assertEquals(0.0, t.distanceM, 0.01)
        assertEquals(10, t.diagnostics.rejectedAccuracy)
    }

    @Test
    fun paceFromSteadyRunning() {
        val t = DistanceTracker()
        for (s in 0..40) t.accept(fix(s, s * 3.0, 8f, speed = 3f, speedAcc = 0.3f))
        // 3 m/s is 333 s per km.
        assertEquals(333.0, t.currentPaceSecPerKm()!!.toDouble(), 10.0)
    }
}
