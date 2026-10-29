package com.basir.run

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.basir.run.engine.Phrase

/**
 * Emergency contacts and actions. Nothing is sent automatically: the app opens the messaging or
 * phone app with everything filled in, says so, and leaves the final tap to the user. It never
 * claims a message was sent or a call was made. A test mode rehearses the flow without opening
 * anything.
 */
@Composable
fun EmergencyScreen(
    prefs: Prefs,
    voice: Voice,
    hasLocationPermission: () -> Boolean,
    requestLocation: (() -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf(prefs.contacts) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var localNumber by remember { mutableStateOf(prefs.localEmergencyNumber) }
    var status by remember { mutableStateOf("") }

    fun report(phrase: Phrase, extra: String = "") {
        status = phrase.ar + extra
        voice.say(phrase, interrupt = true)
    }

    fun sendLocation(test: Boolean) {
        if (contacts.isEmpty()) {
            report(Phrase.SosNoContact)
            return
        }
        if (test) {
            report(Phrase.SosTest, ". كانت ستُفتح رسالة إلى: ${contacts.joinToString("، ") { it.name }}")
            return
        }
        voice.say(Phrase.SosActivated, interrupt = true)
        val open = { link: String? ->
            val body = if (link != null) "أحتاج مساعدة. هذا موقعي الآن: $link" else "أحتاج مساعدة. لم أتمكن من تحديد موقعي."
            val numbers = contacts.joinToString(";") { it.phone }
            val opened = startSafely(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$numbers")).putExtra("sms_body", body))
            if (opened) report(Phrase.SosMessageOpened) else status = "لا يوجد تطبيق رسائل على هذا الهاتف"
        }
        if (!hasLocationPermission()) {
            report(Phrase.SosNoLocation)
            open(null)
            return
        }
        fetchCurrentLocation(context) { location ->
            if (location == null) report(Phrase.SosNoLocation)
            open(location?.let { mapsLink(it) })
        }
    }

    fun dial(number: String, test: Boolean) {
        if (number.isBlank()) {
            status = "لا يوجد رقم محفوظ"
            return
        }
        if (test) {
            report(Phrase.SosTest, ". كانت ستُفتح شاشة الاتصال بالرقم $number")
            return
        }
        if (startSafely(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) report(Phrase.SosDialOpened)
        else status = "لا يوجد تطبيق اتصال على هذا الهاتف"
    }

    Column(
        modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("الطوارئ", onBack)

        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        BigAction("إرسال موقعي لجهات الاتصال", Danger) { requestLocation { sendLocation(test = false) } }
        contacts.firstOrNull()?.let { first -> BigAction("اتصال بـ ${first.name}", Danger) { dial(first.phone, test = false) } }
        if (localNumber.isNotBlank()) BigAction("اتصال برقم الطوارئ $localNumber", Danger) { dial(localNumber, test = false) }

        SectionTitle("اختبار بدون إرسال")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("اختبار الرسالة", Modifier.weight(1f)) { sendLocation(test = true) }
            SmallAction("اختبار الاتصال", Modifier.weight(1f)) { dial(contacts.firstOrNull()?.phone ?: localNumber, test = true) }
        }

        SectionTitle("جهات الاتصال الموثوقة")
        contacts.forEach { c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(c.phone, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SmallAction("حذف ${c.name}", Modifier) {
                    contacts = contacts - c
                    prefs.contacts = contacts
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            phone, { phone = it }, label = { Text("رقم الجوال مع رمز الدولة") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
        )
        SmallAction("إضافة جهة اتصال", Modifier.fillMaxWidth()) {
            if (name.isNotBlank() && phone.isNotBlank()) {
                contacts = contacts + Contact(name.trim(), phone.trim())
                prefs.contacts = contacts
                name = ""
                phone = ""
                status = "أُضيفت جهة الاتصال"
            } else {
                status = "اكتب الاسم والرقم"
            }
        }

        SectionTitle("رقم الطوارئ في بلدك")
        OutlinedTextField(
            localNumber,
            { localNumber = it; prefs.localEmergencyNumber = it.trim() },
            label = { Text("اكتبه بنفسك، لا يُخمَّن") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "لا يتصل التطبيق ولا يرسل تلقائياً. يفتح لك الرسالة أو شاشة الاتصال، والإرسال يحتاج ضغطتك. قد لا يتوفر الموقع أو الشبكة في كل مكان.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigAction(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

private fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: android.content.ActivityNotFoundException) {
    false
}
