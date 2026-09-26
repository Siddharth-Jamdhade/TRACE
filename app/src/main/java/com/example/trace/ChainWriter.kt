package com.example.trace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TRACE — the single serialised writer for the tamper-evidence hash chains.
 *
 * Every event that enters a session MUST be stored through [append].
 *
 * Why this exists
 * ---------------
 * The chain is only valid if each event links to the hash of the event
 * immediately before it. The original implementation read the tip and
 * inserted in two separate steps, so two sensors firing within the same few
 * milliseconds — the exact multi-sensor scenario TRACE exists for — could
 * both read the same tip and fork the chain. [HashChain.verifyChain] then
 * reported the whole timeline as INVALID.
 *
 * [mutex] serialises the read-tip → insert → fuse → hash → update sequence,
 * so concurrent events queue up and each one links to its true predecessor.
 *
 * Scope of the lock: database work only. Callers must keep expensive work
 * (audio clip extraction, file IO, notifications, UI) outside [append], or
 * they will serialise the whole capture pipeline behind one another.
 */
object ChainWriter {

    private val mutex = Mutex()

    /**
     * Inserts [event] into [session]'s chain and links it to that chain's tip.
     *
     * The event's [Event.sessionId] is set here, so callers cannot accidentally
     * file evidence under the wrong session. The first event of a session links
     * to [Session.sessionHash]; every later event links to the previous event of
     * the same session. Chains therefore never cross sessions, and damaging one
     * session cannot invalidate another.
     *
     * Unlike the legacy version, there is no enrichment step — each event
     * stands alone as a self-contained deviation entry with no fusion or
     * status labeling.
     *
     * @return the stored event, with [Event.previousHash] and [Event.hash] set.
     */
    suspend fun append(
        dao: EventDao,
        session: Session,
        event: Event,
    ): Event = mutex.withLock {
        // Read the tip and write the new link while holding the lock, so no
        // other event can be inserted in between.
        val previousHash = dao.getChainTipForSession(session.id)?.hash ?: session.sessionHash

        val inserted = event.copy(sessionId = session.id)
        val stored = inserted.copy(id = dao.insert(inserted))
        val finalEvent = stored.copy(
            hash = HashChain.computeHash(stored, previousHash),
            previousHash = previousHash
        )

        dao.update(finalEvent)
        finalEvent
    }
}
