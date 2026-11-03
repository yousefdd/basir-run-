package run.basir.engine

data class GuidanceStabilizerConfig(
    /** A warning or caution ends only after the calmer state has lasted this long (it always starts at once).
     *  0 by default: measured on the labelled clips, any hold (even 0.13 s) turned the flickering obstacle evidence into
     *  longer false warnings on clear ground (alarm frames on clear leaf-covered ground 0.209 -> 0.261 at 0.13 s,
     *  0.311 at 0.3 s); the flicker must be fixed in the evidence, not hidden by holding the warning. */
    val obstacleReleaseS: Double = 0.0,
    val cautionReleaseS: Double = 0.0,
    /** Left <-> right (corrections, bends, the side of a way round) only after the other side has lasted this long. */
    val sideFlipS: Double = 1.0,
    /** Round 7: how long the side last given is remembered; within it the opposite side must persist [sideFlipS] even
     *  when a calmer cue came in between (one stray frame never flips the direction). */
    val sideMemoryS: Double = 2.0,  // 0 = the 0.13.0 behaviour (persistence only against the command held now)
    /** A calm cue (on path, correction, bend) replaces another calm cue only after it has lasted this long. */
    val calmChangeS: Double = 0.4,
    /** After a warning or caution ends, the faint "on path" tick stays quiet this long (no "go" buzz right after a warning). */
    val quietAfterWarningS: Double = 1.5,
)

/**
 * Guidance Temporal Stabilizer (research round 6b). Sits between the decision layer and the vibration scheduler.
 *
 * Measured on the 0.12.2 replays: the decision layer flipped between "obstacle near" and "on path" from one frame to
 * the next (the app's obstacle level rises and falls frame by frame), about 70 cue changes a minute, many lasting a
 * single frame. This stage gives the command stream temporal rules:
 *  - escalation is immediate: a more serious command (STOP > obstacle > caution > correction > bend > on path)
 *    passes on the frame it is decided; safety is never delayed or smoothed;
 *  - de-escalation: a warning or caution can be held until the calmer state has lasted
 *    [GuidanceStabilizerConfig.obstacleReleaseS] / [GuidanceStabilizerConfig.cautionReleaseS] (0 by default, see there);
 *  - no "go" buzz right after a warning: the faint on-path tick stays quiet for [GuidanceStabilizerConfig.quietAfterWarningS]
 *    (on the replays this removed nearly all "obstacle, then on path" vibration pairs within 2 s);
 *  - direction persistence: the opposite side replaces a side only after [GuidanceStabilizerConfig.sideFlipS],
 *    except a strong (HIGH) correction, which means the runner is about to leave the path;
 *  - calm cues change only after a short confirmation; identical repeats pass (the scheduler rate-limits them).
 * STOP is passed through unchanged in both directions: the planner and the scheduler already hold and release it.
 */
class GuidanceTemporalStabilizer(private val config: GuidanceStabilizerConfig = GuidanceStabilizerConfig()) {
    private var held: GuidanceCommand? = null
    private var candidate: GuidanceCommand? = null
    private var candidateSinceMs = 0L
    private var warningEndedMs: Long? = null

    fun reset() { held = null; candidate = null; warningEndedMs = null; lastSide = null }

    fun update(cmd: GuidanceCommand?, nowMs: Long): GuidanceCommand? {
        lastNowMs = nowMs
        val cur = held
        if (cur == null || cmd?.kind == CommandKind.STOP || cur.kind == CommandKind.STOP) {
            return accept(cmd)
        }
        val rNew = rank(cmd); val rCur = rank(cur)
        // Round 7 fix: direction persistence is about the side last GIVEN, not only the command held now. Before, a
        // right correction, then an "on path" (accepted after calmChangeS), then a left correction (an escalation over
        // "on path") flipped the side within ~0.5 s. A calm directional cue for the other side must now persist
        // sideFlipS whatever came in between; a strong correction, obstacles and STOP are not delayed.
        if (opposesRecentSide(cmd, nowMs)) {
            if (candidate == null || !same(candidate!!, cmd)) { candidate = cmd; candidateSinceMs = nowMs }
            if ((nowMs - candidateSinceMs) / 1000.0 + 1e-9 < config.sideFlipS) return quiet(held)
            return accept(cmd)
        }
        if (rNew > rCur) return accept(cmd)                                   // escalation: at once
        if (rNew == rCur && cmd != null && sameDirection(cmd, cur)) return accept(cmd) // same cue (updated level/strength)
        if (cmd == null && cur.kind == CommandKind.ON_PATH) return accept(cmd)           // calm -> silent: nothing to protect
        if (cmd != null && cmd.kind == CommandKind.CORRECT && cmd.level == Level.HIGH) return accept(cmd) // leaving the path
        // A change that is not more serious: it must persist
        if (candidate == null || !same(candidate!!, cmd)) { candidate = cmd; candidateSinceMs = nowMs }
        val lasted = (nowMs - candidateSinceMs) / 1000.0
        val need = when {
            cur.kind == CommandKind.OBSTACLE && rNew < rCur -> config.obstacleReleaseS
            cur.kind == CommandKind.CAUTION && rNew < rCur -> config.cautionReleaseS
            cmd != null && cmd.side != null && cur.side != null && cmd.side != cur.side -> config.sideFlipS
            else -> config.calmChangeS
        }
        if (lasted + 1e-9 < need) return quiet(held)
        if (cur.kind == CommandKind.OBSTACLE || cur.kind == CommandKind.CAUTION) warningEndedMs = nowMs
        return accept(cmd)
    }

    private fun accept(cmd: GuidanceCommand?): GuidanceCommand? {
        held = cmd; candidate = null
        if (cmd?.side != null && (cmd.kind == CommandKind.CORRECT || cmd.kind == CommandKind.CURVE)) { lastSide = cmd.side; lastSideMs = lastNowMs }
        return quiet(cmd)
    }

    private var lastSide: Side? = null
    private var lastSideMs = 0L

    /** A correction or bend cue (not a strong correction) for the side opposite to one given less than sideFlipS ago. */
    private fun opposesRecentSide(cmd: GuidanceCommand?, nowMs: Long): Boolean {
        if (cmd == null || cmd.side == null || config.sideMemoryS <= 0.0) return false
        if (cmd.kind != CommandKind.CORRECT && cmd.kind != CommandKind.CURVE) return false
        if (cmd.kind == CommandKind.CORRECT && cmd.level == Level.HIGH) return false
        val ls = lastSide ?: return false
        return ls != cmd.side && (nowMs - lastSideMs) / 1000.0 < config.sideMemoryS
    }

    /** The plain on-path tick is withheld for a moment after a warning ended (the state itself is kept). */
    private fun quiet(cmd: GuidanceCommand?): GuidanceCommand? {
        val ended = warningEndedMs ?: return cmd
        if (cmd?.kind == CommandKind.ON_PATH && !cmd.confirm && (lastNowMs - ended) / 1000.0 < config.quietAfterWarningS) return null
        return cmd
    }

    private var lastNowMs = 0L

    private fun sameDirection(a: GuidanceCommand, b: GuidanceCommand) = a.kind == b.kind && a.side == b.side
    private fun same(a: GuidanceCommand?, b: GuidanceCommand?) = a?.kind == b?.kind && a?.side == b?.side

    companion object {
        fun rank(c: GuidanceCommand?): Int = when (c?.kind) {
            CommandKind.STOP -> 5
            CommandKind.OBSTACLE -> 4
            CommandKind.CAUTION -> 3
            CommandKind.CORRECT -> 2
            CommandKind.CURVE -> 1
            else -> 0
        }
    }
}
