package com.basir.run.vision

import com.basir.run.vision.depth.DepthAnalysis
import com.basir.run.vision.seg.SurfaceResult
import run.basir.engine.CameraModel
import run.basir.engine.DetectionFilter
import run.basir.engine.FlatSurfaceCheck
import run.basir.engine.GroundCoverResolver
import run.basir.engine.ProbabilityMask

/**
 * Research round 6 (owner: "leaves must not be treated as danger"): uses the depth floor planner's
 * evidence of height above the floor to stop flat things on the ground from becoming obstacles.
 * Three places turned fallen leaves into hazards, each found on real clips:
 *  1. the object detector names leaves on the ground "bird" (or another small item) -> [filterDetections];
 *  2. the surface-change detector flags leaves as a colour change in the corridor -> [filterSurfaceFindings];
 *  3. the walkable-ground model names a leaf patch plant/tree/flower, so the way seemed to end -> [refineWalkable].
 * Each rule only removes something when depth shows it flat; without depth (no floor found, model missing,
 * result too old, beyond the depth range) everything is kept as before. Pure Kotlin: GuidancePipeline and the
 * research replay call the same code.
 */
object GroundEvidence {

    data class Config(
        val flatDetections: Boolean = true,
        val flatSurfaceFindings: Boolean = true,
        val groundCover: Boolean = true,
    )

    @Volatile var config = Config()

    /** Small-item detections lying flat on the ground (within the depth range) are dropped. */
    fun filterDetections(detections: List<Detection>, analysis: DepthAnalysis?, camera: CameraModel): List<Detection> {
        if (!config.flatDetections || analysis == null || !analysis.floorValid) return detections
        val mask = analysis.obstacleMask ?: return detections
        return detections.filterNot { d ->
            val foot = camera.imageToGround((d.left + d.right) / 2.0 * camera.width, d.bottom.toDouble() * camera.height)
            val inRange = foot != null && foot.forward <= DEPTH_RANGE_M
            val share = if (inRange) FlatSurfaceCheck.raisedShare(mask, analysis.width, analysis.height,
                d.left.toDouble(), d.top.toDouble(), d.right.toDouble(), d.bottom.toDouble()) else null
            DetectionFilter.flatGroundPattern(d.label, share)
        }
    }

    /** Surface-change findings that depth shows to be flat (and not a hole) are dropped. */
    fun filterSurfaceFindings(findings: List<ObstacleFinding>, analysis: DepthAnalysis?): List<ObstacleFinding> {
        if (!config.flatSurfaceFindings || analysis == null || !analysis.floorValid) return findings
        return findings.filter { f ->
            FlatSurfaceCheck.keep(analysis.obstacleMask, analysis.dropMask, analysis.width, analysis.height,
                f.detection.left.toDouble(), f.detection.top.toDouble(), f.detection.right.toDouble(), f.detection.bottom.toDouble())
        }
    }

    /**
     * Walkable ground with flat ground cover added (GroundCoverResolver), resampled to the surface result's
     * mask size; returns [surface] unchanged when there are no labels, no depth floor, or nothing was added.
     */
    fun refineWalkable(surface: SurfaceResult, analysis: DepthAnalysis?, camera: CameraModel, resolver: GroundCoverResolver): SurfaceResult {
        val labels = surface.labels ?: return surface
        val grid = surface.walkableGrid ?: return surface
        val walk = surface.walkable ?: return surface
        if (!config.groundCover || analysis == null || !analysis.floorValid) return surface
        val res = resolver.resolve(labels, surface.labelW, surface.labelH, grid, analysis.obstacleMask, analysis.width, analysis.height, camera)
        if (res.accepted == 0) return surface
        val out = ProbabilityMask(walk.width, walk.height)
        for (y in 0 until walk.height) {
            val sy = ((y + 0.5) * surface.labelH / walk.height).toInt().coerceIn(0, surface.labelH - 1)
            for (x in 0 until walk.width) {
                val sx = ((x + 0.5) * surface.labelW / walk.width).toInt().coerceIn(0, surface.labelW - 1)
                out[x, y] = res.walkable[sy * surface.labelW + sx]
            }
        }
        return SurfaceResult(surface.frameTimeMs, out, surface.line, surface.walkableMs, surface.lineMs,
            surface.labels, surface.labelW, surface.labelH, res.walkable)
    }

    /** FloorPlanner marks raised points up to DepthHazards.RUNNING.lookaheadM; farther boxes cannot be checked. */
    const val DEPTH_RANGE_M = 5.5
}
