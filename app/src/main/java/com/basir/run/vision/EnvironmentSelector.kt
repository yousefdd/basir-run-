package com.basir.run.vision

/**
 * Picks, frame by frame, which detector describes the scene: painted lines (athletics track,
 * marked indoor lanes) or the walkable surface (paths without lines). There is no mode for the
 * user to choose. The current source is kept until the other one is clearly better for several
 * frames in a row, so the guidance does not flip between two estimates of the same path.
 */
class EnvironmentSelector {

    /** The detector currently trusted for this scene, or null before any path was tracked. */
    var active: PathSource? = null
        private set

    private var challengerFrames = 0

    fun reset() {
        active = null
        challengerFrames = 0
    }

    fun choose(lines: PathEstimate, surface: PathEstimate): PathEstimate {
        val both = listOf(lines, surface)
        val current = both.firstOrNull { it.source == active }
        val best = both.filter { it.status == PathStatus.Tracked }.maxByOrNull { it.confidence }

        if (best == null) {
            challengerFrames = 0
            // Nothing tracked. Keep reporting a single visible edge if there is one, so the
            // decision engine can still warn about it.
            return current?.takeIf { it.status == PathStatus.EdgeOnly }
                ?: both.firstOrNull { it.status == PathStatus.EdgeOnly }
                ?: current
                ?: lines
        }
        if (current == null || current.status != PathStatus.Tracked) {
            active = best.source
            challengerFrames = 0
            return best
        }
        if (best.source != current.source && best.confidence > current.confidence + SWITCH_MARGIN) {
            if (++challengerFrames >= SWITCH_FRAMES) {
                active = best.source
                challengerFrames = 0
                return best
            }
        } else {
            challengerFrames = 0
        }
        return current
    }

    companion object {
        const val SWITCH_MARGIN = 0.15f
        const val SWITCH_FRAMES = 5
    }
}
