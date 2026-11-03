package run.basir.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How the runner moved since the previous frame, on the ground: [forwardM] ahead, [lateralM] to the right,
 * turned by [yawRad] (positive = to the right). On the phone: yaw from the gyroscope, advance from the speed.
 */
data class EgoMotion(val forwardM: Double, val lateralM: Double = 0.0, val yawRad: Double = 0.0) {
    /** A point fixed on the ground, seen from where the runner is now. */
    fun apply(p: GroundPoint): GroundPoint {
        val f = p.forward - forwardM
        val l = p.lateral - lateralM
        val c = cos(yawRad); val s = sin(yawRad)
        return GroundPoint(c * f + s * l, -s * f + c * l)
    }

    /** The same ground path expressed in the runner frame after this motion (refitted, measured range shifted). */
    fun apply(curve: PathCurve): PathCurve {
        val from = max(0.0, curve.nearM)
        val to = max(from + 0.5, curve.farM)
        val pts = ArrayList<GroundPoint>()
        var f = from
        while (f <= to + 1e-9) { pts += apply(GroundPoint(f, curve.lateralAt(f))); f += 0.25 }
        val fit = PathCurve.fit(pts, maxDegree = max(1, curve.coeffs.size - 1)) ?: return curve
        return fit.copy(nearM = max(0.0, curve.nearM - forwardM), farM = max(0.5, curve.farM - forwardM), rmsM = curve.rmsM)
    }

    companion object {
        val NONE = EgoMotion(0.0)
    }
}

/** The tracking interface the planner uses; implemented by [PathTracker] (0.12.2) and [TemporalPathTracker]. */
interface PathTrack {
    val state: TrackState
    val curve: PathCurve?
    val confidence: Double
    fun reset()
    fun update(measurement: PathCurve?, timeMs: Long, ego: EgoMotion?): TrackState
}

class LegacyPathTrack(private val t: PathTracker = PathTracker()) : PathTrack {
    override val state get() = t.state
    override val curve get() = t.curve
    override val confidence get() = t.confidence
    override fun reset() = t.reset()
    override fun update(measurement: PathCurve?, timeMs: Long, ego: EgoMotion?) = t.update(measurement)
}

data class TemporalTrackerConfig(
    /** Variance added per SECOND to offset, slope and bend (time-based, not per frame: frames are not evenly spaced). */
    val processNoisePerS: DoubleArray = doubleArrayOf(0.08, 0.04, 0.01),
    /** Frame-to-frame noise of a measured path (segmentation edges, route recomputation) is far larger than the fit
     *  residual inside one frame; this floor (sigma ~9 cm) keeps the filter from following it. */
    val minMeasurementVar: Double = 0.008,
    val confirmCount: Int = 3,
    /** No evidence for this long: the path is dropped (LOST), never drawn on from memory alone. */
    val coastLimitS: Double = 1.0,
    /** While coasting, confidence falls as exp(-t / tau). */
    val confidenceTauS: Double = 0.45,
    /** Association: mean lateral gap between prediction and measurement at [gateAtM] must be within the gate,
     *  which widens while coasting. */
    val gateM: Double = 0.45,
    val gateGrowthPerS: Double = 0.8,
    val gateAtM: DoubleArray = doubleArrayOf(1.0, 2.0, 3.0),
    /** A measurement outside the gate replaces the track only after it has held consistently this long (a real change of
     *  path, a fork taken), so one stray frame never moves the path. */
    val switchConfirmS: Double = 0.35,
)

/**
 * Temporal Path Tracking Engine (research round 6, owner's stabilization request).
 *
 * The path seen in this frame is treated as the continuation of the path already tracked, when the evidence
 * supports it: the tracked path is first carried forward to the current moment (ego-motion: the runner's advance and
 * turn since the last frame, so a path fixed on the ground stays fixed on the ground), then compared with the new
 * measurement at several distances ahead. Inside the association gate it is updated (Kalman filter on the curve
 * coefficients, time-based process noise, measurement noise from the fit residual); outside it the measurement is
 * held as a challenger and replaces the track only if it persists ([TemporalTrackerConfig.switchConfirmS]). Without
 * evidence the track coasts on the prediction with confidence decaying over time, and is dropped after
 * [TemporalTrackerConfig.coastLimitS] instead of being shown as if it were still seen.
 */
class TemporalPathTracker(private val config: TemporalTrackerConfig = TemporalTrackerConfig()) : PathTrack {
    private var x: DoubleArray? = null
    private val p = DoubleArray(3)
    private var range = 0.0 to 0.0
    private var hits = 0
    private var lastMs: Long? = null
    private var lastEvidenceMs = 0L
    private var nowMs = 0L
    private var challenger: PathCurve? = null
    private var challengerSinceMs = 0L

    override var state: TrackState = TrackState.SEARCHING
        private set

    /** True when the current curve comes from the prediction only (no measurement in this frame). */
    var predictedOnly = false
        private set

    /** Number of times the track was replaced by a confirmed different path (diagnostics). */
    var switches = 0
        private set

    override val curve: PathCurve?
        get() = x?.let { PathCurve(it.copyOf(), range.first, range.second) }

    override val confidence: Double
        get() = when (state) {
            TrackState.TRACKING -> 1.0
            TrackState.COASTING -> exp(-((nowMs - lastEvidenceMs) / 1000.0) / config.confidenceTauS)
            TrackState.CONFIRMING -> 0.3
            else -> 0.0
        }

    override fun reset() {
        x = null; hits = 0; state = TrackState.SEARCHING; challenger = null; predictedOnly = false
    }

    override fun update(measurement: PathCurve?, timeMs: Long, ego: EgoMotion?): TrackState {
        val dt = lastMs?.let { ((timeMs - it) / 1000.0).coerceIn(0.0, 0.5) } ?: 0.0
        lastMs = timeMs
        nowMs = timeMs
        val cur = x
        if (cur == null) {
            if (measurement != null) start(measurement, timeMs)
            return state
        }
        // 1. Predict: carry the ground path to the present (ego-motion), uncertainty grows with time
        if (ego != null && (ego.forwardM != 0.0 || ego.yawRad != 0.0 || ego.lateralM != 0.0)) {
            val moved = ego.apply(PathCurve(cur.copyOf(), range.first, range.second))
            for (i in 0..2) cur[i] = moved.coeffs.getOrElse(i) { 0.0 }
            range = moved.nearM to moved.farM
        }
        for (i in 0..2) p[i] += config.processNoisePerS[i] * dt
        predictedOnly = true
        val sinceEvidence = (timeMs - lastEvidenceMs) / 1000.0
        val z = measurement?.let { DoubleArray(3) { k -> it.coeffs.getOrElse(k) { 0.0 } } }
        val gap = measurement?.let { gapTo(cur, it) }
        val gate = config.gateM + config.gateGrowthPerS * sinceEvidence
        if (z != null && gap != null && gap <= gate) {
            val r = max(config.minMeasurementVar, measurement.rmsM * measurement.rmsM)
            for (i in 0..2) {
                val k = p[i] / (p[i] + r * (i + 1))
                cur[i] += k * (z[i] - cur[i])
                p[i] *= (1 - k)
            }
            range = measurement.nearM to measurement.farM
            hits++
            lastEvidenceMs = timeMs
            predictedOnly = false
            challenger = null
            state = if (hits >= config.confirmCount) TrackState.TRACKING else TrackState.CONFIRMING
            return state
        }
        // 2. A measurement that does not continue the track: hold it as a challenger
        if (measurement != null) {
            val ch = challenger
            if (ch == null || gapTo(DoubleArray(3) { k -> ch.coeffs.getOrElse(k) { 0.0 } }, measurement) > config.gateM) {
                challenger = measurement; challengerSinceMs = timeMs
            } else {
                challenger = measurement
                if ((timeMs - challengerSinceMs) / 1000.0 >= config.switchConfirmS || state == TrackState.CONFIRMING) {
                    switches++
                    start(measurement, timeMs, confirmed = state != TrackState.CONFIRMING)
                    return state
                }
            }
        }
        // 3. No usable evidence: coast with decaying confidence, drop after the limit
        if (state == TrackState.CONFIRMING) {
            reset(); state = TrackState.LOST
            if (measurement != null) start(measurement, timeMs)
            return state
        }
        if (sinceEvidence > config.coastLimitS) {
            reset(); state = TrackState.LOST
            return state
        }
        state = TrackState.COASTING
        return state
    }

    private fun start(m: PathCurve, timeMs: Long, confirmed: Boolean = false) {
        x = DoubleArray(3) { k -> m.coeffs.getOrElse(k) { 0.0 } }
        for (i in 0..2) p[i] = 0.05
        range = m.nearM to m.farM
        hits = if (confirmed) config.confirmCount else 1
        lastEvidenceMs = timeMs
        challenger = null
        predictedOnly = false
        state = if (hits >= config.confirmCount) TrackState.TRACKING else TrackState.CONFIRMING
    }

    /** Mean lateral distance between two curves at the gate distances that both cover (or the nearest ones). */
    private fun gapTo(c: DoubleArray, m: PathCurve): Double {
        var sum = 0.0; var n = 0
        for (f in config.gateAtM) {
            if (f < m.nearM - 0.5 || f > max(m.farM, m.nearM + 1.0)) continue
            sum += abs(c[0] + c[1] * f + c[2] * f * f - m.lateralAt(f)); n++
        }
        if (n == 0) { val f = min(max(m.nearM, 1.0), m.farM); return abs(c[0] + c[1] * f + c[2] * f * f - m.lateralAt(f)) }
        return sum / n
    }
}
