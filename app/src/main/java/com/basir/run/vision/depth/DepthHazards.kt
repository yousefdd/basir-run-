package com.basir.run.vision.depth

import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel
import kotlin.math.abs

data class DepthHazard(val level: ObstacleLevel, val kind: HazardKind, val floorVisible: Boolean)

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

    /**
     * [headingDeg] is the bearing of the path being followed; free distances are then read in the
     * planner's corridor nearest to it instead of straight ahead, so a wall beside a bending path
     * is not taken for an obstacle on it. Without a path the straight corridor is used.
     */
    fun assess(a: DepthAnalysis?, cfg: FloorPlanner.Config = RUNNING, headingDeg: Double? = null): DepthHazard {
        if (a == null || !a.floorValid) return DepthHazard(ObstacleLevel.None, HazardKind.Object, floorVisible = false)
        val along = headingDeg?.let { h -> a.headings.minByOrNull { abs(it.headingDeg - h) } }
        if (along != null) {
            val headLevel = along.freeHighM < cfg.safeDistanceM && along.freeHighM < along.freeLowM - 0.4
            if (headLevel) return DepthHazard(ObstacleLevel.Hazard, HazardKind.HeadLevel, true)
            if (along.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
            return DepthHazard(level(along.freeM, cfg), HazardKind.Object, true)
        }
        if (a.headLevelM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.HeadLevel, true)
        if (a.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
        val clear = a.clearDistanceM ?: return DepthHazard(ObstacleLevel.None, HazardKind.Object, true)
        return DepthHazard(level(clear, cfg), HazardKind.Object, true)
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
