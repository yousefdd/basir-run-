package com.basir.run

import android.os.SystemClock
import androidx.compose.foundation.Image
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

/** The developer area is only a convenience gate for testing, not a security measure. */
private const val DEVELOPER_CODE = "00000000"

@Composable
fun DeveloperLoginScreen(onOpen: () -> Unit, onBack: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    BasirBackground {
        Column(
            modifier = screenModifier().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("المطوّر", onBack, silent = true)
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
 * About the project (supervision by ISTAPS, Larbi Tebessi University) and the live vision view.
 */
@Composable
fun DeveloperScreen(onLiveVision: () -> Unit, onBack: () -> Unit) {
    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("المطوّر", onBack, silent = true)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Image(
                        painterResource(R.drawable.istaps_logo),
                        contentDescription = "شعار معهد علوم وتقنيات النشاطات البدنية والرياضية، جامعة العربي التبسي، تبسة",
                        modifier = Modifier.size(104.dp).clip(CircleShape).background(Color.White),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("بصير ران", style = MaterialTheme.typography.titleLarge, color = Ink)
                        Text("توجيه بلغة الاهتزاز للجري للمكفوفين", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                    }
                }
                Text(
                    "تطبيق أُنجز تحت تأطير معهد علوم وتقنيات النشاطات البدنية والرياضية، جامعة العربي التبسي، تبسة.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                )
                Text("الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            }
            SpeakButton(
                "الرؤية المباشرة بالكاميرا",
                onLiveVision,
                spoken = "الرؤية المباشرة بالكاميرا: ما يراه التطبيق ويكتشفه الآن",
                hint = null,
                tone = ButtonTone.Primary,
                icon = Icons.Filled.PlayArrow,
                height = 76.dp,
                silent = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "تعرض صورة الكاميرا الحقيقية وفوقها ما يكتشفه التطبيق: الخط والمسار الذي يتبعه، والعوائق، والأرض والحفر، والأرقام. لا اهتزاز ولا توجيه فيها.",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
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
            ScreenHeader("الرؤية المباشرة", onBack, silent = true)
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
