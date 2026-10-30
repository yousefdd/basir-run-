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
