package com.example.trace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for manual incident tags — the one kind of evidence a person, rather than
 * a sensor, puts into the timeline.
 *
 * The property these lock down: a tag is evidence, but it is not a measurement.
 * It must never be able to make the fusion engine claim that independent sensors
 * agreed, because both "sources" would be the same person standing in the same
 * room.
 */
class ManualTagTest {

    private val session = testSession(id = 1)

    private fun sensor(source: String, confidence: Float, offsetMs: Long = 0L) = Event(
        type = "impact",
        timestamp = 1_700_000_000_000L + offsetMs,
        source = source,
        confidence = confidence
    )

    private fun tag(offsetMs: Long = 0L) = Event(
        type = "object_falls",
        timestamp = 1_700_000_000_000L + offsetMs,
        source = SensorRegistry.MANUAL.id,
        confidence = 1f,
        status = "MANUAL"
    )

    @Test
    fun manualIsRegisteredButIsNotASensor() {
        assertEquals("manual tags must render with their own icon", "✋", SensorRegistry.iconFor("manual"))
        assertFalse(
            "manual must never be part of the sensor set a session captures from",
            SensorRegistry.ALL.any { it.id == SensorRegistry.MANUAL.id }
        )
        assertTrue(
            "manual is a source, just not a sensor",
            SensorRegistry.NON_SENSOR.any { it.id == SensorRegistry.MANUAL.id }
        )
    }

    @Test
    fun aTagAloneIsNotAVerdict() {
        assertTrue(FusionEngine.isHumanAsserted(tag()))
        assertFalse(FusionEngine.isHumanAsserted(sensor("motion", 0.9f)))

        assertEquals(
            "a tag with no sensor evidence is not an incident the sensors confirmed",
            "REJECTED",
            FusionEngine.determineStatus(listOf(tag()))
        )
    }

    @Test
    fun aTagLeavesTheFusionVerdictUnchanged() {
        val oneSource  = listOf(sensor("motion", 0.9f))
        val twoSources = listOf(sensor("camera", 0.9f), sensor("audio", 0.9f))
        val weakOnly   = listOf(sensor("light", 0.1f))

        listOf(oneSource, twoSources, weakOnly).forEach { window ->
            assertEquals(
                "a tag must not change what the sensors concluded",
                FusionEngine.determineStatus(window),
                FusionEngine.determineStatus(window + tag())
            )
        }

        // ... and the underlying verdicts are still what they were.
        assertEquals("UNCONFIRMED", FusionEngine.determineStatus(oneSource + tag()))
        assertEquals("CONFIRMED",   FusionEngine.determineStatus(twoSources + tag()))
        assertEquals("REJECTED",    FusionEngine.determineStatus(weakOnly + tag()))
    }

    @Test
    fun aTagNeverAppearsInASensorBreakdown() {
        val base = sensor("motion", 0.9f)
        val enriched = FusionEngine.buildEnrichedEvent(base, listOf(base, tag()))

        assertFalse(
            "a reconstruction view must not render an assertion as a sensor reading",
            enriched.sensorBreakdown?.contains(SensorRegistry.MANUAL.id) == true
        )
    }

    @Test
    fun aTagIsNeverEnriched() {
        val asserted = tag()
        val enriched = FusionEngine.buildEnrichedEvent(
            asserted,
            listOf(asserted, sensor("camera", 0.95f), sensor("audio", 0.95f))
        )

        assertEquals("a tag is not fused, even with two sensors beside it", asserted, enriched)
        assertEquals("MANUAL", enriched.status)
        assertNull(enriched.cameraConfidence)
        assertNull(enriched.sensorBreakdown)
    }

    @Test
    fun aTagJoinsTheSessionChainInCustodyOrder() {
        runBlocking {
            val dao = FakeEventDao()

            val sensed = ChainWriter.append(dao, session, sensor("motion", 0.9f)) { inserted ->
                FusionEngine.buildEnrichedEvent(inserted, listOf(inserted))
            }
            // The tag arrives a second later, from a person instead of a sensor.
            val stored = ChainWriter.append(dao, session, tag(1_000L))

            assertEquals("the tag must link to the current tip", sensed.hash, stored.previousHash)
            assertEquals(session.id, stored.sessionId)
            assertEquals(
                "the tag path applies no fusion, so the assertion keeps its own status",
                "MANUAL",
                stored.status
            )
            assertTrue(
                "a human assertion is chain evidence like any other",
                HashChain.verifySession(session, dao.getEventsForSession(session.id))
            )
        }
    }
}
