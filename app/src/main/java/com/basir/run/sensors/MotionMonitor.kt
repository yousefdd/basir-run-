package com.basir.run.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Camera tilt and phone shake from the gravity sensor and gyroscope.
 * Tilt is the angle of the rear camera below the horizon: 0° looking straight ahead,
 * 90° looking straight down.
 */
class MotionMonitor(context: Context) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var meanSquare = 0f

    @Volatile var pitchBelowHorizonDeg: Float? = null
        private set
    @Volatile var shakeRadPerS: Float? = null
        private set
    /** Instant rotation rate, unsmoothed, so a single jolt of the harness is seen at once. */
    @Volatile var rotationRadPerS: Float? = null
        private set

    fun start() {
        gravity?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val g = sqrt(x * x + y * y + z * z)
                // The rear camera looks along -Z; gravity reads +Z when the phone lies face up.
                if (g > 1f) pitchBelowHorizonDeg = Math.toDegrees(asin((z / g).coerceIn(-1f, 1f).toDouble())).toFloat()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val sq = x * x + y * y + z * z
                rotationRadPerS = sqrt(sq)
                meanSquare = meanSquare * 0.95f + sq * 0.05f
                shakeRadPerS = sqrt(meanSquare)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
