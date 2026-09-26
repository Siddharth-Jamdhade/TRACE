package com.example.trace.capture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.util.Log
import com.example.trace.Observation
import com.example.trace.SensorRegistry
import kotlin.math.sqrt

/**
 * Motion and inertial sensor capture.
 *
 * Manages accelerometer, gyroscope, linear acceleration, step detector,
 * and significant-motion trigger sensor on a dedicated background handler.
 *
 * Ported from ECHO's SensorSource pattern. Each sub-sensor is registered only
 * when its [SensorChannel]-equivalent is in [enabledSensorIds].
 *
 * @param enabledSensorIds sensor IDs from [SensorRegistry] that are enabled
 * @param sensorHandler background handler for sensor callbacks
 * @param onObservation called for each qualified observation on the handler thread
 */
class MotionSensorSource(
    private val enabledSensorIds: Set<String>,
    private val sensorHandler: Handler,
    private val onObservation: (Observation) -> Unit,
) {
    private var manager: SensorManager? = null
    private var sigMotionArmed = false

    // ── State for motion (Euclidean jerk) ────────────────────────────────
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN

    private val MOTION_THRESHOLD = 3.0f
    private val MOTION_MAX = 20.0f

    private val motBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.2)
    private val gyroBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.02)
    private val linearBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.1)

    private val accelMeter = RateMeter()
    private var lastAccelRatePublish = 0L

    // ── Single listener dispatches all motion sensor types ───────────────
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val tMs = event.timestamp / 1_000_000L
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> onAccel(tMs, event)
                Sensor.TYPE_GYROSCOPE -> onGyro(tMs, event)
                Sensor.TYPE_LINEAR_ACCELERATION -> onLinear(tMs, event)
                Sensor.TYPE_STEP_DETECTOR -> onStep(tMs)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            Log.w("TRACE", "motion sensor accuracy changed: ${sensor?.name} = $accuracy")
        }
    }

    private val sigMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            onObservation(Observation("sigmotion", 0.5f, System.nanoTime() / 1_000_000))
            rearmSigMotion()
        }
    }

    // ── Per-sensor handlers ──────────────────────────────────────────────

    private fun onAccel(tMs: Long, event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        if (lastX.isNaN()) { lastX = x; lastY = y; lastZ = z; return }
        val dx = x - lastX; val dy = y - lastY; val dz = z - lastZ
        val jerk = sqrt(dx * dx + dy * dy + dz * dz)

        motBaseline.feed(jerk.toDouble())
        val sigma = motBaseline.deviationSigma(jerk.toDouble())
        val confidence = toConfidence(jerk, MOTION_THRESHOLD, MOTION_MAX)

        lastX = x; lastY = y; lastZ = z
        if (confidence > 0f) {
            onObservation(Observation("motion", confidence, tMs))
        }
        accelMeter.tick(tMs)
    }

    private fun onGyro(tMs: Long, event: SensorEvent) {
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        gyroBaseline.feed(mag.toDouble())
        val sigma = gyroBaseline.deviationSigma(mag.toDouble())
        val confidence = toConfidence(mag, 1.5f, 8f)
        if (confidence > 0f) {
            onObservation(Observation("gyroscope", confidence, tMs))
        }
    }

    private fun onLinear(tMs: Long, event: SensorEvent) {
        val mag = sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        )
        linearBaseline.feed(mag.toDouble())
        val sigma = linearBaseline.deviationSigma(mag.toDouble())
        // Free-fall: lower magnitude = higher confidence
        val confidence = if (mag < 2.5f) toConfidence(2.5f - mag, 0f, 2.5f) else 0f
        if (confidence > 0f) {
            onObservation(Observation("linear", confidence, tMs))
        }
    }

    private fun onStep(tMs: Long) {
        onObservation(Observation("step", 0.5f, tMs))
    }

    // ── Registration ─────────────────────────────────────────────────────

    fun start(sensorManager: SensorManager) {
        this.manager = sensorManager

        if (SensorRegistry.MOTION.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            }
        }
        if (SensorRegistry.GYROSCOPE.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensorManager.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            }
        }
        if (SensorRegistry.LINEAR.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
                sensorManager.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
            }
        }
        if (SensorRegistry.STEP.id in enabledSensorIds) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sensorManager.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
            }
        }
        if (SensorRegistry.SIGMOTION.id in enabledSensorIds) {
            requestSigMotion(sensorManager)
        }
    }

    fun stop() {
        val sm = manager ?: return
        sm.unregisterListener(motionListener)
        if (sigMotionArmed) {
            sm.cancelTriggerSensor(sigMotionListener, null)
            sigMotionArmed = false
        }
        manager = null
        lastX = Float.NaN; lastY = Float.NaN; lastZ = Float.NaN
    }

    private fun requestSigMotion(sm: SensorManager) {
        val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        sm.requestTriggerSensor(sigMotionListener, sensor)
        sigMotionArmed = true
    }

    private fun rearmSigMotion() {
        val sm = manager ?: return
        requestSigMotion(sm)
    }

    private fun toConfidence(value: Float, threshold: Float, max: Float): Float {
        if (value < threshold) return 0f
        return ((value - threshold) / (max - threshold)).coerceIn(0f, 1f)
    }
}