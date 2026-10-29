package com.basir.run

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.basir.run.tracking.GpsDiagnostics
import java.util.Locale

// ---------- Welcome and limits ----------

@Composable
fun WelcomeScreen(onAccept: () -> Unit) {
    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("بصير ران", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        Text(
            "يساعد المكفوفين وضعاف البصر على الجري في مسارات مهيأة. كاميرا الهاتف ترى المسار أمامك، والتطبيق يوجّهك بالصوت والاهتزاز.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LimitsCard(
            "ما يفعله",
            listOf(
                "يحدد حدود الحارة أو الممر ومنتصفه واتجاهه ومنحنياته عندما تكون واضحة.",
                "يكتشف بعض العوائق مثل الأشخاص والدراجات والمقاعد.",
                "يطلب منك التوقف عندما لا يستطيع تحديد المسار بثقة.",
            ),
        )
        LimitsCard(
            "ما لا يستطيعه",
            listOf(
                "لا يضمن سلامتك، ولا يكتشف كل المخاطر.",
                "لا يكتشف الأعمدة وجذوع الأشجار والحفر والحواف والدرجات بشكل موثوق.",
                "لا يعرف المسافة الدقيقة إلى العوائق.",
                "يحتاج حاملاً ثابتاً على الصدر وإضاءة كافية ومساراً واضح الحدود.",
            ),
        )
        LimitsCard(
            "قبل أول جري",
            listOf(
                "جرّب مع مرافق مبصر يجري بجانبك.",
                "ابدأ بالمشي، ثم بالجري الخفيف.",
                "إذا قال التطبيق «توقف»، توقف فوراً.",
            ),
        )
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(28.dp),
        ) { Text("فهمت وأوافق", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun LimitsCard(title: String, lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
    }
}

// ---------- Permissions ----------

data class PermissionItem(val title: String, val why: String, val granted: Boolean, val request: () -> Unit)

@Composable
fun PermissionsScreen(items: List<PermissionItem>, onContinue: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("الأذونات", onBack)
        items.forEach { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(item.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.granted) {
                    Text("✓ ممنوح", style = MaterialTheme.typography.labelLarge, color = Good)
                } else {
                    Button(onClick = item.request, modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("منح الإذن", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(24.dp),
        ) { Text("متابعة", style = MaterialTheme.typography.titleMedium) }
    }
}

// ---------- Home ----------

@Composable
fun HomeScreen(
    history: List<RunRecord>,
    notice: String?,
    onGuidedRun: () -> Unit,
    onGpsOnlyRun: () -> Unit,
    onEmergency: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("بصير ران", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                Text("توجيه الجري بالكاميرا", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "الإعدادات والسجل", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(236.dp)
                    .clip(CircleShape)
                    .background(Tactile)
                    .clickable(onClickLabel = "بدء جلسة توجيه", onClick = onGuidedRun)
                    .clearAndSetSemantics { contentDescription = "بدء جلسة توجيه بالكاميرا" },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ابدأ", fontSize = 52.sp, style = MaterialTheme.typography.headlineLarge, color = OnTactile)
                    Text("جلسة توجيه", style = MaterialTheme.typography.titleMedium, color = OnTactile)
                }
            }
        }

        if (notice != null) {
            Text(notice, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        HoldButton(
            label = "الطوارئ",
            hint = "اضغط مطولاً لفتح شاشة الطوارئ",
            container = Danger,
            content = Color.White,
            height = 88.dp,
            onTap = onEmergency,
            onHold = onEmergency,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .clickable(onClickLabel = "تسجيل جري بدون توجيه", onClick = onGpsOnlyRun)
                .padding(18.dp),
        ) {
            Column {
                Text("تسجيل المسافة فقط", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("بدون كاميرا ولا توجيه. للجري مع مرافق.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (history.isNotEmpty()) {
            SectionTitle("آخر الجريات")
            history.take(5).forEach { HistoryRow(it) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun HistoryRow(run: RunRecord) {
    val (number, unit) = distanceParts(run.distanceM)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(runDate(run.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("$number $unit · ${clock(run.durationMs)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------- GPS-only run ----------

fun gpsLine(gps: GpsDiagnostics, accuracyM: Float?): String = String.format(
    Locale.US,
    "GPS: %d قراءة · بالسرعة %d · بالموقع %d · مرفوضة %d · دقة %s",
    gps.fixes, gps.usedSpeed, gps.usedPosition, gps.rejectedAccuracy + gps.rejectedJump,
    accuracyM?.let { "±${it.toInt()} م" } ?: "غير معروفة",
)

@Composable
fun RunScreen(run: RunState, onStopHint: () -> Unit, onStop: () -> Unit, onSos: () -> Unit) {
    val (number, unit) = distanceParts(run.distanceM)
    Column(
        modifier = screenModifier().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill("تسجيل المسافة", Good)
            Spacer(Modifier.weight(1f))
            StatusPill(accuracyLabel(run.accuracyM), accuracyColor(run.accuracyM))
        }
        Text(gpsLine(run.gps, run.accuracyM), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "المسافة ${spokenDistance(run.distanceM)}" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(number, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(unit, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("الزمن", clock(run.elapsedMs), Modifier.weight(1f))
            MetricTile("الإيقاع /كم", pace(run.paceSecPerKm), Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        HoldButton("إنهاء الجري", "اضغط مطولاً للإنهاء", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, 104.dp, onStopHint, onStop)
        HoldButton("طوارئ", "اضغط مطولاً", Danger, Color.White, 76.dp, {}, onSos)
    }
}

@Composable
fun SummaryScreen(run: RunRecord, onDone: () -> Unit) {
    val (number, unit) = distanceParts(run.distanceM)
    Column(
        modifier = screenModifier().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("أحسنت!", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(unit, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("الزمن", clock(run.durationMs), Modifier.weight(1f))
            MetricTile("متوسط الإيقاع", pace(run.paceSecPerKm), Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(28.dp)) {
            Text("تم", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

// ---------- Settings and history ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(prefs: Prefs, voice: Voice, tone: ToneGuide, history: List<RunRecord>, onTestVibration: () -> Unit, onBack: () -> Unit) {
    var every by remember { mutableIntStateOf(prefs.announceEveryM) }
    var voiceOn by remember { mutableStateOf(prefs.voiceOn) }
    var vibrate by remember { mutableStateOf(prefs.vibrateOn) }
    var tones by remember { mutableStateOf(prefs.guideTones) }
    var gps by remember { mutableStateOf(prefs.recordGps) }

    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("الإعدادات", onBack)

        SectionTitle("اللغة وإمكانية الوصول")
        Text(
            "اللغة: العربية. حجم الخط يتبع إعدادات الهاتف، وكل الأزرار تعمل مع TalkBack. التسجيلات الصوتية الخاصة: ${voice.recordedCount} جملة.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingSwitch("التعليمات الصوتية", "التحذيرات الحرجة تُقال دائماً حتى لو أُطفئ هذا", voiceOn) { voiceOn = it; prefs.voiceOn = it }
        SettingSwitch("الاهتزاز", "أنماط مميزة للتوجيه والتحذير والتوقف", vibrate) { vibrate = it; prefs.vibrateOn = it }
        SettingSwitch("نغمات الاتجاه", "نغمة في الأذن التي يجب أن تتجه نحوها", tones) { tones = it; prefs.guideTones = it }
        SettingSwitch("تسجيل المسافة بـ GPS", "للسجل فقط. لا يُستخدم GPS في التوجيه داخل المسار", gps) { gps = it; prefs.recordGps = it }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("إعلان المسافة كل")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 200, 400, 1000).forEach { meters ->
                    FilterChip(
                        selected = every == meters,
                        onClick = { every = meters; prefs.announceEveryM = meters },
                        label = { Text(if (meters < 1000) "$meters م" else "1 كم", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }

        SectionTitle("اختبار الصوت والاهتزاز")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TestButton("نغمة يمين", Modifier.weight(1f)) { tone.play(1f) }
            TestButton("نغمة يسار", Modifier.weight(1f)) { tone.play(-1f) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TestButton("صوت التوجيه", Modifier.weight(1f)) { voice.say(com.basir.run.engine.Phrase.Centered, interrupt = true) }
            TestButton("أنماط الاهتزاز", Modifier.weight(1f), onTestVibration)
        }

        if (history.isNotEmpty()) {
            SectionTitle("سجل الجري")
            history.forEach { HistoryRow(it) }
        }
        Text("بصير ران 0.4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TestButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
