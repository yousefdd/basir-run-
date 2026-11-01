import run.basir.engine.*
import java.io.File

/** Runs the engine's TerrainZones + SurfaceMonitor on terrain.bin written by visual_test.py
 *  (per frame: 64x64 ADE labels, top-2 margins x255, walkable 0/1) at the clip's fps. */
fun main(args: Array<String>) {
    val bytes = File(args[0]).readBytes(); val fps = args[2].toDouble(); val n = 64 * 64
    val tz = TerrainZones(); val mon = SurfaceMonitor()
    File(args[1]).printWriter().use { out ->
        out.println("frame,corr_walkable,corr_caution,corr_obstacle,corr_unclear,corr_unverified,foot_walkable,foot_caution,foot_unclear,surface_cue")
        var f = 0
        while ((f + 1) * 3 * n <= bytes.size) {
            val o = f * 3 * n
            val lab = IntArray(n) { bytes[o + it].toInt() and 0xff }
            val mar = FloatArray(n) { (bytes[o + n + it].toInt() and 0xff) / 255f }
            val wk = BooleanArray(n) { bytes[o + 2 * n + it].toInt() != 0 }
            val s = tz.summarize(tz.classify(lab, mar, wk, 64, 64), 64, 64)
            val cue = mon.update(s, (f * 1000 / fps).toLong())
            out.println(listOf(f, s.corridorShare(Zone.WALKABLE), s.corridorShare(Zone.CAUTION), s.corridorShare(Zone.OBSTACLE), s.corridorShare(Zone.UNCLEAR), s.corridorShare(Zone.UNVERIFIED),
                s.footShare(Zone.WALKABLE), s.footShare(Zone.CAUTION), s.footShare(Zone.UNCLEAR)).joinToString(",") { if (it is Double) "%.3f".format(it) else it.toString() } + ",$cue")
            f++
        }
    }
}
