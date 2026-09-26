package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the extended sensor pipeline: EventExtractor labels for the
 * new sensor sources, RollingBaseline adaptive behaviour, and FusionEngine's
 * multi-sensor breakdown. All run on the JVM — no Android framework needed.
 */
class SensorPipelineTest {

    // ---- EventExtractor: new sensor sources ------------------------------

    @Test
    fun `magnetometer large deviation classifies as door_slam`() {
        assertEquals("door_slam", EventExtractor.extract(Observation("magnetometer", 0.85f, 0L)))
    }

    @Test
    fun `magnetometer medium deviation classifies as door_swing`() {
        assertEquals("door_swing", EventExtractor.extract(Observation("magnetometer", 0.50f, 0L)))
    }

    @Test
    fun `magnetometer small deviation classifies as metal_moves`() {
        assertEquals("metal_moves", EventExtractor.extract(Observation("magnetometer", 0.30f, 0L)))
    }

    @Test
    fun `magnetometer tiny deviation is discarded`() {
        assertNull(EventExtractor.extract(Observation("magnetometer", 0.10f, 0L)))
    }

    @Test
    fun `barometer above threshold yields pressure_shift`() {
        assertEquals("pressure_shift", EventExtractor.extract(Observation("barometer", 0.30f, 0L)))
        assertNull(EventExtractor.extract(Observation("barometer", 0.10f, 0L)))
    }

    @Test
    fun `light above threshold yields lights_off`() {
        assertEquals("lights_off", EventExtractor.extract(Observation("light", 0.80f, 0L)))
        assertNull(EventExtractor.extract(Observation("light", 0.50f, 0L)))
    }

    @Test
    fun `linear collapse yields object_falls and device_falls`() {
        assertEquals("object_falls", EventExtractor.extract(Observation("linear", 0.80f, 0L)))
        assertEquals("device_falls", EventExtractor.extract(Observation("linear", 0.60f, 0L)))
        assertNull(EventExtractor.extract(Observation("linear", 0.30f, 0L)))
    }

    @Test
    fun `gyroscope fast spin yields device_falls`() {
        assertEquals("device_falls", EventExtractor.extract(Observation("gyroscope", 0.80f, 0L)))
        assertEquals("device_motion", EventExtractor.extract(Observation("gyroscope", 0.40f, 0L)))
        assertNull(EventExtractor.extract(Observation("gyroscope", 0.20f, 0L)))
    }

    @Test
    fun `step and sigmotion yield person_present`() {
        assertEquals("person_present", EventExtractor.extract(Observation("step", 0.5f, 0L)))
        assertEquals("person_present", EventExtractor.extract(Observation("sigmotion", 0.5f, 0L)))
    }

    @Test
    fun `legacy sources unchanged`() {
        assertEquals("impact", EventExtractor.extract(Observation("motion", 0.8f, 0L)))
        assertEquals("alarm", EventExtractor.extract(Observation("audio", 0.9f, 0L)))
        assertEquals("object_falls", EventExtractor.extract(Observation("camera", 0.9f, 0L)))
    }

    // ---- RollingBaseline ---------------------------------------------------

    @Test
    fun `baseline converges and deviation shrinks for constant input`() {
        val rb = RollingBaseline()
        var lastDelta = 0f
        for (i in 1..500) {
            lastDelta = rb.feed("barometer", 1013.25f)
        }
        // Constant readings must converge: deviation trends toward zero.
        assertTrue("deviation should shrink, got $lastDelta", lastDelta < 0.1f)
        assertEquals(1013.25f, rb.get("barometer"), 0.05f)
    }

    @Test
    fun `first barometer reading does not fake a monster spike`() {
        val rb = RollingBaseline()
        val delta = rb.feed("barometer", 1014.0f)
        // Against the 1013.25 default, a 0.75 hPa first sample is a small delta.
        assertTrue("first delta should be small, got $delta", delta < 1.0f)
    }

    @Test
    fun `magnetometer spike produces large deviation`() {
        val rb = RollingBaseline()
        // Settle the baseline around 48 µT.
        repeat(200) { rb.feed("magnetometer", 48f) }
        // A steel door slams: field jumps by 25 µT.
        val delta = rb.feed("magnetometer", 73f)
        assertTrue("spike delta should be large, got $delta", delta > 20f)
    }

    @Test
    fun `resetAll clears baselines`() {
        val rb = RollingBaseline()
        repeat(100) { rb.feed("light", 500f) }
        rb.resetAll()
        assertEquals(200f, rb.get("light"), 0.01f)
    }

    // ---- FusionEngine: multi-sensor breakdown -----------------------------

    @Test
    fun `sensorBreakdown records extra sensors in window`() {
        val base = Event(type = "door_slam", timestamp = 1000L, source = "magnetometer", confidence = 0.8f, id = 1)
        val magInWindow = base
        val baroInWindow = Event(type = "pressure_shift", timestamp = 1100L, source = "barometer", confidence = 0.35f, id = 2)

        val enriched = FusionEngine.buildEnrichedEvent(base, listOf(magInWindow, baroInWindow))

        assertNotNull(enriched.sensorBreakdown)
        assertTrue(
            "breakdown should include barometer, got: ${enriched.sensorBreakdown}",
            enriched.sensorBreakdown!!.contains("barometer:0.35")
        )
    }

    @Test
    fun `camera event with magnetometer corroboration shows breakdown`() {
        val base = Event(type = "object_moves", timestamp = 1000L, source = "camera", confidence = 0.7f, id = 1)
        val mag = Event(type = "metal_moves", timestamp = 1050L, source = "magnetometer", confidence = 0.3f, id = 2)

        val enriched = FusionEngine.buildEnrichedEvent(base, listOf(mag))

        assertTrue(
            "breakdown should include magnetometer, got: ${enriched.sensorBreakdown}",
            enriched.sensorBreakdown?.contains("magnetometer") == true
        )
        // Only one strong source → UNCONFIRMED.
        assertEquals("UNCONFIRMED", enriched.status)
    }

    @Test
    fun `magnetometer plus accelerometer confirmation works end to end`() {
        // Slam a steel door next to the phone: magnetometer spikes, and the
        // door's impact shakes the phone the accelerometer sees it too.
        val mag = Event(type = "door_slam", timestamp = 1000L, source = "magnetometer", confidence = 0.8f, id = 1)
        val accel = Event(type = "impact", timestamp = 1050L, source = "motion", confidence = 0.7f, id = 2)

        val enriched = FusionEngine.buildEnrichedEvent(accel, listOf(mag, accel))

        assertEquals("CONFIRMED", enriched.status)
        assertNotNull(enriched.sensorBreakdown)
        assertTrue(enriched.sensorBreakdown!!.contains("magnetometer"))
    }

    @Test
    fun `breakdown omits when only legacy sensors present`() {
        val base = Event(type = "impact", timestamp = 1000L, source = "motion", confidence = 0.7f, id = 1)
        val enriched = FusionEngine.buildEnrichedEvent(base, emptyList())
        // No extra sensors → breakdown stays null (no legacy churn).
        assertFalse(enriched.sensorBreakdown != null && enriched.sensorBreakdown!!.isNotEmpty())
    }

    // ---- SensorRegistry -----------------------------------------------------

    @Test
    fun `registry icons resolve for every source`() {
        for (spec in SensorRegistry.ALL) {
            assertEquals(spec.icon, SensorRegistry.iconFor(spec.id))
        }
        assertEquals("❓", SensorRegistry.iconFor("unknown_sensor"))
    }
}
