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
        val edge = e.decide(input(path = tracked(0.8f)))
        assertEquals(SystemState.StopAndCheck, edge.state)
        assertEquals(Severity.Critical, edge.severity)
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
        val stop = e.decide(input(now = 1600, path = unclear))
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
        val d = DecisionEngine().decide(input(path = TrackedPath(edge, 0f, 0f, false)))
        assertEquals(Cue.EdgeNearStop, d.cue)
        assertNull(d.moveTo)
    }

    @Test
    fun obstacleMeansStopWithoutASidewaysDirection() {
        val d = DecisionEngine().decide(input(obstacle = ObstacleLevel.Hazard, path = tracked(0.3f)))
        assertEquals(SystemState.ObstacleAhead, d.state)
        assertEquals(Severity.Critical, d.severity)
        assertNull(d.moveTo)
        val near = DecisionEngine().decide(input(obstacle = ObstacleLevel.Possible, path = tracked(0.3f)))
        assertEquals(Cue.ObstacleNear, near.cue)
        assertNull(near.moveTo)
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
    fun badAngleStopsOnlyAfterGrace() {
        val e = DecisionEngine()
        assertEquals(Cue.Centered, e.decide(input(now = 0, pitch = 5f)).cue)
        assertEquals(Cue.AngleStop, e.decide(input(now = 1200, pitch = 5f)).cue)
    }

    @Test
    fun angleIsCheckedAgainstTheCalibration() {
        val e = DecisionEngine()
        // 50 degrees is inside the absolute range but 20 away from the calibrated 30.
        val base = EngineInput(0, false, 50, good, tracked(0f), cameraPitchDeg = 50f, calibratedPitchDeg = 30f)
        assertEquals(Cue.Centered, e.decide(base).cue)
        assertEquals(Cue.AngleStop, e.decide(base.copy(nowMs = 1200)).cue)
        assertEquals(Cue.Centered, DecisionEngine().decide(base.copy(cameraPitchDeg = 38f, nowMs = 5000)).cue)
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
        assertEquals(Cue.VisibilityStop, e.decide(input(now = 700, path = unclear, quality = dark)).cue)
    }

    @Test
    fun pausedIsSilent() {
        val d = DecisionEngine().decide(input(paused = true, obstacle = ObstacleLevel.Hazard))
        assertEquals(SystemState.Paused, d.state)
        assertEquals(Severity.Silent, d.severity)
    }
}
