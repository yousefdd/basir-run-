package com.basir.run

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// One fresh fix from the Fused Location Provider. Callers check the permission first.
@SuppressLint("MissingPermission")
fun fetchCurrentLocation(context: Context, onResult: (Location?) -> Unit) {
    LocationServices.getFusedLocationProviderClient(context)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { onResult(it) }
        .addOnFailureListener { onResult(null) }
}

fun mapsLink(location: Location) = "https://maps.google.com/?q=${location.latitude},${location.longitude}"

// Opens the messaging app with the message ready; the user taps send.
fun emergencySmsIntent(phone: String, location: Location): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
        .putExtra("sms_body", "أحتاج مساعدة. هذا موقعي الآن: ${mapsLink(location)}")
