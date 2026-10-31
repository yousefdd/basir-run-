// Per-frame layers for the visual test tool. Same chain as the app's LineEngine:
// detections -> ground -> ObstacleTracker -> footprints in the GroundGrid -> FreeSpacePlanner
// (+ side routes) -> [CorridorCenterline] -> IntelligentPathPlanner -> StableGuidanceController.
// Usage: VisualHarness <jobs.csv> <out.csv> <speedMps> <smoothing> <centreline 0|1>
// jobs: mask_png,hfov,height,pitch,boxes   boxes = "x1:y1:x2:y2:score:label;..." (fractions of the frame)
import run.basir.engine.*
import java.io.File
import javax.imageio.ImageIO

fun main(a: Array<String>) {
    val speed = a[2].toDouble(); val smoothing = Smoothing.valueOf(a[3]); val useCl = a[4] == "1"
    val cfg = FreeSpaceConfig(); val free = FreeSpacePlanner(cfg); val planner = IntelligentPathPlanner(); val memory = WalkableMemory()
    val tracker = ObstacleTracker(); val guide = StableGuidanceController(StableGuidanceConfig(smoothing = smoothing))
    var goal = 0.0
    val feltNow = ArrayList<String>()
    val hap = HapticScheduler(object : HapticOutput { override val hasAmplitudeControl = true; override fun play(pattern: HapticPattern) { feltNow += pattern.name }; override fun cancel() {} }, config = SchedulerConfig(onPathOnlyOnConfirm = true))
    val out = StringBuilder("frame,mode,conf,state,cmd,side,level,offset,heading,curv,ms_grid,ms_plan,ms_guide,ribbon,centre,extension,obstacles,ahead_m,blocking,progress,felt,bearing3\n")
    fun pts(cam: CameraModel, list: List<GroundPoint>) = list.mapNotNull { cam.groundToImage(it) }.joinToString(" ") { (u, v) -> "%.4f:%.4f".format(u / cam.width, v / cam.height) }
    File(a[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
        val f = line.split(",")
        val src = ImageIO.read(File(f[0])); val w = 160; val h = src.height * w / src.width
        val raw = ProbabilityMask(w, h)
        for (y in 0 until h) for (x in 0 until w) raw[x, y] = if ((src.getRGB((x + 0.5).toInt() * src.width / w, (y + 0.5).toInt() * src.height / h) and 0xFF) > 127) 1f else 0f
        val mask = memory.update(raw)
        val cam = CameraModel(w, h, f[1].toDouble(), f[2].toDouble(), f[3].toDouble())
        val dets = f.getOrElse(4) { "" }.split(";").filter { it.isNotBlank() }.mapNotNull { b ->
            val (x1, y1, x2, y2, sc) = b.split(":").take(5).map { it.toDouble() }; val label = b.split(":")[5]
            if (!DetectionFilter.keep(label, x1, y1, x2, y2)) return@mapNotNull null
            val v = (y2 * h).coerceAtMost(h - 1.0); val u = (x1 + x2) / 2 * w
            val p = cam.imageToGround(u, v) ?: return@mapNotNull null
            val mpp = cam.lateralMetersPerPixel(v) ?: return@mapNotNull null
            ObstacleDetection(p, ((x2 - x1) * w * mpp / 2).coerceIn(0.15, 1.5), sc, label)
        }
        val tracked = tracker.update(dets, 1.0 / 15, runnerAdvanceM = speed / 15)
        val foot = ArrayList<GroundPoint>()
        for (o in tracked.filterNot { Receding.isReceding(it, speed) }) { var df = -o.radiusM; while (df <= o.radiusM) { var dl = -o.radiusM; while (dl <= o.radiusM) {
            if (df * df + dl * dl <= o.radiusM * o.radiusM) { val p = GroundPoint(o.position.forward + df, o.position.lateral + dl); foot += p; foot += p }
            dl += cfg.cellM }; df += cfg.cellM } }
        val t0 = System.nanoTime()
        val grid = GroundGrid.build(mask, cam, foot, cfg)
        val t1 = System.nanoTime()
        val route = free.plan(grid, goal.coerceIn(-30.0, 30.0)); goal = route?.let { 0.7 * it.steerDeg } ?: 0.0
        val sides = if (route == null || route.progressM < 6.0) listOfNotNull(free.plan(grid, -60.0), free.plan(grid, 60.0)) else emptyList()
        val cl = if (useCl) route?.let { CorridorCenterline.extract(grid, it, fovTanHalf = Math.tan(Math.toRadians(f[1].toDouble() / 2))) } else null
        val d = planner.update(FrameInput(emptyList(), route, grid, tracked, 1.0, speed, 1.0 / 15, sides, freeCenterline = cl))
        val t2 = System.nanoTime()
        val e = guide.update(d, speed, 1.0 / 15)
        feltNow.clear(); hap.onCommand(e.command, i * 1000L / 15)
        val t3 = System.nanoTime()
        val p = d.path
        val far = minOf(p?.farM ?: 0.0, route?.progressM ?: 0.0)
        val samples = p?.takeIf { far > 1.0 }?.sample(0.25, 1.0, far).orEmpty()
        val ribbon = samples.mapNotNull { s ->
            val l = cam.groundToImage(GroundPoint(s.forward, s.lateral - 0.3)); val r = cam.groundToImage(GroundPoint(s.forward, s.lateral + 0.3))
            if (l == null || r == null) null else "%.4f:%.4f:%.4f:%.4f".format(l.first / w, l.second / h, r.first / w, r.second / h)
        }.joinToString(" ")
        val centre = cl?.curve?.let { c -> pts(cam, c.sample(0.25, c.nearM, c.farM)) } ?: ""
        // Expected continuation: the path's own curve carried 3 m past what was seen (drawn dashed, not guidance)
        val ext = if (p != null && far > 1.0) pts(cam, (0..12).map { k -> val ff = far + k * 0.25; GroundPoint(ff, p.lateralAt(ff)) }) else ""
        val obs = tracked.joinToString(";") { o -> "${o.label}:${"%.2f".format(o.position.forward)}:${"%.2f".format(o.position.lateral)}:${o.confirmed}" }
        out.append("$i,${d.mode},${"%.2f".format(d.confidence)},${e.state},${e.command.kind},${e.command.side ?: ""},${e.command.level ?: ""},${"%.3f".format(e.offsetM)},${"%.2f".format(e.headingDeg)},${"%.4f".format(e.curvature)},${"%.2f".format((t1 - t0) / 1e6)},${"%.2f".format((t2 - t1) / 1e6)},${"%.3f".format((t3 - t2) / 1e6)},$ribbon,$centre,$ext,$obs,${d.obstacleAheadM?.let { "%.2f".format(it) } ?: ""},${d.blocking?.label ?: (if (d.obstacleAheadM != null) "path_end" else "")},${route?.progressM?.let { "%.2f".format(it) } ?: ""},${feltNow.joinToString("+")},${p?.let { "%.2f".format(Math.toDegrees(kotlin.math.atan2(it.lateralAt(3.0), 3.0))) } ?: ""}\n")
    }
    File(a[1]).writeText(out.toString())
}
