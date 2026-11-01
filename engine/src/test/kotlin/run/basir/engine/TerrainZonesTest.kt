package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainZonesTest {
    private val w = 10; private val h = 10

    private fun map(fill: (x: Int, y: Int) -> Int) = IntArray(w * h) { fill(it % w, it / w) }

    @Test
    fun fiveZoneKindsFromClassesMarginAndWalkable() {
        // left half sidewalk (11), right half grass (9), top two rows sky (2), one wall cell (0), one hesitant cell
        val labels = map { x, y -> when { y < 2 -> 2; x == 9 && y == 5 -> 0; x < 5 -> 11; else -> 9 } }
        val margin = FloatArray(w * h) { 0.5f }.also { it[6 * w + 2] = 0.01f }
        val walkable = BooleanArray(w * h) { i -> labels[i] == 11 || labels[i] == 9 }
        walkable[3 * w + 1] = false                                           // a sidewalk cell the rule rejected
        val z = TerrainZones().classify(labels, margin, walkable, w, h)
        assertEquals(Zone.NONE, z[0])                                          // sky
        assertEquals(Zone.WALKABLE, z[8 * w + 2])                              // sidewalk, accepted
        assertEquals(Zone.CAUTION, z[8 * w + 7])                               // grass, accepted: natural surface
        assertEquals(Zone.OBSTACLE, z[5 * w + 9])                              // wall
        assertEquals(Zone.UNCLEAR, z[6 * w + 2])                               // the model hesitates
        assertEquals(Zone.UNVERIFIED, z[3 * w + 1])                            // ground the rule did not accept
    }

    @Test
    fun acceptedGroundNotConnectedToTheFeetIsUnverified() {
        // sidewalk under the feet, a wall band across row 4, sidewalk again above it (seen through nothing)
        val labels = map { _, y -> if (y == 4) 0 else 11 }
        val z = TerrainZones().classify(labels, null, BooleanArray(w * h) { true }, w, h)
        assertEquals(Zone.WALKABLE, z[9 * w + 5])
        assertEquals(Zone.UNVERIFIED, z[2 * w + 5])
    }

    @Test
    fun summaryCountsTheCorridorAndTheFoot() {
        val labels = map { x, _ -> if (x < 5) 11 else 9 }
        val tz = TerrainZones()
        val s = tz.summarize(tz.classify(labels, null, BooleanArray(w * h) { true }, w, h), w, h)
        assertTrue(s.footShare(Zone.WALKABLE) > 0.5 && s.footShare(Zone.CAUTION) > 0.3)
        assertEquals(1.0, Zone.values().sumOf { s.corridorShare(it) }, 1e-9)
    }

    private fun summary(rough: Double) = TerrainSummary(emptyMap(), mapOf(Zone.CAUTION to rough, Zone.WALKABLE to 1.0 - rough))

    @Test
    fun surfaceCueOnceWhenSmoothTurnsRough() {
        val m = SurfaceMonitor(); var cues = 0
        for (t in 0L until 4000L step 100) if (m.update(summary(0.0), t)) cues++          // 4 s of asphalt
        for (t in 4000L until 8000L step 100) if (m.update(summary(0.9), t)) cues++       // onto a dirt trail
        assertEquals(1, cues)
        for (t in 8000L until 12000L step 100) if (m.update(summary(0.9), t)) cues++      // still on the trail: no repeat
        assertEquals(1, cues)
    }

    @Test
    fun noSurfaceCueForAShortPatchOrWithoutSmoothBefore() {
        val m = SurfaceMonitor()
        for (t in 0L until 4000L step 100) assertFalse(m.update(summary(0.0), t))
        for (t in 4000L until 5000L step 100) assertFalse(m.update(summary(0.9), t))     // 1 s of grass only
        for (t in 5000L until 6000L step 100) assertFalse(m.update(summary(0.0), t))
        val fresh = SurfaceMonitor()                                                       // started on the trail
        for (t in 0L until 6000L step 100) assertFalse(fresh.update(summary(0.9), t))
    }

    @Test
    fun surfaceCueRateLimited() {
        val m = SurfaceMonitor(); var cues = 0; var t = 0L
        repeat(3) {                                                                        // asphalt / trail every 6 s
            repeat(40) { if (m.update(summary(0.0), t)) cues++; t += 100 }
            repeat(20) { if (m.update(summary(0.9), t)) cues++; t += 100 }
        }
        assertEquals(1, cues)                                                              // 18 s < 30 s gap
    }

    @Test
    fun aOneFrameDipDoesNotEraseTheSmoothRun() {
        val m = SurfaceMonitor(); var cues = 0
        for (t in 0L until 4000L step 200) if (m.update(summary(if (t == 3000L) 0.0 else 0.0).let { if (t == 3000L) TerrainSummary(emptyMap(), mapOf(Zone.WALKABLE to 0.1)) else it }, t)) cues++
        for (t in 4000L until 7000L step 200) if (m.update(summary(0.9), t)) cues++
        assertEquals(1, cues)
    }

    @Test
    fun unclearFramesInTheMiddleOfTheChangeDoNotHideIt() {
        val m = SurfaceMonitor(); var cues = 0
        val unclear = TerrainSummary(emptyMap(), mapOf(Zone.UNCLEAR to 1.0))
        for (t in 0L until 6000L step 200) if (m.update(summary(0.0), t)) cues++       // asphalt
        for (t in 6000L until 7200L step 200) if (m.update(summary(0.9), t)) cues++    // 1.2 s of dirt
        for (t in 7200L until 8000L step 200) if (m.update(unclear, t)) cues++          // 0.8 s unclear
        for (t in 8000L until 10000L step 200) if (m.update(summary(0.9), t)) cues++   // dirt again
        assertEquals(1, cues)
    }

    @Test
    fun implausibleDetectionsAreFiltered() {
        assertFalse(DetectionFilter.keep("bear", 0.3, 0.3, 0.6, 0.9, 0.65))              // forest trail false "bear"
        assertTrue(DetectionFilter.keep("bear", 0.3, 0.3, 0.6, 0.9, 0.9))                // a sure one stays
        assertFalse(DetectionFilter.keep("tie", 0.45, 0.4, 0.5, 0.5, 0.9))               // part of a person
        assertTrue(DetectionFilter.keep("bench", 0.3, 0.5, 0.6, 0.8, 0.5))               // real obstacles unchanged
    }
}
