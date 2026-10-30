package com.basir.run.engine

import com.basir.run.vision.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPolicyTest {

    private val centred = Decision(SystemState.PathTracked, Severity.Info, Cue.Centered)
    private val slightRight = Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, Side.Right, 1)
    private val slightLeft = Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, Side.Left, 1)
    private val nearEdgeLeft = Decision(SystemState.Drifting, Severity.Warning, Cue.NearEdge, Side.Left, 2)
    private val lost = Decision(SystemState.TrackingLost, Severity.Critical, Cue.LostStop)
    private val unclear = Decision(SystemState.BoundariesUnclear, Severity.Warning, Cue.PathUnclear)

    private fun said(out: List<Output>) = out.filterIsInstance<Output.Say>().map { it.phrase }
    private fun buzzes(out: List<Output>) = out.filterIsInstance<Output.Buzz>().map { it.haptic }

    @Test
    fun stopInterruptsWithTheLongVibrationAndRepeats() {
        val p = AnnouncementPolicy()
        val first = p.next(lost, 0)
        assertEquals(listOf(Phrase.CannotVerifyStop), said(first))
        assertTrue((first.first() as Output.Say).interrupt)
        assertEquals(listOf(Haptic.Stop), buzzes(first))
        assertTrue(said(p.next(lost, 1000)).isEmpty())
        assertEquals(listOf(Phrase.CannotVerifyStop), said(p.next(lost, 3100)))
    }

    @Test
    fun obstacleUsesThreeQuickPulsesAndWords() {
        val out = AnnouncementPolicy().next(Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.Obstacle), 0)
        assertEquals(listOf(Haptic.Attention), buzzes(out))
        assertEquals(listOf(Phrase.Obstacle), said(out))
    }

    @Test
    fun headLevelAndDropAreSpokenWithThreeQuickPulses() {
        val head = AnnouncementPolicy().next(Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.HeadLevelStop), 0)
        assertEquals(listOf(Haptic.Attention), buzzes(head))
        assertEquals(listOf(Phrase.HeadLevel), said(head))
        val drop = AnnouncementPolicy().next(Decision(SystemState.ObstacleAhead, Severity.Critical, Cue.DropStop), 0)
        assertEquals(listOf(Phrase.DropAhead), said(drop))
    }

    @Test
    fun unclearPathIsSpokenWithThreeQuickPulses() {
        val out = AnnouncementPolicy().next(unclear, 0)
        assertEquals(listOf(Phrase.PathUnclear), said(out))
        assertEquals(listOf(Haptic.Attention), buzzes(out))
    }

    @Test
    fun steeringIsSilentByDefaultAndUsesOneOrTwoPulses() {
        val p = AnnouncementPolicy()
        val right = p.next(slightRight, 0)
        assertTrue(said(right).isEmpty())
        assertEquals(listOf(Haptic.SteerRight), buzzes(right))
        val left = AnnouncementPolicy().next(slightLeft, 0)
        assertEquals(listOf(Haptic.SteerLeft), buzzes(left))
    }

    @Test
    fun steeringRateFollowsTheDrift() {
        val slight = AnnouncementPolicy()
        val near = AnnouncementPolicy()
        var a = 0
        var b = 0
        for (t in 0L..2900L step 100) {
            a += buzzes(slight.next(slightRight, t)).size
            b += buzzes(near.next(nearEdgeLeft, t)).size
        }
        assertEquals(3, a) // every 1.2 s
        assertEquals(5, b) // every 0.6 s
        assertTrue("larger drift is not spoken by default", said(near.next(nearEdgeLeft, 5000)).isEmpty())
    }

    @Test
    fun directionDoesNotFlipWithinAMoment() {
        val p = AnnouncementPolicy()
        p.next(slightRight, 0)
        assertTrue(buzzes(p.next(slightLeft, 600)).isEmpty())
        assertEquals(listOf(Haptic.SteerLeft), buzzes(p.next(slightLeft, 1300)))
    }

    @Test
    fun noSteeringDuringAStop() {
        val edgeStop = AnnouncementPolicy().next(Decision(SystemState.StopAndCheck, Severity.Critical, Cue.AtEdgeStop, Side.Left, 3), 0)
        assertEquals(listOf(Haptic.Stop), buzzes(edgeStop))
        assertTrue(edgeStop.none { it is Output.Tone })
    }

    @Test
    fun bendGetsTheSwellOnce() {
        val p = AnnouncementPolicy()
        val bend = Decision(SystemState.PathCurving, Severity.Info, Cue.CurveAhead, curve = Side.Right)
        assertEquals(listOf(Haptic.BendAhead), buzzes(p.next(bend, 0)))
        assertTrue(buzzes(p.next(bend, 500)).isEmpty())
        assertTrue(said(p.next(bend, 600)).isEmpty())
    }

    @Test
    fun verboseModeAddsWords() {
        val p = AnnouncementPolicy(verbose = true)
        assertEquals(listOf(Phrase.CorrectRight), said(p.next(slightRight, 0)))
        assertEquals(listOf(Phrase.Centered), said(p.next(centred, 2000)))
    }

    @Test
    fun repeatGivesTheLastImportantInstruction() {
        val p = AnnouncementPolicy()
        p.next(slightRight, 0)
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
