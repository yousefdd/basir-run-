package run.basir.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Round 7 (owner's request): running beside the lines of sports grounds (football touchline, track or court edge). */
class SportsLineTest {
    private fun line(lateral: Double, kappa: Double = 0.0, near: Double = 0.5, far: Double = 10.0, continuity: Double = 1.0, score: Double = 3.0): LineCandidate {
        val curve = PathCurve(doubleArrayOf(lateral, 0.0, kappa / 2), near, far)
        val segs = curve.sample(0.5).map { LineSegment(0.0, 0.0, 4.0, 0.9, it, 0.1) }
        return LineCandidate(segs, curve, far - near, continuity, 0.9, score)
    }

    private fun run(p: IntelligentPathPlanner, lines: List<LineCandidate>, frames: Int = 8): PathDecision {
        var d: PathDecision? = null
        repeat(frames) { d = p.update(FrameInput(lines, null, null, emptyList(), 1.0)) }
        return d!!
    }

    private fun planner(cfg: PlannerConfig = PlannerConfig(modeHoldFrames = 1)) = IntelligentPathPlanner(cfg)

    @Test
    fun aStrongTouchlineBesideTheRunnerIsRunAlongsideAtTheDistanceFirstSeen() {
        val d = run(planner(), listOf(line(1.4)))
        assertEquals(PathMode.LINE_PARALLEL, d.mode)
        val p = d.path!!
        assertEquals(0.0, p.lateralAt(1.0), 0.1, "the runner keeps their distance to the line, they are not pulled onto it")
        assertEquals(0.0, p.lateralAt(5.0), 0.1)
    }

    @Test
    fun theParallelPathFollowsTheLinesBend() {
        val kappa = 0.04 // the bend of a 400 m track (radius ~ 25-36 m)
        val d = run(planner(), listOf(line(1.4, kappa)))
        assertEquals(PathMode.LINE_PARALLEL, d.mode)
        assertEquals(kappa / 2 * 16, d.path!!.lateralAt(4.0), 0.08, "bend not followed")
    }

    @Test
    fun aWeakOrShortLineFarAwayIsNotFollowed() {
        assertNotEquals(PathMode.LINE_PARALLEL, run(planner(), listOf(line(1.4, far = 2.6))).mode, "2 m long line followed")
        assertNotEquals(PathMode.LINE_PARALLEL, run(planner(), listOf(line(1.4, continuity = 0.4))).mode, "broken line followed")
        assertNotEquals(PathMode.LINE_PARALLEL, run(planner(), listOf(line(1.4, near = 3.0))).mode, "line starting 3 m ahead followed")
        assertNotEquals(PathMode.LINE_PARALLEL, run(planner(PlannerConfig(modeHoldFrames = 1, followDistantLines = false)), listOf(line(1.4))).mode,
            "switch off = 0.13.0 behaviour")
    }

    @Test
    fun noJumpToAnotherParallelLineWithAHigherScore() {
        val p = planner()
        run(p, listOf(line(0.9)))
        // another marking appears further out and scores higher (listed first): the locked line is kept
        val d = run(p, listOf(line(1.7, score = 6.0), line(0.9, score = 2.0)), frames = 10)
        assertEquals(PathMode.LINE_PARALLEL, d.mode)
        assertEquals(0.0, d.path!!.lateralAt(2.0), 0.1, "jumped to the other line")
    }

    @Test
    fun aLaneBetweenTwoLinesStillWinsOverRunningBesideOne() {
        val d = run(planner(), listOf(line(-0.6), line(0.62)))
        assertEquals(PathMode.LANE_CENTER, d.mode)
        assertTrue(kotlin.math.abs(d.path!!.lateralAt(2.0)) < 0.1)
    }
}
