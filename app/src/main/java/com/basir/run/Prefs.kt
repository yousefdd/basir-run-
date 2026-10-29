package com.basir.run

import android.content.Context

// User settings, kept on the device.
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("basir", Context.MODE_PRIVATE)

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
}
