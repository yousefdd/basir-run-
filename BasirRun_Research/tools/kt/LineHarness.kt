// Current line detector (ContrastLineDetector) on a still image: writes its line probability mask.
// Usage: LineHarness <image> <out.png> <pitchDeg> <hfovDeg> <cameraHeightM>
import run.basir.engine.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
fun main(a: Array<String>) {
    val src = ImageIO.read(File(a[0])); val w = 240; val h = src.height * w / src.width
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); img.graphics.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
    val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) { val p = img.getRGB(x, y); r[y * w + x] = (p shr 16) and 255; g[y * w + x] = (p shr 8) and 255; b[y * w + x] = p and 255 }
    val cam = CameraModel(w, h, a[3].toDouble(), a[4].toDouble(), a[2].toDouble())
    val t0 = System.nanoTime()
    val mask = ContrastLineDetector().detect(RgbImage(w, h, r, g, b), cam)
    val lines = LineExtractor().extract(mask, cam)
    val ms = (System.nanoTime() - t0) / 1e6
    val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
    for (y in 0 until h) for (x in 0 until w) out.raster.setSample(x, y, 0, if (mask[x, y] >= 0.5f) 255 else 0)
    ImageIO.write(out, "png", File(a[1]))
    println("lines=${lines.size} best=${lines.firstOrNull()?.let { "cov %.1fm lat %.2fm".format(it.coverageM, it.lateralAtRunner(1.0)) }} ms=%.1f".format(ms))
}
