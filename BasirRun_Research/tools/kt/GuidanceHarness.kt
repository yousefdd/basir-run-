// Old (pure pursuit GuidanceController) vs new (StableGuidanceController) on the same engine chain and
// the same clip: WalkableMemory -> GroundGrid -> FreeSpacePlanner (+ side routes) -> IntelligentPathPlanner
// -> controller -> HapticScheduler (what the runner would feel). One planner run per frame feeds every
// controller, so differences come from the controllers only.
// Usage: GuidanceHarness <jobs.csv> <out.csv> <speedMps> ; jobs: mask_png,hfov,height,pitch
import run.basir.engine.*
import java.io.File
import javax.imageio.ImageIO

class Felt : HapticOutput {
    val now = ArrayList<String>()
    override val hasAmplitudeControl = true
    override fun play(pattern: HapticPattern) { now += pattern.name }
    override fun cancel() {}
}

fun main(a: Array<String>) {
    val speed = a[2].toDouble()
    val cfg = FreeSpaceConfig(); val free = FreeSpacePlanner(cfg); val planner = IntelligentPathPlanner(); val memory = WalkableMemory()
    var goal = 0.0
    val share = a.getOrNull(3)?.toDouble() ?: 0.5; val maxHalf = a.getOrNull(4)?.toDouble() ?: Double.MAX_VALUE; val curveConfirm = a.getOrNull(5)?.toInt() ?: 3
    val plannerB = IntelligentPathPlanner(); val stableB = StableGuidanceController(StableGuidanceConfig(curveConfirmFrames = curveConfirm)); val outB = Felt(); val hapB = HapticScheduler(outB, config = SchedulerConfig(onPathOnlyOnConfirm = true))
    val old = GuidanceController(); val oldOut = Felt(); val oldHap = HapticScheduler(oldOut)
    val variants = Smoothing.values().map { s ->
        Triple(s, StableGuidanceController(StableGuidanceConfig(smoothing = s)), Felt().let { f -> f to HapticScheduler(f, config = SchedulerConfig(onPathOnlyOnConfirm = true)) })
    }
    val head = StringBuilder("frame,mode,conf,offset_raw,heading_raw,curv_raw,old_cmd,old_side,old_felt")
    variants.forEach { (s, _, _) -> head.append(",${s}_state,${s}_cmd,${s}_side,${s}_felt,${s}_offset,${s}_heading,${s}_curv") }
    head.append(",cl_found,cl_mode,cl_curv_raw,cl_heading_raw,cl_state,cl_cmd,cl_side,cl_felt")
    val out = StringBuilder(head).append("\n")
    File(a[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
        val (path, hfov, height, pitch) = line.split(",")
        val src = ImageIO.read(File(path)); val w = 160; val h = src.height * w / src.width
        val raw = ProbabilityMask(w, h)
        for (y in 0 until h) for (x in 0 until w) raw[x, y] = if ((src.getRGB((x + 0.5).toInt() * src.width / w, (y + 0.5).toInt() * src.height / h) and 0xFF) > 127) 1f else 0f
        val mask = memory.update(raw)
        val cam = CameraModel(w, h, hfov.toDouble(), height.toDouble(), pitch.toDouble())
        val grid = GroundGrid.build(mask, cam, emptyList(), cfg)
        val route = free.plan(grid, goal.coerceIn(-30.0, 30.0)); goal = route?.let { 0.7 * it.steerDeg } ?: 0.0
        val sides = if (route == null || route.progressM < 6.0) listOfNotNull(free.plan(grid, -60.0), free.plan(grid, 60.0)) else emptyList()
        val d = planner.update(FrameInput(emptyList(), route, grid, emptyList(), 1.0, speed, 1.0 / 15, sides))
        val t = (i * 1000L) / 15
        val p = d.path
        val row = StringBuilder("$i,${d.mode},${"%.3f".format(d.confidence)},${p?.let { "%.4f".format(it.lateralAt(0.0)) } ?: ""},${p?.let { "%.3f".format(it.headingDegAt(0.0)) } ?: ""},${p?.let { "%.5f".format(it.curvatureAt(minOf(speed * 2.5, it.farM) * 0.5)) } ?: ""}")
        val oc = old.update(d, speed); oldOut.now.clear(); oldHap.onCommand(oc, t)
        row.append(",${oc.kind},${oc.side ?: ""},${oldOut.now.joinToString("+")}")
        variants.forEach { (_, c, fh) ->
            val e = c.update(d, speed, 1.0 / 15); fh.first.now.clear(); fh.second.onCommand(e.command, t)
            row.append(",${e.state},${e.command.kind},${e.command.side ?: ""},${fh.first.now.joinToString("+")},${"%.4f".format(e.offsetM)},${"%.3f".format(e.headingDeg)},${"%.5f".format(e.curvature)}")
        }
        val cl = route?.let { CorridorCenterline.extract(grid, it, minBoundedShare = share, maxHalfWidthM = maxHalf, fovTanHalf = Math.tan(Math.toRadians(hfov.toDouble() / 2))) }
        val dB = plannerB.update(FrameInput(emptyList(), route, grid, emptyList(), 1.0, speed, 1.0 / 15, sides, freeCenterline = cl))
        val eB = stableB.update(dB, speed, 1.0 / 15); outB.now.clear(); hapB.onCommand(eB.command, t)
        val pB = dB.path
        row.append(",${cl != null},${dB.mode},${pB?.let { "%.5f".format(it.curvatureAt(minOf(speed * 2.5, it.farM) * 0.5)) } ?: ""},${pB?.let { "%.3f".format(it.headingDegAt(0.0)) } ?: ""},${eB.state},${eB.command.kind},${eB.command.side ?: ""},${outB.now.joinToString("+")}")
        out.append(row).append("\n")
    }
    File(a[1]).writeText(out.toString())
}
