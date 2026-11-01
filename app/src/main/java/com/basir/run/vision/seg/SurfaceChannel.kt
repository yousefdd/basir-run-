package com.basir.run.vision.seg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import run.basir.engine.ProbabilityMask

/** What the surface models made of one frame, at the line engine's resolution (upright frame). */
class SurfaceResult(
    val frameTimeMs: Long,
    /** Walkable ground, 1 per walkable cell; null when the model is missing or failed. */
    val walkable: ProbabilityMask?,
    /** Guide-line probability; null when the model is missing or failed. */
    val line: ProbabilityMask?,
    val walkableMs: Long,
    val lineMs: Long,
) {
    /** Share of walkable cells in the corridor just ahead (bottom 45 %, central 40 % of the frame). */
    fun walkableAheadShare(): Float {
        val m = walkable ?: return 0f
        var n = 0
        var w = 0
        for (y in (m.height * 0.55).toInt() until m.height) for (x in (m.width * 0.3).toInt() until (m.width * 0.7).toInt()) {
            n++
            if (m[x, y] >= 0.5f) w++
        }
        return if (n == 0) 0f else w.toFloat() / n
    }
}

/**
 * Runs the walkable-ground and guide-line models on a worker thread, away from the camera
 * thread, one frame at a time (frames that arrive while it is busy are skipped). Models load on
 * the same thread, so the app opens without waiting for them.
 */
class SurfaceChannel(context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var walkableModel: WalkableSegmenter? = null
    @Volatile private var lineModel: GuidelineSegmenter? = null

    @Volatile var latest: SurfaceResult? = null
        private set
    @Volatile var loaded = false
        private set

    init {
        val app = context.applicationContext
        executor.execute {
            walkableModel = WalkableSegmenter(app)
            lineModel = GuidelineSegmenter(app)
            loaded = true
        }
    }

    val walkableReady: Boolean get() = walkableModel?.ready == true
    val lineReady: Boolean get() = lineModel?.ready == true
    val idle: Boolean get() = !busy.get()

    fun reset() {
        latest = null
    }

    /** Hands a frame to the worker if it is free; [outW]×[outH] is the size of the masks wanted. */
    fun offer(sensorBitmap: Bitmap, rotationDegrees: Int, frameTimeMs: Long, outW: Int, outH: Int): Boolean {
        if (!loaded || (!walkableReady && !lineReady)) return false
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val upright = if (rotationDegrees == 0) sensorBitmap else Bitmap.createBitmap(
                    sensorBitmap, 0, 0, sensorBitmap.width, sensorBitmap.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
                )
                val t0 = SystemClock.elapsedRealtime()
                val walk = walkableModel?.takeIf { it.ready }?.walkable(upright, outW, outH)
                val t1 = SystemClock.elapsedRealtime()
                val line = lineModel?.takeIf { it.ready }?.line(upright, outW, outH)
                val t2 = SystemClock.elapsedRealtime()
                latest = SurfaceResult(frameTimeMs, walk, line, t1 - t0, t2 - t1)
            } catch (e: Exception) {
                Log.w("SurfaceChannel", "Surface analysis failed", e)
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    fun close() {
        executor.execute {
            walkableModel?.close()
            lineModel?.close()
        }
        executor.shutdown()
    }
}
