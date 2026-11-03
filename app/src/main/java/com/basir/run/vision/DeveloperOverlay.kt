package com.basir.run.vision

import com.basir.run.vision.line.LineResult
import run.basir.engine.CameraModel
import run.basir.engine.GroundRibbon
import run.basir.engine.PathCurve

/**
 * Developer view (research round 6b): what to draw over the camera picture to see where any deviation of the path
 * comes from. Pure data in the pixels of [LineResult.camera]; the screen scales them by viewWidth / camera.width.
 * Kept out of the runner's interface: only the developer screen draws it.
 *
 * Colours: raw measurement (red) -> tracked path (orange, what obstacles are checked against) -> stable path
 * (blue strip on the ground, what is drawn and steered by). A path carried by the tracker's prediction alone (no
 * measurement in this frame) is drawn dashed yellow, so it is never mistaken for a detection.
 */
data class OverlayPolyline(val points: List<Pair<Double, Double>>, val argb: Long, val widthPx: Float, val dashed: Boolean = false)

data class DeveloperOverlay(
    val polylines: List<OverlayPolyline>,
    val ribbonOutline: List<Pair<Double, Double>>?,
    val ribbonArgb: Long,
    val lines: List<String>,
) {
    companion object {
        const val RAW = 0xFFFF3030L
        const val TRACKED = 0xFFFFA000L
        const val STABLE = 0x661E6EFFL
        const val PREDICTED = 0xFFFFE000L
        const val ROUTE = 0xFF00E5FFL

        fun of(r: LineResult, keyframe: Boolean, stageMs: Map<String, Long> = emptyMap()): DeveloperOverlay {
            val cam = r.camera
            val polys = ArrayList<OverlayPolyline>()
            r.rawPath?.let { polys += OverlayPolyline(project(it, cam), RAW, 2f) }
            r.trackedPath?.let { polys += OverlayPolyline(project(it, cam), if (r.predictedOnly) PREDICTED else TRACKED, 2f, dashed = r.predictedOnly) }
            r.freeRoute?.let { route -> polys += OverlayPolyline(route.waypoints.mapNotNull { cam.groundToImage(it) }, ROUTE, 1f) }
            val ribbon = r.ribbon
            val text = listOf(
                "mode ${r.decision.mode} ${r.decision.stopReason.takeIf { it != run.basir.engine.StopReason.NONE } ?: ""}",
                "track ${r.trackState} conf ${"%.2f".format(r.trackConfidence)}${if (r.predictedOnly) " PREDICTED" else ""}",
                "frame ${if (keyframe) "KEYFRAME (surface models ran)" else "skipped inference (tracked)"}  mask age ${r.surfaceAgeMs} ms",
                "command ${r.command.kind} ${r.command.side ?: ""} ${r.command.level ?: ""}",
                "engine ${r.processingMs} ms" + stageMs.entries.joinToString("") { "  ${it.key} ${it.value} ms" },
            )
            return DeveloperOverlay(polys, ribbon?.outline(), if (r.predictedOnly) PREDICTED and 0x66FFFFFFL else STABLE, text)
        }

        private fun project(c: PathCurve, cam: CameraModel): List<Pair<Double, Double>> {
            val from = maxOf(c.nearM, GroundRibbon.firstVisibleForwardM(cam) ?: c.nearM)
            return c.sample(0.25, from, c.farM).mapNotNull { cam.groundToImage(it) }
        }
    }
}
