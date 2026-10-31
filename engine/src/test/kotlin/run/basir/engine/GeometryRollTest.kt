package run.basir.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** A phone that rocks sideways on the chest must not bend or shift the ground it sees. */
class GeometryRollTest {

    @Test
    fun `roll is undone before projecting to the ground`() {
        val level = CameraModel(240, 180, 60.0, 1.3, 12.0)
        val rolled = level.copy(rollDeg = 8.0)
        for (f in listOf(1.5, 3.0, 6.0)) for (l in listOf(-1.0, 0.0, 0.7)) {
            val (u, v) = rolled.groundToImage(GroundPoint(f, l))!!
            val back = rolled.imageToGround(u, v)!!
            assertTrue(abs(back.forward - f) < 1e-6 && abs(back.lateral - l) < 1e-6, "round trip at $f, $l: $back")
        }
    }

    @Test
    fun `a straight line seen by a rolled phone stays straight, and the level model would bend it`() {
        val rolled = CameraModel(240, 180, 60.0, 1.3, 12.0, rollDeg = 8.0)
        val ignoring = rolled.copy(rollDeg = 0.0)
        val pixels = listOf(2.0, 4.0, 7.0).map { rolled.groundToImage(GroundPoint(it, 0.3))!! }
        val withRoll = pixels.map { (u, v) -> rolled.imageToGround(u, v)!!.lateral }
        val without = pixels.map { (u, v) -> ignoring.imageToGround(u, v)!!.lateral }
        assertTrue(withRoll.all { abs(it - 0.3) < 1e-6 }, "with roll: $withRoll")
        // Ignoring 8° of roll slants the line: about 15 cm of drift between 2 and 7 m
        assertTrue(without.maxOrNull()!! - without.minOrNull()!! > 0.1, "without roll: $without")
    }
}
