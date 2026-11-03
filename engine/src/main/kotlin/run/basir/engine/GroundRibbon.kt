package run.basir.engine

import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/** One cross-section of the drawn path: where its centre and its two edges fall in the image. */
data class RibbonSection(
    val forwardM: Double,
    val centre: GroundPoint,
    val left: GroundPoint,
    val right: GroundPoint,
    /** Image pixels (u, v) of the centre and edges, at the camera's resolution. */
    val centrePx: Pair<Double, Double>,
    val leftPx: Pair<Double, Double>,
    val rightPx: Pair<Double, Double>,
)

/**
 * The virtual path as a strip lying ON THE GROUND, ready to draw over the camera picture.
 *
 * The followed path is a curve on the ground (metres ahead, metres right). It is drawn by sampling
 * that curve, offsetting each sample by [halfWidthM] to either side perpendicular to the path's own
 * direction, and projecting the three ground points into the image with the frame's camera model
 * (height, tilt and roll). The strip therefore narrows with distance as the real ground does, bends
 * with the path, and tilts with the phone — unlike a line drawn in image space.
 *
 * It covers only what was measured: from where the ground first comes into view (or the curve's
 * [PathCurve.nearM], if further) to [PathCurve.farM]; nothing is extrapolated, and no strip is
 * returned for a path that has no visible measured part.
 */
class GroundRibbon(val sections: List<RibbonSection>) {

    /** Closed outline in image pixels: the left edge from near to far, then the right edge back. */
    fun outline(): List<Pair<Double, Double>> = sections.map { it.leftPx } + sections.reversed().map { it.rightPx }

    val nearM: Double get() = sections.first().forwardM
    val farM: Double get() = sections.last().forwardM

    companion object {
        /** Width of the drawn strip: about a runner's shoulder width. */
        const val DEFAULT_HALF_WIDTH_M = 0.3

        fun build(curve: PathCurve, camera: CameraModel, halfWidthM: Double = DEFAULT_HALF_WIDTH_M, stepM: Double = 0.2): GroundRibbon? {
            val visibleFrom = firstVisibleForwardM(camera) ?: return null
            val from = max(curve.nearM, visibleFrom)
            val to = curve.farM
            if (to - from < stepM) return null
            val out = ArrayList<RibbonSection>()
            var f = from
            while (f <= to + 1e-9) {
                val lat = curve.lateralAt(f)
                val s = tan(Math.toRadians(curve.headingDegAt(f))) // d(lateral)/d(forward)
                val len = sqrt(1 + s * s)
                // Unit normal to the left of the direction of travel (lateral is positive to the right)
                val nf = s / len
                val nl = -1.0 / len
                val c = GroundPoint(f, lat)
                val l = GroundPoint(f + halfWidthM * nf, lat + halfWidthM * nl)
                val r = GroundPoint(f - halfWidthM * nf, lat - halfWidthM * nl)
                val cp = camera.groundToImage(c)
                val lp = camera.groundToImage(l)
                val rp = camera.groundToImage(r)
                if (cp != null && lp != null && rp != null) out += RibbonSection(f, c, l, r, cp, lp, rp)
                f += stepM
            }
            return if (out.size >= 2) GroundRibbon(out) else null
        }

        /** Metres ahead where the ground first shows at the bottom of the picture (straight ahead), or null if no ground is seen. */
        fun firstVisibleForwardM(camera: CameraModel): Double? =
            camera.copy(rollDeg = 0.0).imageToGround((camera.width - 1) / 2.0, camera.height - 1.0)?.forward
    }
}
