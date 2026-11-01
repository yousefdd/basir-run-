package run.basir.engine

import kotlin.math.hypot
import kotlin.math.sqrt

/** One detection on the ground this frame (from an object detector's foot point, or a depth blob). */
data class ObstacleDetection(
    val position: GroundPoint,
    val radiusM: Double,
    val confidence: Double,
    val label: String = "obstacle",
)

/** An obstacle followed across frames. Velocity is in the runner frame (m/s). */
data class TrackedObstacle(
    val id: Int,
    val position: GroundPoint,
    val radiusM: Double,
    val confidence: Double,
    val label: String,
    val velocityForward: Double,
    val velocityLateral: Double,
    val hits: Int,
    val misses: Int,
) {
    val confirmed get() = hits >= 2
    val speed get() = hypot(velocityForward, velocityLateral)

    /** Where it will be in [dt] seconds if it keeps moving. */
    fun predict(dt: Double) = GroundPoint(position.forward + velocityForward * dt, position.lateral + velocityLateral * dt)
}

/**
 * Associates detections across frames (nearest neighbour inside a gate) so that a single-frame
 * false detection does not stop the runner, and so that moving people get a velocity. A track
 * is confirmed after two hits and dropped after [maxMisses] frames without a detection.
 */
class ObstacleTracker(
    private val gateM: Double = 0.8,
    private val maxMisses: Int = 5,
    private val velocitySmoothing: Double = 0.5,
) {
    private var nextId = 1
    private val tracks = ArrayList<TrackedObstacle>()

    /**
     * [runnerAdvanceM]: how far the runner moved forward since the last frame (from step
     * cadence / speed estimate); static obstacles then approach by that much.
     */
    fun update(detections: List<ObstacleDetection>, dt: Double, runnerAdvanceM: Double = 0.0): List<TrackedObstacle> {
        val used = BooleanArray(detections.size)
        val updated = ArrayList<TrackedObstacle>()
        for (t in tracks) {
            val pred = GroundPoint(t.predict(dt).forward - runnerAdvanceM, t.predict(dt).lateral)
            var best = -1
            var bestD = gateM
            detections.forEachIndexed { i, d ->
                if (used[i]) return@forEachIndexed
                val dist = hypot(d.position.forward - pred.forward, d.position.lateral - pred.lateral)
                if (dist < bestD) { bestD = dist; best = i }
            }
            if (best >= 0) {
                used[best] = true
                val d = detections[best]
                val s = velocitySmoothing
                // Velocity relative to the ground ahead: remove the runner's own advance
                val vf = (d.position.forward - t.position.forward + runnerAdvanceM) / dt
                val vl = (d.position.lateral - t.position.lateral) / dt
                updated += t.copy(
                    position = d.position, radiusM = d.radiusM, label = d.label,
                    confidence = s * t.confidence + (1 - s) * d.confidence,
                    velocityForward = s * t.velocityForward + (1 - s) * vf,
                    velocityLateral = s * t.velocityLateral + (1 - s) * vl,
                    hits = t.hits + 1, misses = 0,
                )
            } else if (t.misses + 1 <= maxMisses) {
                updated += t.copy(position = pred, misses = t.misses + 1, confidence = t.confidence * 0.8)
            }
        }
        detections.forEachIndexed { i, d ->
            if (!used[i]) updated += TrackedObstacle(nextId++, d.position, d.radiusM, d.confidence, d.label, 0.0, 0.0, 1, 0)
        }
        tracks.clear()
        tracks += updated
        return tracks.toList()
    }
}

/** Closest approach of a point to a polyline; returns (distance m, forward distance along the path). */
internal fun distanceToPath(p: GroundPoint, path: List<GroundPoint>): Pair<Double, Double> {
    var best = Double.MAX_VALUE
    var along = 0.0
    var acc = 0.0
    for (k in 1 until path.size) {
        val a = path[k - 1]
        val b = path[k]
        val vx = b.forward - a.forward
        val vy = b.lateral - a.lateral
        val len2 = vx * vx + vy * vy
        val t = if (len2 > 0) (((p.forward - a.forward) * vx + (p.lateral - a.lateral) * vy) / len2).coerceIn(0.0, 1.0) else 0.0
        val qx = a.forward + t * vx
        val qy = a.lateral + t * vy
        val d = hypot(p.forward - qx, p.lateral - qy)
        if (d < best) { best = d; along = acc + t * sqrt(len2) }
        acc += sqrt(len2)
    }
    return best to along
}

/**
 * Someone walking ahead the same way at the runner's pace does not block the way: the gap is not
 * closing. Measured on a SANPO walk behind a group (scenario suite, 01): they were carved out of the
 * walkable ground and stopped the walker for a third of the clip. An object counts as receding when its
 * ground speed along the way is within [CLOSING_MPS] of the runner's (velocities are only trusted after
 * [MIN_HITS] matched frames) and it is not already within [ALWAYS_M]; anything coming toward the runner
 * closes faster and is kept.
 */
object Receding {
    const val CLOSING_MPS = 0.3
    const val MIN_HITS = 4
    const val ALWAYS_M = 1.2
    /** Only things that can walk or roll away recede; a bench whose gap stops shrinking (the runner
     *  slowed down) is still a bench. Found on the owner's night video: without this the bench at the
     *  end was dropped once the owner stood still in front of it. */
    val MOBILE = setOf("person", "bicycle", "motorcycle", "dog", "cat", "horse", "car", "bus", "truck")

    fun isReceding(o: TrackedObstacle, runnerSpeedMps: Double): Boolean =
        o.label in MOBILE && o.hits >= MIN_HITS && o.position.forward > ALWAYS_M && runnerSpeedMps - o.velocityForward <= CLOSING_MPS
}

/**
 * Which object detections may become obstacles on the runner's way. Measured on a SANPO park path
 * (sanpo_curve4): D-FINE-N saw a "train" whose box covered most of the frame, its foot point fell
 * on the path 3 m ahead and the runner was stopped for 4 s. Boxes the size of the scene are not one
 * object, and trains, aircraft and boats are not on a footpath; both are dropped. Everything else is
 * kept (an unknown thing is not a clear way).
 */
object DetectionFilter {
    private val NOT_ON_A_FOOTPATH = setOf("train", "airplane", "boat")
    const val MAX_AREA = 0.45
    const val MAX_WIDTH = 0.85

    /** Small sports items and birds: on an empty athletics track (owner's clip) painted lane numbers came
     *  out as "tennis racket", "sports ball" and "bird" at 0.40-0.60; they need a surer detection. */
    val SMALL_ITEMS = setOf("sports ball", "tennis racket", "baseball bat", "baseball glove", "frisbee", "kite", "bird", "skis", "snowboard", "skateboard")
    const val SMALL_ITEM_MIN_SCORE = 0.6

    /** Round 4, real clips: a "bear" (0.65) covered a forest dirt trail and stopped the walker for 30 frames
     *  (archive.org CC0 trail clip); wild animals on a running path need a very sure detection. */
    val WILD_ANIMALS = setOf("bear", "elephant", "zebra", "giraffe")
    const val WILD_ANIMAL_MIN_SCORE = 0.8

    /** Worn items are part of a person, never an obstacle on their own ("tie" in a running crowd, 8 frames). */
    val WORN_ITEMS = setOf("tie")

    /** Box corners as fractions of the frame (0..1). */
    fun keep(label: String, left: Double, top: Double, right: Double, bottom: Double, score: Double = 1.0): Boolean {
        if (label in NOT_ON_A_FOOTPATH || label in WORN_ITEMS) return false
        if (label in SMALL_ITEMS && score < SMALL_ITEM_MIN_SCORE) return false
        if (label in WILD_ANIMALS && score < WILD_ANIMAL_MIN_SCORE) return false
        val w = (right - left).coerceIn(0.0, 1.0); val h = (bottom - top).coerceIn(0.0, 1.0)
        return w * h <= MAX_AREA && w <= MAX_WIDTH
    }
}
