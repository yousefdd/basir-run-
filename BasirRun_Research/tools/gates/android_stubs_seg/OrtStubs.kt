@file:Suppress("unused", "UNUSED_PARAMETER")
package ai.onnxruntime
import java.nio.ByteBuffer
import java.nio.FloatBuffer
class OrtEnvironment { companion object { @JvmStatic fun getEnvironment() = OrtEnvironment() } }
enum class OnnxJavaType { UINT8, FLOAT }
class TensorInfo(val shape: LongArray)
class OnnxTensor(val info: TensorInfo, val floatBuffer: FloatBuffer) : AutoCloseable {
    override fun close() {}
    companion object {
        @JvmStatic fun createTensor(e: OrtEnvironment, b: FloatBuffer, shape: LongArray) = OnnxTensor(TensorInfo(shape), b)
        @JvmStatic fun createTensor(e: OrtEnvironment, b: ByteBuffer, shape: LongArray, t: OnnxJavaType) = OnnxTensor(TensorInfo(shape), FloatBuffer.allocate(1))
    }
}
class OrtSession : AutoCloseable {
    val inputNames: Set<String> = setOf("x")
    fun run(m: Map<String, OnnxTensor>): Result = Result()
    override fun close() {}
    class Result : AutoCloseable { fun get(i: Int): Any = OnnxTensor(TensorInfo(longArrayOf()), FloatBuffer.allocate(1)); override fun close() {} }
}
