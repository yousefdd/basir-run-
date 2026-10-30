package run.basir.devtools

import org.json.JSONArray
import org.json.JSONObject
import run.basir.engine.CameraModel
import run.basir.engine.ContrastLineDetector
import run.basir.engine.FrameInput
import run.basir.engine.IntelligentPathPlanner
import run.basir.engine.LineCandidate
import run.basir.engine.LineExtractor
import run.basir.engine.PathMode
import run.basir.engine.PitchCalibration
import run.basir.engine.RgbImage
import run.basir.engine.toDegrees
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan

/**
 * Evaluates the virtual path against hand-annotated running-lane masks (e.g. TrackAid-DT,
 * prepared with tools/trackaid_to_masks.py). For every frame:
 *  - in_lane: fraction of the path (1.5–10 m ahead) that projects inside the annotated lane
 *  - center_err: |path − lane center| / lane width on the same image rows (0 = center, 0.5 = edge)
 *  - found: whether the planner produced a path at all (otherwise it says STOP)
 * Results are reported per source video, since frames of one video are correlated.
 *
 *   gradle :devtools:run -PmainClass=run.basir.devtools.EvaluateLanesKt --args="data_dir out_dir"
 */
fun main(args: Array<String>) {
    val data = File(args[0])
    val outDir = File(args.getOrElse(1) { "build/eval" }).also { it.mkdirs() }
    val hfov = 70.0
    val pitchMode = args.getOrElse(2) { "sweep" } // sweep | vanishing
    val camHeight = 1.3
    val rows = data.resolve("manifest.csv").readLines().drop(1).map { it.split(",") }
    val perFrame = JSONArray()
    data class Acc(var n: Int = 0, var found: Int = 0, var inLane: Double = 0.0, var centerErr: Double = 0.0, var widths: MutableList<Double> = mutableListOf(), var ms: Double = 0.0)
    val byVideo = LinkedHashMap<String, Acc>()
    val all = Acc()

    for ((stem, video) in rows.map { it[0] to it[1] }) {
        val img = ImageIO.read(data.resolve("images/$stem.jpg"))
        val gt = ImageIO.read(data.resolve("masks/$stem.png")).raster
        val w = img.width
        val h = img.height
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            r[y * w + x] = (p shr 16) and 0xFF; g[y * w + x] = (p shr 8) and 0xFF; b[y * w + x] = p and 0xFF
        }
        val rgb = RgbImage(w, h, r, g, b)
        val t0 = System.nanoTime()
        // No motion sensor in a dataset: estimate the tilt from the image (see PitchCalibration)
        var cam = CameraModel(w, h, hfov, camHeight, 10.0)
        var lines = emptyList<LineCandidate>()
        if (pitchMode == "sweep") {
            PitchCalibration().estimate(rgb, cam)?.let { cam = cam.copy(pitchDeg = it.pitchDeg); lines = it.lines }
        } else {
            lines = LineExtractor().extract(ContrastLineDetector().detect(rgb, cam), cam)
            vanishingPitch(lines, cam)?.let { p ->
                cam = cam.copy(pitchDeg = p)
                lines = LineExtractor().extract(ContrastLineDetector().detect(rgb, cam), cam)
            }
        }
        val planner = IntelligentPathPlanner()
        var d = planner.update(FrameInput(lines, null, null, emptyList(), 1.0))
        repeat(2) { d = planner.update(FrameInput(lines, null, null, emptyList(), 1.0)) }
        val ms = (System.nanoTime() - t0) / 1e6

        var inside = 0; var total = 0; var errSum = 0.0; var errN = 0
        val path = d.path
        if (path != null) {
            for (p in path.sample(0.25, 1.5, minOf(path.farM, 10.0))) {
                val (u, v) = cam.groundToImage(p) ?: continue
                val x = u.toInt(); val y = v.toInt()
                if (y !in 0 until h) continue
                total++
                if (x in 0 until w && gt.getSample(x, y, 0) > 127) inside++
                var left = -1; var right = -1
                for (xx in 0 until w) if (gt.getSample(xx, y, 0) > 127) { if (left < 0) left = xx; right = xx }
                if (left >= 0 && right - left >= 4) {
                    errSum += abs(u - (left + right) / 2.0) / (right - left); errN++
                }
            }
        }
        val found = path != null && total > 0
        val inLane = if (total > 0) inside.toDouble() / total else 0.0
        val centerErr = if (errN > 0) errSum / errN else 0.5
        val width = if (d.mode == PathMode.LANE_CENTER) laneWidth(lines) else null
        for (acc in listOf(byVideo.getOrPut(video) { Acc() }, all)) {
            acc.n++; acc.ms += ms
            if (found) { acc.found++; acc.inLane += inLane; acc.centerErr += centerErr }
            width?.let { acc.widths += it }
        }
        perFrame.put(JSONObject().put("stem", stem).put("video", video).put("mode", d.mode.name)
            .put("in_lane", inLane).put("center_err", centerErr).put("pitch", cam.pitchDeg).put("ms", ms))
        if (!found || inLane < 0.8) overlay(img, gt, cam, lines, path, File(outDir, "fail_$stem.png"))
    }

    fun fmt(a: Acc) = "frames=%3d  path found=%5.1f%%  in-lane=%5.1f%%  center err=%4.2f lane widths  lane width=%s  %.0f ms/frame".format(
        a.n, 100.0 * a.found / a.n, if (a.found > 0) 100 * a.inLane / a.found else 0.0,
        if (a.found > 0) a.centerErr / a.found else 0.5,
        if (a.widths.isEmpty()) "  -  " else "%.2f m".format(a.widths.sorted()[a.widths.size / 2]), a.ms / a.n)
    println("per video:")
    byVideo.forEach { (v, a) -> println("  %-42s %s".format(v.take(42), fmt(a))) }
    println("ALL ${fmt(all)}")
    File(outDir, "frames.json").writeText(perFrame.toString(1))
}

/** Pitch from where the two lane boundaries (straight fit near the runner) meet = the horizon row. */
private fun vanishingPitch(lines: List<LineCandidate>, cam: CameraModel): Double? {
    val left = lines.firstOrNull { it.lateralAtRunner(1.0) < -0.2 } ?: return null
    val right = lines.firstOrNull { it.lateralAtRunner(1.0) > 0.2 } ?: return null
    fun fit(c: LineCandidate): Pair<Double, Double>? { // u = a·v + b over the near half
        val s = c.segments.take(maxOf(4, c.segments.size / 2))
        val mv = s.map { it.v }.average(); val mu = s.map { it.u }.average()
        val sxx = s.sumOf { (it.v - mv) * (it.v - mv) }
        if (sxx < 1e-6) return null
        val a = s.sumOf { (it.v - mv) * (it.u - mu) } / sxx
        return a to (mu - a * mv)
    }
    val (a1, b1) = fit(left) ?: return null
    val (a2, b2) = fit(right) ?: return null
    if (abs(a1 - a2) < 1e-3) return null
    val vh = (b2 - b1) / (a1 - a2)
    val pitch = atan(((cam.height - 1) / 2.0 - vh) / cam.focalPx).toDegrees()
    return pitch.takeIf { it in -20.0..45.0 }
}

private fun laneWidth(lines: List<LineCandidate>): Double? {
    val l = lines.filter { it.lateralAtRunner(1.0) < -0.25 }.maxByOrNull { it.score } ?: return null
    val r = lines.filter { it.lateralAtRunner(1.0) > 0.25 }.maxByOrNull { it.score } ?: return null
    return r.curve.lateralAt(3.0) - l.curve.lateralAt(3.0)
}

private fun overlay(img: BufferedImage, gt: java.awt.image.WritableRaster, cam: CameraModel, lines: List<LineCandidate>,
                    path: run.basir.engine.PathCurve?, file: File) {
    val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.drawImage(img, 0, 0, null)
    g.color = Color(0, 255, 255, 70)
    for (y in 0 until img.height) for (x in 0 until img.width) if (gt.getSample(x, y, 0) > 127) g.fillRect(x, y, 1, 1)
    g.stroke = BasicStroke(2f)
    g.color = Color(60, 170, 255)
    for (l in lines.take(6)) for (s in l.segments) g.drawOval(s.u.toInt() - 2, s.v.toInt() - 2, 4, 4)
    if (path != null) {
        g.color = Color.GREEN
        g.stroke = BasicStroke(3f)
        val pts = path.sample(0.1, 0.5, minOf(path.farM, 12.0)).mapNotNull { cam.groundToImage(it) }
        for (i in 1 until pts.size) g.drawLine(pts[i - 1].first.toInt(), pts[i - 1].second.toInt(), pts[i].first.toInt(), pts[i].second.toInt())
    }
    g.dispose()
    ImageIO.write(out, "png", file)
}
