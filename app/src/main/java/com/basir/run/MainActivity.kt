package com.basir.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.basir.run.engine.Phrase
import com.basir.run.engine.RouteMode
import com.basir.run.sensors.MotionMonitor
import com.basir.run.vision.Calibration
import com.basir.run.vision.GuidancePipeline
import com.basir.run.vision.ObstacleDetector

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BasirTheme {
                // Arabic first: right to left whatever the phone language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    BasirApp()
                }
            }
        }
    }

    // During a guided session the volume keys control it without looking at the screen.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handler = VolumeKeys.handler
        if (handler != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event?.repeatCount == 0) handler(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private enum class Page { Welcome, Permissions, Home, Training, Mode, Calibration, Session, SessionSummary, Emergency, Settings, GpsRun }

@Composable
private fun BasirApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val store = remember { RunStore(context) }
    val voice = remember { Voice(context) }
    val tone = remember { ToneGuide() }
    val motion = remember { MotionMonitor(context) }
    val haptics = remember { HapticEngine(context, prefs) }
    val obstacles = remember { ObstacleDetector(context) }
    val pipeline = remember { GuidancePipeline(obstacles) }
    DisposableEffect(Unit) {
        onDispose {
            voice.shutdown()
            obstacles.close()
        }
    }

    val run by RunSession.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (prefs.welcomeAccepted) Page.Home else Page.Welcome) }
    var mode by rememberSaveable { mutableStateOf(RouteMode.Track) }
    var calibration by remember { mutableStateOf(Calibration()) }
    var session by remember { mutableStateOf<GuidanceSession?>(null) }
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var summaryDistance by remember { mutableStateOf<String?>(null) }
    var summaryLog by remember { mutableStateOf<java.io.File?>(null) }
    var trainingThenRun by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(store.all()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var permissionsVersion by remember { mutableIntStateOf(0) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsVersion++
        afterPermission?.invoke()
        afterPermission = null
    }

    fun request(vararg permissions: String, then: (() -> Unit)? = null) {
        val missing = permissions.filter { !context.has(it) }
        if (missing.isEmpty()) {
            then?.invoke()
        } else {
            afterPermission = then
            launcher.launch(missing.toTypedArray())
        }
    }

    fun startDistanceService(quiet: Boolean) {
        if (!context.has(Manifest.permission.ACCESS_FINE_LOCATION)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, RunService::class.java).setAction(RunService.ACTION_START).putExtra(RunService.EXTRA_QUIET, quiet),
        )
    }

    fun stopDistanceService() {
        context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_STOP))
    }

    LaunchedEffect(run.finished) { if (run.finished != null) history = store.all() }

    BackHandler(enabled = page in setOf(Page.Permissions, Page.Mode, Page.Emergency, Page.Settings, Page.Training)) { page = Page.Home }
    BackHandler(enabled = page == Page.Calibration) { page = Page.Mode }
    BackHandler(enabled = page == Page.Session) { voice.say(Phrase.HoldToStop, interrupt = true) }

    val runFinished = run.finished
    when (page) {
        Page.Welcome -> WelcomeScreen(onAccept = {
            prefs.welcomeAccepted = true
            page = Page.Permissions
        })

        Page.Permissions -> {
            // Keyed on the answer count so the granted marks refresh after each request.
            key(permissionsVersion) { PermissionsScreen(
                items = buildList {
                    add(PermissionItem("الكاميرا", "ضرورية للتوجيه: ترى المسار والعوائق. الصور تُحلَّل على الهاتف ولا تُحفظ ولا تُرفع.", context.has(Manifest.permission.CAMERA)) {
                        request(Manifest.permission.CAMERA)
                    })
                    add(PermissionItem("الموقع الدقيق", "لتسجيل المسافة، ولإرسال موقعك في الطوارئ. لا يُستخدم في التوجيه داخل المسار.", context.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(PermissionItem("الإشعارات", "لإظهار إشعار الجري وزر إنهائه.", context.has(Manifest.permission.POST_NOTIFICATIONS)) {
                            request(Manifest.permission.POST_NOTIFICATIONS)
                        })
                    }
                },
                onContinue = { page = Page.Home },
                onBack = { page = Page.Home },
            ) }
        }

        Page.Home -> HomeScreen(
            history = history,
            notice = notice,
            onGuidedRun = {
                notice = null
                if (!context.has(Manifest.permission.CAMERA)) {
                    notice = "التوجيه يحتاج إذن الكاميرا"
                    page = Page.Permissions
                } else if (!prefs.hapticTrained && haptics.available) {
                    // The patterns must be learned before they are relied on.
                    trainingThenRun = true
                    page = Page.Training
                } else {
                    page = Page.Mode
                }
            },
            onGpsOnlyRun = {
                request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) {
                    if (context.has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        startDistanceService(quiet = false)
                        page = Page.GpsRun
                    } else {
                        notice = "تسجيل المسافة يحتاج إذن الموقع الدقيق"
                    }
                }
            },
            onEmergency = { page = Page.Emergency },
            onSettings = { page = Page.Settings },
        )

        Page.Mode -> ModeScreen(prefs, onPick = {
            mode = it
            page = Page.Calibration
        }, onBack = { page = Page.Home })

        Page.Calibration -> CalibrationScreen(
            mode = mode,
            voice = voice,
            prefs = prefs,
            pipeline = pipeline,
            motion = motion,
            haptics = haptics,
            developer = prefs.developerMode,
            onReady = { cal ->
                calibration = cal
                pipeline.calibration = cal
                pipeline.reset()
                session = GuidanceSession(context, haptics, voice, tone, prefs, motion)
                if (prefs.recordGps) startDistanceService(quiet = true)
                page = Page.Session
            },
            onBack = { page = Page.Mode },
        )

        Page.Session -> {
            val current = session
            if (current == null) {
                LaunchedEffect(Unit) { page = Page.Home }
            } else {
                DisposableEffect(current) {
                    motion.start()
                    onDispose { motion.stop() }
                }
                SessionScreen(
                    session = current,
                    pipeline = pipeline,
                    calibration = calibration,
                    gpsText = if (run.active) {
                        val (n, u) = distanceParts(run.distanceM)
                        "$n $u · ${clock(run.elapsedMs)}"
                    } else null,
                    developer = prefs.developerMode,
                    onEnd = {
                        summaryDistance = if (run.active) distanceParts(run.distanceM).let { "${it.first} ${it.second}" } else null
                        summary = current.finish(SystemClock.elapsedRealtime())
                        summaryLog = current.logFile
                        if (run.active) stopDistanceService()
                        session = null
                        page = Page.SessionSummary
                    },
                    onSos = { page = Page.Emergency },
                )
            }
        }

        Page.SessionSummary -> {
            val s = summary
            if (s == null) LaunchedEffect(Unit) { page = Page.Home }
            else SessionSummaryScreen(
                s, summaryDistance,
                onShareLog = summaryLog?.let { file ->
                    {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "مشاركة سجل القياسات"))
                    }
                },
            ) {
                RunSession.state.value = RunState()
                page = Page.Home
            }
        }

        Page.GpsRun -> when {
            run.active -> RunScreen(
                run = run,
                onStopHint = { voice.say(Phrase.HoldToStop, interrupt = true) },
                onStop = ::stopDistanceService,
                onSos = { page = Page.Emergency },
            )
            runFinished != null -> SummaryScreen(runFinished) {
                RunSession.state.value = RunState()
                page = Page.Home
            }
            else -> RunScreen(run, {}, {}, { page = Page.Emergency })
        }

        Page.Emergency -> EmergencyScreen(
            prefs = prefs,
            voice = voice,
            hasLocationPermission = { context.has(Manifest.permission.ACCESS_FINE_LOCATION) },
            requestLocation = { action -> request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, then = action) },
            onBack = { page = if (session != null) Page.Session else Page.Home },
        )

        Page.Settings -> SettingsScreen(
            prefs = prefs,
            voice = voice,
            tone = tone,
            history = history,
            onTraining = {
                trainingThenRun = false
                page = Page.Training
            },
            onBack = { page = Page.Home },
        )

        Page.Training -> HapticTrainingScreen(
            prefs = prefs,
            voice = voice,
            haptics = haptics,
            onDone = { page = if (trainingThenRun) Page.Mode else Page.Settings },
            onBack = { page = Page.Home },
        )
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
