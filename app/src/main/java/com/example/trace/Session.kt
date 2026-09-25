package com.example.trace

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * TRACE — a recording session: one continuous period of evidence capture.
 *
 * Every event belongs to exactly one session ([Event.sessionId]), and each
 * session carries its **own** SHA-256 hash chain, anchored on [sessionHash]
 * instead of on the global genesis block. That isolation is deliberate:
 *   - deleting, exporting or losing one session cannot invalidate another, and
 *   - the session header (who/what/when) is cryptographically bound to its own
 *     evidence, so editing the description after the fact breaks verification
 *     unless the entire chain is rewritten.
 *
 * Mutable-by-design fields are excluded from [sessionHash] for the same reason
 * [Event.status] is excluded from an event hash — closing a session or
 * refreshing its counters must not look like tampering:
 *
 *   hashed      id, natureOfWork, startedAt, sensorSet
 *   not hashed  state, endedAt, eventCount, confirmedCount, legacy
 */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** What the user said they were doing. Empty when they skipped the prompt. */
    val natureOfWork: String = "",

    /** System.currentTimeMillis() when recording started. */
    val startedAt: Long,

    /** When the session closed, or null while it is still open. */
    val endedAt: Long? = null,

    /** One of [STATE_ACTIVE], [STATE_COMPLETED], [STATE_INTERRUPTED]. */
    val state: String = STATE_ACTIVE,

    /** "|"-separated [SensorRegistry] ids enabled for this session. */
    val sensorSet: String = "",

    /**
     * SHA-256 anchor for this session's chain: the first event of the session
     * links to this value instead of to [HashChain.GENESIS_HASH].
     */
    val sessionHash: String = "",

    /** Aggregates, refreshed when the session closes. */
    val eventCount: Int = 0,
    val confirmedCount: Int = 0,

    /**
     * True only for the synthetic session holding evidence recorded before
     * sessions existed (migrated from schema v3). Its chain is anchored on
     * [HashChain.GENESIS_HASH] and it has no header to verify.
     */
    val legacy: Boolean = false
) {

    /** The [SensorRegistry] ids enabled for this session. */
    val sensorIds: List<String>
        get() = sensorSet.split('|').filter { it.isNotBlank() }

    /** Wall-clock duration in milliseconds, or null while still open. */
    val durationMs: Long?
        get() = endedAt?.let { it - startedAt }

    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_INTERRUPTED = "INTERRUPTED"

        /** Identifier reserved for the pre-session (v3) timeline. */
        const val LEGACY_ID = 1L
    }
}
