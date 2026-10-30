package com.basir.run

import android.os.SystemClock
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.basir.run.engine.AnnouncementPolicy
import com.basir.run.engine.Cue
import com.basir.run.engine.Output
import com.basir.run.engine.Decision
import com.basir.run.engine.DecisionEngine
import com.basir.run.engine.EngineInput
import com.basir.run.engine.Phrase
import com.basir.run.engine.Severity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import com.basir.run.sensors.MotionMonitor
import com.basir.run.vision.Calibration
import com.basir.run.vision.FrameSnapshot
import com.basir.run.vision.GuidancePipeline
import kotlinx.coroutines.delay

/** The engine check is behind a convenience gate for testing, not a security measure. */
private const val DEVELOPER_CODE = "00000000"
private const val DEVELOPER_EMAIL = "yousefgattal75@gmail.com"

@Composable
fun DeveloperLoginScreen(onOpen: () -> Unit, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    BasirBackground {
        Column(
            modifier = screenModifier().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("فحص محرك الاكتشاف", onBack, silent = true)
            OutlinedTextField(
                code,
                { code = it.filter(Char::isDigit).take(16); wrong = false },
                label = { Text("كلمة السر") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Teal, unfocusedBorderColor = NavyRaised, focusedLabelColor = Teal, unfocusedLabelColor = InkMuted),
                modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) {
                Text("كلمة السر غير صحيحة", color = Danger, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            SpeakButton(
                "دخول",
                {
                    if (code == DEVELOPER_CODE) onOpen() else wrong = true
                },
                hint = null,
                tone = ButtonTone.Primary,
                silent = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The developer's space: what it is for, how to reach the developer, and the detection engine
 * check behind the password. Silent, like every developer control.
 */
@Composable
fun DeveloperScreen(onEngineCheck: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("مساحة المطوّر والتحديثات", onBack, silent = true)
            Text(
                "هذا القسم مخصص للمطوّر لإجراء التجارب والاختبارات، ومتابعة تطوير التطبيق، واستعراض التحديثات الجديدة. كما يتيح تدوين الاقتراحات والأفكار المستقبلية، وتجربة الميزات قبل اعتمادها رسميًا.",
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
            )
            Card {
                Text("للمراسلة وإرسال الاقتراحات", style = MaterialTheme.typography.labelLarge, color = InkMuted)
                SelectionContainer {
                    Text(
                        DEVELOPER_EMAIL,
                        style = MaterialTheme.typography.titleMedium,
                        color = Teal,
                        modifier = Modifier.clickable {
                            try {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL")))
                            } catch (e: ActivityNotFoundException) {
                                // No mail app: the address stays on screen to copy.
                            }
                        },
                    )
                }
            }
            SpeakButton(
                "فحص محرك الاكتشاف للتطبيق",
                onEngineCheck,
                hint = null,
                tone = ButtonTone.Primary,
                icon = Icons.Filled.PlayArrow,
                height = 76.dp,
                silent = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("يهدف هذا القسم إلى توفير بيئة مرنة للتطوير المستمر، واكتشاف المشكلات ومعالجتها، وتحسين دقة اكتشاف العوائق وكفاءة توجيه المستخدم، بما يساهم في تقديم تجربة أكثر أمانًا وموثوقية واستقرارًا للمكفوفين وضعاف البصر.", style = MaterialTheme.typography.labelMedium, color = InkMuted)
        }
    }
}

/**
 * The detection engine check: the real camera with everything the engines see drawn over it,
 * the guidance decision in plain Arabic, and what the runner would get, played as it is
 * decided: the vibration and the spoken alerts (the same recorded phrases and rules as a run).
 * No readiness gate; the numbers stay below for the developer.
 */
@Composable
fun LiveVisionScreen(pipeline: GuidancePipeline, motion: MotionMonitor, prefs: Prefs, haptics: HapticEngine, voice: Voice, onBack: () -> Unit) {
    var snapshot by remember { mutableStateOf<FrameSnapshot?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var clockMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val engine = remember { DecisionEngine() }
    val policy = remember { AnnouncementPolicy(verbose = prefs.verboseVoice) }
    var decision by remember { mutableStateOf<Decision?>(null) }
    var pattern by remember { mutableStateOf<String?>(null) }

    // The same decision the run screen makes, so the check shows (and vibrates) what the runner would get.
    fun evaluate(now: Long) {
        val s = snapshot ?: return
        val d = engine.decide(
            EngineInput(
                nowMs = now,
                frameAgeMs = now - s.timeMs,
                quality = s.quality,
                path = s.path,
                obstacle = s.obstacle,
                processingP90Ms = s.processingP90Ms,
                cameraPitchDeg = motion.pitchBelowHorizonDeg,
                obstacleKind = s.hazardKind,
                depthStale = s.depthStale,
                line = s.line?.command,
            )
        )
        decision = d
        haptics.guide(d, now)?.let { pattern = it.name }
        for (out in policy.next(d, now)) if (out is Output.Say) voice.say(out.phrase, out.interrupt)
    }

    DisposableEffect(Unit) {
        pipeline.calibration = Calibration()
        pipeline.cameraHeightM = prefs.cameraHeightM
        pipeline.reset()
        haptics.resetGuidance()
        motion.start()
        onDispose {
            motion.stop()
            haptics.resetGuidance()
            voice.stopAll()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            clockMs = SystemClock.elapsedRealtime()
            evaluate(clockMs)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraError == null) {
            CameraHost(pipeline, onSnapshot = {
                snapshot = it
                clockMs = SystemClock.elapsedRealtime()
                evaluate(clockMs)
            }, onError = { cameraError = it })
            PathOverlay(snapshot, clockMs, 0.5f, developer = true, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = screenModifier().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenHeader("فحص محرك الاكتشاف", onBack, silent = true)
            cameraError?.let { Text(it, color = Danger, style = MaterialTheme.typography.titleMedium) }
            val line = snapshot?.line
            Text(
                line?.decision?.mode?.let { modeLabel(it) } ?: "لا مسار بعد",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xCC000000), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DeveloperReadout(snapshot, clockMs, pattern)
            Spacer(Modifier.weight(1f))
            // The guidance in words, as the runner would get it.
            val d = decision
            val color = when (d?.severity) {
                Severity.Critical -> Danger
                Severity.Warning -> Amber
                Severity.Correction -> Blue
                else -> Good
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xE6081226), RoundedCornerShape(22.dp))
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("التوجيه الآن", style = MaterialTheme.typography.labelLarge, color = Tactile)
                Text(d?.let { guidanceWords(it, line?.command?.curveInM) } ?: "بانتظار الصور", style = MaterialTheme.typography.headlineSmall, color = color)
                Text("الاهتزاز: " + (pattern?.let { patternWords(it) } ?: "—"), style = MaterialTheme.typography.titleMedium, color = Ink)
            }
        }
    }
}

/** The decision in a short Arabic sentence; a bend says roughly where it starts (estimated). */
private fun guidanceWords(d: Decision, bendInM: Double?): String {
    val base = Phrase.forCue(d)?.ar ?: stateLabel(d.state)
    val level = when (d.level) { 3 -> " (قوي)"; 2 -> " (متوسط)"; 1 -> " (خفيف)"; else -> "" }
    return when (d.cue) {
        Cue.Correct, Cue.NearEdge -> base + level
        Cue.CurveAhead -> base + (bendInM?.let { String.format(java.util.Locale.US, " بعد %.1f م تقريباً", it) } ?: "")
        else -> base
    }
}

/** The engine's pattern names in Arabic, as the vibration lessons call them. */
private fun patternWords(name: String): String {
    val level = when {
        name.endsWith("_low") -> "خفيف"
        name.endsWith("_medium") -> "متوسط"
        name.endsWith("_high") -> "قوي"
        else -> ""
    }
    return when {
        name == "stop" -> "طويل ثم نبضتان: توقف"
        name == "obstacle" -> "ثلاث نبضات سريعة: انتباه"
        name == "on_path" -> "نقرة خفيفة: على المسار"
        name == "confirm" -> "نبضة تأكيد"
        name.startsWith("correct_left") -> "نبضة قصيرة: تصحيح يسار $level"
        name.startsWith("correct_right") -> "نبضتان قصيرتان: تصحيح يمين $level"
        name.startsWith("curve_left") -> "نبضة طويلة هادئة: منعطف يسار $level"
        name.startsWith("curve_right") -> "نبضتان طويلتان: منعطف يمين $level"
        else -> name
    }.trim()
}
