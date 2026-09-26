package com.example.trace.capture

/**
 * Measures per-second rates for diagnostic display.
 *
 * Tracks ticks over a sliding 2-second window so the reported rate
 * converges quickly when a sensor starts or stops.
 */
class RateMeter {

    private val ticks = LongArray(WINDOW_SIZE)
    private var index = 0
    private var filled = 0

    /** Record one tick at monotonic time [tMs]. */
    @Synchronized
    fun tick(tMs: Long) {
        ticks[index] = tMs
        index = (index + 1) % WINDOW_SIZE
        if (filled < WINDOW_SIZE) filled++
    }

    /** Hz computed from ticks in the last second before [nowMs]. */
    @Synchronized
    fun ratePerSecond(nowMs: Long): Float {
        if (filled < 2) return 0f
        val window = nowMs - 1_000L
        var count = 0
        for (i in 0 until filled) {
            if (ticks[i] >= window) count++
        }
        return count.toFloat()
    }

    companion object {
        private const val WINDOW_SIZE = 256
    }
}