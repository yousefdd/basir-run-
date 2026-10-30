package com.basir.run

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.basir.run.engine.Phrase

/**
 * Everything the app says, in one queue so recordings and synthesized speech never overlap.
 * A phrase is played from its recording, assets/voice/<phrase id>.<mp3|ogg|wav|m4a>, when there
 * is one; otherwise the phone's Arabic text to speech reads it. Button names are phrases too
 * ([Phrase.forText]), so a blind user hears every control in the same recorded voice; only
 * free texts (a contact's name, check details) fall back to text to speech.
 */
class Voice(context: Context) {

    private val app = context.applicationContext
    private val speaker = Speaker(app)
    private val queue = ArrayDeque<Item>()
    private var player: MediaPlayer? = null
    private var speaking = false

    private sealed interface Item {
        data class Asset(val path: String) : Item
        data class Text(val text: String) : Item
    }

    private val recordings: Map<String, String> = try {
        app.assets.list("voice").orEmpty()
            .filter { it.substringAfterLast('.').lowercase() in AUDIO_TYPES }
            .associate { it.substringBeforeLast('.') to "voice/$it" }
    } catch (e: Exception) {
        emptyMap()
    }

    val recordedCount: Int get() = recordings.size

    /** False when the phone has no Arabic text-to-speech voice (null while it starts). */
    val arabicTextToSpeech: Boolean? get() = speaker.arabicAvailable

    fun say(phrase: Phrase, interrupt: Boolean = false) {
        if (interrupt) stopAll()
        enqueue(recordings[phrase.id]?.let { Item.Asset(it) } ?: Item.Text(phrase.ar))
    }

    /** Free text: its recording when the text is a known phrase, else text to speech. */
    fun sayText(text: String, interrupt: Boolean = false) {
        if (interrupt) stopAll()
        val phrase = Phrase.forText(text)
        if (phrase != null) say(phrase) else enqueue(Item.Text(text))
    }

    /** What a single tap on a control says: its name, then (unless [howToUse] is off) how to use it. */
    fun sayControl(spoken: String, enabled: Boolean = true, howToUse: Boolean = true) {
        sayText(spoken, interrupt = true)
        if (!enabled) say(Phrase.Unavailable) else if (howToUse) say(Phrase.HoldToActivate)
    }

    /** A setting that is on or off: its name, its state, and how to change it. */
    fun saySwitch(title: String, on: Boolean, howToChange: Boolean) {
        sayText(title, interrupt = true)
        say(if (on) Phrase.StateOn else Phrase.StateOff)
        if (howToChange) say(Phrase.HoldToToggle)
    }

    fun stopAll() {
        queue.clear()
        player?.release()
        player = null
        speaking = false
        speaker.stop()
    }

    private fun enqueue(item: Item) {
        queue.addLast(item)
        if (player == null && !speaking) playNext()
    }

    private fun playNext() {
        when (val item = queue.removeFirstOrNull() ?: return) {
            is Item.Text -> {
                speaking = true
                speaker.speak(item.text) {
                    if (speaking) {
                        speaking = false
                        playNext()
                    }
                }
            }
            is Item.Asset -> try {
                val fd = app.assets.openFd(item.path)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    fd.close()
                    setOnCompletionListener {
                        it.release()
                        player = null
                        playNext()
                    }
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w("Voice", "Could not play ${item.path}", e)
                player = null
                playNext()
            }
        }
    }

    fun shutdown() {
        stopAll()
        speaker.shutdown()
    }

    companion object {
        private val AUDIO_TYPES = setOf("mp3", "ogg", "wav", "m4a")
    }
}
