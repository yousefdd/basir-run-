package com.basir.run

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.basir.run.engine.Haptic
import com.basir.run.engine.HapticPatterns
import com.basir.run.engine.HapticScheduler

/** Plays haptic patterns with the user's strength and duration settings and the priority rules. */
class HapticEngine(context: Context, private val prefs: Prefs) {

    private val vibrator: Vibrator? = Haptics.vibrator(context)
    private val scheduler = HapticScheduler()

    /** False on phones without a vibration motor; the app then relies on voice. */
    val available: Boolean get() = vibrator?.hasVibrator() == true

    /** Returns false when nothing could be played (no motor or vibration turned off). */
    fun play(h: Haptic, level: Int = 1, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val v = vibrator ?: return false
        if (!available || !prefs.vibrateOn) return false
        val wave = HapticPatterns.waveform(h, prefs.hapticStrength, prefs.hapticDuration, level)
        val verdict = scheduler.decide(h, wave.totalMs, now)
        if (verdict is HapticScheduler.Verdict.Play) {
            if (verdict.cancelCurrent) v.cancel()
            val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(wave.timings, wave.amplitudes, -1)
            else VibrationEffect.createWaveform(wave.timings, -1)
            v.vibrate(effect)
        }
        return true
    }
}

object Haptics {
    fun pulse(context: Context, pattern: LongArray = longArrayOf(0, 120, 90, 120)) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
