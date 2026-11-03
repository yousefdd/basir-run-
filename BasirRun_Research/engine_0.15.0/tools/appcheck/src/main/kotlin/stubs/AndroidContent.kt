@file:Suppress("unused", "UNUSED_PARAMETER")
package android.content

import java.io.InputStream

open class Context {
    open val applicationContext: Context get() = this
    open val assets: AssetManager get() = AssetManager()
    open fun <T> getSystemService(c: Class<T>): T = throw UnsupportedOperationException()
}
class AssetManager { fun open(name: String): InputStream = throw UnsupportedOperationException() }
