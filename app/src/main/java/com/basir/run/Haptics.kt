package com.basir.run

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.basir.run.engine.Decision
import com.basir.run.engine.HapticBridge
import run.basir.engine.HapticLanguage
import run.basir.engine.HapticOutput
import run.basir.engine.HapticPattern
import run.basir.engine.HapticScheduler
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The phone's vibration motor as the engine's [HapticOutput], with the user's strength and
 * pulse-length settings applied to every pattern. Gaps are left as they are, so each pattern
 * keeps its rhythm.
 */
class VibratorOutput(context: Context, private val prefs: Prefs) : HapticOutput {
    private val vibrator: Vibrator? = vibratorOf(context)

    val available: Boolean get() = vibrator?.hasVibrator() == true
    override val hasAmplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true

    override fun play(pattern: HapticPattern) {
        val v = vibrator ?: return
        if (!available || !prefs.vibrateOn) return
        val strength = prefs.hapticStrength.coerceIn(0.3f, 1f)
        val length = prefs.hapticDuration.coerceIn(0.6f, 1.6f)
        val timings = LongArray(pattern.timingsMs.size) { i ->
            val t = pattern.timingsMs[i]
            if (pattern.amplitudes[i] > 0) (t * length).roundToLong() else t
        }
        val amplitudes = IntArray(pattern.amplitudes.size) { i ->
            val a = pattern.amplitudes[i]
            if (a > 0) (a * strength).roundToInt().coerceIn(1, 255) else 0
        }
        val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
        else VibrationEffect.createWaveform(timings, -1)
        v.vibrate(effect)
    }

    override fun cancel() {
        vibrator?.cancel()
    }

    companion object {
        fun vibratorOf(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
    }
}

/**
 * All vibration in the app goes through here: guidance through the engine's [HapticScheduler]
 * (priorities, the latched stop, repeat limits, no opposite sides back to back), and the
 * lessons and the quiz straight to the motor.
 */
class HapticEngine(context: Context, prefs: Prefs) {
    private val output = VibratorOutput(context, prefs)
    val language = HapticLanguage(output.hasAmplitudeControl)
    private var scheduler = HapticScheduler(output, language)

    /** False on phones without a vibration motor; the app then relies on voice. */
    val available: Boolean get() = output.available

    /** The name of the last guidance pattern played, for the developer view. */
    @Volatile var lastGuidance: String? = null
        private set

    /** Feeds one decision to the scheduler; returns the pattern started now, if any. */
    fun guide(d: Decision, now: Long = SystemClock.elapsedRealtime()): HapticPattern? {
        val cmd = HapticBridge.command(d) ?: return null
        return scheduler.onCommand(cmd, now)?.also { lastGuidance = it.name }
    }

    /** Plays a pattern now (lessons, quiz, tests). Returns false when nothing can vibrate. */
    fun play(pattern: HapticPattern): Boolean {
        if (!available) return false
        output.cancel()
        output.play(pattern)
        return true
    }

    /** Short confirmation: checks passed, guidance started or ended. */
    fun confirm(): Boolean = play(CONFIRM)

    /** A fresh scheduler for each run, so no latch or repeat timer carries over. */
    fun resetGuidance() {
        output.cancel()
        scheduler = HapticScheduler(output, language)
        lastGuidance = null
    }

    companion object {
        /** A soft tick then a firmer one: distinct from every guidance pattern. */
        val CONFIRM = HapticPattern("confirm", longArrayOf(0, 40, 90, 140), intArrayOf(0, 120, 0, 200), priority = 1)
    }
}
