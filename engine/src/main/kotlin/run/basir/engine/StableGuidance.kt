package run.basir.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.min
import kotlin.math.tan

/**
 * What the guidance is doing, as one state at a time. The vibration layer turns each state into
 * one pattern; states never overlap, and only STOP_REQUIRED, OBSTACLE_WARNING and a strong
 * correction may interrupt another state before its minimum dwell time.
 */
enum class GuidanceState {
    TRACK_CENTERED,    // on the path, nothing to do (quiet)
    CORRECT_LEFT,      // drifting off the path: move left
    CORRECT_RIGHT,
    CURVE_LEFT,        // the path itself bends left: follow it (not a correction)
    CURVE_RIGHT,
    OBSTACLE_WARNING,  // something on the path ahead, not yet at stopping distance
    STOP_REQUIRED,     // obstacle without a verified way around, or the way ahead ends
    TRACK_LOST,        // no path for longer than the hold time
    VISION_UNCERTAIN,  // path seen but not trusted (dark, blur, low confidence)
}

/** How each guidance input is smoothed over frames (compared on real clips, see BasirRun_Research). */
enum class Smoothing { NONE, EMA, ONE_EURO, KALMAN, MEDIAN }

/** One input signal, filtered frame by frame. */
interface ScalarFilter {
    fun update(x: Double, dt: Double): Double
    fun reset()
}

class EmaFilter(private val alpha: Double) : ScalarFilter {
    private var y: Double? = null
    override fun update(x: Double, dt: Double): Double = (y?.let { it + alpha * (x - it) } ?: x).also { y = it }
    override fun reset() { y = null }
}

/** Casiez, Roussel, Vogel (CHI 2012): cut-off rises with the signal's speed, so slow drift is smoothed hard and real changes pass quickly. */
class OneEuroFilter(private val minCutoffHz: Double, private val beta: Double, private val dCutoffHz: Double = 1.0) : ScalarFilter {
    private var x0: Double? = null
    private var dx0 = 0.0
    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
    override fun update(x: Double, dt: Double): Double {
        val prev = x0 ?: run { x0 = x; return x }
        val dx = (x - prev) / dt
        dx0 += alpha(dCutoffHz, dt) * (dx - dx0)
        val cutoff = minCutoffHz + beta * abs(dx0)
        return (prev + alpha(cutoff, dt) * (x - prev)).also { x0 = it }
    }
    override fun reset() { x0 = null; dx0 = 0.0 }
}

/** Constant-velocity Kalman filter on one signal. */
class KalmanFilter1D(private val q: Double, private val r: Double) : ScalarFilter {
    private var x = 0.0
    private var v = 0.0
    private var p00 = 1.0; private var p01 = 0.0; private var p11 = 1.0
    private var started = false
    override fun update(z: Double, dt: Double): Double {
        if (!started) { x = z; v = 0.0; p00 = r; p01 = 0.0; p11 = 1.0; started = true; return z }
        x += v * dt
        p00 += dt * (2 * p01 + dt * p11) + q * dt * dt * dt / 3
        p01 += dt * p11 + q * dt * dt / 2
        p11 += q * dt
        val s = p00 + r
        val k0 = p00 / s; val k1 = p01 / s
        val e = z - x
        x += k0 * e; v += k1 * e
        p11 -= k1 * p01; p01 -= k0 * p01; p00 -= k0 * p00
        return x
    }
    override fun reset() { started = false }
}

class MedianFilter(private val n: Int) : ScalarFilter {
    private val buf = ArrayDeque<Double>()
    override fun update(x: Double, dt: Double): Double {
        buf.addLast(x); if (buf.size > n) buf.removeFirst()
        val s = buf.sorted(); return s[s.size / 2]
    }
    override fun reset() = buf.clear()
}

class PassFilter : ScalarFilter {
    override fun update(x: Double, dt: Double) = x
    override fun reset() {}
}

data class StableGuidanceConfig(
    val smoothing: Smoothing = Smoothing.ONE_EURO,
    // Correction: the cross-track error the runner would have after correctionTimeS on the current heading
    // >= the runner's own lag (react ~0.5 s + turn ~0.5 s): with 0.8 s the simulated runner overshot
    // and left the path in 24 of 36 runs, with 1.6 s in 6 (BasirRun_Research/benchmarks/results/planner_sim.txt)
    val correctionTimeS: Double = 1.6,
    // Owner's field-test settings (round 5): less sensitive to small drifts than round 3 (0.15 / 0.30 / 0.15)
    val deadZoneM: Double = 0.20,          // inside: nothing to correct
    val enterCorrectionM: Double = 0.40,   // a correction starts above this ...
    val exitCorrectionM: Double = 0.20,    // ... and ends below this (hysteresis)
    val mediumCorrectionM: Double = 0.60,
    val highCorrectionM: Double = 1.00,
    val highHeadingDeg: Double = 25.0,
    // On a wide strip, being off its middle is fine while the body keeps this margin to the edge
    val bodyRadiusM: Double = 0.4,
    val edgeMarginM: Double = 0.4,
    // Bends of the path itself (curvature, 1/m), separate from the runner's own offset
    val enterCurve: Double = 0.05,         // radius < 20 m
    val exitCurve: Double = 0.03,          // radius > 33 m ends the bend
    val mediumCurve: Double = 0.08,
    val sharpCurve: Double = 0.15,
    val previewS: Double = 2.5,            // how far ahead (in time) a bend is looked for
    val curveTaperS: Double = 2.0,         // after this long inside a bend its cue goes quiet
    val counterHeadingDeg: Double = 8.0,   // no bend cue against a path that clearly heads the other way at the feet (S-shape)
    // Timing
    val confirmFrames: Int = 3,            // a new non-critical state must persist this many frames ...
    val confirmS: Double = 1.0,            // ... and this long (owner, round 5: no change of command in under a second)
    val curveConfirmFrames: Int = 3,       // a bend (low priority, announced early) may wait longer
    val minDwellS: Double = 1.0,           // and the current one must have lasted this long
    val sideFlipFrames: Int = 6,           // left <-> right needs more evidence ...
    val sideFlipS: Double = 1.5,           // ... and must persist this long
    val sideFlipDwellS: Double = 1.5,      // and the current side must have lasted this long (not urgent)
    val uncertainHoldWalkingS: Double = 1.2,  // a short loss of the path gives a caution cue (no direction) ...
    val uncertainHoldRunningS: Double = 0.8,  // ... shorter when running: stop sooner when in doubt
    val detourConfirmFrames: Int = 4,      // a way around must be seen this many frames before it is given
    val turnSideConfirmFrames: Int = 4,    // the same for the side named after a stop
    val runningMps: Double = 2.0,
    // Stops (owner, round 5): only when the way is closed (an obstacle with no way around, the walkable
    // ground ending within stopping distance with no side open, or no ground at all) for longer than
    // blockStopS without a break. Before that the runner feels the obstacle warning (slow down).
    val blockStopS: Double = 1.0,
    // A lost or untrusted path (dark, blur, low confidence) gives the caution cue (slow down, no
    // direction) and never a stop when false.
    val stopWhenUncertain: Boolean = false,
)

/** One frame of guidance: the state plus the command the vibration layer understands. */
data class GuidanceEvent(
    val state: GuidanceState,
    val command: GuidanceCommand,
    /** 0 quiet … 4 stop. */
    val severity: Int,
    val offsetM: Double,
    val headingDeg: Double,
    val curvature: Double,
    val correctionM: Double,
    val changed: Boolean,
)

/**
 * Predictive path following with a stable state machine.
 *
 * The path (runner frame, metres, + right) is split into three things that pure pursuit mixes up:
 *  - offset: where the path is under the runner's feet,
 *  - heading: the angle between the runner's direction and the path's direction there,
 *  - curvature: how the path itself bends ahead.
 * A correction is asked for only when offset plus heading would take the runner off the path
 * within [StableGuidanceConfig.correctionTimeS]; a bend is announced from the curvature alone,
 * early (looked for [StableGuidanceConfig.previewS] seconds ahead), and goes quiet after the
 * runner has been in it for a while. So a centred runner on a curved path gets "follow the bend",
 * never "correct", and a bend never causes a stop.
 *
 * Stability: every input is smoothed (One Euro by default, chosen on real clips), corrections use
 * different enter and exit thresholds with a dead zone, a new state must persist a few frames and
 * the current one must have lasted a minimum time, left<->right needs more frames, and a short
 * loss of the path is held quietly instead of stopping at once. Critical states (stop, obstacle)
 * and strong corrections bypass all of this.
 */
class StableGuidanceController(private val config: StableGuidanceConfig = StableGuidanceConfig()) {

    // Parameters tuned per filter on 4 SANPO clips and checked on the owner's night video
    // (BasirRun_Research/benchmarks/results/smoothing_grid.txt): One Euro and EMA tie (~2 frames
    // of lag), Kalman and median are worse. One Euro is kept for the offset because its cut-off
    // rises when the runner really drifts fast.
    private fun filter(kind: Smoothing, signal: Int): ScalarFilter = when (kind) {
        Smoothing.NONE -> PassFilter()
        Smoothing.EMA -> EmaFilter(if (signal == 2) 0.35 else 0.25)
        Smoothing.ONE_EURO -> if (signal == 0) OneEuroFilter(0.6, 0.3) else OneEuroFilter(1.0, 0.0)
        Smoothing.KALMAN -> if (signal == 0) KalmanFilter1D(0.1, 0.1) else KalmanFilter1D(1.0, 0.1)
        Smoothing.MEDIAN -> MedianFilter(5)
    }
    private val fOffset = filter(config.smoothing, 0)
    private val fHeading = filter(config.smoothing, 1)
    private val fCurve = filter(config.smoothing, 2)

    var state = GuidanceState.TRACK_CENTERED
        private set
    private var stateTimeS = 0.0
    private var candidate: GuidanceState? = null
    private var candidateFrames = 0
    private var candidateS = 0.0
    private var blockedS = 0.0
    private var lastDt = 1.0 / 15
    private var uncertainS = 0.0
    private var curveInsideS = 0.0
    private var detourSide: Side? = null
    private var detourFrames = 0
    private var turnSide: Side? = null
    private var turnFrames = 0
    private var wasCorrecting = false

    fun reset() {
        fOffset.reset(); fHeading.reset(); fCurve.reset()
        state = GuidanceState.TRACK_CENTERED; stateTimeS = 0.0; candidate = null; candidateFrames = 0; candidateS = 0.0; blockedS = 0.0
        uncertainS = 0.0; curveInsideS = 0.0; detourSide = null; detourFrames = 0; turnSide = null; turnFrames = 0
        wasCorrecting = false
    }

    fun update(decision: PathDecision, speedMps: Double = 2.5, dt: Double = 1.0 / 15): GuidanceEvent {
        stateTimeS += dt
        lastDt = dt
        val running = speedMps >= config.runningMps
        val path = decision.path

        // --- No usable path ---------------------------------------------------------------
        if (decision.mode == PathMode.STOP || path == null) {
            val closed = decision.stopReason == StopReason.OBSTACLE_NO_ALTERNATIVE || decision.stopReason == StopReason.PATH_UNSAFE ||
                decision.stopReason == StopReason.NO_PATH
            if (closed) {
                uncertainS = 0.0
                blockedS += dt
                val side = confirmedTurnSide(decision.turnSide)
                val reason = if (decision.stopReason == StopReason.NO_PATH) StopReason.PATH_UNSAFE else decision.stopReason
                // Closed for longer than blockStopS without a break (or already stopped): stop.
                if (blockedS > config.blockStopS || state == GuidanceState.STOP_REQUIRED) {
                    return emit(GuidanceState.STOP_REQUIRED, GuidanceCommand(CommandKind.STOP, side = side, stopReason = reason, mode = decision.mode), 4, force = true)
                }
                // Not yet: obstacle warning (slow down), with the side to turn to once it is confirmed.
                return emit(GuidanceState.OBSTACLE_WARNING, GuidanceCommand(CommandKind.OBSTACLE, side = side, level = Level.HIGH,
                    confidence = decision.confidence, mode = decision.mode), 3, force = true)
            }
            // The planner holds a few frames after a closed way before it resumes (LOW_CONFIDENCE): that is the
            // tail of the same warning, not a new caution, and it never counts toward a stop.
            if (!config.stopWhenUncertain && decision.stopReason == StopReason.LOW_CONFIDENCE &&
                (state == GuidanceState.OBSTACLE_WARNING || state == GuidanceState.STOP_REQUIRED)) {
                blockedS = 0.0
                return emit(GuidanceState.OBSTACLE_WARNING, GuidanceCommand(CommandKind.OBSTACLE, side = turnSide?.takeIf { turnFrames >= config.turnSideConfirmFrames },
                    level = Level.HIGH, confidence = decision.confidence, mode = decision.mode), 3, force = true)
            }
            blockedS = 0.0
            turnSide = null; turnFrames = 0
            val lost = if (decision.stopReason == StopReason.NO_PATH) GuidanceState.TRACK_LOST else GuidanceState.VISION_UNCERTAIN
            uncertainS += dt
            val hold = if (running) config.uncertainHoldRunningS else config.uncertainHoldWalkingS
            // A short dropout (a shadow, one dark frame) is held quietly: no direction, no stop yet.
            if (uncertainS < hold && state != GuidanceState.STOP_REQUIRED && state != GuidanceState.TRACK_LOST && state != GuidanceState.VISION_UNCERTAIN) {
                return quiet(GuidanceState.VISION_UNCERTAIN, decision)
            }
            fOffset.reset(); fHeading.reset(); fCurve.reset()
            if (!config.stopWhenUncertain) {
                // Lost or untrusted for longer than the hold: caution (slow down, no direction), never a stop.
                return emit(lost, GuidanceCommand(CommandKind.CAUTION, confidence = decision.confidence, mode = decision.mode), 1, force = true)
            }
            return emit(lost, GuidanceCommand(CommandKind.STOP, stopReason = decision.stopReason, mode = decision.mode), 4, force = true)
        }
        uncertainS = 0.0
        blockedS = 0.0
        turnSide = null; turnFrames = 0

        // --- Signals: offset, heading, curvature (smoothed) -----------------------------------
        val offset = fOffset.update(path.lateralAt(0.0).coerceIn(-3.0, 3.0), dt)
        val heading = fHeading.update(path.headingDegAt(0.0).coerceIn(-60.0, 60.0), dt)
        val previewM = (speedMps * config.previewS).coerceIn(2.0, 8.0)
        val kappaRaw = if (path.farM >= 1.5) path.curvatureAt(min(previewM, path.farM) * 0.5) else 0.0
        val kappa = fCurve.update(kappaRaw.coerceIn(-0.5, 0.5), dt)
        val predicted = offset + speedMps * config.correctionTimeS * tan(Math.toRadians(heading))
        // Free band: how far from the middle the runner may be and still keep a margin to the edge.
        val band = decision.stripHalfWidthM?.let { max(0.0, it - config.bodyRadiusM - config.edgeMarginM) } ?: 0.0
        val correction = if (abs(predicted) <= band) 0.0 else predicted - band * sign(predicted)

        // --- Obstacle on the path ahead -------------------------------------------------------
        if (decision.obstacleAheadM != null) {
            // A detour path is followed only once its side has been seen for a few frames.
            if (decision.mode == PathMode.DETOUR) {
                val side = if (path.lateralAt(min(decision.obstacleAheadM, path.farM)) >= 0) Side.RIGHT else Side.LEFT
                if (side == detourSide) detourFrames++ else { detourSide = side; detourFrames = 1 }
            } else { detourSide = null; detourFrames = 0 }
            val verified = detourSide != null && detourFrames >= config.detourConfirmFrames
            val cmd = GuidanceCommand(CommandKind.OBSTACLE, side = if (verified) detourSide else null, level = Level.HIGH,
                steerDeg = heading, confidence = decision.confidence, mode = decision.mode)
            return emit(GuidanceState.OBSTACLE_WARNING, cmd, 3, force = true, offset, heading, kappa, correction)
        }
        detourSide = null; detourFrames = 0

        // --- Correction (runner off the path) vs bend (path itself turns) ----------------------
        val correcting = state == GuidanceState.CORRECT_LEFT || state == GuidanceState.CORRECT_RIGHT
        val needCorrection = abs(correction) >= (if (correcting) config.exitCorrectionM else config.enterCorrectionM) && abs(correction) >= config.deadZoneM
        val level = when {
            abs(correction) >= config.highCorrectionM || abs(heading) >= config.highHeadingDeg -> Level.HIGH
            abs(correction) >= config.mediumCorrectionM -> Level.MEDIUM
            else -> Level.LOW
        }.let { if (running && it == Level.HIGH && abs(correction) < 1.5 * config.highCorrectionM) Level.MEDIUM else it } // no sudden swerve when running
        val inCurve = state == GuidanceState.CURVE_LEFT || state == GuidanceState.CURVE_RIGHT
        // An S-shaped path (heading one way at the feet, bending back the other way further on) gets
        // no bend cue yet: "bend left" while the path pulls right would contradict the runner's way.
        val counter = abs(heading) >= config.counterHeadingDeg && sign(heading) != sign(kappa)
        val curveOn = abs(kappa) >= (if (inCurve) config.exitCurve else config.enterCurve) && !counter
        val curveLevel = when {
            abs(kappa) >= config.sharpCurve -> Level.HIGH
            abs(kappa) >= config.mediumCurve -> Level.MEDIUM
            else -> Level.LOW
        }
        val corrSide = if (correction > 0) Side.RIGHT else Side.LEFT
        val curveSide = if (kappa > 0) Side.RIGHT else Side.LEFT

        val wanted: GuidanceState
        val cmd: GuidanceCommand
        val base = GuidanceCommand(CommandKind.ON_PATH, steerDeg = heading, curveCurvature = kappa, curveInM = if (curveOn) 0.0 else null,
            confidence = decision.confidence, mode = decision.mode)
        // While following a bend, a small offset toward its inside is part of following it.
        val correctionWins = needCorrection && !(curveOn && level == Level.LOW && corrSide == curveSide)
        if (correctionWins) {
            wanted = if (corrSide == Side.LEFT) GuidanceState.CORRECT_LEFT else GuidanceState.CORRECT_RIGHT
            cmd = base.copy(kind = CommandKind.CORRECT, side = corrSide, level = level)
        } else if (curveOn) {
            wanted = if (curveSide == Side.LEFT) GuidanceState.CURVE_LEFT else GuidanceState.CURVE_RIGHT
            cmd = base.copy(kind = CommandKind.CURVE, side = curveSide, level = curveLevel)
        } else {
            wanted = GuidanceState.TRACK_CENTERED
            cmd = base
        }
        val urgent = correctionWins && level == Level.HIGH
        val ev = emit(wanted, cmd, when (wanted) {
            GuidanceState.CORRECT_LEFT, GuidanceState.CORRECT_RIGHT -> 2
            GuidanceState.CURVE_LEFT, GuidanceState.CURVE_RIGHT -> 1
            else -> 0
        }, force = urgent, offset, heading, kappa, correction)

        // Bend cue tapers: after a while inside the bend it goes quiet unless the bend tightens.
        if (ev.state == GuidanceState.CURVE_LEFT || ev.state == GuidanceState.CURVE_RIGHT) {
            curveInsideS += dt
            if (curveInsideS > config.curveTaperS && curveLevel != Level.HIGH) return ev.copy(command = base)
        } else curveInsideS = 0.0
        // Back on the path after a correction: one quiet confirmation, then silence.
        val nowCorrecting = ev.state == GuidanceState.CORRECT_LEFT || ev.state == GuidanceState.CORRECT_RIGHT
        val confirm = wasCorrecting && ev.state == GuidanceState.TRACK_CENTERED && ev.changed
        wasCorrecting = nowCorrecting || (wasCorrecting && !ev.changed && ev.state != GuidanceState.TRACK_CENTERED)
        return if (confirm) ev.copy(command = ev.command.copy(confirm = true)) else ev
    }

    private fun confirmedTurnSide(side: Side?): Side? {
        if (side == null) { turnSide = null; turnFrames = 0; return null }
        if (side == turnSide) turnFrames++ else { turnSide = side; turnFrames = 1 }
        return if (turnFrames >= config.turnSideConfirmFrames) side else null
    }

    private fun quiet(st: GuidanceState, d: PathDecision): GuidanceEvent =
        GuidanceEvent(st, GuidanceCommand(CommandKind.CAUTION, confidence = d.confidence, mode = d.mode, state = st), 1, 0.0, 0.0, 0.0, 0.0, false)

    /** Applies persistence and dwell rules; returns the event for the state actually held. */
    private fun emit(wanted: GuidanceState, cmd: GuidanceCommand, severity: Int, force: Boolean,
                     offset: Double = 0.0, heading: Double = 0.0, kappa: Double = 0.0, correction: Double = 0.0): GuidanceEvent {
        if (wanted == state) {
            candidate = null; candidateFrames = 0; candidateS = 0.0
            return GuidanceEvent(state, cmd.copy(state = state), severity, offset, heading, kappa, correction, false)
        }
        val critical = state == GuidanceState.STOP_REQUIRED || state == GuidanceState.TRACK_LOST || state == GuidanceState.VISION_UNCERTAIN || state == GuidanceState.OBSTACLE_WARNING
        if (!force) {
            // The first frame of a candidate counts as one frame of time, so N frames at dt make N*dt seconds.
            if (wanted == candidate) { candidateFrames++; candidateS += lastDt } else { candidate = wanted; candidateFrames = 1; candidateS = lastDt }
            val flip = sideOf(wanted) != null && sideOf(state) != null && sideOf(wanted) != sideOf(state)
            val bend = wanted == GuidanceState.CURVE_LEFT || wanted == GuidanceState.CURVE_RIGHT
            val need = maxOf(if (flip) config.sideFlipFrames else config.confirmFrames, if (bend) config.curveConfirmFrames else 0)
            // Leaving a warning, a caution or a stop also needs a second of the new state (owner, round 5): a way
            // that clears for half a second and closes again is felt as one warning, not warning-go-warning.
            val needS = if (flip) config.sideFlipS else config.confirmS
            val dwellOk = critical || stateTimeS >= (if (flip) config.sideFlipDwellS else config.minDwellS)
            if (candidateFrames < need || candidateS < needS - 1e-9 || !dwellOk) {
                // Hold the current state; while held, a held correction or bend keeps its own cue.
                val held = when (state) {
                    GuidanceState.TRACK_CENTERED -> cmd.copy(kind = CommandKind.ON_PATH, side = null, level = null)
                    else -> null
                }
                return GuidanceEvent(state, (held ?: holdCommand(cmd)).copy(state = state), severity, offset, heading, kappa, correction, false)
            }
        }
        state = wanted; stateTimeS = 0.0; candidate = null; candidateFrames = 0; candidateS = 0.0
        return GuidanceEvent(state, cmd.copy(state = state), severity, offset, heading, kappa, correction, true)
    }

    private fun holdCommand(cmd: GuidanceCommand): GuidanceCommand {
        // While a change waits, the held state keeps its cue only if the new reading still points
        // the same way; otherwise it goes quiet (no new vibration for a change not yet confirmed).
        if (cmd.side == null || cmd.side != sideOf(state)) return cmd.copy(kind = CommandKind.ON_PATH, side = null, level = null)
        return when (state) {
            GuidanceState.CORRECT_LEFT -> cmd.copy(kind = CommandKind.CORRECT, side = Side.LEFT, level = cmd.level ?: Level.LOW)
            GuidanceState.CORRECT_RIGHT -> cmd.copy(kind = CommandKind.CORRECT, side = Side.RIGHT, level = cmd.level ?: Level.LOW)
            GuidanceState.CURVE_LEFT -> cmd.copy(kind = CommandKind.CURVE, side = Side.LEFT, level = cmd.level ?: Level.LOW)
            GuidanceState.CURVE_RIGHT -> cmd.copy(kind = CommandKind.CURVE, side = Side.RIGHT, level = cmd.level ?: Level.LOW)
            else -> cmd.copy(kind = CommandKind.ON_PATH, side = null, level = null)
        }
    }

    private fun sideOf(s: GuidanceState): Side? = when (s) {
        GuidanceState.CORRECT_LEFT, GuidanceState.CURVE_LEFT -> Side.LEFT
        GuidanceState.CORRECT_RIGHT, GuidanceState.CURVE_RIGHT -> Side.RIGHT
        else -> null
    }

}
