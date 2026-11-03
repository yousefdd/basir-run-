@file:Suppress("unused", "UNUSED_PARAMETER")
package android.graphics

class Bitmap private constructor(val width: Int, val height: Int) {
    enum class Config { ARGB_8888 }
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, width: Int, height: Int) {}
    companion object {
        fun createBitmap(width: Int, height: Int, config: Config): Bitmap = Bitmap(width, height)
        fun createBitmap(src: Bitmap, x: Int, y: Int, width: Int, height: Int, m: Matrix?, filter: Boolean): Bitmap = Bitmap(width, height)
        fun createScaledBitmap(src: Bitmap, dstWidth: Int, dstHeight: Int, filter: Boolean): Bitmap = Bitmap(dstWidth, dstHeight)
    }
}
class Matrix { fun postRotate(degrees: Float): Boolean = true }
class Canvas(b: Bitmap) {
    fun drawColor(color: Int) {}
    fun drawBitmap(b: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {}
}
class Rect
class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
class Paint(flags: Int = 0) { companion object { const val FILTER_BITMAP_FLAG = 2 } }
object Color { const val BLACK = -16777216 }
