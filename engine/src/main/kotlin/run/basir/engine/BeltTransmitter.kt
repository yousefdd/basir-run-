package run.basir.engine

/**
 * The phone side of the belt link, without Android (the app's BLE code only moves the bytes this class hands it).
 *
 * Rules that keep stale commands off the belt:
 *  - one write in flight; while it is in flight a newer frame REPLACES the waiting one (latest wins), so nothing old
 *    piles up in a queue; a waiting guidance frame is never put behind a newer one of the same or higher priority;
 *  - a waiting frame of HIGHER priority is not replaced by a lower one (a stop waiting to be written is not lost to
 *    a correction that comes a moment later);
 *  - a frame that waited longer than its ttl is thrown away before it is written (the belt would drop it anyway);
 *  - heartbeats keep the belt's watchdog fed while nothing vibrates, and are the lowest priority;
 *  - after (re)connecting: CONFIG first, then live frames only; nothing from before the disconnect is resent.
 */
class BeltTransmitter(private var config: BeltConfig = BeltConfig(), private val heartbeatMs: Long = 400) {
    private data class Pending(val bytes: ByteArray, val priority: Int, val madeAt: Long, val ttl: Long)

    private var seq = 0
    private var connected = false
    private var inFlight = false
    private var pending: Pending? = null
    private var lastSentAt = Long.MIN_VALUE / 4
    var sent = 0; private set
    var discardedStale = 0; private set
    var replaced = 0; private set

    val isConnected get() = connected

    fun onConnected(nowMs: Long) {
        connected = true; inFlight = false; pending = null
        offer(BeltProtocol.config(config, next(), nowMs), PRIORITY_CONFIG, nowMs, Long.MAX_VALUE / 4)
    }

    fun onDisconnected() { connected = false; inFlight = false; pending = null }

    fun setConfig(c: BeltConfig, nowMs: Long) {
        config = c
        if (connected) offer(BeltProtocol.config(c, next(), nowMs), PRIORITY_CONFIG, nowMs, Long.MAX_VALUE / 4)
    }

    /** Asks the belt to play [p] now (the scheduler already decided that it should). */
    fun play(p: BeltPattern, nowMs: Long, maxSteps: Int = BeltProtocol.MAX_STEPS) {
        if (!connected) return
        val ttl = BeltProtocol.ttlFor(p.priority)
        offer(BeltProtocol.play(p, next(), nowMs, ttl, maxSteps), p.priority, nowMs, ttl.toLong())
    }

    fun silence(nowMs: Long) {
        if (!connected) return
        offer(BeltProtocol.silence(next(), nowMs), PRIORITY_CONFIG, nowMs, 1000)
    }

    /** Called often (every camera frame or timer tick): adds a heartbeat when the link has been quiet. */
    fun tick(nowMs: Long) {
        if (connected && pending == null && !inFlight && nowMs - lastSentAt >= heartbeatMs) {
            offer(BeltProtocol.heartbeat(next(), nowMs), 0, nowMs, heartbeatMs)
        }
    }

    /** The frame to write now, or null (nothing waiting, or a write still in flight). Marks it in flight. */
    fun takeNext(nowMs: Long): ByteArray? {
        if (!connected || inFlight) return null
        val p = pending ?: return null
        pending = null
        if (nowMs - p.madeAt > p.ttl) { discardedStale++; return null }
        inFlight = true
        lastSentAt = nowMs
        sent++
        return p.bytes
    }

    /** The BLE stack finished the write (or it failed: the frame is simply gone, never retried later). */
    fun onWriteDone() { inFlight = false }

    private fun offer(bytes: ByteArray, priority: Int, nowMs: Long, ttl: Long) {
        val cur = pending
        if (cur != null) {
            val curAlive = nowMs - cur.madeAt <= cur.ttl
            // a waiting stop or config is not pushed out by something less important
            if (curAlive && cur.priority > priority) return
            replaced++
        }
        pending = Pending(bytes, priority, nowMs, ttl)
    }

    private fun next(): Int { seq = (seq + 1) and 0xFFFF; return seq }

    companion object {
        /** Config and silence outrank everything (they change how the belt behaves). */
        const val PRIORITY_CONFIG = 9
    }
}
