package com.example.trace

/**
 * TRACE — Evidence Fusion Engine
 *
 * Fuses observations from independent sensors within a time window to
 * decide whether an incident is CONFIRMED, UNCONFIRMED, or REJECTED.
 *
 * Rule (tunable):
 *   >= 2 independent sensor sources with confidence > 60% -> CONFIRMED
 *   == 1 source with confidence > 60%                     -> UNCONFIRMED
 *   zero sources above threshold                           -> REJECTED
 *
 * [sensorBreakdown] records every contributing sensor in the window, so the
 * incident-reconstruction view can show the full multi-sensor picture even
 * for the three legacy sensors that keep dedicated columns.
 *
 * Human assertions are excluded from all of the above — see [HUMAN_SOURCES].
 */
object FusionEngine {

    /** Minimum per-sensor confidence to count as "strong" evidence. */
    private const val HIGH_CONFIDENCE = 0.60f

    /** Events within this window (ms) are treated as the same incident. */
    const val FUSION_WINDOW_MS = 2000L

    /**
     * Sources that record what a person said, rather than what a sensor measured.
     *
     * A manual tag must never be able to manufacture a verdict. If it counted as
     * a "strong source", one operator tap next to one motion event would look
     * like two independent sensors agreeing — which is precisely the claim the
     * fusion rule exists to make, and it would be a lie: both "sources" would be
     * the same person standing in the same room.
     *
     * Tags are therefore left out of the source count AND out of
     * [Event.sensorBreakdown], so no reconstruction view can present an
     * assertion as a measurement. The tag still gets its own record (see
     * [Event.status] = MANUAL) and still joins the session's hash chain.
     */
    private val HUMAN_SOURCES = setOf(SensorRegistry.MANUAL.id)

    /** True when [event] is a human assertion rather than a sensor measurement. */
    fun isHumanAsserted(event: Event): Boolean = event.source in HUMAN_SOURCES

    /**
     * Determines the fused status for a set of events that belong to
     * the same incident window.
     */
    fun determineStatus(events: List<Event>): String {
        if (events.isEmpty()) return "REJECTED"

        // Count distinct sensor sources that had at least one high-confidence event
        val strongSourceCount = events
            .filterNot { isHumanAsserted(it) }
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
     * - [Event.sensorBreakdown] listing every additional sensor in the window
     * - a fused [Event.status] based on all evidence in the window
     */
    fun buildEnrichedEvent(baseEvent: Event, windowEvents: List<Event>): Event {
        // A human assertion is not a measurement, so it is never fused: it keeps
        // its MANUAL status and carries no sensor confidences of its own. Nearby
        // sensor events stand on their own record instead.
        if (isHumanAsserted(baseEvent)) return baseEvent

        // Combine all evidence, deduplicating by ID (base may already be in window)
        val allEvidence = (windowEvents + baseEvent).distinctBy { it.id }

        val cameraConf = allEvidence.filter { it.source == "camera" }.maxOfOrNull { it.confidence }
        val audioConf  = allEvidence.filter { it.source == "audio"  }.maxOfOrNull { it.confidence }
        val motionConf = allEvidence.filter { it.source == "motion" }.maxOfOrNull { it.confidence }

        // Extra sensors (magnetometer, barometer, light, linear, gyroscope,
        // step, sigmotion) are recorded as "sensor:confidence" pairs so no
        // evidence is lost in the reconstruction view. Human tags are filtered
        // out: this line is the sensor picture, and a tag is not a sensor.
        val breakdown = allEvidence
            .filterNot { it.source in setOf("camera", "audio", "motion") || isHumanAsserted(it) }
            .groupBy { it.source }
            .map { (source, events) -> "$source:${"%.2f".format(events.maxOf { it.confidence })}" }
            .filter { it.substringAfter(':') != "0.00" || it.substringBefore(':') == baseEvent.source }
            .joinToString("|")

        val fusedStatus = determineStatus(allEvidence)

        return baseEvent.copy(
            cameraConfidence = cameraConf,
            audioConfidence  = audioConf,
            motionConfidence = motionConf,
            sensorBreakdown  = breakdown.ifEmpty { null },
            status           = fusedStatus
        )
    }
}
