package com.basir.run

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.basir.run.engine.Haptic
import com.basir.run.vision.Side

/**
 * Distinct vibration patterns. A phone vibrates as a whole, so steering direction is coded by
 * rhythm: one pulse = move left, two pulses = move right. Strength, length and rate grow with
 * how far the runner has drifted.
 */
object Haptics {

    fun play(context: Context, haptic: Haptic) {
        when (haptic) {
            Haptic.SessionStart -> wave(context, longArrayOf(0, 80, 80, 80), intArrayOf(0, 200, 0, 200))
            Haptic.Calibrated -> wave(context, longArrayOf(0, 300, 100, 80, 100, 300), intArrayOf(0, 220, 0, 220, 0, 220))
            Haptic.Warning -> wave(context, longArrayOf(0, 350, 150, 350), intArrayOf(0, 230, 0, 230))
            Haptic.Stop -> wave(context, longArrayOf(0, 500, 120, 500, 120, 500), intArrayOf(0, 255, 0, 255, 0, 255))
            Haptic.SessionEnd -> wave(context, longArrayOf(0, 700), intArrayOf(0, 200))
            is Haptic.Steer -> {
                val level = haptic.level.coerceIn(1, 3)
                val on = STEER_MS[level]
                val amp = STEER_AMP[level]
                if (haptic.side == Side.Left) wave(context, longArrayOf(0, on), intArrayOf(0, amp))
                else wave(context, longArrayOf(0, on, 70, on), intArrayOf(0, amp, 0, amp))
            }
        }
    }

    fun pulse(context: Context, pattern: LongArray = longArrayOf(0, 120, 90, 120)) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

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

    private val STEER_MS = longArrayOf(0, 70, 110, 160)
    private val STEER_AMP = intArrayOf(0, 90, 170, 255)
}
