package run.basir.replay

import com.basir.run.vision.depth.DepthHazards
import com.basir.run.vision.depth.FloorPlanner
import org.json.JSONObject
import run.basir.engine.CameraModel
import run.basir.engine.GroundCoverResolver
import run.basir.engine.UnderfootClasses
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Walkable grids for independent ground-truth frames, computed by the engine's own code:
 *  - baseline:     WalkableSegmenter.runnableClasses() with a fresh UnderfootClasses per frame
 *  - ground_cover: baseline + GroundCoverResolver with the depth floor planner's raised mask
 *
 * Input dir (written by BasirRun_Research/benchmarks/eval_gt.py): gt_meta.json (n, segW, segH, depthW,
 * depthH, hfovDeg, heightM), seg_labels.bin (n x segW x segH uint8), disparity.bin (n x depthW x depthH float32).
 * Output: walk_baseline.bin, walk_ground_cover.bin (n x segW x segH uint8).
 */
object GtWalkable {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val m = JSONObject(File(dir, "gt_meta.json").readText())
        val n = m.getInt("n"); val sw = m.getInt("segW"); val shh = m.getInt("segH")
        val dw = m.getInt("depthW"); val dh = m.getInt("depthH")
        val hfov = m.getDouble("hfovDeg"); val heightM = m.getDouble("heightM")
        val labels = File(dir, "seg_labels.bin").readBytes()
        val dispBytes = File(dir, "disparity.bin").readBytes()
        val disp = ByteBuffer.wrap(dispBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val base = ByteArray(n * sw * shh)
        val cover = ByteArray(n * sw * shh)
        val pitches = ArrayList<Double>()
        val resolver = GroundCoverResolver()
        for (i in 0 until n) {
            val lab = IntArray(sw * shh) { labels[i * sw * shh + it].toInt() and 0xFF }
            val runnable = runnableClasses(lab, sw, shh, UnderfootClasses(), 0L)
            val walk = FloatArray(sw * shh) { if (lab[it] in runnable) 1f else 0f }
            val d = FloatArray(dw * dh).also { disp.get(it) }
            val a = FloorPlanner(FloorPlanner.Geometry(heightM, null, hfov), DepthHazards.RUNNING).analyze(d, dw, dh)
            pitches += a.pitchDeg
            val cam = CameraModel(sw, shh, hfov, heightM, a.pitchDeg)
            val res = resolver.resolve(lab, sw, shh, walk, a.takeIf { it.floorValid }?.obstacleMask, dw, dh, cam)
            for (k in 0 until sw * shh) {
                base[i * sw * shh + k] = if (walk[k] >= 0.5f) 1 else 0
                cover[i * sw * shh + k] = if (res.walkable[k] >= 0.5f) 1 else 0
            }
        }
        File(dir, "walk_baseline.bin").writeBytes(base)
        File(dir, "walk_ground_cover.bin").writeBytes(cover)
        File(dir, "pitch_estimates.txt").writeText(pitches.joinToString("\n"))
        println("gt walkable: $n frames")
    }
}
