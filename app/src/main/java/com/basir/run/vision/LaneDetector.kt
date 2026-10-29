package com.basir.run.vision

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sqrt

enum class PathMode { Lane, SingleLine }

enum class PathShape { Straight, CurveLeft, CurveRight }

/**
 * A camera luminance (Y) plane, read as if the phone were upright.
 * rotationDegrees is how far the sensor image must turn clockwise to stand upright.
 */
class LumaFrame(
    private val data: ByteArray,
    private val width: Int,
    private val height: Int,
    private val rowStride: Int,
    private val rotationDegrees: Int,
) {
    val uprightWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val uprightHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    fun luma(u: Int, v: Int): Int {
        val x: Int
        val y: Int
        when (rotationDegrees) {
            90 -> { x = v; y = height - 1 - u }
            180 -> { x = width - 1 - u; y = height - 1 - v }
            270 -> { x = width - 1 - v; y = u }
            else -> { x = u; y = v }
        }
        return data[y * rowStride + x].toInt() and 0xFF
    }
}

/** x = a·y² + b·y + c in frame fractions: x 0 = left edge, y 0 = top, y 1 = nearest the runner. */
data class Curve(val a: Float, val b: Float, val c: Float) {
    fun x(y: Float) = a * y * y + b * y + c
    fun slope(y: Float) = 2 * a * y + b
}

data class LaneResult(
    val found: Boolean,
    /** Where the runner is across the path: 0 = centred, +1 = at the right edge, -1 = at the left edge. */
    val offset: Float = 0f,
    /** Direction of the path just ahead; positive turns to the right. */
    val headingDeg: Float = 0f,
    /** How much the path bends further ahead; positive bends to the right. */
    val curvature: Float = 0f,
    val shape: PathShape = PathShape.Straight,
    val confidence: Float = 0f,
    val lines: List<Curve> = emptyList(),
    val center: Curve? = null,
    val roiTop: Float = LaneDetector.ROI_TOP,
    /** Upright frame width / height, used to place the overlay on the preview. */
    val aspect: Float = 0.75f,
)

/**
 * Finds painted lines on the ground in the lower part of the camera frame: the two edges of a
 * running lane, or a single guide line. Each row is thresholded against its own brightness, the
 * bright runs that are thin enough to be paint become points, and a quadratic is fitted through
 * the points of each line, which tells straight paths from bends.
 */
class LaneDetector(@Volatile var mode: PathMode = PathMode.Lane) {

    private class Pt(val x: Float, val y: Float)

    private val histogram = IntArray(256)
    private var row = IntArray(0)
    private var laneWidth = DEFAULT_LANE_WIDTH
    private var lastLine: Curve? = null

    fun detect(frame: LumaFrame): LaneResult {
        val w = frame.uprightWidth
        val h = frame.uprightHeight
        val aspect = w.toFloat() / h
        val step = max(1, w / SAMPLES_PER_ROW)
        val cols = w / step
        if (row.size < cols) row = IntArray(cols)
        val top = (h * ROI_TOP).toInt()

        val leftPts = ArrayList<Pt>()
        val rightPts = ArrayList<Pt>()
        val linePts = ArrayList<Pt>()
        val predicted = lastLine

        for (r in 0 until ROWS) {
            val v = top + (h - 1 - top) * r / (ROWS - 1)
            val y = v.toFloat() / h
            for (i in 0 until cols) row[i] = frame.luma(i * step, v)
            val centers = paintCenters(cols, step, w, y)
            if (centers.isEmpty()) continue
            when (mode) {
                PathMode.Lane -> {
                    centers.lastOrNull { it < 0.5f }?.let { leftPts.add(Pt(it, y)) }
                    centers.firstOrNull { it >= 0.5f }?.let { rightPts.add(Pt(it, y)) }
                }
                PathMode.SingleLine -> {
                    val target = predicted?.x(y) ?: 0.5f
                    val best = centers.minByOrNull { abs(it - target) } ?: continue
                    if (predicted == null || abs(best - target) < LINE_GATE) linePts.add(Pt(best, y))
                }
            }
        }

        return when (mode) {
            PathMode.Lane -> laneResult(leftPts, rightPts, aspect)
            PathMode.SingleLine -> lineResult(linePts, aspect)
        }
    }

    private fun laneResult(leftPts: List<Pt>, rightPts: List<Pt>, aspect: Float): LaneResult {
        val left = fit(leftPts)
        val right = fit(rightPts)
        val center = when {
            left != null && right != null -> {
                val width = right.x(1f) - left.x(1f)
                if (width < MIN_LANE_WIDTH) return LaneResult(found = false, aspect = aspect)
                laneWidth += (width - laneWidth) * WIDTH_LEARNING
                Curve((left.a + right.a) / 2, (left.b + right.b) / 2, (left.c + right.c) / 2)
            }
            // One edge out of view: place the centre half a remembered lane width away from it.
            left != null -> left.copy(c = left.c + laneWidth / 2)
            right != null -> right.copy(c = right.c - laneWidth / 2)
            else -> return LaneResult(found = false, aspect = aspect)
        }
        val confidence = (leftPts.size + rightPts.size) / (2f * ROWS)
        return build(center, listOfNotNull(left, right), laneWidth / 2, confidence, aspect)
    }

    private fun lineResult(points: List<Pt>, aspect: Float): LaneResult {
        val line = fit(points)
        lastLine = line
        if (line == null) return LaneResult(found = false, aspect = aspect)
        return build(line, listOf(line), LINE_HALF_SPAN, points.size.toFloat() / ROWS, aspect)
    }

    private fun build(center: Curve, lines: List<Curve>, halfSpan: Float, confidence: Float, aspect: Float): LaneResult {
        // Moving forward means moving up the frame, so the path's direction is the negative slope.
        val offset = ((0.5f - center.x(1f)) / halfSpan).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        val heading = Math.toDegrees(atan(-center.slope(1f) * aspect).toDouble()).toFloat()
        val curvature = (center.slope(1f) - center.slope(ROI_TOP)) * aspect
        val shape = when {
            curvature > CURVE_THRESHOLD -> PathShape.CurveRight
            curvature < -CURVE_THRESHOLD -> PathShape.CurveLeft
            else -> PathShape.Straight
        }
        return LaneResult(
            found = true,
            offset = offset,
            headingDeg = heading,
            curvature = curvature,
            shape = shape,
            confidence = confidence.coerceIn(0f, 1f),
            lines = lines,
            center = center,
            aspect = aspect,
        )
    }

    // Centres (as frame fractions) of bright runs in this row that are thin enough to be paint.
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
        val maxRun = ((MAX_PAINT_WIDTH_FAR + (MAX_PAINT_WIDTH_NEAR - MAX_PAINT_WIDTH_FAR) * y) * cols).toInt().coerceAtLeast(1)

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

    // Least squares with one round of outlier removal, so a stray bright spot does not bend the line.
    private fun fit(points: List<Pt>): Curve? {
        if (points.size < MIN_POINTS) return null
        if (points.maxOf { it.y } - points.minOf { it.y } < MIN_Y_SPAN) return null
        val first = leastSquares(points) ?: return null
        val residuals = points.map { abs(it.x - first.x(it.y)) }
        val rms = sqrt(residuals.sumOf { (it * it).toDouble() } / residuals.size).toFloat()
        val limit = max(OUTLIER_FLOOR, rms * 2.5f)
        val kept = points.filterIndexed { i, _ -> residuals[i] <= limit }
        if (kept.size < MIN_POINTS) return null
        return if (kept.size == points.size) first else leastSquares(kept)
    }

    private fun leastSquares(points: List<Pt>): Curve? {
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
        for (p in points) {
            val y = p.y.toDouble()
            val x = p.x.toDouble()
            val y2 = y * y
            s0 += 1; s1 += y; s2 += y2; s3 += y2 * y; s4 += y2 * y2
            t0 += x; t1 += x * y; t2 += x * y2
        }
        val d = det(s4, s3, s2, s3, s2, s1, s2, s1, s0)
        if (abs(d) < 1e-12) {
            val dl = s0 * s2 - s1 * s1
            if (abs(dl) < 1e-12) return null
            val b = (s0 * t1 - s1 * t0) / dl
            return Curve(0f, b.toFloat(), ((t0 - b * s1) / s0).toFloat())
        }
        val a = det(t2, s3, s2, t1, s2, s1, t0, s1, s0) / d
        val b = det(s4, t2, s2, s3, t1, s1, s2, t0, s0) / d
        val c = det(s4, s3, t2, s3, s2, t1, s2, s1, t0) / d
        return Curve(a.toFloat(), b.toFloat(), c.toFloat())
    }

    private fun det(
        a11: Double, a12: Double, a13: Double,
        a21: Double, a22: Double, a23: Double,
        a31: Double, a32: Double, a33: Double,
    ) = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31) + a13 * (a21 * a32 - a22 * a31)

    companion object {
        /** Rows above this fraction of the frame are ignored: that is sky, walls and far track. */
        const val ROI_TOP = 0.45f
        private const val ROWS = 28
        private const val SAMPLES_PER_ROW = 160
        private const val MIN_CONTRAST = 35
        private const val THRESHOLD_FRACTION = 0.5f
        private const val MAX_PAINT_WIDTH_FAR = 0.05f
        private const val MAX_PAINT_WIDTH_NEAR = 0.10f
        private const val MIN_POINTS = 6
        private const val MIN_Y_SPAN = 0.2f
        private const val OUTLIER_FLOOR = 0.03f
        private const val DEFAULT_LANE_WIDTH = 0.6f
        private const val MIN_LANE_WIDTH = 0.12f
        private const val WIDTH_LEARNING = 0.1f
        private const val LINE_HALF_SPAN = 0.25f
        private const val LINE_GATE = 0.12f
        private const val MAX_OFFSET = 1.5f
        private const val CURVE_THRESHOLD = 0.12f
    }
}
