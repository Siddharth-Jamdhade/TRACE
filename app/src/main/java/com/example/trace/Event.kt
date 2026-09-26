package com.example.trace

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TRACE — Event database entity.
 *
 * Represents one sensor deviation entry in the timeline — a moment where one
 * sensor's reading deviated meaningfully from its own session baseline.
 * Each entry is self-contained: the sensor that flagged it, the baseline range,
 * the observed value, and the magnitude of deviation. There is no cross-sensor
 * fusion or status labeling.
 *
 * [hash] and [previousHash] implement the SHA-256 tamper-evidence chain.
 *
 * Legacy fields ([status], [cameraConfidence], [audioConfidence],
 * [motionConfidence], [sensorBreakdown]) were used in the old activity-label
 * + fusion system and are retained in the schema only for backward
 * compatibility with historical data — they are never written by new code.
 */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Sensor source name (e.g. "magnetometer", "camera", "audio", "motion").
     * For new entries this matches [source]; legacy entries hold classified
     * labels like "impact" or "door_slam".
     */
    val type: String,

    /** System.currentTimeMillis() at detection time. */
    val timestamp: Long,

    /** Primary triggering sensor: see [SensorRegistry] for all IDs. */
    val source: String,

    /** Normalized deviation magnitude (0.0–1.0). Higher = further from baseline. */
    val confidence: Float,

    /**
     * Legacy fusion status — no longer written.
     * Historical values: "CONFIRMED", "UNCONFIRMED", "REJECTED", "MANUAL".
     * New entries use a simple "FLAGGED" placeholder.
     */
    var status: String = "FLAGGED",

    // --- Per-sensor confidence values (legacy — no longer written) -------
    val cameraConfidence: Float? = null,
    val audioConfidence: Float? = null,
    val motionConfidence: Float? = null,

    /** Legacy sensor breakdown — no longer written. */
    val sensorBreakdown: String? = null,

    // --- Deviation context (new) ------------------------------------------
    /** The sensor's running baseline mean at the time of flagging, or null. */
    val baselineValue: Double? = null,

    /** The raw sensor reading that triggered the flag, or null. */
    val observedValue: Double? = null,

    /** Number of standard deviations from baseline, or null. */
    val deviationSigma: Double? = null,

    // --- Evidence files (filled after capture) -----------------------
    /** Absolute path to the saved audio clip, or null if not available. */
    val evidenceClipPath: String? = null,

    /**
     * Absolute path to the saved JPEG camera frame, or null if none was taken.
     *
     * A still is the weakest visual evidence there is — one frame, no motion —
     * but an event with an audio clip and no visual at all reconstructs even
     * less. Deliberately a sibling of [evidenceClipPath] rather than a
     * replacement: audio and image fail independently, and neither should
     * block the other.
     */
    val evidencePhotoPath: String? = null,

    // --- Evidence file integrity (Stage 7) ---------------------------------
    /** SHA-256 of the audio clip file at the moment it was written. */
    val clipHash: String? = null,

    /** SHA-256 of the snapshot file at the moment it was written. */
    val photoHash: String? = null,

    // --- SHA-256 hash chain fields ---------------------------------------
    /** SHA-256 hash of this event chained to [previousHash]. */
    val hash: String? = null,

    /** Hash of the preceding event in the timeline, or GENESIS_HASH for the first. */
    val previousHash: String? = null,

    // --- Session ownership -----------------------------------------------
    /**
     * Session this event belongs to (see [Session]).
     *
     * Defaults to [Session.LEGACY_ID] with a matching SQL default so the
     * v3 → v4 migration can add the column without rewriting the events table.
     * Deliberately NOT part of the hash payload: session membership is bound by
     * the chain anchor instead (see [HashChain.verifySession]).
     */
    @ColumnInfo(defaultValue = "1")
    val sessionId: Long = Session.LEGACY_ID
)