package run.basir.replay

import com.basir.run.engine.Cue
import com.basir.run.engine.DecisionEngine
import com.basir.run.engine.EngineConfig
import com.basir.run.engine.EngineInput
import com.basir.run.engine.HapticBridge
import com.basir.run.engine.Severity
import com.basir.run.vision.FrameQuality
import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel
import run.basir.engine.CommandKind
import run.basir.engine.CornerPhase
import run.basir.engine.CornerState
import run.basir.engine.GuidanceCommand
import run.basir.engine.GuidanceMode
import run.basir.engine.Level
import run.basir.engine.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Round 8 stop audit and corner priority in the decision layer (app_decision/DecisionEngine.kt). */
class DecisionRound8Test {
    private val good = FrameQuality(120f, 40f, 10f, 0.05f, 0.01f)
    private val onPath = GuidanceCommand(CommandKind.ON_PATH, confidence = 0.9)
    private fun input(now: Long, age: Long = 66, p90: Float = 80f, line: GuidanceCommand? = onPath, corner: CornerState? = null,
                      obstacle: ObstacleLevel = ObstacleLevel.None, kind: HazardKind = HazardKind.Object) =
        EngineInput(nowMs = now, frameAgeMs = age, quality = good, path = null, obstacle = obstacle, processingP90Ms = p90,
            obstacleKind = kind, line = line, corner = corner)

    @Test
    fun aLateFrameIsACautionNotAStop() {
        val e = DecisionEngine()
        val d = e.decide(input(1000, age = 900))
        assertNotEquals(Severity.Critical, d.severity)
        assertEquals(Cue.PathUnclear, d.cue)
        assertEquals(null, d.moveTo, "a direction was given from stale data")
        assertEquals(GuidanceMode.GUIDANCE_CAUTION, d.mode)
    }

    @Test
    fun aFrozenCameraIsStillAStop() {
        val d = DecisionEngine().decide(input(1000, age = 1600))
        assertEquals(Severity.Critical, d.severity)
        assertEquals(GuidanceMode.EMERGENCY_STOP, d.mode)
    }

    @Test
    fun oneSlowSpikeIsAWarningAndOnlyLastingSlownessStops() {
        val e = DecisionEngine()
        assertEquals(Cue.LatencySlow, e.decide(input(1000, p90 = 400f)).cue)
        assertEquals(Cue.LatencySlow, e.decide(input(2500, p90 = 400f)).cue)   // 1.5 s slow: still no stop
        assertEquals(Cue.LatencyStop, e.decide(input(4000, p90 = 400f)).cue)   // 3 s: stalled, stop
        // recovered: the timer starts again
        e.decide(input(4100, p90 = 90f))
        assertEquals(Cue.LatencySlow, e.decide(input(4200, p90 = 400f)).cue)
        // slow processing is felt as "slow down" (caution), not as an obstacle
        val b = com.basir.run.engine.DecisionStabilityHapticBridge()
        b.command(e.decide(input(5000, p90 = 400f)), 5000); val c = b.command(e.decide(input(5066, p90 = 400f)), 5066)
        assertEquals(CommandKind.CAUTION, c?.kind)
    }

    @Test
    fun the014RulesCanBeRestoredForComparison() {
        val e = DecisionEngine(EngineConfig(frozenFrameStopMs = 700, latencyStopHoldMs = 0))
        assertEquals(Severity.Critical, e.decide(input(1000, age = 900)).severity)
        assertEquals(Cue.LatencyStop, DecisionEngine(EngineConfig(frozenFrameStopMs = 700, latencyStopHoldMs = 0)).decide(input(1000, p90 = 400f)).cue)
    }

    @Test
    fun headLevelAndDropWarnAtOnceAndStopWhenConfirmed() {
        for (kind in listOf(HazardKind.HeadLevel, HazardKind.Drop)) {
            val e = DecisionEngine()
            val first = e.decide(input(1000, obstacle = ObstacleLevel.Hazard, kind = kind))
            assertEquals(Cue.ObstacleNear, first.cue, "no immediate warning")          // warned on the first frame
            for (k in 1..3) assertEquals(Cue.ObstacleNear, e.decide(input(1000 + k * 66L, obstacle = ObstacleLevel.Hazard, kind = kind)).cue)
            val fifth = e.decide(input(1264, obstacle = ObstacleLevel.Hazard, kind = kind))
            assertEquals(Severity.Critical, fifth.severity, "a lasting $kind did not stop")
            // a blip of a fraction of a second (2 frames) never becomes a stop
            val b = DecisionEngine()
            b.decide(input(1000, obstacle = ObstacleLevel.Hazard, kind = kind))
            assertNotEquals(Severity.Critical, b.decide(input(1066, obstacle = ObstacleLevel.Hazard, kind = kind)).severity)
            assertNotEquals(Severity.Critical, b.decide(input(1133)).severity)
            assertNotEquals(Severity.Critical, b.decide(input(1200, obstacle = ObstacleLevel.Hazard, kind = kind)).severity)
        }
        // the 0.14.0 rule (immediate stop) can be restored
        assertEquals(Cue.HeadLevelStop, DecisionEngine(EngineConfig(criticalConfirmMs = 0)).decide(input(1000, obstacle = ObstacleLevel.Hazard, kind = HazardKind.HeadLevel)).cue)
    }

    @Test
    fun aCornerIsOneClearCueAndNeverReplacesAnObstacleWarning() {
        val corner = CornerState(CornerPhase.PREPARE_TURN, Side.RIGHT, 3.0, confidence = 0.8)
        val d = DecisionEngine().decide(input(1000, line = GuidanceCommand(CommandKind.CORRECT, Side.RIGHT, Level.LOW, confidence = 0.9), corner = corner))
        assertEquals(Cue.CurveAhead, d.cue)
        assertEquals(CornerPhase.PREPARE_TURN, d.corner)
        assertEquals(CornerPhase.PREPARE_TURN, HapticBridge.command(d)?.corner)
        // an obstacle warning from the path engine outranks the corner
        val o = DecisionEngine().decide(input(1000, line = GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH, confidence = 0.9), corner = corner))
        assertEquals(Cue.ObstacleNear, o.cue)
        // a strong correction to the other side (about to leave the path) outranks it too
        val c = DecisionEngine().decide(input(1000, line = GuidanceCommand(CommandKind.CORRECT, Side.LEFT, Level.HIGH, confidence = 0.9), corner = corner))
        assertEquals(Cue.NearEdge, c.cue)
    }

    @Test
    fun aWarningIsCloseWhenAnySourceSeesItClose() {
        // depth says "possible", the path engine already has the way closed within stopping distance: a close warning
        val d = DecisionEngine().decide(input(1000, obstacle = ObstacleLevel.Possible,
            line = GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH, confidence = 0.9)))
        assertEquals(Level.HIGH, HapticBridge.command(d)?.level)
        // depth "possible" alone stays a possible (voted, cooled-down) warning
        assertEquals(null, HapticBridge.command(DecisionEngine().decide(input(1000, obstacle = ObstacleLevel.Possible)))?.level)
    }

    @Test
    fun slowProcessingNeverHidesAnObstacleWarning() {
        val e = DecisionEngine()
        assertEquals(Cue.ObstacleNear, e.decide(input(1000, p90 = 400f, obstacle = ObstacleLevel.Possible)).cue)
        assertEquals(Cue.ObstacleNear, e.decide(input(1066, p90 = 400f, line = GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH, confidence = 0.9))).cue)
        assertEquals(Cue.LatencySlow, e.decide(input(1133, p90 = 400f)).cue)
    }

    @Test
    fun lowGrassBesideThePathIsNoObstacleButLowThingsInTheFeetsPathAre() {
        val cfg = com.basir.run.vision.depth.DepthHazards.RUNNING
        val straight = run.basir.engine.PathCurve(doubleArrayOf(0.0, 0.0, 0.0), 0.0, 6.0)
        fun pts(lateral: Float, h: Float) = FloatArray(30 * 3) { k -> when (k % 3) { 0 -> 1.5f + (k / 3) * 0.01f; 1 -> lateral; else -> h } }
        val grassAtEdge = pts(0.55f, 0.16f)          // weeds 16 cm high, 0.55 m beside the centre line
        val kerbInPath = pts(0.10f, 0.16f)           // the same height right in front of the feet
        val armHeightAtEdge = pts(0.55f, 0.9f)       // something at arm height beside the path
        fun free(p: FloatArray) = com.basir.run.vision.depth.DepthHazards.alongPath(p, straight, cfg.bodyWidthM / 2, cfg, null, 8,
            lowHalfWidthM = com.basir.run.vision.depth.DepthHazards.FOOT_HALF_WIDTH_M)
        assertEquals(cfg.lookaheadM, free(grassAtEdge), "grass beside the path counted as an obstacle")
        assertTrue(free(kerbInPath) < 2.0, "a low thing in the feet's path was missed")
        assertTrue(free(armHeightAtEdge) < 2.0, "something at arm height beside the path was missed")
    }

    @Test
    fun anUnverifiedTurnGivesNoDirection() {
        val d = DecisionEngine().decide(input(1000, line = GuidanceCommand(CommandKind.CAUTION, confidence = 0.2),
            corner = CornerState(CornerPhase.NONE, Side.RIGHT, exitUnverified = true)))
        assertEquals(Cue.PathUnclear, d.cue)
        assertEquals(null, d.moveTo)
    }
}
