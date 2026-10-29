package com.basir.run

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface GpsStatus {
    data object NoPermission : GpsStatus
    data object Searching : GpsStatus
    data class Ready(val accuracyM: Float) : GpsStatus
}

private val screenModifier: Modifier
    @Composable get() = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)

// ---------- Home ----------

@Composable
fun HomeScreen(
    gps: GpsStatus,
    history: List<RunRecord>,
    notice: String?,
    onStart: () -> Unit,
    onTone: (Float) -> Unit,
    onSosHint: () -> Unit,
    onSos: () -> Unit,
    onSettings: () -> Unit,
    onRefreshGps: () -> Unit,
) {
    Column(
        modifier = screenModifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "بصير",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Text("رفيقك في الجري", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "الإعدادات", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        val (gpsText, gpsColor) = when (gps) {
            GpsStatus.NoPermission -> "اسمح بالموقع للبدء" to Danger
            GpsStatus.Searching -> "جارٍ تحديد الموقع" to Color(0xFF6B7579)
            is GpsStatus.Ready -> accuracyLabel(gps.accuracyM) to accuracyColor(gps.accuracyM)
        }
        StatusPill(gpsText, gpsColor, Modifier.clickable(onClickLabel = "تحديث الموقع", onClick = onRefreshGps))

        StartButton(onStart)

        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LastRunCard(history.firstOrNull())

        SectionTitle("اختبار الاتجاه بالسماعة")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // In a right-to-left layout the first item sits on the right, matching the ear it plays in.
            ToneTile("نغمة يمين", "تُسمع في الأذن اليمنى", Modifier.weight(1f)) { onTone(1f) }
            ToneTile("نغمة يسار", "تُسمع في الأذن اليسرى", Modifier.weight(1f)) { onTone(-1f) }
        }

        HoldButton(
            label = "طوارئ",
            hint = "اضغط مطولاً لإرسال موقعك",
            container = Danger,
            content = Color.White,
            height = 96.dp,
            onTap = onSosHint,
            onHold = onSos,
        )

        if (history.size > 1) {
            SectionTitle("الجري السابق")
            history.drop(1).take(5).forEach { HistoryRow(it) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StartButton(onStart: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "ring",
    )
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(268.dp)
                .scale(ring)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(236.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClickLabel = "ابدأ الجري", onClick = onStart)
                .clearAndSetSemantics { contentDescription = "ابدأ الجري" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ابدأ", fontSize = 52.sp, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimary)
                Text("الجري", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun LastRunCard(run: RunRecord?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (run == null) {
            Text("لم تجرِ بعد", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "اضغط ابدأ عندما تكون في المضمار. سيعلن بصير المسافة والزمن بالصوت.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("آخر جري", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(runDate(run.startedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val (number, unit) = distanceParts(run.distanceM)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("المسافة", "$number $unit", Modifier.weight(1f))
            MetricTile("الزمن", clock(run.durationMs), Modifier.weight(1f))
            MetricTile("الإيقاع", pace(run.paceSecPerKm), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToneTile(title: String, hint: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryRow(run: RunRecord) {
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

// ---------- Live run ----------

@Composable
fun RunScreen(run: RunState, onStopHint: () -> Unit, onStop: () -> Unit, onSosHint: () -> Unit, onSos: () -> Unit) {
    val (number, unit) = distanceParts(run.distanceM)
    Column(
        modifier = screenModifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill("الجري مستمر", Good)
            Spacer(Modifier.weight(1f))
            StatusPill(accuracyLabel(run.accuracyM), accuracyColor(run.accuracyM))
        }

        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = "المسافة ${spokenDistance(run.distanceM)}" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(number, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(unit, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("الزمن", clock(run.elapsedMs), Modifier.weight(1f))
            MetricTile("الإيقاع /كم", pace(run.paceSecPerKm), Modifier.weight(1f))
            MetricTile("اللفات", "${(run.distanceM / RunRecord.TRACK_LAP_M).toInt()}", Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))

        HoldButton(
            label = "إنهاء الجري",
            hint = "اضغط مطولاً للإنهاء",
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            height = 112.dp,
            onTap = onStopHint,
            onHold = onStop,
        )
        HoldButton(
            label = "طوارئ",
            hint = "اضغط مطولاً لإرسال موقعك",
            container = Danger,
            content = Color.White,
            height = 80.dp,
            onTap = onSosHint,
            onHold = onSos,
        )
    }
}

// ---------- Summary ----------

@Composable
fun SummaryScreen(run: RunRecord, onDone: () -> Unit) {
    val (number, unit) = distanceParts(run.distanceM)
    Column(
        modifier = screenModifier.padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            "أحسنت!",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(unit, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("الزمن", clock(run.durationMs), Modifier.weight(1f))
            MetricTile("متوسط الإيقاع", pace(run.paceSecPerKm), Modifier.weight(1f))
            MetricTile("اللفات", "${run.laps}", Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        ) {
            Text("تم", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

// ---------- Settings ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    var phone by remember { mutableStateOf(prefs.emergencyPhone) }
    var every by remember { mutableIntStateOf(prefs.announceEveryM) }
    var voice by remember { mutableStateOf(prefs.voiceOn) }
    var vibrate by remember { mutableStateOf(prefs.vibrateOn) }

    Column(
        modifier = screenModifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("الإعدادات", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("جهة الطوارئ")
            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    prefs.emergencyPhone = it.trim()
                },
                label = { Text("رقم الجوال") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "زر الطوارئ يفتح رسالة لهذا الرقم فيها رابط موقعك على الخريطة.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("إعلان المسافة كل")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 200, 400, 1000).forEach { meters ->
                    FilterChip(
                        selected = every == meters,
                        onClick = {
                            every = meters
                            prefs.announceEveryM = meters
                        },
                        label = { Text(if (meters < 1000) "$meters م" else "1 كم", style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
        }

        SettingSwitch("الإعلان بالصوت", "يقول المسافة والزمن أثناء الجري", voice) {
            voice = it
            prefs.voiceOn = it
        }
        SettingSwitch("الاهتزاز", "نبضة في الجوال مع كل إعلان", vibrate) {
            vibrate = it
            prefs.vibrateOn = it
        }

        Text("بصير 0.2", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
