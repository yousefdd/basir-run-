package com.basir.run.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Painted line detector for athletics tracks and marked indoor lanes.
 *
 * Each sampled row of the lower frame is thresholded against its own brightness, so shade and
 * sun patches shift the threshold with them. Bright runs that are thin enough to be paint become
 * candidate points; wide bright patches (sunlight between tree shadows, reflections) are dropped
 * by the width limit. The edge on each side is the candidate that continues last frame's edge,
 * or, with no history, the nearest one to the runner. A quadratic through each edge tells
 * straight lanes from bends.
 *
 * Safety rules built in:
 * - one edge only gives EdgeOnly; the centre is never inferred from a single line.
 * - several lines close together on most rows gives Unclear (ambiguous).
 * - after calibration, a lane width far from the measured one gives Unclear.
 */
class LaneDetector {

    @Volatile var calibration = Calibration()

    private val histogram = IntArray(256)
    private var row = IntArray(0)
    private var lastLeft: Curve? = null
    private var lastRight: Curve? = null

    fun detect(frame: CameraFrame): PathEstimate {
        val w = frame.uprightWidth
        val h = frame.uprightHeight
        val aspect = w.toFloat() / h
        val step = max(1, w / SAMPLES_PER_ROW)
        val cols = w / step
        if (row.size < cols) row = IntArray(cols)
        val top = (h * ROI_TOP).toInt()
        val runner = calibration.runnerX

        val leftPts = ArrayList<Pt>()
        val rightPts = ArrayList<Pt>()
        var ambiguousRows = 0
        var rowsWithPaint = 0

        for (r in 0 until ROWS) {
            val v = top + (h - 1 - top) * r / (ROWS - 1)
            val y = v.toFloat() / h
            for (i in 0 until cols) row[i] = frame.luma(i * step, v)
            val centers = paintCenters(cols, step, w, y)
            if (centers.isEmpty()) continue
            rowsWithPaint++

            val left = pick(centers, lastLeft?.x(y)) { it < runner }
            val right = pick(centers, lastRight?.x(y)) { it >= runner }
            if (left != null && right != null && right - left < MIN_LANE_WIDTH) continue
            left?.let { leftPts.add(Pt(it, y)) }
            right?.let { rightPts.add(Pt(it, y)) }
            val crowded = listOfNotNull(left, right).any { chosen ->
                centers.any { it != chosen && abs(it - chosen) < CROWDED_GAP }
            }
            if (crowded) ambiguousRows++
        }

        val leftFit = CurveFit.fit(leftPts)
        val rightFit = CurveFit.fit(rightPts)
        lastLeft = leftFit?.curve
        lastRight = rightFit?.curve

        if (rowsWithPaint > 0 && ambiguousRows.toFloat() / rowsWithPaint > MAX_AMBIGUOUS_FRACTION) {
            return PathEstimate.unclear(PathSource.PaintedLines, aspect, ambiguous = true)
        }

        if (leftFit != null && rightFit != null) {
            val width = rightFit.curve.x(1f) - leftFit.curve.x(1f)
            if (width < MIN_LANE_WIDTH) return PathEstimate.unclear(PathSource.PaintedLines, aspect)
            val measured = calibration.halfWidth
            if (measured != null && abs(width / 2 - measured) / measured > MAX_WIDTH_CHANGE) {
                return PathEstimate.unclear(PathSource.PaintedLines, aspect)
            }
            val coverage = (leftFit.used + rightFit.used) / (2f * ROWS)
            val fitQuality = 1f - min(1f, max(leftFit.rms, rightFit.rms) / RMS_SCALE)
            val confidence = coverage * fitQuality * (1f - ambiguousRows.toFloat() / ROWS)
            return PathEstimate.fromEdges(leftFit.curve, rightFit.curve, calibration, PathSource.PaintedLines, confidence, ROI_TOP, aspect)
        }

        val single = leftFit?.let { it to Side.Left } ?: rightFit?.let { it to Side.Right }
            ?: return PathEstimate.unclear(PathSource.PaintedLines, aspect)
        val (fit, side) = single
        val confidence = fit.used / ROWS.toFloat() * (1f - min(1f, fit.rms / RMS_SCALE))
        return PathEstimate.fromSingleEdge(fit.curve, side, calibration, PathSource.PaintedLines, confidence, ROI_TOP, aspect)
    }

    fun reset() {
        lastLeft = null
        lastRight = null
    }

    private inline fun pick(centers: List<Float>, predicted: Float?, onSide: (Float) -> Boolean): Float? {
        if (predicted != null) {
            val best = centers.minByOrNull { abs(it - predicted) }
            if (best != null && abs(best - predicted) < TRACK_GATE) return best
        }
        val candidates = centers.filter(onSide)
        val runner = calibration.runnerX
        return candidates.minByOrNull { abs(it - runner) }
    }

    // Centres (frame fractions) of bright runs in this row that are thin enough to be paint.
    private fun paintCenters(cols: Int, step: Int, width: Int, y: Float): List<Float> {
        histogram.fill(0)
        var brightest = 0
        for (i in 0 until cols) {
            histogram[row[i]]++
            if (row[i] > brightest) brightest = row[i]
        }
        val median = median(cols)
        if (brightest - median < MIN_CONTRAST) return emptyList()
        val threshold = median + (brightest - median) * THRESHOLD_FRACTION
        val maxRun = ((MAX_PAINT_FAR + (MAX_PAINT_NEAR - MAX_PAINT_FAR) * y) * cols).toInt().coerceAtLeast(1)

        val centers = ArrayList<Float>()
        var start = -1
        for (i in 0..cols) {
            val bright = i < cols && row[i] >= threshold
            if (bright && start < 0) {
                start = i
            } else if (!bright && start >= 0) {
                if (i - start <= maxRun) centers.add(((start + i - 1) / 2f * step + step / 2f) / width)
                start = -1
            }
        }
        return centers
    }

    private fun median(count: Int): Int {
        val half = count / 2
        var seen = 0
        for (value in 0..255) {
            seen += histogram[value]
            if (seen > half) return value
        }
        return 255
    }

    companion object {
        const val ROI_TOP = 0.45f
        private const val ROWS = 28
        private const val SAMPLES_PER_ROW = 160
        private const val MIN_CONTRAST = 35
        private const val THRESHOLD_FRACTION = 0.5f
        private const val MAX_PAINT_FAR = 0.05f
        private const val MAX_PAINT_NEAR = 0.10f
        private const val MIN_LANE_WIDTH = 0.12f
        private const val TRACK_GATE = 0.08f
        private const val CROWDED_GAP = 0.05f
        private const val MAX_AMBIGUOUS_FRACTION = 0.4f
        private const val MAX_WIDTH_CHANGE = 0.35f
        private const val RMS_SCALE = 0.04f
    }
}
