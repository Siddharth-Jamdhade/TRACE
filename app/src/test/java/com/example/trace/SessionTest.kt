package com.example.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that each session owns an independent, verifiable chain, and that the
 * session lifecycle (create / close / recover) keeps that chain honest.
 */
class SessionTest {

    @After
    fun tearDown() {
        SessionManager.resetForTest()
    }

    private fun event(sessionId: Long, seq: Int, status: String = "UNCONFIRMED") = Event(
        type = "impact",
        timestamp = 1_700_000_000_000L + seq * 10L,
        source = "motion",
        confidence = 0.5f,
        status = status,
        sessionId = sessionId
    )

    // ── Chain scoping ─────────────────────────────────────────────────────

    @Test
    fun eachSessionAnchorsOnItsOwnHeader() {
        runBlocking {
            val dao = FakeEventDao()
            val first = testSession(id = 1, natureOfWork = "inspection")
            val second = testSession(id = 2, natureOfWork = "delivery")

            val a1 = ChainWriter.append(dao, first, event(1, 0))
            val b1 = ChainWriter.append(dao, second, event(2, 0))

            assertEquals(first.sessionHash, a1.previousHash)
            assertEquals(second.sessionHash, b1.previousHash)
            assertNotEquals("session 2 must not chain onto session 1", a1.hash, b1.previousHash)
        }
    }

    @Test
    fun aDamagedSessionDoesNotInvalidateAnother() {
        runBlocking {
            val dao = FakeEventDao()
            val healthy = testSession(id = 1)
            val damaged = testSession(id = 2)

            repeat(3) { ChainWriter.append(dao, healthy, event(1, it)) }
            repeat(3) { ChainWriter.append(dao, damaged, event(2, it)) }

            // Corrupt one event of the damaged session only.
            val corrupted = dao.getEventsForSession(2).mapIndexed { index, e ->
                if (index == 1) e.copy(confidence = 0.9f) else e
            }

            assertFalse(HashChain.verifySession(damaged, corrupted))
            assertTrue(
                "damage in one session must not affect another",
                HashChain.verifySession(healthy, dao.getEventsForSession(1))
            )
        }
    }

    @Test
    fun verifySessionIgnoresEventsFromOtherSessions() {
        runBlocking {
            val dao = FakeEventDao()
            val session = testSession(id = 1)
            repeat(2) { ChainWriter.append(dao, session, event(1, it)) }

            // Verify against the whole table, including a foreign session's rows.
            assertTrue(HashChain.verifySession(session, dao.getAllEvents() + event(2, 99)))
        }
    }

    @Test
    fun aSessionThatLostItsFirstEventFailsVerification() {
        runBlocking {
            val dao = FakeEventDao()
            val session = testSession(id = 1)
            repeat(3) { ChainWriter.append(dao, session, event(1, it)) }

            assertTrue("an empty session is trivially valid", HashChain.verifySession(session, emptyList()))

            // Dropping the anchor event leaves a chain that starts mid-stream.
            val withoutFirst = dao.getEventsForSession(1).drop(1)
            assertFalse(HashChain.verifySession(session, withoutFirst))
        }
    }

    @Test
    fun editingTheSessionDescriptionIsDetected() {
        runBlocking {
            val dao = FakeEventDao()
            val session = testSession(id = 1, natureOfWork = "inspection")
            repeat(2) { ChainWriter.append(dao, session, event(1, it)) }

            val edited = session.copy(natureOfWork = "nothing to see here")
            assertFalse(
                "a session header edit must break verification",
                HashChain.verifySession(edited, dao.getEventsForSession(1))
            )
        }
    }

    @Test
    fun legacySessionIsAnchoredOnGenesis() {
        // Rebuild the pre-session (v3) chain exactly as the migration leaves it:
        // genesis-anchored, hashes untouched.
        var previous = HashChain.GENESIS_HASH
        val chained = (0..1).map { seq ->
            val e = event(Session.LEGACY_ID, seq)
            val hashed = e.copy(previousHash = previous, hash = HashChain.computeHash(e, previous))
            previous = hashed.hash!!
            hashed
        }
        val legacy = Session(
            id = Session.LEGACY_ID,
            natureOfWork = "Pre-session timeline (legacy)",
            startedAt = 0,
            state = Session.STATE_COMPLETED,
            sessionHash = HashChain.GENESIS_HASH,
            legacy = true
        )

        assertTrue(HashChain.verifySession(legacy, chained))
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Test
    fun createSessionWritesTheHeaderHash() {
        runBlocking {
            val sessions = FakeSessionDao()
            val session = SessionManager.createSession(sessions, natureOfWork = "site visit")

            assertNotEquals(0L, session.id)
            assertEquals(HashChain.computeSessionHash(session), session.sessionHash)
            assertEquals(session.sessionHash, sessions.getSessionById(session.id)?.sessionHash)
        }
    }

    @Test
    fun concurrentStartCallsShareOneSession() {
        runBlocking {
            val sessions = FakeSessionDao()

            // A double tap on "Start session" must not record one walk around the
            // site as two sessions.
            val created = (0 until 8)
                .map { async(Dispatchers.Default) { SessionManager.startSession(sessions) } }
                .awaitAll()

            assertEquals("every caller must get the same session", 1, created.map { it.id }.toSet().size)
            assertEquals(1, sessions.all().size)
        }
    }

    @Test
    fun startSessionRecordsTheOperatorsChoices() {
        runBlocking {
            val sessions = FakeSessionDao()
            val sensorSet = SensorRegistry.sensorSetOf(listOf("door"))

            val session = SessionManager.startSession(
                sessions,
                natureOfWork = "door inspection",
                sensorSet = sensorSet
            )

            assertEquals("door inspection", session.natureOfWork)
            assertEquals(sensorSet, session.sensorSet)
            assertEquals(Session.STATE_ACTIVE, session.state)
            // The header hash must cover the description and the sensor set, so both
            // are fixed for the life of the session.
            assertEquals(HashChain.computeSessionHash(session), session.sessionHash)
            assertEquals(session.id, SessionManager.currentSession(sessions)?.id)
        }
    }

    @Test
    fun nothingIsActiveBeforeArming() {
        runBlocking {
            val sessions = FakeSessionDao()
            assertNull("capture must be idle until the operator arms a session",
                SessionManager.currentSession(sessions))
        }
    }

    @Test
    fun anEndedSessionIsNoLongerActive() {
        runBlocking {
            val sessions = FakeSessionDao()
            val events = FakeEventDao()
            val session = SessionManager.startSession(sessions, natureOfWork = "first walk")

            SessionManager.endSession(sessions, events, session.id)
            assertNull("a closed session must not be resumed by accident",
                SessionManager.currentSession(sessions))

            // Re-arming starts a genuinely new session, not the finished one.
            val second = SessionManager.startSession(sessions, natureOfWork = "second walk")
            assertNotEquals(session.id, second.id)
            assertEquals("second walk", second.natureOfWork)
        }
    }

    @Test
    fun consecutiveSessionsKeepIndependentChains() {
        runBlocking {
            val sessions = FakeSessionDao()
            val events = FakeEventDao()

            val first = SessionManager.startSession(sessions, natureOfWork = "first")
            repeat(2) { ChainWriter.append(events, first, event(first.id, it)) }
            SessionManager.endSession(sessions, events, first.id)

            val second = SessionManager.startSession(sessions, natureOfWork = "second")
            repeat(2) { ChainWriter.append(events, second, event(second.id, it + 10)) }

            assertTrue(HashChain.verifySession(first, events.getEventsForSession(first.id)))
            assertTrue(
                "the second session must anchor on its own header, not carry the first's tail",
                HashChain.verifySession(second, events.getEventsForSession(second.id))
            )
            assertEquals(
                second.sessionHash,
                events.getEventsForSession(second.id).first().previousHash
            )
        }
    }

    @Test
    fun endSessionFillsAggregatesWithoutTouchingTheHeader() {
        runBlocking {
            val sessions = FakeSessionDao()
            val events = FakeEventDao()
            val session = SessionManager.startSession(sessions)

            ChainWriter.append(events, session, event(session.id, 0, status = "CONFIRMED"))
            ChainWriter.append(events, session, event(session.id, 1))

            val closed = SessionManager.endSession(sessions, events, session.id)!!

            assertEquals(Session.STATE_COMPLETED, closed.state)
            assertEquals(2, closed.eventCount)
            assertEquals(1, closed.confirmedCount)
            assertEquals(event(session.id, 1).timestamp, closed.endedAt ?: -1L)
            assertEquals("closing must not change the header", session.sessionHash, closed.sessionHash)
            assertNull("closing clears the active session", SessionManager.activeSessionId)
        }
    }

    @Test
    fun recoverClosesSessionsLeftActiveByAnotherRun() {
        runBlocking {
            val sessions = FakeSessionDao()
            val events = FakeEventDao()

            // Left open by a previous process (never marked active by this one).
            val orphan = SessionManager.createSession(sessions, natureOfWork = "previous run")
            ChainWriter.append(events, orphan, event(orphan.id, 0, status = "CONFIRMED"))

            // Owned by the current process.
            val current = SessionManager.startSession(sessions)

            val recovered = SessionManager.recoverInterruptedSessions(sessions, events)

            assertEquals(1, recovered)
            val stored = sessions.getSessionById(orphan.id)!!
            assertEquals(Session.STATE_INTERRUPTED, stored.state)
            assertEquals(1, stored.eventCount)
            assertEquals(1, stored.confirmedCount)
            assertEquals(
                "the live session must be left alone",
                Session.STATE_ACTIVE,
                sessions.getSessionById(current.id)!!.state
            )
        }
    }

    @Test
    fun recoveryRepairsAnUnhashedEmptySession() {
        runBlocking {
            val sessions = FakeSessionDao()
            val events = FakeEventDao()

            // Simulates a crash between the session insert and the header-hash write.
            val id = sessions.insert(
                Session(natureOfWork = "crashed", startedAt = 1L, sessionHash = "")
            )

            assertEquals(1, SessionManager.recoverInterruptedSessions(sessions, events))

            val stored = sessions.getSessionById(id)!!
            assertEquals(Session.STATE_INTERRUPTED, stored.state)
            assertEquals(HashChain.computeSessionHash(stored), stored.sessionHash)
        }
    }
}
