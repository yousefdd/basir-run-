package com.basir.run.vision.depth

import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel
import kotlin.math.abs

data class DepthHazard(
    val level: ObstacleLevel, val kind: HazardKind, val floorVisible: Boolean,
    /** Free distance (m) behind an Object level, when measured; lets the caller account for the result's age. */
    val freeM: Double? = null,
) {
    /**
     * Round 7: the depth result describes the frame it was computed on, [ageS] ago; the runner has since come
     * [speedMps]·[ageS] closer. A confirmed Object level is raised to Hazard when the remaining distance is within the
     * stop distance. Only escalation: nothing is lowered and no unconfirmed level is created.
     */
    fun aged(ageS: Double, speedMps: Double, cfg: FloorPlanner.Config): DepthHazard {
        val f = freeM ?: return this
        if (!DepthHazards.ageCompensation || kind != HazardKind.Object || level != ObstacleLevel.Possible) return this
        return if (f - speedMps * ageS.coerceAtLeast(0.0) <= cfg.stopDistanceM) copy(level = ObstacleLevel.Hazard) else this
    }
}

/**
 * Turns the floor planner's answer into what the runner needs. Thresholds are wider than the
 * walking tool's (2.0 m safe, 1.2 m stop) because a runner covers about 3 m every second; they
 * are starting values for field tests, not validated distances. Distances are never spoken.
 * The planner's suggested turn is not used: no way around an obstacle is offered.
 */
object DepthHazards {
    // Corridor 1.2 m wide (body plus arm swing) and points 10 % nearer than the floor count as
    // raised: on 280 human-labelled SANPO frames this raised the frames with a fixed obstacle within
    // 4 m that get a warning from 9 % to 30 %, with false alarms on clear frames 3 -> 4 of 145, the
    // same gain in both halves of the sessions (BasirRun_Research/benchmarks/results/depth_config_sweep.txt).
    val RUNNING = FloorPlanner.Config(safeDistanceM = 4.0, stopDistanceM = 2.5, lookaheadM = 6.0, bodyWidthM = 1.2, closerRatio = 0.9)

    /** Round 7 (experimental, off by default): warning and stop distances scaled with the speed setting, about 1.6 s and
     *  0.9 s of travel plus a margin, never above the running values; at running speed this is exactly [RUNNING]. */
    @Volatile var speedScaled = false
    /** Round 7 experiment (rejected, off): escalate a confirmed depth warning by the runner's advance since its frame
     *  (DepthHazard.aged). Measured with phone-like latency: no change at all, since DecisionEngine warns the same for
     *  Possible and Hazard ground objects. Decision log case D. */
    @Volatile var ageCompensation = false
    fun forSpeed(speedMps: Double): FloorPlanner.Config = if (!speedScaled) RUNNING else RUNNING.copy(
        safeDistanceM = (1.0 + 1.6 * speedMps).coerceIn(2.5, RUNNING.safeDistanceM),
        stopDistanceM = (0.8 + 0.9 * speedMps).coerceIn(1.5, RUNNING.stopDistanceM),
    )

    /**
     * [headingDeg] is the bearing of the path being followed; free distances are then read in the
     * planner's corridor nearest to it instead of straight ahead, so a wall beside a bending path
     * is not taken for an obstacle on it. Without a path the straight corridor is used.
     */
    fun assess(
        a: DepthAnalysis?, cfg: FloorPlanner.Config = RUNNING, headingDeg: Double? = null,
        /** Round 7: the path being followed (runner frame, metres). When given, raised points are looked for in a
         *  body-wide corridor ALONG this path (it bends with it) instead of a straight one along [headingDeg]. */
        path: run.basir.engine.PathCurve? = null,
    ): DepthHazard {
        if (a == null || !a.floorValid) return DepthHazard(ObstacleLevel.None, HazardKind.Object, floorVisible = false)
        val pts = a.obstaclePoints
        if (path != null && pts != null && com.basir.run.vision.PathLinkedHazards.enabled) {
            // Head-level parts and drops stay on the planner's heading nearest the path's bearing (as in 0.13.0): measured
            // on the replays, the curved corridor added head-level STOPs for poles, signs and scaffolding beside the path.
            val bearing = Math.toDegrees(kotlin.math.atan2(path.lateralAt(BEARING_AT_M), BEARING_AT_M))
            val along = a.headings.minByOrNull { abs(it.headingDeg - bearing) }
            if (headCorridor) {
                // Round 8: head level is judged where the head actually goes: along the followed path, head-and-shoulders
                // wide, and only up to the top of the runner's head (+ margin). Before, any point 1.1-2.1 m high in a
                // 1.2 m wide straight corridor (10 deg steps) counted: on the owner's garden video a towel 1.3 m to the
                // right of the path at 2.0-2.1 m height stopped the runner twice.
                val freeHead = alongPath(pts, path, HEAD_HALF_WIDTH_M, cfg, true, a.minPixels, maxHeightM = headClearanceM)
                val freeLow = alongPath(pts, path, cfg.bodyWidthM / 2, cfg, false, a.minPixels)
                // Same distances as objects on the ground: a warning within safeDistanceM, a stop only within stopDistanceM
                // (before, any head-level point within 4 m was a stop at once).
                if (freeHead < cfg.safeDistanceM && freeHead < freeLow - 0.4) {
                    val lvl = if (freeHead <= cfg.stopDistanceM) ObstacleLevel.Hazard else ObstacleLevel.Possible
                    return DepthHazard(lvl, HazardKind.HeadLevel, true, freeHead)
                }
                if (along?.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
            } else if (along != null) {
                if (along.freeHighM < cfg.safeDistanceM && along.freeHighM < along.freeLowM - 0.4) return DepthHazard(ObstacleLevel.Hazard, HazardKind.HeadLevel, true)
                if (along.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
            }
            // Objects on the ground: in a body-wide corridor that bends with the path
            // Round 8: below FOOT_LEVEL_M only the feet's path counts (arms swing at waist height, not at the ankles), so
            // grass or a kerb of weeds 15 cm high beside the path is no obstacle; anything low IN the feet's path still is.
            val lowHalf = if (footCorridor) FOOT_HALF_WIDTH_M else cfg.bodyWidthM / 2
            val alongFree = alongPath(pts, path, cfg.bodyWidthM / 2, cfg, null, a.minPixels, lowHalfWidthM = lowHalf)
            // Within stopping distance the straight corridor ahead of the runner counts too (same rule as detections)
            val straightFree = alongPath(pts, STRAIGHT, cfg.bodyWidthM / 2, cfg, null, a.minPixels, lowHalfWidthM = lowHalf)
            val free = if (straightFree <= cfg.stopDistanceM) minOf(alongFree, straightFree) else alongFree
            return DepthHazard(level(free, cfg), HazardKind.Object, true, free)
        }
        val along = headingDeg?.let { h -> a.headings.minByOrNull { abs(it.headingDeg - h) } }
        if (along != null) {
            val headLevel = along.freeHighM < cfg.safeDistanceM && along.freeHighM < along.freeLowM - 0.4
            if (headLevel) return DepthHazard(ObstacleLevel.Hazard, HazardKind.HeadLevel, true)
            if (along.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
            return DepthHazard(level(along.freeM, cfg), HazardKind.Object, true, along.freeM)
        }
        if (a.headLevelM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.HeadLevel, true)
        if (a.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
        val clear = a.clearDistanceM ?: return DepthHazard(ObstacleLevel.None, HazardKind.Object, true)
        return DepthHazard(level(clear, cfg), HazardKind.Object, true, clear)
    }

    private const val BEARING_AT_M = 3.0

    /** Round 8: head-level obstacles judged along the followed path, head-and-shoulders wide, up to [headClearanceM],
     *  with the same distances as ground objects (warning within 4 m, stop within 2.5 m)
     *  (false = the 0.14.0 rule: straight 10-degree corridors, 1.2 m wide, every point 1.1-2.1 m high, stop within 4 m). */
    @Volatile var headCorridor = true
    /** Round 8: points lower than [FOOT_LEVEL_M] count only within [FOOT_HALF_WIDTH_M] of the path (the feet), higher ones
     *  within the body-and-arms width (false = the 0.14.0 rule: the full 1.2 m width at every height). Owner's garden
     *  video: weeds 13-20 cm high at 0.5-0.6 m beside the path gave "obstacle" warnings for 2 s. */
    @Volatile var footCorridor = true
    const val FOOT_LEVEL_M = 0.3
    const val FOOT_HALF_WIDTH_M = 0.35
    /** Half width of what the head and shoulders sweep (0.25 m) plus a 0.15 m margin. */
    const val HEAD_HALF_WIDTH_M = 0.4
    /** Top of the runner's head plus margin: a chest camera at ~1.3 m means a runner ~1.8 m tall; 1.95 m clears it.
     *  Set from the runner's own height setting when the app has one (stature + 0.15 m). */
    @Volatile var headClearanceM = 1.95
    private val STRAIGHT = run.basir.engine.PathCurve(doubleArrayOf(0.0, 0.0, 0.0), 0.0, 6.0)

    /**
     * Free distance along a curved path: the nearest forward distance at which at least [minPixels] raised points lie
     * within [halfWidthM] of the path's centre line (high = head-level points only, false = below head level, null = all).
     * Beyond the path's measured far end the corridor continues straight along its last direction.
     */
    fun alongPath(pts: FloatArray, path: run.basir.engine.PathCurve, halfWidthM: Double, cfg: FloorPlanner.Config, high: Boolean?, minPixels: Int,
                  maxHeightM: Double = Double.MAX_VALUE, lowHalfWidthM: Double = halfWidthM): Double {
        val far = maxOf(path.farM, path.nearM + 0.5)
        val slope = (path.lateralAt(far) - path.lateralAt(far - 0.25)) / 0.25
        val hits = ArrayList<Double>()
        var k = 0
        while (k + 2 < pts.size) {
            val f = pts[k].toDouble(); val l = pts[k + 1].toDouble(); val h = pts[k + 2].toDouble()
            k += 3
            if (f <= 0.0 || f > cfg.lookaheadM) continue
            if (high != null && (h >= cfg.headLevelHeightM) != high) continue
            if (h > maxHeightM) continue
            val centre = if (f <= far) path.lateralAt(f) else path.lateralAt(far) + slope * (f - far)
            if (abs(l - centre) <= (if (h < FOOT_LEVEL_M) lowHalfWidthM else halfWidthM)) hits += f
        }
        if (hits.size < minPixels) return cfg.lookaheadM
        hits.sort()
        return minOf(hits[minPixels - 1], cfg.lookaheadM)
    }

    private fun level(clear: Double, cfg: FloorPlanner.Config) = when {
        clear <= cfg.stopDistanceM -> ObstacleLevel.Hazard
        clear < cfg.safeDistanceM -> ObstacleLevel.Possible
        else -> ObstacleLevel.None
    }
}

/**
 * Confirms depth hazards over consecutive results, as the walking tool does: a stop-level
 * hazard counts at once, a lower one only when two results in a row agree.
 */
class DepthConfirm {
    private var previous = ObstacleLevel.None

    fun update(h: DepthHazard): DepthHazard {
        val confirmed = when {
            h.level == ObstacleLevel.Hazard -> h
            h.level == ObstacleLevel.Possible && previous != ObstacleLevel.None -> h
            else -> h.copy(level = ObstacleLevel.None)
        }
        previous = h.level
        return confirmed
    }

    fun reset() {
        previous = ObstacleLevel.None
    }
}
