package com.basir.run

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Run history, newest first, stored as JSON on the device.
class RunStore(context: Context) {

    private val sp = context.getSharedPreferences("basir_runs", Context.MODE_PRIVATE)

    fun all(): List<RunRecord> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RunRecord(o.getLong("startedAt"), o.getDouble("distanceM").toFloat(), o.getLong("durationMs"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun add(record: RunRecord) {
        val array = JSONArray()
        (listOf(record) + all()).take(MAX_RUNS).forEach {
            array.put(
                JSONObject()
                    .put("startedAt", it.startedAt)
                    .put("distanceM", it.distanceM.toDouble())
                    .put("durationMs", it.durationMs)
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val KEY = "runs"
        private const val MAX_RUNS = 50
    }
}
