package com.basir.run.vision.seg

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.basir.run.vision.depth.openSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import run.basir.engine.ProbabilityMask

/**
 * Walkable ground from SegFormer-B0 trained on ADE20K (NVIDIA Source Code License: research and
 * evaluation use only, see assets/LICENSE_SEGFORMER.txt), exported to ONNX at 256×256 by
 * tools/models/export_segformer.py.
 *
 * Which classes are runnable follows the surface under the runner: path-like classes (floor,
 * sidewalk, path, dirt track) always are; road, grass, earth, field and sand only when they make
 * up a real part of what is under the runner's feet. ADE20K calls park trails "road", so road is
 * kept on a trail but not beside a sidewalk; grass beside a trail is not taken as the way.
 *
 * Measured on 280 human-labelled chest-camera frames (SANPO-Real) and 184 off-road frames (RUGD):
 * corridor IoU 0.74 and 0.84, against 0.25 and 0.31 for the colour-region fallback; with the
 * engine's free-space planner the path stayed fully on walkable ground in 190 of 280 frames
 * against 84 for the current engine (BasirRun_Research/experiment_results).
 */
class WalkableSegmenter(context: Context) {

    private val session = openSession(context, MODEL, threads = 2)
    val ready: Boolean get() = session != null

    private val pixels = IntArray(SIZE * SIZE)
    private val input = FloatBuffer.allocate(3 * SIZE * SIZE)
    /** Which ground classes count, remembered across frames (the model renames surfaces now and then). */
    private val underfoot = run.basir.engine.UnderfootClasses()
    /** Terrain Understanding (engine TerrainZones): zone shares of the last frame, for the surface monitor and the log. */
    private val terrainZones = run.basir.engine.TerrainZones()
    @Volatile var lastTerrain: run.basir.engine.TerrainSummary? = null
        private set

    fun resetMemory() { underfoot.reset(); lastTerrain = null }

    /** Walkable probability (0 or 1 per cell) resampled to [outW]×[outH] of the upright frame, or null. */
    fun walkable(upright: Bitmap, outW: Int, outH: Int): ProbabilityMask? {
        val s = session ?: return null
        val scaled = Bitmap.createScaledBitmap(upright, SIZE, SIZE, true)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        input.rewind()
        for (i in 0 until plane) {
            val p = pixels[i]
            input.put(i, (((p shr 16) and 0xff) / 255f - MEAN[0]) / STD[0])
            input.put(plane + i, (((p shr 8) and 0xff) / 255f - MEAN[1]) / STD[1])
            input.put(2 * plane + i, ((p and 0xff) / 255f - MEAN[2]) / STD[2])
        }
        return try {
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val shape = out.info.shape // 1, classes, h, w
                    val classes = shape[1].toInt()
                    val h = shape[2].toInt()
                    val w = shape[3].toInt()
                    val logits = FloatArray(classes * h * w).also { out.floatBuffer.get(it) }
                    val label = IntArray(w * h)
                    val margin = FloatArray(w * h)
                    for (k in 0 until w * h) {
                        var best = 0
                        var bestV = logits[k]
                        var second = Float.NEGATIVE_INFINITY
                        for (c in 1 until classes) {
                            val v = logits[c * w * h + k]
                            if (v > bestV) { second = bestV; bestV = v; best = c } else if (v > second) second = v
                        }
                        label[k] = best
                        // softmax top-1 minus top-2 probability: (1 - e^(l2 - l1)) / sum_c e^(lc - l1)
                        var sum = 0.0
                        for (c in 0 until classes) sum += kotlin.math.exp((logits[c * w * h + k] - bestV).toDouble())
                        margin[k] = ((1.0 - kotlin.math.exp((second - bestV).toDouble())) / sum).toFloat()
                    }
                    val runnable = runnableClasses(label, w, h, underfoot, android.os.SystemClock.elapsedRealtime())
                    lastTerrain = terrainZones.summarize(terrainZones.classify(label, margin, BooleanArray(w * h) { label[it] in runnable }, w, h), w, h)
                    nearest(FloatArray(w * h) { if (label[it] in runnable) 1f else 0f }, w, h, outW, outH)
                }
            }
        } catch (e: Exception) {
            Log.w("WalkableSegmenter", "Segmentation failed", e)
            null
        }
    }

    fun close() = session?.close()

    companion object {
        const val MODEL = "walkable_segformer_b0_ade_256.onnx"
        private const val SIZE = 256
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        // Ground classes and the under-the-feet rule (with memory): run.basir.engine.UnderfootClasses

        /** The runnable classes for this frame: the path-like ones plus what the runner stands on (with memory). */
        fun runnableClasses(label: IntArray, w: Int, h: Int, memory: run.basir.engine.UnderfootClasses, nowMs: Long): Set<Int> {
            val counts = HashMap<Int, Int>()
            var n = 0
            for (y in (h * 0.8).toInt() until h) for (x in (w * 0.35).toInt() until (w * 0.65).toInt()) {
                counts.merge(label[y * w + x], 1, Int::plus)
                n++
            }
            return memory.update(counts, n, nowMs)
        }
    }
}

/**
 * Guide-line segmentation from Google's Project Guideline (Apache 2.0): DeepLabV3+ with a
 * MobileNetV3-small backbone trained on purple running lines, converted from its TFLite file to
 * ONNX (pixel agreement with the original 99.97 %). The whole upright frame is squashed to
 * 513×513, which mapped back gives IoU 0.94 on the project's labelled frame against 0.27–0.71
 * (depending on tilt) for [run.basir.engine.ContrastLineDetector]. It only knows the purple
 * line; other lines are left to the colour-contrast detector.
 */
class GuidelineSegmenter(context: Context) {

    private val session = openSession(context, MODEL, threads = 2)
    val ready: Boolean get() = session != null

    private val pixels = IntArray(SIZE * SIZE)
    private val input: ByteBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())

    /** Line probability resampled to [outW]×[outH] of the upright frame, or null. */
    fun line(upright: Bitmap, outW: Int, outH: Int): ProbabilityMask? {
        val s = session ?: return null
        val scaled = Bitmap.createScaledBitmap(upright, SIZE, SIZE, true)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in pixels) {
            input.put(((p shr 16) and 0xff).toByte())
            input.put(((p shr 8) and 0xff).toByte())
            input.put((p and 0xff).toByte())
        }
        input.rewind()
        return try {
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input, longArrayOf(1, SIZE.toLong(), SIZE.toLong(), 3), ai.onnxruntime.OnnxJavaType.UINT8).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val out = (result.get(0) as OnnxTensor).floatBuffer // 1, 513, 513, 2: background, line
                    val prob = FloatArray(SIZE * SIZE)
                    for (k in 0 until SIZE * SIZE) prob[k] = out.get(2 * k + 1)
                    average(prob, SIZE, SIZE, outW, outH)
                }
            }
        } catch (e: Exception) {
            Log.w("GuidelineSegmenter", "Segmentation failed", e)
            null
        }
    }

    fun close() = session?.close()

    companion object {
        const val MODEL = "guideline_segmentation.onnx"
        private const val SIZE = 513
    }
}

/** Nearest-cell resampling of a class map (keeps 0/1 values). */
internal fun nearest(src: FloatArray, w: Int, h: Int, outW: Int, outH: Int): ProbabilityMask {
    val mask = ProbabilityMask(outW, outH)
    for (y in 0 until outH) {
        val sy = ((y + 0.5) * h / outH).toInt().coerceIn(0, h - 1)
        for (x in 0 until outW) mask[x, y] = src[sy * w + ((x + 0.5) * w / outW).toInt().coerceIn(0, w - 1)]
    }
    return mask
}

/** Box-average downsampling of a probability map, so a thin line keeps its weight. */
internal fun average(src: FloatArray, w: Int, h: Int, outW: Int, outH: Int): ProbabilityMask {
    val mask = ProbabilityMask(outW, outH)
    for (y in 0 until outH) {
        val y0 = y * h / outH
        val y1 = maxOf(y0 + 1, (y + 1) * h / outH)
        for (x in 0 until outW) {
            val x0 = x * w / outW
            val x1 = maxOf(x0 + 1, (x + 1) * w / outW)
            var sum = 0f
            for (yy in y0 until y1) for (xx in x0 until x1) sum += src[yy * w + xx]
            mask[x, y] = sum / ((y1 - y0) * (x1 - x0))
        }
    }
    return mask
}
