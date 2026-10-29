package com.basir.run.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticPatternsTest {

    private fun pulses(h: Haptic) = HapticPatterns.waveform(h, 1f, 1f).timings.filterIndexed { i, _ -> i % 2 == 1 }

    @Test
    fun patternsHaveTheAgreedPulseCounts() {
        assertEquals(1, pulses(Haptic.Correction).size)
        assertEquals(2, pulses(Haptic.Attention).size)
        assertEquals(3, pulses(Haptic.Hazard).size)
        assertEquals(1, pulses(Haptic.Stop).size)
        assertTrue("stop is long", pulses(Haptic.Stop).single() >= 3 * pulses(Haptic.Correction).single())
    }

    @Test
    fun strengthAndDurationAreApplied() {
        val soft = HapticPatterns.waveform(Haptic.Correction, 0.5f, 1.5f)
        val normal = HapticPatterns.waveform(Haptic.Correction, 1f, 1f)
        assertTrue(soft.amplitudes[1] < normal.amplitudes[1])
        assertTrue(soft.timings[1] > normal.timings[1])
    }

    @Test
    fun settingsAreClampedToSafeRanges() {
        val w = HapticPatterns.waveform(Haptic.Stop, 0f, 10f)
        assertTrue(w.amplitudes[1] > 0)
        assertEquals((900 * HapticPatterns.MAX_DURATION).toLong(), w.timings[1])
    }

    @Test
    fun stopIsNotInterruptedByACorrection() {
        val s = HapticScheduler()
        assertEquals(HapticScheduler.Verdict.Play(false), s.decide(Haptic.Stop, 900, 0))
        assertEquals(HapticScheduler.Verdict.Skip, s.decide(Haptic.Correction, 90, 300))
        assertEquals(HapticScheduler.Verdict.Play(false), s.decide(Haptic.Correction, 90, 1000))
    }

    @Test
    fun hazardCutsACorrectionShort() {
        val s = HapticScheduler()
        s.decide(Haptic.Correction, 300, 0)
        assertEquals(HapticScheduler.Verdict.Play(true), s.decide(Haptic.Hazard, 350, 100))
    }
}
