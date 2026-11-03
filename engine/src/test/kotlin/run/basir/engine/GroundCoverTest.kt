package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroundCoverTest {
    private val w = 64
    private val h = 64
    private val cam = CameraModel(w, h, 60.0, 1.3, 25.0)
    private val sidewalk = 11
    private val plant = 17
    private val grass = 9
    private val wall = 0

    /** A vertical path (cols 20..43) on grass, rows 18..63; above that a wall. */
    private fun scene(): Pair<IntArray, FloatArray> {
        val lab = IntArray(w * h) { k -> val x = k % w; val y = k / w; if (y < 18) wall else if (x in 20..43) sidewalk else grass }
        val walk = FloatArray(w * h) { if (lab[it] == sidewalk) 1f else 0f }
        return lab to walk
    }

    private fun noRaised() = BooleanArray(32 * 32)

    @Test
    fun leavesAcrossThePathAreGround() {
        val (lab, walk) = scene()
        for (y in 40..46) for (x in 20..43) { lab[y * w + x] = plant; walk[y * w + x] = 0f } // a band of leaves across the path
        val r = GroundCoverResolver().resolve(lab, w, h, walk, noRaised(), 32, 32, cam)
        for (y in 40..46) for (x in 20..43) assertEquals(1f, r.walkable[y * w + x], "($x,$y)")
        assertEquals(7 * 24, r.accepted)
    }

    @Test
    fun aPatchOfLeavesInsideThePathIsGround() {
        val (lab, walk) = scene()
        for (y in 30..34) for (x in 28..33) { lab[y * w + x] = plant; walk[y * w + x] = 0f }
        val r = GroundCoverResolver().resolve(lab, w, h, walk, noRaised(), 32, 32, cam)
        assertEquals(30, r.accepted)
    }

    @Test
    fun raisedVegetationStaysAnObstacle() {
        val (lab, walk) = scene()
        for (y in 40..46) for (x in 20..43) { lab[y * w + x] = plant; walk[y * w + x] = 0f }
        val raised = BooleanArray(32 * 32)
        for (y in 20..23) for (x in 10..21) raised[y * 32 + x] = true // the depth planner sees it above the floor
        val r = GroundCoverResolver().resolve(lab, w, h, walk, raised, 32, 32, cam)
        for (y in 40..46) for (x in 22..41) assertEquals(0f, r.walkable[y * w + x], "bush cell ($x,$y) was accepted")
    }

    @Test
    fun withoutDepthNothingIsAccepted() {
        val (lab, walk) = scene()
        for (y in 40..46) for (x in 20..43) { lab[y * w + x] = plant; walk[y * w + x] = 0f }
        val r = GroundCoverResolver().resolve(lab, w, h, walk, null, 32, 32, cam)
        assertEquals(0, r.accepted)
        assertTrue(r.walkable.contentEquals(walk))
    }

    @Test
    fun flatGrassBesideARecognisedPathIsNotAdded() {
        val (lab, walk) = scene()
        val r = GroundCoverResolver().resolve(lab, w, h, walk, noRaised(), 32, 32, cam)
        assertEquals(0, r.accepted)
    }

    @Test
    fun aLeafCoveredTrailUnderTheFeetIsGround() {
        val lab = IntArray(w * h) { k -> if (k / w < 18) wall else plant }
        val walk = FloatArray(w * h)
        val r = GroundCoverResolver().resolve(lab, w, h, walk, noRaised(), 32, 32, cam)
        assertTrue(r.walkable[63 * w + 32] == 1f && r.walkable[25 * w + 32] == 1f)
        // rows beyond the range depth can check (the far ground near the horizon) are left alone
        val farRow = (0 until h).first { cam.groundDepth(it + 0.5)?.let { d -> d <= 6.0 } == true } - 1
        assertEquals(0f, r.walkable[farRow * w + 32])
    }

    @Test
    fun flatSurfaceChangesAreDroppedRaisedOnesKept() {
        val raised = BooleanArray(20 * 20)
        for (y in 12..17) for (x in 4..7) raised[y * 20 + x] = true
        assertFalse(FlatSurfaceCheck.keep(raised, BooleanArray(400), 20, 20, 0.5, 0.6, 0.9, 0.9), "a flat patch on the right was kept")
        assertTrue(FlatSurfaceCheck.keep(raised, BooleanArray(400), 20, 20, 0.15, 0.55, 0.45, 0.95), "a raised object was dropped")
        val drop = BooleanArray(400).also { for (y in 14..17) for (x in 12..16) it[y * 20 + x] = true }
        assertTrue(FlatSurfaceCheck.keep(raised, drop, 20, 20, 0.55, 0.65, 0.9, 0.9), "a hole was dropped")
        assertTrue(FlatSurfaceCheck.keep(null, null, 20, 20, 0.5, 0.6, 0.9, 0.9), "without depth the finding must be kept")
        assertNull(FlatSurfaceCheck.raisedShare(null, 20, 20, 0.0, 0.0, 1.0, 1.0))
    }
}

class FlatGroundPatternTest {
    @Test
    fun leavesSeenAsBirdsOnFlatGroundAreDropped() {
        kotlin.test.assertTrue(DetectionFilter.flatGroundPattern("bird", 0.0))
        kotlin.test.assertTrue(DetectionFilter.flatGroundPattern("sports ball", 0.05))
    }

    @Test
    fun raisedSmallItemsPeopleAndUnknownDepthAreKept() {
        kotlin.test.assertFalse(DetectionFilter.flatGroundPattern("bird", 0.4), "a raised small item must stay")
        kotlin.test.assertFalse(DetectionFilter.flatGroundPattern("bird", null), "without depth evidence it must stay")
        kotlin.test.assertFalse(DetectionFilter.flatGroundPattern("person", 0.0), "people are never dropped")
        kotlin.test.assertFalse(DetectionFilter.flatGroundPattern("bench", 0.0))
    }
}
