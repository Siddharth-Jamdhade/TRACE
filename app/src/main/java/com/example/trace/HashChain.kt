package com.example.trace

import java.security.MessageDigest

/**
 * TRACE — SHA-256 Hash Chain
 *
 * Provides tamper-evidence for the event timeline. Each event's hash is
 * computed from its core immutable fields plus the previous event's hash.
 *
 * Chains are scoped per [Session]: the first event of a session links to
 * [Session.sessionHash] rather than to the global genesis block, so sessions
 * cannot invalidate one another.
 *
 * NOTE: [Event.status] is intentionally excluded so that human
 * Confirm / Reject reviews do not invalidate the chain. Likewise the
 * closing state and counters of a [Session] are excluded.
 */
object HashChain {

    /** Genesis block, used only by the pre-session (v3) timeline. */
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    /** Domain separator so a session header can never collide with an event payload. */
    private const val SESSION_HASH_PREFIX = "trace-session-v1|"

    /** Returns a lowercase hex SHA-256 digest of [input]. */
    fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Computes the chain hash for [event], chaining it to [previousHash].
     * Fields included: id, type, timestamp, source, confidence, previousHash.
     * Fields excluded: status (mutable by design), evidenceClipPath, hash itself.
     */
    fun computeHash(event: Event, previousHash: String): String {
        val payload = buildString {
            append(event.id);         append('|')
            append(event.type);       append('|')
            append(event.timestamp);  append('|')
            append(event.source);     append('|')
            append(event.confidence); append('|')
            append(previousHash)
        }
        return sha256(payload)
    }

    // ── Session headers ───────────────────────────────────────────────────

    /**
     * Hash over the immutable header of a session. Every event hash in that
     * session chains back to this value, which is what binds a session's
     * description, start time and sensor set to its own evidence.
     */
    fun computeSessionHash(
        id: Long,
        natureOfWork: String,
        startedAt: Long,
        sensorSet: String
    ): String {
        val payload = buildString {
            append(SESSION_HASH_PREFIX)
            append(id);           append('|')
            append(natureOfWork); append('|')
            append(startedAt);    append('|')
            append(sensorSet)
        }
        return sha256(payload)
    }

    /** [computeSessionHash] over the hashed fields of [session]. */
    fun computeSessionHash(session: Session): String =
        computeSessionHash(session.id, session.natureOfWork, session.startedAt, session.sensorSet)

    /**
     * Verifies one session's slice of the timeline.
     *
     * Three things must hold:
     *   1. the session header still hashes to the stored [Session.sessionHash],
     *   2. the session's first event links to that header, and
     *   3. every following event links to the event appended before it.
     *
     * Ordering is by [Event.id] (append order), NOT timestamp: a chain links to
     * whichever event was appended immediately before it, preserving append
     * order regardless of event timestamps.
     *
     * Events belonging to other sessions are ignored, so a broken session can
     * never make a healthy one look invalid. An empty session verifies true; a
     * session whose events are all missing fails on the anchor check.
     */
    fun verifySession(session: Session, events: List<Event>): Boolean {
        // The synthetic pre-session timeline has no header of its own.
        if (!session.legacy && session.sessionHash != computeSessionHash(session)) return false

        val ordered = events.filter { it.sessionId == session.id }.sortedBy { it.id }
        if (ordered.isEmpty()) return true

        // The first event of a session must anchor on the session header.
        if (ordered.first().previousHash != session.sessionHash) return false

        var prevHash = session.sessionHash
        for (event in ordered) {
            if (event.hash != computeHash(event, prevHash)) return false
            prevHash = event.hash ?: return false
        }
        return true
    }

    /** Verifies a single event against its own stored previousHash. */
    fun verifySingle(event: Event): Boolean {
        val prev = event.previousHash ?: GENESIS_HASH
        return event.hash != null && event.hash == computeHash(event, prev)
    }
}
