package com.example.trace

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * TRACE — Tier 1 continuous numeric sensor log entry.
 *
 * Written at a fixed sampling interval (every 1–2 seconds per sensor) for the
 * entire duration of a session, regardless of whether anything unusual is
 * happening. This provides the continuous trend record that Tier 2 transitions
 * are detected against, and survives process restarts for later analysis.
 *
 * Each entry is minimal: timestamp + sensor id + value. No baselines,
 * no confidence scores, no status labels — just the raw numeric reading.
 */
@Entity(
    tableName = "sensor_logs",
    indices = [Index("sessionId", "source", "timestamp")]
)
data class SensorLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val source: String,       // SensorRegistry sensor id (e.g. "motion", "audio", "camera")
    val timestamp: Long,      // monotonic milliseconds (System.nanoTime() / 1_000_000)
    val value: Double         // the raw sensor reading (RMS, jerk, lux, hPa, diff-ratio, etc.)
)