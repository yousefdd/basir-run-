package com.basir.run

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

// Plays a short stereo tone panned left or right. This is the cue that will steer
// the runner back to the middle of the lane once the camera lane detection is added.
class ToneGuide {

    // pan: -1 = left ear only, 0 = both, 1 = right ear only.
    fun play(pan: Float, durationMs: Int = 400, frequency: Double = 660.0) {
        Thread {
            val frames = SAMPLE_RATE * durationMs / 1000
            val left = ((1f - pan) / 2f).coerceIn(0f, 1f)
            val right = ((1f + pan) / 2f).coerceIn(0f, 1f)
            val samples = ShortArray(frames * 2)
            for (i in 0 until frames) {
                // 10 ms fade in and out so the tone does not click.
                val fade = minOf(1.0, minOf(i, frames - i) / (SAMPLE_RATE * 0.01))
                val value = sin(2 * PI * frequency * i / SAMPLE_RATE) * fade * 0.6 * Short.MAX_VALUE
                samples[2 * i] = (value * left).toInt().toShort()
                samples[2 * i + 1] = (value * right).toInt().toShort()
            }

            // An audio failure must never take the app down from this thread: the tone is optional.
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
            } catch (e: Exception) {
                return@Thread
            }
            try {
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(durationMs + 100L)
            } catch (e: Exception) {
                // nothing to do: the vibration carries the same cue
            } finally {
                track.release()
            }
        }.start()
    }

    companion object {
        private const val SAMPLE_RATE = 44100
    }
}
