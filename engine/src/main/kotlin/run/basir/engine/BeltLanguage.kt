package run.basir.engine

import kotlin.math.roundToInt

/**
 * The five belt motors, left to right around the front half of the waist (seen by the wearer).
 * Placement (docs/belt/BELT_HAPTIC_LANGUAGE.md): M3 on the navel line (the body's own centre reference), M2/M4 about
 * 45 degrees to each side, M1/M5 on the flanks above the hip bone (90 degrees). At a 70-90 cm waist that is 9-11 cm
 * between neighbours, in the 8-10 cm range that waist belts in the literature used to keep motors apart.
 */
enum class Motor(val bit: Int, val arabic: String) {
    M1(1, "أقصى اليسار"),
    M2(2, "أمام اليسار"),
    M3(4, "الوسط"),
    M4(8, "أمام اليمين"),
    M5(16, "أقصى اليمين");

    companion object {
        fun maskOf(vararg m: Motor) = m.fold(0) { acc, x -> acc or x.bit }
        val ALL = maskOf(*values())
        fun inMask(mask: Int) = values().filter { mask and it.bit != 0 }
    }
}

/** One step of a belt pattern: these motors at this strength for [onMs], then all of them off for [offMs]. */
data class BeltStep(val mask: Int, val amplitude: Int, val onMs: Int, val offMs: Int = 0) {
    init {
        require(mask in 0..Motor.ALL) { "mask $mask" }
        require(amplitude in 0..255 && onMs in 0..65535 && offMs in 0..65535)
    }
    val motors: Int get() = Integer.bitCount(mask)
}

/** What the belt plays for one cue: an ordered list of steps, the cue's priority and its meaning. */
data class BeltPattern(val name: String, val priority: Int, val steps: List<BeltStep>, val meaningAr: String = "") {
    val durationMs: Long get() = steps.sumOf { (it.onMs + it.offMs).toLong() }
    val onMs: Long get() = steps.sumOf { it.onMs.toLong() }
    val motorsUsed: Int get() = steps.fold(0) { acc, s -> acc or s.mask }
    /** The most motors running at the same moment (for the current budget). */
    val maxSimultaneous: Int get() = steps.maxOfOrNull { it.motors } ?: 0
}

/** The four priority classes of the belt, highest first (the owner's list). */
object BeltPriority {
    const val EMERGENCY = 4     // stop now: all five motors
    const val DANGER = 3        // obstacle close / within stopping distance
    const val DIRECTION = 2     // corrections, bends, corners, caution
    const val CONTINUE = 1      // back on the path (silence is the normal "continue")
}

/**
 * The belt's tactile language. Three rules, so it can be learned in minutes:
 *
 *  1. WHERE it vibrates is the direction. Left motors = go/turn left, right motors = right, centre = straight ahead.
 *     The further out the motor, the bigger the turn (M2 = small correction, M1 = strong correction).
 *  2. HOW it vibrates is the kind of message. One short pulse = correct; a soft movement across the belt = the path bends;
 *     three quick pulses in front = something ahead; all five together = stop.
 *  3. SILENCE means you are on the path. Nothing vibrates while everything is fine.
 *
 * Guidance cues are short (at most about 0.7 s) and use one motor at a time; warnings use the centre or the whole front
 * and a rhythm no guidance cue has; the stop uses every motor and is the only cue that does.
 *
 * The cue names are the phone language's ([HapticLanguage]) names, so the existing scheduler (priorities, cooldown,
 * repeat limits, the latched stop, no stale commands) decides WHEN, and this class only decides WHERE and HOW on the belt.
 */
class BeltLanguage(private val intensity: Double = 1.0) {

    private fun a(fraction: Double) = (255 * fraction * intensity.coerceIn(0.3, 1.0)).roundToInt().coerceIn(1, 255)
    private fun side(s: Side, inner: Boolean) = when (s) {
        Side.LEFT -> if (inner) Motor.M2 else Motor.M1
        Side.RIGHT -> if (inner) Motor.M4 else Motor.M5
    }
    private fun m(vararg x: Motor) = Motor.maskOf(*x)

    // ---- guidance (priority DIRECTION) -----------------------------------------------------------------------------
    fun correct(s: Side, level: Level): BeltPattern = when (level) {
        Level.LOW -> BeltPattern("correct_${s.key}_low", BeltPriority.DIRECTION, listOf(BeltStep(m(side(s, true)), a(0.60), 150)),
            "تصحيح بسيط ${s.ar}")
        Level.MEDIUM -> BeltPattern("correct_${s.key}_medium", BeltPriority.DIRECTION, listOf(BeltStep(m(side(s, true)), a(0.85), 220)),
            "تصحيح متوسط ${s.ar}")
        // the outer motor, twice: "more to the side, now"
        Level.HIGH -> BeltPattern("correct_${s.key}_high", BeltPriority.DIRECTION,
            listOf(BeltStep(m(side(s, false)), a(1.0), 200, 120), BeltStep(m(side(s, false)), a(1.0), 200)), "تصحيح قوي ${s.ar}: أنت تخرج من المسار")
    }

    /** The path bends: a soft movement from the centre toward that side (gentle: two motors, sharp: three). */
    fun curve(s: Side, level: Level): BeltPattern {
        val sharp = level == Level.HIGH
        val amp = a(if (sharp) 0.55 else 0.45)
        val chain = if (sharp) listOf(Motor.M3, side(s, true), side(s, false)) else listOf(Motor.M3, side(s, true))
        return BeltPattern("curve_${s.key}_${level.name.lowercase()}", BeltPriority.DIRECTION,
            chain.mapIndexed { i, mo -> BeltStep(m(mo), amp, 180, if (i == chain.lastIndex) 0 else 20) },
            if (sharp) "منعطف حاد ${s.ar} قادم" else "انحناء تدريجي ${s.ar} قادم")
    }

    /** A corner is coming: the same movement, slower and rising in strength (soft -> firm). */
    fun turnPrepare(s: Side) = BeltPattern("turn_prepare_${s.key}", BeltPriority.DIRECTION, listOf(
        BeltStep(m(Motor.M3), a(0.40), 220, 40), BeltStep(m(side(s, true)), a(0.70), 220, 40), BeltStep(m(side(s, false)), a(1.0), 220)),
        "زاوية قادمة: استعد للانعطاف ${s.ar}")

    /** Turn now: a short tap on the inner motor, then a long firm hold on the outer one ("da-daaa" on that side). */
    fun turnNow(s: Side) = BeltPattern("turn_now_${s.key}", BeltPriority.DIRECTION, listOf(
        BeltStep(m(side(s, true)), a(0.9), 90, 110), BeltStep(m(side(s, false)), a(1.0), 600)), "انعطف الآن ${s.ar}")

    /** The path is briefly unclear: both outer motors, two slow soft pulses ("careful on both sides"), no direction. */
    fun caution() = BeltPattern("caution", BeltPriority.DIRECTION, listOf(
        BeltStep(m(Motor.M1, Motor.M5), a(0.40), 300, 300), BeltStep(m(Motor.M1, Motor.M5), a(0.40), 300)), "الطريق غير واضح: خفّف السرعة")

    // ---- continue (priority CONTINUE) ------------------------------------------------------------------------------
    /** Back on the path after a correction: two faint taps in the centre, then silence again. */
    fun backOnPath() = BeltPattern("back_on_path", BeltPriority.CONTINUE, listOf(
        BeltStep(m(Motor.M3), a(0.35), 60, 90), BeltStep(m(Motor.M3), a(0.35), 60)), "عدت إلى المسار")

    /** Optional faint tick on the path (off in the app: silence is "continue"). */
    fun onPath() = BeltPattern("on_path", BeltPriority.CONTINUE, listOf(BeltStep(m(Motor.M3), a(0.25), 50)), "أنت على المسار")

    // ---- warnings (priority DANGER) --------------------------------------------------------------------------------
    /** Something on the path ahead, not yet close: three quick pulses in the centre. */
    fun obstacle() = BeltPattern("obstacle", BeltPriority.DANGER, quick(m(Motor.M3), a(0.75)), "عائق أمامك")

    /** Obstacle ahead and a way around it on [s]: the obstacle rhythm, then one pulse on the side to pass. */
    fun obstacleDetour(s: Side) = BeltPattern("obstacle_${s.key}", BeltPriority.DANGER,
        quick(m(Motor.M3), a(0.75)).dropLast(1) + listOf(BeltStep(m(Motor.M3), a(0.75), 100, 300), BeltStep(m(side(s, true)), a(0.9), 220)),
        "عائق أمامك، تجاوزه من ${s.fromAr}")

    /** Danger close (within stopping distance): the whole front, three quick strong pulses. */
    fun danger() = BeltPattern("danger", BeltPriority.DANGER, quick(m(Motor.M2, Motor.M3, Motor.M4), a(1.0)), "خطر قريب أمامك: خفّف فوراً")

    // ---- emergency (priority EMERGENCY) ----------------------------------------------------------------------------
    /** Stop now: every motor, one long continuous buzz with no break (the phone's stop). The only all-five cue. */
    fun stop() = BeltPattern("stop", BeltPriority.EMERGENCY, listOf(BeltStep(Motor.ALL, a(0.9), 1100)), "توقف فوراً")

    /** Stop, then which way the ground goes on: the stop, a pause, then a long pulse on that side's outer motor. */
    fun stopTurn(s: Side) = BeltPattern("stop_turn_${s.key}", BeltPriority.EMERGENCY,
        listOf(BeltStep(Motor.ALL, a(0.9), 1100, 600), BeltStep(m(side(s, false)), a(0.8), 400)),
        "توقّف، ثم الطريق ${s.ar}")

    /** Motor check for the training screen: one motor alone, a clear pulse. */
    fun motorTest(mo: Motor) = BeltPattern("test_${mo.name}", BeltPriority.EMERGENCY, listOf(BeltStep(m(mo), a(0.85), 400)), mo.arabic)

    /**
     * The belt pattern for a phone-language pattern the scheduler decided to play now. The command (when known) tells a
     * close obstacle (level HIGH) from a possible one, which share the phone's "obstacle" pattern.
     */
    fun forPattern(phoneName: String, cmd: GuidanceCommand? = null): BeltPattern? {
        val parts = phoneName.split("_")
        fun sideOf(s: String) = when (s) { "left" -> Side.LEFT; "right" -> Side.RIGHT; else -> null }
        fun levelOf(s: String?) = when (s) { "medium" -> Level.MEDIUM; "high" -> Level.HIGH; else -> Level.LOW }
        return when {
            phoneName == "stop" -> stop()
            phoneName.startsWith("stop_turn_") -> sideOf(parts.last())?.let { stopTurn(it) }
            phoneName == "obstacle" -> if (cmd?.level == Level.HIGH) danger() else obstacle()
            phoneName.startsWith("obstacle_") -> sideOf(parts.last())?.let { if (cmd?.level == Level.HIGH && cmd.side == null) danger() else obstacleDetour(it) }
            phoneName.startsWith("correct_") -> sideOf(parts.getOrNull(1) ?: "")?.let { correct(it, levelOf(parts.getOrNull(2))) }
            phoneName.startsWith("curve_") -> sideOf(parts.getOrNull(1) ?: "")?.let { curve(it, levelOf(parts.getOrNull(2))) }
            phoneName.startsWith("turn_prepare_") -> sideOf(parts.last())?.let { turnPrepare(it) }
            phoneName.startsWith("turn_now_") -> sideOf(parts.last())?.let { turnNow(it) }
            phoneName == "caution" -> caution()
            phoneName == "back_on_path" -> backOnPath()
            phoneName == "on_path" -> onPath()
            else -> null
        }
    }

    /** Every cue with its meaning, in teaching order (the training screen and the recognition quiz use it). */
    fun catalog(): List<BeltPattern> = listOf(
        correct(Side.LEFT, Level.LOW), correct(Side.RIGHT, Level.LOW),
        correct(Side.LEFT, Level.HIGH), correct(Side.RIGHT, Level.HIGH),
        curve(Side.LEFT, Level.LOW), curve(Side.RIGHT, Level.LOW),
        turnPrepare(Side.LEFT), turnPrepare(Side.RIGHT),
        turnNow(Side.LEFT), turnNow(Side.RIGHT),
        obstacle(), obstacleDetour(Side.LEFT), obstacleDetour(Side.RIGHT),
        danger(), caution(), backOnPath(), stop(),
    )

    private fun quick(mask: Int, amp: Int) = listOf(BeltStep(mask, amp, 100, 80), BeltStep(mask, amp, 100, 80), BeltStep(mask, amp, 100))

    private val Side.key get() = name.lowercase()
    private val Side.ar get() = if (this == Side.LEFT) "إلى اليسار" else "إلى اليمين"
    private val Side.fromAr get() = if (this == Side.LEFT) "اليسار" else "اليمين"
}
