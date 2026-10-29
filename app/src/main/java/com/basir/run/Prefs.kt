package com.basir.run

import android.content.Context
import com.basir.run.engine.RouteMode
import com.basir.run.vision.Calibration
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Contact(val name: String, val phone: String)

// User settings, kept on the device.
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("basir", Context.MODE_PRIVATE)

    var welcomeAccepted: Boolean
        get() = sp.getBoolean("welcome_accepted", false)
        set(value) = sp.edit().putBoolean("welcome_accepted", value).apply()

    /** Kept for the GPS-only run and old installs; the emergency screen uses [contacts]. */
    var emergencyPhone: String
        get() = sp.getString("emergency_phone", "") ?: ""
        set(value) = sp.edit().putString("emergency_phone", value).apply()

    var announceEveryM: Int
        get() = sp.getInt("announce_every_m", 200)
        set(value) = sp.edit().putInt("announce_every_m", value).apply()

    var voiceOn: Boolean
        get() = sp.getBoolean("voice_on", true)
        set(value) = sp.edit().putBoolean("voice_on", value).apply()

    var vibrateOn: Boolean
        get() = sp.getBoolean("vibrate_on", true)
        set(value) = sp.edit().putBoolean("vibrate_on", value).apply()

    var guideTones: Boolean
        get() = sp.getBoolean("guide_tones", true)
        set(value) = sp.edit().putBoolean("guide_tones", value).apply()

    /** Record distance with GPS during guided sessions. Never used for guidance. */
    var recordGps: Boolean
        get() = sp.getBoolean("record_gps", true)
        set(value) = sp.edit().putBoolean("record_gps", value).apply()

    /** The local emergency number as the user enters it; never guessed. */
    var localEmergencyNumber: String
        get() = sp.getString("local_emergency_number", "") ?: ""
        set(value) = sp.edit().putString("local_emergency_number", value).apply()

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
            return if (list.isEmpty() && emergencyPhone.isNotBlank()) listOf(Contact("جهة الطوارئ", emergencyPhone)) else list
        }
        set(value) {
            val a = JSONArray()
            value.forEach { a.put(JSONObject().put("name", it.name).put("phone", it.phone)) }
            sp.edit().putString("contacts", a.toString()).apply()
        }

    fun calibration(mode: RouteMode): Calibration? {
        val x = sp.getFloat("cal_${mode.name}_x", -1f)
        if (x < 0f) return null
        val half = sp.getFloat("cal_${mode.name}_half", -1f)
        return Calibration(runnerX = x, halfWidth = half.takeIf { it > 0f })
    }

    fun saveCalibration(mode: RouteMode, calibration: Calibration) {
        sp.edit()
            .putFloat("cal_${mode.name}_x", calibration.runnerX)
            .putFloat("cal_${mode.name}_half", calibration.halfWidth ?: -1f)
            .apply()
    }
}
