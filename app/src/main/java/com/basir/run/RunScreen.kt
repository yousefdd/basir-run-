package com.basir.run

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Check
import com.basir.run.engine.CheckResult
import run.basir.engine.CommandKind
import run.basir.engine.PathMode
import com.basir.run.engine.Phrase
import com.basir.run.engine.Readiness
import com.basir.run.engine.ReadinessInput
import com.basir.run.engine.ReadinessTimer
import com.basir.run.engine.Severity
import com.basir.run.engine.SystemState
import com.basir.run.sensors.MotionMonitor
import com.basir.run.vision.Calibration
import com.basir.run.vision.FrameSnapshot
import com.basir.run.vision.GuidancePipeline
import com.basir.run.vision.PathStatus
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The one run screen. It opens the camera and checks, without any choice from the user, that
 * the path is visible and stable, the light and angle are right and analysis is fast enough.
 * Only when every check has held for three seconds does guidance start, with the confirmation
 * pulse and one short sentence. A failed check is never bypassed. During guidance the screen
 * stays simple: status, a tracking indicator, a large stop button and the emergency button.
 * The camera picture and the virtual path are shown only while checking, or in developer mode.
 */
@Composable
fun RunScreen(
    voice: Voice,
    prefs: Prefs,
    pipeline: GuidancePipeline,
    motion: MotionMonitor,
    haptics: HapticEngine,
    onFinished: (SessionSummary, java.io.File?) -> Unit,
    onEmergency: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val developer = prefs.developerMode
    var snapshot by remember { mutableStateOf<FrameSnapshot?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }
    var session by remember { mutableStateOf<GuidanceSession?>(null) }
    var calibration by remember { mutableStateOf(Calibration()) }
    var clockMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val timer = remember { ReadinessTimer() }
    val tone = remember { ToneGuide() }

    DisposableEffect(Unit) {
        // Every run starts from nothing: no remembered position, width or path.
        pipeline.calibration = Calibration()
        pipeline.cameraHeightM = prefs.cameraHeightM
        pipeline.reset()
        motion.start()
        voice.say(Phrase.CheckStart, interrupt = true)
        if (!haptics.available) voice.say(Phrase.HapticsUnavailable)
        onDispose {
            motion.stop()
            VolumeKeys.handler = null
        }
    }

    // Checking: re-evaluated on a timer as well, so a stalled camera can never look ready.
    LaunchedEffect(Unit) {
        var refusalSaid = false
        val started = SystemClock.elapsedRealtime()
        while (session == null) {
            val now = SystemClock.elapsedRealtime()
            clockMs = now
            val s = snapshot
            val fresh = s != null && now - s.timeMs < 700
            results = Readiness.evaluate(
                ReadinessInput(
                    framesPerSecond = if (fresh) s!!.framesPerSecond else 0f,
                    processingP90Ms = s?.processingP90Ms ?: 0f,
                    quality = if (fresh) s!!.quality else null,
                    path = if (fresh) s!!.path else null,
                    cameraPitchDeg = motion.pitchBelowHorizonDeg,
                    shakeRadPerS = motion.shakeRadPerS,
                    obstacleDetectorReady = s?.obstacleDetectorReady ?: false,
                    depthReady = s?.depthReady ?: false,
                    depthAgeMs = if (fresh) s!!.deep?.let { now - it.frameTimeMs } else null,
                    floorVisible = s?.deep?.hazard?.floorVisible ?: false,
                    depthMs = s?.deep?.depthMs ?: 0L,
                    lineTracked = fresh && s!!.line?.command?.kind.let { it != null && it != CommandKind.STOP },
                    lineDetail = s?.line?.decision?.mode?.let { modeLabel(it) },
                    hapticsReady = prefs.hapticQuizPassed || !haptics.available,
                    hapticsDetail = when {
                        !haptics.available -> "لا يوجد اهتزاز، سيُعتمد على الصوت"
                        prefs.hapticQuizPassed -> "اجتزت الاختبار"
                        else -> "أكمل اختبار الأنماط من الإعدادات"
                    },
                )
            )
            val estimate = s?.path?.estimate
            val left = estimate?.left
            val right = estimate?.right
            val center = estimate?.center
            val imagePath = estimate?.status == PathStatus.Tracked && left != null && right != null && center != null
            val linePath = results.firstOrNull { it.check == Check.Path }?.passed == true
            if (timer.update(results, now) && (imagePath || linePath)) {
                // The image-space fallback needs the runner's position and the path width now;
                // the line engine works in metres on the ground and needs only the tilt.
                val cal = if (imagePath) Calibration(runnerX = center!!.x(1f), halfWidth = (right!!.x(1f) - left!!.x(1f)) / 2, pitchDeg = motion.pitchBelowHorizonDeg)
                else Calibration(pitchDeg = motion.pitchBelowHorizonDeg)
                calibration = cal
                pipeline.calibration = cal
                haptics.confirm()
                voice.say(Phrase.GuidanceStarted, interrupt = true)
                val g = GuidanceSession(context, haptics, voice, tone, prefs, motion, cal.pitchDeg)
                g.start(now)
                session = g
                break
            }
            if (!refusalSaid && now - started > 20_000) {
                refusalSaid = true
                voice.say(Phrase.NotReady, interrupt = true)
            }
            delay(250)
        }
    }

    val current = session
    if (current != null) {
        DisposableEffect(current) {
            VolumeKeys.handler = { up ->
                if (up) current.repeatLast()
                else if (current.paused) current.resume(SystemClock.elapsedRealtime()) else current.pause()
                true
            }
            onDispose { current.close() }
        }
        LaunchedEffect(current) {
            while (true) {
                delay(250)
                clockMs = SystemClock.elapsedRealtime()
                current.tick(clockMs)
            }
        }
    }

    fun stop() {
        val g = session
        if (g == null) {
            voice.stopAll()
            onCancel()
        } else {
            onFinished(g.finish(SystemClock.elapsedRealtime()), g.logFile)
        }
    }

    val showCamera = current == null || developer
    Box(Modifier.fillMaxSize().background(NavyDeep)) {
        if (cameraError == null) {
            CameraHost(
                pipeline,
                onSnapshot = {
                    snapshot = it
                    clockMs = SystemClock.elapsedRealtime()
                    session?.onSnapshot(it, clockMs)
                },
                onError = { cameraError = it },
            )
            if (showCamera) PathOverlay(snapshot, clockMs, calibration.runnerX, developer, Modifier.fillMaxSize())
        }
        // During guidance the picture is covered: the runner needs the buttons, not the image.
        if (!showCamera) BasirBackground(animated = false) { }

        Column(
            modifier = screenModifier().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (current == null) CheckingPanel(results, cameraError, voice)
            else GuidingPanel(current, snapshot, clockMs)
            if (developer) DeveloperReadout(snapshot, clockMs, haptics.lastGuidance)
            Spacer(Modifier.weight(1f))
            SpeakButton(
                label = if (current == null) "إلغاء" else "إيقاف الجري",
                spoken = if (current == null) "إلغاء الفحص والعودة" else "إيقاف الجري وإنهاء التوجيه",
                onHold = ::stop,
                tone = ButtonTone.Accent,
                height = if (current == null) 72.dp else 120.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            SpeakButton(
                label = "طوارئ",
                spoken = "طوارئ: مشاركة موقعك والاتصال بجهة الاتصال الموثوقة",
                onHold = onEmergency,
                tone = ButtonTone.Danger,
                height = 76.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CheckingPanel(results: List<CheckResult>, cameraError: String?, voice: Voice) {
    Card(alpha = 0.9f) {
        Text("فحص المسار", style = MaterialTheme.typography.headlineSmall, color = Ink, modifier = Modifier.semantics { heading() })
        Text(
            "قف في منتصف المسار، والكاميرا إلى الأمام. يبدأ التوجيه وحده عندما تنجح كل الفحوص.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkMuted,
        )
        cameraError?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Danger) }
        if (results.isEmpty()) Text("جارٍ تشغيل الكاميرا", color = Ink)
        results.forEach { CheckRow(it) }
        val failed = results.filter { !it.passed }
        SpeakButton(
            label = "اقرأ الحالة",
            spoken = "قراءة نتيجة الفحص",
            onHold = {
                voice.sayText(
                    if (failed.isEmpty()) "كل الفحوص ناجحة. انتظر ثلاث ثوانٍ" else "غير جاهز. " + failed.joinToString("، ") { "${it.check.label}: ${it.detail}" },
                    interrupt = true,
                )
            },
            hint = null,
            height = 52.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CheckRow(result: CheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "${result.check.label}: ${if (result.passed) "ناجح" else "غير ناجح"}، ${result.detail}"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (result.passed) "✓" else "✕", style = MaterialTheme.typography.titleMedium, color = if (result.passed) Good else Danger, modifier = Modifier.width(22.dp))
        Text(result.check.label, style = MaterialTheme.typography.labelLarge, color = Ink, modifier = Modifier.weight(1f))
        Text(result.detail, style = MaterialTheme.typography.labelMedium, color = InkMuted)
    }
}

@Composable
private fun GuidingPanel(session: GuidanceSession, snapshot: FrameSnapshot?, nowMs: Long) {
    val d = session.decision
    val color = severityColor(d.severity)
    val status = Phrase.forCue(d)?.ar ?: stateLabel(d.state)
    Card(alpha = 0.92f) {
        Text(
            if (session.paused) "التوجيه متوقف مؤقتاً" else "التوجيه يعمل",
            style = MaterialTheme.typography.labelLarge,
            color = Tactile,
        )
        Text(
            status,
            style = MaterialTheme.typography.headlineSmall,
            color = Ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TrackingIndicator(snapshot, nowMs, color)
        Text(
            "مفتاح خفض الصوت: إيقاف مؤقت أو استئناف · رفع الصوت: تكرار آخر تعليمات",
            style = MaterialTheme.typography.labelMedium,
            color = InkMuted,
        )
    }
}

/**
 * Shows only what is known: whether the path is tracked now, and where the runner sits in it
 * while it is. No distance, accuracy figure or speed is shown.
 */
@Composable
private fun TrackingIndicator(snapshot: FrameSnapshot?, nowMs: Long, color: Color) {
    val fresh = snapshot != null && nowMs - snapshot.timeMs < 700
    val line = snapshot?.line?.takeIf { fresh && it.command.kind != CommandKind.STOP }
    val imageTracked = fresh && snapshot!!.path.stable && snapshot.path.estimate.status == PathStatus.Tracked
    val tracked = line != null || imageTracked
    // Where the runner is relative to the path: from the line engine's steering angle when it
    // leads (the path to the right means the runner is left of it), else from the image path.
    val position = line?.let { (-it.command.steerDeg / 25.0).toFloat() } ?: snapshot?.path?.offset ?: 0f
    val label = when {
        !fresh -> "لا تصل صور من الكاميرا"
        line != null -> modeLabel(line.decision.mode)
        tracked -> "المسار متتبَّع"
        snapshot!!.path.estimate.status == PathStatus.EdgeOnly -> "حد واحد فقط ظاهر"
        else -> "المسار غير مؤكد"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusLine(label, if (tracked) Good else Danger)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Canvas(Modifier.fillMaxWidth().height(28.dp).clearAndSetSemantics { }) {
                val mid = size.width / 2
                val half = size.width / 2 - 14.dp.toPx()
                val y = size.height / 2
                drawLine(NavyRaised, Offset(14.dp.toPx(), y), Offset(size.width - 14.dp.toPx(), y), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Tactile.copy(alpha = 0.6f), Offset(mid, 2.dp.toPx()), Offset(mid, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                if (tracked) {
                    val x = mid + position.coerceIn(-1f, 1f) * half
                    drawCircle(color, 11.dp.toPx(), Offset(x, y))
                    drawCircle(NavyDeep, 4.dp.toPx(), Offset(x, y))
                }
            }
        }
    }
}

fun stateLabel(state: SystemState): String = when (state) {
    SystemState.ReadyToCalibrate -> "جاهز للفحص"
    SystemState.CalibrationIncomplete -> "الفحص غير مكتمل"
    SystemState.PathTracked -> "المسار محدد"
    SystemState.PathCurving -> "منحنى أمامك"
    SystemState.Drifting -> "انحراف عن المنتصف"
    SystemState.ObstacleAhead -> "عائق محتمل"
    SystemState.PoorVisibility -> "رؤية ضعيفة"
    SystemState.BoundariesUnclear -> "حدود المسار غير واضحة"
    SystemState.CameraBlocked -> "الكاميرا محجوبة أو زاويتها غير مناسبة"
    SystemState.HighLatency -> "المعالجة بطيئة"
    SystemState.TrackingLost -> "فُقد التتبع"
    SystemState.StopAndCheck -> "توقف وتحقق"
    SystemState.Paused -> "التوجيه متوقف مؤقتاً"
    SystemState.Emergency -> "طوارئ"
}

private fun severityColor(severity: Severity) = when (severity) {
    Severity.Critical -> Danger
    Severity.Warning -> Amber
    Severity.Correction -> Blue
    Severity.Info, Severity.Silent -> Good
}

// ---------- After the run ----------

@Composable
fun SessionSummaryScreen(summary: SessionSummary, onShareLog: (() -> Unit)?, onDone: () -> Unit) {
    BasirBackground(animated = false) {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("انتهت الجلسة", style = MaterialTheme.typography.headlineLarge, color = Tactile, modifier = Modifier.semantics { heading() })
            Text("قياسات من جلستك الفعلية على هذا الهاتف، بلا صور.", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("المدة", clock(summary.durationMs), Modifier.weight(1f))
                MetricTile("مسار متتبَّع", String.format(Locale.US, "%.0f%%", summary.trackedShare * 100), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("تنبيهات توقف", "${summary.stopAlerts}", Modifier.weight(1f))
                MetricTile("تنبيهات عوائق", "${summary.obstacleAlerts}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("زمن المعالجة (وسيط)", String.format(Locale.US, "%.0f ملّي ث", summary.processingP50Ms), Modifier.weight(1f))
                MetricTile("أسوأ 10%", String.format(Locale.US, "%.0f ملّي ث", summary.processingP90Ms), Modifier.weight(1f))
            }
            if (onShareLog != null) {
                SpeakButton("مشاركة سجل القياسات", onShareLog, spoken = "مشاركة سجل القياسات، ملف نصي بلا صور", modifier = Modifier.fillMaxWidth())
            }
            SpeakButton("تم", onDone, spoken = "العودة إلى الشاشة الرئيسية", tone = ButtonTone.Primary, height = 76.dp, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** What the Virtual Path Engine is following, in words for the runner. */
fun modeLabel(mode: PathMode): String = when (mode) {
    PathMode.LINE -> "يتبع خطاً إرشادياً على الأرض"
    PathMode.LANE_CENTER -> "يتبع منتصف حارة بين خطين"
    PathMode.LINE_PARALLEL -> "يسير موازياً لخط جانبي"
    PathMode.FREE_PATH -> "يتبع الأرض الخالية"
    PathMode.DETOUR -> "يلتف حول عائق"
    PathMode.STOP -> "لا مسار مؤكداً"
}
