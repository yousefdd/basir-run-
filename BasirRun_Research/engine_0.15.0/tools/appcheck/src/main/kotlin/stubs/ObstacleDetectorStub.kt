@file:Suppress("unused", "UNUSED_PARAMETER")
package com.basir.run.vision

// Stand-in for app_vision/ObstacleDetector.kt (MediaPipe EfficientDet), with the members GuidancePipeline uses.
class ObstacleDetector(context: android.content.Context) {
    val ready: Boolean get() = false
    fun detect(sensorBitmap: android.graphics.Bitmap, rotationDegrees: Int): List<Detection> = emptyList()
    fun close() {}
}
