package com.example.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the append path of a session's hash chain.
 *
 * The original bug these lock down: the capture pipeline read the chain tip and
 * inserted the new event in two separate steps, so two sensors firing within the
 * same few milliseconds could both chain onto the same predecessor and fork the
 * timeline.
 */
class ChainWriterTest {

    private val session = testSession(id = 1)

    /** A sensor event whose timestamp increases with [seq]. */
    private fun event(seq: Int) = Event(
        type = "impact",
        timestamp = 1_700_000_000_000L + seq * 10L,
        source = "motion",
        confidence = 0.5f
    )

    @Test
    fun firstEventLinksToTheSessionAnchor() {
        runBlocking {
            val dao = FakeEventDao()
            val stored = ChainWriter.append(dao, session, event(0))

            assertEquals(session.sessionHash, stored.previousHash)
            assertEquals(session.id, stored.sessionId)
            assertEquals(HashChain.computeHash(stored, session.sessionHash), stored.hash)
        }
    }

    @Test
    fun sequentialAppendsFormAVerifiableChain() {
        runBlocking {
            val dao = FakeEventDao()
            repeat(5) { ChainWriter.append(dao, session, event(it)) }

            val stored = dao.getEventsForSession(session.id)
            assertEquals(5, stored.size)
            assertTrue(
                "a sequentially appended chain must verify",
                HashChain.verifySession(session, stored)
            )
        }
    }

    @Test
    fun concurrentAppendsDoNotForkTheChain() {
        runBlocking {
            val dao = FakeEventDao()
            val total = 40

            (0 until total)
                .map { seq -> async(Dispatchers.Default) { ChainWriter.append(dao, session, event(seq)) } }
                .awaitAll()

            val stored = dao.getEventsForSession(session.id)
            assertEquals(total, stored.size)

            // Every event must match the hash recorded for itself.
            stored.forEach { assertTrue("event ${it.id} fails its own hash check", HashChain.verifySingle(it)) }

            // Exactly one event anchors on the session header ...
            assertEquals(1, stored.count { it.previousHash == session.sessionHash })

            // ... and no two events may share a predecessor — that is a fork.
            assertEquals(
                "chain forked: more than one event links to the same previousHash",
                total,
                stored.map { it.previousHash }.toSet().size
            )

            // Verification walks append (id) order, so it must hold even though
            // the mutex decides that order while the test's timestamps ascend.
            assertTrue(
                "concurrently appended chain must verify",
                HashChain.verifySession(session, stored)
            )
        }
    }

    @Test
    fun anOlderTimestampedAppendStillLinksInAppendOrder() {
        runBlocking {
            val dao = FakeEventDao()
            val live = ChainWriter.append(dao, session, event(10))

            // Imported footage can contribute an event from earlier in the day,
            // i.e. with a timestamp older than the current tip.
            val imported = ChainWriter.append(dao, session, event(0))

            assertEquals("the later append must link to the live tip", live.hash, imported.previousHash)
            assertTrue(
                "an older-timestamped append must not invalidate the chain",
                HashChain.verifySession(session, dao.getEventsForSession(session.id))
            )
        }
    }

    @Test
    fun enrichReceivesTheAssignedId() {
        runBlocking {
            val dao = FakeEventDao()
            var seenId = 0L

            val stored = ChainWriter.append(dao, session, event(0)) { inserted ->
                seenId = inserted.id
                inserted.copy(status = "CONFIRMED")
            }

            assertNotEquals("enrich must run after the id is assigned", 0L, seenId)
            assertEquals(seenId, stored.id)
            assertEquals("CONFIRMED", stored.status)
        }
    }

    @Test
    fun tamperingWithAStoredEventBreaksTheChain() {
        runBlocking {
            val dao = FakeEventDao()
            repeat(4) { ChainWriter.append(dao, session, event(it)) }

            val tampered = dao.getEventsForSession(session.id).mapIndexed { index, e ->
                if (index == 2) e.copy(type = "noise_complaint") else e
            }

            assertTrue(
                "a modified event must fail chain verification",
                !HashChain.verifySession(session, tampered)
            )
        }
    }
}
