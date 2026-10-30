package run.basir.devtools

import org.json.JSONObject
import run.basir.engine.CameraModel
import run.basir.engine.CommandKind
import run.basir.engine.ContrastLineDetector
import run.basir.engine.FrameInput
import run.basir.engine.GuidanceController
import run.basir.engine.HapticOutput
import run.basir.engine.HapticPattern
import run.basir.engine.HapticScheduler
import run.basir.engine.IntelligentPathPlanner
import run.basir.engine.LineExtractor
import run.basir.engine.PitchCalibration
import run.basir.engine.RgbImage
import run.basir.engine.Side
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Replays a recorded sequence through the whole engine — line detection → planner → guidance
 * → haptic scheduler — and writes developer-mode frames (detected lines, virtual path, upcoming
 * bend, confidence, processing time, the haptic pattern chosen) plus a stability report.
 *
 *   gradle :devtools:run -PmainClass=run.basir.devtools.ReplaySequenceKt \
 *       --args="data_dir video_prefix out_dir [frame_interval_ms]"
 * Frames are taken in name order from data_dir/images whose name starts with video_prefix.
 */
fun main(args: Array<String>) {
    val data = File(args[0])
    val prefix = args[1]
    val outDir = File(args[2]).also { it.mkdirs() }
    val frameMs = args.getOrElse(3) { "500" }.toLong()
    val files = data.resolve("images").listFiles()!!.filter { it.name.startsWith(prefix) }.sortedBy { it.name }
    require(files.isNotEmpty()) { "no frames for $prefix" }

    class Log : HapticOutput {
        override val hasAmplitudeControl = true
        var last: HapticPattern? = null
        override fun play(pattern: HapticPattern) { last = pattern }
        override fun cancel() {}
    }
    val haptic = Log()
    val scheduler = HapticScheduler(haptic)
    val planner = IntelligentPathPlanner()
    val guidance = GuidanceController()
    val detector = ContrastLineDetector()
    val extractor = LineExtractor()
    var cam: CameraModel? = null
    val events = ArrayList<Triple<Long, String, String>>()
    val stopReasons = HashMap<String, Int>()
    val modes = HashMap<String, Int>()
    val times = ArrayList<Double>()
    var lastSide: Side? = null
    var sideFlips = 0
    var contradictions = 0
    var lastDirectionalAt = Long.MIN_VALUE / 2
    val steer = ArrayList<Double>()

    files.forEachIndexed { idx, f ->
        val img = ImageIO.read(f)
        val rgb = toRgb(img)
        val now = idx * frameMs
        val t0 = System.nanoTime()
        // The phone's motion sensor gives the tilt every frame; a dataset has none, so stand in
        // for it: full sweep on the first frame, then a narrow search around the last value
        val prev = cam
        val base = prev ?: CameraModel(img.width, img.height, 70.0, 1.3, 10.0)
        val est = if (prev == null) PitchCalibration().estimate(rgb, base)
                  else PitchCalibration().estimate(rgb, base, prev.pitchDeg - 6.0, prev.pitchDeg + 6.0, 3.0)
        cam = base.copy(pitchDeg = est?.pitchDeg ?: base.pitchDeg)
        val c = cam!!
        val lines = est?.lines ?: extractor.extract(detector.detect(rgb, c), c)
        val decision = planner.update(FrameInput(lines, null, null, emptyList(), 1.0, dt = frameMs / 1000.0))
        val cmd = guidance.update(decision)
        haptic.last = null
        scheduler.onCommand(cmd, now)
        val ms = (System.nanoTime() - t0) / 1e6
        times += ms
        modes.merge(decision.mode.name, 1, Int::plus)
        if (decision.mode.name == "STOP") stopReasons.merge(decision.stopReason.name, 1, Int::plus)
        if (decision.path != null) steer += cmd.steerDeg
        haptic.last?.let { p ->
            events += Triple(now, p.name, cmd.kind.name)
            val side = when {
                p.name.contains("_left") -> Side.LEFT
                p.name.contains("_right") -> Side.RIGHT
                else -> null
            }
            if (side != null) {
                if (lastSide != null && side != lastSide) {
                    sideFlips++
                    if (now - lastDirectionalAt < 1000 && !p.name.endsWith("_high")) contradictions++
                }
                lastSide = side
                lastDirectionalAt = now
            }
        }
        drawFrame(img, c, lines, decision, cmd, haptic.last, ms, File(outDir, "frame_%04d.png".format(idx)))
    }

    val sorted = times.sorted()
    val jitter = steer.zipWithNext { a, b -> kotlin.math.abs(b - a) }.let { if (it.isEmpty()) 0.0 else it.average() }
    val report = JSONObject()
        .put("frames", files.size)
        .put("pitch_deg", cam!!.pitchDeg)
        .put("modes", JSONObject(modes as Map<*, *>))
        .put("stop_reasons", JSONObject(stopReasons as Map<*, *>))
        .put("haptic_events", events.size)
        .put("side_flips", sideFlips)
        .put("contradictions_within_1s", contradictions)
        .put("steer_jitter_deg_per_frame", jitter)
        .put("ms_p50", sorted[sorted.size / 2]).put("ms_p95", sorted[(sorted.size * 95) / 100])
        .put("events", events.map { "${it.first}ms ${it.second} (${it.third})" })
    File(outDir, "report.json").writeText(report.toString(1))
    println(report.toString(1).lines().take(20).joinToString("\n"))
}

private fun toRgb(img: BufferedImage): RgbImage {
    val w = img.width
    val h = img.height
    val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val p = img.getRGB(x, y)
        r[y * w + x] = (p shr 16) and 0xFF; g[y * w + x] = (p shr 8) and 0xFF; b[y * w + x] = p and 0xFF
    }
    return RgbImage(w, h, r, g, b)
}

private fun drawFrame(
    img: BufferedImage, cam: CameraModel, lines: List<run.basir.engine.LineCandidate>,
    d: run.basir.engine.PathDecision, cmd: run.basir.engine.GuidanceCommand, fired: HapticPattern?, ms: Double, file: File,
) {
    val out = BufferedImage(img.width, img.height + 70, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.drawImage(img, 0, 0, null)
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    fun poly(pts: List<run.basir.engine.GroundPoint>) = pts.mapNotNull { cam.groundToImage(it) }
        .filter { it.second in -50.0..(img.height + 50.0) }
    g.stroke = BasicStroke(3f)
    g.color = Color(60, 170, 255)
    for (l in lines.take(6)) poly(l.curve.sample(0.2)).zipWithNext { a, b -> g.drawLine(a.first.toInt(), a.second.toInt(), b.first.toInt(), b.second.toInt()) }
    d.path?.let { p ->
        g.stroke = BasicStroke(5f)
        g.color = Color(60, 230, 110)
        poly(p.sample(0.1, 0.5, minOf(p.farM, 15.0))).zipWithNext { a, b -> g.drawLine(a.first.toInt(), a.second.toInt(), b.first.toInt(), b.second.toInt()) }
        cmd.curveInM?.let { at ->
            g.color = Color(255, 80, 220)
            poly(p.sample(0.1, at, minOf(at + 2.0, p.farM))).zipWithNext { a, b -> g.drawLine(a.first.toInt(), a.second.toInt(), b.first.toInt(), b.second.toInt()) }
        }
    }
    g.color = Color(20, 28, 38)
    g.fillRect(0, img.height, img.width, 70)
    g.font = Font(Font.MONOSPACED, Font.BOLD, 15)
    g.color = if (d.mode.name == "STOP") Color(255, 80, 80) else Color(90, 220, 140)
    g.drawString("MODE ${d.mode}  conf ${"%.2f".format(d.confidence)}  steer ${"%+.1f".format(cmd.steerDeg)}°  ${"%.0f".format(ms)} ms", 10, img.height + 22)
    g.color = Color(230, 235, 245)
    val curve = cmd.curveInM?.let { "  bend ${if (cmd.curveCurvature > 0) "R" else "L"} in ${"%.1f".format(it)} m (r=${"%.0f".format(1 / kotlin.math.abs(cmd.curveCurvature))} m)" } ?: ""
    g.drawString("CMD ${cmd.kind}${cmd.side?.let { " $it" } ?: ""}${cmd.level?.let { " $it" } ?: ""}$curve", 10, img.height + 42)
    g.color = Color(255, 200, 80)
    g.drawString("HAPTIC ${fired?.name ?: "-"}", 10, img.height + 62)
    g.dispose()
    ImageIO.write(out, "png", file)
}
