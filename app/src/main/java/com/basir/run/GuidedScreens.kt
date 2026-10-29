package com.basir.run

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Check
import com.basir.run.engine.CheckResult
import com.basir.run.engine.Haptic
import com.basir.run.engine.Phrase
import com.basir.run.engine.Readiness
import com.basir.run.engine.ReadinessInput
import com.basir.run.engine.ReadinessTimer
import com.basir.run.engine.RouteMode
import com.basir.run.engine.Severity
import com.basir.run.engine.SystemState
import com.basir.run.sensors.MotionMonitor
import com.basir.run.vision.Calibration
import com.basir.run.vision.FrameSnapshot
import com.basir.run.vision.GuidancePipeline
import com.basir.run.vision.PathStatus
import java.util.Locale
import kotlinx.coroutines.delay

// ---------- Route type ----------

@Composable
fun ModeScreen(prefs: Prefs, onPick: (RouteMode) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader("نوع المسار", onBack)
        Text(
            "اختر المكان الذي ستجري فيه. لكل نوع طريقة مختلفة لتحديد المسار، ومعايرة خاصة به.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RouteMode.values().forEach { mode ->
            val calibrated = prefs.calibration(mode) != null
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClickLabel = "اختيار ${mode.title}") { onPick(mode) }
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(mode.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(mode.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (calibrated) "سبقت معايرته. ستُعاد المعايرة قبل كل جلسة." else "لم تُجرَ معايرة بعد.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Calibration and readiness ----------

@Composable
fun CalibrationScreen(
    mode: RouteMode,
    voice: Voice,
    prefs: Prefs,
    pipeline: GuidancePipeline,
    motion: MotionMonitor,
    onReady: (Calibration) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<FrameSnapshot?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    val timer = remember { ReadinessTimer() }

    DisposableEffect(mode) {
        pipeline.mode = mode
        // Calibrate from scratch: no remembered position or width may bias the measurement.
        pipeline.calibration = Calibration()
        pipeline.reset()
        motion.start()
        voice.say(Phrase.CalibrationStart, interrupt = true)
        onDispose { motion.stop() }
    }

    // Re-evaluate on a timer too, so a stalled camera shows as not ready.
    LaunchedEffect(Unit) {
        var announcedRefusal = false
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
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
                )
            )
            val nowReady = timer.update(results, now)
            if (nowReady && !ready) {
                if (prefs.vibrateOn) Haptics.play(context, Haptic.Calibrated)
                voice.say(Phrase.CalibrationDone, interrupt = true)
            }
            ready = nowReady
            if (!announcedRefusal && !nowReady && now - started > 20_000) {
                announcedRefusal = true
                voice.say(if (mode == RouteMode.Unknown) Phrase.UnknownPathRefused else Phrase.NotReady, interrupt = true)
            }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraError == null) {
            CameraHost(pipeline, onSnapshot = { snapshot = it }, onError = { cameraError = it })
            val pathOk = results.firstOrNull { it.check == Check.Path }?.passed == true
            PathOverlay(snapshot, trusted = pathOk, runnerX = 0.5f, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel {
                ScreenHeader("المعايرة: ${mode.title}", onBack)
                Text(
                    "قف في منتصف المسار، والهاتف مثبت على الحامل والكاميرا الخلفية إلى الأمام. لا تتحرك حتى تكتمل الفحوص.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                cameraError?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.weight(1f))
            Panel {
                results.forEach { ChecklistRow(it) }
                if (results.isEmpty()) Text("جارٍ تشغيل الكاميرا", color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (ready) "جاهز للجري. اضغط تأكيد المعايرة." else "غير جاهز للجري.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val failed = results.filter { !it.passed }
                            voice.sayText(
                                if (failed.isEmpty()) "كل الفحوص ناجحة" else "غير جاهز. " + failed.joinToString("، ") { "${it.check.label}: ${it.detail}" },
                                interrupt = true,
                            )
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Text("اقرأ الحالة", style = MaterialTheme.typography.labelLarge) }
                    Button(
                        onClick = {
                            val estimate = snapshot?.path?.estimate
                            val left = estimate?.left
                            val right = estimate?.right
                            val center = estimate?.center
                            if (ready && estimate?.status == PathStatus.Tracked && left != null && right != null && center != null) {
                                val calibration = Calibration(runnerX = center.x(1f), halfWidth = (right.x(1f) - left.x(1f)) / 2)
                                prefs.saveCalibration(mode, calibration)
                                onReady(calibration)
                            } else {
                                voice.say(Phrase.NotReady, interrupt = true)
                            }
                        },
                        enabled = ready,
                        modifier = Modifier.weight(1.4f).heightIn(min = 64.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) { Text("تأكيد المعايرة", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(result: CheckResult) {
    val mark = if (result.passed) "✓" else "✕"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "${result.check.label}: ${if (result.passed) "ناجح" else "غير ناجح"}، ${result.detail}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(mark, style = MaterialTheme.typography.titleMedium, color = if (result.passed) Good else MaterialTheme.colorScheme.error, modifier = Modifier.width(22.dp))
        Text(result.check.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(result.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Live guidance ----------

@Composable
fun SessionScreen(
    session: GuidanceSession,
    pipeline: GuidancePipeline,
    calibration: Calibration,
    gpsText: String?,
    onEnd: () -> Unit,
    onSos: () -> Unit,
) {
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        VolumeKeys.handler = { up ->
            if (up) session.repeatLast()
            else if (session.paused) session.resume(SystemClock.elapsedRealtime()) else session.pause()
            true
        }
        onDispose { VolumeKeys.handler = null }
    }
    LaunchedEffect(Unit) {
        session.start(SystemClock.elapsedRealtime())
        while (true) {
            delay(250)
            session.tick(SystemClock.elapsedRealtime())
        }
    }

    val d = session.decision
    val trusted = d.state == SystemState.PathTracked || d.state == SystemState.PathCurving || d.state == SystemState.Drifting
    val instruction = Phrase.forCue(d)?.ar ?: stateLabel(d.state)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraError == null) {
            CameraHost(
                pipeline,
                onSnapshot = { session.onSnapshot(it, SystemClock.elapsedRealtime()) },
                onError = { cameraError = it },
            )
            PathOverlay(session.snapshot, trusted, calibration.runnerX, Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(stateLabel(d.state), stateColor(d.severity))
                Spacer(Modifier.weight(1f))
                session.snapshot?.let {
                    StatusPill(
                        String.format(Locale.US, "ثقة %.0f%% · %.0f ملّي ث", it.path.estimate.confidence * 100, it.processingP90Ms),
                        Color(0xFF6B7579),
                    )
                }
            }
            gpsText?.let { StatusPill(it, Good) }
            cameraError?.let { Panel { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) } }

            // The middle of the screen repeats the last instruction when touched.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(onClickLabel = "كرر آخر تعليمات") { session.repeatLast() }
            )

            Panel {
                Text(
                    instruction,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = instruction },
                )
                if (trusted) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PositionGauge(session.snapshot?.path?.offset ?: 0f, stateColor(d.severity))
                            Row(Modifier.fillMaxWidth()) {
                                Text("يسار", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Text("المنتصف", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Text("يمين", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    Text(
                        "لا توجيه جانبي الآن: المسار غير مؤكد.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { if (session.paused) session.resume(SystemClock.elapsedRealtime()) else session.pause() },
                    modifier = Modifier.weight(1f).height(88.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(if (session.paused) "استئناف التوجيه" else "إيقاف مؤقت", style = MaterialTheme.typography.titleMedium) }
                HoldButton(
                    label = "إنهاء",
                    hint = "اضغط مطولاً",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    height = 88.dp,
                    onTap = { session.repeatLast() },
                    onHold = onEnd,
                    modifier = Modifier.weight(1f),
                )
            }
            HoldButton(
                label = "طوارئ",
                hint = "اضغط مطولاً",
                container = Danger,
                content = Color.White,
                height = 72.dp,
                onTap = {},
                onHold = onSos,
            )
        }
    }
}

// The bar is the path; the dot is the runner. Zones show where each correction level starts.
@Composable
private fun PositionGauge(offset: Float, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val range = 1.0f
        val mid = size.width / 2
        val half = size.width / 2 - 17.dp.toPx()
        val barTop = size.height / 2 - 5.dp.toPx()
        drawRoundRect(Color(0xFF3A4449), Offset(0f, barTop), Size(size.width, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        listOf(0.75f to Danger, 0.45f to Warn, 0.2f to Good).forEach { (edge, zone) ->
            val span = edge / range * half
            drawRoundRect(zone.copy(alpha = 0.45f), Offset(mid - span, barTop), Size(span * 2, 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        }
        drawLine(Color.White, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2.dp.toPx())
        val x = mid + offset.coerceIn(-range, range) / range * half
        drawCircle(color, radius = 13.dp.toPx(), center = Offset(x, size.height / 2))
        drawCircle(Color(0xFF0E1113), radius = 5.dp.toPx(), center = Offset(x, size.height / 2))
    }
}

fun stateLabel(state: SystemState): String = when (state) {
    SystemState.ReadyToCalibrate -> "جاهز للمعايرة"
    SystemState.CalibrationIncomplete -> "المعايرة غير مكتملة"
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

private fun stateColor(severity: Severity) = when (severity) {
    Severity.Critical -> Danger
    Severity.Warning -> Warn
    Severity.Correction -> Tactile
    Severity.Info -> Good
    Severity.Silent -> Color(0xFF6B7579)
}

// ---------- Session summary ----------

@Composable
fun SessionSummaryScreen(summary: SessionSummary, distanceText: String?, onDone: () -> Unit) {
    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("انتهت الجلسة", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        Text(
            "هذه قياسات من جلستك الفعلية على هذا الهاتف.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("المدة", clock(summary.durationMs), Modifier.weight(1f))
            MetricTile("مسار محدد", String.format(Locale.US, "%.0f%%", summary.trackedShare * 100), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("تنبيهات توقف", "${summary.stopAlerts}", Modifier.weight(1f))
            MetricTile("تنبيهات عوائق", "${summary.obstacleAlerts}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("زمن المعالجة (وسيط)", String.format(Locale.US, "%.0f ملّي ث", summary.processingP50Ms), Modifier.weight(1f))
            MetricTile("أسوأ 10%", String.format(Locale.US, "%.0f ملّي ث", summary.processingP90Ms), Modifier.weight(1f))
        }
        MetricTile("إطارات محللة", "${summary.frames}", Modifier.fillMaxWidth())
        distanceText?.let { MetricTile("المسافة (GPS)", it, Modifier.fillMaxWidth()) }
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(28.dp),
        ) { Text("تم", style = MaterialTheme.typography.headlineSmall) }
    }
}

// ---------- Shared pieces ----------

@Composable
fun Panel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
