package run.basir.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Leaves, petals, pine needles and similar ground cover lying flat on the way are ground, not
 * obstacles (owner's request, research round 6). The walkable-ground model (SegFormer, ADE20K
 * classes) has no class for "leaves on a path": it names a leaf-covered patch plant, tree, flower,
 * grass or earth, and the under-the-feet rule then drops it from the walkable ground, so the way
 * seemed to end at the leaves (warning, then a stop after a second).
 *
 * What separates a carpet of leaves from a bush, a trunk or a fallen branch is height above the
 * floor, not colour or class. This resolver accepts a ground-cover cell as walkable only when all
 * of these hold:
 *  - its class is one of [GroundCoverConfig.coverClasses];
 *  - it lies below the horizon and within the range where depth can check it
 *    ([GroundCoverConfig.maxRangeM]);
 *  - the depth floor planner found a floor, and less than [GroundCoverConfig.maxRaisedShare] of
 *    the depth cells under it (grown by one cell) stand above the floor (FloorPlanner's
 *    obstacle mask: 8 cm to 2.1 m above the fitted floor plane);
 *  - and either (A) it fills a gap of the walkable ground: walkable ground on both sides of it in
 *    its row, or below and above it in its column (leaves across or inside the path), or (B) the
 *    runner stands on it: the foot area is mostly flat cover and the model found almost no
 *    walkable ground there (a leaf-covered trail); then the flat cover connected to the feet is taken.
 *
 * Without depth (no model, no floor found) nothing is accepted: unknown ground stays unknown.
 * Flat grass beside a recognised path is not a gap and the runner is not standing on it, so it is
 * not added: the path keeps its edges. Accepted cells are only "flat ground seen", never "safe":
 * the camera cannot tell wet, loose or slippery ground.
 */
data class GroundCoverConfig(
    /** ADE20K-150 ids (0-based): 4 tree, 9 grass, 13 earth, 17 plant, 29 field, 46 sand, 66 flower, 94 land. */
    val coverClasses: Set<Int> = setOf(4, 9, 13, 17, 29, 46, 66, 94),
    val maxRaisedShare: Double = 0.2,
    val maxRangeM: Double = 6.0,         // FloorPlanner marks raised points only this far (DepthHazards.RUNNING.lookaheadM)
    val footTop: Double = 0.8,           // foot area, as UnderfootClasses: bottom 20 % ...
    val footLeft: Double = 0.35,         // ... and the middle 30 %
    val footRight: Double = 0.65,
    val standOnCoverShare: Double = 0.5, // (B): flat cover + walkable must fill this much of the foot area ...
    val standOnWalkableMax: Double = 0.15, // ... while the model's own walkable ground fills less than this
)

class GroundCoverResolver(private val config: GroundCoverConfig = GroundCoverConfig()) {

    class Result(val walkable: FloatArray, val accepted: Int)

    /**
     * [labels] class per cell and [walkable] (0/1) of the segmentation grid ([w] x [h], covering the
     * whole frame); [raised] the depth planner's obstacle mask ([rw] x [rh], whole frame) or null;
     * [camera] the camera of the frame (any resolution), to know which rows lie within [GroundCoverConfig.maxRangeM].
     */
    fun resolve(
        labels: IntArray, w: Int, h: Int, walkable: FloatArray,
        raised: BooleanArray?, rw: Int, rh: Int,
        camera: CameraModel,
    ): Result {
        require(labels.size == w * h && walkable.size == w * h)
        val out = walkable.copyOf()
        if (raised == null) return Result(out, 0)
        require(raised.size == rw * rh)
        val cam = camera.resized(w, h)
        // Rows (of the segmentation grid) whose ground lies within the checked range
        val rowOk = BooleanArray(h) { y ->
            val d = cam.groundDepth(y + 0.5)
            d != null && d <= config.maxRangeM
        }
        // Depth raised mask grown by one cell, so the edge of a trunk or a bush is not taken as flat
        val grown = BooleanArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) if (raised[y * rw + x]) {
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until rw && ny in 0 until rh) grown[ny * rw + nx] = true
            }
        }
        val walk = BooleanArray(w * h) { walkable[it] >= 0.5f }
        val cand = BooleanArray(w * h)
        for (y in 0 until h) {
            if (!rowOk[y]) continue
            val ry0 = y * rh / h; val ry1 = max(ry0 + 1, (y + 1) * rh / h)
            for (x in 0 until w) {
                val k = y * w + x
                if (walk[k] || labels[k] !in config.coverClasses) continue
                val rx0 = x * rw / w; val rx1 = max(rx0 + 1, (x + 1) * rw / w)
                var up = 0; var n = 0
                for (ry in ry0 until min(ry1, rh)) for (rx in rx0 until min(rx1, rw)) { n++; if (grown[ry * rw + rx]) up++ }
                if (n > 0 && up.toDouble() / n < config.maxRaisedShare) cand[k] = true
            }
        }
        val accept = BooleanArray(w * h)
        // (A) gaps of the walkable ground, in rows and in columns
        for (y in 0 until h) fillGaps(walk, cand, accept, w, h, y, horizontal = true)
        for (x in 0 until w) fillGaps(walk, cand, accept, w, h, x, horizontal = false)
        // (B) standing on flat cover
        val fy0 = (h * config.footTop).toInt().coerceIn(0, h - 1)
        val fx0 = (w * config.footLeft).toInt(); val fx1 = (w * config.footRight).toInt().coerceAtMost(w)
        var footN = 0; var footWalk = 0; var footCover = 0
        for (y in fy0 until h) for (x in fx0 until fx1) {
            val k = y * w + x
            footN++
            if (walk[k]) footWalk++ else if (cand[k]) footCover++
        }
        if (footN > 0 && footWalk.toDouble() / footN < config.standOnWalkableMax &&
            (footWalk + footCover).toDouble() / footN >= config.standOnCoverShare
        ) {
            val queue = ArrayDeque<Int>()
            for (y in fy0 until h) for (x in fx0 until fx1) {
                val k = y * w + x
                if (cand[k] && !accept[k]) { accept[k] = true; queue.add(k) }
            }
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst(); val x = k % w; val y = k / w
                for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (cand[j] && !accept[j]) { accept[j] = true; queue.add(j) }
                }
            }
        }
        var n = 0
        for (k in out.indices) if (accept[k]) { out[k] = 1f; n++ }
        return Result(out, n)
    }

    /** Marks runs of candidate cells that have walkable ground at both ends of the run (in one row or column). */
    private fun fillGaps(walk: BooleanArray, cand: BooleanArray, accept: BooleanArray, w: Int, h: Int, line: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        fun idx(p: Int) = if (horizontal) line * w + p else p * w + line
        var p = 0
        while (p < len) {
            if (!cand[idx(p)]) { p++; continue }
            var e = p
            while (e < len && cand[idx(e)]) e++
            val before = p > 0 && walk[idx(p - 1)]
            val after = e < len && walk[idx(e)]
            if (before && after) for (q in p until e) accept[idx(q)] = true
            p = e
        }
    }
}

/**
 * Whether a surface-change finding (IntrusionDetector: a patch of the corridor whose colour differs
 * from the ground at the feet) stands above the floor. Leaves, puddles, painted numbers and sharp
 * shadows are flat; a bag, a pole or a kerb is not. Holes are kept through the drop mask.
 */
object FlatSurfaceCheck {
    /** A finding with less than this share of raised (or drop) depth cells is a flat surface change. */
    const val MIN_RAISED_SHARE = 0.15

    /**
     * Share of cells of [mask] ([w] x [h], whole frame) inside the box (frame fractions), or null when
     * there is no mask (then the caller keeps the finding: no evidence that it is flat).
     */
    fun raisedShare(mask: BooleanArray?, w: Int, h: Int, left: Double, top: Double, right: Double, bottom: Double): Double? {
        if (mask == null) return null
        val x0 = (left * w).toInt().coerceIn(0, w - 1); val x1 = max(x0 + 1, (right * w).toInt().coerceIn(0, w))
        val y0 = (top * h).toInt().coerceIn(0, h - 1); val y1 = max(y0 + 1, (bottom * h).toInt().coerceIn(0, h))
        var n = 0; var up = 0
        for (y in y0 until y1) for (x in x0 until x1) { n++; if (mask[y * w + x]) up++ }
        return if (n == 0) null else up.toDouble() / n
    }

    /** True when the finding should be kept: no depth, or enough raised or drop cells under it. */
    fun keep(raised: BooleanArray?, drop: BooleanArray?, w: Int, h: Int, left: Double, top: Double, right: Double, bottom: Double): Boolean {
        val r = raisedShare(raised, w, h, left, top, right, bottom) ?: return true
        val d = raisedShare(drop, w, h, left, top, right, bottom) ?: 0.0
        return r >= MIN_RAISED_SHARE || d >= MIN_RAISED_SHARE
    }
}
