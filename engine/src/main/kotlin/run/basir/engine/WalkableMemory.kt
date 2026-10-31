package run.basir.engine

/**
 * Short memory of the walkable-ground mask: an exponential average over the last frames, so a
 * single dark or shadowed frame in which the model misses the ground does not make the way ahead
 * look blocked. With [alpha] = 0.4 a cell stays walkable through one missed frame and drops after
 * two; a new obstacle shows up within two frames (about 0.15 s at 15 fps).
 */
class WalkableMemory(private val alpha: Float = 0.4f) {
    private var avg: FloatArray? = null
    private var w = 0
    private var h = 0

    fun reset() {
        avg = null
    }

    fun update(mask: ProbabilityMask): ProbabilityMask {
        val a = avg
        if (a == null || mask.width != w || mask.height != h) {
            w = mask.width; h = mask.height
            avg = mask.data.copyOf()
            return mask
        }
        val out = ProbabilityMask(w, h)
        for (k in a.indices) {
            a[k] = (1 - alpha) * a[k] + alpha * mask.data[k]
            out.data[k] = if (a[k] >= 0.5f) 1f else 0f
        }
        return out
    }
}
