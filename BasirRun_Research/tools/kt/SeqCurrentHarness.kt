// The line engine as it runs in the app today (engine at HEAD: colour-contrast line detector ->
// line extractor -> path planner without free space -> guidance), frame by frame on images.
// Usage: SeqCurrentHarness <frames.txt> <out.csv> <hfov> <height> <pitch>
import run.basir.engine.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
fun main(a: Array<String>) {
    val det = ContrastLineDetector(); val ext = LineExtractor(); val planner = IntelligentPathPlanner(); val guidance = GuidanceController()
    val out = StringBuilder("frame,lines,mode,cmd,side,level,steer,points\n")
    File(a[0]).readLines().filter { it.isNotBlank() }.forEachIndexed { i, path ->
        val src = ImageIO.read(File(path)); val w = 240; val h = src.height * w / src.width
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); img.graphics.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { val p = img.getRGB(x, y); r[y * w + x] = (p shr 16) and 255; g[y * w + x] = (p shr 8) and 255; b[y * w + x] = p and 255 }
        val cam = CameraModel(w, h, a[2].toDouble(), a[3].toDouble(), a[4].toDouble())
        val lines = ext.extract(det.detect(RgbImage(w, h, r, g, b), cam), cam)
        val d = planner.update(FrameInput(lines, null, null, emptyList(), 1.0, dt = 1.0 / 15))
        val c = guidance.update(d)
        val pts = d.path?.sample(0.25, maxOf(d.path.nearM, 0.8), d.path.farM)?.mapNotNull { cam.groundToImage(it) }?.joinToString(" ") { (u, v) -> "%.3f:%.3f".format(u / w, v / h) } ?: ""
        out.append("$i,${lines.size},${d.mode},${c.kind},${c.side ?: ""},${c.level ?: ""},${"%.1f".format(c.steerDeg)},$pts\n")
    }
    File(a[1]).writeText(out.toString())
}
