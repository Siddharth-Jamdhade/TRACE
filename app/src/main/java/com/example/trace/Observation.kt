package com.example.trace

data class Observation(
    val source: String,       // "audio", "motion", "camera", "magnetometer", etc.
    val confidence: Float,    // 0.0 to 1.0 — deviation magnitude / how far from baseline
    val timestamp: Long,      // Monotonic milliseconds (elapsedRealtimeNanos / 1e6).
                              // Hardware sensor events use SensorEvent.timestamp;
                              // audio/camera use System.nanoTime(). This is the
                              // monotonic clock domain — never System.currentTimeMillis().

    /** The sensor's running baseline mean at the time of detection, if available. */
    val baselineValue: Double? = null,

    /** The raw sensor reading that triggered the flag, if available. */
    val observedValue: Double? = null,

    /** Number of standard deviations from baseline, if available. */
    val deviationSigma: Double? = null,
)