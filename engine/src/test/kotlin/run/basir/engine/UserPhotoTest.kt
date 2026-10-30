package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole chain on the user's reference photo (a purple guide line on a park path that bends
 * right): detector → line extraction → planner → guidance, at full size and at the reduced size
 * the app analyses. Camera values as in [RealImageLineTest]; in the app the tilt comes from the
 * gravity sensor.
 */
class UserPhotoTest {

    private fun downscale(img: RgbImage, f: Int): RgbImage {
        val w = img.width / f
        val h = img.height / f
        val r = IntArray(w * h); val g = IntArray(w * h); val b = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (dy in 0 until f) for (dx in 0 until f) {
                val k = (y * f + dy) * img.width + x * f + dx
                sr += img.r[k]; sg += img.g[k]; sb += img.b[k]
            }
            val n = f * f
            r[y * w + x] = sr / n; g[y * w + x] = sg / n; b[y * w + x] = sb / n
        }
        return RgbImage(w, h, r, g, b)
    }

    private fun run(img: RgbImage): Pair<PathDecision, GuidanceCommand> {
        val cam = CameraModel(img.width, img.height, hfovDeg = 65.0, heightM = 1.0, pitchDeg = 22.0)
        val lines = LineExtractor().extract(ContrastLineDetector().detect(img, cam), cam)
        assertTrue(lines.isNotEmpty(), "no line at ${img.width}x${img.height}")
        val planner = IntelligentPathPlanner()
        val guidance = GuidanceController()
        var d: PathDecision? = null
        var c: GuidanceCommand? = null
        // The tracker needs a few consistent frames before it trusts a line; feed the same frame.
        repeat(6) {
            d = planner.update(FrameInput(lines, null, null, emptyList(), visibility = 1.0))
            c = guidance.update(d!!)
        }
        return d!! to c!!
    }

    @Test
    fun `follows the purple line and sees the bend to the right`() {
        val full = TestImages.rgb("guideline/user_photo.jpg")
        for (img in listOf(full, downscale(full, 2))) {
            val t0 = System.nanoTime()
            val (decision, command) = run(img)
            val ms = (System.nanoTime() - t0) / 1e6
            println("user photo ${img.width}x${img.height}: mode=${decision.mode} conf=${decision.confidence} " +
                "command=${command.kind} ${command.side} ${command.level} steer=${"%.1f".format(command.steerDeg)}° " +
                "curve=${"%.3f".format(command.curveCurvature)}/m in ${command.curveInM} m (${"%.0f".format(ms)} ms, 6 frames, JVM)")
            assertEquals(PathMode.LINE, decision.mode)
            val path = assertNotNull(decision.path)
            assertTrue(path.headingDegAt(path.farM) > path.headingDegAt(path.nearM) + 10, "the line bends right ahead")
            // The camera is right of the line, so the cue is to move left, never right.
            assertTrue(command.side != Side.RIGHT, "contradictory side ${command.side}")
            assertTrue(command.curveCurvature >= 0.0, "a bend, if announced, is to the right")
        }
    }
}
