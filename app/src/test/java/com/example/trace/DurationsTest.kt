package com.example.trace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the duration format used by the session list, review screen and live
 * view: mm:ss under an hour, h:mm:ss past it, negatives clamped.
 */
class DurationsTest {

    @Test
    fun `zero is 00-00`() = assertEquals("00:00", Durations.format(0))

    @Test
    fun `under a minute keeps leading zero`() = assertEquals("00:09", Durations.format(9_000))

    @Test
    fun `exactly a minute rolls over`() = assertEquals("01:00", Durations.format(60_000))

    @Test
    fun `just under an hour has no hour part`() = assertEquals("59:59", Durations.format(3_599_000))

    @Test
    fun `an hour switches to h-mm-ss`() = assertEquals("1:00:00", Durations.format(3_600_000))

    @Test
    fun `hours do not pad`() = assertEquals("12:05:07", Durations.format((12 * 3600 + 5 * 60 + 7) * 1000L))

    @Test
    fun `negative input clamps to zero`() = assertEquals("00:00", Durations.format(-5_000))
}
