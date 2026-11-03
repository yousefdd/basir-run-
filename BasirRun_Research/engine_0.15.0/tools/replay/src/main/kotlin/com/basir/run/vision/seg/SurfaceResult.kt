package com.basir.run.vision.seg

import run.basir.engine.ProbabilityMask

// Verbatim copy of SurfaceResult from app_vision/seg/SurfaceChannel.kt (that file needs Android).
/** What the surface models made of one frame, at the line engine's resolution (upright frame). */
class SurfaceResult(
    val frameTimeMs: Long,
    /** Walkable ground, 1 per walkable cell; null when the model is missing or failed. */
    val walkable: ProbabilityMask?,
    /** Guide-line probability; null when the model is missing or failed. */
    val line: ProbabilityMask?,
    val walkableMs: Long,
    val lineMs: Long,
    /** Walkable-model classes (ADE20K ids) on its own grid, whole frame; null without the model. */
    val labels: IntArray? = null,
    val labelW: Int = 0,
    val labelH: Int = 0,
    /** The walkable decision on that grid (0/1), before resampling to [walkable]'s size. */
    val walkableGrid: FloatArray? = null,
) {
    /** Share of walkable cells in the corridor just ahead (bottom 45 %, central 40 % of the frame). */
    fun walkableAheadShare(): Float {
        val m = walkable ?: return 0f
        var n = 0
        var w = 0
        for (y in (m.height * 0.55).toInt() until m.height) for (x in (m.width * 0.3).toInt() until (m.width * 0.7).toInt()) {
            n++
            if (m[x, y] >= 0.5f) w++
        }
        return if (n == 0) 0f else w.toFloat() / n
    }
}
