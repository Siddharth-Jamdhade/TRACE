package com.example.trace

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TRACE — session lifecycle.
 *
 * Owns the create / close / recover rules for [Session]s so that no caller has
 * to know how a session header is hashed:
 *   - a session's [Session.sessionHash] is always written before the session is
 *     handed out, because the session's first event anchors on it,
 *   - a session left ACTIVE by a process that died is recovered as
 *     [Session.STATE_INTERRUPTED] rather than silently continuing, and
 *   - closing a session fills in its aggregates without touching the header.
 *
 * Functions take DAOs rather than a database instance so the lifecycle rules
 * are unit-testable without Android.
 */
object SessionManager {

    /** Description used for sessions created to hold imported video analysis. */
    const val IMPORT_SESSION_NAME = "Imported footage"

    /** The session this process is currently recording into, if any. */
    @Volatile
    var activeSessionId: Long? = null
        private set

    /** Guards session creation, so two simultaneous callers share one session. */
    private val lifecycleLock = Mutex()

    /**
     * Creates a session and writes the header hash its chain will anchor on.
     *
     * The session is inserted before the hash is computed because the id is part
     * of the hashed header; the hash is then written immediately, before any
     * caller can append an event.
     */
    suspend fun createSession(
        sessionDao: SessionDao,
        natureOfWork: String = "",
        sensorSet: String = defaultSensorSet(),
        startedAt: Long = System.currentTimeMillis()
    ): Session {
        val draft = Session(
            natureOfWork = natureOfWork,
            startedAt = startedAt,
            sensorSet = sensorSet
        )
        val id = sessionDao.insert(draft)
        val hashed = draft.copy(id = id).let {
            it.copy(sessionHash = HashChain.computeSessionHash(it))
        }
        sessionDao.update(hashed)
        return hashed
    }

    /**
     * Returns the session this process is recording into, creating one if it has
     * not started recording yet. Concurrent callers share a single session.
     */
    suspend fun ensureActiveSession(
        sessionDao: SessionDao,
        natureOfWork: String = "",
        sensorSet: String = defaultSensorSet()
    ): Session = lifecycleLock.withLock {
        activeSessionId?.let { id -> sessionDao.getSessionById(id)?.let { return it } }

        val session = createSession(sessionDao, natureOfWork, sensorSet)
        activeSessionId = session.id
        session
    }

    /**
     * Closes [sessionId] with its aggregates filled in and clears the process's
     * active session if it was this one.
     */
    suspend fun endSession(
        sessionDao: SessionDao,
        eventDao: EventDao,
        sessionId: Long,
        state: String = Session.STATE_COMPLETED
    ): Session? {
        val session = sessionDao.getSessionById(sessionId) ?: return null
        val closed = closeOut(eventDao, session, state)
        sessionDao.update(closed)
        if (activeSessionId == sessionId) activeSessionId = null
        return closed
    }

    /** [endSession] for the process-wide active session. */
    suspend fun endActiveSession(sessionDao: SessionDao, eventDao: EventDao): Session? {
        val id = activeSessionId ?: return null
        return endSession(sessionDao, eventDao, id)
    }

    /**
     * Marks every session left ACTIVE by an earlier process as INTERRUPTED, so a
     * crash or force-stop cannot leave a session that looks like it is still
     * recording. Returns how many were recovered.
     */
    suspend fun recoverInterruptedSessions(sessionDao: SessionDao, eventDao: EventDao): Int {
        var recovered = 0
        for (session in sessionDao.getSessionsByState(Session.STATE_ACTIVE)) {
            if (session.id == activeSessionId) continue

            val eventCount = eventDao.countEventsForSession(session.id)
            val usable =
                if (session.sessionHash.isBlank() && eventCount == 0) {
                    // The process died between the insert and the header-hash
                    // write. Nothing can be anchored to a session with no
                    // events, so hashing the header now is safe — and the
                    // alternative is reporting an untouched session as tampered.
                    session.copy(sessionHash = HashChain.computeSessionHash(session))
                } else {
                    session
                }

            sessionDao.update(closeOut(eventDao, usable, Session.STATE_INTERRUPTED))
            recovered++
        }
        return recovered
    }

    /**
     * Session for evidence imported from outside a live recording.
     *
     * Imported footage gets its own session, keeping provenance obvious (its
     * events carry [SensorRegistry.VIDEO] as their source) and keeping its chain
     * independent of whatever is being recorded live.
     */
    suspend fun createImportSession(sessionDao: SessionDao): Session =
        createSession(sessionDao, natureOfWork = IMPORT_SESSION_NAME, sensorSet = "")

    /** Computes the closed form of [session] — writes nothing. */
    private suspend fun closeOut(eventDao: EventDao, session: Session, state: String): Session =
        session.copy(
            state = state,
            endedAt = eventDao.lastTimestampForSession(session.id) ?: System.currentTimeMillis(),
            eventCount = eventDao.countEventsForSession(session.id),
            confirmedCount = eventDao.countEventsForSessionWithStatus(session.id, "CONFIRMED")
        )

    /** "|"-separated ids of every live capture sensor. */
    private fun defaultSensorSet(): String = SensorRegistry.ALL.joinToString("|") { it.id }

    @VisibleForTesting
    internal fun resetForTest() {
        activeSessionId = null
    }
}
