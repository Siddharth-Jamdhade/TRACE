package com.example.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Stage 0 regression tests for the tamper-evidence hash chain.
 *
 * The bug these lock down: the capture pipeline used to read the chain tip and
 * insert the new event in two separate steps, so two sensors firing within the
 * same few milliseconds could both chain onto the same predecessor and fork the
 * timeline. [HashChain.verifyChain] then reported the whole timeline as INVALID.
 */
class ChainWriterTest {

    /**
     * In-memory [EventDao] that mirrors the real DAO's ordering semantics:
     * [EventDao.getChainTip] is the highest id (last inserted), and window
     * queries are ordered by timestamp.
     */
    private class FakeEventDao : EventDao {

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

        override suspend fun getChainTip(): Event? = rows.maxByOrNull { it.id }

        override suspend fun updateStatus(eventId: Long, newStatus: String) {
            val existing = rows.firstOrNull { it.id == eventId } ?: return
            update(existing.copy(status = newStatus))
        }

        override suspend fun getRecentEvents(sinceTimestamp: Long): List<Event> =
            rows.filter { it.timestamp >= sinceTimestamp }.sortedBy { it.timestamp }

        override suspend fun getEventsInWindow(from: Long, to: Long): List<Event> =
            rows.filter { it.timestamp in from..to }.sortedBy { it.timestamp }

        override suspend fun clearAll() {
            rows.clear()
        }
    }

    /** A sensor event whose timestamp increases with [seq]. */
    private fun event(seq: Int) = Event(
        type = "impact",
        timestamp = 1_700_000_000_000L + seq * 10L,
        source = "motion",
        confidence = 0.5f
    )

    @Test
    fun firstEventLinksToGenesis() {
        runBlocking {
            val stored = ChainWriter.append(FakeEventDao(), event(0))

            assertEquals(HashChain.GENESIS_HASH, stored.previousHash)
            assertEquals(HashChain.computeHash(stored, HashChain.GENESIS_HASH), stored.hash)
        }
    }

    @Test
    fun sequentialAppendsFormAVerifiableChain() {
        runBlocking {
            val dao = FakeEventDao()
            repeat(5) { ChainWriter.append(dao, event(it)) }

            val stored = dao.getAllEvents()
            assertEquals(5, stored.size)
            assertTrue("a sequentially appended chain must verify", HashChain.verifyChain(stored))
        }
    }

    @Test
    fun concurrentAppendsDoNotForkTheChain() {
        runBlocking {
            val dao = FakeEventDao()
            val total = 40

            (0 until total)
                .map { seq -> async(Dispatchers.Default) { ChainWriter.append(dao, event(seq)) } }
                .awaitAll()

            val stored = dao.getAllEvents()
            assertEquals(total, stored.size)

            // Every event must match the hash recorded for itself.
            stored.forEach { assertTrue("event ${it.id} fails its own hash check", HashChain.verifySingle(it)) }

            // Exactly one event may start from GENESIS ...
            assertEquals(1, stored.count { it.previousHash == HashChain.GENESIS_HASH })

            // ... and no two events may share a predecessor — that is a fork.
            assertEquals(
                "chain forked: more than one event links to the same previousHash",
                total,
                stored.map { it.previousHash }.toSet().size
            )

            // The whole chain must verify in append order. (Not asserted here
            // before the fix: insertion order is decided by the mutex, while the
            // test's timestamps ascend, so a forked chain is the failure mode.)
            assertTrue("concurrently appended chain must verify", HashChain.verifyChain(stored))
        }
    }

    @Test
    fun importedEventWithOlderTimestampDoesNotBreakTheChain() {
        runBlocking {
            val dao = FakeEventDao()
            val live = ChainWriter.append(dao, event(10))

            // Video import can contribute an event from earlier in the day, i.e.
            // with a timestamp older than the current tip.
            val imported = ChainWriter.append(
                dao,
                Event(
                    type = "object_moves",
                    timestamp = event(0).timestamp,
                    source = "video",
                    confidence = 0.7f
                )
            )

            assertEquals("imported event must link to the live tip", live.hash, imported.previousHash)
            assertTrue(
                "an older-timestamped import must not invalidate the chain",
                HashChain.verifyChain(dao.getAllEvents())
            )
        }
    }

    @Test
    fun enrichReceivesTheAssignedId() {
        runBlocking {
            val dao = FakeEventDao()
            var seenId = 0L

            val stored = ChainWriter.append(dao, event(0)) { inserted ->
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
            repeat(4) { ChainWriter.append(dao, event(it)) }
            val stored = dao.getAllEvents()

            val tampered = stored.mapIndexed { index, e ->
                if (index == 2) e.copy(type = "noise_complaint") else e
            }

            assertTrue(
                "a modified event must fail chain verification",
                !HashChain.verifyChain(tampered)
            )
        }
    }
}
