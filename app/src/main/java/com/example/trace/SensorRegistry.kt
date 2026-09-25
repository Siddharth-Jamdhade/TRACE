package com.example.trace

/**
 * TRACE — Sensor Registry
 *
 * Single source of truth for every sensor that participates in the
 * observation -> event extraction -> fusion pipeline. Each entry pairs the
 * string source ID used by [Observation]/[Event] with:
 *   - the Android.hardware.Sensor type constant it is read from
 *   - a display icon
 *   - the incident-reconstruction role it plays (which event types its
 *     evidence supports)
 *
 * Sensors that can NOT contribute meaningful signal to physical incident
 * reconstruction are deliberately NOT listed here:
 *   - Ambient temperature / humidity: consumer devices lack them, and air
 *     state does not distinguish incident signatures in a warehouse.
 *   - Rotation vector / orientation / gravity (raw): absorbed by the
 *     gyroscope + linear-acceleration signals which carry the dynamics.
 *   - Wi-Fi / Bluetooth RSSI: seconds-scale scans, poor spatial resolution,
 *     and heavy permission burden for near-zero demo value.
 *   - Vibration motor: an actuator, deliberately NOT used. TRACE must stay
 *     silent during a live session, so CONFIRMED incidents give no haptic
 *     feedback at all.
 */
object SensorRegistry {

    data class SensorSpec(
        val id: String,
        val type: Int,
        val icon: String,
        val role: String
    )

    val CAMERA = SensorSpec("camera", -1, "📷",
        "Frame differencing: object_moves, object_falls")
    val AUDIO = SensorSpec("audio", -1, "🔊",
        "Amplitude envelope: alarm, abnormal_sound")
    val MOTION = SensorSpec("motion", android.hardware.Sensor.TYPE_ACCELEROMETER, "📳",
        "Jerk analysis: impact, object_moves")
    val MAGNETOMETER = SensorSpec("magnetometer", android.hardware.Sensor.TYPE_MAGNETIC_FIELD, "🧲",
        "Ambient field distortion: door_swing, door_slam, metal_moves")
    val BAROMETER = SensorSpec("barometer", android.hardware.Sensor.TYPE_PRESSURE, "🌀",
        "Air-pressure transients from sudden openings: pressure_shift")
    val LIGHT = SensorSpec("light", android.hardware.Sensor.TYPE_LIGHT, "💡",
        "Illumination drop: lights_off (light source interrupted)")
    val LINEAR = SensorSpec("linear", android.hardware.Sensor.TYPE_LINEAR_ACCELERATION, "🪂",
        "Gravity-free free-fall signature: object_falls, device_falls")
    val GYROSCOPE = SensorSpec("gyroscope", android.hardware.Sensor.TYPE_GYROSCOPE, "🔄",
        "Angular velocity: device_falls, device_motion")
    val STEP = SensorSpec("step", android.hardware.Sensor.TYPE_STEP_DETECTOR, "👣",
        "Footsteps near the phone: person_present")
    val SIGMOTION = SensorSpec("sigmotion", android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION, "🚶",
        "Hardware activity trigger: person_present")

    /** All sensors that feed Observations into the pipeline. */
    val ALL = listOf(
        CAMERA, AUDIO, MOTION, MAGNETOMETER, BAROMETER,
        LIGHT, LINEAR, GYROSCOPE, STEP, SIGMOTION
    )

    /** Import source (not a physical sensor): ML Kit-labelled video events. */
    val VIDEO = SensorSpec("video", -1, "🎥", "Imported video keyframes")

    fun byId(id: String): SensorSpec? =
        ALL.firstOrNull { it.id == id } ?: if (id == "video") VIDEO else null

    fun iconFor(source: String): String = byId(source)?.icon ?: "❓"

    fun labelFor(source: String): String =
        source.replaceFirstChar { it.uppercase() }

    /**
     * Logs which of the pipeline sensors physically exist on this device.
     * Sensors absent from hardware simply never emit Observations — the
     * fusion engine treats an absent sensor the same as a silent one.
     */
    fun availabilityReport(sensorManager: android.hardware.SensorManager): String =
        ALL.filter { it.type > 0 }.joinToString("\n") { spec ->
            val present = sensorManager.getDefaultSensor(spec.type) != null
            "${spec.icon} ${spec.id}: ${if (present) "AVAILABLE" else "not present on device"}"
        }
}
