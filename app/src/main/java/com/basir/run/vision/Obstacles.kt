package com.basir.run.vision

/** One object found by the detector; box in upright frame fractions. */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Decides which detections matter: objects of a supported class whose box reaches into the
 * lower part of the frame and overlaps the runnable corridor. Box size and position are used
 * only as a rough sign of "near"; they are not a distance and no distance is ever announced.
 *
 * The model (EfficientDet-Lite0, COCO classes) knows people, bicycles, benches, dogs, vehicles
 * and similar objects. It does not know poles, tree trunks, holes, curbs or steps; those are
 * not detected.
 */
object ObstacleFilter {

    val SUPPORTED = setOf(
        "person", "bicycle", "car", "motorcycle", "bus", "truck", "bench", "chair", "dog", "cat",
        "horse", "fire hydrant", "stop sign", "parking meter", "potted plant", "suitcase",
        "backpack", "handbag", "umbrella", "sports ball", "skateboard", "bottle", "bird",
    )

    const val MIN_SCORE = 0.45f
    /** The box must reach below this line of the frame to be on the ground ahead. */
    const val MIN_BOTTOM = 0.55f
    const val MIN_SIZE = 0.12f
    /** Half width of the corridor used when the path itself is not tracked. */
    const val FALLBACK_HALF_WIDTH = 0.2f

    fun isRelevant(d: Detection, path: PathEstimate?, runnerX: Float): Boolean {
        if (d.label !in SUPPORTED || d.score < MIN_SCORE) return false
        if (d.bottom < MIN_BOTTOM) return false
        if (d.right - d.left < MIN_SIZE && d.bottom - d.top < MIN_SIZE) return false
        val y = d.bottom.coerceAtMost(1f)
        val (from, to) = if (path != null && path.status == PathStatus.Tracked && path.left != null && path.right != null) {
            path.left.x(y) to path.right.x(y)
        } else {
            (runnerX - FALLBACK_HALF_WIDTH) to (runnerX + FALLBACK_HALF_WIDTH)
        }
        return d.right > from && d.left < to
    }
}

/** An obstacle is reported only when seen on at least 2 of the last 3 detector runs. */
class ObstacleConfirmation {
    private val recent = ArrayDeque<Boolean>()

    fun update(relevantNow: Boolean): Boolean {
        recent.addLast(relevantNow)
        while (recent.size > 3) recent.removeFirst()
        return recent.count { it } >= 2
    }

    fun reset() = recent.clear()
}
