package com.example.trace

import java.security.MessageDigest

/**
 * TRACE — SHA-256 Hash Chain
 *
 * Provides tamper-evidence for the event timeline. Each event's hash is
 * computed from its core immutable fields plus the previous event's hash.
 *
 * NOTE: [Event.status] is intentionally excluded so that human
 * Confirm / Reject reviews do not invalidate the chain.
 */
object HashChain {

    /** First event in the chain uses this as its "previous hash". */
    const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

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

    /**
     * Verifies the full chain in append order.
     *
     * Ordering is by [Event.id] (insertion order), NOT timestamp. The chain
     * links to whichever event was appended immediately before it, and an
     * imported video can legitimately contribute an event whose timestamp is
     * older than events already stored.
     *
     * Returns false as soon as any hash does not match.
     */
    fun verifyChain(events: List<Event>): Boolean {
        if (events.isEmpty()) return true
        var prevHash = GENESIS_HASH
        for (event in events.sortedBy { it.id }) {
            val expected = computeHash(event, prevHash)
            if (event.hash != expected) return false
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
