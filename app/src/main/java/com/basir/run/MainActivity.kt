package com.basir.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BasirTheme {
                // The app is Arabic-first, so it lays out right to left whatever the phone language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    BasirApp()
                }
            }
        }
    }
}

private enum class Page { Home, Settings }

@Composable
private fun BasirApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val store = remember { RunStore(context) }
    val speaker = remember { Speaker(context) }
    val tone = remember { ToneGuide() }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    val run by RunSession.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    var history by remember { mutableStateOf(store.all()) }
    var gps by remember { mutableStateOf<GpsStatus>(GpsStatus.Searching) }
    var notice by remember { mutableStateOf<String?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun tell(text: String) {
        notice = text
        if (prefs.voiceOn) speaker.say(text)
    }

    fun refreshGps() {
        if (!context.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            gps = GpsStatus.NoPermission
            return
        }
        gps = GpsStatus.Searching
        fetchCurrentLocation(context) { location ->
            gps = if (location != null && location.hasAccuracy()) GpsStatus.Ready(location.accuracy) else GpsStatus.Searching
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || context.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            refreshGps()
            afterPermission?.invoke()
        } else {
            tell("بصير يحتاج إذن الموقع الدقيق ليعمل")
        }
        afterPermission = null
    }

    // Location is required. Notifications (Android 13+) let the run show while the screen is off.
    fun withPermissions(action: () -> Unit) {
        val missing = buildList {
            if (!context.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.has(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun startRun() = withPermissions {
        notice = null
        ContextCompat.startForegroundService(context, Intent(context, RunService::class.java).setAction(RunService.ACTION_START))
    }

    fun stopRun() {
        context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_STOP))
    }

    fun sos() {
        val phone = prefs.emergencyPhone
        if (phone.isBlank()) {
            tell("أضف رقم الطوارئ في الإعدادات أولاً")
            page = Page.Settings
            return
        }
        withPermissions {
            tell("جارٍ تحديد موقعك")
            fetchCurrentLocation(context) { location ->
                if (location == null) tell("لم أستطع تحديد موقعك. حاول مرة أخرى")
                else context.startActivity(emergencySmsIntent(phone, location))
            }
        }
    }

    LaunchedEffect(Unit) { refreshGps() }
    LaunchedEffect(run.finished) { if (run.finished != null) history = store.all() }
    BackHandler(enabled = page == Page.Settings) { page = Page.Home }

    val finished = run.finished
    when {
        run.active -> RunScreen(
            run = run,
            onStopHint = { tell("اضغط مطولاً لإنهاء الجري") },
            onStop = ::stopRun,
            onSosHint = { tell("اضغط مطولاً لإرسال موقعك") },
            onSos = ::sos,
        )
        finished != null -> SummaryScreen(finished, onDone = { RunSession.state.value = RunState() })
        page == Page.Settings -> SettingsScreen(prefs, onBack = { page = Page.Home })
        else -> HomeScreen(
            gps = gps,
            history = history,
            notice = notice,
            onStart = ::startRun,
            onTone = { pan -> tone.play(pan) },
            onSosHint = { tell("اضغط مطولاً لإرسال موقعك للطوارئ") },
            onSos = ::sos,
            onSettings = { page = Page.Settings },
            onRefreshGps = ::refreshGps,
        )
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
