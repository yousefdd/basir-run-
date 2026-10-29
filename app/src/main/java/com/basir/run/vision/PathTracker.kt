package com.basir.run.vision

import kotlin.math.abs

data class TrackedPath(
    val estimate: PathEstimate,
    /** Smoothed offset and bend; only meaningful while the path is tracked. */
    val offset: Float,
    val curvature: Float,
    /** Most recent frames agree with each other. Guidance is only given while this holds. */
    val stable: Boolean,
)

/**
 * Smooths the path between frames and decides whether it is stable. A measurement that jumps
 * far from the running estimate is treated as noise (a bounce of the harness, a stray line),
 * not as a real change of path; it still counts against stability. While the gyroscope reports
 * a sharp jolt the frame moves the estimate only a little and its jumps are not held against
 * the path, because the picture itself is unreliable for that instant. Losing the path is never
 * smoothed: an untracked frame breaks stability at once.
 */
class PathTracker {

    private var offset = 0f
    private var curvature = 0f
    private var initialized = false
    private var missed = 0
    private val agreement = ArrayDeque<Boolean>()

    fun reset() {
        initialized = false
        missed = 0
        agreement.clear()
    }

    fun update(m: PathEstimate, shaky: Boolean = false): TrackedPath {
        if (m.status != PathStatus.Tracked) {
            record(false)
            if (++missed > RESET_AFTER_MISSES) initialized = false
            return TrackedPath(m, offset, curvature, stable = false)
        }
        missed = 0
        if (!initialized) {
            offset = m.offset
            curvature = m.curvature
            initialized = true
            record(true)
        } else {
            val jump = abs(m.offset - offset) > OFFSET_GATE || abs(m.curvature - curvature) > CURVE_GATE
            if (!(jump && shaky)) record(!jump)
            // A jump or a jolt moves the estimate only a little, so one bad frame cannot swing guidance.
            val gain = if (jump || shaky) JUMP_GAIN else GAIN
            offset += gain * (m.offset - offset)
            curvature += gain * (m.curvature - curvature)
        }
        val stable = agreement.size >= WINDOW && agreement.count { it } >= NEEDED && agreement.last()
        return TrackedPath(m, offset, curvature, stable)
    }

    private fun record(ok: Boolean) {
        agreement.addLast(ok)
        while (agreement.size > WINDOW) agreement.removeFirst()
    }

    companion object {
        private const val WINDOW = 8
        private const val NEEDED = 6
        private const val OFFSET_GATE = 0.35f
        private const val CURVE_GATE = 0.5f
        private const val GAIN = 0.4f
        private const val JUMP_GAIN = 0.1f
        private const val RESET_AFTER_MISSES = 15
    }
}
