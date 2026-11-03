@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.camera.core

import java.nio.ByteBuffer

interface ImageProxy {
    interface PlaneProxy { val buffer: ByteBuffer; val rowStride: Int; val pixelStride: Int }
    interface ImageInfo { val rotationDegrees: Int }
    val planes: Array<PlaneProxy>
    val width: Int
    val height: Int
    val imageInfo: ImageInfo
    fun toBitmap(): android.graphics.Bitmap
}
