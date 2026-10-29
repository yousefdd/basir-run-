package com.basir.run

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// Follows the runner with the Fused Location Provider and adds up the distance run.
// Callers must hold the location permission before calling start() or currentLocation().
class LocationTracker(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
        .setMinUpdateIntervalMillis(500L)
        .build()

    private var callback: LocationCallback? = null
    private var lastCounted: Location? = null

    var lastLocation: Location? = null
        private set
    var distanceMeters = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start(onUpdate: (Location) -> Unit) {
        stop()
        distanceMeters = 0f
        lastCounted = null
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) handle(location, onUpdate)
            }
        }
        callback = cb
        client.requestLocationUpdates(request, cb, Looper.getMainLooper())
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    // One fresh fix, for the emergency message when no run is in progress.
    @SuppressLint("MissingPermission")
    fun currentLocation(onResult: (Location?) -> Unit) {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) lastLocation = location
                onResult(location ?: lastLocation)
            }
            .addOnFailureListener { onResult(lastLocation) }
    }

    private fun handle(location: Location, onUpdate: (Location) -> Unit) {
        lastLocation = location
        // Ignore imprecise fixes and GPS jitter so standing still does not add distance.
        if (location.accuracy <= MAX_ACCURACY_M) {
            val previous = lastCounted
            if (previous == null) {
                lastCounted = location
            } else {
                val step = previous.distanceTo(location)
                if (step >= MIN_STEP_M) {
                    distanceMeters += step
                    lastCounted = location
                }
            }
        }
        onUpdate(location)
    }

    companion object {
        const val MAX_ACCURACY_M = 20f
        const val MIN_STEP_M = 3f

        fun mapsLink(location: Location) =
            "https://maps.google.com/?q=${location.latitude},${location.longitude}"
    }
}
