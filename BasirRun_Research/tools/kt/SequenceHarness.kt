// Frame-by-frame run of the engine chain on a clip's walkable masks, as LineEngine does it in the app:
// WalkableMemory -> GroundGrid.build -> FreeSpacePlanner (+ side routes when the way ahead is short)
// -> IntelligentPathPlanner -> GuidanceController -> HapticScheduler (what the runner feels).
// Usage: SequenceHarness <jobs.csv> <out.csv> [speedMps] ; jobs: mask_png,hfov,height,pitch (frame order)
import run.basir.engine.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2

fun main(a: Array<String>) {
    val speed = a.getOrNull(2)?.toDouble() ?: 2.5
    val cfg = FreeSpaceConfig()
    val free = FreeSpacePlanner(cfg)
    val planner = IntelligentPathPlanner()
    val guidance = GuidanceController()
    val memory = WalkableMemory()
    var goal = 0.0
    val felt = ArrayList<String>()
    val motor = object : HapticOutput {
        override val hasAmplitudeControl = true
        override fun play(pattern: HapticPattern) { felt += pattern.name }
        override fun cancel() {}
    }
    val haptics = HapticScheduler(motor)
    val out = StringBuilder("frame,route_steer,progress,mode,path_bearing3,cmd,side,level,steer,felt,points,ribbon\n")
    File(a[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
        val (path, hfov, height, pitch) = line.split(",")
        val src = ImageIO.read(File(path)); val w = 160; val h = src.height * w / src.width
        val raw = ProbabilityMask(w, h)
        for (y in 0 until h) for (x in 0 until w) raw[x, y] = if ((src.getRGB((x + 0.5).toInt() * src.width / w, (y + 0.5).toInt() * src.height / h) and 0xFF) > 127) 1f else 0f
        val mask = memory.update(raw)
        val cam = CameraModel(w, h, hfov.toDouble(), height.toDouble(), pitch.toDouble())
        val grid = GroundGrid.build(mask, cam, emptyList(), cfg)
        val route = free.plan(grid, goal.coerceIn(-30.0, 30.0))
        goal = route?.let { 0.7 * it.steerDeg } ?: 0.0
        val sides = if (route == null || route.progressM < 6.0) listOfNotNull(free.plan(grid, -60.0), free.plan(grid, 60.0)) else emptyList()
        val d = planner.update(FrameInput(emptyList(), route, grid, emptyList(), 1.0, speed, 1.0 / 15, sides))
        val c = guidance.update(d, speed)
        felt.clear()
        haptics.onCommand(c, (i * 1000L) / 15)
        fun bearing(p: PathCurve?) = p?.let { Math.toDegrees(atan2(it.lateralAt(3.0), 3.0)) }
        // The path as drawn: only as far as the walkable ground goes, from the first visible metre
        val far = minOf(d.path?.farM ?: 0.0, route?.progressM ?: 0.0)
        val samples = d.path?.takeIf { far > 1.0 }?.sample(0.25, 1.0, far).orEmpty()
        val pts = samples.mapNotNull { cam.groundToImage(it) }.joinToString(" ") { (u, v) -> "%.3f:%.3f".format(u / w, v / h) }
        // A 0.6 m wide strip on the ground: left and right edges, with the distance of each point
        val ribbon = samples.mapNotNull { p ->
            val l = cam.groundToImage(GroundPoint(p.forward, p.lateral - 0.3)); val r = cam.groundToImage(GroundPoint(p.forward, p.lateral + 0.3))
            if (l == null || r == null) null else "%.3f:%.3f:%.3f:%.3f:%.2f".format(l.first / w, l.second / h, r.first / w, r.second / h, p.forward)
        }.joinToString(" ")
        out.append("$i,${route?.steerDeg ?: ""},${route?.progressM ?: ""},${d.mode},${bearing(d.path) ?: ""},${c.kind},${c.side ?: ""},${c.level ?: ""},${c.steerDeg},${felt.joinToString("+")},$pts,$ribbon\n")
    }
    File(a[1]).writeText(out.toString())
}
