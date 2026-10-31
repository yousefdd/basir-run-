// The engine chain as of 0.10.0 (commit 1dab1c4), as its LineEngine ran it: detections -> ground ->
// ObstacleTracker (no runner advance, no detection filter) -> footprints -> FreeSpacePlanner (+ side routes)
// -> IntelligentPathPlanner -> GuidanceController (pure pursuit) -> HapticScheduler (default config).
// Same jobs and output columns as VisualHarness for the ones the scenario suite reads.
// Usage: OldHarness <jobs.csv> <out.csv> <speedMps>
import run.basir.engine.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2

fun main(a: Array<String>) {
    val speed = a[2].toDouble()
    val cfg = FreeSpaceConfig(); val free = FreeSpacePlanner(cfg); val planner = IntelligentPathPlanner(); val memory = WalkableMemory()
    val tracker = ObstacleTracker(); val guide = GuidanceController()
    val feltNow = ArrayList<String>()
    val hap = HapticScheduler(object : HapticOutput { override val hasAmplitudeControl = true; override fun play(pattern: HapticPattern) { feltNow += pattern.name }; override fun cancel() {} })
    var goal = 0.0
    val out = StringBuilder("frame,mode,state,cmd,side,level,bearing3,felt\n")
    File(a[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
        val f = line.split(",")
        val src = ImageIO.read(File(f[0])); val w = 160; val h = src.height * w / src.width
        val raw = ProbabilityMask(w, h)
        for (y in 0 until h) for (x in 0 until w) raw[x, y] = if ((src.getRGB((x + 0.5).toInt() * src.width / w, (y + 0.5).toInt() * src.height / h) and 0xFF) > 127) 1f else 0f
        val mask = memory.update(raw)
        val cam = CameraModel(w, h, f[1].toDouble(), f[2].toDouble(), f[3].toDouble())
        val dets = f.getOrElse(4) { "" }.split(";").filter { it.isNotBlank() }.mapNotNull { b ->
            val (x1, y1, x2, y2, sc) = b.split(":").take(5).map { it.toDouble() }; val label = b.split(":")[5]
            val v = (y2 * h).coerceAtMost(h - 1.0); val u = (x1 + x2) / 2 * w
            val p = cam.imageToGround(u, v) ?: return@mapNotNull null
            val mpp = cam.lateralMetersPerPixel(v) ?: return@mapNotNull null
            ObstacleDetection(p, ((x2 - x1) * w * mpp / 2).coerceIn(0.15, 1.5), sc, label)
        }
        val tracked = tracker.update(dets, 1.0 / 15)
        val foot = ArrayList<GroundPoint>()
        for (o in tracked) { var df = -o.radiusM; while (df <= o.radiusM) { var dl = -o.radiusM; while (dl <= o.radiusM) {
            if (df * df + dl * dl <= o.radiusM * o.radiusM) { val p = GroundPoint(o.position.forward + df, o.position.lateral + dl); foot += p; foot += p }
            dl += cfg.cellM }; df += cfg.cellM } }
        val grid = GroundGrid.build(mask, cam, foot, cfg)
        val route = free.plan(grid, goal.coerceIn(-30.0, 30.0)); goal = route?.let { 0.7 * it.steerDeg } ?: 0.0
        val sides = if (route == null || route.progressM < 6.0) listOfNotNull(free.plan(grid, -60.0), free.plan(grid, 60.0)) else emptyList()
        val d = planner.update(FrameInput(emptyList(), route, grid, tracked, 1.0, speed, 1.0 / 15, sides))
        val c = guide.update(d, speed)
        feltNow.clear(); hap.onCommand(c, i * 1000L / 15)
        val b3 = d.path?.let { "%.2f".format(Math.toDegrees(atan2(it.lateralAt(3.0), 3.0))) } ?: ""
        out.append("$i,${d.mode},${c.kind},${c.kind},${c.side ?: ""},${c.level ?: ""},$b3,${feltNow.joinToString("+")}\n")
    }
    File(a[1]).writeText(out.toString())
}
