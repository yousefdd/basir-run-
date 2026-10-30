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
                "هذا القسم مخصص للمطوّر لإدارة عمليات تطوير التطبيق، وإجراء التجارب والاختبارات على الميزات والوظائف الجديدة، ومتابعة أداء النظام ومدى كفاءته. كما يُستخدم لاستعراض التحديثات الحالية والمستقبلية، وتسجيل الاقتراحات والأفكار التطويرية، وتجربة التحسينات قبل إطلاقها رسميًا للمستخدمين.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
            Text(
                "يهدف هذا القسم إلى توفير بيئة مرنة للتطوير المستمر، واكتشاف المشكلات ومعالجتها، وتحسين دقة اكتشاف العوائق وكفاءة توجيه المستخدم، بما يساهم في تقديم تجربة أكثر أمانًا وموثوقية واستقرارًا للمكفوفين وضعاف البصر.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
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
        }
    }
}

/**
 * The real camera with everything the vision engines see drawn over it, for testing on the
 * spot: the line engine's crossings and followed path, object boxes, depth obstacles and drops,
 * and the numbers. No guidance, no vibration, no readiness gate.
 */
@Composable
fun LiveVisionScreen(pipeline: GuidancePipeline, motion: MotionMonitor, prefs: Prefs, onBack: () -> Unit) {
    var snapshot by remember { mutableStateOf<FrameSnapshot?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var clockMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    DisposableEffect(Unit) {
        pipeline.calibration = Calibration()
        pipeline.cameraHeightM = prefs.cameraHeightM
        pipeline.reset()
        motion.start()
        onDispose { motion.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            clockMs = SystemClock.elapsedRealtime()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraError == null) {
            CameraHost(pipeline, onSnapshot = { snapshot = it; clockMs = SystemClock.elapsedRealtime() }, onError = { cameraError = it })
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
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = line?.decision?.mode?.let { modeLabel(it) } ?: "لا مسار بعد" },
            )
            DeveloperReadout(snapshot, clockMs)
        }
    }
}
