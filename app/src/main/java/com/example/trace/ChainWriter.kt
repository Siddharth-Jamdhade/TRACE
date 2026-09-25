package com.example.trace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TRACE — the single serialised writer for the tamper-evidence hash chain.
 *
 * Every event that enters the timeline MUST be stored through [append].
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
     * Inserts [event] and links it to the current chain tip.
     *
     * @param enrich maps the freshly inserted event (whose `id` has now been
     *   assigned) into the row to store — normally
     *   [FusionEngine.buildEnrichedEvent] over a fusion window that includes
     *   the new event. It runs inside the lock, so it may query the database
     *   but must not do file or network IO.
     * @return the stored event, with [Event.previousHash] and [Event.hash] set.
     */
    suspend fun append(
        dao: EventDao,
        event: Event,
        enrich: suspend (Event) -> Event = { it }
    ): Event = mutex.withLock {
        // Read the tip and write the new link while holding the lock, so no
        // other event can be inserted in between.
        val previousHash = dao.getChainTip()?.hash ?: HashChain.GENESIS_HASH

        val inserted = event.copy(id = dao.insert(event))
        val enriched = enrich(inserted)
        val finalEvent = enriched.copy(
            hash = HashChain.computeHash(enriched, previousHash),
            previousHash = previousHash
        )

        dao.update(finalEvent)
        finalEvent
    }
}
