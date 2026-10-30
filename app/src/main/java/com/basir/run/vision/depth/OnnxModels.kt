package com.basir.run.vision.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.basir.run.vision.Detection
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** Opens an ONNX model from assets; null if the file is missing or cannot be loaded. */
private fun openSession(context: Context, asset: String, threads: Int): OrtSession? = try {
    val bytes = context.assets.open(asset).use { it.readBytes() }
    val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    OrtEnvironment.getEnvironment().createSession(bytes, options)
} catch (e: Exception) {
    Log.w("OnnxModels", "Model $asset unavailable", e)
    null
}

/**
 * Depth Anything V2 Small (Apache 2.0), as used by the blind-navigation tool: relative
 * disparity per pixel, larger = closer. Input is the upright frame stretched to a square whose
 * side is a multiple of 14 (the ViT patch), with ImageNet normalisation, in NCHW order.
 */
class DepthEstimator(context: Context) {

    private val session = openSession(context, MODEL, threads = 2)
    val ready: Boolean get() = session != null

    /** Square input side: fixed by the model when it says so, otherwise [PREFERRED_SIZE]. */
    private val size: Int = session?.let { s ->
        val shape = (s.inputInfo.values.first().info as TensorInfo).shape
        if (shape.size == 4 && shape[2] > 0) shape[2].toInt() else PREFERRED_SIZE
    } ?: PREFERRED_SIZE
    private val pixels = IntArray(size * size)
    private val input = FloatBuffer.allocate(3 * size * size)

    /** Disparity resampled to [outW]×[outH] (row-major), or null if the model is unavailable or fails. */
    fun disparity(upright: Bitmap, outW: Int, outH: Int): FloatArray? {
        val s = session ?: return null
        val scaled = Bitmap.createScaledBitmap(upright, size, size, true)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val plane = size * size
        input.rewind()
        for (i in 0 until plane) {
            val p = pixels[i]
            input.put(i, (((p shr 16) and 0xff) / 255f - MEAN[0]) / STD[0])
            input.put(plane + i, (((p shr 8) and 0xff) / 255f - MEAN[1]) / STD[1])
            input.put(2 * plane + i, ((p and 0xff) / 255f - MEAN[2]) / STD[2])
        }
        return try {
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val shape = out.info.shape
                    val h = shape[shape.size - 2].toInt()
                    val w = shape[shape.size - 1].toInt()
                    val buf = out.floatBuffer
                    val raw = FloatArray(w * h).also { buf.get(it) }
                    resample(raw, w, h, outW, outH)
                }
            }
        } catch (e: Exception) {
            Log.w("DepthEstimator", "Depth inference failed", e)
            null
        }
    }

    fun close() = session?.close()

    companion object {
        const val MODEL = "depth_anything_v2_small.onnx"
        /** 18 × 14. Smaller than the reference 518 to keep phone latency down; measured on device, not assumed. */
        const val PREFERRED_SIZE = 252
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** Bilinear resampling of a row-major map. */
        fun resample(src: FloatArray, w: Int, h: Int, outW: Int, outH: Int): FloatArray {
            val out = FloatArray(outW * outH)
            for (y in 0 until outH) {
                val sy = ((y + 0.5f) * h / outH - 0.5f).coerceIn(0f, h - 1f)
                val y0 = sy.toInt()
                val y1 = min(y0 + 1, h - 1)
                val fy = sy - y0
                for (x in 0 until outW) {
                    val sx = ((x + 0.5f) * w / outW - 0.5f).coerceIn(0f, w - 1f)
                    val x0 = sx.toInt()
                    val x1 = min(x0 + 1, w - 1)
                    val fx = sx - x0
                    val top = src[y0 * w + x0] * (1 - fx) + src[y0 * w + x1] * fx
                    val bottom = src[y1 * w + x0] * (1 - fx) + src[y1 * w + x1] * fx
                    out[y * outW + x] = top * (1 - fy) + bottom * fy
                }
            }
            return out
        }
    }
}

/**
 * D-FINE-N object detector (Apache 2.0), 80 COCO classes, exported from the official weights by
 * tools/models/export_dfine.py. It replaces the blind-navigation tool's YOLO26n (AGPL-3.0) and
 * reads the same kind of DETR output: logits [1,300,80] (sigmoid) and pred_boxes [1,300,4]
 * (cx, cy, w, h in 0..1 of the square input). Input 640×640, aspect kept with black padding as
 * in D-FINE's own ONNX demo, values / 255. An end-to-end [1,N,6] export is also understood.
 */
class ObjectDetectorOnnx(context: Context) {

    private val session = openSession(context, MODEL, threads = 2)
    private val names: Map<Int, String> = try {
        val json = org.json.JSONObject(context.assets.open(NAMES).bufferedReader().use { it.readText() })
        json.keys().asSequence().associate { it.toInt() to json.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }
    val ready: Boolean get() = session != null && names.isNotEmpty()

    private val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(SIZE * SIZE)
    private val input = FloatBuffer.allocate(3 * SIZE * SIZE)

    /** Boxes as fractions of the upright frame. */
    fun detect(upright: Bitmap, minScore: Float = MIN_SCORE): List<Detection> {
        val s = session ?: return emptyList()
        val w0 = upright.width.toFloat()
        val h0 = upright.height.toFloat()
        val r = min(SIZE / h0, SIZE / w0)
        val nw = (w0 * r).roundToInt()
        val nh = (h0 * r).roundToInt()
        val left = (SIZE - nw) / 2
        val top = (SIZE - nh) / 2
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(upright, null, RectF(left.toFloat(), top.toFloat(), (left + nw).toFloat(), (top + nh).toFloat()), paint)
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            input.put(i, ((p shr 16) and 0xff) / 255f)
            input.put(plane + i, ((p shr 8) and 0xff) / 255f)
            input.put(2 * plane + i, (p and 0xff) / 255f)
        }
        input.rewind()
        val boxes = try {
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val first = result.get(0) as OnnxTensor
                    if (result.size() >= 2 && (result.get(1) as OnnxTensor).info.shape.last() == 4L) {
                        decodeDetr(first, result.get(1) as OnnxTensor, minScore)
                    } else if (first.info.shape.last() == 6L) {
                        decodeEndToEnd(first, minScore)
                    } else {
                        emptyList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("ObjectDetectorOnnx", "Detection failed", e)
            emptyList()
        }
        return boxes.mapNotNull { (cls, score, b) ->
            val x1 = ((b[0] - left) / r).coerceIn(0f, w0)
            val y1 = ((b[1] - top) / r).coerceIn(0f, h0)
            val x2 = ((b[2] - left) / r).coerceIn(0f, w0)
            val y2 = ((b[3] - top) / r).coerceIn(0f, h0)
            if (x2 - x1 < 2 || y2 - y1 < 2) null
            else Detection(names[cls] ?: cls.toString(), score, x1 / w0, y1 / h0, x2 / w0, y2 / h0)
        }
    }

    private data class Raw(val cls: Int, val score: Float, val box: FloatArray)

    private fun decodeDetr(logits: OnnxTensor, boxes: OnnxTensor, minScore: Float): List<Raw> {
        val shape = logits.info.shape
        val n = shape[1].toInt()
        val c = shape[2].toInt()
        val l = FloatArray(n * c).also { logits.floatBuffer.get(it) }
        val b = FloatArray(n * 4).also { boxes.floatBuffer.get(it) }
        val out = ArrayList<Raw>()
        for (i in 0 until n) {
            var best = 0
            for (k in 1 until c) if (l[i * c + k] > l[i * c + best]) best = k
            val score = 1f / (1f + exp(-l[i * c + best]))
            if (score < minScore) continue
            val cx = b[i * 4] * SIZE
            val cy = b[i * 4 + 1] * SIZE
            val bw = b[i * 4 + 2] * SIZE
            val bh = b[i * 4 + 3] * SIZE
            out += Raw(best, score, floatArrayOf(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2))
        }
        return out
    }

    private fun decodeEndToEnd(t: OnnxTensor, minScore: Float): List<Raw> {
        val n = t.info.shape[1].toInt()
        val v = FloatArray(n * 6).also { t.floatBuffer.get(it) }
        return (0 until n).mapNotNull { i ->
            val score = v[i * 6 + 4]
            if (score < minScore) null else Raw(v[i * 6 + 5].toInt(), score, floatArrayOf(v[i * 6], v[i * 6 + 1], v[i * 6 + 2], v[i * 6 + 3]))
        }
    }

    fun close() = session?.close()

    companion object {
        const val MODEL = "dfine_n_coco.onnx"
        const val NAMES = "coco80_names.json"
        const val SIZE = 640
        const val MIN_SCORE = 0.4f
    }
}
