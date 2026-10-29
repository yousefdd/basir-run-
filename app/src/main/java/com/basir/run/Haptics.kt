package com.basir.run

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {

    fun pulse(context: Context, pattern: LongArray = longArrayOf(0, 120, 90, 120)) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // timings alternate off/on starting with a delay; amplitudes 0-255 per segment, used where the motor supports it.
    fun wave(context: Context, timings: LongArray, amplitudes: IntArray) {
        val vibrator = vibrator(context) ?: return
        val effect = if (vibrator.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
        else VibrationEffect.createWaveform(timings, -1)
        vibrator.vibrate(effect)
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
