package com.basir.run.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One reading from the Fused Location Provider, as plain numbers so the logic can be tested. */
data class Fix(
    val timeNanos: Long,
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy radius in metres, or null when the provider gave none. */
    val accuracyM: Float?,
    /** Ground speed in m/s from GPS Doppler, or null when the provider gave none. */
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
)

data class GpsDiagnostics(
    val fixes: Int = 0,
    val usedSpeed: Int = 0,
    val usedPosition: Int = 0,
    val rejectedAccuracy: Int = 0,
    val rejectedJump: Int = 0,
    val lastAccuracyM: Float? = null,
    val lastSpeedMps: Float? = null,
)

/**
 * Distance and pace for the activity log. This is separate from guidance: GPS is never used
 * to place the runner inside a lane.
 *
 * GPS Doppler speed is much steadier than the change between two positions, so it is preferred
 * whenever the provider reports it. Positions are the fallback, with a looser accuracy limit
 * than before (the old 20 m limit rejected every fix on many phones, which is why the
 * distance stayed at 0).
 */
class DistanceTracker {

    var distanceM = 0.0
        private set
    var diagnostics = GpsDiagnostics()
        private set

    private var previous: Fix? = null
    private var anchor: Fix? = null
    private val recent = ArrayDeque<Pair<Long, Double>>()

    fun reset() {
        distanceM = 0.0
        diagnostics = GpsDiagnostics()
        previous = null
        anchor = null
        recent.clear()
    }

    fun accept(fix: Fix) {
        var d = diagnostics.copy(fixes = diagnostics.fixes + 1, lastAccuracyM = fix.accuracyM, lastSpeedMps = fix.speedMps)
        val last = previous
        previous = fix
        if (last == null) {
            anchor = fix
            diagnostics = d
            return
        }
        val seconds = (fix.timeNanos - last.timeNanos) / 1e9
        if (seconds <= 0.0) {
            diagnostics = d
            return
        }

        val speed = fix.speedMps
        val speedTrusted = speed != null && (fix.speedAccuracyMps == null || fix.speedAccuracyMps <= MAX_SPEED_ACCURACY_MPS)
        if (speedTrusted && (fix.accuracyM ?: 0f) <= MAX_ACCURACY_FOR_SPEED_M) {
            // Speed is reported even when standing still; ignore the slow drift.
            if (speed!! in MIN_MOVING_MPS..MAX_RUNNER_MPS) add(speed * min(seconds, MAX_GAP_S), fix.timeNanos)
            anchor = fix
            diagnostics = d.copy(usedSpeed = d.usedSpeed + 1)
            return
        }

        val accuracy = fix.accuracyM ?: Float.MAX_VALUE
        if (accuracy > MAX_ACCURACY_FOR_POSITION_M) {
            diagnostics = d.copy(rejectedAccuracy = d.rejectedAccuracy + 1)
            return
        }
        val from = anchor ?: fix.also { anchor = it }
        val step = metersBetween(from.lat, from.lon, fix.lat, fix.lon)
        val sinceAnchor = (fix.timeNanos - from.timeNanos) / 1e9
        if (sinceAnchor > 0 && step / sinceAnchor > MAX_RUNNER_MPS) {
            d = d.copy(rejectedJump = d.rejectedJump + 1)
            // A long gap makes the old anchor meaningless; restart from here.
            if (sinceAnchor > MAX_GAP_S) anchor = fix
            diagnostics = d
            return
        }
        if (step >= max(MIN_STEP_M, accuracy * NOISE_FACTOR)) {
            add(step, fix.timeNanos)
            anchor = fix
        }
        diagnostics = d.copy(usedPosition = d.usedPosition + 1)
    }

    private fun add(meters: Double, timeNanos: Long) {
        distanceM += meters
        val ms = timeNanos / 1_000_000
        recent.addLast(ms to distanceM)
        while (recent.size > 1 && ms - recent.first().first > PACE_WINDOW_MS) recent.removeFirst()
    }

    /** Seconds per km over the last 30 seconds of movement, or null until there is enough of it. */
    fun currentPaceSecPerKm(): Int? {
        if (recent.size < 2) return null
        val (t0, d0) = recent.first()
        val (t1, d1) = recent.last()
        val meters = d1 - d0
        if (meters < MIN_PACE_DISTANCE_M || t1 <= t0) return null
        return ((t1 - t0) / 1000.0 / (meters / 1000.0)).toInt()
    }

    companion object {
        private const val MAX_SPEED_ACCURACY_MPS = 1.5f
        private const val MAX_ACCURACY_FOR_SPEED_M = 50f
        private const val MAX_ACCURACY_FOR_POSITION_M = 35f
        private const val MIN_MOVING_MPS = 0.6f
        private const val MAX_RUNNER_MPS = 11f
        private const val MAX_GAP_S = 3.0
        private const val MIN_STEP_M = 3.0
        private const val NOISE_FACTOR = 0.4
        private const val PACE_WINDOW_MS = 30_000L
        private const val MIN_PACE_DISTANCE_M = 20.0

        fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(min(1.0, h)))
        }
    }
}
