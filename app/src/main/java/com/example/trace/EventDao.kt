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

    /**
     * The most recently *inserted* event — the current chain tip.
     *
     * Deliberately ordered by id, not timestamp: the chain records the order in
     * which TRACE appended evidence. Video import can contribute an event whose
     * timestamp is older than the current tip, and ordering by timestamp would
     * then link the new event to a row that does not actually precede it.
     */
    @Query("SELECT * FROM events ORDER BY id DESC LIMIT 1")
    suspend fun getChainTip(): Event?

    @Query("UPDATE events SET status = :newStatus WHERE id = :eventId")
    suspend fun updateStatus(eventId: Long, newStatus: String)

    @Query("SELECT * FROM events WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    suspend fun getRecentEvents(sinceTimestamp: Long): List<Event>

    /** Returns all events whose timestamp falls within [from, to] inclusive. */
    @Query("SELECT * FROM events WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getEventsInWindow(from: Long, to: Long): List<Event>

    @Query("DELETE FROM events")
    suspend fun clearAll()
}