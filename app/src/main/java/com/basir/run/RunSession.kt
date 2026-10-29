package com.basir.run

import kotlinx.coroutines.flow.MutableStateFlow

data class RunRecord(val startedAt: Long, val distanceM: Float, val durationMs: Long) {
    val paceSecPerKm: Int?
        get() = if (distanceM < MIN_PACE_DISTANCE_M) null else (durationMs / 1000f / (distanceM / 1000f)).toInt()

    val laps: Int get() = (distanceM / TRACK_LAP_M).toInt()

    companion object {
        const val TRACK_LAP_M = 400f
        private const val MIN_PACE_DISTANCE_M = 50f
    }
}

data class RunState(
    val active: Boolean = false,
    val elapsedMs: Long = 0L,
    val distanceM: Float = 0f,
    val accuracyM: Float? = null,
    val paceSecPerKm: Int? = null,
    val finished: RunRecord? = null,
)

// Shared between RunService, which writes it, and the screens, which observe it.
object RunSession {
    val state = MutableStateFlow(RunState())
}
