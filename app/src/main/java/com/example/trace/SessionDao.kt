package com.example.trace

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * TRACE — Room Data Access Object for [Session].
 *
 * All functions are suspend for use with Kotlin Coroutines on Dispatchers.IO.
 */
@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): Session?

    /** Sessions currently in [state] — used to recover sessions left open by a crash. */
    @Query("SELECT * FROM sessions WHERE state = :state ORDER BY startedAt DESC")
    suspend fun getSessionsByState(state: String): List<Session>

    /** Every session, newest first — used by the whole-timeline AI chat. */
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    suspend fun getAllSessions(): List<Session>

    /** Removes the session row. Its events are deleted separately, first. */
    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}
