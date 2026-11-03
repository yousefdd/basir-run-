package run.basir.replay

import com.basir.run.vision.CameraFrame
import com.basir.run.vision.line.LineEngine
import run.basir.engine.CameraModel
import run.basir.engine.GroundPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coordinate audit (research round 6b): every transform between the sensor frame, the upright frame, the analysis
 * image, the model grids, the ground and the drawing, checked with a marker that must land where geometry says.
 */
class CoordinateAuditTest {
    /** A sensor frame (landscape, as CameraX delivers it) with one bright pixel at (sx, sy). */
    private fun sensorFrame(w: Int, h: Int, sx: Int, sy: Int, rotation: Int, radius: Int = 0): CameraFrame {
        val y = ByteArray(w * h) { 16 }
        for (dy in -radius..radius) for (dx in -radius..radius) y[(sy + dy) * w + sx + dx] = 250.toByte()
        val u = ByteArray(w * h / 4) { 128.toByte() }
        val v = ByteArray(w * h / 4) { 128.toByte() }
        return CameraFrame(y, w, w, h, rotation, u, v, w / 2, 1)
    }

    private fun brightest(f: CameraFrame): Pair<Int, Int> {
        var best = -1; var bx = 0; var by = 0
        for (yy in 0 until f.uprightHeight) for (xx in 0 until f.uprightWidth) {
            val l = f.luma(xx, yy)
            if (l > best) { best = l; bx = xx; by = yy }
        }
        return bx to by
    }

    @Test
    fun sensorToUprightRotationPutsThePixelWhereThePhoneSeesIt() {
        val w = 64; val h = 48; val sx = 10; val sy = 5
        // rotation = clockwise turn that makes the sensor image upright (Android's convention)
        val expected = mapOf(0 to (sx to sy), 90 to (h - 1 - sy to sx), 180 to (w - 1 - sx to h - 1 - sy), 270 to (sy to w - 1 - sx))
        for ((rot, exp) in expected) {
            val f = sensorFrame(w, h, sx, sy, rot)
            assertEquals(if (rot % 180 == 0) w else h, f.uprightWidth, "upright width at $rot")
            assertEquals(exp, brightest(f), "rotation $rot")
        }
    }

    @Test
    fun analysisImageKeepsAspectAndPosition() {
        val w = 640; val h = 480
        val f = sensorFrame(w, h, 400, 300, 90, radius = 3) // portrait upright 480 x 640, marker centred at (179, 400)
        val img = LineEngine.toRgb(f, LineEngine.WIDTH)
        val scale = f.uprightWidth.toDouble() / LineEngine.WIDTH
        assertEquals((f.uprightHeight / scale).toInt(), img.height, "height formula")
        assertTrue(abs(img.width.toDouble() / img.height - f.uprightWidth.toDouble() / f.uprightHeight) < 0.01, "aspect changed")
        var sx = 0.0; var sy = 0.0; var n = 0
        for (yy in 0 until img.height) for (xx in 0 until img.width) if (img.r[yy * img.width + xx] > 128) { sx += xx; sy += yy; n++ }
        assertTrue(n > 0, "the 7 x 7 marker was not sampled at all")
        // pixel centres: analysis pixel x covers upright x in [x*scale, (x+1)*scale)
        val ex = 179.5 / scale - 0.5; val ey = 400.5 / scale - 0.5
        assertTrue(abs(sx / n - ex) <= 1.0 && abs(sy / n - ey) <= 1.0, "marker at (${sx / n},${sy / n}), expected ($ex,$ey)")
    }

    @Test
    fun gridResamplingUsesCellCentres() {
        // one walkable cell of a 64 x 64 model grid must cover the matching block of the 240 x 320 mask, centred
        val src = FloatArray(64 * 64); val ci = 20; val cj = 41
        src[cj * 64 + ci] = 1f
        val m = nearest(src, 64, 64, 240, 320)
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until 320) for (x in 0 until 240) if (m[x, y] > 0.5f) { sx += x; sy += y; n++ }
        assertTrue(n > 0)
        assertTrue(abs(sx / n + 0.5 - (ci + 0.5) * 240 / 64) <= 0.5, "x centre ${sx / n}")
        assertTrue(abs(sy / n + 0.5 - (cj + 0.5) * 320 / 64) <= 0.5, "y centre ${sy / n}")
    }

    @Test
    fun imageGroundImageRoundTripWithPitchAndRoll() {
        val rnd = Random(11)
        var worst = 0.0
        repeat(300) {
            val cam = CameraModel(240, 427, rnd.nextDouble(45.0, 75.0), rnd.nextDouble(0.8, 1.5), rnd.nextDouble(5.0, 40.0), rnd.nextDouble(-15.0, 15.0))
            val g = GroundPoint(rnd.nextDouble(1.0, 10.0), rnd.nextDouble(-2.0, 2.0))
            val px = cam.groundToImage(g) ?: return@repeat
            val back = assertNotNull(cam.imageToGround(px.first, px.second))
            worst = maxOf(worst, hypot(back.forward - g.forward, back.lateral - g.lateral))
        }
        assertTrue(worst < 1e-6, "round trip error $worst m")
    }

    @Test
    fun overlayScalingIsUniform() {
        // drawing scales camera pixels by viewWidth / camera.width on both axes: the analysis image has the frame's aspect
        for ((w, h) in listOf(640 to 480, 480 to 640, 1280 to 720, 720 to 1280)) {
            val ah = (h / (w.toFloat() / LineEngine.WIDTH)).toInt()
            val sx = w.toDouble() / LineEngine.WIDTH; val sy = h.toDouble() / ah
            assertTrue(abs(sx - sy) / sx < 0.01, "$w x $h: x scale $sx, y scale $sy")
        }
    }
}
