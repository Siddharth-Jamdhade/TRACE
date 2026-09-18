package com.example.trace

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TRACE — Event database entity.
 *
 * Represents one detected incident entry in the timeline.
 * [cameraConfidence], [audioConfidence], [motionConfidence] are filled by
 * [FusionEngine] after looking at the 2-second window around this event.
 * [hash] and [previousHash] implement the SHA-256 tamper-evidence chain.
 */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Classified event label, e.g. "impact", "alarm", "object_falls". */
    val type: String,

    /** System.currentTimeMillis() at detection time. */
    val timestamp: Long,

    /** Primary triggering sensor: "camera", "audio", or "motion". */
    val source: String,

    /** Normalized confidence of the primary triggering sensor (0.0–1.0). */
    val confidence: Float,

    /** Fusion status: "CONFIRMED", "UNCONFIRMED", or "REJECTED". */
    var status: String = "UNCONFIRMED",

    // --- Per-sensor confidence values (filled by FusionEngine) ----------
    val cameraConfidence: Float? = null,
    val audioConfidence: Float? = null,
    val motionConfidence: Float? = null,

    // --- Evidence clip path (filled after saving audio evidence) ---------
    /** Absolute path to the saved 3gp audio clip, or null if not available. */
    val evidenceClipPath: String? = null,

    // --- SHA-256 hash chain fields ---------------------------------------
    /** SHA-256 hash of this event chained to [previousHash]. */
    val hash: String? = null,

    /** Hash of the preceding event in the timeline, or GENESIS_HASH for the first. */
    val previousHash: String? = null
)