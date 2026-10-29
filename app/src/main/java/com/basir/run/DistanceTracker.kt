package com.basir.run

import android.location.Location
import kotlin.math.max

// Turns a stream of GPS fixes into distance and current pace, rejecting the noise
// that makes phone GPS add distance while standing still or jump across the track.
class DistanceTracker {

    var distanceM = 0f
        private set

    private var anchor: Location? = null
    private val recent = ArrayDeque<Pair<Long, Float>>() // (elapsed ms, distance) for pace

    fun reset() {
        distanceM = 0f
        anchor = null
        recent.clear()
    }

    fun accept(location: Location, elapsedMs: Long) {
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_M) return
        val previous = anchor
        if (previous == null) {
            anchor = location
            return
        }
        val seconds = (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1e9f
        if (seconds <= 0f) return
        val step = previous.distanceTo(location)
        if (step / seconds > MAX_SPEED_MPS) return // a GPS jump, not a runner
        if (step < max(MIN_STEP_M, location.accuracy * NOISE_FACTOR)) return // jitter in place

        distanceM += step
        anchor = location
        recent.addLast(elapsedMs to distanceM)
        while (recent.size > 1 && elapsedMs - recent.first().first > PACE_WINDOW_MS) recent.removeFirst()
    }

    // Seconds per km over the last 30 seconds, or null until there is enough movement.
    fun currentPaceSecPerKm(): Int? {
        if (recent.size < 2) return null
        val (t0, d0) = recent.first()
        val (t1, d1) = recent.last()
        val meters = d1 - d0
        if (meters < MIN_PACE_DISTANCE_M) return null
        return ((t1 - t0) / 1000f / (meters / 1000f)).toInt()
    }

    companion object {
        private const val MAX_ACCURACY_M = 20f
        private const val MAX_SPEED_MPS = 11f
        private const val MIN_STEP_M = 3f
        private const val NOISE_FACTOR = 0.4f
        private const val PACE_WINDOW_MS = 30_000L
        private const val MIN_PACE_DISTANCE_M = 20f
    }
}
