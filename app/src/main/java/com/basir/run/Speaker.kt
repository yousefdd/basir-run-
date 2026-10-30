package com.basir.run

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

// Arabic text to speech, for the few texts that have no recording (names, check details).
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Pair<String, () -> Unit>>()
    private val callbacks = HashMap<String, () -> Unit>()
    private var ready = false
    private var counter = 0

    /** null until the engine has started; false when the phone has no Arabic voice. */
    @Volatile var arabicAvailable: Boolean? = null
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            arabicAvailable = false
            flushPending()
            return
        }
        val lang = tts.setLanguage(Locale.forLanguageTag("ar"))
        arabicAvailable = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = Unit
        })
        ready = true
        flushPending()
    }

    private fun flushPending() {
        val items = pending.toList()
        pending.clear()
        items.forEach { (text, done) -> speak(text, done) }
    }

    private fun finish(id: String) {
        val done = synchronized(callbacks) { callbacks.remove(id) } ?: return
        main.post(done)
    }

    /** Speaks [text] and calls [done] on the main thread when it has finished (or cannot be spoken). */
    fun speak(text: String, done: () -> Unit) {
        if (arabicAvailable == false) {
            main.post(done)
            return
        }
        if (!ready) {
            pending.add(text to done)
            return
        }
        val id = "u${counter++}"
        synchronized(callbacks) { callbacks[id] = done }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
    }

    fun stop() {
        pending.clear()
        synchronized(callbacks) { callbacks.clear() }
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
