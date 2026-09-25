package com.example.trace

/**
 * TRACE — Rolling Baseline
 *
 * Environment sensors (magnetic field, air pressure, ambient light) are only
 * meaningful relative to the *local* conditions at recording time: the Earth's
 * magnetic field differs by hemisphere and building steelwork, barometric
 * readings drift with weather, and light levels range from a dark closet to
 * direct sunlight. Absolute thresholds are therefore useless.
 *
 * This class maintains a per-sensor exponentially-weighted moving average
 * (EWMA) that adapts to the ambient level over ~30 s so that detectors can
 * fire on *deviations* from the environment rather than fixed values.
 *
 * Thread-safety: synchronized — environment listeners run on the main thread
 * only, but the guard costs nothing and makes the class safe to share.
 *
 * A deliberate default is supplied per sensor so the very first reading never
 * produces a huge fake delta (e.g. raw barometer ≈ 1013 hPa vs a naive
 * default of 0 would look like a monster pressure event).
 */
class RollingBaseline {

    companion object {
        /** ~30 s warm-up at 50 Hz equivalent: alpha=0.05. */
        private const val ALPHA = 0.05f

        /** Neutral starting points so the first sample can't fake a spike. */
        private val DEFAULTS = mapOf(
            "magnetometer" to 48f,    // typical indoor Earth-field magnitude (µT)
            "barometer"    to 1013.25f, // standard sea-level pressure (hPa)
            "light"        to 200f    // ordinary indoor lighting (lux)
        )
    }

    private val baselines = HashMap<String, Float>()

    /**
     * Feeds a fresh sample and returns the absolute deviation from the
     * baseline BEFORE the sample was merged (i.e. the delta the detector
     * should evaluate).
     */
    @Synchronized
    fun feed(sensor: String, value: Float): Float {
        val baseline = baselines.getOrDefault(sensor, DEFAULTS[sensor] ?: value)
        val delta = kotlin.math.abs(value - baseline)
        baselines[sensor] = baseline + ALPHA * (value - baseline)
        return delta
    }

    /** Current smoothed baseline for [sensor]. */
    @Synchronized
    fun get(sensor: String): Float =
        baselines.getOrDefault(sensor, DEFAULTS[sensor] ?: 0f)

    /** Clears everything (used when recording restarts). */
    @Synchronized
    fun resetAll() = baselines.clear()
}
