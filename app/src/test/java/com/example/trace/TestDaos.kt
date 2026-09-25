package com.example.trace

import java.util.Collections

/**
 * In-memory DAO fakes for the JVM unit tests.
 *
 * They mirror the semantics the real queries rely on:
 *  - `getChainTipForSession` is the highest id *of that session*,
 *  - window and "recent" queries are ordered by timestamp,
 *  - inserts assign increasing ids across all sessions, as AUTOINCREMENT does.
 */
internal class FakeEventDao : EventDao {

    private val rows = Collections.synchronizedList(mutableListOf<Event>())
    private var nextId = 1L

    override suspend fun insert(event: Event): Long {
        val id = nextId++
        rows.add(event.copy(id = id))
        return id
    }

    override suspend fun update(event: Event) {
        val index = rows.indexOfFirst { it.id == event.id }
        if (index >= 0) rows[index] = event
    }

    override suspend fun getAllEvents(): List<Event> = rows.toList()

    override suspend fun getEventById(eventId: Long): Event? =
        rows.firstOrNull { it.id == eventId }

    override suspend fun updateStatus(eventId: Long, newStatus: String) {
        rows.firstOrNull { it.id == eventId }?.let { update(it.copy(status = newStatus)) }
    }

    override suspend fun updateClipPath(eventId: Long, path: String) {
        rows.firstOrNull { it.id == eventId }?.let { update(it.copy(evidenceClipPath = path)) }
    }

    override suspend fun updatePhotoPath(eventId: Long, path: String) {
        rows.firstOrNull { it.id == eventId }?.let { update(it.copy(evidencePhotoPath = path)) }
    }

    override suspend fun getRecentEvents(sinceTimestamp: Long): List<Event> =
        rows.filter { it.timestamp >= sinceTimestamp }.sortedBy { it.timestamp }

    override suspend fun getEventsInWindowForSession(
        sessionId: Long,
        from: Long,
        to: Long
    ): List<Event> = rows
        .filter { it.sessionId == sessionId && it.timestamp in from..to }
        .sortedBy { it.timestamp }

    override suspend fun getEventsForSession(sessionId: Long): List<Event> =
        rows.filter { it.sessionId == sessionId }.sortedBy { it.id }

    override suspend fun getChainTipForSession(sessionId: Long): Event? =
        rows.filter { it.sessionId == sessionId }.maxByOrNull { it.id }

    override suspend fun getRecentEventsForSession(sessionId: Long, limit: Int): List<Event> =
        rows.filter { it.sessionId == sessionId }
            .sortedByDescending { it.id }
            .take(limit)
            .sortedBy { it.id }

    override suspend fun countEventsForSession(sessionId: Long): Int =
        rows.count { it.sessionId == sessionId }

    override suspend fun countEventsForSessionWithStatus(sessionId: Long, status: String): Int =
        rows.count { it.sessionId == sessionId && it.status == status }

    override suspend fun lastTimestampForSession(sessionId: Long): Long? =
        rows.filter { it.sessionId == sessionId }.maxOfOrNull { it.timestamp }

    override suspend fun clearAll() {
        rows.clear()
    }
}

/** In-memory [SessionDao]. Ids are assigned the way AUTOINCREMENT assigns them. */
internal class FakeSessionDao : SessionDao {

    private val rows = Collections.synchronizedList(mutableListOf<Session>())
    private var nextId = 1L

    override suspend fun insert(session: Session): Long {
        val id = nextId++
        rows.add(session.copy(id = id))
        return id
    }

    override suspend fun update(session: Session) {
        val index = rows.indexOfFirst { it.id == session.id }
        if (index >= 0) rows[index] = session
    }

    override suspend fun getSessionById(sessionId: Long): Session? =
        rows.firstOrNull { it.id == sessionId }

    override suspend fun getSessionsByState(state: String): List<Session> =
        rows.filter { it.state == state }.sortedByDescending { it.startedAt }

    /** Test-only helper: everything stored so far. */
    fun all(): List<Session> = rows.toList()
}

/** A session whose header hash is consistent, ready to anchor a chain. */
internal fun testSession(
    id: Long,
    natureOfWork: String = "test session",
    startedAt: Long = 1_700_000_000_000L,
    sensorSet: String = "motion|audio"
): Session {
    val draft = Session(
        id = id,
        natureOfWork = natureOfWork,
        startedAt = startedAt,
        sensorSet = sensorSet
    )
    return draft.copy(sessionHash = HashChain.computeSessionHash(draft))
}
