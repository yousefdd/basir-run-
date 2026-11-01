package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules of the state-based guidance: curve vs offset, hysteresis, dwell, uncertainty, stop priority, detours, vibrations. */
class StableGuidanceTest {
    private fun path(offset: Double = 0.0, slope: Double = 0.0, kappa: Double = 0.0, far: Double = 8.0) =
        PathCurve(doubleArrayOf(offset, slope, kappa / 2), 0.8, far)
    private fun follow(p: PathCurve, conf: Double = 0.9) = PathDecision(PathMode.FREE_PATH, p, conf)
    private fun stop(r: StopReason, side: Side? = null) = PathDecision(PathMode.STOP, null, 0.0, stopReason = r, turnSide = side)
    private val dt = 1.0 / 15

    private fun run(c: StableGuidanceController, d: PathDecision, frames: Int, speed: Double = 1.4) =
        (1..frames).map { c.update(d, speed, dt) }

    @Test
    fun `a centred runner on a curved path is told to follow the bend, never to correct`() {
        val c = StableGuidanceController()
        val evs = run(c, follow(path(kappa = 0.10)), 45)
        assertTrue(evs.none { it.state == GuidanceState.CORRECT_LEFT || it.state == GuidanceState.CORRECT_RIGHT }, "a bend is not a correction")
        assertTrue(evs.none { it.command.kind == CommandKind.STOP }, "a bend never stops the runner")
        assertEquals(GuidanceState.CURVE_RIGHT, evs.last().state)
        // The old pure-pursuit controller mixes the two: the same path gives a correction there.
        val old = GuidanceController().update(follow(path(kappa = 0.10)), 1.4)
        assertEquals(CommandKind.CORRECT, old.kind)
    }

    @Test
    fun `a runner beside a straight path is corrected toward it`() {
        val c = StableGuidanceController()
        val evs = run(c, follow(path(offset = 0.7)), 20)
        assertEquals(GuidanceState.CORRECT_RIGHT, evs.last().state)
        assertEquals(Side.RIGHT, evs.last().command.side)
        val l = StableGuidanceController()
        assertEquals(GuidanceState.CORRECT_LEFT, run(l, follow(path(offset = -0.7)), 20).last().state)
    }

    @Test
    fun `heading away from the path counts as drift before any offset builds up`() {
        val c = StableGuidanceController()
        // path straight ahead but the runner points 20 deg off it: path heads left relative to the runner
        val evs = run(c, follow(path(slope = -0.36)), 20)
        assertEquals(GuidanceState.CORRECT_LEFT, evs.last().state)
    }

    @Test
    fun `corrections use different enter and exit thresholds and a dead zone`() {
        val c = StableGuidanceController()
        assertEquals(GuidanceState.TRACK_CENTERED, run(c, follow(path(offset = 0.22)), 20).last().state, "0.22 m does not start a correction")
        run(c, follow(path(offset = 0.5)), 20)
        assertEquals(GuidanceState.CORRECT_RIGHT, c.state)
        assertEquals(GuidanceState.CORRECT_RIGHT, run(c, follow(path(offset = 0.22)), 20).last().state, "but it does not end one either")
        assertEquals(GuidanceState.TRACK_CENTERED, run(c, follow(path(offset = 0.05)), 30).last().state)
    }

    @Test
    fun `noisy readings around the threshold do not make the state flicker`() {
        val c = StableGuidanceController()
        val evs = (0 until 90).map { i -> c.update(follow(path(offset = if (i % 2 == 0) 0.45 else -0.45)), 1.4, dt) }
        val changes = evs.count { it.changed }
        assertTrue(changes <= 1, "state changed $changes times on alternating noise")
        assertTrue(evs.none { it.state == GuidanceState.CORRECT_LEFT } || evs.none { it.state == GuidanceState.CORRECT_RIGHT }, "no left/right flip")
    }

    @Test
    fun `left to right needs more evidence than a first correction`() {
        val c = StableGuidanceController()
        run(c, follow(path(offset = -0.8)), 20)
        assertEquals(GuidanceState.CORRECT_LEFT, c.state)
        val evs = run(c, follow(path(offset = 0.8)), 4)
        assertTrue(evs.all { it.state != GuidanceState.CORRECT_RIGHT }, "a flip is not taken after 4 frames")
        assertTrue(evs.all { it.command.side != Side.RIGHT }, "and no right cue is given meanwhile")
    }

    @Test
    fun `a lost or untrusted path slows the runner down and never stops them`() {
        // Owner, round 5: stops only when the way is closed; an unsure path is a caution without a direction.
        val c = StableGuidanceController()
        run(c, follow(path(offset = 0.7)), 20)
        val held = run(c, stop(StopReason.LOW_CONFIDENCE), 15) // 1.0 s
        assertTrue(held.all { it.command.kind == CommandKind.CAUTION && it.command.side == null }, "caution, no direction from unsure frames")
        assertTrue(held.all { it.state == GuidanceState.VISION_UNCERTAIN })
        assertEquals("caution", HapticLanguage().forCommand(held.first().command)!!.name)
        val longer = run(c, stop(StopReason.LOW_CONFIDENCE), 150) // 10 s more
        assertTrue(longer.none { it.command.kind == CommandKind.STOP }, "no stop however long the path stays unsure")
        assertTrue(run(c, stop(StopReason.POOR_VISIBILITY), 60).none { it.command.kind == CommandKind.STOP })
        // the old behaviour is still there behind the switch
        val old = StableGuidanceController(StableGuidanceConfig(stopWhenUncertain = true))
        run(old, follow(path()), 10)
        assertEquals(CommandKind.STOP, run(old, stop(StopReason.LOW_CONFIDENCE), 25).last().command.kind)
    }

    @Test
    fun `a closed way stops only after it has lasted more than a second without a break`() {
        for (reason in listOf(StopReason.OBSTACLE_NO_ALTERNATIVE, StopReason.PATH_UNSAFE, StopReason.NO_PATH)) {
            val c = StableGuidanceController()
            run(c, follow(path(offset = 0.7)), 20)
            val first = run(c, stop(reason), 15) // 1.0 s: not yet
            assertTrue(first.none { it.command.kind == CommandKind.STOP }, "$reason: no stop within the first second")
            assertEquals(GuidanceState.OBSTACLE_WARNING, first.first().state, "$reason: the warning comes at once")
            assertEquals(CommandKind.OBSTACLE, first.first().command.kind)
            val e = c.update(stop(reason), 1.4, dt) // just over 1 s
            assertEquals(GuidanceState.STOP_REQUIRED, e.state, "$reason")
            assertEquals(CommandKind.STOP, e.command.kind)
            assertEquals(4, e.severity)
            assertTrue(run(c, stop(reason), 10).all { it.command.kind == CommandKind.STOP }, "$reason: stays stopped while closed")
        }
        // closed for 0.8 s, open for one frame, closed again for 0.8 s: the timer starts over, no stop
        val c = StableGuidanceController()
        run(c, follow(path()), 20)
        val flicker = run(c, stop(StopReason.OBSTACLE_NO_ALTERNATIVE), 12) + run(c, follow(path()), 1) + run(c, stop(StopReason.OBSTACLE_NO_ALTERNATIVE), 12)
        assertTrue(flicker.none { it.command.kind == CommandKind.STOP })
        // the planner's hold before it resumes (LOW_CONFIDENCE) is the tail of the same warning: no caution, no stop
        val tail = run(c, stop(StopReason.LOW_CONFIDENCE), 30)
        assertTrue(tail.all { it.state == GuidanceState.OBSTACLE_WARNING && it.command.kind == CommandKind.OBSTACLE })
    }

    @Test
    fun `a new direction is given only after it has lasted a second, a side change after one and a half`() {
        val c = StableGuidanceController()
        run(c, follow(path()), 30)
        val toRight = run(c, follow(path(offset = 0.7)), 30) // 2 s, 0.7 m beside the path: correct right
        val first = toRight.indexOfFirst { it.state == GuidanceState.CORRECT_RIGHT }
        assertTrue(first >= 0, "corrected at last")
        assertTrue(first * dt >= 0.9, "not before about a second: ${first * dt} s")
        val back = run(c, follow(path(offset = -0.7)), 45)
        val flip = back.indexOfFirst { it.state == GuidanceState.CORRECT_LEFT }
        assertTrue(flip >= 0 && flip * dt >= 1.4, "left <-> right after about 1.5 s: ${flip * dt} s")
        // A strong correction (about to leave the path) is still given at once.
        val u = StableGuidanceController(); run(u, follow(path()), 30)
        assertEquals(GuidanceState.CORRECT_RIGHT, run(u, follow(path(offset = 1.5)), 2).last().state)
    }

    @Test
    fun `the side to turn after a stop is named only once it has been seen for several frames`() {
        val c = StableGuidanceController()
        val sides = (0 until 8).map { i -> c.update(stop(StopReason.PATH_UNSAFE, if (i % 2 == 0) Side.LEFT else Side.RIGHT), 1.4, dt).command.side }
        assertTrue(sides.all { it == null }, "a side open in single frames is never given")
        val steady = run(c, stop(StopReason.PATH_UNSAFE, Side.LEFT), 5).map { it.command.side }
        assertNull(steady.first())
        assertEquals(Side.LEFT, steady.last())
    }

    @Test
    fun `a detour side is given only after it has been seen for several frames`() {
        val c = StableGuidanceController()
        fun detour(right: Boolean) = PathDecision(PathMode.DETOUR, path(offset = if (right) 0.6 else -0.6), 0.6, obstacleAheadM = 3.0)
        val flicker = (0 until 8).map { c.update(detour(it % 2 == 0), 1.4, dt) }
        assertTrue(flicker.all { it.state == GuidanceState.OBSTACLE_WARNING && it.command.side == null })
        val steady = (0 until 5).map { c.update(detour(true), 1.4, dt) }
        assertEquals(Side.RIGHT, steady.last().command.side)
        assertEquals("obstacle_right", HapticLanguage().forCommand(steady.last().command)!!.name)
    }

    @Test
    fun `coming back to the path gives one quiet confirmation, then silence`() {
        val c = StableGuidanceController()
        val away = run(c, follow(path(offset = 0.7)), 20)
        val back = run(c, follow(path(offset = 0.0)), 40)
        assertEquals(1, back.count { it.command.confirm })
        assertEquals(GuidanceState.TRACK_CENTERED, back.last().state)
        val felt = ArrayList<String>()
        val s = HapticScheduler(object : HapticOutput {
            override val hasAmplitudeControl = true
            override fun play(pattern: HapticPattern) { felt += pattern.name }
            override fun cancel() {}
        }, config = SchedulerConfig(onPathOnlyOnConfirm = true))
        (away + back).forEachIndexed { i, e -> s.onCommand(e.command, i * 67L) }
        assertEquals("back_on_path", felt.last(), "the last thing felt is the confirmation: then silence on the path")
        assertEquals(1, felt.count { it == "back_on_path" })
        assertTrue(felt.all { it == "back_on_path" || it.startsWith("correct_right") }, "$felt")
    }

    @Test
    fun `no bend cue against a path that clearly heads the other way at the feet`() {
        val c = StableGuidanceController()
        // heads 15 deg right at the feet, bends left further on (crossing onto a sidewalk)
        val evs = run(c, PathDecision(PathMode.FREE_PATH, path(slope = 0.27, kappa = -0.12), 0.9, stripHalfWidthM = 2.5), 30)
        assertTrue(evs.none { it.state == GuidanceState.CURVE_LEFT || it.command.side == Side.LEFT }, "no left cue while the path pulls right")
    }

    @Test
    fun `a long stop vibrates once and twice more as a reminder, then stays quiet`() {
        // Owner: "the stop vibration does not stop". With pulses at double length it used to repeat
        // with 150 ms gaps for as long as the stop lasted.
        val starts = ArrayList<Long>(); var on = 0L; var now = 0L
        val doubled = object : HapticOutput {
            override val hasAmplitudeControl = true
            override fun play(pattern: HapticPattern) { starts += now; on += pattern.amplitudes.indices.filter { pattern.amplitudes[it] > 0 }.sumOf { pattern.timingsMs[it] * 2 } }
            override fun cancel() {}
            override fun playedDurationMs(pattern: HapticPattern) = pattern.amplitudes.indices.sumOf { if (pattern.amplitudes[it] > 0) pattern.timingsMs[it] * 2 else pattern.timingsMs[it] }
        }
        val s = HapticScheduler(doubled)
        for (k in 0 until 300) { now = k * 67L; s.onCommand(GuidanceCommand(CommandKind.STOP), now) } // 20 s stopped
        assertEquals(3, starts.size, "$starts")
        assertTrue(on <= 3 * 2200, "vibrating ${on} ms in 20 s")
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 5000 })
        // the side becoming known during the stop is played once more
        s.onCommand(GuidanceCommand(CommandKind.STOP, side = Side.LEFT), 20_100)
        assertEquals(4, starts.size)
        s.onCommand(GuidanceCommand(CommandKind.STOP, side = Side.LEFT), 20_200)
        assertEquals(4, starts.size, "but only once")
    }

    @Test
    fun `a lasting obstacle warning is felt three times at most, a new side at once`() {
        val felt = ArrayList<Pair<Long, String>>(); var now = 0L
        val s = HapticScheduler(object : HapticOutput {
            override val hasAmplitudeControl = true
            override fun play(pattern: HapticPattern) { felt += now to pattern.name }
            override fun cancel() {}
        })
        for (k in 0 until 150) { now = k * 67L; s.onCommand(GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH), now) } // 10 s
        assertEquals(3, felt.size, "$felt")
        now = 10_100; s.onCommand(GuidanceCommand(CommandKind.OBSTACLE, side = Side.LEFT, level = Level.HIGH), now)
        assertEquals("obstacle_left", felt.last().second)
    }

    @Test
    fun `a still object stays still when the runner walks toward it`() {
        val t = ObstacleTracker(); var last: List<TrackedObstacle> = emptyList()
        for (k in 0 until 15) last = t.update(listOf(ObstacleDetection(GroundPoint(6.0 - k * 1.4 / 15, 0.0), 0.4, 0.9, "bench")), 1.0 / 15, runnerAdvanceM = 1.4 / 15)
        assertTrue(last.single().speed < 0.1, "speed ${last.single().speed}")
        val noAdvance = ObstacleTracker()
        for (k in 0 until 15) last = noAdvance.update(listOf(ObstacleDetection(GroundPoint(6.0 - k * 1.4 / 15, 0.0), 0.4, 0.9, "bench")), 1.0 / 15)
        assertTrue(last.single().speed > 1.0, "without the runner's advance it looks like it is coming at 1.4 m/s (the old app)")
    }

    @Test
    fun `scene-sized boxes and things that are never on a footpath do not become obstacles`() {
        assertTrue(!DetectionFilter.keep("train", 0.3, 0.3, 0.5, 0.6))                 // sanpo_curve4: a "train" on a park path
        assertTrue(!DetectionFilter.keep("person", 0.0, 0.1, 1.0, 1.0))                // a box as wide as the frame
        assertTrue(DetectionFilter.keep("person", 0.35, 0.0, 0.7, 1.0))                // a person close ahead (35 % wide) stays
        assertTrue(DetectionFilter.keep("bench", 0.2, 0.4, 0.8, 0.8))                  // a bench across the way stays
    }

    @Test
    fun `someone walking ahead at the runner's pace does not block the way, someone standing or coming does`() {
        val route = FreeRoute((0..24).map { GroundPoint(it * 0.25, 0.0) }, 6.0, 6.0, 0.0, 1.0)
        fun decide(vf: Double, at: Double): PathDecision {
            val p = IntelligentPathPlanner(); var d: PathDecision? = null
            val o = TrackedObstacle(1, GroundPoint(at, 0.0), 0.3, 0.9, "person", vf, 0.0, 8, 0)
            repeat(5) { d = p.update(FrameInput(emptyList(), route, null, listOf(o), 1.0, runnerSpeedMps = 1.4)) }
            return d!!
        }
        assertNull(decide(vf = 1.4, at = 2.4).obstacleAheadM, "walking ahead at the same pace: no warning, no stop")
        assertTrue(decide(vf = 0.0, at = 2.4).let { it.mode == PathMode.STOP || it.obstacleAheadM != null }, "standing: warned or stopped")
        assertTrue(decide(vf = -1.0, at = 4.5).obstacleAheadM != null, "coming toward the runner: warned")
        assertTrue(decide(vf = 1.4, at = 1.0).let { it.mode == PathMode.STOP || it.obstacleAheadM != null }, "but within 1.2 m always counts")
        // a bench never recedes, even if its gap stops shrinking because the runner slowed down
        val bench = TrackedObstacle(2, GroundPoint(2.4, 0.0), 0.5, 0.9, "bench", 1.4, 0.0, 8, 0)
        assertTrue(!Receding.isReceding(bench, 1.4))
    }

    @Test
    fun `a ground class the model renames for a frame stays runnable for a moment`() {
        val u = UnderfootClasses()
        fun foot(vararg pairs: Pair<Int, Int>) = mapOf(*pairs)
        assertTrue(9 in u.update(foot(9 to 100), 100, 0), "grass under the feet: runnable")
        // the athletics clip: the same tartan is called "earth" for a frame
        assertTrue(9 in u.update(foot(13 to 100), 100, 250), "grass still runnable 0.25 s later")
        assertTrue(13 in u.update(foot(13 to 100), 100, 300), "and earth now too")
        assertTrue(9 !in u.update(foot(13 to 100), 100, 2600), "grass gone for 2.3 s: dropped")
        assertTrue(9 !in UnderfootClasses().update(foot(9 to 5, 11 to 95), 100, 0), "a little grass beside a sidewalk never enters")
    }

    @Test
    fun `small sports items need a sure detection`() {
        assertTrue(!DetectionFilter.keep("tennis racket", 0.4, 0.6, 0.45, 0.65, 0.45), "painted lane number seen as a racket")
        assertTrue(DetectionFilter.keep("sports ball", 0.4, 0.6, 0.45, 0.65, 0.8), "a sure ball stays")
        assertTrue(DetectionFilter.keep("person", 0.4, 0.3, 0.5, 0.9, 0.45), "people keep the normal threshold")
    }

    @Test
    fun `a lane lost for a moment is coasted instead of trusting a short free route`() {
        val p = IntelligentPathPlanner()
        fun lineAt(lat: Double): LineCandidate {
            val c = PathCurve(doubleArrayOf(lat, 0.0, 0.0), 0.8, 8.0)
            return LineCandidate(c.sample(0.5).map { LineSegment(0.0, 0.0, 4.0, 0.9, it, 0.1) }, c, 7.2, 1.0, 0.9, 3.0)
        }
        val lanes = listOf(lineAt(-0.61), lineAt(0.61))
        val shortRoute = FreeRoute((0..12).map { GroundPoint(it * 0.25, 0.0) }, 3.0, 3.0, 0.0, 1.0)
        repeat(12) { p.update(FrameInput(lanes, shortRoute, null, emptyList(), 1.0, runnerSpeedMps = 2.5)) }
        val gap = (1..4).map { p.update(FrameInput(emptyList(), shortRoute, null, emptyList(), 1.0, runnerSpeedMps = 2.5)) }
        assertTrue(gap.all { it.mode == PathMode.LANE_CENTER }, "a 4-frame gap in the lines keeps the lane: ${gap.map { it.mode }}")
        val long = (1..30).map { p.update(FrameInput(emptyList(), shortRoute, null, emptyList(), 1.0, runnerSpeedMps = 2.5)) }
        assertTrue(long.last().mode != PathMode.LANE_CENTER, "and the lane is dropped once the tracker gives it up")
    }

    @Test
    fun `the bend cue goes quiet after a while inside a gentle bend`() {
        val c = StableGuidanceController()
        val evs = run(c, follow(path(kappa = 0.07)), 60) // 4 s
        assertEquals(GuidanceState.CURVE_RIGHT, evs.last().state)
        assertEquals(CommandKind.ON_PATH, evs.last().command.kind, "tapered")
        assertTrue(evs.any { it.command.kind == CommandKind.CURVE }, "but it was announced")
    }

    @Test
    fun `no sudden swerve cue when running for a moderate drift`() {
        val run = StableGuidanceController()
        val walk = StableGuidanceController()
        val r = (1..20).map { run.update(follow(path(offset = 1.2)), 2.8, dt) }.last()
        val w = (1..20).map { walk.update(follow(path(offset = 1.2)), 1.4, dt) }.last()
        assertEquals(Level.HIGH, w.command.level)
        assertEquals(Level.MEDIUM, r.command.level)
    }

    @Test
    fun `every vibration in the catalogue differs clearly from every other`() {
        val lang = HapticLanguage()
        val pats = lang.catalog().map { it.first }
        fun sig(p: HapticPattern): Triple<Int, Long, Int> {
            val on = p.amplitudes.indices.filter { p.amplitudes[it] > 0 }
            return Triple(on.size, on.sumOf { p.timingsMs[it] }, on.maxOf { p.amplitudes[it] })
        }
        for (i in pats.indices) for (j in i + 1 until pats.size) {
            val a = sig(pats[i]); val b = sig(pats[j])
            val differs = a.first != b.first || abs(a.second - b.second) >= 100 || abs(a.third - b.third) >= 60
            assertTrue(differs, "${pats[i].name} and ${pats[j].name} feel alike: $a vs $b")
        }
        assertNotEquals(sig(lang.stop()), sig(lang.obstacle()))
    }

    @Test
    fun `smoothing choices all keep the curve-vs-correction rule`() {
        for (s in Smoothing.values()) {
            val c = StableGuidanceController(StableGuidanceConfig(smoothing = s))
            val evs = run(c, follow(path(kappa = 0.10)), 45)
            assertTrue(evs.none { it.state == GuidanceState.CORRECT_LEFT || it.state == GuidanceState.CORRECT_RIGHT }, "$s")
        }
    }

    private fun abs(x: Long) = if (x < 0) -x else x
    private fun abs(x: Int) = if (x < 0) -x else x
}
