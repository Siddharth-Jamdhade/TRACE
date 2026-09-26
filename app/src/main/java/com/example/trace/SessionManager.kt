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
     * Opens a session for the operator and marks it as the one this process is
     * recording into.
     *
     * Capture is armed explicitly (the operator states the nature of work and
     * picks the sensor set), so nothing calls this implicitly — a session only
     * ever exists because a person asked for one.
     *
     * If a session is already open it is returned unchanged: a double tap on
     * *Start session* must not produce two recordings from one walk around the
     * site, and the second tap has no description or sensor set of its own to
     * apply anyway.
     *
     * @param sensorSet "|"-separated [SensorRegistry] ids, see
     *   [SensorRegistry.sensorSetOf]. It is part of the hashed session header, so
     *   it is fixed for the life of the session.
     */
    suspend fun startSession(
        sessionDao: SessionDao,
        natureOfWork: String = "",
        sensorSet: String = defaultSensorSet(),
        startedAt: Long = System.currentTimeMillis()
    ): Session = lifecycleLock.withLock {
        openSessionOrNull(sessionDao)?.let { return it }

        val session = createSession(sessionDao, natureOfWork, sensorSet, startedAt)
        activeSessionId = session.id
        session
    }

    /**
     * The session this process is recording into, or null when nothing is armed.
     *
     * A session that has been closed ([Session.STATE_COMPLETED]) is not active, so
     * a stale id left behind by a finished recording can never be resumed by
     * accident.
     */
    suspend fun currentSession(sessionDao: SessionDao): Session? = openSessionOrNull(sessionDao)

    /** Unlocked body of [currentSession], safe to call while holding [lifecycleLock]. */
    private suspend fun openSessionOrNull(sessionDao: SessionDao): Session? {
        val id = activeSessionId ?: return null
        val session = sessionDao.getSessionById(id) ?: return null
        return session.takeIf { it.state == Session.STATE_ACTIVE }
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

    /** Computes the closed form of [session] — writes nothing. */
    private suspend fun closeOut(eventDao: EventDao, session: Session, state: String): Session =
        session.copy(
            state = state,
            endedAt = eventDao.lastTimestampForSession(session.id) ?: System.currentTimeMillis(),
            eventCount = eventDao.countEventsForSession(session.id),
        )

    /** "|"-separated ids of every live capture sensor. */
    private fun defaultSensorSet(): String = SensorRegistry.sensorSetOf(SensorSuggester.all())

    @VisibleForTesting
    internal fun resetForTest() {
        activeSessionId = null
    }
}
