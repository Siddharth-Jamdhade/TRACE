package com.example.trace

/**
 * TRACE — Evidence Fusion Engine
 *
 * Fuses observations from independent sensors within a time window to
 * decide whether an incident is CONFIRMED, UNCONFIRMED, or REJECTED.
 *
 * Rule (tunable):
 *   >= 2 independent sensor sources with confidence > 60% -> CONFIRMED
 *   == 1 source with confidence > 60%                    -> UNCONFIRMED
 *   zero sources above threshold                          -> REJECTED
 */
object FusionEngine {

    /** Minimum per-sensor confidence to count as "strong" evidence. */
    private const val HIGH_CONFIDENCE = 0.60f

    /** Events within this window (ms) are treated as the same incident. */
    const val FUSION_WINDOW_MS = 2000L

    /**
     * Determines the fused status for a set of events that belong to
     * the same incident window.
     */
    fun determineStatus(events: List<Event>): String {
        if (events.isEmpty()) return "REJECTED"

        // Count distinct sensor sources that had at least one high-confidence event
        val strongSourceCount = events
            .groupBy { it.source }
            .count { (_, sourceEvents) -> sourceEvents.any { it.confidence > HIGH_CONFIDENCE } }

        return when {
            strongSourceCount >= 2 -> "CONFIRMED"
            strongSourceCount == 1 -> "UNCONFIRMED"
            else                   -> "REJECTED"
        }
    }

    /**
     * Returns an enriched copy of [baseEvent] with:
     * - per-sensor confidence values filled from [windowEvents]
     * - a fused [Event.status] based on all evidence in the window
     */
    fun buildEnrichedEvent(baseEvent: Event, windowEvents: List<Event>): Event {
        // Combine all evidence, deduplicating by ID (base may already be in window)
        val allEvidence = (windowEvents + baseEvent).distinctBy { it.id }

        val cameraConf = allEvidence.filter { it.source == "camera" }.maxOfOrNull { it.confidence }
        val audioConf  = allEvidence.filter { it.source == "audio"  }.maxOfOrNull { it.confidence }
        val motionConf = allEvidence.filter { it.source == "motion" }.maxOfOrNull { it.confidence }

        val fusedStatus = determineStatus(allEvidence)

        return baseEvent.copy(
            cameraConfidence = cameraConf,
            audioConfidence  = audioConf,
            motionConfidence = motionConf,
            status           = fusedStatus
        )
    }
}
