package com.basir.run.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * On-device object detection with MediaPipe and EfficientDet-Lite0 (Apache 2.0). The model file
 * is downloaded by the build into assets; if it is missing or fails to load, [ready] is false and
 * the readiness check reports it, so a guided run cannot start without obstacle detection.
 */
class ObstacleDetector(context: Context) {

    @Volatile private var detector: ObjectDetector? = null

    /** True once loading has finished, whether or not the model opened. */
    @Volatile var loaded = false
        private set

    // Loaded off the main thread, so the app opens without waiting for the model.
    private val loader = Thread {
        detector = try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(8)
                .setScoreThreshold(ObstacleAssessment.MIN_SCORE)
                .build()
            ObjectDetector.createFromOptions(context.applicationContext, options)
        } catch (e: Exception) {
            Log.w(TAG, "Obstacle model unavailable", e)
            null
        }
        loaded = true
    }.apply { start() }

    val ready: Boolean get() = detector != null

    /** Runs on an upright copy of the frame and returns boxes as upright frame fractions. */
    fun detect(sensorBitmap: Bitmap, rotationDegrees: Int): List<Detection> {
        val d = detector ?: return emptyList()
        val upright = if (rotationDegrees == 0) sensorBitmap else Bitmap.createBitmap(
            sensorBitmap, 0, 0, sensorBitmap.width, sensorBitmap.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
        )
        val w = upright.width.toFloat()
        val h = upright.height.toFloat()
        return try {
            d.detect(BitmapImageBuilder(upright).build()).detections().mapNotNull { det ->
                val category = det.categories().firstOrNull() ?: return@mapNotNull null
                val box = det.boundingBox()
                Detection(category.categoryName(), category.score(), box.left / w, box.top / h, box.right / w, box.bottom / h)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Obstacle detection failed", e)
            emptyList()
        }
    }

    fun close() {
        loader.join(LOAD_WAIT_MS)
        detector?.close()
    }

    companion object {
        private const val TAG = "ObstacleDetector"
        const val MODEL = "efficientdet_lite0.tflite"
        private const val LOAD_WAIT_MS = 3000L
    }
}
