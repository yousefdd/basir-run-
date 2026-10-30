package run.basir.engine

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared helpers for tests on real images. */
object TestImages {
    val dir = File(System.getProperty("basir.testdata") ?: "src/test/resources")
    val outDir = File("build/test-output").also { it.mkdirs() }

    fun rgb(name: String): RgbImage {
        val img = ImageIO.read(File(dir, name))
        val w = img.width
        val h = img.height
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            r[y * w + x] = (p shr 16) and 0xFF; g[y * w + x] = (p shr 8) and 0xFF; b[y * w + x] = p and 0xFF
        }
        return RgbImage(w, h, r, g, b)
    }

    /** A mask PNG (8- or 16-bit gray) as probabilities 0..1. */
    fun mask(name: String): ProbabilityMask {
        val img = ImageIO.read(File(dir, name))
        val raster = img.raster
        val max = if (img.type == BufferedImage.TYPE_USHORT_GRAY) 65535f else 255f
        val m = ProbabilityMask(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) m[x, y] = raster.getSample(x, y, 0) / max
        return m
    }

    fun overlay(name: String, base: String, draw: (java.awt.Graphics2D) -> Unit) {
        val img = ImageIO.read(File(dir, base))
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        draw(g)
        g.dispose()
        ImageIO.write(out, "png", File(outDir, name))
    }
}

class RealImageLineTest {
    // guideline1.png: phone at about waist height looking down the path; horizon near row 40
    // of 480 for a ~65° horizontal field of view → ~22° tilt. Ground truth comes from the
    // golden mask, not from these numbers.
    private val camera = CameraModel(640, 480, hfovDeg = 65.0, heightM = 1.0, pitchDeg = 22.0)
    private val golden = TestImages.mask("guideline/guideline1_mask_golden.png")

    /** Fraction of the path's projected samples that land on (or within [tolPx] of) the golden line. */
    private fun onGolden(curve: PathCurve, cam: CameraModel, tolPx: Int = 6): Double {
        val pts = curve.sample(0.2)
        var hit = 0
        var total = 0
        for (p in pts) {
            val (u, v) = cam.groundToImage(p) ?: continue
            val x = u.toInt(); val y = v.toInt()
            if (x !in 0 until golden.width || y !in 0 until golden.height) continue
            total++
            var found = false
            for (dy in -tolPx..tolPx) for (dx in -tolPx..tolPx) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until golden.width && yy in 0 until golden.height && golden[xx, yy] > 0.5f) found = true
            }
            if (found) hit++
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    @Test
    fun `golden mask gives one continuous line that bends right`() {
        val candidates = LineExtractor().extract(golden, camera)
        val best = candidates.firstOrNull()
        assertNotNull(best, "no line extracted from the golden mask")
        println("golden: ${candidates.size} candidate(s); best coverage=${"%.2f".format(best.coverageM)} m " +
            "continuity=${"%.2f".format(best.continuity)} rms=${"%.3f".format(best.curve.rmsM)} m")
        assertTrue(best.coverageM >= 3.0, "coverage ${best.coverageM}")
        assertTrue(onGolden(best.curve, camera) >= 0.95)
        // In the photo the line runs up and to the right: it heads right further ahead
        val near = best.curve.headingDegAt(best.curve.nearM)
        val far = best.curve.headingDegAt(best.curve.farM)
        println("golden: heading near=${"%.1f".format(near)}° far=${"%.1f".format(far)}°")
        assertTrue(far > near + 5, "expected the line to bend right ahead")
    }

    @Test
    fun `color-agnostic detector finds the guide line and ignores the pavement crack`() {
        val rgb = TestImages.rgb("guideline/guideline1.png")
        val t0 = System.nanoTime()
        val mask = ContrastLineDetector().detect(rgb, camera)
        val t1 = System.nanoTime()
        val candidates = LineExtractor().extract(mask, camera)
        val t2 = System.nanoTime()
        val best = candidates.firstOrNull()
        assertNotNull(best, "no line found")

        // Pixel metrics of the raw detector against the golden mask (no learning, no color prior)
        var tp = 0; var fp = 0; var fn = 0
        for (i in mask.data.indices) {
            val p = mask.data[i] >= 0.5f
            val gt = golden.data[i] > 0.5f
            if (p && gt) tp++ else if (p) fp++ else if (gt) fn++
        }
        val precision = tp.toDouble() / max(1, tp + fp)
        val recall = tp.toDouble() / max(1, tp + fn)
        val iou = tp.toDouble() / max(1, tp + fp + fn)
        val hit = onGolden(best.curve, camera)
        println("contrast detector: pixel precision=${"%.2f".format(precision)} recall=${"%.2f".format(recall)} " +
            "IoU=${"%.2f".format(iou)} | ${candidates.size} candidate lines | chosen path on golden line: " +
            "${"%.0f".format(100 * hit)}% | detect ${(t1 - t0) / 1_000_000} ms, extract ${(t2 - t1) / 1_000_000} ms " +
            "(JVM, 640x480, unoptimized)")

        TestImages.overlay("guideline1_contrast.png", "guideline/guideline1.png") { g ->
            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                if (mask[x, y] >= 0.5f) { g.color = Color(255, 255, 0, 160); g.fillRect(x, y, 1, 1) }
            }
            g.stroke = BasicStroke(2f)
            for ((k, c) in candidates.take(6).withIndex()) {
                g.color = if (k == 0) Color.GREEN else Color.RED
                for (s in c.segments) g.drawOval(s.u.toInt() - 3, s.v.toInt() - 3, 6, 6)
            }
            g.color = Color.CYAN
            g.stroke = BasicStroke(4f)
            val pts = best.curve.sample(0.1).mapNotNull { camera.groundToImage(it) }
            for (i in 1 until pts.size) g.drawLine(pts[i - 1].first.toInt(), pts[i - 1].second.toInt(), pts[i].first.toInt(), pts[i].second.toInt())
        }

        assertTrue(hit >= 0.9, "chosen path is not on the guide line ($hit)")
        assertTrue(best.coverageM >= 3.0)
        // Any other candidate (e.g. the crack crossing the path) must score clearly lower
        candidates.drop(1).firstOrNull()?.let { assertTrue(it.score < 0.5 * best.score) }
    }

    @Test
    fun `result is stable under tilt calibration error`() {
        // ±4° pitch error must not flip which line is chosen
        for (pitch in listOf(18.0, 22.0, 26.0)) {
            val cam = camera.copy(pitchDeg = pitch)
            val best = LineExtractor().extract(golden, cam).firstOrNull()
            assertNotNull(best)
            assertTrue(onGolden(best.curve, cam) >= 0.9, "pitch $pitch")
            assertTrue(abs(best.curve.lateralAt(1.0)) < 0.6, "pitch $pitch: line should start near the runner")
        }
    }
}
