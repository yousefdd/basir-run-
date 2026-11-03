package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeltTest {
    private val belt = BeltLanguage()

    // ---- the language --------------------------------------------------------------------------------------------

    @Test
    fun `direction is where it vibrates - left cues only use left motors, right cues only right ones`() {
        val leftMotors = Motor.maskOf(Motor.M1, Motor.M2, Motor.M3)
        val rightMotors = Motor.maskOf(Motor.M3, Motor.M4, Motor.M5)
        for (lv in Level.values()) {
            assertEquals(0, belt.correct(Side.LEFT, lv).motorsUsed and rightMotors, "correct left $lv")
            assertEquals(0, belt.correct(Side.RIGHT, lv).motorsUsed and leftMotors, "correct right $lv")
            assertEquals(0, belt.curve(Side.LEFT, lv).motorsUsed and Motor.maskOf(Motor.M4, Motor.M5))
            assertEquals(0, belt.curve(Side.RIGHT, lv).motorsUsed and Motor.maskOf(Motor.M1, Motor.M2))
        }
        // a bigger correction is further out: M2 for small, M1 for strong
        assertEquals(Motor.M2.bit, belt.correct(Side.LEFT, Level.LOW).motorsUsed)
        assertEquals(Motor.M1.bit, belt.correct(Side.LEFT, Level.HIGH).motorsUsed)
        assertEquals(Motor.M5.bit, belt.correct(Side.RIGHT, Level.HIGH).motorsUsed)
    }

    @Test
    fun `a bend and a corner move across the belt from the centre outward`() {
        val sharp = belt.curve(Side.RIGHT, Level.HIGH).steps.map { it.mask }
        assertEquals(listOf(Motor.M3.bit, Motor.M4.bit, Motor.M5.bit), sharp)
        val prep = belt.turnPrepare(Side.LEFT).steps
        assertEquals(listOf(Motor.M3.bit, Motor.M2.bit, Motor.M1.bit), prep.map { it.mask })
        assertTrue(prep.zipWithNext().all { (a, b) -> b.amplitude > a.amplitude }, "prepare rises soft -> firm")
    }

    @Test
    fun `only the stop uses all five motors, and every warning differs from every guidance cue`() {
        val all = belt.catalog()
        assertEquals(listOf("stop"), all.filter { it.maxSimultaneous == 5 }.map { it.name })
        val guidance = all.filter { it.priority == BeltPriority.DIRECTION }
        val warnings = all.filter { it.priority >= BeltPriority.DANGER }
        // guidance never uses more than two motors at once; warnings that use the centre rhythm (3 quick pulses)
        assertTrue(guidance.all { it.maxSimultaneous <= 2 })
        for (w in warnings) for (g in guidance) assertTrue(signature(w) != signature(g), "${w.name} vs ${g.name}")
        // no two cues in the catalogue are identical
        assertEquals(all.size, all.map { signature(it) }.toSet().size)
    }

    @Test
    fun `everyday guidance is short, warnings stand out`() {
        for (p in belt.catalog().filter { it.priority == BeltPriority.DIRECTION }) assertTrue(p.durationMs <= 900, "${p.name} ${p.durationMs} ms")
        assertTrue(belt.danger().steps.all { it.amplitude == 255 })
        assertEquals(3, belt.obstacle().steps.size)
        assertTrue(belt.stop().onMs >= 1000)
    }

    @Test
    fun `every phone cue the scheduler can play has a belt cue, and a close obstacle becomes the danger cue`() {
        val phone = HapticLanguage(true)
        for ((p, _, _) in phone.catalog()) assertNotNull(belt.forPattern(p.name), p.name)
        assertEquals("obstacle", belt.forPattern("obstacle", GuidanceCommand(CommandKind.OBSTACLE, level = Level.LOW))!!.name)
        assertEquals("danger", belt.forPattern("obstacle", GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH))!!.name)
        assertEquals("obstacle_left", belt.forPattern("obstacle_left", GuidanceCommand(CommandKind.OBSTACLE, side = Side.LEFT, level = Level.HIGH))!!.name)
        assertNull(belt.forPattern("confirm"))
    }

    @Test
    fun `the current stays within a power bank and the board - at most 5 motors, and only during a stop`() {
        val motorMa = 90.0; val boardMa = 120.0
        val peak = belt.catalog().maxOf { it.maxSimultaneous * motorMa * it.steps.maxOf { s -> s.amplitude } / 255.0 } + boardMa
        assertTrue(peak <= 600, "peak $peak mA")
    }

    private fun signature(p: BeltPattern) = p.steps.joinToString(";") { "${it.mask}/${it.amplitude / 32}/${it.onMs / 40}/${it.offMs / 40}" }

    // ---- the protocol --------------------------------------------------------------------------------------------

    @Test
    fun `frames round-trip and a one-step frame fits the smallest BLE packet`() {
        for (p in belt.catalog()) {
            val f = BeltProtocol.parse(BeltProtocol.play(p, 77, 123_456L))!!
            assertEquals(p.steps, f.steps); assertEquals(77, f.seq); assertEquals(p.priority, f.priority)
            assertEquals(BeltProtocol.ttlFor(p.priority), f.ttlMs)
        }
        assertEquals(20, BeltProtocol.play(belt.correct(Side.LEFT, Level.LOW), 1, 0).size)
        val c = BeltConfig(80, listOf(110, 100, 90, 100, 110), 65, 1200)
        assertEquals(c, BeltProtocol.parse(BeltProtocol.config(c, 2, 5))!!.config)
        assertEquals(BeltProtocol.HEARTBEAT, BeltProtocol.parse(BeltProtocol.heartbeat(3, 9))!!.type)
    }

    @Test
    fun `a corrupted frame is rejected`() {
        val b = BeltProtocol.play(belt.stop(), 5, 1000)
        for (i in b.indices) {
            val c = b.copyOf(); c[i] = (c[i].toInt() xor 0x10).toByte()
            assertNull(BeltProtocol.parse(c), "flipped byte $i")
        }
    }

    // ---- the belt's decisions (reference model of the firmware) ---------------------------------------------------

    private fun play(p: BeltPattern, seq: Int, phoneMs: Long) = BeltProtocol.play(p, seq, phoneMs)

    @Test
    fun `old or repeated frames are dropped, a stale one is never played late`() {
        val m = BeltPlayerModel(); m.onConnect()
        val off = 50_000L   // the belt's clock is 50 s ahead of the phone's
        assertEquals(BeltPlayerModel.Result.HEARTBEAT, m.receive(BeltProtocol.heartbeat(1, 1000), 1000 + off + 20))
        assertEquals(BeltPlayerModel.Result.PLAYING, m.receive(play(belt.correct(Side.LEFT, Level.LOW), 2, 1100), 1100 + off + 25))
        assertEquals(BeltPlayerModel.Result.DROP_OLD_SEQ, m.receive(play(belt.correct(Side.RIGHT, Level.LOW), 2, 1200), 1200 + off + 20))
        // a Bluetooth stall delivers a correction made 900 ms ago: stale, dropped
        assertEquals(BeltPlayerModel.Result.DROP_STALE, m.receive(play(belt.correct(Side.RIGHT, Level.LOW), 3, 1300), 1300 + off + 920))
        // a stop delayed 500 ms is still played (ttl 700), one delayed 800 ms is not
        assertEquals(BeltPlayerModel.Result.PLAYING, m.receive(play(belt.stop(), 4, 2500), 2500 + off + 500))
        assertEquals(BeltPlayerModel.Result.DROP_STALE, m.receive(play(belt.stop(), 5, 6000), 6000 + off + 800))
    }

    @Test
    fun `a lower priority never cuts a higher one, a higher one replaces it at once`() {
        val m = BeltPlayerModel(); m.onConnect()
        assertEquals(BeltPlayerModel.Result.PLAYING, m.receive(play(belt.stop(), 1, 0), 10))
        assertEquals(BeltPlayerModel.Result.DROP_LOWER_PRIORITY, m.receive(play(belt.correct(Side.LEFT, Level.HIGH), 2, 100), 110))
        assertTrue(m.duties(200).all { it > 0 }, "the stop keeps playing on all motors")
        val n = BeltPlayerModel(); n.onConnect()
        n.receive(play(belt.curve(Side.LEFT, Level.LOW), 1, 0), 10)
        assertEquals(BeltPlayerModel.Result.PLAYING, n.receive(play(belt.danger(), 2, 50), 60))
        val d = n.duties(70)
        assertTrue(d[1] > 0 && d[2] > 0 && d[3] > 0 && d[0] == 0 && d[4] == 0, d.joinToString())
    }

    @Test
    fun `the motors follow the steps and stop when the link goes quiet`() {
        val m = BeltPlayerModel(BeltConfig(linkTimeoutMs = 1000)); m.onConnect()
        m.receive(play(belt.correct(Side.LEFT, Level.HIGH), 1, 0), 0)   // M1 200 on, 120 off, 200 on
        assertEquals(listOf(255, 0, 0, 0, 0), m.duties(100).toList())
        assertEquals(listOf(0, 0, 0, 0, 0), m.duties(250).toList())
        assertEquals(255, m.duties(400)[0])
        assertEquals(0, m.duties(600)[0])
        val s = BeltPlayerModel(BeltConfig(linkTimeoutMs = 1000)); s.onConnect()
        s.receive(play(belt.stop(), 1, 0), 0)
        assertTrue(s.duties(500).all { it > 0 })
        s.onDisconnect()
        assertTrue(s.duties(510).all { it == 0 }, "a disconnect silences the belt")
        val w = BeltPlayerModel(BeltConfig(linkTimeoutMs = 300)); w.onConnect()
        w.receive(play(belt.stop(), 1, 0), 0)
        assertTrue(w.duties(400).all { it == 0 }, "no frame for longer than the link timeout: everything off")
    }

    @Test
    fun `intensity, trim and the duty cap scale the motors`() {
        val m = BeltPlayerModel(BeltConfig(intensityPct = 50, trimPct = listOf(100, 100, 100, 100, 140), maxDutyPct = 65))
        assertEquals(128, m.duty(255, 0))
        assertEquals(166, m.duty(255, 4))   // 255*0.5*1.4 = 178 -> capped at 65 % = 166
    }

    // ---- the phone's transmitter ---------------------------------------------------------------------------------

    @Test
    fun `nothing piles up - a newer command replaces a waiting one, a waiting stop is not lost`() {
        val t = BeltTransmitter()
        t.onConnected(0)
        assertNotNull(t.takeNext(0))           // the config goes first
        t.play(belt.correct(Side.LEFT, Level.LOW), 10)
        t.play(belt.correct(Side.RIGHT, Level.LOW), 20)   // the write is still in flight: replaces the waiting left
        t.onWriteDone()
        val f = BeltProtocol.parse(t.takeNext(30)!!)!!
        assertEquals(belt.correct(Side.RIGHT, Level.LOW).steps, f.steps)
        assertNull(t.takeNext(31), "in flight")
        t.play(belt.stop(), 40)
        t.play(belt.correct(Side.LEFT, Level.LOW), 50)     // must not push out the waiting stop
        t.onWriteDone()
        assertEquals(BeltPriority.EMERGENCY, BeltProtocol.parse(t.takeNext(60)!!)!!.priority)
        // a guidance frame that waited longer than its ttl is thrown away, not sent late
        t.play(belt.correct(Side.LEFT, Level.LOW), 100)
        t.onWriteDone()
        assertNull(t.takeNext(100 + 400))
        assertEquals(1, t.discardedStale)
    }

    @Test
    fun `after a reconnect only the config and new frames are sent, and heartbeats keep the link alive`() {
        val t = BeltTransmitter(heartbeatMs = 400)
        t.onConnected(0); t.takeNext(0); t.onWriteDone()
        t.play(belt.correct(Side.LEFT, Level.LOW), 100)
        t.onDisconnected()
        t.onConnected(2000)
        assertEquals(BeltProtocol.CONFIG, BeltProtocol.parse(t.takeNext(2000)!!)!!.type)
        t.onWriteDone()
        assertNull(t.takeNext(2001), "the command from before the disconnect is gone")
        t.tick(2500)
        assertEquals(BeltProtocol.HEARTBEAT, BeltProtocol.parse(t.takeNext(2500)!!)!!.type)
    }

    // ---- the whole chain: engine commands -> scheduler (cooldown, priorities) -> belt --------------------------------

    private class BeltRecorder(val lang: BeltLanguage) : HapticOutput {
        override val hasAmplitudeControl = true
        var cmd: GuidanceCommand? = null
        val played = ArrayList<Pair<Long, BeltPattern>>()
        var now = 0L
        override fun play(pattern: HapticPattern) { lang.forPattern(pattern.name, cmd)?.let { played += now to it } }
        override fun cancel() {}
    }

    @Test
    fun `flickering camera readings do not make the belt flicker`() {
        val rec = BeltRecorder(belt)
        val s = HapticScheduler(rec, HapticLanguage(true), SchedulerConfig(onPathOnlyOnConfirm = true))
        // 10 s at 15 fps: the reading jumps between small left / small right / on path every frame
        val r = java.util.Random(4)
        for (k in 0 until 150) {
            rec.now = k * 67L
            val c = when (r.nextInt(3)) {
                0 -> GuidanceCommand(CommandKind.CORRECT, side = Side.LEFT, level = Level.LOW)
                1 -> GuidanceCommand(CommandKind.CORRECT, side = Side.RIGHT, level = Level.LOW)
                else -> GuidanceCommand(CommandKind.ON_PATH)
            }
            rec.cmd = c
            s.onCommand(c, rec.now)
        }
        val gaps = rec.played.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { it >= 1000 }, "cues closer than the cooldown: $gaps")
        val flips = rec.played.zipWithNext().count { (a, b) -> a.second.name != b.second.name }
        assertTrue(rec.played.size <= 10, "${rec.played.size} cues in 10 s")
        assertTrue(flips <= rec.played.size)
    }

    @Test
    fun `a close danger and a stop pass the cooldown at once`() {
        val rec = BeltRecorder(belt)
        val s = HapticScheduler(rec, HapticLanguage(true), SchedulerConfig(onPathOnlyOnConfirm = true))
        fun send(t: Long, c: GuidanceCommand) { rec.now = t; rec.cmd = c; s.onCommand(c, t) }
        send(0, GuidanceCommand(CommandKind.CORRECT, side = Side.LEFT, level = Level.LOW))
        send(200, GuidanceCommand(CommandKind.OBSTACLE, level = Level.HIGH))
        assertEquals("danger", rec.played.last().second.name)
        assertEquals(200L, rec.played.last().first)
        send(400, GuidanceCommand(CommandKind.STOP))
        assertEquals("stop", rec.played.last().second.name)
        assertEquals(400L, rec.played.last().first)
    }

    @Test
    fun `a corner is announced once to prepare and once to turn, never as a stop`() {
        val rec = BeltRecorder(belt)
        val s = HapticScheduler(rec, HapticLanguage(true), SchedulerConfig(onPathOnlyOnConfirm = true))
        fun send(t: Long, c: GuidanceCommand) { rec.now = t; rec.cmd = c; s.onCommand(c, t) }
        var t = 0L
        repeat(30) { send(t, GuidanceCommand(CommandKind.CURVE, side = Side.LEFT, level = Level.HIGH, corner = CornerPhase.PREPARE_TURN)); t += 67 }
        repeat(20) { send(t, GuidanceCommand(CommandKind.CURVE, side = Side.LEFT, level = Level.HIGH, corner = CornerPhase.TURNING)); t += 67 }
        val names = rec.played.map { it.second.name }
        assertTrue(names.first() == "turn_prepare_left", names.joinToString())
        assertTrue("turn_now_left" in names, names.joinToString())
        assertFalse(names.any { it.startsWith("stop") || it == "danger" })
        assertTrue(names.count { it == "turn_prepare_left" } <= 2 && names.count { it == "turn_now_left" } == 1, names.joinToString())
    }
}
