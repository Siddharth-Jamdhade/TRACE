package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the live session log buffer.
 *
 * The buffer is a runtime surface, not evidence — the tests pin the two
 * properties that make it a truthful surface: the cap is enforced (a long
 * session cannot grow it without bound) and appends from any thread are not
 * lost (the pipeline produces log lines from sensor, audio and main threads).
 */
class SessionLogTest {

    @Test
    fun `appends keep chronological order oldest first`() {
        val log = SessionLog()
        log.append(SessionLog.Kind.LIFECYCLE, "one", timestamp = 1)
        log.append(SessionLog.Kind.EVENT, "two", timestamp = 2)
        log.append(SessionLog.Kind.TAG, "three", timestamp = 3)

        val texts = log.all().map { it.text }
        assertEquals(listOf("one", "two", "three"), texts)
        assertEquals(listOf(1L, 2L, 3L), log.all().map { it.timestamp })
    }

    @Test
    fun `buffer never exceeds its capacity and drops the oldest`() {
        val log = SessionLog(capacity = 5)
        repeat(20) { i -> log.append(SessionLog.Kind.EVENT, "line-$i", timestamp = i.toLong()) }

        val lines = log.all()
        assertEquals(5, lines.size)
        // The five newest survive, in order.
        assertEquals(
            (15..19).map { "line-$it" },
            lines.map { it.text }
        )
        // Timestamps travel with their lines — a re-render must not re-date them.
        assertEquals((15L..19L).toList(), lines.map { it.timestamp })
    }

    @Test
    fun `capacity of exactly the append count keeps everything`() {
        val log = SessionLog(capacity = 3)
        repeat(3) { i -> log.append(SessionLog.Kind.INFO, "line-$i") }
        assertEquals(3, log.all().size)
    }

    @Test
    fun `clear empties the buffer for a new session`() {
        val log = SessionLog()
        log.append(SessionLog.Kind.EVENT, "stale entry")
        log.clear()
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `default capacity holds the documented size`() {
        val log = SessionLog()
        repeat(SessionLog.DEFAULT_CAPACITY + 10) { log.append(SessionLog.Kind.EVENT, "x") }
        assertEquals(SessionLog.DEFAULT_CAPACITY, log.all().size)
    }

    @Test
    fun `concurrent appends never lose or corrupt lines`() {
        val log = SessionLog(capacity = 10_000)
        val threads = (1..8).map { t ->
            Thread {
                repeat(200) { i -> log.append(SessionLog.Kind.EVENT, "t$t-$i") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val all = log.all()
        assertEquals(8 * 200, all.size)
        // No torn lines: every entry is one thread's complete write.
        assertTrue(all.all { it.text.matches(Regex("t[1-8]-\\d+")) })
    }

    @Test
    fun `kinds survive the round trip so colours cannot drift`() {
        val log = SessionLog()
        log.append(SessionLog.Kind.EVENT, "e")
        log.append(SessionLog.Kind.TAG, "t")
        log.append(SessionLog.Kind.LIFECYCLE, "l")
        log.append(SessionLog.Kind.ERROR, "x")
        log.append(SessionLog.Kind.INFO, "i")

        assertEquals(
            listOf(
                SessionLog.Kind.EVENT,
                SessionLog.Kind.TAG,
                SessionLog.Kind.LIFECYCLE,
                SessionLog.Kind.ERROR,
                SessionLog.Kind.INFO
            ),
            log.all().map { it.kind }
        )
    }
}
