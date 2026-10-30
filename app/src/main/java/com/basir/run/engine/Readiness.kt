package com.basir.run.engine

import com.basir.run.vision.FrameQuality
import com.basir.run.vision.PathStatus
import com.basir.run.vision.TrackedPath
import java.util.Locale

enum class Check(val label: String) {
    Camera("الكاميرا تعمل"),
    Speed("سرعة التحليل كافية"),
    Light("الإضاءة ووضوح الصورة"),
    Angle("زاوية الكاميرا"),
    Steady("ثبات الهاتف"),
    Path("المسار ظاهر بثقة"),
    Obstacles("كاشف العوائق"),
    Depth("تقدير العمق والأرض"),
    Haptics("أنماط الاهتزاز"),
}

data class CheckResult(val check: Check, val passed: Boolean, val detail: String)

data class ReadinessInput(
    val framesPerSecond: Float,
    val processingP90Ms: Float,
    val quality: FrameQuality?,
    val path: TrackedPath?,
    val cameraPitchDeg: Float?,
    /** Rotation rate of the phone in rad/s, smoothed. */
    val shakeRadPerS: Float?,
    val obstacleDetectorReady: Boolean,
    /** The depth model is loaded. */
    val depthReady: Boolean,
    /** Age of the newest depth result, or null if none has arrived. */
    val depthAgeMs: Long?,
    /** The newest depth result found the floor in front. */
    val floorVisible: Boolean,
    val depthMs: Long,
    /** The Virtual Path Engine follows a line, a lane or runs alongside a line. */
    val lineTracked: Boolean = false,
    /** What it follows, in words, when [lineTracked]. */
    val lineDetail: String? = null,
    /** The runner has learned the vibration patterns (passed the quiz), or the phone cannot vibrate. */
    val hapticsReady: Boolean = true,
    val hapticsDetail: String = "",
)

/**
 * Checks done before a guided run may start. Every check must pass continuously for a few
 * seconds; otherwise the screen says "not ready" and why.
 */
object Readiness {

    const val MIN_FPS = 10f
    const val MAX_P90_MS = 150f
    const val MIN_PITCH = 15f
    const val MAX_PITCH = 75f
    const val MAX_SHAKE = 0.6f
    const val MIN_CONFIDENCE = 0.5f
    const val MAX_DEPTH_AGE_MS = 1500L

    fun evaluate(i: ReadinessInput): List<CheckResult> {
        val q = i.quality
        val p = i.path
        return listOf(
            CheckResult(Check.Camera, i.framesPerSecond > 0f, if (i.framesPerSecond > 0f) "تصل الصور" else "لا تصل صور من الكاميرا"),
            CheckResult(
                Check.Speed,
                i.framesPerSecond >= MIN_FPS && i.processingP90Ms <= MAX_P90_MS,
                String.format(Locale.US, "%.0f إطار/ث · %.0f ملّي ثانية", i.framesPerSecond, i.processingP90Ms),
            ),
            CheckResult(
                Check.Light,
                q != null && q.usable,
                when {
                    q == null -> "لم تُحلَّل صورة بعد"
                    q.blocked -> "العدسة محجوبة"
                    q.tooDark -> "الإضاءة ضعيفة"
                    q.glare -> "وهج قوي"
                    q.blurry -> "الصورة ضبابية"
                    else -> "جيدة"
                },
            ),
            CheckResult(
                Check.Angle,
                i.cameraPitchDeg != null && i.cameraPitchDeg in MIN_PITCH..MAX_PITCH,
                i.cameraPitchDeg?.let { String.format(Locale.US, "%.0f° تحت الأفق (المطلوب %.0f° إلى %.0f°)", it, MIN_PITCH, MAX_PITCH) }
                    ?: "حساس الاتجاه غير متاح",
            ),
            CheckResult(
                Check.Steady,
                i.shakeRadPerS != null && i.shakeRadPerS <= MAX_SHAKE,
                i.shakeRadPerS?.let { if (it <= MAX_SHAKE) "ثابت" else "يهتز كثيراً" } ?: "حساس الحركة غير متاح",
            ),
            CheckResult(
                Check.Path,
                i.lineTracked || (p != null && p.estimate.status == PathStatus.Tracked && p.stable && p.estimate.confidence >= MIN_CONFIDENCE),
                when {
                    i.lineTracked -> i.lineDetail ?: "الخط متتبَّع"
                    p == null -> "لم يُحلَّل المسار بعد"
                    p.estimate.ambiguous -> "خطوط متعددة متقاربة"
                    p.estimate.status == PathStatus.EdgeOnly -> "أرى حداً واحداً فقط"
                    p.estimate.status == PathStatus.Unclear -> "لا أرى حدود المسار"
                    !p.stable -> "التقدير غير مستقر بعد"
                    else -> String.format(Locale.US, "الثقة %.0f%%", p.estimate.confidence * 100)
                },
            ),
            CheckResult(
                Check.Obstacles,
                i.obstacleDetectorReady,
                if (i.obstacleDetectorReady) "يعمل" else "النموذج غير محمّل",
            ),
            CheckResult(
                Check.Depth,
                i.depthReady && i.depthAgeMs != null && i.depthAgeMs <= MAX_DEPTH_AGE_MS && i.floorVisible,
                when {
                    !i.depthReady -> "نموذج العمق غير محمّل"
                    i.depthAgeMs == null -> "لم تصل نتيجة بعد"
                    i.depthAgeMs > MAX_DEPTH_AGE_MS -> "النتائج متأخرة"
                    !i.floorVisible -> "لا أرى الأرض أمامك"
                    else -> String.format(Locale.US, "الأرض ظاهرة · %d ملّي ثانية", i.depthMs)
                },
            ),
            CheckResult(Check.Haptics, i.hapticsReady, i.hapticsDetail),
        )
    }
}

/** Ready only after every check has passed without a break for [holdMs]. */
class ReadinessTimer(private val holdMs: Long = 3000) {
    private var passingSince = -1L

    fun update(results: List<CheckResult>, now: Long): Boolean {
        if (results.all { it.passed }) {
            if (passingSince < 0) passingSince = now
        } else {
            passingSince = -1L
        }
        return passingSince >= 0 && now - passingSince >= holdMs
    }

    fun reset() {
        passingSince = -1L
    }
}
