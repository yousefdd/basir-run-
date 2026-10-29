package com.basir.run.vision

import kotlin.math.max
import kotlin.math.min

/** One object found by the detector; box in upright frame fractions. */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

enum class ObstacleLevel {
    None,
    /** Something overlaps the running corridor; slow down and pay attention. */
    Possible,
    /** Large, low in the frame and well inside the corridor; stop and check. */
    Hazard,
}

data class ObstacleFinding(val detection: Detection, val overlap: Float, val level: ObstacleLevel)

/**
 * How much a detection matters for the runner. A box being in the picture is not a hazard by
 * itself: it has to be a supported class, reach the ground ahead, and overlap the corridor.
 * Box size and height in the frame are only a rough sign of "near"; no distance is computed
 * or announced.
 *
 * The model (EfficientDet-Lite0, COCO classes) knows people, bicycles, benches, dogs, vehicles
 * and similar objects. It does not know poles, tree trunks, holes, curbs or steps.
 */
object ObstacleAssessment {

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
    const val HAZARD_OVERLAP = 0.3f
    const val HAZARD_HEIGHT = 0.25f
    const val HAZARD_BOTTOM = 0.8f

    fun assess(d: Detection, path: PathEstimate?, runnerX: Float): ObstacleFinding? {
        if (d.label !in SUPPORTED || d.score < MIN_SCORE) return null
        if (d.bottom < MIN_BOTTOM) return null
        val width = d.right - d.left
        val height = d.bottom - d.top
        if (width < MIN_SIZE && height < MIN_SIZE) return null

        val y = min(d.bottom, 1f)
        val (from, to) = if (path != null && path.status == PathStatus.Tracked && path.left != null && path.right != null) {
            path.left.x(y) to path.right.x(y)
        } else {
            (runnerX - FALLBACK_HALF_WIDTH) to (runnerX + FALLBACK_HALF_WIDTH)
        }
        val shared = min(d.right, to) - max(d.left, from)
        if (shared <= 0f) return null
        val overlap = shared / max(width, 1e-3f)
        val near = height >= HAZARD_HEIGHT || d.bottom >= HAZARD_BOTTOM
        val level = if (overlap >= HAZARD_OVERLAP && near) ObstacleLevel.Hazard else ObstacleLevel.Possible
        return ObstacleFinding(d, overlap, level)
    }

    /** Kept for callers that only need yes or no. */
    fun isRelevant(d: Detection, path: PathEstimate?, runnerX: Float) = assess(d, path, runnerX) != null
}

/**
 * Confirms obstacles over the last three detector runs. A strong, near, central detection is
 * reported at once: the guard against flicker must never delay a warning about something
 * right in front of the runner.
 */
class ObstacleTracker {
    private val recent = ArrayDeque<ObstacleLevel>()

    fun update(findings: List<ObstacleFinding>): ObstacleLevel {
        val now = findings.maxOfOrNull { it.level } ?: ObstacleLevel.None
        recent.addLast(now)
        while (recent.size > 3) recent.removeFirst()
        val immediate = findings.any {
            it.level == ObstacleLevel.Hazard && it.detection.score >= 0.7f && it.detection.bottom >= ObstacleAssessment.HAZARD_BOTTOM
        }
        return when {
            immediate -> ObstacleLevel.Hazard
            recent.count { it == ObstacleLevel.Hazard } >= 2 -> ObstacleLevel.Hazard
            recent.count { it != ObstacleLevel.None } >= 2 -> ObstacleLevel.Possible
            else -> ObstacleLevel.None
        }
    }

    fun reset() = recent.clear()
}
