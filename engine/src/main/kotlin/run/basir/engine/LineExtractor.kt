package run.basir.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** One horizontal slice of a candidate line: where it crosses a band of image rows. */
data class LineSegment(
    val u: Double,          // center column (probability weighted)
    val v: Double,          // center row of the band
    val widthPx: Double,
    val confidence: Double, // mean probability over the segment
    val ground: GroundPoint,
    val widthM: Double,
)

/** A chain of segments that form one continuous line on the ground. */
data class LineCandidate(
    val segments: List<LineSegment>,
    val curve: PathCurve,
    val coverageM: Double,   // forward extent on the ground
    val continuity: Double,  // fraction of bands in its span that contain the line
    val confidence: Double,
    val score: Double,
) {
    /** Lateral offset of the line at the runner's feet (extrapolated to [nearM] ahead). */
    fun lateralAtRunner(nearM: Double = 0.5) = curve.lateralAt(nearM)
}

data class LineExtractorConfig(
    val threshold: Float = 0.5f,
    val bands: Int = 48,
    val minLineWidthM: Double = 0.02,  // thin tape / painted lane line
    val maxLineWidthM: Double = 0.45,  // anything wider is a surface, not a line
    val maxRangeM: Double = 15.0,
    val gateM: Double = 0.30,          // lateral association gate (grows with the gap)
    val maxGapM: Double = 1.5,         // a line may disappear this long (occlusion, worn paint)
    val minCoverageM: Double = 1.5,
    val minSegments: Int = 4,
    val maxNearHeadingDeg: Double = 60.0, // lines crossing the path sideways are not guides
)

/**
 * Turns a line-probability mask (from any segmentation model or [ContrastLineDetector]) into
 * candidate lines on the ground. Unlike averaging all line pixels per row, every crossing is
 * kept separate and chained bottom-up by continuity in ground coordinates, then scored on
 * length, continuity, smoothness, width and orientation — never on brightness alone.
 */
class LineExtractor(private val config: LineExtractorConfig = LineExtractorConfig()) {

    fun extract(mask: ProbabilityMask, camera: CameraModel): List<LineCandidate> {
        val cam = camera.resized(mask.width, mask.height)
        val bands = segmentBands(mask, cam)
        val chains = chain(bands)
        return chains.mapNotNull { score(it, bands.size) }.sortedByDescending { it.score }
    }

    /** Line crossings per band of rows, from the bottom of the image up to the horizon. */
    internal fun segmentBands(mask: ProbabilityMask, cam: CameraModel): List<List<LineSegment>> {
        val top = max(0, (cam.horizonRow + 2).toInt())
        val bandH = max(2, (mask.height - top) / config.bands)
        val out = ArrayList<List<LineSegment>>()
        var y1 = mask.height
        while (y1 - bandH >= top) {
            val y0 = y1 - bandH
            val vMid = (y0 + y1 - 1) / 2.0
            val mpp = cam.lateralMetersPerPixel(vMid)
            val segs = ArrayList<LineSegment>()
            if (mpp != null) {
                val profile = FloatArray(mask.width)
                for (y in y0 until y1) for (x in 0 until mask.width) profile[x] += mask[x, y]
                for (x in 0 until mask.width) profile[x] /= bandH
                var x = 0
                while (x < mask.width) {
                    if (profile[x] < config.threshold) { x++; continue }
                    var end = x
                    var gap = 0
                    var k = x
                    while (k < mask.width && gap <= 2) {
                        if (profile[k] >= config.threshold) { end = k; gap = 0 } else gap++
                        k++
                    }
                    var mass = 0.0
                    var mu = 0.0
                    for (i in x..end) { mass += profile[i]; mu += i * profile[i].toDouble() }
                    val widthPx = (end - x + 1).toDouble()
                    val u = mu / mass + 0.5
                    val g = cam.imageToGround(u, vMid + 0.5)
                    val widthM = widthPx * mpp
                    if (g != null && g.forward <= config.maxRangeM &&
                        widthM in config.minLineWidthM..config.maxLineWidthM
                    ) {
                        segs += LineSegment(u, vMid, widthPx, mass / widthPx, g, widthM)
                    }
                    x = end + 1
                }
            }
            out += segs
            y1 = y0
        }
        return out
    }

    private class Chain(val first: Int) {
        val segs = ArrayList<Pair<Int, LineSegment>>() // band index, segment
        fun predictLateral(f: Double): Double {
            val n = segs.size
            if (n == 1) return segs[0].second.ground.lateral
            val a = segs[n - 2].second.ground
            val b = segs[n - 1].second.ground
            val slope = (b.lateral - a.lateral) / max(b.forward - a.forward, 1e-3)
            return b.lateral + slope.coerceIn(-2.0, 2.0) * (f - b.forward)
        }
        val lastForward get() = segs.last().second.ground.forward
    }

    private fun chain(bands: List<List<LineSegment>>): List<Chain> {
        val chains = ArrayList<Chain>()
        bands.forEachIndexed { bi, segs ->
            val taken = BooleanArray(segs.size)
            // Extend existing chains greedily, best match first
            val pairs = ArrayList<Triple<Double, Chain, Int>>()
            for (c in chains) {
                val gapM = segs.firstOrNull()?.ground?.forward?.minus(c.lastForward) ?: continue
                if (gapM > config.maxGapM) continue
                segs.forEachIndexed { si, s ->
                    val err = abs(s.ground.lateral - c.predictLateral(s.ground.forward))
                    val gate = config.gateM + 0.15 * max(0.0, s.ground.forward - c.lastForward)
                    if (err <= gate) pairs += Triple(err, c, si)
                }
            }
            val used = HashSet<Chain>()
            for ((_, c, si) in pairs.sortedBy { it.first }) {
                if (c in used || taken[si]) continue
                c.segs += bi to segs[si]
                used += c
                taken[si] = true
            }
            segs.forEachIndexed { si, s -> if (!taken[si]) chains += Chain(bi).also { it.segs += bi to s } }
        }
        return chains
    }

    private fun score(c: Chain, nBands: Int): LineCandidate? {
        if (c.segs.size < config.minSegments) return null
        val pts = c.segs.map { it.second.ground }
        val near = pts.minOf { it.forward }
        val far = pts.maxOf { it.forward }
        val coverage = far - near
        if (coverage < config.minCoverageM) return null
        val bandSpan = c.segs.last().first - c.segs.first().first + 1
        val continuity = c.segs.size.toDouble() / bandSpan
        val conf = c.segs.map { it.second.confidence }.average()
        // Far points are less precise (fewer pixels per meter) — weight them down
        val weights = pts.map { 1.0 / (1.0 + 0.15 * it.forward) }
        val curve = PathCurve.fit(pts, weights) ?: return null
        val nearHeading = abs(curve.headingDegAt(near))
        if (nearHeading > config.maxNearHeadingDeg) return null
        val widths = c.segs.map { it.second.widthM }.sorted()
        val widthSpread = (widths[widths.size * 3 / 4] - widths[widths.size / 4]) / max(widths[widths.size / 2], 1e-3)
        val score = min(coverage, 8.0) *
            continuity *
            conf *
            exp(-curve.rmsM / 0.08) *              // smooth, not a zig-zag of unrelated blobs
            cos(nearHeading.toRadians()) *         // runs along the path, not across it
            exp(-max(0.0, widthSpread - 0.5)) *    // a real line keeps a steady width
            exp(-near / 4.0)                       // starts close to the runner
        return LineCandidate(c.segs.map { it.second }, curve, coverage, continuity, conf, score)
    }
}
