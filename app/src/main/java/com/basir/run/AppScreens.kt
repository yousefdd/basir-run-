package com.basir.run

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Phrase

// ---------- Home ----------

data class SystemStatus(
    val cameraAllowed: Boolean,
    val detectorReady: Boolean,
    val depthReady: Boolean,
    val vibration: Boolean,
    val contact: Contact?,
    /** false when the phone has no Arabic text-to-speech voice; recorded phrases still play. */
    val arabicTextToSpeech: Boolean? = null,
)

/**
 * The only home screen: the mark, one large start button, a short status of what the run
 * depends on, the emergency button and the way to settings. There is no route type to choose.
 */
@Composable
fun HomeScreen(status: SystemStatus, notice: String?, onStart: () -> Unit, onEmergency: () -> Unit, onSettings: () -> Unit, onDeveloper: () -> Unit) {
    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasirLogo(64.dp)
                Column(Modifier.weight(1f)) {
                    Text("بصير ران", style = MaterialTheme.typography.headlineLarge, color = Ink, modifier = Modifier.semantics { heading() })
                    Text("توجيه بلغة الاهتزاز للجري للمكفوفين", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                }
                SpeakButton("الإعدادات", onSettings, hint = null, icon = Icons.Filled.Settings, height = 52.dp, spoken = Phrase.ButtonSettings.ar)
            }
            Spacer(Modifier.height(8.dp))

            SpeakButton(
                label = "ابدأ الجري",
                spoken = Phrase.ButtonStart.ar,
                onHold = onStart,
                tone = ButtonTone.Primary,
                icon = Icons.Filled.PlayArrow,
                height = 132.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            notice?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Amber, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }

            Card {
                SectionTitle("حالة النظام")
                StatusLine(if (status.cameraAllowed) "الكاميرا: مسموح بها، تُفحص عند البدء" else "الكاميرا: تحتاج إذناً", if (status.cameraAllowed) Good else Danger)
                StatusLine(if (status.detectorReady) "كاشف العوائق: محمّل على الهاتف" else "كاشف العوائق: غير محمّل، لن يبدأ التوجيه", if (status.detectorReady) Good else Danger)
                StatusLine(if (status.depthReady) "تقدير العمق: محمّل على الهاتف" else "تقدير العمق: غير محمّل، لن يبدأ التوجيه", if (status.depthReady) Good else Danger)
                StatusLine(if (status.vibration) "الاهتزاز: متاح" else "الاهتزاز غير متاح: سيعتمد التوجيه على الصوت", if (status.vibration) Good else Amber)
                StatusLine(status.contact?.let { "جهة الطوارئ: ${it.name}" } ?: "لا توجد جهة اتصال موثوقة", if (status.contact != null) Good else Amber)
                StatusLine("التتبع: يبدأ بعد فحص المسار", Blue)
                if (status.arabicTextToSpeech == false) {
                    StatusLine("لا يوجد قارئ نصوص عربي في الهاتف: الجمل المسجّلة تعمل، والتفاصيل الأخرى لن تُقرأ", Amber)
                }
            }

            SpeakButton(
                label = "طوارئ",
                spoken = Phrase.ButtonEmergency.ar,
                onHold = onEmergency,
                tone = ButtonTone.Danger,
                icon = Icons.Filled.Warning,
                height = 80.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            // A small, silent entry for the developer in the bottom corner. It opens only after
            // a six-second press, so a blind runner cannot land in it by accident.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val voice = LocalVoice.current
                Text(
                    Phrase.BeltSoon.ar,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkMuted,
                    modifier = Modifier.weight(1f).clickable { voice?.say(Phrase.BeltSoon, interrupt = true) },
                )
                LongHoldIcon(Icons.Filled.Build, holdMs = 6_000, onDone = onDeveloper)
            }
        }
    }
}

// ---------- First use ----------

val GUIDE = listOf(
    Phrase.OnboardingIntro,
    Phrase.OnboardingLight,
    Phrase.OnboardingMount,
    Phrase.OnboardingSafe,
    Phrase.OnboardingCamera,
    Phrase.OnboardingStop,
)

/** The first-use guide; read aloud on entry. Shown once at first start and kept in settings. */
@Composable
fun GuideScreen(voice: Voice, nextLabel: String, onNext: () -> Unit, onBack: (() -> Unit)?) {
    LaunchedEffect(Unit) {
        voice.stopAll()
        GUIDE.forEach { voice.say(it) }
    }
    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onBack != null) ScreenHeader("قبل أول جري", onBack)
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasirLogo(56.dp)
                Text("قبل أول جري", style = MaterialTheme.typography.headlineLarge, color = Ink, modifier = Modifier.semantics { heading() })
            }
            GUIDE.drop(1).forEach { p ->
                Card { Text(p.ar, style = MaterialTheme.typography.bodyLarge, color = if (p == Phrase.OnboardingStop) Tactile else Ink) }
            }
            Text(
                "أثناء الجري: إذا فُقد المسار أو لم يعد التقدير موثوقاً، يهتز الهاتف اهتزازاً طويلاً ويطلب منك التوقف.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
            SpeakButton("اقرأ مرة أخرى", { voice.stopAll(); GUIDE.forEach { voice.say(it) } }, spoken = Phrase.ButtonReadAgain.ar, modifier = Modifier.fillMaxWidth())
            SpeakButton(nextLabel, { voice.stopAll(); onNext() }, tone = ButtonTone.Primary, height = 76.dp, modifier = Modifier.fillMaxWidth())
        }
    }
}

data class PermissionItem(val title: String, val why: String, val granted: Boolean, val allowSpoken: String, val request: () -> Unit)

@Composable
fun PermissionsScreen(items: List<PermissionItem>, onContinue: () -> Unit) {
    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("الأذونات", null)
            items.forEach { item ->
                Card {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text(item.why, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                    if (item.granted) StatusLine("مسموح", Good)
                    else SpeakButton("السماح", item.request, spoken = item.allowSpoken, tone = ButtonTone.Primary, modifier = Modifier.fillMaxWidth())
                }
            }
            SpeakButton("التالي", onContinue, tone = ButtonTone.Primary, height = 76.dp, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ContactStepScreen(prefs: Prefs, voice: Voice, onContinue: () -> Unit) {
    var contacts by remember { mutableStateOf(prefs.contacts) }
    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenHeader("جهة الاتصال الموثوقة", null)
            Text(
                "عند الضغط المطوّل على زر الطوارئ تُجهَّز رسالة بموقعك لهذه الجهة ثم تُفتح شاشة الاتصال بها.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
            TrustedContactsEditor(prefs, voice) { contacts = it }
            SpeakButton(
                if (contacts.isEmpty()) Phrase.ButtonNextNoContact.ar else Phrase.ButtonNext.ar,
                {
                    if (contacts.isEmpty()) voice.say(Phrase.SosNoContact, interrupt = true)
                    onContinue()
                },
                tone = if (contacts.isEmpty()) ButtonTone.Neutral else ButtonTone.Primary,
                height = 76.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Picks one phone number from the phone's contacts; no contacts permission is needed. */
private class PickPhone : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

private fun readContact(context: Context, uri: Uri): Contact? = try {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { c ->
        if (c.moveToFirst()) Contact(c.getString(0) ?: "جهة اتصال", c.getString(1) ?: return@use null) else null
    }
} catch (e: Exception) {
    null
}

@Composable
fun TrustedContactsEditor(prefs: Prefs, voice: Voice, onChange: (List<Contact>) -> Unit = {}) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf(prefs.contacts) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    fun save(list: List<Contact>) {
        contacts = list
        prefs.contacts = list
        onChange(list)
    }

    val picker = rememberLauncherForActivityResult(PickPhone()) { uri ->
        val c = uri?.let { readContact(context, it) }
        if (c != null && contacts.none { it.phone == c.phone }) {
            save(contacts + c)
            note = "أُضيفت ${c.name}"
            voice.say(Phrase.ContactAdded, interrupt = true)
            voice.sayText(c.name)
        }
    }

    contacts.forEachIndexed { i, c ->
        Card {
            Text(if (i == 0) "${c.name} · يُتصل بها أولاً" else c.name, style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(c.phone, style = MaterialTheme.typography.labelMedium, color = InkMuted)
            SpeakButton("حذف", { save(contacts - c) }, spoken = Phrase.ButtonDeleteContact.ar, hint = null, height = 48.dp, modifier = Modifier.fillMaxWidth())
        }
    }
    SpeakButton(
        "اختيار من جهات الاتصال", { picker.launch(Unit) },
        spoken = Phrase.ButtonPickContact.ar, tone = ButtonTone.Primary, modifier = Modifier.fillMaxWidth(),
    )
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Teal, unfocusedBorderColor = NavyRaised, focusedLabelColor = Teal, unfocusedLabelColor = InkMuted)
    OutlinedTextField(name, { name = it }, label = { Text("أو اكتب الاسم") }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        phone, { phone = it }, label = { Text("رقم الجوال مع رمز الدولة") }, singleLine = true, colors = fieldColors,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
    )
    SpeakButton("إضافة", {
        if (name.isNotBlank() && phone.isNotBlank()) {
            save(contacts + Contact(name.trim(), phone.trim()))
            note = "أُضيفت جهة الاتصال"
            name = ""
            phone = ""
        } else {
            note = "اكتب الاسم والرقم"
        }
        note?.let { voice.sayText(it, interrupt = true) }
    }, spoken = Phrase.ButtonAddContact.ar, modifier = Modifier.fillMaxWidth())
    note?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Tactile, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(
    prefs: Prefs,
    voice: Voice,
    onTraining: () -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
) {
    var vibrate by remember { mutableStateOf(prefs.vibrateOn) }
    var verbose by remember { mutableStateOf(prefs.verboseVoice) }
    var tones by remember { mutableStateOf(prefs.guideTones) }
    var developer by remember { mutableStateOf(prefs.developerMode) }
    var localNumber by remember { mutableStateOf(prefs.localEmergencyNumber) }
    var heightText by remember { mutableStateOf(prefs.runnerHeightCm.toString()) }

    BasirBackground {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("الإعدادات", onBack)

            SectionTitle("الاهتزاز")
            SpeakButton("أنماط الاهتزاز واختبارها", onTraining, spoken = Phrase.ButtonHapticLessons.ar, modifier = Modifier.fillMaxWidth())
            SpeakSwitch(Phrase.SwitchVibration.ar, "قناة التوجيه الأساسية", vibrate) { vibrate = it; prefs.vibrateOn = it }

            SectionTitle("الكاميرا")
            OutlinedTextField(
                heightText,
                { v ->
                    heightText = v.filter { it.isDigit() }.take(3)
                    heightText.toIntOrNull()?.takeIf { it in 100..220 }?.let { prefs.runnerHeightCm = it }
                },
                label = { Text("طولك بالسنتيمتر") },
                supportingText = { Text(String.format(java.util.Locale.US, "الكاميرا على الصدر على ارتفاع %.2f م تقريباً؛ المسافات على الأرض تُحسب منه", prefs.cameraHeightM)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Teal, unfocusedBorderColor = NavyRaised, focusedLabelColor = Teal, unfocusedLabelColor = InkMuted),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("الطوارئ")
            TrustedContactsEditor(prefs, voice)
            OutlinedTextField(
                localNumber,
                { localNumber = it; prefs.localEmergencyNumber = it.trim() },
                label = { Text("رقم الطوارئ في بلدك (اكتبه بنفسك)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Teal, unfocusedBorderColor = NavyRaised, focusedLabelColor = Teal, unfocusedLabelColor = InkMuted),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("الصوت")
            SpeakSwitch(Phrase.SwitchVoice.ar, "يقول التصحيحات والمنحنيات بالكلام أيضاً", verbose) { verbose = it; prefs.verboseVoice = it }
            SpeakSwitch(Phrase.SwitchTones.ar, "نغمة قصيرة في الأذن جهة الحركة، مع سماعة", tones) { tones = it; prefs.guideTones = it }
            Text(
                if (voice.recordedCount > 0) "تسجيلات صوتية مضافة: ${voice.recordedCount}" else "الجمل تُقرأ بصوت الهاتف. يمكن استبدالها بتسجيلات.",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
            )

            SectionTitle("عام")
            SpeakButton("إرشادات الاستخدام الأول", onGuide, spoken = Phrase.ButtonGuide.ar, modifier = Modifier.fillMaxWidth())
            SpeakSwitch(Phrase.SwitchDeveloper.ar, "يعرض الكاميرا والمسار الافتراضي والأرقام أثناء الجري، للاختبار", developer) { developer = it; prefs.developerMode = it }
            Text("الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = InkMuted)
        }
    }
}
