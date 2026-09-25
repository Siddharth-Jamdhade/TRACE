package com.example.trace

/**
 * TRACE — Event Extractor
 *
 * Maps a normalized [Observation] into a labelled event type string, or null
 * if the confidence does not cross any threshold.
 *
 * Supported event labels:
 *   motion:       impact, object_moves
 *   audio:        alarm, abnormal_sound
 *   camera:       object_falls, object_moves
 *   magnetometer: door_swing, door_slam, metal_moves
 *   barometer:    pressure_shift
 *   light:        lights_off
 *   linear:       object_falls (free-fall), device_falls
 *   gyroscope:    device_falls, device_motion
 *   step:         person_present
 *   sigmotion:    person_present
 *
 * Thresholds are tuned against real device measurements — see MainActivity comments.
 */
object EventExtractor {

    // Motion thresholds (calibrated for MOTION_THRESHOLD=3, MOTION_MAX=20).
    // jerk ~5  → confidence 0.12 (ignored, just picking up phone)
    // jerk ~8  → confidence 0.29 → object_moves
    // jerk ~15 → confidence 0.71 → impact
    private const val MOTION_IMPACT = 0.60f
    private const val MOTION_MOVES  = 0.25f

    // Audio thresholds. Retune these after you measure real amplitude
    // values in a quiet room (see the RAW amplitude log in MainActivity).
    private const val AUDIO_ALARM    = 0.70f
    private const val AUDIO_ABNORMAL = 0.35f

    // Camera frame-differencing thresholds.
    // object_falls threshold is higher because a fall causes rapid, large pixel change.
    private const val CAMERA_FALLS = 0.75f
    private const val CAMERA_MOVES = 0.50f

    // Magnetometer: door hinges/frames are steel — a door swinging open or a
    // hand truck passing distorts the local field far more than a person walking.
    private const val MAG_DOOR_SLAM = 0.70f
    private const val MAG_DOOR_SWING = 0.45f
    private const val MAG_METAL_MOVES = 0.25f

    // Barometer: a door opening/closing couples a fast air-pressure pulse into
    // the room; HVAC drifts far more slowly, so fast spikes are incident-shaped.
    private const val BARO_SHIFT = 0.20f

    // Ambient light: a light being switched off is a step drop in lux. The
    // detector fires on large *negative* deviations only (see MainActivity).
    private const val LIGHT_OFF = 0.70f

    // Linear acceleration: true free-fall reads ≈0 m/s² on all axes. Confidence
    // rises as the vector collapses toward zero (see MainActivity.linearListener).
    private const val LINEAR_FALL = 0.72f
    private const val LINEAR_DEVICE_FALL = 0.55f

    // Gyroscope: a phone tumbling off a shelf spins fast on multiple axes.
    private const val GYRO_FALL = 0.75f
    private const val GYRO_MOVE = 0.35f

    fun extract(obs: Observation): String? {
        return when (obs.source) {
            "motion" -> when {
                obs.confidence > MOTION_IMPACT -> "impact"
                obs.confidence > MOTION_MOVES  -> "object_moves"
                else                           -> null
            }
            "audio" -> when {
                obs.confidence > AUDIO_ALARM    -> "alarm"
                obs.confidence > AUDIO_ABNORMAL -> "abnormal_sound"
                else                            -> null
            }
            "camera" -> when {
                obs.confidence > CAMERA_FALLS -> "object_falls"
                obs.confidence > CAMERA_MOVES -> "object_moves"
                else                          -> null
            }
            "magnetometer" -> when {
                obs.confidence > MAG_DOOR_SLAM   -> "door_slam"
                obs.confidence > MAG_DOOR_SWING  -> "door_swing"
                obs.confidence > MAG_METAL_MOVES -> "metal_moves"
                else                             -> null
            }
            "barometer" -> if (obs.confidence > BARO_SHIFT) "pressure_shift" else null
            "light"     -> if (obs.confidence > LIGHT_OFF)  "lights_off"     else null
            "linear" -> when {
                obs.confidence > LINEAR_FALL        -> "object_falls"
                obs.confidence > LINEAR_DEVICE_FALL -> "device_falls"
                else                                -> null
            }
            "gyroscope" -> when {
                obs.confidence > GYRO_FALL -> "device_falls"
                obs.confidence > GYRO_MOVE -> "device_motion"
                else                       -> null
            }
            "step"      -> if (obs.confidence > 0f) "person_present" else null
            "sigmotion" -> if (obs.confidence > 0f) "person_present" else null
            else -> null
        }
    }
}