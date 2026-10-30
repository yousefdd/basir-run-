package com.basir.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.basir.run.engine.Phrase
import com.basir.run.sensors.MotionMonitor
import com.basir.run.vision.GuidancePipeline
import com.basir.run.vision.ObstacleDetector
import com.basir.run.vision.depth.DeepChannel

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

private enum class Page { Guide, Permissions, Contact, Training, Home, Run, Summary, Emergency, Settings, SettingsGuide, SettingsTraining, DeveloperLogin, Developer, LiveVision }

@Composable
private fun BasirApp() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val voice = remember { Voice(context) }
    val motion = remember { MotionMonitor(context) }
    val haptics = remember { HapticEngine(context, prefs) }
    val obstacles = remember { ObstacleDetector(context) }
    val deep = remember { DeepChannel(context) }
    val pipeline = remember { GuidancePipeline(obstacles, deep, { motion.rotationRadPerS }, { motion.pitchBelowHorizonDeg }) }
    DisposableEffect(Unit) {
        onDispose {
            voice.shutdown()
            obstacles.close()
            deep.close()
        }
    }

    var page by rememberSaveable { mutableStateOf(if (prefs.onboarded) Page.Home else Page.Guide) }
    var emergencyFrom by remember { mutableStateOf(Page.Home) }
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var summaryLog by remember { mutableStateOf<java.io.File?>(null) }
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

    fun emergency(from: Page) {
        emergencyFrom = from
        page = Page.Emergency
    }

    BackHandler(enabled = page in setOf(Page.Emergency, Page.Settings, Page.SettingsGuide, Page.SettingsTraining, Page.DeveloperLogin, Page.Developer, Page.LiveVision)) {
        page = when (page) {
            Page.Emergency -> if (emergencyFrom == Page.Run) Page.Home else emergencyFrom
            Page.SettingsGuide, Page.SettingsTraining -> Page.Settings
            Page.LiveVision, Page.DeveloperLogin -> Page.Developer
            else -> Page.Home
        }
    }
    // A back gesture must not end a run by accident; stopping needs the long press.
    BackHandler(enabled = page == Page.Run) { voice.say(Phrase.HoldToActivate, interrupt = true) }

    CompositionLocalProvider(LocalVoice provides voice) {
        when (page) {
            Page.Guide -> GuideScreen(voice, nextLabel = Phrase.ButtonNext.ar, onNext = { page = Page.Permissions }, onBack = null)

            Page.Permissions -> key(permissionsVersion) {
                PermissionsScreen(
                    items = listOf(
                        PermissionItem("الكاميرا", "ضرورية للتوجيه: ترى المسار والعوائق. الصور تُحلَّل على الهاتف ولا تُحفظ ولا تُرفع.", context.has(Manifest.permission.CAMERA), Phrase.ButtonAllowCamera.ar) {
                            request(Manifest.permission.CAMERA)
                        },
                        PermissionItem("الموقع", "لإرسال موقعك إلى جهة الاتصال الموثوقة في الطوارئ فقط. لا يُستخدم في التوجيه.", context.has(Manifest.permission.ACCESS_FINE_LOCATION), Phrase.ButtonAllowLocation.ar) {
                            request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        },
                    ),
                    onContinue = { page = Page.Contact },
                )
            }

            Page.Contact -> ContactStepScreen(prefs, voice, onContinue = { page = Page.Training })

            Page.Training -> HapticTrainingScreen(prefs, voice, haptics, doneLabel = Phrase.ButtonUnderstood.ar, onDone = {
                prefs.onboarded = true
                page = Page.Home
            }, onBack = null)

            Page.Home -> HomeScreen(
                status = SystemStatus(
                    cameraAllowed = context.has(Manifest.permission.CAMERA),
                    detectorReady = deep.detectorReady || obstacles.ready,
                    depthReady = deep.depthReady,
                    vibration = haptics.available,
                    contact = prefs.contacts.firstOrNull(),
                    arabicTextToSpeech = voice.arabicTextToSpeech,
                ),
                notice = notice,
                onStart = {
                    notice = null
                    request(Manifest.permission.CAMERA) {
                        if (context.has(Manifest.permission.CAMERA)) page = Page.Run
                        else notice = "الجري يحتاج إذن الكاميرا"
                    }
                },
                onEmergency = { emergency(Page.Home) },
                onSettings = { page = Page.Settings },
                onDeveloper = { page = Page.Developer },
            )

            Page.Run -> RunScreen(
                voice = voice,
                prefs = prefs,
                pipeline = pipeline,
                motion = motion,
                haptics = haptics,
                onFinished = { s, file ->
                    summary = s
                    summaryLog = file
                    page = Page.Summary
                },
                onEmergency = { emergency(Page.Run) },
                onCancel = { page = Page.Home },
            )

            Page.Summary -> {
                val s = summary
                if (s == null) LaunchedEffect(Unit) { page = Page.Home }
                else SessionSummaryScreen(
                    s,
                    onShareLog = summaryLog?.let { file ->
                        {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
                            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "مشاركة سجل القياسات"))
                        }
                    },
                ) { page = Page.Home }
            }

            Page.Emergency -> EmergencyScreen(
                prefs = prefs,
                voice = voice,
                // A run interrupted by an emergency is over; it is not resumed silently.
                onBack = { page = if (emergencyFrom == Page.Run) Page.Home else emergencyFrom },
            )

            Page.Settings -> SettingsScreen(
                prefs = prefs,
                voice = voice,
                onTraining = { page = Page.SettingsTraining },
                onGuide = { page = Page.SettingsGuide },
                onBack = { page = Page.Home },
            )

            Page.SettingsGuide -> GuideScreen(voice, nextLabel = Phrase.ButtonDone.ar, onNext = { page = Page.Settings }, onBack = { page = Page.Settings })

            Page.Developer -> DeveloperScreen(onEngineCheck = { page = Page.DeveloperLogin }, onBack = { page = Page.Home })

            Page.DeveloperLogin -> DeveloperLoginScreen(
                onOpen = {
                    request(Manifest.permission.CAMERA) {
                        page = if (context.has(Manifest.permission.CAMERA)) Page.LiveVision else Page.Developer
                    }
                },
                onBack = { page = Page.Developer },
            )

            Page.LiveVision -> LiveVisionScreen(pipeline, motion, prefs, onBack = { page = Page.Developer })

            Page.SettingsTraining -> HapticTrainingScreen(prefs, voice, haptics, doneLabel = Phrase.ButtonSave.ar, onDone = { page = Page.Settings }, onBack = { page = Page.Settings })
        }
    }
}

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
