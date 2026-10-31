@file:Suppress("unused", "UNUSED_PARAMETER")
package android.graphics
class Bitmap(val width: Int, val height: Int) { companion object {
    @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix, filter: Boolean): Bitmap = src } }
class Matrix { fun postRotate(d: Float): Boolean = true }
