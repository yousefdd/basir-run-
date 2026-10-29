package com.basir.run.vision

import kotlin.math.abs

enum class PathConfidence {
    /** Both edges observed, tracking stable, confidence above the guidance threshold. */
    Confirmed,
    /** Something was seen, but not enough to guide by. */
    Uncertain,
    /** Nothing usable in this frame. */
    None,
}

/** One sample of the virtual running path, from the far end (small y) to the runner's feet (y = 1). */
data class PathPoint(
    val y: Float,
    val left: Float?,
    val right: Float?,
    /** Centre of the runnable area; only present when both edges exist at this row. */
    val center: Float?,
    /** True when the detector saw this edge on a nearby row; false when it comes from the fitted curve only. */
    val leftObserved: Boolean,
    val rightObserved: Boolean,
)

/**
 * The expected running path ahead as a series of points rather than one straight line, so bends
 * keep their shape. Every edge point says whether it was observed or only estimated by the curve.
 * A centre exists only where both edges exist; it is never derived from a single edge.
 */
data class VirtualPath(
    val points: List<PathPoint>,
    val confidence: PathConfidence,
    val source: PathSource,
    /** Share of edge points that were observed rather than estimated. */
    val observedShare: Float,
) {
    companion object {
        const val POINTS = 12
        /** An edge point counts as observed when a raw observation lies this close (frame fraction). */
        private const val OBSERVED_TOLERANCE = 0.035f

        fun from(tracked: TrackedPath, minConfidence: Float): VirtualPath {
            val e = tracked.estimate
            val confidence = when {
                e.status == PathStatus.Tracked && tracked.stable && e.confidence >= minConfidence -> PathConfidence.Confirmed
                e.status == PathStatus.Unclear && e.left == null && e.right == null -> PathConfidence.None
                else -> PathConfidence.Uncertain
            }
            if (e.left == null && e.right == null) return VirtualPath(emptyList(), confidence, e.source, 0f)

            val rowGap = (1f - e.roiTop) / POINTS
            var observed = 0
            var total = 0
            val points = (0 until POINTS).map { i ->
                val y = e.roiTop + (1f - e.roiTop) * (i + 0.5f) / POINTS
                val nearby = e.evidence.filter { abs(it.y - y) <= rowGap }
                val left = e.left?.x(y)
                val right = e.right?.x(y)
                val leftSeen = left != null && nearby.any { it.left != null && abs(it.left - left) < OBSERVED_TOLERANCE }
                val rightSeen = right != null && nearby.any { it.right != null && abs(it.right - right) < OBSERVED_TOLERANCE }
                if (left != null) { total++; if (leftSeen) observed++ }
                if (right != null) { total++; if (rightSeen) observed++ }
                PathPoint(
                    y = y,
                    left = left,
                    right = right,
                    center = if (left != null && right != null && e.status == PathStatus.Tracked) (left + right) / 2 else null,
                    leftObserved = leftSeen,
                    rightObserved = rightSeen,
                )
            }
            return VirtualPath(points, confidence, e.source, if (total == 0) 0f else observed.toFloat() / total)
        }
    }
}
