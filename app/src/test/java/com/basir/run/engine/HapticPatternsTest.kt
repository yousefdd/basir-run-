package com.basir.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPatternsTest {

    private fun pulses(h: Haptic) = HapticPatterns.waveform(h, 1f, 1f).let { w ->
        // Count separate vibrations: runs of non-zero amplitude.
        var count = 0
        var inPulse = false
        for (a in w.amplitudes) {
            if (a > 0 && !inPulse) count++
            inPulse = a > 0
        }
        count
    }

    @Test
    fun patternsHaveTheAgreedShapes() {
        assertEquals(1, pulses(Haptic.SteerLeft))
        assertEquals(2, pulses(Haptic.SteerRight))
        assertEquals(3, pulses(Haptic.Attention))
        assertEquals(1, pulses(Haptic.Stop))
        assertEquals(1, pulses(Haptic.BendAhead))
        assertEquals(2, pulses(Haptic.Confirm))
        val swell = HapticPatterns.waveform(Haptic.BendAhead, 1f, 1f).amplitudes.filter { it > 0 }
        assertTrue("swell rises", swell.zipWithNext().all { (a, b) -> b > a })
        assertTrue("stop is long", HapticPatterns.waveform(Haptic.Stop, 1f, 1f).totalMs >= 800)
    }

    @Test
    fun leftAndRightAreTellApartByCountNotStrength() {
        val l = HapticPatterns.waveform(Haptic.SteerLeft, 1f, 1f)
        val r = HapticPatterns.waveform(Haptic.SteerRight, 1f, 1f)
        assertEquals(l.amplitudes.max(), r.amplitudes.max())
    }

    @Test
    fun largerDriftSteersFirmer() {
        val gentle = HapticPatterns.waveform(Haptic.SteerLeft, 1f, 1f, level = 1)
        val firm = HapticPatterns.waveform(Haptic.SteerLeft, 1f, 1f, level = 2)
        assertTrue(firm.amplitudes.max() > gentle.amplitudes.max())
    }

    @Test
    fun strengthAndDurationAreApplied() {
        val soft = HapticPatterns.waveform(Haptic.SteerLeft, 0.5f, 1.5f)
        val normal = HapticPatterns.waveform(Haptic.SteerLeft, 1f, 1f)
        assertTrue(soft.amplitudes[1] < normal.amplitudes[1])
        assertTrue(soft.timings[1] > normal.timings[1])
    }

    @Test
    fun settingsAreClampedToSafeRanges() {
        val w = HapticPatterns.waveform(Haptic.Stop, 0f, 10f)
        assertTrue(w.amplitudes[1] > 0)
        assertEquals(Math.round(900 * HapticPatterns.MAX_DURATION).toLong(), w.timings[1])
    }

    @Test
    fun stopIsNotInterruptedBySteering() {
        val s = HapticScheduler()
        assertEquals(HapticScheduler.Verdict.Play(false), s.decide(Haptic.Stop, 900, 0))
        assertEquals(HapticScheduler.Verdict.Skip, s.decide(Haptic.SteerLeft, 90, 300))
        assertEquals(HapticScheduler.Verdict.Play(false), s.decide(Haptic.SteerLeft, 90, 1000))
    }

    @Test
    fun attentionCutsSteeringShort() {
        val s = HapticScheduler()
        s.decide(Haptic.SteerRight, 300, 0)
        assertEquals(HapticScheduler.Verdict.Play(true), s.decide(Haptic.Attention, 350, 100))
    }
}
