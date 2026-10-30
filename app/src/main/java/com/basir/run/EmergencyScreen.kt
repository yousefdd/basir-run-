package com.basir.run

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.basir.run.engine.Phrase
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single emergency flow, started by a long press on the emergency button on the home or
 * run screen:
 * 1. says that the procedure has started and checks for a network connection;
 * 2. takes the current location from the Fused Location Provider if it is allowed and found
 *    within 10 seconds, and builds a map link;
 * 3. opens the messaging app with an SOS message (and the link) to every trusted contact;
 * 4. when the user comes back, opens the phone app with the first trusted contact's number.
 * Android does not let an ordinary app send a message or place a call without the user's last
 * tap, so every step says exactly what was opened and never claims that anything was sent.
 * In test mode nothing is opened: each step only says what would have happened, unless the
 * user explicitly chooses to open the messaging app.
 */
@Composable
fun EmergencyScreen(prefs: Prefs, voice: Voice, test: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val contacts = remember { prefs.contacts }
    val localNumber = remember { prefs.localEmergencyNumber }
    val log = remember { mutableStateListOf<Pair<String, Color>>() }
    var link by remember { mutableStateOf<String?>(null) }
    var located by remember { mutableStateOf(false) }
    var callWhenBack by remember { mutableStateOf(false) }

    fun report(phrase: Phrase, color: Color, extra: String = "") {
        log += (phrase.ar + extra) to color
        voice.say(phrase)
    }

    fun message(): String = if (link != null) "أحتاج مساعدة. هذا موقعي الآن: $link" else "أحتاج مساعدة. لم أتمكن من تحديد موقعي."

    fun openMessage(really: Boolean) {
        if (contacts.isEmpty()) return report(Phrase.SosNoContact, Danger)
        if (!really) return report(Phrase.SosTest, Amber, ". كانت ستُفتح رسالة إلى ${contacts.joinToString("، ") { it.name }}: «${message()}»")
        val numbers = contacts.joinToString(";") { it.phone }
        if (start(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$numbers")).putExtra("sms_body", message()))) {
            report(Phrase.SosMessageOpened, Good)
            callWhenBack = !test
        } else {
            report(Phrase.SosNoApp, Danger, " (الرسائل)")
        }
    }

    fun dial(number: String?, label: String) {
        if (number.isNullOrBlank()) return report(Phrase.SosNoContact, Danger)
        if (test) return report(Phrase.SosTest, Amber, ". كانت ستُفتح شاشة الاتصال بـ $label")
        if (start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) report(Phrase.SosDialOpened, Good)
        else report(Phrase.SosNoApp, Danger, " (الاتصال)")
    }

    fun share() {
        if (test) return report(Phrase.SosTest, Amber, ". كانت ستُفتح مشاركة: «${message()}»")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message())
        if (start(context, Intent.createChooser(send, "مشاركة موقعي"))) report(Phrase.SosShareOpened, Good)
        else report(Phrase.SosNoApp, Danger)
    }

    LaunchedEffect(Unit) {
        voice.stopAll()
        report(if (test) Phrase.SosTest else Phrase.SosStarted, if (test) Amber else Danger)
        if (!hasInternet(context)) report(Phrase.SosNoNetwork, Amber)
        val location = if (context.has(Manifest.permission.ACCESS_FINE_LOCATION) || context.has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            withTimeoutOrNull(10_000) { currentLocation(context) }
        } else null
        located = true
        if (location != null) {
            link = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            report(Phrase.SosLocationReady, Good, String.format(java.util.Locale.US, " (±%.0f م)", location.accuracy))
        } else {
            report(Phrase.SosNoLocation, Amber)
        }
        openMessage(really = !test)
        if (!test && contacts.isEmpty() && localNumber.isNotBlank()) dial(localNumber, localNumber)
        if (test) dial(contacts.firstOrNull()?.phone, contacts.firstOrNull()?.name ?: "")
    }

    // Back from the messaging app: go on to the call.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && callWhenBack) {
                callWhenBack = false
                contacts.firstOrNull()?.let { dial(it.phone, it.name) } ?: dial(localNumber, localNumber)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BasirBackground(animated = false) {
        Column(
            modifier = screenModifier().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader(if (test) "اختبار الطوارئ" else "الطوارئ", onBack)
            Card {
                if (!located) StatusLine("جارٍ تحديد الموقع…", Blue)
                log.forEach { (text, color) ->
                    StatusLine(text, color, Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
            }
            contacts.firstOrNull()?.let { c ->
                SpeakButton("اتصال بـ ${c.name}", { dial(c.phone, c.name) }, spoken = Phrase.ButtonCallContact.ar, tone = ButtonTone.Danger, height = 76.dp, modifier = Modifier.fillMaxWidth())
            }
            SpeakButton("فتح رسالة الطوارئ", { openMessage(really = !test) }, spoken = Phrase.ButtonOpenMessage.ar, tone = ButtonTone.Danger, modifier = Modifier.fillMaxWidth())
            SpeakButton("مشاركة الموقع بتطبيق آخر", ::share, spoken = Phrase.ButtonShareLocation.ar, modifier = Modifier.fillMaxWidth())
            if (localNumber.isNotBlank()) {
                SpeakButton("اتصال برقم الطوارئ $localNumber", { dial(localNumber, localNumber) }, spoken = Phrase.ButtonCallLocal.ar, tone = ButtonTone.Danger, modifier = Modifier.fillMaxWidth())
            }
            if (test) {
                SpeakButton(
                    "تجربة فعلية: فتح تطبيق الرسائل",
                    { openMessage(really = true) },
                    spoken = Phrase.ButtonRealTrial.ar,
                    tone = ButtonTone.Accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "لا يرسل التطبيق ولا يتصل وحده: يفتح لك الرسالة وشاشة الاتصال، والإرسال والاتصال يحتاجان ضغطتك. قد لا يتوفر الموقع أو الشبكة في كل مكان.",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
            )
        }
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// One fresh fix from the Fused Location Provider. Callers check the permission first.
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? = suspendCancellableCoroutine { cont ->
    LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
}

private fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: android.content.ActivityNotFoundException) {
    false
}
