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

enum class ObstacleMotion { Unknown, Still, Approaching, Crossing }

/** What kind of hazard raised the obstacle level, so the runner hears the right sentence. */
enum class HazardKind {
    /** Something on the ground in the corridor (a person, a bench, a pole). */
    Object,
    /** Something at head height that a cane would pass under (a branch, a sign). */
    HeadLevel,
    /** A possible step down or hole. */
    Drop,
}

data class ObstacleFinding(
    val detection: Detection,
    val overlap: Float,
    val level: ObstacleLevel,
    val motion: ObstacleMotion = ObstacleMotion.Unknown,
)

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
    /** A box reaching this low is cut by the frame's bottom edge. */
    const val BOTTOM_EDGE = 0.97f

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

    /**
     * Round 7: the same decision against the metric path the engine follows (and draws), instead of the fallback
     * detectors' image-space corridor or a fixed fraction of the screen. The object's foot point (bottom centre of its
     * box) is put on the ground; it is in the way when its body overlaps a corridor of [halfWidthM] around the path's
     * centre line at that distance. Near (Hazard) = within [stopDistanceM]; up to [warnDistanceM] = Possible; further
     * or beside the path = not a finding. Null also when the box does not reach the ground (above the horizon).
     */
    fun assessOnPath(
        d: Detection, path: run.basir.engine.PathCurve, camera: run.basir.engine.CameraModel,
        halfWidthM: Double = PathLinkedHazards.HALF_WIDTH_M, stopDistanceM: Double = PathLinkedHazards.STOP_M,
        warnDistanceM: Double = PathLinkedHazards.WARN_M,
    ): ObstacleFinding? {
        if (d.label !in SUPPORTED || d.score < MIN_SCORE) return null
        val v = (d.bottom * camera.height).toDouble().coerceAtMost(camera.height - 1.0)
        val u = ((d.left + d.right) / 2 * camera.width).toDouble()
        val foot = camera.imageToGround(u, v) ?: return null
        if (foot.forward <= 0.0 || foot.forward > warnDistanceM) return null
        val mpp = camera.lateralMetersPerPixel(v) ?: return null
        val radius = ((d.right - d.left) * camera.width * mpp / 2).coerceIn(0.1, 2.0)
        val far = maxOf(path.farM, path.nearM + 0.5)
        val centre = if (foot.forward <= far) path.lateralAt(foot.forward)
        else path.lateralAt(far) + (path.lateralAt(far) - path.lateralAt(far - 0.25)) / 0.25 * (foot.forward - far)
        // Within stopping distance the runner's own heading counts too (they may not follow the path exactly):
        // measured, a tent passed at ~0.4 m was missed when only the planned path's corridor was checked.
        val onPath = kotlin.math.abs(foot.lateral - centre) - radius <= halfWidthM
        // A box cut by the bottom edge stands at or nearer than the nearest visible ground (its foot is out of view)
        val near = foot.forward <= stopDistanceM || d.bottom >= BOTTOM_EDGE
        // Straight ahead of the runner within the warning distance (we are already within it): at least a warning,
        // even when the planned path goes round it — the runner is heading at it now (measured: a tent 3 m ahead
        // with the path bending past it was otherwise never announced).
        val ahead = kotlin.math.abs(foot.lateral) - radius <= halfWidthM
        if (!onPath && !ahead) return null
        val c = if (onPath) centre else 0.0
        // Share of the object's width inside the corridor
        val inside = (minOf(foot.lateral + radius, c + halfWidthM) - maxOf(foot.lateral - radius, c - halfWidthM)).coerceAtLeast(0.0)
        val overlap = (inside / (2 * radius)).toFloat()
        val level = if (near && overlap >= HAZARD_OVERLAP && (onPath || foot.forward <= stopDistanceM || d.bottom >= BOTTOM_EDGE)) ObstacleLevel.Hazard else ObstacleLevel.Possible
        return ObstacleFinding(d, overlap, level)
    }

    /** Kept for callers that only need yes or no. */
    fun isRelevant(d: Detection, path: PathEstimate?, runnerX: Float) = assess(d, path, runnerX) != null
}

/**
 * Round 7 switch: obstacles (detections and depth) are judged against the metric path the engine follows. Off = the
 * 0.13.0 behaviour (image-space corridor of the fallback detectors / straight depth corridor along the path's bearing).
 * The corridor is body-wide (the depth channel's 1.2 m), so an object beside a wide walkable strip is not "in the way".
 */
object PathLinkedHazards {
    @Volatile var enabled = true
    const val HALF_WIDTH_M = 0.6
    /** Stop distance of the depth channel (DepthHazards.RUNNING.stopDistanceM). */
    const val STOP_M = 2.5
    /** Warning distance of the depth channel (DepthHazards.RUNNING.safeDistanceM), so both channels agree. */
    const val WARN_M = 4.0
    /** Below this camera tilt the ground is seen almost edge-on and image-to-ground distances are meaningless (an
     *  object 4 m away projects beyond 6 m): the 0.13.0 image-space corridor is used instead. Measured on a SANPO clip
     *  filmed with a level camera (pitch about -1 deg), where the metric check dropped every approaching person. */
    const val MIN_PITCH_DEG = 5.0
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

/**
 * Follows detections from one detector run to the next (same label, overlapping boxes) to see
 * whether an object is approaching (its box grows and its bottom moves down the frame) or
 * crossing towards the corridor. A possible obstacle that approaches or crosses is raised to a
 * hazard. This is a direction of change in the image, not a measured speed or distance.
 */
class ObstacleMotionTracker {
    private var previous: List<Detection> = emptyList()

    fun reset() {
        previous = emptyList()
    }

    fun annotate(findings: List<ObstacleFinding>, all: List<Detection>, corridorCenter: Float): List<ObstacleFinding> {
        val result = findings.map { f ->
            val d = f.detection
            val before = previous.filter { it.label == d.label }.maxByOrNull { iou(it, d) }?.takeIf { iou(it, d) >= MIN_IOU }
            if (before == null) return@map f
            val grew = (d.bottom - d.top) > (before.bottom - before.top) * (1 + GROWTH) || d.bottom - before.bottom > DROP
            val oldGap = kotlin.math.abs((before.left + before.right) / 2 - corridorCenter)
            val newGap = kotlin.math.abs((d.left + d.right) / 2 - corridorCenter)
            val motion = when {
                grew -> ObstacleMotion.Approaching
                oldGap - newGap > SIDEWAYS -> ObstacleMotion.Crossing
                else -> ObstacleMotion.Still
            }
            val level = if (f.level == ObstacleLevel.Possible && motion != ObstacleMotion.Still) ObstacleLevel.Hazard else f.level
            f.copy(level = level, motion = motion)
        }
        previous = all
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val w = kotlin.math.min(a.right, b.right) - kotlin.math.max(a.left, b.left)
        val h = kotlin.math.min(a.bottom, b.bottom) - kotlin.math.max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter
        return inter / union
    }

    companion object {
        private const val MIN_IOU = 0.3f
        private const val GROWTH = 0.08f
        private const val DROP = 0.02f
        private const val SIDEWAYS = 0.02f
    }
}
