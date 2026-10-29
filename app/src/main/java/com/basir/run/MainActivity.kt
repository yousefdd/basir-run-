package com.basir.run

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// One screen, built for TalkBack: large buttons, plain labels, and every state change spoken aloud.
class MainActivity : ComponentActivity() {

    private lateinit var tracker: LocationTracker
    private lateinit var speaker: Speaker
    private val tone = ToneGuide()

    private lateinit var statusText: TextView
    private lateinit var phoneInput: EditText
    private lateinit var runButton: Button

    private var running = false
    private var nextAnnouncementM = ANNOUNCE_EVERY_M
    private var afterPermission: (() -> Unit)? = null

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            afterPermission?.invoke()
        } else {
            speakAndShow("بصير يحتاج إذن الموقع ليعمل")
        }
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = LocationTracker(this)
        speaker = Speaker(this)
        setContentView(buildLayout())
        speakAndShow("مرحباً بك في بصير")
    }

    override fun onDestroy() {
        tracker.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        statusText = TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(statusText, matchWidth(bottom = 24))

        runButton = bigButton("ابدأ الجري", Color.parseColor("#1B5E20")) { toggleRun() }
        column.addView(runButton, matchWidth(height = 120, bottom = 16))

        column.addView(bigButton("اختبار الصوت يساراً", Color.parseColor("#37474F")) {
            tone.play(-1f)
        }, matchWidth(height = 72, bottom = 12))
        column.addView(bigButton("اختبار الصوت يميناً", Color.parseColor("#37474F")) {
            tone.play(1f)
        }, matchWidth(height = 72, bottom = 24))

        phoneInput = EditText(this).apply {
            hint = "رقم جوال الطوارئ"
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 20f
            setText(prefs().getString(KEY_PHONE, ""))
        }
        column.addView(phoneInput, matchWidth(bottom = 12))

        column.addView(bigButton("طوارئ: أرسل موقعي", Color.parseColor("#B71C1C")) {
            sendEmergency()
        }, matchWidth(height = 96))

        return ScrollView(this).apply { addView(column) }
    }

    private fun toggleRun() {
        if (running) stopRun() else withLocationPermission(::startRun)
    }

    private fun startRun() {
        running = true
        nextAnnouncementM = ANNOUNCE_EVERY_M
        runButton.text = "إيقاف الجري"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speakAndShow("بدأ الجري")

        tracker.start { location ->
            val meters = tracker.distanceMeters
            statusText.text = "المسافة: ${meters.toInt()} متر\nدقة الموقع: ${location.accuracy.toInt()} متر"
            if (meters >= nextAnnouncementM) {
                speaker.say("${nextAnnouncementM.toInt()} متر")
                nextAnnouncementM += ANNOUNCE_EVERY_M
            }
        }
    }

    private fun stopRun() {
        running = false
        tracker.stop()
        runButton.text = "ابدأ الجري"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speakAndShow("انتهى الجري. المسافة ${tracker.distanceMeters.toInt()} متر")
    }

    // Opens a ready-to-send SMS with a map link to the runner's position.
    private fun sendEmergency() {
        val phone = phoneInput.text.toString().trim()
        if (phone.isEmpty()) {
            speakAndShow("أضف رقم جوال الطوارئ أولاً")
            phoneInput.requestFocus()
            return
        }
        prefs().edit().putString(KEY_PHONE, phone).apply()

        withLocationPermission {
            speakAndShow("جاري تحديد موقعك")
            tracker.currentLocation { location ->
                if (location == null) {
                    speakAndShow("لم أستطع تحديد موقعك، حاول مرة أخرى")
                    return@currentLocation
                }
                val message = "أحتاج مساعدة. هذا موقعي: ${LocationTracker.mapsLink(location)}"
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                    .putExtra("sms_body", message)
                startActivity(intent)
            }
        }
    }

    private fun withLocationPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterPermission = action
            permissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun speakAndShow(text: String) {
        statusText.text = text
        speaker.say(text)
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { onClick() }
    }

    private fun matchWidth(height: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        if (height > 0) dp(height) else LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun prefs() = getSharedPreferences("basir", Context.MODE_PRIVATE)

    companion object {
        private const val ANNOUNCE_EVERY_M = 100f
        private const val KEY_PHONE = "emergency_phone"
    }
}
