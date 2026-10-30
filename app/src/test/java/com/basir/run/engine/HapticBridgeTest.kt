package com.basir.run.engine

import com.basir.run.vision.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import run.basir.engine.CommandKind
import run.basir.engine.Level

class HapticBridgeTest {

    private fun cmd(d: Decision) = HapticBridge.command(d)

    @Test
    fun everyCriticalDecisionIsAStop() {
        for (cue in listOf(Cue.LostStop, Cue.CameraStop, Cue.AngleStop, Cue.Obstacle, Cue.HeadLevelStop, Cue.DropStop, Cue.AtEdgeStop)) {
            assertEquals(cue.name, CommandKind.STOP, cmd(Decision(SystemState.StopAndCheck, Severity.Critical, cue, Side.Left, 3))?.kind)
        }
    }

    @Test
    fun warningsAreThreeQuickPulsesExceptAtTheEdge() {
        assertEquals(CommandKind.OBSTACLE, cmd(Decision(SystemState.ObstacleAhead, Severity.Warning, Cue.ObstacleNear))?.kind)
        assertEquals(CommandKind.OBSTACLE, cmd(Decision(SystemState.BoundariesUnclear, Severity.Warning, Cue.PathUnclear))?.kind)
        val edge = cmd(Decision(SystemState.Drifting, Severity.Warning, Cue.NearEdge, Side.Left, 2))!!
        assertEquals(CommandKind.CORRECT, edge.kind)
        assertEquals(run.basir.engine.Side.LEFT, edge.side)
        assertEquals(Level.HIGH, edge.level)
    }

    @Test
    fun correctionsAndBendsKeepSideAndLevel() {
        val c = cmd(Decision(SystemState.Drifting, Severity.Correction, Cue.Correct, Side.Right, 2))!!
        assertEquals(CommandKind.CORRECT, c.kind)
        assertEquals(run.basir.engine.Side.RIGHT, c.side)
        assertEquals(Level.MEDIUM, c.level)
        val b = cmd(Decision(SystemState.PathCurving, Severity.Info, Cue.CurveAhead, level = 1, curve = Side.Left))!!
        assertEquals(CommandKind.CURVE, b.kind)
        assertEquals(run.basir.engine.Side.LEFT, b.side)
        assertEquals(Level.LOW, b.level)
    }

    @Test
    fun silenceAndPauseSendNothing() {
        assertNull(cmd(Decision(SystemState.BoundariesUnclear, Severity.Silent, Cue.None)))
        assertNull(cmd(Decision(SystemState.Paused, Severity.Silent, Cue.Paused)))
    }

    @Test
    fun onThePathIsAQuietTick() {
        assertEquals(CommandKind.ON_PATH, cmd(Decision(SystemState.PathTracked, Severity.Info, Cue.Centered))?.kind)
    }
}
