package com.basir.run

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

// Arabic text to speech. Phrases said before the engine is ready are queued.
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val pending = mutableListOf<String>()
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.forLanguageTag("ar")
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
        pending.forEach { say(it) }
        pending.clear()
    }

    /** interrupt drops whatever is being said, for stop instructions. */
    fun say(text: String, interrupt: Boolean = false) {
        if (!ready) {
            if (interrupt) pending.clear()
            pending.add(text)
            return
        }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
