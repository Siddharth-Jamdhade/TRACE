package com.example.trace

/**
 * TRACE — Sensor Registry
 *
 * Single source of truth for every sensor that participates in the
 * observation pipeline. Each entry pairs the string source ID used by
 * [Observation]/[Event] with:
 *   - the Android.hardware.Sensor type constant it is read from
 *   - a display icon
 *   - a description of what physical phenomenon it measures
 *
 * Sensors that can NOT contribute meaningful signal to physical incident
 * reconstruction are deliberately NOT listed here:
 *   - Ambient temperature / humidity: consumer devices lack them, and air
 *     state does not distinguish incident signatures in a warehouse.
 *   - Rotation vector / orientation / gravity (raw): absorbed by the
 *     gyroscope + linear-acceleration signals which carry the dynamics.
 *   - Wi-Fi / Bluetooth RSSI: seconds-scale scans, poor spatial resolution,
 *     and heavy permission burden for near-zero demo value.
 */
object SensorRegistry {

    data class SensorSpec(
        val id: String,
        val type: Int,
        val icon: String,
        val role: String
    )

    val CAMERA = SensorSpec("camera", -1, "📷",
        "Frame differencing — detects visual changes between frames")
    val AUDIO = SensorSpec("audio", -1, "🔊",
        "Amplitude envelope — detects loud or unusual sounds")
    val MOTION = SensorSpec("motion", android.hardware.Sensor.TYPE_ACCELEROMETER, "📳",
        "Jerk analysis — detects bumps, shakes, and movement")
    val MAGNETOMETER = SensorSpec("magnetometer", android.hardware.Sensor.TYPE_MAGNETIC_FIELD, "🧲",
        "Ambient magnetic field — detects ferrous objects or field distortion")
    val BAROMETER = SensorSpec("barometer", android.hardware.Sensor.TYPE_PRESSURE, "🌀",
        "Air pressure — detects sudden pressure transients from openings")
    val LIGHT = SensorSpec("light", android.hardware.Sensor.TYPE_LIGHT, "💡",
        "Illumination level — detects sudden brightness changes")
    val LINEAR = SensorSpec("linear", android.hardware.Sensor.TYPE_LINEAR_ACCELERATION, "🪂",
        "Gravity-free acceleration — detects free-fall and linear motion")
    val GYROSCOPE = SensorSpec("gyroscope", android.hardware.Sensor.TYPE_GYROSCOPE, "🔄",
        "Angular velocity — detects rotation and spin")
    val STEP = SensorSpec("step", android.hardware.Sensor.TYPE_STEP_DETECTOR, "👣",
        "Footstep detection — detects nearby footsteps")
    val SIGMOTION = SensorSpec("sigmotion", android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION, "🚶",
        "Hardware motion trigger — detects large movements with low power")

    /** All sensors that feed Observations into the pipeline. */
    val ALL = listOf(
        CAMERA, AUDIO, MOTION, MAGNETOMETER, BAROMETER,
        LIGHT, LINEAR, GYROSCOPE, STEP, SIGMOTION
    )

    /** Human assertion, not a measurement: the operator saw something happen and
     * tagged it from the live view.
     *
     * Deliberately absent from [ALL], because it is not a sensor and must not
     * leak into the places that assume one:
     *   - a session's sensor set and the availability report,
     *   - the count of independent sources, and
     *   - [Event.sensorBreakdown].
     * A tag has no `Sensor.TYPE_*` to read, hence the -1 placeholder shared with
     * the other software sources.
     */
    val MANUAL = SensorSpec("manual", -1, "✋",
        "Operator assertion — an incident a human on scene reported directly")

    /** Sources that are NOT physical sensors (human input only). */
    val NON_SENSOR = listOf(MANUAL)

    fun byId(id: String): SensorSpec? =
        ALL.firstOrNull { it.id == id } ?: NON_SENSOR.firstOrNull { it.id == id }

    /**
     * The "|"-separated set string stored on a [Session], in registry order.
     *
     * The format matters beyond tidiness: [Session.sensorSet] is inside the
     * hashed session header, so the same selection must always serialise to the
     * same string or the header check would fail.
     */
    fun sensorSetOf(ids: Collection<String>): String =
        ALL.map { it.id }.filter { it in ids }.joinToString("|")

    fun iconFor(source: String): String = byId(source)?.icon ?: "❓"

    fun labelFor(source: String): String =
        source.replaceFirstChar { it.uppercase() }

    /**
     * Logs which of the pipeline sensors physically exist on this device.
     * Sensors absent from hardware simply never emit Observations.
     */
    fun availabilityReport(sensorManager: android.hardware.SensorManager): String =
        ALL.filter { it.type > 0 }.joinToString("\n") { spec ->
            val present = sensorManager.getDefaultSensor(spec.type) != null
            "${spec.icon} ${spec.id}: ${if (present) "AVAILABLE" else "not present on device"}"
        }
}