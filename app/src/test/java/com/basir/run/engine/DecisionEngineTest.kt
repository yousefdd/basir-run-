package com.basir.run.engine

import com.basir.run.vision.FrameQuality
import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel
import com.basir.run.vision.PathEstimate
import com.basir.run.vision.PathSource
import com.basir.run.vision.PathStatus
import com.basir.run.vision.Side
import com.basir.run.vision.TrackedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import run.basir.engine.CommandKind
import run.basir.engine.GuidanceCommand
import run.basir.engine.Level
import run.basir.engine.StopReason

class DecisionEngineTest {

    private val good = FrameQuality(meanLuma = 120f, contrast = 40f, sharpness = 12f, darkFraction = 0f, glareFraction = 0f)

    private fun tracked(offset: Float, curvature: Float = 0f, stable: Boolean = true, confidence: Float = 0.9f) =
        TrackedPath(
            PathEstimate(PathStatus.Tracked, PathSource.PaintedLines, confidence = confidence, offset = offset, curvature = curvature),
            offset, curvature, stable,
        )

    private val unclear = TrackedPath(PathEstimate.unclear(PathSource.PaintedLines, 0.75f), 0f, 0f, false)

    private fun input(
        now: Long = 10_000,
        path: TrackedPath? = tracked(0f),
        quality: FrameQuality? = good,
        frameAge: Long = 50,
        obstacle: ObstacleLevel = ObstacleLevel.None,
        p90: Float = 60f,
        pitch: Float? = 40f,
        paused: Boolean = false,
    ) = EngineInput(now, paused, frameAge, quality, path, obstacle, p90, pitch)

    @Test
    fun centredOnTrackedPath() {
        val d = DecisionEngine().decide(input())
        assertEquals(SystemState.PathTracked, d.state)
        assertEquals(Cue.Centered, d.cue)
    }

    @Test
    fun driftGradesIntoCorrectionWarningAndStop() {
        val e = DecisionEngine()
        assertEquals(Cue.Correct, e.decide(input(path = tracked(0.3f))).cue)
        assertEquals(Side.Left, e.decide(input(path = tracked(0.3f))).moveTo)
        assertEquals(Cue.NearEdge, e.decide(input(path = tracked(-0.5f))).cue)
        assertEquals(Side.Right, e.decide(input(path = tracked(-0.5f))).moveTo)
        // Owner, round 5: at the edge is a strong correction toward the path, not a stop
        val edge = e.decide(input(path = tracked(0.8f)))
        assertEquals(Cue.NearEdge, edge.cue)
        assertEquals(Severity.Warning, edge.severity)
        assertEquals(Side.Left, edge.moveTo)
        val old = DecisionEngine(EngineConfig(stopWhenUncertain = true)).decide(input(path = tracked(0.8f)))
        assertEquals(SystemState.StopAndCheck, old.state)
        assertEquals(Severity.Critical, old.severity)
    }

    @Test
    fun bendIsReportedWithItsDirection() {
        val d = DecisionEngine().decide(input(path = tracked(0f, curvature = 0.3f)))
        assertEquals(SystemState.PathCurving, d.state)
        assertEquals(Side.Right, d.curve)
    }

    @Test
    fun unstablePathGivesNoDirection() {
        val d = DecisionEngine().decide(input(path = tracked(0.5f, stable = false)))
        assertNull(d.moveTo)
        assertEquals(Cue.None, d.cue)
    }

    @Test
    fun lowConfidenceGivesNoDirection() {
        val d = DecisionEngine().decide(input(path = tracked(0.5f, confidence = 0.3f)))
        assertNull(d.moveTo)
    }

    @Test
    fun unclearPathEscalatesFromSilenceToCautionToStop() {
        val e = DecisionEngine()
        assertEquals(Severity.Silent, e.decide(input(now = 0, path = unclear)).severity)
        assertEquals(Cue.PathUnclear, e.decide(input(now = 700, path = unclear)).cue)
        // Owner, round 5: a lasting unclear path stays a caution (slow down), it never stops the runner
        for (t in listOf(1600L, 5000L, 20_000L)) assertEquals(Cue.PathUnclear, e.decide(input(now = t, path = unclear)).cue)
        val old = DecisionEngine(EngineConfig(stopWhenUncertain = true))
        old.decide(input(now = 0, path = unclear))
        val stop = old.decide(input(now = 1600, path = unclear))
        assertEquals(SystemState.TrackingLost, stop.state)
        assertEquals(Cue.LostStop, stop.cue)
    }

    @Test
    fun recoveryClearsTheUnclearTimer() {
        val e = DecisionEngine()
        e.decide(input(now = 0, path = unclear))
        e.decide(input(now = 1000, path = tracked(0f)))
        assertEquals(Severity.Silent, e.decide(input(now = 1100, path = unclear)).severity)
    }

    @Test
    fun singleEdgeCloseToRunnerStops() {
        val edge = PathEstimate(PathStatus.EdgeOnly, PathSource.PaintedLines, edgeSide = Side.Left, edgeDistance = 0.05f)
        val e = DecisionEngine()
        e.decide(input(now = 0, path = TrackedPath(edge, 0f, 0f, false)))
        val d = e.decide(input(now = 700, path = TrackedPath(edge, 0f, 0f, false)))
        assertEquals(Cue.EdgeOnlyCaution, d.cue)                       // round 5: caution, not a stop
        assertNull(d.moveTo)
        val old = DecisionEngine(EngineConfig(stopWhenUncertain = true)).decide(input(path = TrackedPath(edge, 0f, 0f, false)))
        assertEquals(Cue.EdgeNearStop, old.cue)
        assertNull(old.moveTo)
    }

    @Test
    fun obstacleMeansStopWithoutASidewaysDirection() {
        // Round 5: a close object on the ground is a warning (slow down) with no side; the stop for a closed
        // way comes from the path engine after a second. Head-level obstacles and drops still stop.
        val d = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard, path = tracked(0.3f)))
        assertEquals(SystemState.ObstacleAhead, d.state)
        assertEquals(Severity.Warning, d.severity)
        assertEquals(Cue.ObstacleNear, d.cue)
        assertNull(d.moveTo)
        assertEquals(Cue.HeadLevelStop, DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard, path = tracked(0.3f)).copy(obstacleKind = HazardKind.HeadLevel)).cue)
        val drop = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard, path = tracked(0.3f)).copy(obstacleKind = HazardKind.Drop))
        assertEquals(Cue.DropStop, drop.cue)
        assertEquals(Severity.Critical, drop.severity)
        val near = DecisionEngine().decide(input(obstacle = ObstacleLevel.Possible, path = tracked(0.3f)))
        assertEquals(Cue.ObstacleNear, near.cue)
        assertNull(near.moveTo)
    }

    @Test
    fun aCloseGroundObjectWithAVerifiedWayAroundIsATurnNotAStop() {
        val detour = GuidanceCommand(CommandKind.OBSTACLE, run.basir.engine.Side.RIGHT, Level.HIGH, confidence = 0.6)
        val d = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard, path = tracked(0.3f)).copy(line = detour))
        assertEquals(Severity.Warning, d.severity)
        assertEquals(Cue.ObstacleNear, d.cue)
        assertEquals(Side.Right, d.moveTo)
        assertEquals("obstacle_right", run.basir.engine.HapticLanguage().forCommand(HapticBridge.command(d)!!)!!.name)
        // a head-level obstacle is still a stop, way around or not
        val head = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard).copy(obstacleKind = HazardKind.HeadLevel, line = detour))
        assertEquals(Severity.Critical, head.severity)
    }

    @Test
    fun headLevelAndDropHazardsHaveTheirOwnStop() {
        val head = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard).copy(obstacleKind = HazardKind.HeadLevel))
        assertEquals(Cue.HeadLevelStop, head.cue)
        assertEquals(Severity.Critical, head.severity)
        assertNull(head.moveTo)
        val drop = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard).copy(obstacleKind = HazardKind.Drop))
        assertEquals(Cue.DropStop, drop.cue)
    }

    @Test
    fun staleDepthSlowsDownWithoutSteering() {
        val d = DecisionEngine().decide(input(path = tracked(0.3f)).copy(depthStale = true))
        assertEquals(Cue.LatencySlow, d.cue)
        assertNull(d.moveTo)
    }

    private fun withLine(cmd: GuidanceCommand, path: TrackedPath? = unclear, frameAge: Long = 50, p90: Float = 60f) =
        input(path = path, frameAge = frameAge, p90 = p90).copy(line = cmd)

    @Test
    fun somethingStraightAheadStopsAndSaysWhereTheGroundGoesOn() {
        // The free path ends at a bench: stop, and the ground goes on to the left
        val turn = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.STOP, run.basir.engine.Side.LEFT, stopReason = StopReason.PATH_UNSAFE)))
        assertEquals(Cue.TurnStop, turn.cue)
        assertEquals(Severity.Critical, turn.severity)
        assertEquals(Side.Left, turn.moveTo)
        assertEquals(Phrase.TurnStopLeft, Phrase.forCue(turn))
        // No clear side: a plain stop, even when the image-space path looks fine
        val plain = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.STOP, stopReason = StopReason.PATH_UNSAFE)))
        assertEquals(Cue.Obstacle, plain.cue)
        assertEquals(Severity.Critical, plain.severity)
        // The vibration carries the side: stop, then the bend rhythm of that side
        val haptic = HapticBridge.command(turn)!!
        assertEquals(CommandKind.STOP, haptic.kind)
        assertEquals(run.basir.engine.Side.LEFT, haptic.side)
    }

    @Test
    fun theLineEngineSteersEvenWithoutAnImagePath() {
        val d = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.CORRECT, run.basir.engine.Side.LEFT, Level.MEDIUM, confidence = 0.8)))
        assertEquals(Cue.Correct, d.cue)
        assertEquals(Side.Left, d.moveTo)
        assertEquals(2, d.level)
        assertEquals(0.8f, d.confidence, 1e-6f)
        val strong = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.CORRECT, run.basir.engine.Side.RIGHT, Level.HIGH)))
        assertEquals(Cue.NearEdge, strong.cue)
        assertEquals(Severity.Warning, strong.severity)
    }

    @Test
    fun aBendFromTheLineEngineKeepsItsSideAndLevel() {
        val d = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.CURVE, run.basir.engine.Side.RIGHT, Level.HIGH, curveCurvature = 0.2, curveInM = 3.0)))
        assertEquals(Cue.CurveAhead, d.cue)
        assertEquals(Side.Right, d.curve)
        assertEquals(3, d.level)
    }

    @Test
    fun noLineFallsBackToTheImagePath() {
        val d = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.STOP, stopReason = StopReason.NO_PATH), path = tracked(0.3f)))
        assertEquals(Cue.Correct, d.cue)
        assertEquals(Side.Left, d.moveTo)
    }

    @Test
    fun anObstacleWithoutAWayAroundStops() {
        val d = DecisionEngine().decide(withLine(GuidanceCommand(CommandKind.STOP, stopReason = StopReason.OBSTACLE_NO_ALTERNATIVE)))
        assertEquals(Cue.Obstacle, d.cue)
        assertEquals(Severity.Critical, d.severity)
    }

    @Test
    fun systemChecksStillWinOverTheLineEngine() {
        val line = GuidanceCommand(CommandKind.CORRECT, run.basir.engine.Side.LEFT, Level.LOW)
        assertEquals(Cue.CameraStop, DecisionEngine().decide(withLine(line, frameAge = 2000)).cue)
        val slow = DecisionEngine().decide(withLine(line, p90 = 200f))
        assertEquals(Cue.LatencySlow, slow.cue)
        assertNull(slow.moveTo)
    }

    @Test
    fun cameraStallStops() {
        val d = DecisionEngine().decide(input(frameAge = 2000))
        assertEquals(Cue.CameraStop, d.cue)
        assertEquals(Severity.Critical, d.severity)
    }

    @Test
    fun blockedLensStops() {
        val blocked = good.copy(contrast = 3f)
        assertEquals(SystemState.CameraBlocked, DecisionEngine().decide(input(quality = blocked)).state)
    }

    @Test
    fun noAngleStopAnyMore() {
        // The owner asked to remove the angle check and its "recalibrate" request: an odd tilt does
        // not stop guidance; only the path engine decides whether the ground is seen.
        val e = DecisionEngine()
        assertEquals(Cue.Centered, e.decide(input(now = 0, pitch = 2f)).cue)
        assertEquals(Cue.Centered, e.decide(input(now = 5000, pitch = 2f)).cue)
        assertEquals(Cue.Centered, e.decide(input(now = 9000, pitch = 85f)).cue)
    }

    @Test
    fun tiltSwingsOfAStrideDoNotStopGuidance() {
        // The phone swings between 8° and 30° below the horizon while running: never a stop.
        val e = DecisionEngine()
        for (t in 0L..4000L step 100L) {
            val swing = if ((t / 300) % 2 == 0L) 8f else 30f
            assertEquals(Cue.Centered, e.decide(EngineInput(t, false, 50, good, tracked(0f), cameraPitchDeg = swing)).cue)
        }
    }

    @Test
    fun slowProcessingWarnsThenStops() {
        val e = DecisionEngine()
        assertEquals(Cue.LatencySlow, e.decide(input(p90 = 200f)).cue)
        assertEquals(Cue.LatencyStop, e.decide(input(p90 = 400f)).cue)
    }

    @Test
    fun poorVisibilityWithLostPathStops() {
        val dark = good.copy(meanLuma = 20f, darkFraction = 0.9f)
        val e = DecisionEngine()
        e.decide(input(now = 0, path = unclear, quality = dark))
        assertEquals(Cue.PathUnclear, e.decide(input(now = 700, path = unclear, quality = dark)).cue)   // round 5: caution
        val old = DecisionEngine(EngineConfig(stopWhenUncertain = true))
        old.decide(input(now = 0, path = unclear, quality = dark))
        assertEquals(Cue.VisibilityStop, old.decide(input(now = 700, path = unclear, quality = dark)).cue)
    }

    @Test
    fun pausedIsSilent() {
        val d = DecisionEngine().decide(input(paused = true, obstacle = ObstacleLevel.Hazard))
        assertEquals(SystemState.Paused, d.state)
        assertEquals(Severity.Silent, d.severity)
    }
}
