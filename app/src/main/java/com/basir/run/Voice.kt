package com.basir.run

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.basir.run.engine.Phrase

/**
 * Speaks app phrases. A recording in assets/voice/<phrase id>.<mp3|ogg|wav|m4a> is played when
 * present; otherwise the phone's Arabic text to speech reads the phrase. Numbers (distance,
 * time) always use text to speech.
 */
class Voice(context: Context) {

    private val app = context.applicationContext
    private val speaker = Speaker(app)
    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null

    private val recordings: Map<String, String> = try {
        app.assets.list("voice").orEmpty()
            .filter { it.substringAfterLast('.').lowercase() in AUDIO_TYPES }
            .associate { it.substringBeforeLast('.') to "voice/$it" }
    } catch (e: Exception) {
        emptyMap()
    }

    val recordedCount: Int get() = recordings.size

    fun say(phrase: Phrase, interrupt: Boolean = false) {
        val asset = recordings[phrase.id]
        if (interrupt) stopAll()
        if (asset == null) {
            speaker.say(phrase.ar, interrupt)
        } else {
            queue.addLast(asset)
            if (player == null) playNext()
        }
    }

    fun sayText(text: String, interrupt: Boolean = false) {
        if (interrupt) stopAll()
        speaker.say(text, interrupt)
    }

    fun stopAll() {
        queue.clear()
        player?.release()
        player = null
        speaker.stop()
    }

    private fun playNext() {
        val asset = queue.removeFirstOrNull() ?: return
        try {
            val fd = app.assets.openFd(asset)
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
            Log.w("Voice", "Could not play $asset", e)
            player = null
            playNext()
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
