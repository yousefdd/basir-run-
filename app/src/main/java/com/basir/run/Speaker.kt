package com.basir.run

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

// Arabic voice announcements. Phrases said before the engine is ready are queued.
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val pending = mutableListOf<String>()
    private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.forLanguageTag("ar")
        ready = true
        pending.forEach(::say)
        pending.clear()
    }

    fun say(text: String) {
        if (!ready) {
            pending.add(text)
            return
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() = tts.shutdown()
}
