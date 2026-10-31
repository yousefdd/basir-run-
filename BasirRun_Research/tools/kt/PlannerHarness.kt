// End-to-end virtual path from a walkable mask: GroundGrid.build -> FreeSpacePlanner (the engine's
// own code) -> route waypoints projected back to image pixels.
// Usage: PlannerHarness <jobs.csv> <out.csv>
// jobs.csv: mask_png,hfov_deg,height_m,pitch_deg  (one frame per line, no header)
import run.basir.engine.*
import java.io.File
import javax.imageio.ImageIO

fun main(a: Array<String>) {
    val out = StringBuilder("mask,status,progress_m,min_clearance_m,steer_deg,ms,points\n")
    val cfg = FreeSpaceConfig()
    for (line in File(a[0]).readLines().filter { it.isNotBlank() }) {
        val (path, hfov, height, pitch) = line.split(",")
        val src = ImageIO.read(File(path))
        val w = 160; val h = src.height * w / src.width
        val mask = ProbabilityMask(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = (x + 0.5) * src.width / w; val sy = (y + 0.5) * src.height / h
            mask[x, y] = if ((src.getRGB(sx.toInt(), sy.toInt()) and 0xFF) > 127) 1f else 0f
        }
        val cam = CameraModel(w, h, hfov.toDouble(), height.toDouble(), pitch.toDouble())
        val t0 = System.nanoTime()
        val grid = GroundGrid.build(mask, cam, emptyList(), cfg)
        val route = FreeSpacePlanner(cfg).plan(grid)
        val ms = (System.nanoTime() - t0) / 1e6
        if (route == null) { out.append("$path,NO_ROUTE,,,,${"%.1f".format(ms)},\n"); continue }
        val pts = route.waypoints.mapNotNull { p -> cam.groundToImage(p)?.let { (u, v) -> "%.3f:%.3f:%.2f".format(u / w, v / h, p.forward) } }
        out.append("$path,ROUTE,${"%.2f".format(route.progressM)},${"%.2f".format(route.minClearanceM)},${"%.1f".format(route.steerDeg)},${"%.1f".format(ms)},${pts.joinToString(" ")}\n")
    }
    File(a[1]).writeText(out.toString())
}
