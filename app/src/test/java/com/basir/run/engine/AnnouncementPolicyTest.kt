package com.basir.run.engine

import com.basir.run.vision.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPolicyTest {

    private val centred = Decision(SystemState.PathTracked, Severity.Info, Cue.Centered)
    private val correctRight = Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, Side.Right, 1)
    private val lost = Decision(SystemState.TrackingLost, Severity.Critical, Cue.LostStop)

    private fun said(out: List<Output>) = out.filterIsInstance<Output.Say>().map { it.phrase }

    @Test
    fun stopInterruptsAndRepeatsEveryThreeSeconds() {
        val p = AnnouncementPolicy()
        val first = p.next(lost, 0)
        assertEquals(listOf(Phrase.LostStop), said(first))
        assertTrue((first.first() as Output.Say).interrupt)
        assertTrue(first.contains(Output.Buzz(Haptic.Stop)))
        assertTrue(said(p.next(lost, 1000)).isEmpty())
        assertEquals(listOf(Phrase.LostStop), said(p.next(lost, 3100)))
    }

    @Test
    fun correctionIsNotRepeatedEveryFrame() {
        val p = AnnouncementPolicy()
        assertEquals(listOf(Phrase.CorrectRight), said(p.next(correctRight, 0)))
        for (t in 100L..3900L step 100) assertTrue(said(p.next(correctRight, t)).isEmpty())
        assertEquals(listOf(Phrase.CorrectRight), said(p.next(correctRight, 4000)))
    }

    @Test
    fun steeringVibrationFollowsItsOwnRate() {
        val p = AnnouncementPolicy()
        var buzzes = 0
        for (t in 0L..2900L step 100) buzzes += p.next(correctRight, t).count { it is Output.Buzz }
        // Level 1 steers once a second.
        assertEquals(3, buzzes)
    }

    @Test
    fun centredIsConfirmedAfterACorrection() {
        val p = AnnouncementPolicy()
        p.next(correctRight, 0)
        assertEquals(listOf(Phrase.Centered), said(p.next(centred, 2000)))
        assertTrue(said(p.next(centred, 4000)).isEmpty())
    }

    @Test
    fun centredIsNotChattyWhenNothingHappened() {
        val p = AnnouncementPolicy()
        p.next(centred, 0) // first heartbeat
        for (t in 100L..19_000L step 500) assertTrue(said(p.next(centred, t)).isEmpty())
    }

    @Test
    fun repeatGivesTheLastImportantInstruction() {
        val p = AnnouncementPolicy()
        p.next(correctRight, 0)
        p.next(centred, 2000)
        assertEquals(Phrase.CorrectRight, p.repeatLast()?.phrase)
    }

    @Test
    fun everyCueThatShouldSpeakHasAPhrase() {
        for (cue in Cue.values()) {
            if (cue == Cue.None || cue == Cue.Paused) continue
            val d = Decision(SystemState.PathTracked, Severity.Info, cue, Side.Left, 1, Side.Left)
            assertTrue("$cue has no phrase", Phrase.forCue(d) != null)
        }
    }

    @Test
    fun phraseIdsAreUniqueFileNames() {
        val ids = Phrase.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("[a-z_]+")) })
    }
}
