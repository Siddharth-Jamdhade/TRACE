package com.example.trace

data class Observation(
    val source: String,       // "audio", "motion", or "camera"
    val confidence: Float,    // 0.0 to 1.0 — how strong the signal was
    val timestamp: Long       // System.currentTimeMillis()
)