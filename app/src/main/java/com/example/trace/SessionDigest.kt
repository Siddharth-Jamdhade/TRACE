package com.example.trace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE — session digest for Cloud AI chat.
 *
 * Builds the compact, factual context the model answers from: the session
 * header and every event line (chronological, with status, source and
 * confidence), plus counts. Nothing is summarised by inference — the digest is
 * a deterministic rendering of database rows, so the same session always
 * produces the same prompt.
 *
 * Deliberately excluded: hash-chain fields (meaningless to a language model,
 * and they would double the prompt size for zero grounding value).
 *
 * Pure functions on (Session, List<Event>) so they are unit-testable and the
 * token cost of a chat is predictable.
 */
object SessionDigest {

    /**
     * Upper bound on event lines sent per request.
     *
     * The most recent events win: the tail of a session is where the incident
     * usually is, and the header states the total so the model can say when it
     * is not seeing the whole session.
     */
    const val MAX_EVENT_LINES = 120

    /** Shared human-readable timestamp for exports and headers. */
    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

    fun buildHeader(session: Session): String {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("Session #").append(session.id)
        if (session.natureOfWork.isNotBlank()) sb.append(" — \"").append(session.natureOfWork).append("\"")
        sb.append('\n')
        sb.append("Started: ").append(dateFmt.format(Date(session.startedAt))).append('\n')
        val ended = session.endedAt
        sb.append("State: ").append(session.state)
        if (ended != null) sb.append(" (ended ").append(dateFmt.format(Date(ended))).append(")")
        sb.append('\n')
        if (session.sensorIds.isNotEmpty()) {
            sb.append("Sensors in use: ")
                .append(session.sensorIds.joinToString(", ") { SensorRegistry.labelFor(it) })
                .append('\n')
        }
        return sb.toString()
    }

    /** One line per event, oldest first. MANUAL tags are marked as human claims. */
    fun buildEventLines(events: List<Event>): List<String> {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val ordered = events.sortedBy { it.id }
        val tail = ordered.takeLast(MAX_EVENT_LINES)
        val omitted = ordered.size - tail.size

        val lines = tail.map { e ->
            val t = timeFmt.format(Date(e.timestamp))
            val label = e.type.replace("_", " ")
            val conf = (e.confidence * 100).toInt()
            val extras = buildString {
                if (e.evidenceClipPath != null) append(" [audio]")
                if (e.evidencePhotoPath != null) append(" [photo]")
            }
            if (e.source == SensorRegistry.MANUAL.id) {
                "$t [MANUAL] \"$label\" — asserted by the human operator$extras"
            } else {
                "$t [${e.status}] $label — ${SensorRegistry.labelFor(e.source)} ($conf%)$extras"
            }
        }.toMutableList()

        if (omitted > 0) {
            lines.add(0, "(…the $omitted earliest events are omitted; showing the ${tail.size} most recent…)")
        }
        return lines
    }

    fun buildStats(session: Session, events: List<Event>): String {
        val byStatus = events.groupingBy { it.status }.eachCount()
        val bySource = events.groupingBy { it.source }.eachCount()
        val statusStr = byStatus.entries.joinToString(", ") { "${it.key}=${it.value}" }
            .ifEmpty { "none" }
        val sourceStr = bySource.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${SensorRegistry.labelFor(it.key)}=${it.value}" }
            .ifEmpty { "none" }
        val clips = events.count { it.evidenceClipPath != null }
        val photos = events.count { it.evidencePhotoPath != null }
        return "Total events: ${events.size} (session counter: ${session.eventCount})\n" +
            "By status: $statusStr\n" +
            "By source: $sourceStr\n" +
            "Evidence files: $clips audio clip(s), $photos photo(s)"
    }

    /** The complete digest block: header + stats + event lines. */
    fun buildDigest(session: Session, events: List<Event>): String =
        buildString {
            append(buildHeader(session))
            append('\n')
            append(buildStats(session, events))
            append("\n\nEVENT LOG (chronological):\n")
            buildEventLines(events).forEach { append(it).append('\n') }
        }

    /**
     * Digest over every session (the Timeline's chat covers the whole store).
     * Each session becomes a labelled section; sessions with no events still
     * show their header, so the model can mention a session that recorded
     * nothing. Per-session line caps apply ([MAX_EVENT_LINES]).
     */
    fun buildAllSessionsDigest(
        sessions: List<Session>,
        eventsBySession: Map<Long, List<Event>>
    ): String = buildString {
        append("RECORDED SESSIONS: ").append(sessions.size).append("\n\n")
        sessions.sortedBy { it.id }.forEach { s ->
            append(buildHeader(s))
            append(buildStats(s, eventsBySession[s.id] ?: emptyList()))
            val lines = buildEventLines(eventsBySession[s.id] ?: emptyList())
            if (lines.isNotEmpty()) {
                append("\nEVENT LOG (chronological):\n")
                lines.forEach { append(it).append('\n') }
            }
            append('\n')
        }
    }

    /** System prompt: what the model is, and the rules it must not break. */
    fun buildSystemPrompt(): String = """
        You are the review assistant inside TRACE, a tamper-evident multi-sensor
        incident recorder. You are given a digest of one recorded session and
        answer an investigator's questions about it.

        Rules:
        - Ground every factual claim in the digest. If the digest does not contain
          the answer, say so plainly — never invent events, times or readings.
        - Timestamps are wall-clock times of the device that recorded them.
        - [CONFIRMED] means two or more independent sensors agreed within a
          2-second window; [UNCONFIRMED] means one sensor fired; [REJECTED] means
          the signal was too weak to count; [MANUAL] is something a human on
          scene asserted directly — a claim, not a sensor measurement.
        - The sensors infer THAT something happened; they do not know what it was.
          Use [MANUAL] lines and event labels as the best available description.
        - You are review tooling, not part of the evidence chain. Never present
          your own output as a sensor reading or as evidence.
    """.trimIndent()

    /** Renders the operator's question as the user turn. */
    fun buildUserTurn(digest: String, question: String): String =
        "SESSION DIGEST:\n$digest\n\nQUESTION: ${question.trim()}"
}
