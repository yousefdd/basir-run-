package com.basir.run

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.basir.run.engine.Decision
import com.basir.run.belt.BeltLink
import com.basir.run.engine.DecisionStabilityHapticBridge
import run.basir.engine.BeltLanguage
import run.basir.engine.BeltPattern
import run.basir.engine.GuidanceCommand
import run.basir.engine.HapticLanguage
import run.basir.engine.HapticOutput
import run.basir.engine.SchedulerConfig
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

    /** Guidance obeys the vibration switch in settings; lessons and the quiz vibrate regardless. */
    override fun play(pattern: HapticPattern) {
        if (prefs.vibrateOn) vibrate(pattern)
    }

    fun vibrate(pattern: HapticPattern) {
        val v = vibrator ?: return
        if (!available) return
        val strength = prefs.hapticStrength.coerceIn(0.3f, 1f)
        val timings = stretched(pattern)
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

    override fun playedDurationMs(pattern: HapticPattern): Long = stretched(pattern).sum()

    // The pulse-length setting lengthens every pulse; gaps stay as they are.
    private fun stretched(pattern: HapticPattern): LongArray {
        val length = prefs.hapticDuration.coerceIn(0.6f, 2f)
        return LongArray(pattern.timingsMs.size) { i ->
            val t = pattern.timingsMs[i]
            if (pattern.amplitudes[i] > 0) (t * length).roundToLong() else t
        }
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
 * Guidance output: the vibration belt when it is connected and switched on, the phone's motor otherwise. The scheduler
 * in front of it (priorities, cooldown, repeat limits, the latched stop, no stale commands) is the same for both; the
 * belt only changes WHERE and HOW a cue is felt (run.basir.engine.BeltLanguage). If the belt drops out in the middle of
 * a run the next cue simply comes from the phone.
 */
class GuidanceOutput(private val phone: VibratorOutput, private val belt: BeltLink, private val prefs: Prefs) : HapticOutput {
    private val beltLanguage = BeltLanguage()
    /** The command behind the pattern being played (a close obstacle and a possible one share the phone pattern). */
    var command: GuidanceCommand? = null

    val beltActive: Boolean get() = prefs.beltEnabled && belt.connected
    override val hasAmplitudeControl: Boolean get() = phone.hasAmplitudeControl

    override fun play(pattern: HapticPattern) {
        if (!prefs.vibrateOn) return
        if (beltActive) {
            val b = beltLanguage.forPattern(pattern.name, command)
            if (b != null) { belt.play(b); return }
        }
        phone.play(pattern)
    }

    override fun cancel() { if (beltActive) belt.silence() else phone.cancel() }

    override fun playedDurationMs(pattern: HapticPattern): Long =
        if (beltActive) beltLanguage.forPattern(pattern.name, command)?.durationMs ?: phone.playedDurationMs(pattern)
        else phone.playedDurationMs(pattern)
}

/**
 * All vibration in the app goes through here: guidance through the engine's [HapticScheduler]
 * (priorities, the latched stop, repeat limits, no opposite sides back to back), and the
 * lessons and the quiz straight to the motor (or the belt when it is connected).
 */
class HapticEngine(context: Context, private val prefs: Prefs) {
    private val phone = VibratorOutput(context, prefs)
    /** The vibration belt (Bluetooth); guidance moves to it while it is connected. */
    val belt = BeltLink(context, prefs)
    private val output = GuidanceOutput(phone, belt, prefs)
    val language = HapticLanguage(phone.hasAmplitudeControl)
    val beltLanguage = BeltLanguage()
    private fun schedulerConfig() = if (output.beltActive) BELT_SCHEDULER.copy(minChangeGapMs = prefs.cueCooldownMs.toLong())
        else SchedulerConfig(onPathOnlyOnConfirm = true, minChangeGapMs = prefs.cueCooldownMs.toLong())
    private var scheduler = HapticScheduler(output, language, schedulerConfig())

    /** False on phones without a vibration motor and no belt; the app then relies on voice. */
    val available: Boolean get() = phone.available || output.beltActive
    /** The phone itself can vibrate (phone lessons). */
    val phoneAvailable: Boolean get() = phone.available
    val beltActive: Boolean get() = output.beltActive

    /** The name of the last guidance pattern played, for the developer view. */
    @Volatile var lastGuidance: String? = null
        private set

    /**
     * Round 8 decision stability (dominant command over time, adaptive cooldown) in front of the scheduler, so a
     * flickering reading never reaches the motors; the reason of the last decision is kept for the developer view.
     */
    private val stability = DecisionStabilityHapticBridge()
    val lastStability: run.basir.engine.StableGuidance? get() = stability.last

    /** Feeds one decision to the stability engine and the scheduler; returns the pattern started now, if any. */
    fun guide(d: Decision, now: Long = SystemClock.elapsedRealtime(), trackConfidence: Double = 1.0): HapticPattern? {
        val speed = if (prefs.walkingMode) 1.4 else 2.5
        val cmd = stability.command(d, now, speed, trackConfidence) ?: return null
        output.command = cmd
        return scheduler.onCommand(cmd, now)?.also { lastGuidance = it.name }
    }

    /**
     * Plays a phone pattern now on the phone's motor (the phone lessons and quiz teach the phone's language, so they
     * stay on the phone even when a belt is connected). Returns false when the phone cannot vibrate.
     */
    fun play(pattern: HapticPattern): Boolean {
        if (!phone.available) return false
        phone.cancel()
        phone.vibrate(pattern)
        return true
    }

    /** Plays a belt pattern now (belt lessons, motor check, belt quiz). False when the belt is not connected. */
    fun playBelt(pattern: BeltPattern): Boolean {
        if (!belt.connected) return false
        belt.play(pattern)
        return true
    }

    /** How long [pattern] lasts with the user's pulse length, so a lesson waits for it to end. */
    fun durationOf(pattern: HapticPattern): Long = phone.playedDurationMs(pattern)

    /** Short confirmation: checks passed, guidance started or ended. Follows the vibration switch. */
    fun confirm(): Boolean {
        if (output.beltActive) { if (prefs.vibrateOn) belt.play(beltLanguage.backOnPath()); return true }
        if (!phone.available) return false
        phone.cancel()
        phone.play(CONFIRM)
        return true
    }

    /** A fresh scheduler for each run, so no latch or repeat timer carries over (and the chosen cooldown applies). */
    fun resetGuidance() {
        output.cancel()
        scheduler = HapticScheduler(output, language, schedulerConfig())
        stability.reset()
        lastGuidance = null
    }

    companion object {
        /**
         * The belt repeats a lasting correction less often than the phone: a strong belt correction lasts 0.52 s, and the
         * phone's 0.65 s repeat would keep it buzzing ~80 % of the time while the runner drifts (measured on the 45 clip
         * replays, BasirRun_Research/benchmarks/results/belt_replay.txt). Same priorities and cooldown otherwise.
         */
        val BELT_SCHEDULER = SchedulerConfig(onPathOnlyOnConfirm = true,
            correctRepeatMs = mapOf(run.basir.engine.Level.LOW to 1400L, run.basir.engine.Level.MEDIUM to 1100L, run.basir.engine.Level.HIGH to 950L))
        /** A soft tick then a firmer one: distinct from every guidance pattern. */
        val CONFIRM = HapticPattern("confirm", longArrayOf(0, 80, 120, 250), intArrayOf(0, 120, 0, 200), priority = 1)
    }
}
