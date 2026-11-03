package run.basir.engine

import kotlin.math.abs
import kotlin.math.exp

data class StabilityConfig(
    /** The path is held as its lateral position at these distances ahead (m). */
    val anchorsM: DoubleArray = DoubleArray(12) { 0.5 + 0.5 * it }, // 0.5 .. 6.0 m (PathCurve.fit needs >= 8 points for a bend)
    /** Changes smaller than this are treated as measurement noise and followed slowly ... */
    val deadbandM: Double = 0.06,
    val tauSlowS: Double = 0.35,
    /** ... larger ones are followed quickly (a real change must not lag). */
    val tauFastS: Double = 0.06,
    /** Physical bound: how fast the path may move sideways under the runner's feet (body sway + steering) ... */
    val maxLateralMps: Double = 1.5,
    /** ... and how fast its direction may turn; a point f metres ahead may move by maxLateral + f * maxYaw per second. */
    val maxYawRateRadPerS: Double = 1.0,
    val widthTauS: Double = 0.6,
    val maxWidthRateMps: Double = 0.6,
    /** Round 7: when the tracker starts a new track (source switch line <-> free ground, a re-acquired path) that lies
     *  within this mean gap of the drawn path, the drawn path moves to it at the fast rate within the physical bound
     *  (one to three frames) instead of jumping in one frame. Further away it is another way: drawn at once.
     *  0 = the 0.13.0 behaviour (every new track resets the drawn path), the default: measured on 24 clips the gain
     *  (frame-to-frame variation -2.5 %) did not outweigh the added lag (+5 %), decision log case D. */
    val handoverMaxGapM: Double = 0.0,
)

/**
 * Path Stability Controller (research round 6): makes the followed path visually and numerically steady while it
 * keeps following the real way.
 *
 * The previous stable path is carried to the present by the runner's ego-motion (so a path fixed on the ground stays
 * put on the ground), then each anchor moves toward the tracked path: small differences (noise of the segmentation
 * edge, a shadow) are followed with a slow time constant, large ones (a real bend, the runner drifting) with a fast
 * one, and no anchor may move faster than the physical bound. The stable curve is refitted through the anchors and
 * keeps the tracked path's measured range. The strip width is smoothed and rate-limited the same way. A new track
 * (the tracker confirmed a different path) or no path resets it: nothing is drawn from the controller's memory alone.
 *
 * Only the drawing and the direction cues use this path; obstacle checks use the tracked path directly, so stability
 * never delays a safety decision.
 */
class PathStabilityController(private val config: StabilityConfig = StabilityConfig()) {
    private var stable: PathCurve? = null
    private var halfWidth: Double? = null
    private var lastMs: Long? = null

    class Stable(val curve: PathCurve, val halfWidthM: Double?)

    fun reset() { stable = null; halfWidth = null; lastMs = null }

    fun update(tracked: PathCurve?, timeMs: Long, ego: EgoMotion?, stripHalfWidthM: Double?, newTrack: Boolean = false): Stable? {
        val dt = lastMs?.let { ((timeMs - it) / 1000.0).coerceIn(1e-3, 0.5) } ?: 0.0
        lastMs = timeMs
        if (tracked == null) { stable = null; halfWidth = null; return null }
        val prev0 = stable
        if (prev0 == null || dt == 0.0) {
            stable = tracked; halfWidth = stripHalfWidthM
            return Stable(tracked, stripHalfWidthM)
        }
        val prev = if (ego != null) ego.apply(prev0) else prev0
        val handover = newTrack && config.handoverMaxGapM > 0.0 && meanGap(prev, tracked) <= config.handoverMaxGapM
        if (newTrack && !handover) {
            stable = tracked; halfWidth = stripHalfWidthM
            return Stable(tracked, stripHalfWidthM)
        }
        val pts = ArrayList<GroundPoint>()
        for (a in config.anchorsM) {
            if (a < tracked.nearM - 0.3 || a > tracked.farM + 0.5) continue
            val target = tracked.lateralAt(a)
            val cur = prev.lateralAt(a)
            val e = target - cur
            val tau = if (abs(e) <= config.deadbandM && !handover) config.tauSlowS else config.tauFastS
            val alpha = 1 - exp(-dt / tau)
            val bound = (config.maxLateralMps + a * config.maxYawRateRadPerS) * dt
            pts += GroundPoint(a, cur + (alpha * e).coerceIn(-bound, bound))
        }
        val out = if (pts.size >= 3) PathCurve.fit(pts, maxDegree = 2)?.copy(nearM = tracked.nearM, farM = tracked.farM, rmsM = tracked.rmsM) ?: tracked
        else tracked
        stable = out
        halfWidth = when {
            stripHalfWidthM == null -> null
            halfWidth == null -> stripHalfWidthM
            else -> {
                val hw = halfWidth!!
                val step = (1 - exp(-dt / config.widthTauS)) * (stripHalfWidthM - hw)
                hw + step.coerceIn(-config.maxWidthRateMps * dt, config.maxWidthRateMps * dt)
            }
        }
        return Stable(out, halfWidth)
    }
}

/** Mean lateral gap between two paths over the anchors both cover (large when they share no range). */
private fun meanGap(a: PathCurve, b: PathCurve): Double {
    val from = maxOf(a.nearM, b.nearM, 0.5); val to = minOf(a.farM, b.farM, 6.0)
    if (to - from < 0.5) return Double.MAX_VALUE
    var sum = 0.0; var n = 0; var f = from
    while (f <= to + 1e-9) { sum += abs(a.lateralAt(f) - b.lateralAt(f)); n++; f += 0.5 }
    return sum / n
}

data class AdaptiveFrameConfig(
    /** Heavy surface models (walkable ground, guide line) on a straight, steadily tracked way: every this many ms ... */
    val stableIntervalMs: Long = 250,
    /** ... and after this long of steady tracking, up to this interval. */
    val relaxedIntervalMs: Long = 400,
    val relaxAfterMs: Long = 2000,
    /** Near a bend, an obstacle or people: at most this interval. */
    val alertIntervalMs: Long = 100,
    /** A frame older than this when its turn comes is skipped if a newer one is waiting (always work on the latest). */
    val maxFrameAgeMs: Long = 120,
    val fastYawRateRadPerS: Double = 0.6,
    val curveKappa: Double = 0.05,
    val obstacleNearM: Double = 6.0,
    val minConfidence: Double = 0.7,
    val sceneChange: Double = 0.25,
    val runningMps: Double = 2.0,
)

/**
 * Adaptive Frame Processing Controller (research round 6). The camera keeps delivering every frame and the light
 * work (line contrast detector, temporal tracking with ego-motion, guidance) runs on every frame; this controller
 * decides, from real timestamps, when the HEAVY surface models (walkable-ground segmentation, guide-line model) run:
 * every frame while the way is uncertain (tracking not confirmed, low confidence, fast turn of the phone, a sudden
 * scene change), at most every [AdaptiveFrameConfig.alertIntervalMs] near bends, obstacles or people, and only every
 * [AdaptiveFrameConfig.stableIntervalMs]..[AdaptiveFrameConfig.relaxedIntervalMs] on a straight, steadily tracked way.
 * Stale frames are dropped in favour of the newest one. The safety channel (object detector and depth) is never
 * gated here: [Decision.runSafety] is always true and it keeps running whenever its worker is free.
 */
class AdaptiveFrameController(private val config: AdaptiveFrameConfig = AdaptiveFrameConfig()) {
    data class Context(
        val frameTimeMs: Long,
        val nowMs: Long,
        val newerFrameWaiting: Boolean,
        val trackState: TrackState,
        val trackConfidence: Double,
        val curvature: Double = 0.0,
        val yawRateRadPerS: Double = 0.0,
        val speedMps: Double = 0.0,
        val obstacleWithinM: Double? = null,
        val peopleInView: Boolean = false,
        /** 0..1: how much the picture changed since the last keyframe (e.g. mean absolute luma difference / 255). */
        val sceneChange: Double = 0.0,
    )

    data class Decision(val process: Boolean, val keyframe: Boolean, val runSafety: Boolean, val intervalMs: Long, val reason: String)

    private var lastKeyframeMs: Long? = null
    private var stableSinceMs: Long? = null

    fun reset() { lastKeyframeMs = null; stableSinceMs = null }

    fun decide(c: Context): Decision {
        if (c.nowMs - c.frameTimeMs > config.maxFrameAgeMs && c.newerFrameWaiting) {
            return Decision(process = false, keyframe = false, runSafety = true, intervalMs = 0, reason = "stale frame, newer one waiting")
        }
        val steady = c.trackState == TrackState.TRACKING && c.trackConfidence >= config.minConfidence
        if (!steady) stableSinceMs = null else if (stableSinceMs == null) stableSinceMs = c.frameTimeMs
        val (interval, reason) = when {
            !steady -> 0L to "tracking not steady"
            abs(c.yawRateRadPerS) >= config.fastYawRateRadPerS -> 0L to "phone turning fast"
            c.sceneChange >= config.sceneChange -> 0L to "scene changed"
            abs(c.curvature) >= config.curveKappa -> config.alertIntervalMs to "bend ahead"
            c.obstacleWithinM != null && c.obstacleWithinM <= config.obstacleNearM -> config.alertIntervalMs to "obstacle near"
            c.peopleInView -> config.alertIntervalMs to "people in view"
            c.frameTimeMs - (stableSinceMs ?: c.frameTimeMs) >= config.relaxAfterMs && c.speedMps < config.runningMps -> config.relaxedIntervalMs to "steady straight way"
            else -> (if (c.speedMps >= config.runningMps) (config.stableIntervalMs * 0.7).toLong() else config.stableIntervalMs) to "steady"
        }
        val last = lastKeyframeMs
        val key = last == null || c.frameTimeMs - last >= interval
        if (key) lastKeyframeMs = c.frameTimeMs
        return Decision(process = true, keyframe = key, runSafety = true, intervalMs = interval, reason = reason)
    }
}

/** Composes per-frame ego-motion since a past moment, to bring a measurement made on an older frame to the present. */
class EgoHistory(private val keepMs: Long = 1500) {
    private val items = ArrayDeque<Pair<Long, EgoMotion>>()

    fun add(timeMs: Long, ego: EgoMotion) {
        items.addLast(timeMs to ego)
        while (items.isNotEmpty() && timeMs - items.first().first > keepMs) items.removeFirst()
    }

    /** The motion after [sinceMs] up to now, composed in order (steps older than the kept window are not known: zero). */
    fun since(sinceMs: Long): EgoMotion {
        var f = 0.0; var l = 0.0; var yaw = 0.0
        for ((t, e) in items) {
            if (t <= sinceMs) continue
            // express this step's translation in the frame at sinceMs, then accumulate the turn
            val c = kotlin.math.cos(yaw); val s = kotlin.math.sin(yaw)
            f += c * e.forwardM - s * e.lateralM
            l += s * e.forwardM + c * e.lateralM
            yaw += e.yawRad
        }
        return EgoMotion(f, l, yaw)
    }

    fun clear() = items.clear()
}
