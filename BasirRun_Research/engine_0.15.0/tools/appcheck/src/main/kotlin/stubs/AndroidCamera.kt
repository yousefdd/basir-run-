@file:Suppress("unused", "UNUSED_PARAMETER")
package android.hardware.camera2

class CameraManager {
    val cameraIdList: Array<String> get() = emptyArray()
    fun getCameraCharacteristics(id: String): CameraCharacteristics = CameraCharacteristics()
}
class CameraCharacteristics {
    class Key<T>
    fun <T> get(key: Key<T>): T? = null
    companion object {
        val LENS_FACING = Key<Int>()
        const val LENS_FACING_BACK = 1
        val LENS_INFO_AVAILABLE_FOCAL_LENGTHS = Key<FloatArray>()
        val SENSOR_INFO_PHYSICAL_SIZE = Key<android.util.SizeF>()
    }
}
