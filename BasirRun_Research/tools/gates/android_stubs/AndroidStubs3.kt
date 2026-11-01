@file:Suppress("unused", "UNUSED_PARAMETER")
package android.graphics
class Bitmap(val width: Int, val height: Int) { fun getPixels(p: IntArray, o: Int, st: Int, x: Int, y: Int, w: Int, h: Int) {}
  companion object {
    @JvmStatic fun createScaledBitmap(b: Bitmap, w: Int, h: Int, f: Boolean): Bitmap = Bitmap(w, h)
    @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix, filter: Boolean): Bitmap = src } }
class Matrix { fun postRotate(d: Float): Boolean = true }
