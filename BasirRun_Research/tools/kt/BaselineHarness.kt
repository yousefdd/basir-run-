// Runs the app's current image-space path detectors (LaneDetector, PathRegionDetector,
// EnvironmentSelector) on still images and writes the walkable region they imply as a mask.
// Usage: BaselineHarness <list.txt> <outDir>   (list: one image path per line)
import com.basir.run.vision.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun frameOf(img: BufferedImage): CameraFrame {
    val w = img.width and 1.inv(); val h = img.height and 1.inv()
    val y = ByteArray(w * h); val u = ByteArray((w / 2) * (h / 2)); val v = ByteArray((w / 2) * (h / 2))
    for (j in 0 until h) for (i in 0 until w) {
        val p = img.getRGB(i, j); val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        y[j * w + i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0) {
            val k = (j / 2) * (w / 2) + i / 2
            u[k] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).toInt().coerceIn(0, 255).toByte()
            v[k] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).toInt().coerceIn(0, 255).toByte()
        }
    }
    return CameraFrame(y, w, w, h, 0, u, v, w / 2, 1)
}

fun main(args: Array<String>) {
    val out = File(args[1]).apply { mkdirs() }
    val csv = StringBuilder("image,status,source,confidence,ms\n")
    for (path in File(args[0]).readLines().filter { it.isNotBlank() }) {
        val img = ImageIO.read(File(path))
        val frame = frameOf(img)
        val t0 = System.nanoTime()
        val est = EnvironmentSelector().choose(LaneDetector().detect(frame), PathRegionDetector().detect(frame))
        val ms = (System.nanoTime() - t0) / 1e6
        val w = frame.uprightWidth; val h = frame.uprightHeight
        val mask = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val l = est.left; val r = est.right
        if (est.status == PathStatus.Tracked && l != null && r != null) {
            for (j in (est.roiTop * h).toInt() until h) {
                val yy = j.toFloat() / h
                val x0 = (l.x(yy) * w).toInt().coerceIn(0, w); val x1 = (r.x(yy) * w).toInt().coerceIn(0, w)
                for (i in x0 until x1) mask.raster.setSample(i, j, 0, 255)
            }
        }
        val name = File(path).parentFile.name + "__" + File(path).nameWithoutExtension
        ImageIO.write(mask, "png", File(out, "$name.png"))
        csv.append("$path,${est.status},${est.source},${est.confidence},${"%.1f".format(ms)}\n")
    }
    File(out, "baseline.csv").writeText(csv.toString())
}
