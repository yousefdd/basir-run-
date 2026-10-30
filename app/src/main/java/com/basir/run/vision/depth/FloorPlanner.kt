package com.basir.run.vision.depth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Floor, obstacles, head-level hazards and drops from a relative depth map.
 *
 * Kotlin port of the blind-navigation tool's planner (mobile/planner.js, itself a port of
 * scripts/path_planner.py), kept step for step so its results can be checked against the
 * Python reference (see FloorPlannerTest):
 * relative disparity (larger = closer) → floor plane by RANSAC (d = a·v + b·u + c) → every
 * pixel gets an approximate metric position from the camera height and tilt → obstacles
 * between [Config.minObstacleHeightM] and [Config.maxObstacleHeightM] above the floor,
 * head-level parts of them, and drops below the floor → free distance along a few headings.
 *
 * Distances depend on the camera height and tilt and on flat ground; they are estimates, used
 * here only to rank how urgent something is, never spoken as metres.
 */
class FloorPlanner(private val geometry: Geometry, private val cfg: Config = Config()) {

    data class Geometry(
        val heightM: Double = 1.3,
        /** Camera tilt below the horizon; null = estimate it from the depth map. */
        val pitchDeg: Double? = 20.0,
        val hfovDeg: Double = 70.0,
    ) {
        fun focalPx(width: Int) = (width / 2.0) / tan(hfovDeg * DEG / 2)
        private val th get() = (pitchDeg ?: 20.0) * DEG

        /** (down, forward) components of the ray through image row v, for camera z = 1. */
        fun rayDown(v: Double, height: Int, f: Double): Double {
            val y = (v - (height - 1) / 2.0) / f
            return y * cos(th) + sin(th)
        }

        fun rayForward(v: Double, height: Int, f: Double): Double {
            val y = (v - (height - 1) / 2.0) / f
            return cos(th) - y * sin(th)
        }

        fun floorDepth(v: Double, height: Int, f: Double): Double {
            val down = rayDown(v, height, f)
            return if (down > 1e-4) heightM / down else Double.POSITIVE_INFINITY
        }

        fun groundDistance(v: Double, height: Int, f: Double): Double {
            val down = rayDown(v, height, f)
            return if (down > 1e-4) (heightM / down) * rayForward(v, height, f) else Double.POSITIVE_INFINITY
        }

        fun horizonRow(height: Int, f: Double) = (height - 1) / 2.0 - f * tan(th)
    }

    data class Config(
        val bodyWidthM: Double = 0.75,
        val safeDistanceM: Double = 2.0,
        val stopDistanceM: Double = 1.2,
        val lookaheadM: Double = 5.0,
        val minObstacleHeightM: Double = 0.08,
        val maxObstacleHeightM: Double = 2.1,
        val headLevelHeightM: Double = 1.1,
        val closerRatio: Double = 0.8,
        val noiseMargin: Double = 0.03,
        val dropRatio: Double = 1.25,
        val dropRangeM: Double = 3.0,
        val detectDrops: Boolean = true,
        val headingsDeg: IntArray = intArrayOf(-35, -20, -10, 0, 10, 20, 35),
        val minPixels: Int = 8,
        val ransacIters: Int = 150,
        val ransacTau: Double = 0.015,
        val minFloorInliers: Double = 0.3,
        val turnPenaltyMPerDeg: Double = 0.01,
        val fallbackPitchDeg: Double = 20.0,
    )

    fun analyze(disparity: FloatArray, w: Int, h: Int): DepthAnalysis {
        var geo = geometry
        val norm = normalize(disparity)
        val d = norm.values
        var pitchSource = PitchSource.Config
        if (geo.pitchDeg == null) {
            val est = estimatePitch(d, w, h, norm.lo, norm.hi, geo.hfovDeg)
            pitchSource = if (est != null) PitchSource.Image else PitchSource.Default
            geo = geo.copy(pitchDeg = est ?: cfg.fallbackPitchDeg)
        }
        val pitchDeg = round(geo.pitchDeg!!, 1)!!
        val f = geo.focalPx(w)
        val vH = geo.horizonRow(h, f)
        val vStart = floor(max(0.5 * h, vH + 0.05 * h)).toInt()
        val (firstPlane, inlierFrac) = fitFloorPlane(d, w, h, vStart)
        if (firstPlane == null || inlierFrac < cfg.minFloorInliers) {
            return DepthAnalysis(
                floorValid = false, floorConfidence = round(inlierFrac)!!, action = DepthAction.Caution, targetHeadingDeg = 0,
                clearDistanceM = null, headings = emptyList(), sectors = Sectors(null, null, null), nearestObstacle = null,
                headLevelM = null, dropM = null, pitchDeg = pitchDeg, pitchSource = pitchSource, raisedSurface = false,
                width = w, height = h, obstacleMask = null, dropMask = null,
            )
        }
        val (coef, switched) = lowestHorizontalPlane(d, w, h, firstPlane, vH)
        val (a, b, c) = Triple(coef[0], coef[1], coef[2])
        val (a0, b0, c0) = Triple(firstPlane[0], firstPlane[1], firstPlane[2])

        val rowDown = DoubleArray(h) { geo.rayDown(it.toDouble(), h, f) }
        val rowFwd = DoubleArray(h) { geo.rayForward(it.toDouble(), h, f) }
        val zFloorRow = DoubleArray(h) { geo.floorDepth(it.toDouble(), h, f) }
        val distFloorRow = DoubleArray(h) { geo.groundDistance(it.toDouble(), h, f) }
        val th = geo.pitchDeg!! * DEG
        val cosT = cos(th)
        val sinT = sin(th)
        val cy = (h - 1) / 2.0
        val cx = (w - 1) / 2.0

        val obstacle = BooleanArray(w * h)
        val dropMask = BooleanArray(w * h)
        val oF = DoubleList()
        val oL = DoubleList()
        val oH = DoubleList()
        val dF = DoubleList()
        val dL = DoubleList()
        val nF = DoubleList()
        val nL = DoubleList()
        for (v in 0 until h) {
            for (u in 0 until w) {
                val i = v * w + u
                val di = d[i].toDouble()
                val resid = di - (a * v + b * u + c)
                // Same disparity as the floor at eqRow → same camera depth.
                val eqRow = (di - b * u - c) / a
                val yEq = (eqRow - cy) / f
                val downEq = yEq * cosT + sinT
                val zEq = if (downEq > 1e-4) geo.heightM / downEq else Double.POSITIVE_INFINITY
                val zFl = zFloorRow[v]
                val xk = (u - cx) / f
                if (zEq.isFinite()) {
                    val fwd = zEq * rowFwd[v]
                    val lat = zEq * xk
                    val hgt = geo.heightM - zEq * rowDown[v]
                    // On a raised surface (table top, or the floor before a drop) = in the way.
                    val closer = zEq < cfg.closerRatio * zFl ||
                        (switched && abs(di - (a0 * v + b0 * u + c0)) < cfg.ransacTau * 1.5)
                    if (resid > cfg.noiseMargin && closer && fwd <= cfg.lookaheadM &&
                        hgt >= cfg.minObstacleHeightM && hgt <= cfg.maxObstacleHeightM
                    ) {
                        obstacle[i] = true
                        oF.add(fwd); oL.add(lat); oH.add(hgt)
                    }
                }
                if (v > vH && distFloorRow[v] < cfg.dropRangeM) {
                    val df = distFloorRow[v]
                    nF.add(df); nL.add(df * xk)
                    if (cfg.detectDrops && resid < -cfg.noiseMargin && zEq > cfg.dropRatio * zFl) {
                        dropMask[i] = true
                        dF.add(df); dL.add(df * xk)
                    }
                }
            }
        }

        val halfFov = geo.hfovDeg / 2
        val high = BooleanArray(oH.size) { oH[it] >= cfg.headLevelHeightM }
        val headings = ArrayList<HeadingFree>()
        for (psi in cfg.headingsDeg) {
            if (abs(psi) > halfFov - 5) continue
            var dropAt: Double? = null
            if (cfg.detectDrops && dF.size > 0) {
                val inDrop = corridorForward(dF, dL, psi, null, true)
                val nFloor = corridorForward(nF, nL, psi, null, true).size
                if (inDrop.size >= max(cfg.minPixels.toDouble(), 0.15 * max(nFloor, 1))) {
                    dropAt = round(kthSmallest(inDrop.toArray(), cfg.minPixels - 1))
                }
            }
            headings += HeadingFree(
                headingDeg = psi,
                freeM = round(corridorFree(oF, oL, psi, null, true))!!,
                freeLowM = round(corridorFree(oF, oL, psi, high, false))!!,
                freeHighM = round(corridorFree(oF, oL, psi, high, true))!!,
                dropM = dropAt,
            )
        }

        val straight = headings.firstOrNull { it.headingDeg == 0 }
            ?: headings.reduce { p, e -> if (abs(e.headingDeg) < abs(p.headingDeg)) e else p }
        val clear = straight.freeM
        val action: DepthAction
        val target: Int
        if (clear >= cfg.safeDistanceM) {
            action = DepthAction.Forward
            target = straight.headingDeg
        } else {
            fun score(e: HeadingFree) = e.freeM - cfg.turnPenaltyMPerDeg * abs(e.headingDeg)
            val best = headings.reduce { p, e -> if (score(e) > score(p)) e else p }
            if (best.freeM >= cfg.safeDistanceM) {
                target = best.headingDeg
                action = headingToAction(target)
            } else if (clear > cfg.stopDistanceM) {
                target = if (best.freeM > clear + 0.5) best.headingDeg else straight.headingDeg
                action = DepthAction.Slow
            } else {
                target = best.headingDeg
                action = DepthAction.Stop
            }
        }

        var nearest: NearObstacle? = null
        if (oF.size >= cfg.minPixels) {
            val kth = kthSmallest(oF.toArray(), cfg.minPixels - 1)
            var k = 0
            while (oF[k] != kth) k++
            nearest = NearObstacle(round(oF[k])!!, round(atan2(oL[k], oF[k]) / DEG, 1)!!, round(oH[k])!!)
        }
        val headLevel = if (straight.freeHighM < cfg.safeDistanceM && straight.freeHighM < straight.freeLowM - 0.4) straight.freeHighM else null

        return DepthAnalysis(
            floorValid = true, floorConfidence = round(inlierFrac)!!, action = action, targetHeadingDeg = target,
            clearDistanceM = round(clear), headings = headings, sectors = sectors(oF, oL), nearestObstacle = nearest,
            headLevelM = headLevel, dropM = straight.dropM, pitchDeg = pitchDeg, pitchSource = pitchSource, raisedSurface = switched,
            width = w, height = h, obstacleMask = obstacle, dropMask = dropMask,
        )
    }

    // ---------- steps ----------

    private class Normalized(val values: FloatArray, val lo: Double, val hi: Double)

    private fun normalize(disp: FloatArray): Normalized {
        val step = max(1, disp.size / 4000)
        val sample = DoubleList()
        var i = 0
        while (i < disp.size) { sample.add(disp[i].toDouble()); i += step }
        val sorted = sample.toArray().also { it.sort() }
        val lo = percentile(sorted, 2.0)
        val hi = percentile(sorted, 98.0)
        val s = 1 / (hi - lo + 1e-6)
        val out = FloatArray(disp.size) { min(1.2, max(-0.2, (disp[it] - lo) * s)).toFloat() }
        return Normalized(out, lo, hi)
    }

    private fun fitFloorPlane(d: FloatArray, w: Int, h: Int, vStart: Int): Pair<DoubleArray?, Double> {
        if (vStart >= h - 4) return null to 0.0
        val vs = IntList()
        val us = IntList()
        val zs = DoubleList()
        var v = vStart
        while (v < h) {
            var u = 0
            while (u < w) { vs.add(v); us.add(u); zs.add(d[v * w + u].toDouble()); u += 2 }
            v += 2
        }
        val n = zs.size
        if (n < 30) return null to 0.0
        val rand = Mulberry32(0)
        val minSlope = 0.15 / h
        val weight = DoubleArray(n) { 0.5 + (vs[it] - vStart).toDouble() / max(h - vStart, 1) }
        fun ok(c: DoubleArray?) = c != null && c[0] >= minSlope && abs(c[1]) <= 0.5 * c[0]

        var best: DoubleArray? = null
        var bestScore = -1.0
        repeat(cfg.ransacIters) {
            val i0 = floor(rand.next() * n).toInt()
            val i1 = floor(rand.next() * n).toInt()
            val i2 = floor(rand.next() * n).toInt()
            if (i0 == i1 || i1 == i2 || i0 == i2) return@repeat
            val c = solve3(
                arrayOf(doubleArrayOf(vs[i0].toDouble(), us[i0].toDouble(), 1.0), doubleArrayOf(vs[i1].toDouble(), us[i1].toDouble(), 1.0), doubleArrayOf(vs[i2].toDouble(), us[i2].toDouble(), 1.0)),
                doubleArrayOf(zs[i0], zs[i1], zs[i2]),
            )
            if (!ok(c)) return@repeat
            var score = 0.0
            for (k in 0 until n) if (abs(c!![0] * vs[k] + c[1] * us[k] + c[2] - zs[k]) < cfg.ransacTau) score += weight[k]
            if (score > bestScore) { best = c; bestScore = score }
        }
        val b0 = best ?: return null to 0.0

        // Least-squares refit on the inliers (normal equations).
        val tau = cfg.ransacTau * 1.5
        val m = Array(3) { DoubleArray(3) }
        val r = DoubleArray(3)
        var cnt = 0
        for (k in 0 until n) {
            if (abs(b0[0] * vs[k] + b0[1] * us[k] + b0[2] - zs[k]) >= tau) continue
            val x = doubleArrayOf(vs[k].toDouble(), us[k].toDouble(), 1.0)
            for (p in 0 until 3) { r[p] += x[p] * zs[k]; for (q in 0 until 3) m[p][q] += x[p] * x[q] }
            cnt++
        }
        if (cnt < 10) return null to 0.0
        val coef = solve3(m, r)
        if (!ok(coef)) return null to 0.0
        // Narrow corridors: what matters is the floor right in front (bottom rows, central columns).
        var inl = 0
        var front = 0
        var frontInl = 0
        for (k in 0 until n) {
            val good = abs(coef!![0] * vs[k] + coef[1] * us[k] + coef[2] - zs[k]) < tau
            if (good) inl++
            if (vs[k] >= 0.7 * h && abs(us[k] - (w - 1) / 2.0) < 0.25 * w) { front++; if (good) frontInl++ }
        }
        return coef to max(inl.toDouble() / n, if (front > 0) 0.6 * frontInl / front else 0.0)
    }

    /** Camera tilt from the image: disparity is about 0 at infinity, so the floor reaches 0 at the horizon. */
    private fun estimatePitch(d: FloatArray, w: Int, h: Int, lo: Double, hi: Double, hfovDeg: Double): Double? {
        val (coef, frac) = fitFloorPlane(d, w, h, floor(0.5 * h).toInt())
        if (coef == null || frac < cfg.minFloorInliers) return null
        val zero = -lo / (hi - lo + 1e-6)
        val vH = (zero - coef[1] * (w - 1) / 2 - coef[2]) / coef[0]
        val f = (w / 2.0) / tan(hfovDeg * DEG / 2)
        val pitch = atan(((h - 1) / 2.0 - vH) / f) / DEG
        return if (pitch >= -5 && pitch <= 60) pitch else null
    }

    /**
     * The fitted plane may be a table top. All horizontal surfaces share the horizon, and the
     * floor is the lowest of them (smallest significant slope). Returns the plane and whether it changed.
     */
    private fun lowestHorizontalPlane(d: FloatArray, w: Int, h: Int, coef: DoubleArray, vH: Double): Pair<DoubleArray, Boolean> {
        val (a, b, c) = Triple(coef[0], coef[1], coef[2])
        val cx = (w - 1) / 2.0
        val r = b / a
        val t = a * vH + b * cx + c
        val k = DoubleList()
        for (v in ceil(0.35 * h).toInt() until h) for (u in 0 until w) {
            val den = v - vH + r * (u - cx)
            if (den > 0.15 * h) k.add((d[v * w + u] - t) / den)
        }
        if (k.size < 50) return coef to false
        val minSupport = max(0.05 * k.size, 6.0 * cfg.minPixels)
        var kFloor = a
        var switched = false
        for (it in 0 until 3) {
            val logk = DoubleList()
            for (j in 0 until k.size) { val x = k[j]; if (x > 0.2 * kFloor && x < 0.85 * kFloor) logk.add(ln(x)) }
            if (logk.size < minSupport) break
            var mn = Double.POSITIVE_INFINITY
            var mx = Double.NEGATIVE_INFINITY
            for (j in 0 until logk.size) { mn = min(mn, logk[j]); mx = max(mx, logk[j]) }
            val bins = IntArray(48)
            val bw = ((mx - mn) / 48).let { if (it == 0.0) 1e-9 else it }
            for (j in 0 until logk.size) bins[min(47, floor((logk[j] - mn) / bw).toInt())]++
            var top = 0
            for (j in 1 until 48) if (bins[j] > bins[top]) top = j
            val center = mn + (top + 0.5) * bw
            val members = DoubleList()
            for (j in 0 until logk.size) if (abs(logk[j] - center) < 0.05) members.add(exp(logk[j]))
            if (members.size < minSupport) break
            kFloor = percentile(members.toArray().also { m -> m.sort() }, 50.0)
            switched = true
        }
        if (!switched) return coef to false
        return doubleArrayOf(kFloor, kFloor * r, t - kFloor * vH - kFloor * r * cx) to true
    }

    private fun corridorForward(fs: DoubleList, ls: DoubleList, psi: Int, mask: BooleanArray?, want: Boolean): DoubleList {
        val out = DoubleList()
        val half = cfg.bodyWidthM / 2
        val p = psi * DEG
        val cp = cos(p)
        val sp = sin(p)
        for (i in 0 until fs.size) {
            if (mask != null && mask[i] != want) continue
            val f2 = fs[i] * cp + ls[i] * sp
            val l2 = -fs[i] * sp + ls[i] * cp
            if (abs(l2) < half && f2 > 0) out.add(f2)
        }
        return out
    }

    private fun corridorFree(fs: DoubleList, ls: DoubleList, psi: Int, mask: BooleanArray?, want: Boolean): Double {
        val blocked = corridorForward(fs, ls, psi, mask, want)
        if (blocked.size < cfg.minPixels) return cfg.lookaheadM
        return min(kthSmallest(blocked.toArray(), cfg.minPixels - 1), cfg.lookaheadM)
    }

    private fun sectors(fs: DoubleList, ls: DoubleList): Sectors {
        val left = DoubleList()
        val center = DoubleList()
        val right = DoubleList()
        for (i in 0 until fs.size) {
            val b = atan2(ls[i], fs[i]) / DEG
            (if (b < -12) left else if (b > 12) right else center).add(fs[i])
        }
        fun near(xs: DoubleList) = if (xs.size >= cfg.minPixels) round(kthSmallest(xs.toArray(), cfg.minPixels - 1)) else null
        return Sectors(near(left), near(center), near(right))
    }

    companion object {
        const val DEG = PI / 180

        fun headingToAction(h: Int): DepthAction = when {
            abs(h) <= 5 -> DepthAction.Forward
            h < 0 -> if (h >= -20) DepthAction.SlightLeft else DepthAction.Left
            else -> if (h <= 20) DepthAction.SlightRight else DepthAction.Right
        }

        /** Rounds like the reference implementation (half up), keeping null and non-finite as null. */
        fun round(x: Double?, digits: Int = 2): Double? {
            if (x == null || !x.isFinite()) return null
            val p = Math.pow(10.0, digits.toDouble())
            return floor(x * p + 0.5) / p
        }

        fun percentile(sorted: DoubleArray, p: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val idx = (sorted.size - 1) * p / 100
            val lo = floor(idx).toInt()
            val hi = ceil(idx).toInt()
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
        }

        /** k-th smallest (0-based) by partial selection; the array is reordered. */
        fun kthSmallest(a: DoubleArray, k: Int): Double {
            var lo = 0
            var hi = a.size - 1
            while (lo < hi) {
                val pivot = a[(lo + hi) shr 1]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (a[i] < pivot) i++
                    while (a[j] > pivot) j--
                    if (i <= j) { val t = a[i]; a[i] = a[j]; a[j] = t; i++; j-- }
                }
                if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
            }
            return a[k]
        }

        /** Cramer's rule for a 3×3 system; null if singular. */
        fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val (a, b1, c) = Triple(m[0][0], m[0][1], m[0][2])
            val (d, e, f) = Triple(m[1][0], m[1][1], m[1][2])
            val (g, h, i) = Triple(m[2][0], m[2][1], m[2][2])
            val det = a * (e * i - f * h) - b1 * (d * i - f * g) + c * (d * h - e * g)
            if (abs(det) < 1e-12) return null
            val dx = b[0] * (e * i - f * h) - b1 * (b[1] * i - f * b[2]) + c * (b[1] * h - e * b[2])
            val dy = a * (b[1] * i - f * b[2]) - b[0] * (d * i - f * g) + c * (d * b[2] - b[1] * g)
            val dz = a * (e * b[2] - b[1] * h) - b1 * (d * b[2] - b[1] * g) + b[0] * (d * h - e * g)
            return doubleArrayOf(dx / det, dy / det, dz / det)
        }
    }
}

enum class DepthAction { Forward, SlightLeft, Left, SlightRight, Right, Slow, Stop, Caution }

enum class PitchSource { Config, Image, Default }

data class HeadingFree(val headingDeg: Int, val freeM: Double, val freeLowM: Double, val freeHighM: Double, val dropM: Double?)

data class NearObstacle(val distanceM: Double, val bearingDeg: Double, val heightM: Double)

/** Nearest estimated obstacle distance left of −12°, within ±12° and right of +12°. */
data class Sectors(val left: Double?, val center: Double?, val right: Double?)

class DepthAnalysis(
    /** A floor plane was found in front of the runner; without it nothing else is meaningful. */
    val floorValid: Boolean,
    val floorConfidence: Double,
    val action: DepthAction,
    val targetHeadingDeg: Int,
    /** Estimated free distance straight ahead in a body-wide corridor. */
    val clearDistanceM: Double?,
    val headings: List<HeadingFree>,
    val sectors: Sectors,
    val nearestObstacle: NearObstacle?,
    /** Something at head height straight ahead that a cane would not touch. */
    val headLevelM: Double?,
    /** A possible step down or hole straight ahead (experimental in the reference tool). */
    val dropM: Double?,
    val pitchDeg: Double,
    val pitchSource: PitchSource,
    /** The first plane found was a raised surface (a table top); the lower one is the floor. */
    val raisedSurface: Boolean,
    val width: Int,
    val height: Int,
    val obstacleMask: BooleanArray?,
    val dropMask: BooleanArray?,
)

/** mulberry32, the deterministic generator of the reference, so RANSAC picks the same samples. */
private class Mulberry32(seed: Int) {
    private var a = seed
    fun next(): Double {
        a += 0x6d2b79f5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }
}

/** Growable primitive lists, to avoid boxing hundreds of thousands of values per frame. */
private class DoubleList {
    private var data = DoubleArray(256)
    var size = 0
        private set
    fun add(x: Double) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = x }
    operator fun get(i: Int) = data[i]
    fun toArray(): DoubleArray = data.copyOf(size)
}

private class IntList {
    private var data = IntArray(256)
    var size = 0
        private set
    fun add(x: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = x }
    operator fun get(i: Int) = data[i]
}
