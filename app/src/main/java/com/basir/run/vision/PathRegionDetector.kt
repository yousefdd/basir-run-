package com.basir.run.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Path detector for walkways without painted lines (parks, paved paths).
 *
 * The lower frame is sampled on a coarse grid. The surface just ahead of the runner's feet is
 * taken as the reference (the user stands on the path during calibration), and the region of
 * cells that look like it, in brightness and above all in colour, is grown from there. Each row
 * of that region gives a left and a right boundary; quadratics through them give the edges,
 * the centre line and the bend.
 *
 * It reports Unclear instead of inventing edges when:
 * - the region reaches both sides of the frame on most rows (the path blends into its surroundings),
 * - the colour difference across the edges is weak,
 * - too few rows have both edges, or the edges do not fit a smooth curve.
 *
 * Brightness is weighted lower than colour so tree shadows break the region less, but strong
 * shadows and wet patches can still split it. This is a heuristic, not a trained model.
 */
class PathRegionDetector {

    @Volatile var calibration = Calibration()

    private val ly = FloatArray(COLS * ROWS)
    private val lu = FloatArray(COLS * ROWS)
    private val lv = FloatArray(COLS * ROWS)
    private val inside = BooleanArray(COLS * ROWS)
    private val queue = IntArray(COLS * ROWS)

    fun detect(frame: CameraFrame): PathEstimate = detectRaw(frame).let { it.copy(evidence = lastEvidence) }

    private var lastEvidence: List<RowEvidence> = emptyList()

    private fun detectRaw(frame: CameraFrame): PathEstimate {
        lastEvidence = emptyList()
        val w = frame.uprightWidth
        val h = frame.uprightHeight
        val aspect = w.toFloat() / h
        if (!frame.hasColor) return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)
        sample(frame, w, h)

        // Reference surface: the bottom rows around where the runner stands.
        val seedCol = (calibration.runnerX * COLS).toInt().coerceIn(SEED_HALF, COLS - 1 - SEED_HALF)
        var my = 0f; var mu = 0f; var mv = 0f; var n = 0
        for (r in ROWS - SEED_ROWS until ROWS) for (c in seedCol - SEED_HALF..seedCol + SEED_HALF) {
            val i = r * COLS + c
            my += ly[i]; mu += lu[i]; mv += lv[i]; n++
        }
        my /= n; mu /= n; mv /= n
        var spread = 0f
        for (r in ROWS - SEED_ROWS until ROWS) for (c in seedCol - SEED_HALF..seedCol + SEED_HALF) {
            spread = max(spread, distance(r * COLS + c, my, mu, mv))
        }
        val limit = max(MIN_LIMIT, spread * SPREAD_FACTOR)

        // Grow the region of matching cells, 4-connected, from the reference cells.
        inside.fill(false)
        var head = 0
        var tail = 0
        for (r in ROWS - SEED_ROWS until ROWS) for (c in seedCol - SEED_HALF..seedCol + SEED_HALF) {
            val i = r * COLS + c
            inside[i] = true
            queue[tail++] = i
        }
        var regionCells = tail
        while (head < tail) {
            val i = queue[head++]
            val r = i / COLS
            val c = i % COLS
            for (k in 0 until 4) {
                val nr = r + DR[k]
                val nc = c + DC[k]
                if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue
                val j = nr * COLS + nc
                if (inside[j] || distance(j, my, mu, mv) > limit) continue
                inside[j] = true
                queue[tail++] = j
                regionCells++
            }
        }
        if (regionCells > COLS * ROWS * MAX_REGION_FRACTION) return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)

        // Walk up from the feet, following the run of region cells that contains the path centre.
        val leftPts = ArrayList<Pt>()
        val rightPts = ArrayList<Pt>()
        val evidence = ArrayList<RowEvidence>(ROWS)
        var bothRows = 0
        var touchingRows = 0
        var contrastSum = 0f
        var contrastCount = 0
        var centre = seedCol
        for (r in ROWS - 1 downTo 0) {
            if (!inside[r * COLS + centre]) {
                val near = (1..3).flatMap { listOf(centre - it, centre + it) }
                    .firstOrNull { it in 0 until COLS && inside[r * COLS + it] } ?: break
                centre = near
            }
            var lc = centre
            while (lc > 0 && inside[r * COLS + lc - 1]) lc--
            var rc = centre
            while (rc < COLS - 1 && inside[r * COLS + rc + 1]) rc++
            val y = gridY(r)
            val leftVisible = lc > 0
            val rightVisible = rc < COLS - 1
            if (!leftVisible && !rightVisible) touchingRows++
            if (leftVisible) {
                leftPts.add(Pt(lc.toFloat() / COLS, y))
                contrastSum += distance(r * COLS + lc - 1, ly[r * COLS + lc], lu[r * COLS + lc], lv[r * COLS + lc]); contrastCount++
            }
            if (rightVisible) {
                rightPts.add(Pt((rc + 1).toFloat() / COLS, y))
                contrastSum += distance(r * COLS + rc + 1, ly[r * COLS + rc], lu[r * COLS + rc], lv[r * COLS + rc]); contrastCount++
            }
            if (leftVisible && rightVisible) bothRows++
            evidence.add(RowEvidence(y, if (leftVisible) lc.toFloat() / COLS else null, if (rightVisible) (rc + 1).toFloat() / COLS else null))
            centre = (lc + rc) / 2
        }

        lastEvidence = evidence
        if (touchingRows > ROWS * MAX_TOUCHING_FRACTION) return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)
        val contrast = if (contrastCount == 0) 0f else contrastSum / contrastCount
        if (contrast < limit * MIN_EDGE_CONTRAST) return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)

        val leftFit = CurveFit.fit(leftPts, minPoints = MIN_POINTS, outlierFloor = OUTLIER_FLOOR)
        val rightFit = CurveFit.fit(rightPts, minPoints = MIN_POINTS, outlierFloor = OUTLIER_FLOOR)
        val contrastScore = min(1f, contrast / (limit * 3f))

        if (leftFit != null && rightFit != null && bothRows >= MIN_POINTS) {
            val width = rightFit.curve.x(1f) - leftFit.curve.x(1f)
            if (width < MIN_PATH_WIDTH) return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)
            val measured = calibration.halfWidth
            if (measured != null && abs(width / 2 - measured) / measured > MAX_WIDTH_CHANGE) {
                return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)
            }
            val coverage = bothRows / ROWS.toFloat()
            val smoothness = 1f - min(1f, max(leftFit.rms, rightFit.rms) / RMS_SCALE)
            val confidence = coverage * 0.4f + contrastScore * 0.3f + smoothness * 0.3f
            return PathEstimate.fromEdges(leftFit.curve, rightFit.curve, calibration, PathSource.SurfaceRegion, confidence, ROI_TOP, aspect)
        }
        val single = leftFit?.let { it to Side.Left } ?: rightFit?.let { it to Side.Right }
            ?: return PathEstimate.unclear(PathSource.SurfaceRegion, aspect)
        val (fit, side) = single
        val confidence = fit.used / ROWS.toFloat() * 0.5f + contrastScore * 0.25f
        return PathEstimate.fromSingleEdge(fit.curve, side, calibration, PathSource.SurfaceRegion, confidence, ROI_TOP, aspect)
    }

    private fun sample(frame: CameraFrame, w: Int, h: Int) {
        for (r in 0 until ROWS) {
            val py = (gridY(r) * h).toInt().coerceIn(1, h - 2)
            for (c in 0 until COLS) {
                val px = (((c + 0.5f) / COLS) * w).toInt().coerceIn(1, w - 2)
                var sy = 0; var su = 0; var sv = 0
                // Average a small cross to smooth texture such as gravel and grass blades.
                for ((dx, dy) in CROSS) {
                    sy += frame.luma(px + dx, py + dy)
                    su += frame.chromaU(px + dx, py + dy)
                    sv += frame.chromaV(px + dx, py + dy)
                }
                val i = r * COLS + c
                ly[i] = sy / 5f
                lu[i] = su / 5f
                lv[i] = sv / 5f
            }
        }
    }

    private fun gridY(r: Int) = ROI_TOP + (1f - ROI_TOP) * (r + 0.5f) / ROWS

    private fun distance(i: Int, y: Float, u: Float, v: Float): Float {
        val dy = (ly[i] - y) * LUMA_WEIGHT
        val du = lu[i] - u
        val dv = lv[i] - v
        return sqrt(dy * dy + du * du + dv * dv)
    }

    companion object {
        const val ROI_TOP = 0.45f
        private const val COLS = 48
        private const val ROWS = 24
        private const val SEED_ROWS = 3
        private const val SEED_HALF = 2
        private const val LUMA_WEIGHT = 0.2f
        private const val MIN_LIMIT = 12f
        private const val SPREAD_FACTOR = 1.6f
        private const val MAX_REGION_FRACTION = 0.85f
        private const val MAX_TOUCHING_FRACTION = 0.5f
        private const val MIN_EDGE_CONTRAST = 1.2f
        private const val MIN_POINTS = 8
        private const val OUTLIER_FLOOR = 0.04f
        private const val MIN_PATH_WIDTH = 0.12f
        private const val MAX_WIDTH_CHANGE = 0.4f
        private const val RMS_SCALE = 0.06f
        private val DR = intArrayOf(-1, 1, 0, 0)
        private val DC = intArrayOf(0, 0, -1, 1)
        private val CROSS = listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1)
    }
}
