package run.basir.engine

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroundRibbonTest {
    private val cam = CameraModel(240, 427, 60.0, 1.3, 20.0, rollDeg = 6.0)

    @Test
    fun ribbonPointsLieOnTheGround() {
        val curve = PathCurve(doubleArrayOf(0.2, 0.05, 0.02), 0.0, 8.0)
        val ribbon = assertNotNull(GroundRibbon.build(curve, cam))
        var worst = 0.0
        for (s in ribbon.sections) for ((g, px) in listOf(s.centre to s.centrePx, s.left to s.leftPx, s.right to s.rightPx)) {
            val back = assertNotNull(cam.imageToGround(px.first, px.second))
            worst = maxOf(worst, hypot(back.forward - g.forward, back.lateral - g.lateral))
        }
        assertTrue(worst < 0.02, "round trip error $worst m")
    }

    @Test
    fun ribbonNarrowsWithDistance() {
        val straight = PathCurve(doubleArrayOf(0.0, 0.0, 0.0), 0.0, 10.0)
        val level = cam.copy(rollDeg = 0.0)
        val ribbon = assertNotNull(GroundRibbon.build(straight, level))
        val widths = ribbon.sections.map { hypot(it.rightPx.first - it.leftPx.first, it.rightPx.second - it.leftPx.second) }
        for (k in 1 until widths.size) assertTrue(widths[k] < widths[k - 1], "width grew at ${ribbon.sections[k].forwardM} m: $widths")
        // and it rises toward the horizon
        for (k in 1 until ribbon.sections.size) assertTrue(ribbon.sections[k].centrePx.second < ribbon.sections[k - 1].centrePx.second)
    }

    @Test
    fun ribbonCoversOnlyTheMeasuredVisibleRange() {
        val curve = PathCurve(doubleArrayOf(-0.1, 0.0, 0.01), 0.5, 4.0)
        val ribbon = assertNotNull(GroundRibbon.build(curve, cam))
        val visible = assertNotNull(GroundRibbon.firstVisibleForwardM(cam))
        assertTrue(ribbon.nearM >= maxOf(curve.nearM, visible) - 1e-9, "starts at ${ribbon.nearM}, visible from $visible")
        assertTrue(ribbon.farM <= curve.farM + 1e-9, "ends at ${ribbon.farM} beyond the measured ${curve.farM}")
    }

    @Test
    fun noRibbonWithoutAVisibleMeasuredPart() {
        val visible = assertNotNull(GroundRibbon.firstVisibleForwardM(cam))
        val hidden = PathCurve(doubleArrayOf(0.0, 0.0, 0.0), 0.0, visible - 0.3) // all of it below the picture
        assertNull(GroundRibbon.build(hidden, cam))
    }

    @Test
    fun ribbonFollowsABend() {
        val bend = PathCurve(doubleArrayOf(0.0, 0.0, 0.08), 0.0, 8.0) // bends right
        val ribbon = assertNotNull(GroundRibbon.build(bend, cam.copy(rollDeg = 0.0)))
        val near = ribbon.sections.first()
        val far = ribbon.sections.last()
        assertTrue(far.centre.lateral > near.centre.lateral + 1.0)
        assertTrue(far.centrePx.first > near.centrePx.first, "the far end is drawn to the right in the picture")
        // the edges stay the strip's width apart on the ground, measured across the path
        for (s in ribbon.sections) assertTrue(abs(hypot(s.right.forward - s.left.forward, s.right.lateral - s.left.lateral) - 0.6) < 1e-6)
    }
}
