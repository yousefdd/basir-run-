package com.basir.run

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ARABIC = Locale.forLanguageTag("ar")

// Number and unit shown on screen, e.g. ("850", "متر") or ("2.35", "كم").
fun distanceParts(meters: Float): Pair<String, String> =
    if (meters < 1000f) "${meters.toInt()}" to "متر"
    else String.format(Locale.US, "%.2f", meters / 1000f) to "كم"

fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun pace(secPerKm: Int?): String =
    if (secPerKm == null || secPerKm > 3600) "--:--"
    else String.format(Locale.US, "%d:%02d", secPerKm / 60, secPerKm % 60)

fun spokenDuration(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return buildString {
        if (m > 0) append("$m دقيقة")
        if (m > 0 && s > 0) append(" و")
        if (s > 0 || m == 0L) append("$s ثانية")
    }
}

fun spokenDistance(meters: Float): String =
    if (meters < 1000f) "${meters.toInt()} متر"
    else String.format(Locale.US, "%.2f كيلومتر", meters / 1000f)

fun runDate(millis: Long): String = SimpleDateFormat("EEEE d MMMM", ARABIC).format(Date(millis))
