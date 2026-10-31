package run.basir.engine

/**
 * Which ground classes of the segmentation count as runnable, with a memory. Path-like classes always
 * do; road, grass, earth, field and sand only when they are a real part of what is under the runner's
 * feet. The model may name the same surface differently from one frame to the next: on an athletics
 * track (owner's dev-mode clip) it called the red tartan "grass" in most frames and "earth" in 15 of 96,
 * and every such frame threw the whole track out, which looked like the way ending 3 m ahead and gave
 * a stop. So a class that was under the feet stays runnable for [holdMs], or while it still covers
 * [stayShare] of the foot area; it enters only at [enterShare].
 */
class UnderfootClasses(
    private val always: Set<Int> = setOf(3, 11, 52, 91),          // ADE20K: floor, sidewalk, path, dirt track
    private val conditional: List<Int> = listOf(6, 9, 13, 29, 46), // road, grass, earth, field, sand
    private val enterShare: Double = 0.15,
    private val stayShare: Double = 0.03,
    private val holdMs: Long = 2000,
) {
    private val lastSeen = HashMap<Int, Long>()

    /** [footCounts]: pixels per class in the foot area (bottom 20 %, central 30 %); [n]: pixels in it. */
    fun update(footCounts: Map<Int, Int>, n: Int, nowMs: Long): Set<Int> {
        val out = HashSet(always)
        for (c in conditional) {
            val share = if (n > 0) (footCounts[c] ?: 0).toDouble() / n else 0.0
            val admitted = lastSeen[c]
            when {
                share >= enterShare -> { lastSeen[c] = nowMs; out += c }
                admitted != null && share >= stayShare -> { lastSeen[c] = nowMs; out += c }
                admitted != null && nowMs - admitted <= holdMs -> out += c
                else -> lastSeen.remove(c)
            }
        }
        return out
    }

    fun reset() = lastSeen.clear()
}
