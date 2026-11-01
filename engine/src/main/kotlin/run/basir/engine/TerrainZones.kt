package run.basir.engine

/**
 * Terrain Understanding layer (round 4). It turns the walkable-ground model's class map into five
 * kinds of zone, so the engine knows *how sure* it is about the ground and *what kind* of ground it is,
 * instead of a plain yes/no mask:
 *
 *  - [Zone.WALKABLE]   smooth ground (floor, sidewalk, path, road, track) that the walkable rule accepted;
 *  - [Zone.CAUTION]    accepted ground that is natural or loose (grass, earth, field, sand): movable, but
 *                      it can hide holes and roots, so the runner should slow down;
 *  - [Zone.OBSTACLE]   not ground (people, walls, trees, poles, water, vehicles...);
 *  - [Zone.UNCLEAR]    ground classes where the model hesitates (top-2 probability margin below
 *                      [TerrainConfig.unclearMargin]);
 *  - [Zone.UNVERIFIED] ground the walkable rule did not accept, or accepted ground that is not connected
 *                      to the ground under the feet (an island seen through a gap).
 *
 * The camera cannot know friction, wetness or firmness. CAUTION only says "natural or loose surface
 * seen", never "safe". Measured in round 4 (benchmarks/results/terrain_labelled.txt): removing UNCLEAR
 * pixels from the route lowers false-walkable but costs about as much IoU on SANPO. So the zones do
 * **not** cut the route; they feed [SurfaceMonitor] and the developer overlay and log.
 */
enum class Zone { WALKABLE, CAUTION, OBSTACLE, UNCLEAR, UNVERIFIED, NONE }

data class TerrainConfig(
    val unclearMargin: Float = 0.05f,       // top-1 minus top-2 softmax probability
    val footTop: Double = 0.8,               // foot area: bottom 20 % of the map ...
    val footLeft: Double = 0.35,             // ... and the middle 30 %
    val footRight: Double = 0.65,
    val corridorTop: Double = 0.55,          // corridor ahead used for the summary
    val corridorLeft: Double = 0.3,
    val corridorRight: Double = 0.7,
)

/** Zone shares in the corridor ahead and in the foot area (0..1 each). */
data class TerrainSummary(
    val corridor: Map<Zone, Double>,
    val foot: Map<Zone, Double>,
) {
    fun corridorShare(z: Zone) = corridor[z] ?: 0.0
    fun footShare(z: Zone) = foot[z] ?: 0.0
}

/** Which ADE20K classes are which kind of ground. Ids are the 0-based ADE20K-150 indices the model emits. */
object AdeGround {
    /** Hard, even surfaces. 3 floor, 6 road, 11 sidewalk, 52 path, 54 runway, 91 dirt track (race track). */
    val SMOOTH = setOf(3, 6, 11, 52, 54, 91)
    /** Natural or loose surfaces. 9 grass, 13 earth, 29 field, 46 sand, 94 land. */
    val ROUGH = setOf(9, 13, 29, 46, 94)
    /** Not an obstacle and not ground: 2 sky. */
    val IGNORE = setOf(2)
    fun isGround(c: Int) = c in SMOOTH || c in ROUGH
}

class TerrainZones(private val config: TerrainConfig = TerrainConfig()) {

    /**
     * [labels] class per cell (row-major, [w] x [h]); [margin] top-1 minus top-2 probability per cell (or
     * null when the model gives no probabilities); [walkable] the walkable rule's decision per cell.
     */
    fun classify(labels: IntArray, margin: FloatArray?, walkable: BooleanArray, w: Int, h: Int): Array<Zone> {
        require(labels.size == w * h && walkable.size == w * h && (margin == null || margin.size == w * h))
        val z = Array(w * h) { i ->
            val c = labels[i]
            when {
                c in AdeGround.IGNORE -> Zone.NONE
                !AdeGround.isGround(c) -> Zone.OBSTACLE
                margin != null && margin[i] < config.unclearMargin -> Zone.UNCLEAR
                !walkable[i] -> Zone.UNVERIFIED
                c in AdeGround.ROUGH -> Zone.CAUTION
                else -> Zone.WALKABLE
            }
        }
        // accepted ground that cannot be reached from the feet through accepted / unclear ground is unverified
        val reach = BooleanArray(w * h); val queue = ArrayDeque<Int>()
        val passable = { i: Int -> z[i] == Zone.WALKABLE || z[i] == Zone.CAUTION || z[i] == Zone.UNCLEAR }
        val y0 = (h * config.footTop).toInt().coerceIn(0, h - 1)
        val x0 = (w * config.footLeft).toInt(); val x1 = (w * config.footRight).toInt().coerceAtMost(w)
        for (y in y0 until h) for (x in x0 until x1) { val i = y * w + x; if (passable(i) && !reach[i]) { reach[i] = true; queue.add(i) } }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst(); val x = i % w; val y = i / w
            for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx !in 0 until w || ny !in 0 until h) continue
                val j = ny * w + nx
                if (!reach[j] && passable(j)) { reach[j] = true; queue.add(j) }
            }
        }
        for (i in z.indices) if ((z[i] == Zone.WALKABLE || z[i] == Zone.CAUTION) && !reach[i]) z[i] = Zone.UNVERIFIED
        return z
    }

    fun summarize(zones: Array<Zone>, w: Int, h: Int): TerrainSummary {
        fun shares(t: Double, l: Double, r: Double): Map<Zone, Double> {
            val counts = IntArray(Zone.values().size); var n = 0
            for (y in (h * t).toInt() until h) for (x in (w * l).toInt() until (w * r).toInt().coerceAtMost(w)) { counts[zones[y * w + x].ordinal]++; n++ }
            return Zone.values().associateWith { if (n == 0) 0.0 else counts[it.ordinal].toDouble() / n }
        }
        return TerrainSummary(shares(config.corridorTop, config.corridorLeft, config.corridorRight), shares(config.footTop, config.footLeft, config.footRight))
    }
}

data class SurfaceConfig(
    val roughEnter: Double = 0.5,           // CAUTION share of the foot area that counts as a rough frame
    val enterHoldMs: Long = 1500,           // rough frames must add up to this (since the last smooth frame) before the cue
    val smoothBeforeMs: Long = 3000,        // the smooth run before must have lasted this long
    val flickerMs: Long = 600,              // rough frames adding up to less than this between smooth frames are flicker
    val staleMs: Long = 3000,               // the first rough frame must come within this of the last smooth frame
    val minGapMs: Long = 30_000,            // at most one surface cue per 30 s
)

/**
 * Watches the ground under the feet and says once when the runner moves from smooth to natural/loose
 * ground (asphalt → dirt trail, track → grass), so they can slow down. It keeps the last *confirmed*
 * surface: frames that are neither smooth nor rough (unclear, a shadow, an obstacle at the feet) change
 * nothing, so a flicker of the segmentation neither ends the smooth run nor restarts the rough one
 * (round 4, forest clip: 0.8 s of unclear frames in the middle of the asphalt → dirt change hid it).
 * No cue for a short patch, none without a smooth run just before, at most one per [SurfaceConfig.minGapMs].
 */
class SurfaceMonitor(private val config: SurfaceConfig = SurfaceConfig()) {
    private var smoothStart = 0L
    private var smoothLast: Long? = null
    private var roughMs = 0L                 // rough time since the last smooth frame
    private var firstRough: Long? = null     // first rough frame since the last smooth frame
    private var prev: Long? = null
    private var onRough = false
    private var lastCue = Long.MIN_VALUE / 2

    /** Returns true when the "rough surface" caution should be given now. */
    fun update(summary: TerrainSummary, nowMs: Long): Boolean {
        val dt = prev?.let { (nowMs - it).coerceIn(0L, 500L) } ?: 0L
        prev = nowMs
        val rough = summary.footShare(Zone.CAUTION) >= config.roughEnter
        val smooth = !rough && summary.footShare(Zone.WALKABLE) >= 1.0 - config.roughEnter
        if (smooth) {
            // a long rough stretch ended: a new smooth run starts; a short one was flicker inside the run
            if (smoothLast == null || roughMs >= config.flickerMs) smoothStart = nowMs
            if (roughMs >= config.flickerMs) onRough = false
            smoothLast = nowMs; roughMs = 0L; firstRough = null
            return false
        }
        if (!rough) return false
        if (firstRough == null) firstRough = nowMs
        roughMs += dt
        if (onRough || roughMs < config.enterHoldMs) return false
        onRough = true
        val sl = smoothLast ?: return false
        val smoothRun = sl - smoothStart
        if (firstRough!! - sl <= config.staleMs && smoothRun >= config.smoothBeforeMs && nowMs - lastCue >= config.minGapMs) {
            lastCue = nowMs; return true
        }
        return false
    }
}
