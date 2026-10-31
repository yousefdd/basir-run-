@file:Suppress("unused", "UNUSED_PARAMETER")
package android.content
open class Context { open val applicationContext: Context get() = this; fun <T> getSystemService(c: Class<T>): T? = null }
