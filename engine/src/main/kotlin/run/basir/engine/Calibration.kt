package run.basir.engine

import kotlin.math.abs
import kotlin.math.sqrt

enum class CheckStatus { PASS, WARN, FAIL }

data class CheckResult(val id: String, val status: CheckStatus, val messageAr: String, val value: Double? = null)

data class CalibrationReport(val checks: List<CheckResult>) {
    /** Running may start only when no required check failed. */
    val ready: Boolean get() = checks.none { it.status == CheckStatus.FAIL }
}

/** Per-frame image quality measured on the camera feed during calibration. */
data class FrameStats(
    val meanLuma: Double,       // 0..255
    val clippedFraction: Double, // pixels near black or white
    val sharpness: Double,      // variance of the Laplacian (higher = sharper)
    val pathFound: Boolean,     // the planner produced a path (line, lane or free space)
    val processingMs: Double,
) {
    companion object {
        fun measure(img: RgbImage, pathFound: Boolean, processingMs: Double, step: Int = 2): FrameStats {
            val w = img.width / step
            val h = img.height / step
            val y = DoubleArray(w * h)
            var sum = 0.0
            var clipped = 0
            for (j in 0 until h) for (i in 0 until w) {
                val k = (j * step) * img.width + i * step
                val l = 0.299 * img.r[k] + 0.587 * img.g[k] + 0.114 * img.b[k]
                y[j * w + i] = l
                sum += l
                if (l < 5 || l > 250) clipped++
            }
            var lapSum = 0.0
            var lapSq = 0.0
            var n = 0
            for (j in 1 until h - 1) for (i in 1 until w - 1) {
                val c = j * w + i
                val lap = y[c - 1] + y[c + 1] + y[c - w] + y[c + w] - 4 * y[c]
                lapSum += lap; lapSq += lap * lap; n++
            }
            val mean = lapSum / n
            return FrameStats(sum / (w * h), clipped.toDouble() / (w * h), lapSq / n - mean * mean, pathFound, processingMs)
        }
    }
}

data class CalibrationInputs(
    val pitchDeg: Double,             // from the phone's gravity sensor (camera tilt down)
    val rollDeg: Double,
    val frames: List<FrameStats>,     // the last ~2 s of camera frames
    val gyroStdDegPerS: Double,       // angular jitter while the runner stands still
    val vibrationConfirmed: Boolean?, // the user felt the test vibration (null = not done)
    val quizCorrect: Int,             // haptic pattern recognition quiz
    val quizTotal: Int,
)

data class CalibrationConfig(
    val pitchRange: ClosedFloatingPointRange<Double> = -5.0..35.0,
    val maxRollDeg: Double = 10.0,
    val lumaRange: ClosedFloatingPointRange<Double> = 40.0..215.0,
    val maxClipped: Double = 0.25,
    val minSharpness: Double = 30.0,
    val maxGyroStd: Double = 15.0,
    val minPathFoundRate: Double = 0.8,
    val maxP95Ms: Double = 66.0,      // 15 fps
    val warnP95Ms: Double = 100.0,
    val minQuizRate: Double = 0.8,
    val recalibratePitchDeg: Double = 10.0,
    val recalibrateRollDeg: Double = 15.0,
)

/**
 * The checks that must pass before a run: phone mounting and camera direction, ground in view,
 * lighting, camera steadiness, that a path can actually be found here, that the phone is fast
 * enough, that vibration works and that the user recognizes the patterns. Messages are Arabic
 * for the user (spoken by the app); ids are stable for logging.
 */
class Calibration(private val config: CalibrationConfig = CalibrationConfig()) {

    fun evaluate(inputs: CalibrationInputs): CalibrationReport {
        val c = config
        val out = ArrayList<CheckResult>()
        out += if (inputs.pitchDeg in c.pitchRange && abs(inputs.rollDeg) <= c.maxRollDeg)
            CheckResult("mounting", CheckStatus.PASS, "وضع الهاتف مناسب", inputs.pitchDeg)
        else CheckResult("mounting", CheckStatus.FAIL,
            if (inputs.pitchDeg !in c.pitchRange) "عدّل ميل الهاتف: يجب أن تنظر الكاميرا إلى الأمام مع ميل خفيف نحو الأرض"
            else "الهاتف مائل إلى أحد الجانبين، اجعله مستقيماً", inputs.pitchDeg)

        val frames = inputs.frames
        if (frames.isEmpty()) {
            out += CheckResult("camera", CheckStatus.FAIL, "لا تصل صور من الكاميرا")
            return CalibrationReport(out)
        }
        val luma = frames.map { it.meanLuma }.average()
        val clipped = frames.map { it.clippedFraction }.average()
        out += when {
            luma !in c.lumaRange -> CheckResult("lighting", CheckStatus.FAIL,
                if (luma < c.lumaRange.start) "الإضاءة ضعيفة جداً" else "الضوء قوي جداً، الكاميرا مبهورة", luma)
            clipped > c.maxClipped -> CheckResult("lighting", CheckStatus.WARN, "جزء كبير من الصورة مظلم جداً أو ساطع جداً", clipped)
            else -> CheckResult("lighting", CheckStatus.PASS, "الإضاءة مناسبة", luma)
        }
        val sharp = frames.map { it.sharpness }.sorted()[frames.size / 2]
        out += if (sharp >= c.minSharpness) CheckResult("focus", CheckStatus.PASS, "الصورة واضحة", sharp)
        else CheckResult("focus", CheckStatus.FAIL, "الصورة غير واضحة: نظّف عدسة الكاميرا", sharp)

        out += if (inputs.gyroStdDegPerS <= c.maxGyroStd) CheckResult("steady", CheckStatus.PASS, "الكاميرا ثابتة", inputs.gyroStdDegPerS)
        else CheckResult("steady", CheckStatus.FAIL, "الهاتف يهتز في الحامل، ثبّته جيداً", inputs.gyroStdDegPerS)

        val found = frames.count { it.pathFound }.toDouble() / frames.size
        out += if (found >= c.minPathFoundRate) CheckResult("path", CheckStatus.PASS, "المسار ظاهر للكاميرا", found)
        else CheckResult("path", CheckStatus.FAIL, "لا أستطيع تحديد مسار هنا، قف على الخط أو في وسط الممر وانظر إلى الأمام", found)

        val times = frames.map { it.processingMs }.sorted()
        val p95 = times[minOf(times.size - 1, (times.size * 0.95).toInt())]
        out += when {
            p95 <= c.maxP95Ms -> CheckResult("speed", CheckStatus.PASS, "سرعة المعالجة كافية", p95)
            p95 <= c.warnP95Ms -> CheckResult("speed", CheckStatus.WARN, "المعالجة بطيئة قليلاً: اجرِ بسرعة أقل", p95)
            else -> CheckResult("speed", CheckStatus.FAIL, "الهاتف أبطأ من أن يوجّهك بأمان أثناء الجري", p95)
        }

        out += when (inputs.vibrationConfirmed) {
            true -> CheckResult("vibration", CheckStatus.PASS, "الاهتزاز يعمل")
            false -> CheckResult("vibration", CheckStatus.FAIL, "لم تشعر بالاهتزاز: تحقق من إعدادات الهاتف")
            null -> CheckResult("vibration", CheckStatus.FAIL, "لم يُجرَ اختبار الاهتزاز بعد")
        }

        val rate = if (inputs.quizTotal == 0) 0.0 else inputs.quizCorrect.toDouble() / inputs.quizTotal
        out += if (inputs.quizTotal > 0 && rate >= c.minQuizRate)
            CheckResult("patterns", CheckStatus.PASS, "تعرّفت على أنماط الاهتزاز", rate)
        else CheckResult("patterns", CheckStatus.FAIL, "تدرّب على أنماط الاهتزاز مرة أخرى قبل الجري", rate)
        return CalibrationReport(out)
    }

    /** True when the phone has moved in its harness enough to invalidate the calibration. */
    fun needsRecalibration(calibratedPitch: Double, calibratedRoll: Double, pitch: Double, roll: Double): Boolean =
        abs(pitch - calibratedPitch) > config.recalibratePitchDeg || abs(roll - calibratedRoll) > config.recalibrateRollDeg

    companion object {
        /** Camera tilt (down positive) and roll from the gravity vector in Android device axes, phone upright (portrait). */
        fun pitchRollFromGravity(gx: Double, gy: Double, gz: Double): Pair<Double, Double> {
            val norm = sqrt(gx * gx + gy * gy + gz * gz)
            // Upright phone, camera level: gravity along +y. Tilting the camera down moves it toward +z.
            val pitch = kotlin.math.atan2(gz, gy).toDegrees()
            val roll = kotlin.math.asin((gx / norm).coerceIn(-1.0, 1.0)).toDegrees()
            return pitch to roll
        }
    }
}
