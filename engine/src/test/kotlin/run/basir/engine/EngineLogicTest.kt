package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decision logic: tracking, planner safety rules, guidance hysteresis, haptic priorities and
 * calibration. Inputs here are small hand-made paths/obstacles that exercise one rule each;
 * vision accuracy is measured on real images elsewhere (RealImageLineTest, devtools EvaluateLanes).
 */
class EngineLogicTest {
    private fun straight(lateral: Double = 0.0, far: Double = 10.0) = PathCurve(doubleArrayOf(lateral, 0.0, 0.0), 0.5, far)
    private fun bend(kappa: Double) = PathCurve(doubleArrayOf(0.0, 0.0, kappa / 2), 0.5, 10.0)

    private fun lineCandidate(curve: PathCurve, score: Double = 3.0): LineCandidate {
        val segs = curve.sample(0.5).map { LineSegment(0.0, 0.0, 4.0, 0.9, it, 0.1) }
        return LineCandidate(segs, curve, curve.farM - curve.nearM, 1.0, 0.9, score)
    }

    private fun obstacle(f: Double, l: Double, hits: Int = 3, vl: Double = 0.0) =
        TrackedObstacle(1, GroundPoint(f, l), 0.3, 0.9, "person", 0.0, vl, hits, 0)

    // ── tracker ───────────────────────────────────────────────────────────

    @Test
    fun `tracker confirms after consistent frames and coasts through a short occlusion`() {
        val t = PathTracker(TrackerConfig(maxCoastFrames = 5))
        repeat(3) { t.update(straight(0.1)) }
        assertEquals(TrackState.TRACKING, t.state)
        repeat(4) { t.update(null) }
        assertEquals(TrackState.COASTING, t.state)
        assertTrue(t.confidence in 0.0..0.5)
        t.update(straight(0.1))
        assertEquals(TrackState.TRACKING, t.state)
        repeat(6) { t.update(null) }
        assertEquals(TrackState.LOST, t.state)
    }

    @Test
    fun `tracker ignores a jump to another line while it can coast`() {
        val t = PathTracker()
        repeat(4) { t.update(straight(0.0)) }
        t.update(straight(1.5))  // a lane line 1.5 m away
        assertEquals(TrackState.COASTING, t.state)
        assertEquals(0.0, t.curve!!.lateralAt(1.0), 0.05)
    }

    // ── planner ───────────────────────────────────────────────────────────

    private fun planner() = IntelligentPathPlanner(PlannerConfig(modeHoldFrames = 1))

    private fun settle(p: IntelligentPathPlanner, input: FrameInput): PathDecision {
        var d = p.update(input)
        repeat(3) { d = p.update(input) }
        return d
    }

    @Test
    fun `follows a guide line under the runner`() {
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight(0.05))), null, null, emptyList(), 1.0))
        assertEquals(PathMode.LINE, d.mode)
    }

    @Test
    fun `between two lane boundaries follows the lane center, not a boundary`() {
        val lines = listOf(lineCandidate(straight(-0.5)), lineCandidate(straight(0.7)))
        val d = settle(planner(), FrameInput(lines, null, null, emptyList(), 1.0))
        assertEquals(PathMode.LANE_CENTER, d.mode)
        assertEquals(0.1, d.path!!.lateralAt(2.0), 0.05)
    }

    @Test
    fun `a lone side line is followed in parallel, not run onto`() {
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight(0.45))), null, null, emptyList(), 1.0))
        assertEquals(PathMode.LINE_PARALLEL, d.mode)
        assertEquals(0.0, d.path!!.lateralAt(3.0), 0.05)
    }

    @Test
    fun `without lines the free-space route is the virtual path`() {
        val free = FreeRoute((0..20).map { GroundPoint(it * 0.25, 0.0) }, 5.0, 5.0, 0.0, 1.0)
        val d = settle(planner(), FrameInput(emptyList(), free, null, emptyList(), 1.0))
        assertEquals(PathMode.FREE_PATH, d.mode)
    }

    @Test
    fun `stops when nothing can be verified`() {
        val d = settle(planner(), FrameInput(emptyList(), null, null, emptyList(), 1.0))
        assertEquals(PathMode.STOP, d.mode)
        assertEquals(StopReason.NO_PATH, d.stopReason)
        assertEquals(StopReason.POOR_VISIBILITY, planner().update(FrameInput(emptyList(), null, null, emptyList(), 0.1)).stopReason)
    }

    @Test
    fun `close obstacle on the line without a verified alternative stops`() {
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight())), null, null, listOf(obstacle(2.5, 0.1)), 1.0))
        assertEquals(PathMode.STOP, d.mode)
        assertEquals(StopReason.OBSTACLE_NO_ALTERNATIVE, d.stopReason)
    }

    @Test
    fun `close obstacle with a visible clear route around it takes the detour`() {
        val around = FreeRoute((0..24).map { k -> val f = k * 0.25; GroundPoint(f, if (f in 1.5..3.5) 1.0 else if (f < 1.5) f / 1.5 else 1.0) },
            6.0, 6.0, 20.0, 0.8)
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight())), around, null, listOf(obstacle(2.5, 0.0)), 1.0))
        assertEquals(PathMode.DETOUR, d.mode)
    }

    @Test
    fun `far obstacle on the path is a warning, not a stop`() {
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight())), null, null, listOf(obstacle(8.0, 0.0)), 1.0))
        assertEquals(PathMode.LINE, d.mode)
        assertEquals(8.0, d.obstacleAheadM!!, 0.3)
    }

    @Test
    fun `a person walking into the path is caught before they get there`() {
        // 3 m ahead, 1.5 m to the side, walking toward the line at 1.5 m/s: there when we arrive
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight())), null, null,
            listOf(obstacle(3.0, 1.5, vl = -1.5)), 1.0))
        assertEquals(PathMode.STOP, d.mode)
    }

    @Test
    fun `an unconfirmed far detection is ignored`() {
        val d = settle(planner(), FrameInput(listOf(lineCandidate(straight())), null, null, listOf(obstacle(4.0, 0.0, hits = 1)), 1.0))
        assertEquals(PathMode.LINE, d.mode)
        assertNull(d.obstacleAheadM)
    }

    // ── guidance ──────────────────────────────────────────────────────────

    private fun decision(c: PathCurve, conf: Double = 1.0) = PathDecision(PathMode.LINE, c, conf)

    @Test
    fun `correction levels grow with the deviation`() {
        val g = GuidanceController()
        assertEquals(CommandKind.ON_PATH, g.update(decision(straight(0.05))).kind)
        val low = GuidanceController().update(decision(straight(0.3)))
        val high = GuidanceController().update(decision(straight(1.2)))
        assertEquals(CommandKind.CORRECT to Side.RIGHT, low.kind to low.side)
        assertEquals(Level.LOW, low.level)
        assertEquals(Level.HIGH, high.level)
        assertEquals(Side.LEFT, GuidanceController().update(decision(straight(-0.6))).side)
    }

    @Test
    fun `no left-right flicker around the path`() {
        val g = GuidanceController()
        g.update(decision(straight(0.3)))              // right
        val flip = g.update(decision(straight(-0.2)))  // small opposite deviation
        assertTrue(flip.side != Side.LEFT, "flipped side on a small deviation")
        g.update(decision(straight(-0.5)))
        assertEquals(Side.LEFT, g.update(decision(straight(-0.5))).side) // persistent → switches
    }

    @Test
    fun `a bend is announced ahead with its sharpness`() {
        val gentle = GuidanceController().update(decision(bend(0.05)))
        val sharp = GuidanceController().update(decision(bend(-0.2)))
        assertEquals(CommandKind.CURVE to Side.RIGHT, gentle.kind to gentle.side)
        assertEquals(Level.LOW, gentle.level)
        assertEquals(Side.LEFT, sharp.side)
        assertTrue(sharp.level!! > gentle.level!!, "sharper bend must give a stronger cue")
        assertNotNull(gentle.curveInM)
    }

    @Test
    fun `stop decision gives a stop command`() {
        val c = GuidanceController().update(PathDecision(PathMode.STOP, null, 0.0, StopReason.OBSTACLE_NO_ALTERNATIVE))
        assertEquals(CommandKind.STOP, c.kind)
        assertEquals(StopReason.OBSTACLE_NO_ALTERNATIVE, c.stopReason)
    }

    // ── haptics ───────────────────────────────────────────────────────────

    private class Recorder(override val hasAmplitudeControl: Boolean = true) : HapticOutput {
        val played = ArrayList<Pair<Long, String>>()
        var cancels = 0
        var now = 0L
        override fun play(pattern: HapticPattern) { played += now to pattern.name }
        override fun cancel() { cancels++ }
    }

    @Test
    fun `left is one pulse, right is two, levels change amplitude or length`() {
        val lang = HapticLanguage(true)
        fun pulses(p: HapticPattern) = p.amplitudes.count { it > 0 }
        assertEquals(1, pulses(lang.correct(Side.LEFT, Level.LOW)))
        assertEquals(2, pulses(lang.correct(Side.RIGHT, Level.LOW)))
        assertTrue(lang.correct(Side.LEFT, Level.HIGH).amplitudes.max() > lang.correct(Side.LEFT, Level.LOW).amplitudes.max())
        assertEquals(3, pulses(lang.obstacle()))
        val noAmp = HapticLanguage(false)
        assertTrue(noAmp.correct(Side.LEFT, Level.HIGH).durationMs > noAmp.correct(Side.LEFT, Level.LOW).durationMs)
        assertTrue(noAmp.correct(Side.LEFT, Level.LOW).amplitudes.all { it == 0 || it == 255 })
        // Bends are longer and softer than corrections
        assertTrue(lang.curve(Side.LEFT, Level.LOW).durationMs > lang.correct(Side.LEFT, Level.LOW).durationMs)
        assertTrue(lang.curve(Side.LEFT, Level.LOW).amplitudes.max() < lang.correct(Side.LEFT, Level.MEDIUM).amplitudes.max())
        // createWaveform contract
        lang.catalog().forEach { (p, _, _) -> assertEquals(p.timingsMs.size, p.amplitudes.size); assertEquals(0, p.amplitudes[0]) }
    }

    @Test
    fun `stop pre-empts and stays latched until the danger clears`() {
        val out = Recorder()
        val s = HapticScheduler(out)
        val correct = GuidanceCommand(CommandKind.CORRECT, Side.LEFT, Level.LOW, confidence = 1.0)
        assertNotNull(s.onCommand(correct, 0))
        assertEquals("stop", s.onCommand(GuidanceCommand(CommandKind.STOP), 30)!!.name)
        assertEquals(1, out.cancels)
        // Danger gone for a few frames: still silent (latched)
        for (t in 1..5) assertNull(s.onCommand(correct, 3000L + t * 66))
        // ... then guidance resumes
        var resumed = false
        for (t in 6..20) if (s.onCommand(correct, 3000L + t * 66) != null) resumed = true
        assertTrue(resumed)
    }

    @Test
    fun `repeats are rate limited and lower priority never cuts a pattern`() {
        val out = Recorder()
        val s = HapticScheduler(out)
        val curve = GuidanceCommand(CommandKind.CURVE, Side.RIGHT, Level.LOW, confidence = 1.0)
        assertNotNull(s.onCommand(curve, 0))
        assertNull(s.onCommand(curve, 500))                     // too soon
        assertNull(s.onCommand(GuidanceCommand(CommandKind.ON_PATH), 100)) // lower priority while playing
        assertNotNull(s.onCommand(GuidanceCommand(CommandKind.OBSTACLE), 120)) // higher priority interrupts
    }

    @Test
    fun `stretched pulses are never cut by their own repeat`() {
        // The user doubled the pulse length: a strong right correction now lasts 2 x 300 + 150 ms.
        val doubled = object : HapticOutput {
            override val hasAmplitudeControl = true
            val played = ArrayList<Long>()
            var now = 0L
            override fun play(pattern: HapticPattern) { played += now }
            override fun cancel() {}
            override fun playedDurationMs(pattern: HapticPattern) =
                pattern.timingsMs.indices.sumOf { i -> if (pattern.amplitudes[i] > 0) pattern.timingsMs[i] * 2 else pattern.timingsMs[i] }
        }
        val s = HapticScheduler(doubled)
        val cmd = GuidanceCommand(CommandKind.CORRECT, Side.RIGHT, Level.HIGH)
        for (t in 0L..2000L step 50L) { doubled.now = t; s.onCommand(cmd, t) }
        val gaps = doubled.played.zipWithNext { a, b -> b - a }
        assertTrue(gaps.isNotEmpty())
        assertTrue(gaps.all { it >= 750 + HapticScheduler.REPEAT_GAP_MS }, "repeats at $gaps")
    }

    @Test
    fun `opposite side cues are not fired back to back`() {
        val s = HapticScheduler(Recorder())
        assertNotNull(s.onCommand(GuidanceCommand(CommandKind.CORRECT, Side.RIGHT, Level.LOW), 0))
        assertNull(s.onCommand(GuidanceCommand(CommandKind.CORRECT, Side.LEFT, Level.LOW), 460)) // the right cue (450 ms) has ended, the guard has not
        assertNotNull(s.onCommand(GuidanceCommand(CommandKind.CORRECT, Side.LEFT, Level.HIGH), 480)) // urgent is allowed
    }

    // ── calibration ───────────────────────────────────────────────────────

    private fun okFrames(found: Boolean = true, ms: Double = 40.0) = List(30) { FrameStats(120.0, 0.05, 200.0, found, ms) }

    @Test
    fun `calibration passes when everything is in order`() {
        val r = Calibration().evaluate(CalibrationInputs(12.0, 2.0, okFrames(), 5.0, true, 7, 8))
        assertTrue(r.ready, r.checks.toString())
    }

    @Test
    fun `calibration blocks the run on each required failure`() {
        val base = CalibrationInputs(12.0, 2.0, okFrames(), 5.0, true, 7, 8)
        val cal = Calibration()
        assertFalse(cal.evaluate(base.copy(pitchDeg = 60.0)).ready)
        assertFalse(cal.evaluate(base.copy(rollDeg = 25.0)).ready)
        assertFalse(cal.evaluate(base.copy(frames = okFrames(found = false))).ready)
        assertFalse(cal.evaluate(base.copy(frames = okFrames(ms = 150.0))).ready)
        assertFalse(cal.evaluate(base.copy(vibrationConfirmed = null)).ready)
        assertFalse(cal.evaluate(base.copy(quizCorrect = 4)).ready)
        assertFalse(cal.evaluate(base.copy(gyroStdDegPerS = 40.0)).ready)
        assertTrue(cal.needsRecalibration(12.0, 0.0, 25.0, 0.0))
        assertFalse(cal.needsRecalibration(12.0, 0.0, 15.0, 3.0))
    }

    @Test
    fun `gravity gives camera tilt for an upright phone`() {
        val (level, _) = Calibration.pitchRollFromGravity(0.0, 9.81, 0.0)
        val (down20, roll) = Calibration.pitchRollFromGravity(0.0, 9.81 * kotlin.math.cos(20.0.toRadians()), 9.81 * kotlin.math.sin(20.0.toRadians()))
        assertEquals(0.0, level, 0.01)
        assertEquals(20.0, down20, 0.01)
        assertEquals(0.0, roll, 0.01)
    }
}
