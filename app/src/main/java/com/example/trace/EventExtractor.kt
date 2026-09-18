package com.example.trace

/**
 * TRACE — Event Extractor
 *
 * Maps a normalized [Observation] into a labelled event type string, or null
 * if the confidence does not cross any threshold.
 *
 * Supported event labels: impact, object_moves, object_falls, alarm, abnormal_sound.
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
            else -> null
        }
    }
}