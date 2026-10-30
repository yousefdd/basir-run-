package run.basir.engine

import kotlin.math.max

data class TrackerConfig(
    val processNoise: DoubleArray = doubleArrayOf(0.02, 0.01, 0.002), // per frame, for c0, c1, c2
    val minMeasurementVar: Double = 0.003,
    val maxCoastFrames: Int = 15,        // ~0.5 s at 30 fps: a person crossing the line
    val confirmFrames: Int = 3,          // detections needed before the track is trusted
    val gateM: Double = 0.6,             // a new detection farther than this is another line
    val gateAtM: Double = 3.0,           // compared this far ahead
)

enum class TrackState { SEARCHING, CONFIRMING, TRACKING, COASTING, LOST }

/**
 * Temporal smoothing of the followed path: a Kalman filter on the curve coefficients
 * (c0 lateral offset, c1 heading slope, c2 bend) in the runner frame. It keeps the line steady
 * frame to frame, bridges short gaps (line hidden behind a person, worn paint) by coasting on
 * the prediction with growing uncertainty, and only declares TRACKING after several consistent
 * detections. A detection far from the prediction is treated as a different line and ignored
 * while the current one can still be coasted.
 */
class PathTracker(private val config: TrackerConfig = TrackerConfig()) {
    private var x: DoubleArray? = null           // state: c0, c1, c2
    private val p = Array(3) { DoubleArray(3) }   // covariance (diagonal kept for robustness)
    private var hits = 0
    private var misses = 0
    private var range = 0.0 to 0.0

    var state: TrackState = TrackState.SEARCHING
        private set

    /** Current smoothed path, or null when nothing is tracked. */
    val curve: PathCurve?
        get() = x?.let { PathCurve(it.copyOf(), range.first, range.second) }

    /** 0..1: how much the guidance may rely on the track (drops while coasting). */
    val confidence: Double
        get() = when (state) {
            TrackState.TRACKING -> 1.0
            TrackState.COASTING -> max(0.0, 1.0 - misses.toDouble() / config.maxCoastFrames)
            TrackState.CONFIRMING -> 0.3
            else -> 0.0
        }

    fun reset() {
        x = null; hits = 0; misses = 0; state = TrackState.SEARCHING
    }

    /** Feed one frame's best measurement (null if no line was found). */
    fun update(measurement: PathCurve?): TrackState {
        val z = measurement?.let { coeffs3(it) }
        val cur = x
        if (cur == null) {
            if (z != null) start(z, measurement)
            return state
        }
        // Predict: the path in the runner frame changes slowly; uncertainty grows
        for (i in 0..2) p[i][i] += config.processNoise[i]

        val consistent = z != null && kotlin.math.abs(eval(z, config.gateAtM) - eval(cur, config.gateAtM)) <= config.gateM
        if (z != null && consistent) {
            val r = max(config.minMeasurementVar, measurement!!.rmsM * measurement.rmsM)
            for (i in 0..2) {
                val k = p[i][i] / (p[i][i] + r * (i + 1))
                cur[i] += k * (z[i] - cur[i])
                p[i][i] *= (1 - k)
            }
            range = measurement.nearM to measurement.farM
            hits++
            misses = 0
            state = if (hits >= config.confirmFrames) TrackState.TRACKING else TrackState.CONFIRMING
        } else {
            misses++
            if (misses > config.maxCoastFrames || state == TrackState.CONFIRMING) {
                // Lost: start over on the new line, if there is one
                reset()
                if (z != null) start(z, measurement)
                if (state == TrackState.SEARCHING) state = TrackState.LOST
            } else {
                state = TrackState.COASTING
            }
        }
        return state
    }

    private fun start(z: DoubleArray, m: PathCurve?) {
        x = z.copyOf()
        for (i in 0..2) { p[i][i] = 0.05; for (j in 0..2) if (i != j) p[i][j] = 0.0 }
        hits = 1
        misses = 0
        range = (m?.nearM ?: 0.0) to (m?.farM ?: 0.0)
        state = if (config.confirmFrames <= 1) TrackState.TRACKING else TrackState.CONFIRMING
    }

    private fun coeffs3(c: PathCurve) = DoubleArray(3) { c.coeffs.getOrElse(it) { 0.0 } }
    private fun eval(c: DoubleArray, f: Double) = c[0] + c[1] * f + c[2] * f * f
}
