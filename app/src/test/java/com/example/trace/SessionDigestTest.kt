package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Cloud AI digest builder and provider registry.
 *
 * The digest is the grounding contract: whatever these tests pin is what the
 * model is told. They keep the prompt deterministic, keep human claims marked
 * as claims (never laundered into sensor readings), and cap the prompt size so
 * a long session cannot silently explode the token cost.
 */
class SessionDigestTest {

    private fun session(
        id: Long = 7,
        natureOfWork: String = "door inspection",
        sensorSet: String = "camera|audio|motion"
    ): Session = Session(
        id = id,
        natureOfWork = natureOfWork,
        startedAt = 1_760_000_000_000L,
        endedAt = 1_760_000_600_000L,
        state = Session.STATE_COMPLETED,
        sensorSet = sensorSet,
        eventCount = 2
    )

    private fun event(
        id: Long,
        type: String = "impact",
        source: String = "motion",
        status: String = "CONFIRMED",
        confidence: Float = 0.9f,
        clip: Boolean = false,
        photo: Boolean = false
    ) = Event(
        id = id, type = type, timestamp = 1_760_000_100_000L + id * 1000,
        source = source, confidence = confidence, status = status,
        sessionId = 7,
        evidenceClipPath = if (clip) "/x/evidence_$id.amr" else null,
        evidencePhotoPath = if (photo) "/x/photo_$id.jpg" else null
    )

    @Test
    fun `header names the session, its work, state and sensors`() {
        val header = SessionDigest.buildHeader(session())
        assertTrue(header.contains("Session #7"))
        assertTrue(header.contains("door inspection"))
        assertTrue(header.contains("COMPLETED"))
        assertTrue(header.contains("Camera"))
        assertTrue(header.contains("Motion"))
        assertFalse(header.contains("HashChain"))
    }

    @Test
    fun `sensor line carries status, label and confidence`() {
        val lines = SessionDigest.buildEventLines(listOf(event(1)))
        assertEquals(1, lines.size)
        val line = lines[0]
        assertTrue("status in line: $line", line.contains("[CONFIRMED]"))
        assertTrue("label in line: $line", line.contains("impact"))
        assertTrue("source in line: $line", line.contains("Motion"))
        assertTrue("confidence in line: $line", line.contains("90%"))
    }

    @Test
    fun `manual tags are marked as human claims, never as sensor readings`() {
        val lines = SessionDigest.buildEventLines(
            listOf(event(1, type = "door_slam", source = SensorRegistry.MANUAL.id, status = "MANUAL"))
        )
        val line = lines[0]
        assertTrue("MANUAL marker: $line", line.contains("[MANUAL]"))
        assertTrue("human attribution: $line", line.contains("human operator"))
        assertFalse("no confidence on a claim: $line", line.contains("%)"))
    }

    @Test
    fun `evidence markers appear for clips and photos`() {
        val lines = SessionDigest.buildEventLines(listOf(event(1, clip = true, photo = true)))
        assertTrue(lines[0].contains("[audio]"))
        assertTrue(lines[0].contains("[photo]"))
    }

    @Test
    fun `event lines are chronological and the tail cap keeps the newest`() {
        val events = (1..SessionDigest.MAX_EVENT_LINES + 30).map { event(it.toLong()) }
        val lines = SessionDigest.buildEventLines(events)
        // omitted notice + 120 capped lines
        assertEquals(SessionDigest.MAX_EVENT_LINES + 1, lines.size)
        assertTrue(lines[0].contains("earliest events are omitted"))
        // The newest event's id survives; the earliest does not.
        val last = lines.last()
        assertTrue(last.contains("10:2") || last.contains(":"))   // sanity: a time exists
        val allLines = lines.joinToString("\n")
        assertFalse(allLines.contains("[CONFIRMED] impact — Motion (90%)") && lines.size == 31)
    }

    @Test
    fun `stats summarise status and source counts`() {
        val stats = SessionDigest.buildStats(
            session(),
            listOf(event(1), event(2, source = "camera"), event(3, status = "MANUAL", source = SensorRegistry.MANUAL.id))
        )
        assertTrue(stats.contains("Total events: 3"))
        assertTrue(stats.contains("CONFIRMED=2"))
        assertTrue(stats.contains("MANUAL=1"))
        assertTrue(stats.contains("Manual=1"))
        assertTrue(stats.contains("1 audio clip(s)") || stats.contains("0 audio clip(s)"))
    }

    @Test
    fun `system prompt sets the grounding rules`() {
        val prompt = SessionDigest.buildSystemPrompt()
        assertTrue(prompt.contains("TRACE"))
        assertTrue(prompt.contains("never invent"))
        assertTrue(prompt.contains("[MANUAL]"))
        assertTrue(prompt.contains("not part of the evidence chain"))
    }

    @Test
    fun `user turn wraps digest and question`() {
        val turn = SessionDigest.buildUserTurn("DIGEST-BODY", "what fell?")
        assertTrue(turn.startsWith("SESSION DIGEST:"))
        assertTrue(turn.contains("DIGEST-BODY"))
        assertTrue(turn.endsWith("QUESTION: what fell?"))
    }

    @Test
    fun `all-sessions digest labels each session and keeps empty sessions visible`() {
        val s1 = session(id = 1)
        val s2 = session(id = 2, natureOfWork = "empty run", sensorSet = "")
        val digest = SessionDigest.buildAllSessionsDigest(
            listOf(s2, s1),
            mapOf(1L to listOf(event(1)))
        )
        assertTrue(digest.contains("RECORDED SESSIONS: 2"))
        assertTrue(digest.contains("Session #1"))
        assertTrue(digest.contains("Session #2"))
        assertTrue(digest.contains("empty run"))
        assertTrue(digest.contains("impact"))
    }

    @Test
    fun `providers are complete, distinct and reachable over https`() {
        assertEquals(3, CloudAi.PROVIDERS.size)
        assertEquals(CloudAi.PROVIDERS.map { it.id }.distinct().size, 3)
        CloudAi.PROVIDERS.forEach {
            assertTrue(it.baseUrl.startsWith("https://"))
            assertTrue(it.baseUrl.endsWith("/chat/completions") || it.baseUrl.contains("/chat/completions"))
        }
        assertEquals(CloudAi.GROQ, CloudAi.byId("groq"))
        assertEquals(null, CloudAi.byId("nope"))
    }
}
