package com.example.trace

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * TRACE — Room Data Access Object for [Event].
 *
 * All functions are suspend for use with Kotlin Coroutines on Dispatchers.IO.
 */
@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: Event): Long

    @Update
    suspend fun update(event: Event)

    @Query("SELECT * FROM events ORDER BY timestamp ASC")
    suspend fun getAllEvents(): List<Event>

    @Query("SELECT * FROM events WHERE id = :eventId LIMIT 1")
    suspend fun getEventById(eventId: Long): Event?

    // ── Session-scoped queries ────────────────────────────────────────────

    /** Events of one session, in append (custody) order. */
    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getEventsForSession(sessionId: Long): List<Event>

    /**
     * The most recently *appended* event of a session — its chain tip.
     *
     * Deliberately ordered by id, not timestamp: the chain records the order in
     * which TRACE appended evidence. An imported event could have a
     * timestamp older than the current tip, and ordering by timestamp would
     * then link the new event to a row that does not actually precede it.
     */
    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun getChainTipForSession(sessionId: Long): Event?

    /**
     * The most recent [limit] events of a session, oldest first.
     *
     * Reads only — no schema change — and backs the live view's log tail: when
     * the dashboard re-attaches to a running session it reseeds the tail from
     * the chain's actual last events, so entries recorded while the screen was
     * away are not lost. Ordered by id (custody order) for the same reason
     * [getChainTipForSession] is.
     */
    @Query(
        "SELECT * FROM (SELECT * FROM events WHERE sessionId = :sessionId " +
            "ORDER BY id DESC LIMIT :limit) ORDER BY id ASC"
    )
    suspend fun getRecentEventsForSession(sessionId: Long, limit: Int): List<Event>

    @Query("SELECT COUNT(*) FROM events WHERE sessionId = :sessionId")
    suspend fun countEventsForSession(sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM events WHERE sessionId = :sessionId AND status = :status")
    suspend fun countEventsForSessionWithStatus(sessionId: Long, status: String): Int

    /** Timestamp of the last event in a session, or null if it has none. */
    @Query("SELECT MAX(timestamp) FROM events WHERE sessionId = :sessionId")
    suspend fun lastTimestampForSession(sessionId: Long): Long?

    @Query("UPDATE events SET status = :newStatus WHERE id = :eventId")
    suspend fun updateStatus(eventId: Long, newStatus: String)

    /** Attaches the saved audio clip to [eventId] once the file exists on disk. */
    @Query("UPDATE events SET evidenceClipPath = :path WHERE id = :eventId")
    suspend fun updateClipPath(eventId: Long, path: String)

    /** Attaches the saved camera snapshot to [eventId] once the file exists on disk. */
    @Query("UPDATE events SET evidencePhotoPath = :path WHERE id = :eventId")
    suspend fun updatePhotoPath(eventId: Long, path: String)

    /** Stores the audio clip's SHA-256, computed at write time. */
    @Query("UPDATE events SET clipHash = :hash WHERE id = :eventId")
    suspend fun updateClipHash(eventId: Long, hash: String)

    /** Stores the snapshot's SHA-256, computed at write time. */
    @Query("UPDATE events SET photoHash = :hash WHERE id = :eventId")
    suspend fun updatePhotoHash(eventId: Long, hash: String)

    @Query("SELECT * FROM events WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getRecentEvents(sinceTimestamp: Long): List<Event>

    /**
     * Events of one session whose timestamp falls within [from, to] inclusive.
     *
     * Session-scoped so a fusion window can never pull evidence in from another
     * session — for example imported footage whose timestamps overlap a live
     * session would otherwise be able to confirm (or be confirmed by) it.
     */
    @Query(
        "SELECT * FROM events WHERE sessionId = :sessionId " +
            "AND timestamp BETWEEN :from AND :to ORDER BY timestamp ASC"
    )
    suspend fun getEventsInWindowForSession(sessionId: Long, from: Long, to: Long): List<Event>

    @Query("DELETE FROM events")
    suspend fun clearAll()

    /**
     * Deletes every event of one session — per-session deletion only ever
     * happens behind an explicit confirmation, and the files on disk are
     * removed by [EvidenceFiles] alongside it.
     */
    @Query("DELETE FROM events WHERE sessionId = :sessionId")
    suspend fun deleteEventsForSession(sessionId: Long)
}