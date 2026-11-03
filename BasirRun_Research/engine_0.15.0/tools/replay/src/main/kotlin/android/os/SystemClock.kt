package android.os

/** Replay stand-in for Android's SystemClock: time advances only when the replay says so. */
object SystemClock {
    @Volatile var nowMs: Long = 0L
    fun elapsedRealtime(): Long = nowMs
}
