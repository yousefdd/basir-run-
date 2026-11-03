package run.basir.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FRAME_MS = 66L

private fun correct(side: Side, level: Level = Level.LOW, conf: Double = 0.9) = GuidanceCommand(CommandKind.CORRECT, side, level, confidence = conf)
private fun onPath() = GuidanceCommand(CommandKind.ON_PATH, confidence = 0.9)

/** Guidance Decision Stability Engine: the owner's round-8 rules, one test each. */
class DecisionStabilityTest {
    private fun run(e: GuidanceDecisionStabilityEngine, cmds: List<GuidanceCommand?>, start: Long = 0L): List<StableGuidance> =
        cmds.mapIndexed { i, c -> e.update(c, start + i * FRAME_MS) }

    private fun side(s: StableGuidance) = s.held?.side

    @Test
    fun oneOppositeFrameNeverChangesTheDirection() {
        val e = GuidanceDecisionStabilityEngine()
        val out = run(e, List(20) { correct(Side.RIGHT) } + listOf(correct(Side.LEFT)) + List(10) { correct(Side.RIGHT) })
        assertTrue(out.drop(5).all { side(it) == Side.RIGHT }, "a single left frame changed the held direction")
        assertTrue(out.none { it.command?.side == Side.LEFT }, "a left cue reached the vibration layer")
    }

    @Test
    fun rightLeftRightFlickerGivesNoFlip() {
        val e = GuidanceDecisionStabilityEngine()
        // right, left, right, left ... every 2 frames (image noise around the middle)
        val cmds = List(60) { if ((it / 2) % 2 == 0) correct(Side.RIGHT) else correct(Side.LEFT) }
        val sides = run(e, cmds).mapNotNull { it.command?.side }
        var flips = 0
        for (k in 1 until sides.size) if (sides[k] != sides[k - 1]) flips++
        assertTrue(flips <= 1, "flicker produced $flips left/right flips")
    }

    @Test
    fun aRealPersistentChangeOfSideIsFollowed() {
        val e = GuidanceDecisionStabilityEngine()
        val out = run(e, List(30) { correct(Side.RIGHT) } + List(60) { correct(Side.LEFT) })
        val firstLeft = out.indexOfFirst { side(it) == Side.LEFT }
        assertTrue(firstLeft in 31..30 + 40, "a 4 s change of side was not followed in time (frame $firstLeft)")
        assertTrue(out.last().held?.side == Side.LEFT, "the engine stayed on the old direction after the road changed")
    }

    @Test
    fun stopPassesOnTheSameFrameAndIsNeverDelayed() {
        val e = GuidanceDecisionStabilityEngine()
        run(e, List(10) { correct(Side.RIGHT) })
        val s = e.update(GuidanceCommand(CommandKind.STOP), 10 * FRAME_MS)
        assertEquals(CommandKind.STOP, s.command?.kind)
        assertEquals(GuidanceMode.EMERGENCY_STOP, s.mode)
    }

    @Test
    fun obstacleWarningPassesAtOnceButItsSideOnlyWhenStable() {
        val e = GuidanceDecisionStabilityEngine()
        run(e, List(10) { onPath() })
        val first = e.update(GuidanceCommand(CommandKind.OBSTACLE, Side.LEFT, Level.HIGH), 10 * FRAME_MS)  // close: never voted
        assertEquals(CommandKind.OBSTACLE, first.command?.kind, "the obstacle warning was delayed")
        assertNull(first.command?.side, "the way round was named on its first frame")
        var last: StableGuidance? = null
        for (k in 1..4) last = e.update(GuidanceCommand(CommandKind.OBSTACLE, Side.LEFT, Level.HIGH), (10 + k) * FRAME_MS)
        assertEquals(Side.LEFT, last!!.command?.side)
    }

    @Test
    fun strongCorrectionNeedsTwoFramesNotOne() {
        val e = GuidanceDecisionStabilityEngine()
        run(e, List(20) { onPath() })
        val one = e.update(correct(Side.LEFT, Level.HIGH), 20 * FRAME_MS)
        assertNotEquals(Level.HIGH, one.command?.level, "one frame of a strong correction passed")
        val two = e.update(correct(Side.LEFT, Level.HIGH), 21 * FRAME_MS)
        val three = e.update(correct(Side.LEFT, Level.HIGH), 22 * FRAME_MS)
        assertTrue(two.command?.level == Level.HIGH || three.command?.level == Level.HIGH, "a sustained strong correction was held back")
    }

    @Test
    fun oneDarkFrameIsNotACaution() {
        val e = GuidanceDecisionStabilityEngine()
        run(e, List(20) { onPath() })
        val s = e.update(GuidanceCommand(CommandKind.CAUTION), 20 * FRAME_MS)
        assertNotEquals(CommandKind.CAUTION, s.command?.kind)
        val s2 = e.update(GuidanceCommand(CommandKind.CAUTION), 21 * FRAME_MS)
        assertEquals(CommandKind.CAUTION, s2.command?.kind)
        assertEquals(GuidanceMode.GUIDANCE_CAUTION, s2.mode)
    }

    @Test
    fun aFlickeringPossibleObstacleIsNotACommandButACloseOneIsAtOnce() {
        val e = GuidanceDecisionStabilityEngine()
        run(e, List(20) { onPath() })
        // one or two frames of a possible obstacle among calm frames: no warning
        val out = run(e, listOf(GuidanceCommand(CommandKind.OBSTACLE), onPath(), GuidanceCommand(CommandKind.OBSTACLE), GuidanceCommand(CommandKind.OBSTACLE), onPath()), start = 20 * FRAME_MS)
        assertTrue(out.none { it.command?.kind == CommandKind.OBSTACLE }, "a flickering possible obstacle reached the vibration layer")
        // a possible obstacle that stays dominant is given
        val held = run(e, List(6) { GuidanceCommand(CommandKind.OBSTACLE) }, start = 25 * FRAME_MS)
        assertTrue(held.any { it.command?.kind == CommandKind.OBSTACLE }, "a dominant possible obstacle was never given")
        // a close obstacle passes on its first frame
        val f = GuidanceDecisionStabilityEngine(); run(f, List(20) { onPath() })
        assertEquals(CommandKind.OBSTACLE, f.update(GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH), 20 * FRAME_MS).command?.kind)
    }

    @Test
    fun cooldownIsAdaptiveAndRecordsReasons() {
        val c = AdaptiveGuidanceCooldownManager()
        val slight = correct(Side.LEFT)
        assertTrue(c.holdS(slight, 2.5, 1.0) > c.holdS(correct(Side.LEFT, Level.HIGH), 2.5, 1.0))
        assertTrue(c.holdS(slight, 1.4, 1.0) > c.holdS(slight, 3.5, 1.0), "slower running should hold longer")
        assertTrue(c.holdS(slight, 2.5, 0.3) > c.holdS(slight, 2.5, 1.0), "weak tracking should hold longer")
        assertEquals(0.0, c.holdS(GuidanceCommand(CommandKind.OBSTACLE), 2.5, 1.0), "safety events must never wait")
        c.record(1000L, "test"); assertEquals("test", c.lastReason); assertEquals(1000L, c.lastChangeMs)
    }

    @Test
    fun modesFollowTheFourStates() {
        assertEquals(GuidanceMode.GUIDANCE_ACTIVE, guidanceModeOf(onPath()))
        assertEquals(GuidanceMode.GUIDANCE_CORRECTION, guidanceModeOf(correct(Side.LEFT)))
        assertEquals(GuidanceMode.GUIDANCE_CAUTION, guidanceModeOf(GuidanceCommand(CommandKind.CAUTION)))
        assertEquals(GuidanceMode.EMERGENCY_STOP, guidanceModeOf(GuidanceCommand(CommandKind.STOP)))
        assertTrue(guidancePriority(GuidanceCommand(CommandKind.CURVE, Side.LEFT, corner = CornerPhase.PREPARE_TURN)) > guidancePriority(correct(Side.LEFT, Level.MEDIUM)))
        assertTrue(guidancePriority(GuidanceCommand(CommandKind.OBSTACLE)) > guidancePriority(GuidanceCommand(CommandKind.CURVE, Side.LEFT, corner = CornerPhase.TURNING)))
    }
}

/** Corner Detection & Navigation Engine on exact geometry (path curves and a rendered line mask). */
class CornerNavigationTest {
    private val cam = CameraModel(240, 426, 45.0, 1.3, 30.0, 0.0)

    /** Followed straight line that ends [endM] ahead, plus a perpendicular line leaving its end to [side], drawn into a mask. */
    private fun cornerMask(endM: Double, side: Side?, continueM: Double = 0.0): ProbabilityMask {
        val m = ProbabilityMask(cam.width, cam.height)
        fun draw(f: Double, l: Double) {
            cam.groundToImage(GroundPoint(f, l))?.let { (u, v) ->
                val x = u.toInt(); val y = v.toInt()
                for (dy in -1..1) for (dx in -1..1) if (x + dx in 0 until m.width && y + dy in 0 until m.height) m[x + dx, y + dy] = 1f
            }
        }
        var f = 0.5
        while (f <= endM + continueM) { draw(f, 0.0); f += 0.02 }
        if (side != null) {
            var l = 0.0
            while (l <= 5.0) { draw(endM, if (side == Side.RIGHT) l else -l); l += 0.02 }
        }
        return m
    }

    private fun straight(far: Double) = PathCurve(doubleArrayOf(0.0, 0.0, 0.0), 0.5, far)

    @Test
    fun lineCornerIsDetectedPreparedTurnedAndExited() {
        val e = CornerNavigationEngine()
        val v = 2.5; val dt = 1.0 / 15
        var dist = 9.0
        val phases = ArrayList<CornerPhase>()
        var turnedYaw = 0.0
        for (k in 0 until 200) {
            val inTurn = dist <= 0.6
            val yaw = if (inTurn && turnedYaw < PI / 2) (PI / 2) / (1.2 / dt) else 0.0
            turnedYaw += yaw
            val path = if (dist > 1.5) straight(dist) else if (turnedYaw > 1.3) straight(8.0) else null
            val mask = if (dist > 1.5) cornerMask(dist, Side.RIGHT) else null
            val s = e.update(CornerInput(dt, v, yaw, path, if (path != null) PathMode.LINE else null, 0.9, mask, cam, null))
            phases += s.phase
            if (s.phase != CornerPhase.NONE) assertEquals(Side.RIGHT, s.side, "wrong corner side")
            dist -= v * dt
        }
        val order = phases.distinct()
        assertTrue(CornerPhase.APPROACH_CORNER in order && CornerPhase.PREPARE_TURN in order && CornerPhase.TURNING in order &&
            CornerPhase.EXIT_CORNER in order && CornerPhase.STABLE_STRAIGHT in order, "phases seen: $order")
        assertTrue(order.indexOf(CornerPhase.PREPARE_TURN) < order.indexOf(CornerPhase.TURNING))
        // prepare comes before the corner, with time to react
        val prepareAt = phases.indexOf(CornerPhase.PREPARE_TURN)
        assertTrue(9.0 - prepareAt * v * dt >= 2.5, "prepared too late")
    }

    /** The followed straight line ends at [endM]; a quarter arc of radius [r] continues it to the right, then a straight. */
    private fun roundedMask(endM: Double, r: Double, lineAt: Double = 0.0): ProbabilityMask {
        val m = ProbabilityMask(cam.width, cam.height)
        fun draw(f: Double, l: Double) {
            cam.groundToImage(GroundPoint(f, l))?.let { (u, v) ->
                val x = u.toInt(); val y = v.toInt()
                for (dy in -1..1) for (dx in -1..1) if (x + dx in 0 until m.width && y + dy in 0 until m.height) m[x + dx, y + dy] = 1f
            }
        }
        var f = 0.5
        while (f <= endM) { draw(f, lineAt); f += 0.02 }
        var a = 0.0
        while (a <= PI / 2) { draw(endM + r * sin(a), lineAt + r - r * cos(a)); a += 0.01 }
        var l = r
        while (l <= 5.0) { draw(endM + r, lineAt + l); l += 0.02 }
        return m
    }

    @Test
    fun roundedCornerIsACornerToo() {
        val e = CornerNavigationEngine()
        var s = CornerState()
        for (k in 0 until 12) {
            val end = 6.0 - k * 0.17
            s = e.update(CornerInput(1.0 / 15, 2.5, 0.0, straight(end), PathMode.LINE, 0.9, roundedMask(end, 3.0), cam, null))
        }
        assertNotEquals(CornerPhase.NONE, s.phase, "a 3 m rounded corner was not seen: ${e.lastDebug}")
        assertEquals(Side.RIGHT, s.side)
    }

    @Test
    fun cornerSeenWhileRunningBesideTheLine() {
        val e = CornerNavigationEngine()
        var s = CornerState()
        // the runner follows a path 0.7 m left of the line (LINE_PARALLEL); the line turns right at its end
        for (k in 0 until 12) {
            val end = 6.0 - k * 0.17
            val path = PathCurve(doubleArrayOf(-0.7, 0.0, 0.0), 0.5, end)
            s = e.update(CornerInput(1.0 / 15, 1.4, 0.0, path, PathMode.LINE_PARALLEL, 0.9, roundedMask(end, 0.01), cam, null))
        }
        assertNotEquals(CornerPhase.NONE, s.phase, "the corner of a line followed alongside was not seen")
        assertEquals(Side.RIGHT, s.side)
    }

    @Test
    fun leftCornerGivesLeft() {
        val e = CornerNavigationEngine()
        var last: CornerState? = null
        for (k in 0 until 10) last = e.update(CornerInput(1.0 / 15, 2.5, 0.0, straight(8.0 - k * 0.17), PathMode.LINE, 0.9, cornerMask(8.0 - k * 0.17, Side.LEFT), cam, null))
        assertEquals(Side.LEFT, last!!.side)
        assertNotEquals(CornerPhase.NONE, last.phase)
    }

    @Test
    fun oneFrameNeverMakesACorner() {
        val e = CornerNavigationEngine()
        val s1 = e.update(CornerInput(1.0 / 15, 2.5, 0.0, straight(7.0), PathMode.LINE, 0.9, cornerMask(7.0, Side.RIGHT), cam, null))
        assertEquals(CornerPhase.NONE, s1.phase)
        var s = s1
        repeat(30) { s = e.update(CornerInput(1.0 / 15, 2.5, 0.0, straight(12.0), PathMode.LINE, 0.9, cornerMask(12.0, null), cam, null)) }
        assertEquals(CornerPhase.NONE, s.phase)
    }

    @Test
    fun aCrossingLineThatGoesOnIsNotACorner() {
        val e = CornerNavigationEngine()
        var s = CornerState()
        // the followed line continues 4 m past the perpendicular line: a crossing (e.g. a pitch marking), not a corner
        repeat(15) { k -> s = e.update(CornerInput(1.0 / 15, 2.5, 0.0, straight(6.0), PathMode.LINE, 0.9, cornerMask(6.0, Side.RIGHT, continueM = 4.0), cam, null)) }
        assertEquals(CornerPhase.NONE, s.phase)
    }

    @Test
    fun athleticsBendIsNotACorner() {
        val e = CornerNavigationEngine()
        // lateral = f^2 / (2R), R = 36.5 m
        val bend = PathCurve(doubleArrayOf(0.0, 0.0, 1.0 / (2 * 36.5)), 0.5, 10.0)
        var s = CornerState()
        repeat(60) { s = e.update(CornerInput(1.0 / 15, 3.0, 3.0 / 36.5 / 15, bend, PathMode.LINE, 0.9, null, cam, null)) }
        assertEquals(CornerPhase.NONE, s.phase)
    }

    @Test
    fun noBodyTurnCancelsTheTurnPhase() {
        val e = CornerNavigationEngine()
        var dist = 6.0; var s = CornerState()
        val seen = HashSet<CornerPhase>()
        repeat(80) {
            val path = if (dist > 1.5) straight(dist) else straight(8.0)
            s = e.update(CornerInput(1.0 / 15, 2.5, 0.0, path, PathMode.LINE, 0.9, if (dist > 1.5) cornerMask(dist, Side.RIGHT) else null, cam, null))
            seen += s.phase
            dist -= 2.5 / 15
        }
        assertTrue(CornerPhase.TURNING in seen || CornerPhase.PREPARE_TURN in seen)
        assertEquals(CornerPhase.NONE, s.phase, "kept a corner the runner never turned at")
    }

    @Test
    fun turnWithoutVerifiedExitGivesNoDirection() {
        val e = CornerNavigationEngine()
        var dist = 6.0; var s = CornerState(); var unverified = false
        repeat(150) {
            val path = if (dist > 1.5) straight(dist) else null
            // the body turns slowly but never enough and no path is found after the corner
            val yaw = if (dist <= 0.6) 0.25 / 15 else 0.0
            s = e.update(CornerInput(1.0 / 15, 2.5, yaw, path, if (path != null) PathMode.LINE else null, 0.9, if (dist > 1.5) cornerMask(dist, Side.RIGHT) else null, cam, null))
            if (s.exitUnverified) unverified = true
            dist -= 2.5 / 15
        }
        assertTrue(unverified, "a turn with no way out found was not reported as unverified")
        assertEquals(CornerPhase.NONE, s.phase)
    }
}

/** Round-8 vibration language and scheduler rules. */
class Round8HapticsTest {
    private class Rec : HapticOutput {
        override val hasAmplitudeControl = true
        val played = ArrayList<String>()
        override fun play(pattern: HapticPattern) { played += pattern.name }
        override fun cancel() {}
    }

    @Test
    fun turnPatternsAreDistinctFromEveryOtherCue() {
        val l = HapticLanguage(true)
        val all = l.catalog().map { it.first }
        val turn = listOf(l.turnPrepare(Side.LEFT), l.turnPrepare(Side.RIGHT), l.turnNow(Side.LEFT), l.turnNow(Side.RIGHT))
        for (t in turn) {
            val same = all.filter { it.name != t.name && it.timingsMs.contentEquals(t.timingsMs) && it.amplitudes.contentEquals(t.amplitudes) }
            assertTrue(same.isEmpty(), "${t.name} feels the same as ${same.map { it.name }}")
        }
        // left = one, right = two, as everywhere
        assertTrue(l.turnPrepare(Side.RIGHT).durationMs > l.turnPrepare(Side.LEFT).durationMs)
        assertTrue(l.turnNow(Side.RIGHT).durationMs > l.turnNow(Side.LEFT).durationMs)
    }

    @Test
    fun cornerCommandsPlayTheirPatternsAndStopStillPreempts() {
        val out = Rec()
        val s = HapticScheduler(out)
        s.onCommand(GuidanceCommand(CommandKind.CURVE, Side.RIGHT, Level.HIGH, corner = CornerPhase.PREPARE_TURN), 0)
        assertEquals("turn_prepare_right", out.played.last())
        s.onCommand(GuidanceCommand(CommandKind.STOP), 100)
        assertTrue(out.played.last().startsWith("stop"), "STOP did not cut the corner pattern")
    }

    @Test
    fun aLaterObstacleIsFeltEvenWhenNothingPlayedInBetween() {
        assertTrue(laterObstacleFelt(SchedulerConfig(enableOnPathTick = false)), "the new obstacle was not felt")
        // the 0.14.0 behaviour (never a new episode) indeed missed it: the test detects the bug
        assertFalse(laterObstacleFelt(SchedulerConfig(enableOnPathTick = false, obstacleEpisodeGapMs = Long.MAX_VALUE)))
    }

    private fun laterObstacleFelt(cfg: SchedulerConfig): Boolean {
        val out = Rec()
        val s = HapticScheduler(out, config = cfg)
        var t = 0L
        // a first obstacle episode, long enough to use up its reminders
        repeat(120) { s.onCommand(GuidanceCommand(CommandKind.OBSTACLE), t); t += 66 }
        val before = out.played.count { it == "obstacle" }
        // calm (nothing plays: the on-path tick is quiet), then a new obstacle 4 s later
        repeat(60) { s.onCommand(GuidanceCommand(CommandKind.ON_PATH, confirm = false), t); t += 66 }
        out.played.removeAll { it == "on_path" }
        s.onCommand(GuidanceCommand(CommandKind.OBSTACLE), t)
        return out.played.count { it == "obstacle" } == before + 1
    }

    @Test
    fun anObstacleThatBecomesCloseIsFeltAgainSoon() {
        val out = Rec()
        val s = HapticScheduler(out)
        var t = 0L
        repeat(15) { s.onCommand(GuidanceCommand(CommandKind.OBSTACLE), t); t += 66 }           // possible obstacle: 1 play
        repeat(5) { s.onCommand(GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH), t); t += 66 } // now close
        assertEquals(2, out.played.count { it == "obstacle" }, "the escalation to a close obstacle was not felt: ${out.played}")
        // flicker close <-> possible inside the same obstacle episode: re-warned only at the normal 2.5 s pace
        t += 300
        repeat(6) { k -> s.onCommand(GuidanceCommand(CommandKind.OBSTACLE, level = if (k % 2 == 0) null else Level.HIGH), t); t += 300 }
        assertEquals(2, out.played.count { it == "obstacle" }, "flicker inside one obstacle episode was felt again too soon: ${out.played}")
        // and a close obstacle that just stays keeps the owner's cap (3 plays in 10 s)
        val o2 = Rec(); val s2 = HapticScheduler(o2)
        for (k in 0 until 150) s2.onCommand(GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH), k * 67L)
        assertEquals(3, o2.played.size)
    }

    @Test
    fun differentVibrationsNeverComeWithinTheCooldownExceptForSafety() {
        val out = Rec(); val s = HapticScheduler(out)
        s.onCommand(GuidanceCommand(CommandKind.CORRECT, Side.RIGHT, Level.LOW), 0)
        s.onCommand(GuidanceCommand(CommandKind.OBSTACLE), 300)                      // possible obstacle 0.3 s later: waits
        assertEquals(listOf("correct_right_low"), out.played)
        s.onCommand(GuidanceCommand(CommandKind.OBSTACLE), 1300)                     // still there after the cooldown: plays
        assertEquals("obstacle", out.played.last())
        s.onCommand(GuidanceCommand(CommandKind.CORRECT, Side.LEFT, Level.MEDIUM), 1600)  // calmer, 0.3 s later: waits
        assertEquals(2, out.played.size)
        // the faint on-path tick never delays a warning
        val o3 = Rec(); val s3 = HapticScheduler(o3)
        s3.onCommand(GuidanceCommand(CommandKind.ON_PATH), 0)
        s3.onCommand(GuidanceCommand(CommandKind.OBSTACLE), 200)
        assertEquals("obstacle", o3.played.last(), "an on-path tick delayed a warning")
        // safety is never held: a close obstacle and a STOP pass at once
        val o2 = Rec(); val s2 = HapticScheduler(o2)
        s2.onCommand(GuidanceCommand(CommandKind.CORRECT, Side.RIGHT, Level.LOW), 0)
        s2.onCommand(GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH), 200)
        assertEquals("obstacle", o2.played.last())
        s2.onCommand(GuidanceCommand(CommandKind.STOP), 300)
        assertTrue(o2.played.last().startsWith("stop"))
    }

    @Test
    fun aLastingCautionIsFeltOncePlusOneReminder() {
        val out = Rec(); val s = HapticScheduler(out)
        for (k in 0 until 150) s.onCommand(GuidanceCommand(CommandKind.CAUTION), k * 67L)   // 10 s of "path unclear"
        assertEquals(2, out.played.count { it == "caution" })
        // a new caution after a calm second is felt again
        for (k in 150 until 180) s.onCommand(GuidanceCommand(CommandKind.ON_PATH), k * 67L)
        s.onCommand(GuidanceCommand(CommandKind.CAUTION), 180 * 67L)
        assertEquals(3, out.played.count { it == "caution" })
    }

    @Test
    fun aGradualBendKeepsItsOwnPattern() {
        val l = HapticLanguage(true)
        assertTrue(l.forCommand(GuidanceCommand(CommandKind.CURVE, Side.LEFT, Level.LOW))!!.name.startsWith("curve_left"))
    }
}
