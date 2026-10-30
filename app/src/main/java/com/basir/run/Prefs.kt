package com.basir.run

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Contact(val name: String, val phone: String)

// User settings, kept on the device.
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("basir", Context.MODE_PRIVATE)

    /** The first-use guide, permissions, trusted contact and vibration lessons were completed. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(value) = sp.edit().putBoolean("onboarded", value).apply()

    /** Extra spoken guidance on top of vibration (corrections and bends in words). */
    var verboseVoice: Boolean
        get() = sp.getBoolean("verbose_voice", false)
        set(value) = sp.edit().putBoolean("verbose_voice", value).apply()

    var vibrateOn: Boolean
        get() = sp.getBoolean("vibrate_on", true)
        set(value) = sp.edit().putBoolean("vibrate_on", value).apply()

    var guideTones: Boolean
        get() = sp.getBoolean("guide_tones", false)
        set(value) = sp.edit().putBoolean("guide_tones", value).apply()

    /** 0.3 to 1.0: vibration strength on phones that support amplitude control (lessons offer 0.45, 0.7, 1). */
    var hapticStrength: Float
        get() = sp.getFloat("haptic_strength", 0.8f)
        set(value) = sp.edit().putFloat("haptic_strength", value).apply()

    /** 0.6 to 2.0: multiplies the length of each pulse (lessons offer 0.7, 1, 1.4, 2). */
    var hapticDuration: Float
        get() = sp.getFloat("haptic_duration", 1f)
        set(value) = sp.edit().putFloat("haptic_duration", value).apply()

    /** The user has gone through the vibration patterns at least once. */
    var hapticTrained: Boolean
        get() = sp.getBoolean("haptic_trained", false)
        set(value) = sp.edit().putBoolean("haptic_trained", value).apply()

    /** The user passed the vibration recognition quiz at least once (required before running). */
    var hapticQuizPassed: Boolean
        get() = sp.getBoolean("haptic_quiz_passed", false)
        set(value) = sp.edit().putBoolean("haptic_quiz_passed", value).apply()

    /** The runner's height; the chest-mounted camera sits at about [CHEST_RATIO] of it. */
    var runnerHeightCm: Int
        get() = sp.getInt("runner_height_cm", 170)
        set(value) = sp.edit().putInt("runner_height_cm", value.coerceIn(100, 220)).apply()

    /** Camera height above the ground in metres, which every distance on the ground scales with. */
    val cameraHeightM: Double get() = runnerHeightCm / 100.0 * CHEST_RATIO

    /** Shows the camera, the virtual path, detector boxes and timings during guidance, for testing. */
    var developerMode: Boolean
        get() = sp.getBoolean("developer_mode", false)
        set(value) = sp.edit().putBoolean("developer_mode", value).apply()

    /** The local emergency number as the user enters it; never guessed. */
    var localEmergencyNumber: String
        get() = sp.getString("local_emergency_number", "") ?: ""
        set(value) = sp.edit().putString("local_emergency_number", value).apply()

    /** Trusted contacts; the first one is called in an emergency, all receive the message. */
    var contacts: List<Contact>
        get() {
            val raw = sp.getString("contacts", null)
            val list = try {
                if (raw == null) emptyList() else JSONArray(raw).let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { o -> Contact(o.getString("name"), o.getString("phone")) } }
                }
            } catch (e: JSONException) {
                emptyList()
            }
            // Carry over the single number saved by earlier versions.
            val old = sp.getString("emergency_phone", "") ?: ""
            return if (list.isEmpty() && old.isNotBlank()) listOf(Contact("جهة الطوارئ", old)) else list
        }
        set(value) {
            val a = JSONArray()
            value.forEach { a.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
            sp.edit().putString("contacts", a.toString()).remove("emergency_phone").apply()
        }

    companion object {
        /** Chest strap height as a share of body height (sternum level for adults). */
        const val CHEST_RATIO = 0.74
    }
}
