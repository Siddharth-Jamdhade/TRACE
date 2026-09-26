package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the arming sheet's sensor suggestion.
 *
 * The property that matters is the failure mode: an unrecognised description must
 * capture **everything**. Under-capturing destroys evidence that cannot be
 * recovered, while over-capturing only costs storage.
 */
class SensorSuggesterTest {

    private val allSensors = SensorRegistry.ALL.map { it.id }.toSet()

    @Test
    fun anUnrecognisedDescriptionCapturesEverything() {
        for (phrase in listOf("", "   ", "inspection", "site visit", "stuff", "level 2")) {
            val suggestion = SensorSuggester.suggest(phrase)
            assertFalse("'$phrase' must not be treated as understood", suggestion.confident)
            assertEquals(
                "'$phrase' must fall back to every sensor",
                allSensors,
                suggestion.sensorIds.toSet()
            )
        }
    }

    @Test
    fun aDoorDescriptionSelectsDoorEvidence() {
        val suggestion = SensorSuggester.suggest("Door inspection")

        assertTrue(suggestion.confident)
        // Steel hinges and frames distort the local field; a swinging door pulses
        // the room pressure and makes noise.
        assertTrue("magnetometer" in suggestion.sensorIds)
        assertTrue("barometer" in suggestion.sensorIds)
        assertTrue("audio" in suggestion.sensorIds)
        assertFalse("a door phrase must not silently pull in everything", "step" in suggestion.sensorIds)
        assertEquals(listOf("door"), suggestion.matched)
    }

    @Test
    fun matchingIsCaseInsensitiveAndMatchesWordVariants() {
        assertEquals(
            SensorSuggester.suggest("DOOR").sensorIds,
            SensorSuggester.suggest("doors and gateways").sensorIds
        )
        assertTrue(SensorSuggester.suggest("unloading a truck").confident)
    }

    @Test
    fun overlappingDescriptionsUnionTheirEvidence() {
        val doors = SensorSuggester.suggest("door").sensorIds.toSet()
        val machines = SensorSuggester.suggest("machine").sensorIds.toSet()
        val both = SensorSuggester.suggest("machine room door").sensorIds.toSet()

        assertTrue(both.containsAll(doors))
        assertTrue(both.containsAll(machines))
        assertEquals("matched keywords must all be reported", 2, SensorSuggester.suggest("machine room door").matched.size)
    }

    @Test
    fun suggestionsStayInRegistryOrder() {
        // The sheet writes these straight into chips; a reordering selection would
        // make the toggles jump under the user's finger as they type.
        val suggestion = SensorSuggester.suggest("door shelf machine lights")
        val expectedOrder = SensorRegistry.ALL.map { it.id }.filter { it in suggestion.sensorIds }
        assertEquals(expectedOrder, suggestion.sensorIds)
    }

    @Test
    fun suggestionsOnlyEverNameRealCaptureSensors() {
        val phrases = listOf("door", "shelf", "person", "machine", "lights", "fall", "truck", "nonsense")
        for (phrase in phrases) {
            val ids = SensorSuggester.suggest(phrase).sensorIds
            assertTrue("'$phrase' suggested nothing", ids.isNotEmpty())
            assertTrue(
                "'$phrase' suggested a non-sensor: $ids",
                allSensors.containsAll(ids)
            )
            assertFalse(
                "software sources must never be offered as capture sensors",
                ids.any { it == SensorRegistry.MANUAL.id }
            )
        }
    }

    @Test
    fun theSensorSetStringIsOrderIndependentAndRoundTrips() {
        // The set is inside the hashed session header: the same selection must
        // always serialise to the same string, whatever order it arrives in.
        assertEquals(
            SensorRegistry.sensorSetOf(listOf("audio", "motion", "camera")),
            SensorRegistry.sensorSetOf(listOf("camera", "audio", "motion"))
        )
        assertEquals("camera|audio|motion", SensorRegistry.sensorSetOf(listOf("audio", "motion", "camera")))

        // Registry order, not the order the sheet happened to hand them over in.
        val session = testSession(id = 1, sensorSet = SensorRegistry.sensorSetOf(listOf("motion", "camera")))
        assertEquals(listOf("camera", "motion"), session.sensorIds)
        assertEquals(session.sessionHash, HashChain.computeSessionHash(session))
    }

    @Test
    fun unknownSensorIdsAreDroppedFromTheSet() {
        assertEquals("", SensorRegistry.sensorSetOf(listOf("teleporter")))
        assertEquals("motion", SensorRegistry.sensorSetOf(listOf("teleporter", "motion")))
    }
}
