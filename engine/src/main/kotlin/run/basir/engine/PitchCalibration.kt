package run.basir.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Camera tilt from the image when the phone's motion sensor is unavailable or must be checked
 * during calibration. Lines that are parallel on the ground (lane lines, path edges, a guide
 * line and a path edge) only come out parallel in ground coordinates at the right tilt: too
 * little tilt and they splay apart with distance, too much and they converge. The detector is
 * also scale-aware (expected line width in pixels comes from the tilt), so a wrong tilt loses
 * lines altogether. Sweeps the tilt and keeps the one that gives the most parallel, best
 * supported pair of lines — or, with a single line, the best supported line.
 */
class PitchCalibration(
    private val detector: ContrastLineDetector = ContrastLineDetector(),
    private val extractor: LineExtractor = LineExtractor(),
) {
    data class Result(val pitchDeg: Double, val score: Double, val lines: List<LineCandidate>)

    fun estimate(image: RgbImage, camera: CameraModel, from: Double = -10.0, to: Double = 50.0, step: Double = 5.0): Result? {
        var best: Result? = null
        fun tryPitch(p: Double) {
            val cam = camera.copy(pitchDeg = p)
            val lines = extractor.extract(detector.detect(image, cam), cam)
            val s = score(lines)
            if (s > 0 && (best == null || s > best!!.score)) best = Result(p, s, lines)
        }
        var p = from
        while (p <= to + 1e-9) { tryPitch(p); p += step }
        val coarse = best ?: return null
        // Refine around the best coarse tilt
        for (d in listOf(-2.5, 2.5)) tryPitch(coarse.pitchDeg + d)
        return best
    }

    /** How "right" a tilt looks: parallel line pairs score highest. */
    internal fun score(lines: List<LineCandidate>): Double {
        if (lines.isEmpty()) return 0.0
        var pairBest = 0.0
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i].curve
            val b = lines[j].curve
            val near = max(a.nearM, b.nearM)
            val far = min(a.farM, b.farM)
            if (far - near < 2.0) continue
            val wNear = abs(b.lateralAt(near) - a.lateralAt(near))
            val wFar = abs(b.lateralAt(far) - a.lateralAt(far))
            val mean = (wNear + wFar) / 2
            if (mean < 0.5) continue
            val spread = abs(wFar - wNear) / mean
            pairBest = max(pairBest, min(lines[i].score, lines[j].score) * exp(-spread / 0.1))
        }
        return if (pairBest > 0) pairBest else 0.3 * lines.first().score
    }
}
