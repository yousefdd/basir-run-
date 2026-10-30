package run.basir.devtools

import org.json.JSONArray
import org.json.JSONObject
import run.basir.engine.CameraModel
import run.basir.engine.ContrastLineDetector
import run.basir.engine.FrameInput
import run.basir.engine.IntelligentPathPlanner
import run.basir.engine.LineExtractor
import run.basir.engine.RgbImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Runs line detection + the path planner on one image and prints JSON: candidate lines and the
 * chosen virtual path, in image pixels and ground meters.
 *
 *   gradle :devtools:run --args="photo.jpg --hfov 60 --height 1.3 --pitch 5 --frames 3"
 *
 * A still photo is fed [--frames] times so the line tracker can confirm the track as it would
 * after a few frames of steady video (default 3 = the tracker's confirmation count).
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("usage: AnalyzeImage <image> [--hfov deg] [--height m] [--pitch deg]")
    fun opt(name: String, default: Double) =
        args.indexOf(name).takeIf { it >= 0 }?.let { args[it + 1].toDouble() } ?: default
    val img = ImageIO.read(File(path))
    val w = img.width
    val h = img.height
    val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val p = img.getRGB(x, y)
        r[y * w + x] = (p shr 16) and 0xFF; g[y * w + x] = (p shr 8) and 0xFF; b[y * w + x] = p and 0xFF
    }
    val cam = CameraModel(w, h, opt("--hfov", 65.0), opt("--height", 1.2), opt("--pitch", 10.0))
    val t0 = System.nanoTime()
    val mask = ContrastLineDetector().detect(RgbImage(w, h, r, g, b), cam)
    val lines = LineExtractor().extract(mask, cam)
    val planner = IntelligentPathPlanner()
    var decision = planner.update(FrameInput(lines, null, null, emptyList(), visibility = 1.0))
    repeat(opt("--frames", 3.0).toInt() - 1) {
        decision = planner.update(FrameInput(lines, null, null, emptyList(), visibility = 1.0))
    }
    val ms = (System.nanoTime() - t0) / 1e6

    fun toImage(points: List<run.basir.engine.GroundPoint>) = JSONArray().apply {
        points.forEach { p -> cam.groundToImage(p)?.let { (u, v) -> put(JSONArray(listOf(u, v, p.forward, p.lateral))) } }
    }
    val out = JSONObject()
    out.put("mode", decision.mode.name)
    out.put("confidence", decision.confidence)
    out.put("stop_reason", decision.stopReason.name)
    out.put("ms", ms)
    out.put("lines", JSONArray().apply {
        lines.forEach { l ->
            put(JSONObject().put("score", l.score).put("coverage_m", l.coverageM)
                .put("lateral_at_runner", l.lateralAtRunner(1.0)).put("points", toImage(l.curve.sample(0.2))))
        }
    })
    decision.path?.let { c ->
        out.put("path", toImage(c.sample(0.1, 0.0, c.farM)))
        out.put("heading_near_deg", c.headingDegAt(1.0))
        out.put("heading_far_deg", c.headingDegAt(c.farM))
    }
    println(out.toString())
}
