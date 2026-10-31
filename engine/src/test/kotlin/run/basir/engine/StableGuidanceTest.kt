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
    fun `a short loss of the path is held quietly without a direction, a longer one stops`() {
        val c = StableGuidanceController()
        run(c, follow(path(offset = 0.7)), 20)
        val held = run(c, stop(StopReason.LOW_CONFIDENCE), 5) // 0.33 s
        assertTrue(held.all { it.command.kind == CommandKind.ON_PATH && it.command.side == null }, "no direction from an unsure frame")
        assertTrue(held.all { it.state == GuidanceState.VISION_UNCERTAIN })
        val longer = run(c, stop(StopReason.LOW_CONFIDENCE), 5)
        assertEquals(CommandKind.STOP, longer.last().command.kind, "after the hold time the runner is stopped")
        val lost = StableGuidanceController()
        run(lost, follow(path()), 10)
        assertEquals(GuidanceState.TRACK_LOST, run(lost, stop(StopReason.NO_PATH), 10).last().state)
    }

    @Test
    fun `running stops sooner than walking when the path is not trusted`() {
        fun framesToStop(speed: Double): Int {
            val c = StableGuidanceController(); run(c, follow(path()), 10, speed)
            return (1..30).first { c.update(stop(StopReason.LOW_CONFIDENCE), speed, dt).command.kind == CommandKind.STOP }
        }
        assertTrue(framesToStop(2.8) < framesToStop(1.4))
    }

    @Test
    fun `a stop for an obstacle without a way around wins at once over anything`() {
        val c = StableGuidanceController()
        run(c, follow(path(offset = 0.7)), 20)
        val e = c.update(stop(StopReason.OBSTACLE_NO_ALTERNATIVE), 1.4, dt)
        assertEquals(GuidanceState.STOP_REQUIRED, e.state)
        assertEquals(CommandKind.STOP, e.command.kind)
        assertEquals(4, e.severity)
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
