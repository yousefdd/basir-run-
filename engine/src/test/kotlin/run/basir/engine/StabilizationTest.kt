package run.basir.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun line(c0: Double, c1: Double = 0.0, c2: Double = 0.0, near: Double = 0.5, far: Double = 8.0, rms: Double = 0.03) =
    PathCurve(doubleArrayOf(c0, c1, c2), near, far, rms)

class TemporalPathTrackerTest {
    @Test
    fun confirmsAfterConsistentMeasurementsAndHoldsAgainstOneStrayFrame() {
        val t = TemporalPathTracker()
        var ms = 0L
        repeat(3) { t.update(line(0.1), ms, null); ms += 66 }
        assertEquals(TrackState.TRACKING, t.state)
        t.update(line(1.5), ms, null); ms += 66 // one stray measurement 1.4 m away
        assertTrue(abs(t.curve!!.lateralAt(2.0) - 0.1) < 0.05, "a single stray frame moved the path")
        t.update(line(0.1), ms, null)
        assertEquals(TrackState.TRACKING, t.state)
    }

    @Test
    fun aPersistentDifferentPathReplacesTheTrack() {
        val t = TemporalPathTracker()
        var ms = 0L
        repeat(5) { t.update(line(0.0), ms, null); ms += 66 }
        repeat(8) { t.update(line(1.2), ms, null); ms += 66 } // ~0.5 s of a consistent other path (a fork taken)
        assertTrue(abs(t.curve!!.lateralAt(2.0) - 1.2) < 0.1, "a confirmed real change was not followed")
        assertEquals(1, t.switches)
    }

    @Test
    fun coastsWithDecayingConfidenceThenDrops() {
        val t = TemporalPathTracker()
        var ms = 0L
        repeat(4) { t.update(line(0.2), ms, null); ms += 66 }
        t.update(null, ms, null); ms += 200
        val c1 = t.confidence
        t.update(null, ms, null); ms += 300
        val c2 = t.confidence
        assertEquals(TrackState.COASTING, t.state)
        assertTrue(c2 < c1 && c1 < 1.0, "confidence did not decay: $c1 -> $c2")
        t.update(null, ms + 600, null) // more than 1 s without evidence
        assertEquals(TrackState.LOST, t.state)
        assertNull(t.curve, "a path without evidence for over a second is still offered")
    }

    @Test
    fun egoMotionKeepsAGroundPathFixedOnTheGround() {
        val t = TemporalPathTracker()
        var ms = 0L
        repeat(4) { t.update(line(0.0), ms, null); ms += 66 }
        // the runner turns right by 10 degrees without new evidence: the straight path should now appear to the left
        t.update(null, ms, EgoMotion(0.1, 0.0, Math.toRadians(10.0)))
        val lat = t.curve!!.lateralAt(3.0)
        assertTrue(lat < -0.4, "path not rotated by the turn: $lat")
    }

    @Test
    fun timeBasedNotFrameBased() {
        // the same 1.2 s gap without evidence drops the track whether it is 3 or 18 frames
        for (frames in listOf(3, 18)) {
            val t = TemporalPathTracker()
            var ms = 0L
            repeat(4) { t.update(line(0.0), ms, null); ms += 66 }
            val step = 1200L / frames
            repeat(frames) { ms += step; t.update(null, ms, null) }
            assertEquals(TrackState.LOST, t.state, "$frames frames")
        }
    }

    @Test
    fun noisyMeasurementsAreSmoothed() {
        val rnd = Random(3)
        val t = TemporalPathTracker()
        var ms = 0L
        val out = ArrayList<Double>()
        repeat(60) { t.update(line(0.3 + rnd.nextDouble(-0.1, 0.1)), ms, null); ms += 66; out += t.curve!!.lateralAt(2.0) }
        val jitter = out.zipWithNext { a, b -> abs(a - b) }.drop(10).average()
        assertTrue(jitter < 0.04, "frame-to-frame jitter $jitter m for +-0.1 m noise")
    }

    @Test
    fun plannerUsesTheTemporalTrackerByDefaultAndTheLegacyOneOnRequest() {
        assertNotNull(IntelligentPathPlanner().temporal)
        assertNull(IntelligentPathPlanner(PlannerConfig(temporalTracking = false)).temporal)
    }
}

class PathStabilityControllerTest {
    @Test
    fun smallNoiseIsDampedStrongly() {
        val rnd = Random(5)
        val s = PathStabilityController()
        var ms = 0L
        val out = ArrayList<Double>()
        repeat(90) { out += s.update(line(0.2 + rnd.nextDouble(-0.03, 0.03)), ms, null, null)!!.curve.lateralAt(2.0); ms += 66 }
        val jitter = out.zipWithNext { a, b -> abs(a - b) }.drop(5).average()
        assertTrue(jitter < 0.01, "jitter $jitter m for +-3 cm noise")
    }

    @Test
    fun aRealStepIsFollowedWithinHalfASecond() {
        val s = PathStabilityController()
        var ms = 0L
        repeat(10) { s.update(line(0.0), ms, null, null); ms += 66 }
        var reached: Long? = null
        val t0 = ms
        repeat(30) {
            val v = s.update(line(0.5), ms, null, null)!!.curve.lateralAt(1.5)
            if (reached == null && v >= 0.45) reached = ms - t0
            ms += 66
        }
        assertNotNull(reached, "never reached 90 % of a 0.5 m step")
        assertTrue(reached!! <= 500, "took $reached ms")
    }

    @Test
    fun aBendKeepsItsCurvatureWithoutLag() {
        val s = PathStabilityController()
        var ms = 0L
        repeat(10) { s.update(line(0.0), ms, null, null); ms += 66 }
        var k = 0.0
        repeat(8) { k = s.update(line(0.0, 0.0, 0.06), ms, null, null)!!.curve.curvatureAt(3.0); ms += 66 } // 0.5 s into a bend
        assertTrue(k > 0.09, "bend curvature $k after 0.5 s (true 0.12)")
    }

    @Test
    fun movesNoFasterThanThePhysicalBound() {
        val s = PathStabilityController()
        s.update(line(0.0), 0L, null, null)
        val v = s.update(line(3.0), 66L, null, null)!!.curve.lateralAt(0.5)
        assertTrue(v <= (1.5 + 0.5 * 1.0) * 0.066 + 0.02, "moved $v m in one frame")
    }

    @Test
    fun widthIsStableAndNoPathMeansNothingDrawn() {
        val s = PathStabilityController()
        var ms = 0L
        var hw = 0.0
        repeat(20) { i -> hw = s.update(line(0.0), ms, null, if (i % 2 == 0) 1.0 else 1.6)!!.halfWidthM!!; ms += 66 }
        assertTrue(hw in 1.1..1.5, "width $hw")
        assertNull(s.update(null, ms, null, 1.0))
        assertFalse(s.update(line(0.0), ms + 66, null, null) == null)
    }
}

class AdaptiveFrameControllerTest {
    private fun ctx(t: Long, state: TrackState = TrackState.TRACKING, conf: Double = 1.0, kappa: Double = 0.0, yaw: Double = 0.0,
                    obstacle: Double? = null, people: Boolean = false, now: Long = t, newer: Boolean = false, speed: Double = 1.4) =
        AdaptiveFrameController.Context(t, now, newer, state, conf, kappa, yaw, speed, obstacle, people)

    private fun keyframes(c: AdaptiveFrameController, make: (Long) -> AdaptiveFrameController.Context): Int {
        var n = 0
        for (i in 0 until 30) if (c.decide(make(i * 66L)).keyframe) n++
        return n
    }

    @Test
    fun steadyStraightWayRunsTheHeavyModelLessOften() {
        val n = keyframes(AdaptiveFrameController()) { ctx(it) }
        assertTrue(n in 4..10, "$n keyframes in 2 s on a steady straight way")
    }

    @Test
    fun uncertainTrackingRunsEveryFrame() {
        assertEquals(30, keyframes(AdaptiveFrameController()) { ctx(it, state = TrackState.COASTING, conf = 0.5) })
    }

    @Test
    fun bendsObstaclesAndPeopleRaiseTheRate() {
        val steady = keyframes(AdaptiveFrameController()) { ctx(it) }
        for (make in listOf<(Long) -> AdaptiveFrameController.Context>({ ctx(it, kappa = 0.08) }, { ctx(it, obstacle = 4.0) }, { ctx(it, people = true) })) {
            assertTrue(keyframes(AdaptiveFrameController(), make) > steady)
        }
    }

    @Test
    fun fastTurningRunsEveryFrame() {
        assertEquals(30, keyframes(AdaptiveFrameController()) { ctx(it, yaw = 1.0) })
    }

    @Test
    fun staleFramesAreDroppedWhenANewerOneWaits() {
        val c = AdaptiveFrameController()
        assertFalse(c.decide(ctx(0, now = 300, newer = true)).process)
        assertTrue(c.decide(ctx(0, now = 300, newer = false)).process, "the only frame must still be used")
    }

    @Test
    fun safetyIsNeverSkippedAndTimestampsNotFrameCountsDecide() {
        val c = AdaptiveFrameController()
        val ds = (0 until 20).map { c.decide(ctx(it * 33L)) }
        assertTrue(ds.all { it.runSafety })
        // irregular frame times: the interval is respected in milliseconds
        val c2 = AdaptiveFrameController()
        val times = listOf(0L, 40, 90, 200, 210, 260, 330, 470, 520, 600)
        val keys = times.filter { c2.decide(ctx(it)).keyframe }
        for ((a, b) in keys.zipWithNext()) assertTrue(b - a >= 250 * 0.7 - 1, "keyframes $a and $b too close")
    }
}

class TemporalTrackerResponseTest {
    @Test
    fun aModerateRealShiftIsFollowedWithinHalfASecond() {
        val t = TemporalPathTracker()
        var ms = 0L
        repeat(10) { t.update(line(0.0), ms, null); ms += 66 }
        val t0 = ms
        var reached: Long? = null
        repeat(20) { if (reached == null && t.curve!!.lateralAt(2.0) >= 0.24) reached = ms - t0; t.update(line(0.3), ms, null); ms += 66 }
        assertNotNull(reached); assertTrue(reached!! <= 500, "0.3 m shift took $reached ms")
    }
}

class GuidanceTemporalStabilizerTest {
    private val onPath = GuidanceCommand(CommandKind.ON_PATH)
    private val obstacle = GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH)
    private val stop = GuidanceCommand(CommandKind.STOP)
    private fun correct(side: Side, level: Level = Level.LOW) = GuidanceCommand(CommandKind.CORRECT, side, level)

    @Test
    fun warningsStartAndEndAtOnceButNoGoBuzzRightAfter() {
        val g = GuidanceTemporalStabilizer()
        assertEquals(CommandKind.ON_PATH, g.update(onPath, 0)!!.kind)
        assertEquals(CommandKind.OBSTACLE, g.update(obstacle, 66)!!.kind, "a warning must not wait")
        assertNull(g.update(onPath, 132), "right after a warning the on-path tick must stay quiet (and the warning is not held)")
        assertEquals(CommandKind.ON_PATH, g.update(onPath, 1800)!!.kind, "on-path cue did not come back after the quiet window")
    }

    @Test
    fun aConfiguredHoldKeepsTheWarningUntilTheCalmLasts() {
        val g = GuidanceTemporalStabilizer(GuidanceStabilizerConfig(obstacleReleaseS = 0.8))
        g.update(onPath, 0)
        assertEquals(CommandKind.OBSTACLE, g.update(obstacle, 66)!!.kind)
        assertEquals(CommandKind.OBSTACLE, g.update(onPath, 132)!!.kind, "one clear frame ended a held warning")
        assertEquals(CommandKind.OBSTACLE, g.update(onPath, 600)!!.kind)
        assertNull(g.update(onPath, 1000), "released after 0.87 s of calm (quiet tick)")
    }

    @Test
    fun stopPassesBothWaysImmediately() {
        val g = GuidanceTemporalStabilizer()
        g.update(obstacle, 0)
        assertEquals(CommandKind.STOP, g.update(stop, 66)!!.kind)
        assertEquals(CommandKind.ON_PATH, g.update(onPath, 132)?.kind, "STOP release is the planner's and scheduler's job")
    }

    @Test
    fun flickeringWarningsGiveNoOnPathTicksInBetween() {
        val g = GuidanceTemporalStabilizer()
        val out = (0 until 30).map { i -> g.update(if (i % 2 == 0) obstacle else onPath, i * 66L)?.kind }
        assertTrue(out.none { it == CommandKind.ON_PATH }, "on-path ticks between flickering warnings: $out")
        assertTrue(out.filterIndexed { i, _ -> i % 2 == 0 }.all { it == CommandKind.OBSTACLE }, "a warning frame was not passed")
    }

    @Test
    fun directionPersistsUnlessLeavingThePath() {
        val g = GuidanceTemporalStabilizer()
        g.update(correct(Side.LEFT), 0)
        assertEquals(Side.LEFT, g.update(correct(Side.RIGHT), 300)!!.side, "flipped side after 0.3 s")
        assertEquals(Side.RIGHT, g.update(correct(Side.RIGHT), 1400)!!.side, "did not flip after the opposite side held 1.1 s")
        assertEquals(Side.LEFT, g.update(correct(Side.LEFT, Level.HIGH), 1466)!!.side, "a strong correction must pass at once")
    }

    /** Round 7 regression: right, then "on path", then left used to flip the side in ~0.5 s (escalation over on-path). */
    @Test
    fun noSideFlipThroughAnOnPathCueInBetween() {
        val g = GuidanceTemporalStabilizer()
        assertEquals(Side.RIGHT, g.update(correct(Side.RIGHT), 0)!!.side)
        var t = 66L
        while (t < 600) { g.update(onPath, t); t += 66 }           // on-path accepted after calmChangeS
        val early = g.update(correct(Side.LEFT), 660)
        assertTrue(early?.side != Side.LEFT, "left given 0.66 s after right, through an on-path cue: $early")
        t = 726
        var firstLeft: Long? = null
        while (t <= 2000) { if (g.update(correct(Side.LEFT), t)?.side == Side.LEFT && firstLeft == null) firstLeft = t; t += 66 }
        assertNotNull(firstLeft, "the opposite side must come through once it persists")
        assertTrue(firstLeft!! - 660 >= 1000 - 1, "left accepted after only ${firstLeft!! - 660} ms of persistence")
        // obstacles and STOP are never delayed by the side memory
        val g2 = GuidanceTemporalStabilizer()
        g2.update(correct(Side.RIGHT), 0)
        assertEquals(CommandKind.OBSTACLE, g2.update(GuidanceCommand(CommandKind.OBSTACLE, Side.LEFT, Level.HIGH), 100)!!.kind)
        assertEquals(CommandKind.STOP, g2.update(stop, 166)!!.kind)
    }
}

class VisualYawEstimatorTest {
    /** A synthetic scene: random vertical stripes in the upper half, shifted by [shift] px. */
    private fun scene(shift: Int, w: Int = 240, h: Int = 320): RgbImage {
        val rnd = kotlin.random.Random(9)
        val stripes = IntArray(w + 80) { rnd.nextInt(40, 220) }
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (y < h / 2) stripes[(x - shift + 40).coerceIn(0, w + 79)] else 120
            r[y * w + x] = v; g[y * w + x] = v; b[y * w + x] = v
        }
        return RgbImage(w, h, r, g, b)
    }

    @Test
    fun aSceneMovingLeftMeansTurningRight() {
        val cam = CameraModel(240, 320, 60.0, 1.3, 15.0)
        val e = VisualYawEstimator()
        e.update(scene(0), cam)
        val yaw = assertNotNull(e.update(scene(-6), cam))
        val expected = kotlin.math.atan(6.0 / cam.focalPx)
        assertTrue(yaw > 0 && abs(yaw - expected) < 0.2 * expected, "yaw $yaw, expected $expected")
    }

    @Test
    fun noShiftNoTurnAndFlatSceneGivesNothing() {
        val cam = CameraModel(240, 320, 60.0, 1.3, 15.0)
        val e = VisualYawEstimator()
        e.update(scene(0), cam)
        assertTrue(abs(e.update(scene(0), cam)!!) < 1e-3)
        val flat = RgbImage(240, 320, IntArray(240 * 320) { 128 }, IntArray(240 * 320) { 128 }, IntArray(240 * 320) { 128 })
        val e2 = VisualYawEstimator(); e2.update(flat, cam)
        assertNull(e2.update(flat, cam))
    }
}

/** Round 7: a new track close to the drawn path is reached within a few frames instead of in one jump. */
class PathHandoverTest {
    private fun straight(l: Double) = PathCurve(doubleArrayOf(l, 0.0, 0.0), 0.5, 8.0)

    @Test
    fun aNearbyNewTrackIsReachedQuicklyButNotInOneFrame() {
        val c = PathStabilityController(StabilityConfig(handoverMaxGapM = 1.0))
        var t = 0L
        repeat(10) { c.update(straight(0.0), t, null, null); t += 66 }
        val first = c.update(straight(0.4), t, null, null, newTrack = true)!!.curve.lateralAt(3.0)
        assertTrue(first < 0.39, "jumped in one frame: $first")
        assertTrue(first > 0.1, "followed too slowly: $first")
        var reached = 1
        var v = first
        // within the controller's noise deadband (6 cm), after which it settles slowly by design
        while (v < 0.34 && reached < 10) { t += 66; v = c.update(straight(0.4), t, null, null, newTrack = false)!!.curve.lateralAt(3.0); reached++ }
        assertTrue(reached <= 3, "took $reached frames to come within 6 cm of a 0.4 m handover")
    }

    @Test
    fun anotherWayFarAwayIsDrawnAtOnceAndTheSwitchOffKeeps0130() {
        val c = PathStabilityController(StabilityConfig(handoverMaxGapM = 1.0))
        var t = 0L
        repeat(10) { c.update(straight(0.0), t, null, null); t += 66 }
        assertEquals(2.0, c.update(straight(2.0), t, null, null, newTrack = true)!!.curve.lateralAt(3.0), 1e-6)
        val old = PathStabilityController(StabilityConfig(handoverMaxGapM = 0.0))
        t = 0L
        repeat(10) { old.update(straight(0.0), t, null, null); t += 66 }
        assertEquals(0.4, old.update(straight(0.4), t, null, null, newTrack = true)!!.curve.lateralAt(3.0), 1e-6)
    }
}

