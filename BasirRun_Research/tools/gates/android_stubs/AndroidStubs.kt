// Minimal stand-ins for the Android classes the changed app files touch, so the Kotlin compiler can
// type-check those files here (no Android SDK in this container). Signatures follow the Android API;
// nothing here runs on a phone.
@file:Suppress("unused", "UNUSED_PARAMETER")
package android.os
object SystemClock { @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
object Build { object VERSION { @JvmField val SDK_INT: Int = 35 }; object VERSION_CODES { const val S: Int = 31 } }
open class VibrationEffect { companion object {
    @JvmStatic fun createWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int): VibrationEffect = VibrationEffect()
    @JvmStatic fun createWaveform(timings: LongArray, repeat: Int): VibrationEffect = VibrationEffect() } }
open class Vibrator { fun hasVibrator() = true; fun hasAmplitudeControl() = true; fun vibrate(e: VibrationEffect) {}; fun cancel() {} }
open class VibratorManager { val defaultVibrator: Vibrator = Vibrator() }
