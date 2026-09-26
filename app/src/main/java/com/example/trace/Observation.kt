package com.example.trace

data class Observation(
    val source: String,       // "audio", "motion", or "camera"
    val confidence: Float,    // 0.0 to 1.0 — how strong the signal was
    val timestamp: Long       // Monotonic milliseconds (elapsedRealtimeNanos / 1e6).
                              // Hardware sensor events use SensorEvent.timestamp;
                              // audio/camera use System.nanoTime(). This is the
                              // monotonic clock domain — never System.currentTimeMillis().
)