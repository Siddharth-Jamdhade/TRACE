package com.example.trace.capture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log
import com.example.trace.Observation
import com.example.trace.SensorRegistry
import kotlin.math.sqrt

/**
 * Environment sensor capture (zero-permission sensors).
 *
 * Manages magnetometer, barometer, and light sensors — the slow-drifting
 * channels that contextualise an incident.
 *
 * Uses custom slower rates (≈5 Hz) for barometer and light since these
 * sensors drift in seconds-to-minutes, not milliseconds.
 *
 * @param enabledSensorIds sensor IDs from [SensorRegistry] that are enabled
 * @param sensorHandler background handler for sensor callbacks
 * @param onObservation called for each qualified observation
 */
class EnvironmentSensorSource(
    private val enabledSensorIds: Set<String>,
    private val sensorHandler: Handler,
    private val onObservation: (Observation) -> Unit,
) {
    private var manager: SensorManager? = null

    // Per-sensor baselines with appropriate sigma floors
    private val magBaseline = BaselineTracker(
        minSamples = 200, sigmaFloor = 1.0, relativeSigmaFloor = 0.03,
    )
    private val pressureBaseline = BaselineTracker(
        minSamples = 200, sigmaFloor = 1.0, relativeSigmaFloor = 0.0002,
    )
    private val lightBaseline = BaselineTracker(
        minSamples = 100, sigmaFloor = 2.0, relativeSigmaFloor = 0.05,
    )

    /** Custom 200 ms (≈5 Hz) for slow-drifting environment sensors. */
    private val SENSOR_DELAY_SLOWISH = 200_000

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tMs = event.timestamp / 1_000_000L
            when (event.sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> onMagnetic(tMs, event)
                Sensor.TYPE_PRESSURE -> onPressure(tMs, event)
                Sensor.TYPE_LIGHT -> onLight(tMs, event)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.w("TRACE", "environment sensor accuracy changed: ${sensor?.name} = $accuracy")
        }
    }

    private fun onMagnetic(tMs: Long, event: SensorEvent) {
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        magBaseline.feed(mag.toDouble())
        // Use sigma-based confidence when baseline is ready; fall back to delta
        val sigma = magBaseline.deviationSigma(mag.toDouble())
        val confidence = if (sigma != null) {
            (sigma / 10.0).coerceIn(0.0, 1.0).toFloat()
        } else {
            toConfidence(mag, DEFAULT_MAG_THRESHOLD, DEFAULT_MAG_MAX)
        }
        if (confidence > 0f) {
            onObservation(Observation(
                source = "magnetometer",
                confidence = confidence,
                timestamp = tMs,
                baselineValue = magBaseline.baselineMean,
                observedValue = mag.toDouble(),
                deviationSigma = sigma,
            ))
        }
    }

    private fun onPressure(tMs: Long, event: SensorEvent) {
        val hPa = event.values[0]
        pressureBaseline.feed(hPa.toDouble())
        val sigma = pressureBaseline.deviationSigma(hPa.toDouble())
        val confidence = if (sigma != null) {
            (sigma / 10.0).coerceIn(0.0, 1.0).toFloat()
        } else {
            toConfidence(hPa, DEFAULT_BARO_THRESHOLD, DEFAULT_BARO_MAX)
        }
        if (confidence > 0f) {
            onObservation(Observation(
                source = "barometer",
                confidence = confidence,
                timestamp = tMs,
                baselineValue = pressureBaseline.baselineMean,
                observedValue = hPa.toDouble(),
                deviationSigma = sigma,
            ))
        }
    }

    private fun onLight(tMs: Long, event: SensorEvent) {
        val lux = event.values[0]
        lightBaseline.feed(lux.toDouble())
        val sigma = lightBaseline.deviationSigma(lux.toDouble())
        // Light sensor fires only on dimming (lights off)
        val confidence = if (sigma != null && sigma < 0) {
            0f  // getting brighter — ignore
        } else if (sigma != null) {
            (sigma / 10.0).coerceIn(0.0, 1.0).toFloat()
        } else {
            if (lux < lightBaseline.baselineMean) {
                toConfidence(lux, DEFAULT_LIGHT_THRESHOLD, DEFAULT_LIGHT_MAX)
            } else 0f
        }
        if (confidence > 0f) {
            onObservation(Observation(
                source = "light",
                confidence = confidence,
                timestamp = tMs,
                baselineValue = lightBaseline.baselineMean,
                observedValue = lux.toDouble(),
                deviationSigma = sigma,
            ))
        }
    }

    fun start(sensorManager: SensorManager) {
        this.manager = sensorManager

        if (SensorRegistry.MAGNETOMETER.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
            }
        }
        if (SensorRegistry.BAROMETER.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
                sensorManager.registerListener(listener, it, SENSOR_DELAY_SLOWISH, sensorHandler)
            }
        }
        if (SensorRegistry.LIGHT.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                sensorManager.registerListener(listener, it, SENSOR_DELAY_SLOWISH, sensorHandler)
            }
        }
    }

    fun stop() {
        manager?.unregisterListener(listener)
        manager = null
    }

    private fun toConfidence(value: Float, threshold: Float, max: Float): Float {
        val absDelta = kotlin.math.abs(value - threshold)
        if (absDelta < threshold) return 0f
        return ((absDelta - threshold) / (max - threshold)).coerceIn(0f, 1f)
    }

    companion object {
        private const val DEFAULT_MAG_THRESHOLD = 10f
        private const val DEFAULT_MAG_MAX = 40f
        private const val DEFAULT_BARO_THRESHOLD = 0.3f
        private const val DEFAULT_BARO_MAX = 1.2f
        private const val DEFAULT_LIGHT_THRESHOLD = 100f
        private const val DEFAULT_LIGHT_MAX = 400f
    }
}