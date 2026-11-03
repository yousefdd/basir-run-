package run.basir.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * The four guidance states the runner can be in (owner's list, round 8):
 *  - GUIDANCE_ACTIVE: the path is known, nothing to correct (quiet, or a faint on-path tick);
 *  - GUIDANCE_CORRECTION: directional cues (correction, bend, corner, way round an obstacle) — never a stop;
 *  - GUIDANCE_CAUTION: vision briefly uncertain or something ahead: slow down, no guessed direction;
 *  - EMERGENCY_STOP: a real danger, or no data to guide on for too long.
 */
enum class GuidanceMode { GUIDANCE_ACTIVE, GUIDANCE_CORRECTION, GUIDANCE_CAUTION, EMERGENCY_STOP }

fun guidanceModeOf(c: GuidanceCommand?): GuidanceMode = when (c?.kind) {
    CommandKind.STOP -> GuidanceMode.EMERGENCY_STOP
    CommandKind.CAUTION -> GuidanceMode.GUIDANCE_CAUTION
    CommandKind.OBSTACLE -> if (c.side != null) GuidanceMode.GUIDANCE_CORRECTION else GuidanceMode.GUIDANCE_CAUTION
    CommandKind.CORRECT, CommandKind.CURVE -> GuidanceMode.GUIDANCE_CORRECTION
    else -> GuidanceMode.GUIDANCE_ACTIVE
}

/** Command priority (owner's order, round 8): critical stop > obstacle crossing the path > corner > clear correction > slight correction > keep going. */
fun guidancePriority(c: GuidanceCommand?): Int = when {
    c == null -> 0
    c.kind == CommandKind.STOP -> 7
    c.kind == CommandKind.OBSTACLE -> 6
    c.kind == CommandKind.CAUTION -> 5
    c.kind == CommandKind.CURVE && c.corner != null -> 4
    c.kind == CommandKind.CORRECT && c.level != Level.LOW -> 3
    c.kind == CommandKind.CORRECT -> 2
    c.kind == CommandKind.CURVE -> 1
    else -> 0
}

data class DecisionStabilityConfig(
    /** Voting window at running speed (2.5 m/s); longer when slower or when tracking is weak, never below/above the bounds. */
    val windowS: Double = 0.9,
    val minWindowS: Double = 0.6,
    val maxWindowS: Double = 1.5,
    /** Share of the directional vote the opposite side needs before a left<->right change is even considered ... */
    val flipShare: Double = 0.7,
    /** ... and how long it must have been dominant (adaptive, see [confirmS]). */
    val flipConfirmS: Double = 0.8,
    /** A directional cue needs this share of the whole vote (otherwise "on path" or no change). */
    val directionalShare: Double = 0.45,
    /** Strong correction (leaving the path): passes after this many frames of agreeing evidence, never on one frame. */
    val strongFrames: Int = 2,
    /** Escalation to a caution passes after this many frames (one dark frame is not a caution). */
    val cautionFrames: Int = 2,
    /** Minimum time a command is held before a calmer or different one may replace it (s), per class, at 2.5 m/s. */
    val holdSlightS: Double = 1.2,
    val holdMediumS: Double = 0.9,
    val holdStrongS: Double = 0.5,
    val holdCornerS: Double = 1.5,
    val holdBendS: Double = 1.0,
    val holdOnPathS: Double = 0.6,
    val holdCautionS: Double = 0.5,
    /** How long the side last given is remembered: within it, the other side must win the vote and persist. */
    val sideMemoryS: Double = 2.5,
    /**
     * An obstacle warning ends only after the calmer state has lasted this long (at 2.5 m/s; adaptive with speed). Its
     * start is never delayed. Measured on the replays: obstacle evidence flickers on/off every few frames, which made
     * the command stream alternate warning/calm several times a second.
     */
    val obstacleReleaseS: Double = 0.4,
    /** A possible (not yet close) obstacle warning must be the command of this many frames in a row and of half the vote. */
    val possibleObstacleFrames: Int = 3,
    /** After a warning or caution ends, the faint "on path" tick stays quiet this long. */
    val quietAfterWarningS: Double = 1.5,
)

/** One frame's output: the command for the scheduler plus why it is what it is. */
data class StableGuidance(
    val command: GuidanceCommand?,
    val mode: GuidanceMode,
    /** The command actually held (the output can be null, i.e. quiet, while a held cue waits for its repeat). */
    val held: GuidanceCommand?,
    val changed: Boolean,
    val reason: String,
    val lastChangeMs: Long,
)

/**
 * Adaptive Guidance Cooldown Manager: how long the command now held must last before a different, not more urgent
 * command may replace it. The base time depends on the class (a slight correction is held longer than a strong one,
 * a corner cue longest so the runner has time to understand and act), and adapts to speed (faster running: less time
 * to the next event, shorter hold) and to tracking stability (weak tracking: evidence for a change is less certain,
 * longer hold). Safety events (STOP, obstacles, cautions rising) are never held back by it. It records the time and
 * reason of the last change.
 */
class AdaptiveGuidanceCooldownManager(private val config: DecisionStabilityConfig = DecisionStabilityConfig()) {
    var lastChangeMs = Long.MIN_VALUE / 4
        private set
    var lastReason = ""
        private set
    var changes = 0
        private set

    fun holdS(held: GuidanceCommand?, speedMps: Double, trackConfidence: Double): Double {
        val base = when {
            held == null || held.kind == CommandKind.ON_PATH -> config.holdOnPathS
            held.kind == CommandKind.CURVE && held.corner != null -> config.holdCornerS
            held.kind == CommandKind.CURVE -> config.holdBendS
            held.kind == CommandKind.CORRECT && held.level == Level.HIGH -> config.holdStrongS
            held.kind == CommandKind.CORRECT && held.level == Level.MEDIUM -> config.holdMediumS
            held.kind == CommandKind.CORRECT -> config.holdSlightS
            held.kind == CommandKind.CAUTION -> config.holdCautionS
            else -> 0.0 // OBSTACLE and STOP are released by their own rules (planner, scheduler latch)
        }
        val speed = (2.5 / max(speedMps, 0.5)).coerceIn(0.75, 1.3)
        val stability = 1.0 + 0.4 * (1.0 - trackConfidence.coerceIn(0.0, 1.0))
        return base * speed * stability
    }

    fun ready(nowMs: Long, held: GuidanceCommand?, speedMps: Double, trackConfidence: Double): Boolean =
        (nowMs - lastChangeMs) / 1000.0 + 1e-9 >= holdS(held, speedMps, trackConfidence)

    fun record(nowMs: Long, reason: String) { lastChangeMs = nowMs; lastReason = reason; changes++ }

    fun reset() { lastChangeMs = Long.MIN_VALUE / 4; lastReason = ""; changes = 0 }
}

/**
 * Dominant Guidance Command Engine: instead of following the command of each camera frame, keeps a short history of
 * commands and computes the dominant one by weighted temporal voting. Each frame votes for its (kind, side) with a
 * weight from its recency (exponential, half the window), its confidence and its strength (a strong correction or a
 * corner counts more than a slight one). The side vote is pooled over corrections, bends and corners, so "slight right,
 * bend right, slight right" is one right, and a lone left frame in it is outvoted.
 */
class DominantGuidanceCommandEngine(private val config: DecisionStabilityConfig = DecisionStabilityConfig()) {
    private data class Vote(val tMs: Long, val cmd: GuidanceCommand?, val weight: Double)
    private val votes = ArrayDeque<Vote>()

    data class Tally(val left: Double, val right: Double, val calm: Double, val caution: Double, val total: Double, val obstacle: Double = 0.0) {
        val directional get() = left + right
        fun share(side: Side) = if (directional <= 1e-9) 0.0 else (if (side == Side.LEFT) left else right) / directional
        val dominantSide: Side? get() = when { directional <= 1e-9 -> null; left >= right -> Side.LEFT; else -> Side.RIGHT }
    }

    fun windowS(speedMps: Double, trackConfidence: Double): Double =
        (config.windowS * (2.5 / max(speedMps, 0.5)).pow(0.5) * (1 + 0.5 * (1 - trackConfidence.coerceIn(0.0, 1.0))))
            .coerceIn(config.minWindowS, config.maxWindowS)

    fun add(cmd: GuidanceCommand?, nowMs: Long) {
        val strength = when {
            cmd == null -> 0.8
            cmd.kind == CommandKind.CURVE && cmd.corner != null -> 2.0
            cmd.kind == CommandKind.CORRECT -> when (cmd.level) { Level.HIGH -> 2.5; Level.MEDIUM -> 1.5; else -> 1.0 }
            cmd.kind == CommandKind.CURVE -> 0.9
            cmd.kind == CommandKind.CAUTION -> 1.2
            else -> 1.0
        }
        val conf = if (cmd == null || cmd.confidence <= 0.0) 1.0 else (0.5 + 0.5 * cmd.confidence.coerceIn(0.0, 1.0))
        votes.addLast(Vote(nowMs, cmd, strength * conf))
    }

    fun tally(nowMs: Long, speedMps: Double, trackConfidence: Double): Tally {
        val w = windowS(speedMps, trackConfidence)
        while (votes.isNotEmpty() && (nowMs - votes.first().tMs) / 1000.0 > w) votes.removeFirst()
        var l = 0.0; var r = 0.0; var calm = 0.0; var caution = 0.0; var obstacle = 0.0
        val tau = w / 2
        for (v in votes) {
            val k = v.weight * exp(-((nowMs - v.tMs) / 1000.0) / tau)
            val c = v.cmd
            when {
                c != null && (c.kind == CommandKind.CORRECT || c.kind == CommandKind.CURVE) && c.side == Side.LEFT -> l += k
                c != null && (c.kind == CommandKind.CORRECT || c.kind == CommandKind.CURVE) && c.side == Side.RIGHT -> r += k
                c != null && c.kind == CommandKind.CAUTION -> caution += k
                c != null && c.kind == CommandKind.OBSTACLE -> obstacle += k
                else -> calm += k
            }
        }
        return Tally(l, r, calm, caution, l + r + calm + caution + obstacle, obstacle)
    }

    /** Frames in a row (newest first) whose command satisfies [p]. */
    fun streak(p: (GuidanceCommand?) -> Boolean): Int {
        var n = 0
        for (k in votes.indices.reversed()) { if (p(votes[k].cmd)) n++ else break }
        return n
    }

    fun reset() = votes.clear()
}

/**
 * Guidance Decision Stability Engine (round 8). Sits between the decision layer and the vibration scheduler and keeps
 * the decision when it is still right and supported:
 *  - Safety first, never delayed: STOP passes in both directions on the frame it is decided (the scheduler latches and
 *    releases it); an obstacle warning (more urgent than any steering) passes at once. Only its *side* (the way round)
 *    must persist before it is named, so the way round never flips left/right on one frame.
 *  - Direction persistence + hysteresis: the opposite side replaces a side only when it holds [DecisionStabilityConfig.flipShare]
 *    of the weighted directional vote for an adaptive confirmation time — whatever came in between (side memory) —
 *    and the cooldown of the cue held now is over.
 *  - Consecutive-frame confirmation: no change of decision from one frame; a strong correction (about to leave the
 *    path) needs [DecisionStabilityConfig.strongFrames] agreeing frames, a caution [DecisionStabilityConfig.cautionFrames].
 *  - Temporal confidence smoothing and adaptive thresholds: votes are weighted by confidence and recency; the window and
 *    the hold times grow when tracking is weak and shrink at higher speed.
 *  - Event filtering / dedup: a cue that is already held keeps flowing unchanged (the scheduler rate-limits its
 *    repeats), a level change within the same side is an update, not a new event.
 *  - Decision state management: every output carries one of the four [GuidanceMode]s and the reason it changed.
 */
class GuidanceDecisionStabilityEngine(private val config: DecisionStabilityConfig = DecisionStabilityConfig()) {
    val voting = DominantGuidanceCommandEngine(config)
    val cooldown = AdaptiveGuidanceCooldownManager(config)
    private var held: GuidanceCommand? = null
    private var heldSet = false
    private var lastSide: Side? = null
    private var lastSideMs = Long.MIN_VALUE / 4
    private var oppositeSinceMs: Long? = null
    private var obstacleSide: Side? = null
    private var obstacleSideFrames = 0
    private var warningEndedMs: Long? = null
    private var calmSinceMs: Long? = null
    private var lastNowMs = 0L

    fun reset() {
        calmSinceMs = null
        voting.reset(); cooldown.reset(); held = null; heldSet = false; lastSide = null; lastSideMs = Long.MIN_VALUE / 4
        oppositeSinceMs = null; obstacleSide = null; obstacleSideFrames = 0; warningEndedMs = null
    }

    fun update(cmd: GuidanceCommand?, nowMs: Long, speedMps: Double = 2.5, trackConfidence: Double = 1.0): StableGuidance {
        lastNowMs = nowMs
        voting.add(cmd, nowMs)
        val t = voting.tally(nowMs, speedMps, trackConfidence)
        lastTally = t
        val cur = held

        // ---- 1. Safety: never delayed --------------------------------------------------------------------------
        if (cmd?.kind == CommandKind.STOP) return change(cmd, "stop")
        if (cur?.kind == CommandKind.STOP) return change(cmd, "stop released by the planner")
        if (cmd?.kind == CommandKind.OBSTACLE && cmd.level != Level.HIGH && cmd.side == null && cur?.kind != CommandKind.OBSTACLE) {
            // Round 8 (owner): a "possible" obstacle (beyond stopping distance, no way round named) is a command like any
            // other: it is given only when it is the dominant command of the last frames, never from a flicker. A close
            // obstacle (level HIGH, within stopping distance), a way round and STOP are not voted: they pass at once.
            val n = voting.streak { it?.kind == CommandKind.OBSTACLE }
            if (n < config.possibleObstacleFrames || t.obstacle < 0.5 * t.total) return hold(cur, "possible obstacle not dominant yet")
        }
        if (cmd?.kind == CommandKind.OBSTACLE) {
            calmSinceMs = null
            // The warning itself at once; the way round only once it has been the same side for a few frames
            val s = cmd.side
            if (s == null) { obstacleSide = null; obstacleSideFrames = 0 }
            else if (s == obstacleSide) obstacleSideFrames++ else { obstacleSide = s; obstacleSideFrames = 1 }
            val sideOk = s != null && obstacleSideFrames >= 3 && !(opposesMemory(s, nowMs) && obstacleSideFrames < 8)
            val out = if (sideOk) cmd else cmd.copy(side = null)
            if (cur?.kind == CommandKind.OBSTACLE && cur.side == out.side) return keep(out, "obstacle (held)")
            return change(out, if (sideOk) "obstacle, way round ${s}" else "obstacle")
        }
        if (cur?.kind == CommandKind.OBSTACLE) {
            // Leaving a warning: only once the calmer state has lasted obstacleReleaseS (event filtering of flicker)
            // ... but a clear directional cue that dominates the vote is never held back by it (guidance continues).
            val calmSince = calmSinceMs ?: nowMs.also { calmSinceMs = it }
            val need = config.obstacleReleaseS * (2.5 / max(speedMps, 0.5)).coerceIn(0.75, 1.3)
            val ds = t.dominantSide
            val directionWins = ds != null && t.directional >= config.directionalShare * t.total && t.share(ds) >= 0.6
            if (!directionWins && (nowMs - calmSince) / 1000.0 + 1e-9 < need) return keep(cur, "obstacle release pending")
            warningEndedMs = nowMs
        }
        calmSinceMs = null
        obstacleSide = null; obstacleSideFrames = 0

        // ---- 2. Caution: escalates after a couple of frames, released when calm is dominant ------------------------
        if (cmd?.kind == CommandKind.CAUTION) {
            if (cur?.kind == CommandKind.CAUTION) return keep(cmd, "caution (held)")
            val n = voting.streak { it?.kind == CommandKind.CAUTION }
            if (n >= config.cautionFrames || guidancePriority(cur) < 2 && n >= 1 && t.caution >= 0.6 * t.total)
                return change(cmd, "caution: vision uncertain")
            return hold(cur, "caution not confirmed yet")
        }

        // ---- 3. Directional and calm commands: dominant command by vote, with hysteresis and cooldown -------------
        val wanted = dominant(cmd, t)
        if (sameCue(wanted, cur)) return keep(wanted, "same cue")
        val urgent = wanted != null && wanted.kind == CommandKind.CORRECT && wanted.level == Level.HIGH &&
            voting.streak { it?.kind == CommandKind.CORRECT && it.level == Level.HIGH && it.side == wanted.side } >= config.strongFrames
        val cornerTurn = wanted?.corner == CornerPhase.TURNING && cur?.corner == CornerPhase.PREPARE_TURN && wanted.side == cur.side
        val ws = wanted?.side
        if (ws != null && (opposesMemory(ws, nowMs) || (cur?.side != null && cur.side != ws))) {
            // Left <-> right: the other side must win the vote clearly and keep winning
            if (t.share(ws) < config.flipShare) { oppositeSinceMs = null; return hold(cur, "opposite side not dominant") }
            val since = oppositeSinceMs ?: nowMs.also { oppositeSinceMs = it }
            val need = confirmS(speedMps, trackConfidence) * (if (urgent) 0.5 else 1.0)
            if ((nowMs - since) / 1000.0 + 1e-9 < need) return hold(cur, "side change waiting for confirmation")
            if (!urgent && !cooldown.ready(nowMs, cur, speedMps, trackConfidence)) return hold(cur, "cooldown")
            oppositeSinceMs = null
            return change(wanted, "side change to $ws confirmed")
        }
        oppositeSinceMs = null
        val escalation = guidancePriority(wanted) > guidancePriority(cur)
        if (cornerTurn || urgent) return change(wanted, if (urgent) "strong correction" else "turn now")
        if (escalation && wanted != null) {
            // a more important cue of the same side (slight -> clear correction, bend -> corner): two frames of support
            val n = voting.streak { sameCue(it, wanted) || (it?.side == wanted.side && guidancePriority(it) >= guidancePriority(wanted)) }
            if (n >= 2) return change(wanted, "escalation")
            return hold(cur, "escalation not confirmed yet")
        }
        if (!cooldown.ready(nowMs, cur, speedMps, trackConfidence)) return hold(cur, "cooldown")
        return change(wanted, if (wanted == null || wanted.kind == CommandKind.ON_PATH) "back to calm" else "change")
    }

    /** The command the vote supports now (a directional cue only when its side dominates the vote). */
    private fun dominant(cmd: GuidanceCommand?, t: DominantGuidanceCommandEngine.Tally): GuidanceCommand? {
        val side = t.dominantSide
        val directionalOk = side != null && t.directional >= config.directionalShare * t.total && t.share(side) >= 0.55
        if (!directionalOk) {
            // Calm wins: on path (or quiet). A corner-exit confirmation passes through as calm.
            return if (cmd == null || cmd.kind == CommandKind.ON_PATH) cmd else cmd.copy(kind = CommandKind.ON_PATH, side = null, level = null, corner = null)
        }
        // The frame's own command when it agrees with the dominant side; otherwise the cue held for that side continues.
        if (cmd != null && cmd.side == side && (cmd.kind == CommandKind.CORRECT || cmd.kind == CommandKind.CURVE)) return cmd
        val cur = held
        if (cur != null && cur.side == side) return cur.copy(confidence = cmd?.confidence ?: cur.confidence)
        // dominant side but this frame disagrees and nothing is held for it: a slight correction toward the dominant side
        return GuidanceCommand(CommandKind.CORRECT, side, Level.LOW, confidence = cmd?.confidence ?: 0.5)
    }

    private fun confirmS(speedMps: Double, trackConfidence: Double): Double =
        (config.flipConfirmS * (2.5 / max(speedMps, 0.5)).coerceIn(0.7, 1.4) * (1 + 0.5 * (1 - trackConfidence.coerceIn(0.0, 1.0)))).coerceIn(0.5, 1.6)

    private fun opposesMemory(s: Side, nowMs: Long): Boolean {
        val ls = lastSide ?: return false
        return ls != s && (nowMs - lastSideMs) / 1000.0 < config.sideMemoryS
    }

    private fun sameCue(a: GuidanceCommand?, b: GuidanceCommand?): Boolean {
        if (a == null || b == null) return a == null && b == null || (a?.kind == CommandKind.ON_PATH && b == null) || (b?.kind == CommandKind.ON_PATH && a == null)
        return a.kind == b.kind && a.side == b.side && a.corner == b.corner && (a.kind != CommandKind.CORRECT || a.level == b.level)
    }

    private fun change(cmd: GuidanceCommand?, reason: String): StableGuidance {
        val was = held
        if (was != null && (was.kind == CommandKind.OBSTACLE || was.kind == CommandKind.CAUTION) && guidancePriority(cmd) < guidancePriority(was)) warningEndedMs = lastNowMs
        held = cmd; heldSet = true
        if (cmd?.side != null && (cmd.kind == CommandKind.CORRECT || cmd.kind == CommandKind.CURVE)) { lastSide = cmd.side; lastSideMs = lastNowMs }
        cooldown.record(lastNowMs, reason)
        return StableGuidance(quiet(cmd), guidanceModeOf(cmd), cmd, true, reason, cooldown.lastChangeMs)
    }

    private fun keep(cmd: GuidanceCommand?, reason: String): StableGuidance {
        held = cmd
        if (cmd?.side != null && (cmd.kind == CommandKind.CORRECT || cmd.kind == CommandKind.CURVE)) { lastSide = cmd.side; lastSideMs = lastNowMs }
        return StableGuidance(quiet(cmd), guidanceModeOf(cmd), cmd, false, reason, cooldown.lastChangeMs)
    }

    /**
     * Hold the current cue. While held, a directional cue keeps flowing (the scheduler decides on its repeats) only
     * while the vote still supports its side; when the evidence now leans the other way, nothing new is sent (no
     * vibration for a side that may be wrong, and none for the side not yet confirmed).
     */
    private fun hold(cur: GuidanceCommand?, reason: String): StableGuidance {
        val s = cur?.side
        val supported = s == null || lastTally?.let { it.share(s) >= 0.5 && it.directional > 0.0 } ?: true
        val out = if (supported) quiet(cur) else null
        return StableGuidance(out, guidanceModeOf(cur), cur, false, if (supported) reason else "$reason (held quietly)", cooldown.lastChangeMs)
    }

    private var lastTally: DominantGuidanceCommandEngine.Tally? = null

    private fun quiet(cmd: GuidanceCommand?): GuidanceCommand? {
        val ended = warningEndedMs ?: return cmd
        if (cmd?.kind == CommandKind.ON_PATH && !cmd.confirm && (lastNowMs - ended) / 1000.0 < config.quietAfterWarningS) return null
        return cmd
    }
}
