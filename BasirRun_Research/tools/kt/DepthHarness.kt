// Runs the app's own depth chain (FloorPlanner.analyze + DepthHazards.assess with the RUNNING config,
// heading 0) on disparity maps saved by bench_depth_prep.py. Usage: DepthHarness <jobs.csv> <out.csv>
import com.basir.run.vision.depth.DepthHazards
import com.basir.run.vision.depth.FloorPlanner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(a: Array<String>) {
    val lines = File(a[0]).readLines(); val head = lines[0].split(",")
    val cfg = DepthHazards.RUNNING.copy(
        bodyWidthM = a.getOrNull(2)?.toDouble() ?: DepthHazards.RUNNING.bodyWidthM,
        closerRatio = a.getOrNull(3)?.toDouble() ?: DepthHazards.RUNNING.closerRatio,
        minPixels = a.getOrNull(4)?.toInt() ?: DepthHazards.RUNNING.minPixels)
    val out = StringBuilder("session,frame,floor_valid,level,kind,clear_m,nearest_m,static_m,dynamic_m,wall_m\n")
    for (l in lines.drop(1)) {
        val c = head.zip(l.split(",")).toMap()
        val w = c["w"]!!.toInt(); val h = c["h"]!!.toInt()
        val bb = ByteBuffer.wrap(File(c["bin"]!!).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val disp = FloatArray(w * h) { bb.float }
        val fp = FloorPlanner(FloorPlanner.Geometry(c["height"]!!.toDouble(), c["pitch"]!!.toDouble(), c["hfov"]!!.toDouble()), cfg)
        val an = fp.analyze(disp, w, h)
        val hz = DepthHazards.assess(an, cfg, 0.0)
        out.append("${c["session"]},${c["frame"]},${an.floorValid},${hz.level},${hz.kind},${an.clearDistanceM ?: ""},${an.nearestObstacle?.distanceM ?: ""},${c["static_m"]},${c["dynamic_m"]},${c["wall_m"]}\n")
    }
    File(a[1]).writeText(out.toString())
}
