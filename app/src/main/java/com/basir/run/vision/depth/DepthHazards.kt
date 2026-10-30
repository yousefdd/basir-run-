package com.basir.run.vision.depth

import com.basir.run.vision.HazardKind
import com.basir.run.vision.ObstacleLevel

data class DepthHazard(val level: ObstacleLevel, val kind: HazardKind, val floorVisible: Boolean)

/**
 * Turns the floor planner's answer into what the runner needs. Thresholds are wider than the
 * walking tool's (2.0 m safe, 1.2 m stop) because a runner covers about 3 m every second; they
 * are starting values for field tests, not validated distances. Distances are never spoken.
 * The planner's suggested turn is not used: no way around an obstacle is offered.
 */
object DepthHazards {
    val RUNNING = FloorPlanner.Config(safeDistanceM = 4.0, stopDistanceM = 2.5, lookaheadM = 6.0)

    fun assess(a: DepthAnalysis?, cfg: FloorPlanner.Config = RUNNING): DepthHazard {
        if (a == null || !a.floorValid) return DepthHazard(ObstacleLevel.None, HazardKind.Object, floorVisible = false)
        if (a.headLevelM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.HeadLevel, true)
        if (a.dropM != null) return DepthHazard(ObstacleLevel.Hazard, HazardKind.Drop, true)
        val clear = a.clearDistanceM ?: return DepthHazard(ObstacleLevel.None, HazardKind.Object, true)
        val level = when {
            clear <= cfg.stopDistanceM -> ObstacleLevel.Hazard
            clear < cfg.safeDistanceM -> ObstacleLevel.Possible
            else -> ObstacleLevel.None
        }
        return DepthHazard(level, HazardKind.Object, true)
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
