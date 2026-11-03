package run.basir.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phases of a sharp turn (a 90° corner of a rectangular track around a pitch, a path corner), in order:
 * APPROACH_CORNER (seen ahead, nothing said yet) -> PREPARE_TURN (the runner is told which way, early) -> TURNING
 * (inside the corner, followed by the measured turn) -> EXIT_CORNER (new straight found) -> STABLE_STRAIGHT (the new
 * direction is held; no re-flip on a small reading change) -> NONE.
 */
enum class CornerPhase { NONE, APPROACH_CORNER, PREPARE_TURN, TURNING, EXIT_CORNER, STABLE_STRAIGHT }

/** Where the corner evidence of a frame came from. */
enum class CornerSource { LINE_INTERSECTION, PATH_GEOMETRY, WALKABLE_TURN }

/** One frame's evidence of a sharp turn ahead (runner frame: [distanceM] ahead, turning to [side] by [angleDeg]). */
data class CornerObservation(val side: Side, val distanceM: Double, val angleDeg: Double, val strength: Double, val source: CornerSource)

data class CornerState(
    val phase: CornerPhase = CornerPhase.NONE,
    val side: Side? = null,
    /** Distance to the corner along the path (m), while it is ahead. */
    val distanceM: Double? = null,
    /** Turn measured since the turn began (deg, toward [side]). */
    val turnedDeg: Double = 0.0,
    /** Turn the corner is expected to need (deg): from the evidence, 90 by default. */
    val expectedDeg: Double = 90.0,
    val confidence: Double = 0.0,
    val source: CornerSource? = null,
    /** True for a short time when a turn ended without a verified way out: no direction may be given then. */
    val exitUnverified: Boolean = false,
    /** Why the phase is what it is (diagnostics, developer overlay). */
    val reason: String = "",
    /** While exiting a corner: the side of a next corner already being seen (consecutive turns), else null. */
    val nextSide: Side? = null,
) {
    val active: Boolean get() = phase == CornerPhase.PREPARE_TURN || phase == CornerPhase.TURNING
}

data class CornerConfig(
    // --- Line intersection (a second line leaving the followed line's end at a large angle) ---
    val maskThreshold: Float = 0.5f,
    val maskStride: Int = 2,
    val maxRangeM: Double = 14.0,
    val onLineToleranceM: Double = 0.35,
    val branchMinLateralM: Double = 0.45,
    val branchMaxLateralM: Double = 5.0,
    val branchMinPoints: Int = 18,
    val branchSideShare: Double = 0.75,
    val branchMinAngleDeg: Double = 40.0,     // a rounded corner's arc leaves the line end at ~40-55 deg; a sharp one at ~90
    val branchMaxGapM: Double = 1.4,           // the branch must start near the followed line's end (a corner, not another line)
    val continuationMaxShare: Double = 0.35,  // the followed line must (mostly) end there; if it goes on it is a crossing
    // --- Path geometry (the followed path itself turns sharply within the preview) ---
    val geomMinTurnDeg: Double = 30.0,
    val geomMaxRadiusM: Double = 8.0,         // a bend of a 36.5 m athletics curve is NOT a corner
    // --- Walkable corridor turning (no line) ---
    val routeMinTurnDeg: Double = 50.0,
    val routeChordM: Double = 1.0,
    // --- Temporal fusion ---
    val supportTauS: Double = 0.6,
    val confirmSupport: Double = 1.8,
    val confirmObservations: Int = 3,
    val dropAfterS: Double = 1.2,             // no supporting evidence this long while ahead: the hypothesis is dropped
    val distanceGain: Double = 0.4,
    val prepareTimeS: Double = 1.8,           // prepare this long before the corner ...
    val prepareMinM: Double = 2.5,            // ... but never closer than this
    val prepareMaxM: Double = 7.0,
    val turnStartM: Double = 0.6,
    val turnStartYawDeg: Double = 12.0,
    val turnStartYawRateDegS: Double = 15.0,
    val exitShare: Double = 0.65,             // turned this share of the expected angle and a straight path is seen again
    val exitHardShare: Double = 0.9,          // turned this share: exit even before the path is re-acquired
    val straightHeadingDeg: Double = 15.0,
    val maxTurnS: Double = 4.0,               // turning longer than this with no way out found: unverified, no direction
    val turnConfirmS: Double = 1.2,           // inside "turning" the body must have turned this much within this time ...
    val turnConfirmDeg: Double = 15.0,        // ... or it was not a corner (the cue stops at once)
    val geomMinTurnFreeDeg: Double = 50.0,    // path geometry on a free path (no line) needs a clearer turn: its curve is noisier
    val exitHoldS: Double = 1.0,
    val stableHoldS: Double = 1.5,
    val unverifiedHoldS: Double = 1.5,
    val overshootM: Double = 1.5,             // ran this far past the corner with no turn: it was not a corner
)

/** Everything the corner engine reads in one frame. */
data class CornerInput(
    val dt: Double,
    val speedMps: Double,
    /** This frame's turn about the vertical (rad, + right): gyroscope when present, else the video's own estimate. */
    val yawRad: Double,
    val path: PathCurve?,
    val mode: PathMode?,
    val pathConfidence: Double = 1.0,
    /** Line probability on the analysis image (guide-line model or colour contrast) and the camera that maps it to the ground. */
    val lineMask: ProbabilityMask? = null,
    val camera: CameraModel? = null,
    /** Route through walkable ground (no line). */
    val route: FreeRoute? = null,
)

/**
 * Corner Detection & Navigation Engine.
 *
 * Sharp turns are not what the path tracker represents (lateral = f(forward) cannot turn 90°, and the band-wise line
 * extractor cannot see a line that runs across the image), so corners get their own evidence and their own state:
 *  1. Line intersection: the line mask is projected onto the ground; the points that are not on the followed line and
 *     start near its far end, on one side, along a direction at >= [CornerConfig.branchMinAngleDeg] to it, while the
 *     followed line itself ends there, are a corner of that side (a line that crosses and goes on is not).
 *  2. Path geometry: the followed path turns by >= [CornerConfig.geomMinTurnDeg] within the preview on a radius under
 *     [CornerConfig.geomMaxRadiusM] (an athletics bend of 36.5 m turns ~12° over 8 m and is NOT a corner).
 *  3. Walkable turn: without a line, the route through walkable ground bends by >= [CornerConfig.routeMinTurnDeg].
 * One frame never makes a corner: evidence is accumulated with decay ([CornerConfig.supportTauS]) on a hypothesis
 * anchored to the ground (its distance shrinks with the runner's advance and is corrected by new observations), and
 * a corner is confirmed only after [CornerConfig.confirmObservations] consistent observations. The turn itself is
 * followed by the measured heading change (gyroscope / visual yaw: body rotation of a corner, not camera wobble — wobble
 * averages out over the turn), and the exit needs both enough turn and a straight path seen again. Without a verified
 * way out the engine says so ([CornerState.exitUnverified]) instead of inventing a direction.
 */
class CornerNavigationEngine(private val config: CornerConfig = CornerConfig()) {

    private var phase = CornerPhase.NONE
    private var side: Side? = null
    private var distanceM = Double.NaN
    private var expectedDeg = 90.0
    private var support = 0.0
    private var oppositeSupport = 0.0
    private var observations = 0
    private var sinceSeenS = 0.0
    private var phaseS = 0.0
    private var turnedRad = 0.0
    private var prepareYawRad = 0.0
    private var source: CornerSource? = null
    private var straightS = 0.0
    private var unverifiedS = 0.0
    // Consecutive turns: evidence for the NEXT corner is collected while the last one is still being exited
    private var nextSide: Side? = null
    private var nextSupport = 0.0
    private var nextObservations = 0
    private var nextDistanceM = Double.NaN
    private var nextAngleDeg = 90.0
    private var nextSource: CornerSource? = null

    /** Diagnostics: why the last frame's line-intersection evidence was accepted or rejected. */
    var lastDebug: String = ""
        private set

    /** Diagnostics: the last frame's raw observation (null = none). */
    var lastObservation: CornerObservation? = null
        private set

    fun reset() {
        phase = CornerPhase.NONE; side = null; distanceM = Double.NaN; expectedDeg = 90.0; support = 0.0; oppositeSupport = 0.0
        observations = 0; sinceSeenS = 0.0; phaseS = 0.0; turnedRad = 0.0; prepareYawRad = 0.0; source = null; straightS = 0.0
        unverifiedS = 0.0; lastObservation = null; clearNext()
    }

    private fun clearNext() { nextSide = null; nextSupport = 0.0; nextObservations = 0; nextDistanceM = Double.NaN; nextSource = null }

    /** Evidence for a new corner while exiting / stabilising after the last one; true when it is confirmed. */
    private fun accumulateNext(obs: CornerObservation?, input: CornerInput, dt: Double): Boolean {
        nextSupport *= exp(-dt / config.supportTauS)
        if (!nextDistanceM.isNaN()) nextDistanceM -= input.speedMps * dt
        if (obs != null && obs.distanceM > 1.0) {
            if (nextSide == null || (obs.side != nextSide && nextSupport < 0.5)) {
                nextSide = obs.side; nextSupport = obs.strength; nextObservations = 1; nextDistanceM = obs.distanceM
                nextAngleDeg = obs.angleDeg; nextSource = obs.source
            } else if (obs.side == nextSide) {
                nextSupport += obs.strength; nextObservations++; nextSource = obs.source
                nextDistanceM = if (nextDistanceM.isNaN()) obs.distanceM else nextDistanceM + config.distanceGain * (obs.distanceM - nextDistanceM)
            }
        } else if (nextSupport < 0.2) clearNext()
        return nextSide != null && nextSupport >= config.confirmSupport && nextObservations >= config.confirmObservations
    }

    private fun startNext() {
        side = nextSide; support = nextSupport; observations = nextObservations; distanceM = nextDistanceM; expectedDeg = nextAngleDeg
        source = nextSource; sinceSeenS = 0.0; oppositeSupport = 0.0; turnedRad = 0.0; prepareYawRad = 0.0
        clearNext(); setPhase(CornerPhase.APPROACH_CORNER)
    }

    /** A next corner is being seen (diagnostics; the decision layer lifts the post-turn hold for it). */
    val nextCornerSide: Side? get() = nextSide?.takeIf { nextObservations >= 2 }

    fun update(input: CornerInput): CornerState {
        val dt = input.dt
        phaseS += dt
        val obs = observe(input)
        lastObservation = obs
        if (unverifiedS > 0) unverifiedS = max(0.0, unverifiedS - dt)

        when (phase) {
            CornerPhase.STABLE_STRAIGHT -> {
                if (accumulateNext(obs, input, dt)) startNext()
                else if (phaseS >= config.stableHoldS) {
                    // hand the candidate over to the normal search
                    val ns = nextSide
                    if (ns != null) { side = ns; support = nextSupport; observations = nextObservations; distanceM = nextDistanceM; source = nextSource }
                    else { side = null; support = 0.0; observations = 0; distanceM = Double.NaN }
                    clearNext(); setPhase(CornerPhase.NONE)
                }
            }
            CornerPhase.NONE -> {
                accumulate(obs, input, dt)
                if (side != null && support >= config.confirmSupport && observations >= config.confirmObservations) {
                    setPhase(CornerPhase.APPROACH_CORNER)
                }
            }
            CornerPhase.APPROACH_CORNER, CornerPhase.PREPARE_TURN -> {
                accumulate(obs, input, dt)
                if (side == null) { setPhase(CornerPhase.NONE); return state("hypothesis dropped") }
                val prepareAt = (input.speedMps * config.prepareTimeS).coerceIn(config.prepareMinM, config.prepareMaxM)
                val toward = towardSide(input.yawRad)
                if (phase == CornerPhase.PREPARE_TURN) prepareYawRad += toward
                val yawRate = Math.toDegrees(toward / max(dt, 1e-3))
                val turnBegun = distanceM <= config.turnStartM + 0.15 * input.speedMps ||
                    (phase == CornerPhase.PREPARE_TURN && Math.toDegrees(prepareYawRad) >= config.turnStartYawDeg && yawRate >= config.turnStartYawRateDegS)
                when {
                    turnBegun -> { turnedRad = max(0.0, prepareYawRad); setPhase(CornerPhase.TURNING) }
                    phase == CornerPhase.APPROACH_CORNER && distanceM <= prepareAt -> { prepareYawRad = 0.0; setPhase(CornerPhase.PREPARE_TURN) }
                }
                // Ran past it without turning, or the evidence stopped: it was not a corner (never invent one)
                if (phase != CornerPhase.TURNING && (distanceM < -config.overshootM || sinceSeenS > config.dropAfterS && distanceM > config.turnStartM)) {
                    clear(); return state("no corner after all")
                }
            }
            CornerPhase.TURNING -> {
                turnedRad += towardSide(input.yawRad)
                val turned = Math.toDegrees(turnedRad)
                val straight = straightAhead(input)
                val expectedS = (expectedDeg / 90.0) * (Math.PI / 2 * 1.5) / max(input.speedMps, 0.8) + 1.0
                if (phaseS >= config.turnConfirmS && turned < config.turnConfirmDeg && !straightAfterTurnAllowed(input)) {
                    clear(); return state("no turn measured: not a corner")
                }
                when {
                    turned >= config.exitShare * expectedDeg && straight -> setPhase(CornerPhase.EXIT_CORNER)
                    turned >= config.exitHardShare * expectedDeg -> setPhase(CornerPhase.EXIT_CORNER)
                    // Body turned less (visual yaw underestimates) but the new straight is clearly followed again
                    phaseS >= expectedS && straight && turned >= 0.35 * expectedDeg -> setPhase(CornerPhase.EXIT_CORNER)
                    phaseS >= max(config.maxTurnS, 2.5 * expectedS) -> {
                        // No way out verified in time: stop giving the turn direction (guidance falls back to caution)
                        val s = side; clear(); unverifiedS = config.unverifiedHoldS
                        return CornerState(CornerPhase.NONE, s, exitUnverified = true, reason = "turn not verified after ${"%.1f".format(phaseS)} s")
                    }
                }
            }
            CornerPhase.EXIT_CORNER -> {
                if (accumulateNext(obs, input, dt)) { startNext(); return state("next corner ${side} at %.1f m".format(distanceM)) }
                straightS = if (straightAhead(input)) straightS + dt else 0.0
                if (straightS >= config.exitHoldS) setPhase(CornerPhase.STABLE_STRAIGHT)
                else if (phaseS >= 3.0) setPhase(CornerPhase.STABLE_STRAIGHT)
            }
        }
        return state(when (phase) {
            CornerPhase.APPROACH_CORNER -> "corner ${side} at %.1f m".format(distanceM)
            CornerPhase.PREPARE_TURN -> "prepare ${side} at %.1f m".format(distanceM)
            CornerPhase.TURNING -> "turning ${side} %.0f/%.0f deg".format(Math.toDegrees(turnedRad), expectedDeg)
            CornerPhase.EXIT_CORNER -> "exit"
            CornerPhase.STABLE_STRAIGHT -> "new straight"
            CornerPhase.NONE -> ""
        })
    }

    private fun state(reason: String) = CornerState(
        phase, if (phase == CornerPhase.NONE) null else side,
        distanceM.takeIf { !it.isNaN() && (phase == CornerPhase.APPROACH_CORNER || phase == CornerPhase.PREPARE_TURN) },
        Math.toDegrees(turnedRad), expectedDeg, min(1.0, support / (2 * config.confirmSupport)), source, unverifiedS > 0, reason, nextCornerSide)

    private fun setPhase(p: CornerPhase) {
        if (p != phase) {
            if (p == CornerPhase.EXIT_CORNER) clearNext()
            phase = p; phaseS = 0.0; straightS = 0.0
        }
    }

    private fun clear() {
        phase = CornerPhase.NONE; side = null; distanceM = Double.NaN; support = 0.0; oppositeSupport = 0.0; observations = 0
        sinceSeenS = 0.0; phaseS = 0.0; turnedRad = 0.0; prepareYawRad = 0.0; source = null; expectedDeg = 90.0
    }

    private fun towardSide(yawRad: Double) = if (side == Side.LEFT) -yawRad else yawRad

    /** Evidence accumulation on a ground-anchored hypothesis. */
    private fun accumulate(obs: CornerObservation?, input: CornerInput, dt: Double) {
        val decay = exp(-dt / config.supportTauS)
        support *= decay; oppositeSupport *= decay
        if (!distanceM.isNaN()) distanceM -= input.speedMps * dt
        sinceSeenS += dt
        if (obs == null) {
            if (phase == CornerPhase.NONE && support < 0.2) { side = null; observations = 0; distanceM = Double.NaN }
            return
        }
        if (side == null || (phase == CornerPhase.NONE && obs.side != side && support < 0.5)) {
            side = obs.side; support = obs.strength; observations = 1; distanceM = obs.distanceM; expectedDeg = obs.angleDeg
            source = obs.source; sinceSeenS = 0.0; oppositeSupport = 0.0
            return
        }
        if (obs.side == side) {
            support += obs.strength; observations++; sinceSeenS = 0.0; source = obs.source
            distanceM = if (distanceM.isNaN()) obs.distanceM else distanceM + config.distanceGain * (obs.distanceM - distanceM)
            expectedDeg += 0.2 * (obs.angleDeg.coerceIn(45.0, 135.0) - expectedDeg)
        } else {
            oppositeSupport += obs.strength
            // The other side has clearly taken over before anything was said: start again with it
            if (phase == CornerPhase.APPROACH_CORNER && oppositeSupport > 1.5 * support + 1.0) {
                side = obs.side; support = oppositeSupport; oppositeSupport = 0.0; observations = 2; distanceM = obs.distanceM
                expectedDeg = obs.angleDeg; source = obs.source; sinceSeenS = 0.0
            }
        }
    }

    /** A rounded corner can be followed by the line itself with little body turn per frame; only a line path allows that. */
    private fun straightAfterTurnAllowed(input: CornerInput): Boolean =
        (input.mode == PathMode.LINE || input.mode == PathMode.LINE_PARALLEL) && input.path?.let { abs(it.headingDegAt(min(2.0, it.farM))) > 20.0 } == true

    /** The path is followed again and runs (nearly) straight ahead of the runner. */
    private fun straightAhead(input: CornerInput): Boolean {
        val p = input.path ?: return false
        if (p.farM < 2.0 || input.pathConfidence < 0.3) return false
        val h = abs(p.headingDegAt(min(2.0, p.farM)))
        return h <= config.straightHeadingDeg && abs(p.lateralAt(min(2.0, p.farM))) < 1.0
    }

    // ---------------------------------------------------------------- evidence of one frame

    internal fun observe(input: CornerInput): CornerObservation? {
        val a = lineIntersection(input)
        val b = pathGeometry(input)
        val c = if (a == null && b == null) walkableTurn(input) else null
        return listOfNotNull(a, b, c).maxByOrNull { it.strength }
    }

    private fun lineIntersection(input: CornerInput): CornerObservation? {
        lastDebug = ""
        val mask = input.lineMask ?: return null.also { lastDebug = "no mask" }
        val cam = input.camera ?: return null
        val path = input.path ?: return null.also { lastDebug = "no path" }
        if (input.mode != PathMode.LINE && input.mode != PathMode.LINE_PARALLEL && input.mode != PathMode.LANE_CENTER) return null.also { lastDebug = "mode ${input.mode}" }
        if (path.farM < 1.5) return null.also { lastDebug = "short path" }
        val sx = cam.width.toDouble() / mask.width; val sy = cam.height.toDouble() / mask.height
        // The followed line. In LINE_PARALLEL mode the path runs beside the line at a fixed distance: that distance is
        // estimated from the mask itself (median offset of the line pixels next to the path) and the path shifted onto it.
        val shift = if (input.mode == PathMode.LINE_PARALLEL) parallelShift(mask, cam, path, sx, sy) else 0.0
        val line = if (shift != 0.0) PathCurve(path.coeffs.copyOf().also { it[0] += shift }, path.nearM, path.farM) else path
        return intersectionWith(line, mask, cam, sx, sy)
    }

    private fun parallelShift(mask: ProbabilityMask, cam: CameraModel, path: PathCurve, sx: Double, sy: Double): Double {
        val d = ArrayList<Double>()
        var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (mask[x, y] >= config.maskThreshold) {
                    val g = cam.imageToGround((x + 0.5) * sx, (y + 0.5) * sy)
                    if (g != null && g.forward in 0.8..path.farM) {
                        val r = g.lateral - path.lateralAt(g.forward)
                        if (abs(r) in 0.25..2.2) d += r
                    }
                }
                x += 2
            }
            y += 2
        }
        if (d.size < 10) return 0.0
        d.sort(); return d[d.size / 2]
    }

    private fun intersectionWith(path: PathCurve, mask: ProbabilityMask, cam: CameraModel, sx: Double, sy: Double): CornerObservation? {
        val endF = path.farM
        val endL = path.lateralAt(endF)
        val branch = ArrayList<GroundPoint>()
        var continuation = 0
        val tangentDeg = path.headingDegAt(endF)
        val tx = kotlin.math.cos(Math.toRadians(tangentDeg)); val ty = kotlin.math.sin(Math.toRadians(tangentDeg))
        val stride = config.maskStride
        var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (mask[x, y] >= config.maskThreshold) {
                    val g = cam.imageToGround((x + 0.5) * sx, (y + 0.5) * sy)
                    if (g != null && g.forward > 0.3 && g.forward <= config.maxRangeM) {
                        // along/across the followed line's end direction
                        val df = g.forward - endF; val dl = g.lateral - endL
                        val along = df * tx + dl * ty
                        val across = -df * ty + dl * tx
                        // from 1 m on: a rounded corner leaves the line tangentially, so its start is not "the line going on"
                        if (along in 1.0..3.0 && abs(across) < config.onLineToleranceM) continuation++
                        val onFollowed = g.forward <= endF + 0.3 && abs(g.lateral - path.lateralAt(g.forward)) < config.onLineToleranceM
                        if (!onFollowed && along in -1.5..6.0 && abs(across) in config.branchMinLateralM..config.branchMaxLateralM + 1.0) branch += GroundPoint(along, across)
                    }
                }
                x += stride
            }
            y += stride
        }
        // How many mask samples a continuing line would give: compare with the followed line's own density near its end
        val continuationSlots = max(1, (countNear(mask, cam, path, endF - 2.5, endF - 0.4, sx, sy) * 2.0 / 2.6).toInt())
        lastDebug = "endF=%.1f branch=%d cont=%d slots=%d".format(endF, branch.size, continuation, continuationSlots)
        if (branch.size < config.branchMinPoints) return null
        val right = branch.count { it.lateral > 0 }; val left = branch.size - right
        val sideIsRight = right >= left
        val share = max(right, left).toDouble() / branch.size
        lastDebug += " share=%.2f".format(share)
        if (share < config.branchSideShare) return null
        val pts = branch.filter { (it.lateral > 0) == sideIsRight }
        // Dominant straight line through the branch points (a small Hough over direction and offset): blobs and
        // scattered texture do not form one; the corner's line does. Angle is measured from the followed line's direction.
        var bestCount = 0; var bestAng = 0.0; var bestRho = 0.0
        var a = 0
        while (a < 180) {
            val th = Math.toRadians(a.toDouble()); val nx = -kotlin.math.sin(th); val ny = kotlin.math.cos(th)
            val bins = HashMap<Int, Int>()
            for (p in pts) { val k = kotlin.math.floor((p.forward * nx + p.lateral * ny) / 0.15).toInt(); bins.merge(k, 1, Int::plus) }
            val best = bins.entries.maxBy { e -> e.value + (bins[e.key + 1] ?: 0) }
            val c = best.value + (bins[best.key + 1] ?: 0)
            if (c > bestCount) { bestCount = c; bestAng = a.toDouble(); bestRho = (best.key + 1) * 0.15 }
            a += 5
        }
        val angDeg = if (bestAng > 90) 180 - bestAng else bestAng
        val inliers = bestCount.toDouble() / pts.size
        val contShare = continuation.toDouble() / (continuationSlots * 0.6 + 1)
        lastDebug += " ang=%.0f inl=%.2f n=%d contShare=%.2f".format(angDeg, inliers, bestCount, contShare)
        if (angDeg < config.branchMinAngleDeg || bestCount < config.branchMinPoints || inliers < 0.45) return null
        if (contShare > config.continuationMaxShare) return null   // the followed line goes on: a crossing, not a corner
        // Where is the corner? A clearly perpendicular branch: where its line meets the followed line's extension
        // (line intersection), so worn paint around the corner still gives the corner; it must lie 0..5 m past the
        // visible end and the branch must start within 4.5 m of it. A rounded corner's arc (40-60 deg): at the end.
        val th = Math.toRadians(bestAng); val nx = -kotlin.math.sin(th)
        val distance: Double
        if (angDeg >= 60.0 && abs(nx) > 0.3) {
            val alongI = bestRho / nx
            val near = pts.minOf { hypot(it.forward - alongI, it.lateral) }
            lastDebug += " meet=%.1f near=%.1f".format(alongI, near)
            if (alongI !in -0.5..5.0 || near > 4.5) return null
            distance = endF + max(0.0, alongI)
        } else {
            val nearest = pts.minOf { hypot(it.forward, it.lateral) }
            lastDebug += " gap=%.1f".format(nearest)
            if (nearest > config.branchMaxGapM + 0.5) return null
            distance = endF
        }
        lastDebug += " OK"
        val strength = (bestCount / 50.0).coerceIn(0.3, 1.0) * ((angDeg - config.branchMinAngleDeg) / 30.0 + 0.5).coerceIn(0.5, 1.0)
        return CornerObservation(if (sideIsRight) Side.RIGHT else Side.LEFT, distance, angDeg, strength, CornerSource.LINE_INTERSECTION)
    }

    private fun countNear(mask: ProbabilityMask, cam: CameraModel, path: PathCurve, from: Double, to: Double, sx: Double, sy: Double): Int {
        var n = 0; var y = 0
        while (y < mask.height) {
            var x = 0
            while (x < mask.width) {
                if (mask[x, y] >= config.maskThreshold) {
                    val g = cam.imageToGround((x + 0.5) * sx, (y + 0.5) * sy)
                    if (g != null && g.forward in from..to && abs(g.lateral - path.lateralAt(g.forward)) < config.onLineToleranceM) n++
                }
                x += config.maskStride
            }
            y += config.maskStride
        }
        // per metre of line, scaled to the 2.6 m window used for the continuation test
        return (n / max(0.5, to - from) * 2.6).toInt()
    }

    private fun pathGeometry(input: CornerInput): CornerObservation? {
        val p = input.path ?: return null
        if (p.farM < 3.0 || input.pathConfidence < 0.4) return null
        val f0 = max(0.5, p.nearM); val f1 = min(p.farM, max(4.0, input.speedMps * 3.0))
        if (f1 - f0 < 1.5) return null
        val h0 = p.headingDegAt(f0); val h1 = p.headingDegAt(f1)
        val turn = h1 - h0
        val free = input.mode == PathMode.FREE_PATH
        if (abs(turn) < (if (free) config.geomMinTurnFreeDeg else config.geomMinTurnDeg)) return null
        val arc = f1 - f0
        val radius = arc / Math.toRadians(abs(turn))
        if (radius > config.geomMaxRadiusM) return null
        // where the turn happens: first point whose heading passed half of it
        var fc = f0; var f = f0
        while (f <= f1) { if (abs(p.headingDegAt(f) - h0) >= abs(turn) / 2) { fc = f; break }; f += 0.25 }
        val strength = ((abs(turn) - config.geomMinTurnDeg) / 30.0 + 0.4).coerceIn(0.3, 0.8) * input.pathConfidence.coerceIn(0.5, 1.0) *
            (if (free) 0.6 else 1.0)
        return CornerObservation(if (turn > 0) Side.RIGHT else Side.LEFT, fc, 90.0, strength, CornerSource.PATH_GEOMETRY)
    }

    private fun walkableTurn(input: CornerInput): CornerObservation? {
        val r = input.route ?: return null
        val wp = r.waypoints
        if (wp.size < 4 || r.progressM < 2.5) return null
        // headings of ~1 m chords along the route
        val chords = ArrayList<Pair<Double, Double>>() // (distance along, heading deg)
        var i = 0; var along = 0.0
        while (i < wp.size - 1) {
            var j = i + 1; var len = 0.0
            while (j < wp.size && len < config.routeChordM) { len = hypot(wp[j].forward - wp[i].forward, wp[j].lateral - wp[i].lateral); if (len < config.routeChordM) j++ }
            if (j >= wp.size) break
            chords += along to Math.toDegrees(atan2(wp[j].lateral - wp[i].lateral, wp[j].forward - wp[i].forward))
            along += len; i = j
        }
        if (chords.size < 3) return null
        val first = chords.first().second
        val k = chords.indexOfFirst { abs(it.second - first) >= config.routeMinTurnDeg }
        if (k < 1) return null
        val turn = chords[k].second - first
        return CornerObservation(if (turn > 0) Side.RIGHT else Side.LEFT, chords[k].first, abs(turn).coerceIn(45.0, 135.0),
            0.45, CornerSource.WALKABLE_TURN)
    }
}
