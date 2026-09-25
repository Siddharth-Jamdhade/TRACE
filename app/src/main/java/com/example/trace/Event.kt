package com.example.trace

import androidx.room.ColumnInfo
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

    /** Primary triggering sensor: see [SensorRegistry] for all IDs. */
    val source: String,

    /** Normalized confidence of the primary triggering sensor (0.0–1.0). */
    val confidence: Float,

    /**
     * Fusion status: "CONFIRMED", "UNCONFIRMED", "REJECTED", or "MANUAL".
     *
     * "MANUAL" is not a fusion verdict — it marks a record a human asserted from
     * the live view. It is excluded from [FusionEngine]'s source count, so a tag
     * can never turn into a CONFIRMED incident on the strength of its own
     * presence. Mutable, and excluded from [HashChain]'s payload, so review
     * decisions (Confirm / Reject) never invalidate the chain.
     */
    var status: String = "UNCONFIRMED",

    // --- Per-sensor confidence values (filled by FusionEngine) ----------
    val cameraConfidence: Float? = null,
    val audioConfidence: Float? = null,
    val motionConfidence: Float? = null,

    /** |-separated "sensor:0.xx" pairs for extra sensors present in the window (see FusionEngine). */
    val sensorBreakdown: String? = null,

    // --- Evidence files (filled after capture) -----------------------
    /** Absolute path to the saved amr audio clip, or null if not available. */
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