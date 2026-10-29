package com.basir.run.vision

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds things on the running surface that the object model has no class for: poles, tree
 * trunks, bags, and holes or patches that look different from the surface. It compares cells
 * inside the tracked corridor, away from its painted edges, with the surface just ahead of the
 * runner's feet, and reports clusters of cells that differ strongly in colour or brightness.
 *
 * It is class-agnostic and can raise false alarms (leaves, puddles, lane numbers painted on a
 * track, sharp shadows). Its findings are therefore at most "possible" unless a cluster is large,
 * close and confirmed over several runs. It runs only while the corridor itself is tracked.
 */
class IntrusionDetector {

    private val ly = FloatArray(COLS * ROWS)
    private val lu = FloatArray(COLS * ROWS)
    private val lv = FloatArray(COLS * ROWS)
    private val odd = BooleanArray(COLS * ROWS)
    private val seen = BooleanArray(COLS * ROWS)
    private val stack = IntArray(COLS * ROWS)

    fun detect(frame: CameraFrame, path: PathEstimate, runnerX: Float): List<ObstacleFinding> {
        val left = path.left
        val right = path.right
        if (path.status != PathStatus.Tracked || left == null || right == null) return emptyList()
        val w = frame.uprightWidth
        val h = frame.uprightHeight

        for (r in 0 until ROWS) {
            val py = (rowY(r) * h).toInt().coerceIn(1, h - 2)
            for (c in 0 until COLS) {
                val px = (((c + 0.5f) / COLS) * w).toInt().coerceIn(1, w - 2)
                val i = r * COLS + c
                ly[i] = frame.luma(px, py).toFloat()
                lu[i] = frame.chromaU(px, py).toFloat()
                lv[i] = frame.chromaV(px, py).toFloat()
            }
        }

        // Reference surface: the bottom rows around the runner, where the corridor is known clear
        // enough to have been calibrated on. Median per channel resists a stray cell.
        val seedCol = (runnerX * COLS).toInt().coerceIn(2, COLS - 3)
        val ref = (ROWS - 2 until ROWS).flatMap { r -> (seedCol - 2..seedCol + 2).map { r * COLS + it } }
        val my = median(ref.map { ly[it] })
        val mu = median(ref.map { lu[it] })
        val mv = median(ref.map { lv[it] })
        var spread = 0f
        for (i in ref) spread = max(spread, distance(i, my, mu, mv))
        val limit = max(MIN_LIMIT, spread * SPREAD_FACTOR)

        odd.fill(false)
        seen.fill(false)
        for (r in 0 until ROWS) {
            val y = rowY(r)
            val margin = (right.x(y) - left.x(y)) * EDGE_MARGIN
            val from = left.x(y) + margin
            val to = right.x(y) - margin
            if (to <= from) continue
            for (c in 0 until COLS) {
                val x = (c + 0.5f) / COLS
                if (x < from || x > to) continue
                val i = r * COLS + c
                odd[i] = distance(i, my, mu, mv) > limit
            }
        }

        val findings = ArrayList<ObstacleFinding>()
        for (start in odd.indices) {
            if (!odd[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var cells = 0
            var minR = ROWS; var maxR = -1; var minC = COLS; var maxC = -1
            while (top > 0) {
                val i = stack[--top]
                cells++
                val r = i / COLS
                val c = i % COLS
                minR = min(minR, r); maxR = max(maxR, r); minC = min(minC, c); maxC = max(maxC, c)
                for (k in 0 until 4) {
                    val nr = r + DR[k]
                    val nc = c + DC[k]
                    if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue
                    val j = nr * COLS + nc
                    if (odd[j] && !seen[j]) {
                        seen[j] = true
                        stack[top++] = j
                    }
                }
            }
            if (cells < MIN_CELLS || maxR - minR + 1 < MIN_ROWS) continue
            val box = Detection(
                label = LABEL,
                score = min(1f, cells / LARGE_CELLS.toFloat()),
                left = minC.toFloat() / COLS,
                top = rowY(minR) - HALF_ROW,
                right = (maxC + 1).toFloat() / COLS,
                bottom = rowY(maxR) + HALF_ROW,
            )
            val level = if (cells >= LARGE_CELLS && box.bottom >= NEAR_BOTTOM) ObstacleLevel.Hazard else ObstacleLevel.Possible
            findings += ObstacleFinding(box, overlap = 1f, level = level)
        }
        return findings
    }

    private fun rowY(r: Int) = TOP + (1f - TOP) * (r + 0.5f) / ROWS

    private fun distance(i: Int, y: Float, u: Float, v: Float): Float {
        val dy = (ly[i] - y) * LUMA_WEIGHT
        val du = lu[i] - u
        val dv = lv[i] - v
        return sqrt(dy * dy + du * du + dv * dv)
    }

    private fun median(values: List<Float>) = values.sorted()[values.size / 2]

    companion object {
        const val LABEL = "surface_change"
        private const val COLS = 48
        private const val ROWS = 16
        private const val TOP = 0.55f
        private const val HALF_ROW = (1f - TOP) / ROWS / 2
        private const val EDGE_MARGIN = 0.12f
        private const val LUMA_WEIGHT = 0.35f
        private const val MIN_LIMIT = 28f
        private const val SPREAD_FACTOR = 3f
        private const val MIN_CELLS = 6
        private const val MIN_ROWS = 2
        private const val LARGE_CELLS = 14
        private const val NEAR_BOTTOM = 0.85f
        private val DR = intArrayOf(-1, 1, 0, 0)
        private val DC = intArrayOf(0, 0, -1, 1)
    }
}
