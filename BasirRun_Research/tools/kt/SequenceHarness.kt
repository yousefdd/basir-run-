// Frame-by-frame run of the engine chain on a clip's walkable masks:
// GroundGrid.build -> FreeSpacePlanner -> IntelligentPathPlanner -> GuidanceController.
// Usage: SequenceHarness <jobs.csv> <out.csv> ; jobs: mask_png,hfov,height,pitch (in frame order)
import run.basir.engine.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2

fun main(a: Array<String>) {
    val cfg = FreeSpaceConfig()
    val free = FreeSpacePlanner(cfg)
    val planner = IntelligentPathPlanner()
    val guidance = GuidanceController()
    var goal = 0.0
    val out = StringBuilder("frame,route_steer,route_bearing3,mode,path_bearing3,cmd,side,level,steer,points\n")
    File(a[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
        val (path, hfov, height, pitch) = line.split(",")
        val src = ImageIO.read(File(path)); val w = 160; val h = src.height * w / src.width
        val mask = ProbabilityMask(w, h)
        for (y in 0 until h) for (x in 0 until w) mask[x, y] = if ((src.getRGB((x + 0.5).toInt() * src.width / w, (y + 0.5).toInt() * src.height / h) and 0xFF) > 127) 1f else 0f
        val cam = CameraModel(w, h, hfov.toDouble(), height.toDouble(), pitch.toDouble())
        val grid = GroundGrid.build(mask, cam, emptyList(), cfg)
        val route = free.plan(grid, goal.coerceIn(-30.0, 30.0))
        goal = route?.let { 0.7 * it.steerDeg } ?: 0.0
        val d = planner.update(FrameInput(emptyList(), route, grid, emptyList(), 1.0, dt = 1.0 / 15))
        val c = guidance.update(d)
        fun bearing(p: PathCurve?) = p?.let { Math.toDegrees(atan2(it.lateralAt(3.0), 3.0)) }
        val pts = d.path?.sample(0.25, maxOf(d.path.nearM, 0.8), d.path.farM)?.mapNotNull { cam.groundToImage(it) }?.joinToString(" ") { (u, v) -> "%.3f:%.3f".format(u / w, v / h) } ?: ""
        out.append("$i,${route?.steerDeg ?: ""},${bearing(route?.toCurve()) ?: ""},${d.mode},${bearing(d.path) ?: ""},${c.kind},${c.side ?: ""},${c.level ?: ""},${c.steerDeg},$pts\n")
    }
    File(a[1]).writeText(out.toString())
}
