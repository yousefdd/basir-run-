package com.basir.run.vision

import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the detectors on frames taken from real recordings, if any are present, and checks them
 * against what a person marked as correct for that clip.
 *
 * Layout: src/test/resources/frames/<clip>/ with upright PNG frames in time order and an
 * expected.properties file:
 *   mode=track | park
 *   status=Tracked | EdgeOnly | Unclear      (what most frames should give)
 *   side=left | right | centre               (optional: which side of the path the runner is on)
 *   curve=left | right | straight            (optional)
 *   min_share=0.7                            (optional: share of frames that must match, default 0.7)
 *
 * Make frames from a phone video with, for example:
 *   ffmpeg -i clip.mp4 -vf "fps=5,scale=480:-1" frames/track_straight/%04d.png
 *
 * Passing here shows the detector agrees with the marked clips; it does not show that running
 * with the app is safe.
 */
class RecordedFramesTest {

    @Test
    fun recordedClipsMatchTheirExpectations() {
        val root = File("src/test/resources/frames")
        val clips = root.listFiles { f -> f.isDirectory && File(f, "expected.properties").exists() }.orEmpty().sortedBy { it.name }
        assumeTrue("No recorded clips in ${root.path}; add some to run this test", clips.isNotEmpty())

        val failures = mutableListOf<String>()
        for (clip in clips) {
            val expected = Properties().apply { File(clip, "expected.properties").inputStream().use { load(it) } }
            val frames = clip.listFiles { f -> f.extension.lowercase() == "png" }.orEmpty().sortedBy { it.name }
            if (frames.isEmpty()) continue
            val lane = LaneDetector()
            val region = PathRegionDetector()
            val tracker = PathTracker()
            var matched = 0
            for (file in frames) {
                val frame = toFrame(file)
                val estimate = if (expected.getProperty("mode") == "park") region.detect(frame) else lane.detect(frame)
                val tracked = tracker.update(estimate)
                if (matches(tracked, expected)) matched++
            }
            val share = matched.toFloat() / frames.size
            val needed = expected.getProperty("min_share", "0.7").toFloat()
            println("clip ${clip.name}: ${frames.size} frames, ${"%.0f".format(share * 100)}% as expected (need ${"%.0f".format(needed * 100)}%)")
            if (share < needed) failures += "${clip.name}: ${"%.0f".format(share * 100)}%"
        }
        if (failures.isNotEmpty()) throw AssertionError("Clips below their expected share: " + failures.joinToString())
    }

    private fun matches(t: TrackedPath, expected: Properties): Boolean {
        val e = t.estimate
        if (e.status.name != expected.getProperty("status")) return false
        if (e.status != PathStatus.Tracked) return true
        when (expected.getProperty("side")) {
            "left" -> if (t.offset > -0.15f) return false
            "right" -> if (t.offset < 0.15f) return false
            "centre" -> if (kotlin.math.abs(t.offset) > 0.25f) return false
        }
        when (expected.getProperty("curve")) {
            "left" -> if (t.curvature > -0.1f) return false
            "right" -> if (t.curvature < 0.1f) return false
            "straight" -> if (kotlin.math.abs(t.curvature) > 0.15f) return false
        }
        return true
    }

    // RGB to YUV 4:2:0 with the BT.601 coefficients cameras use.
    private fun toFrame(file: File): CameraFrame {
        val img = ImageIO.read(file)
        val w = img.width and 1.inv()
        val h = img.height and 1.inv()
        val y = ByteArray(w * h)
        val u = ByteArray((w / 2) * (h / 2))
        val v = ByteArray((w / 2) * (h / 2))
        for (py in 0 until h) for (px in 0 until w) {
            val rgb = img.getRGB(px, py)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            y[py * w + px] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
            if (px % 2 == 0 && py % 2 == 0) {
                val i = (py / 2) * (w / 2) + px / 2
                u[i] = (-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt().coerceIn(0, 255).toByte()
                v[i] = (0.5 * r - 0.419 * g - 0.081 * b + 128).toInt().coerceIn(0, 255).toByte()
            }
        }
        return CameraFrame(y, w, w, h, 0, u, v, w / 2, 1)
    }
}
