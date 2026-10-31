package run.basir.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Signs end to end, checked against an independent 3-D model of a phone camera (not CameraModel):
 * a camera 1.3 m up, tilted down by pitch and turned clockwise (as the runner sees the screen) by roll,
 * photographs ground points; the gravity reading that phone would give goes through GravityTilt; the
 * pixels go back to the ground through CameraModel with those angles. A wrong sign anywhere puts
 * points on the wrong side or at the wrong distance.
 */
class AngleConventionTest {
    private val w = 160; private val h = 120; private val hfov = 60.0; private val height = 1.3
    private val f = (w / 2.0) / tan(Math.toRadians(hfov / 2))

    /** World: X right, Y forward, Z up. Returns pixel (u right, v down) or null if behind. */
    private fun shoot(pitch: Double, roll: Double, X: Double, Y: Double): Pair<Double, Double>? {
        val p = Math.toRadians(pitch); val r = Math.toRadians(roll)
        val fw = doubleArrayOf(0.0, cos(p), -sin(p))          // optical axis
        val r0 = doubleArrayOf(1.0, 0.0, 0.0); val d0 = doubleArrayOf(0.0, -sin(p), -cos(p))
        val right = DoubleArray(3) { r0[it] * cos(r) + d0[it] * sin(r) }   // clockwise roll: right swings down
        val down = DoubleArray(3) { d0[it] * cos(r) - r0[it] * sin(r) }
        val v = doubleArrayOf(X, Y, -height)
        fun dot(a: DoubleArray) = a[0] * v[0] + a[1] * v[1] + a[2] * v[2]
        val zc = dot(fw); if (zc <= 0.1) return null
        return (w - 1) / 2.0 + f * dot(right) / zc to (h - 1) / 2.0 + f * dot(down) / zc
    }

    /** What the gravity sensor reads for that pose (device x = image right, y = image up, z = toward the runner). */
    private fun gravity(pitch: Double, roll: Double): Triple<Double, Double, Double> {
        val p = Math.toRadians(pitch); val r = Math.toRadians(roll); val g = 9.81
        return Triple(-g * cos(p) * sin(r), g * cos(p) * cos(r), g * sin(p))
    }

    @Test
    fun `sensor angles and the ground projection agree for any tilt and roll`() {
        for (pitch in listOf(10.0, 20.0, 35.0)) for (roll in listOf(-20.0, -8.0, 0.0, 8.0, 20.0)) {
            val (gx, gy, gz) = gravity(pitch, roll)
            val pm = GravityTilt.pitchBelowHorizonDeg(gx, gy, gz)!!; val rm = GravityTilt.rollDeg(gx, gy, gz)!!
            assertTrue(abs(pm - pitch) < 1e-6 && abs(rm - roll) < 1e-6, "sensor: $pm/$rm for $pitch/$roll")
            val cam = CameraModel(w, h, hfov, height, pm, rm)
            for ((lat, fwd) in listOf(-1.0 to 3.0, 0.0 to 4.0, 1.0 to 3.0, 0.5 to 6.0, -0.7 to 2.5)) {
                val px = shoot(pitch, roll, lat, fwd) ?: continue
                if (px.first !in 0.0..(w - 1.0) || px.second !in 0.0..(h - 1.0)) continue
                val g = cam.imageToGround(px.first, px.second) ?: error("no ground for $px")
                assertTrue(abs(g.forward - fwd) < 0.02 && abs(g.lateral - lat) < 0.02,
                    "pitch $pitch roll $roll: ($lat, $fwd) came back as (${g.lateral}, ${g.forward})")
            }
        }
    }

    @Test
    fun `something to the right on the ground is to the right in the image and in the path`() {
        val cam = CameraModel(w, h, hfov, height, 20.0, 0.0)
        val px = shoot(20.0, 0.0, 1.0, 4.0)!!
        assertTrue(px.first > (w - 1) / 2.0, "a point 1 m to the right appears right of centre")
        assertTrue(cam.imageToGround(px.first, px.second)!!.lateral > 0, "and lateral > 0 means right")
        val c = PathCurve(doubleArrayOf(0.5, 0.0, 0.0), 0.8, 6.0)
        val e = StableGuidanceController().let { s -> (1..20).map { s.update(PathDecision(PathMode.FREE_PATH, c, 0.9), 1.4, 1.0 / 15) }.last() }
        assertTrue(e.command.side == Side.RIGHT, "path to the right -> correct right, not left")
    }
}
