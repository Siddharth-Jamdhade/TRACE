package com.example.trace

import com.example.trace.capture.BaselineTracker

/**
 * Per-sensor adaptive baseline using EWMA with sigma-gated detection.
 *
 * Replaces the original simple-EWMA implementation with full sigma-floor
 * semantics. Each named sensor gets its own [BaselineTracker] configured
 * with appropriate sigma floors for its physical range.
 *
 * The legacy [feed]/[get]/[resetAll] interface is preserved so existing
 * callers in [CaptureService] continue to work unchanged.
 */
class RollingBaseline {

    private val trackers = HashMap<String, BaselineTracker>()

    /**
     * Feeds a sample for [sensor] and returns the absolute deviation from
     * the baseline *before* the sample was merged.
     */
    @Synchronized
    fun feed(sensor: String, value: Float): Float {
        val t = trackers.getOrPut(sensor) { trackerFor(sensor) }
        return t.feed(value)
    }

    /** Current smoothed baseline for [sensor], or a sensible default. */
    @Synchronized
    fun get(sensor: String): Float =
        trackers[sensor]?.baselineMean?.toFloat() ?: DEFAULTS[sensor] ?: 0f

    /** True once the tracker for [sensor] has seen enough samples. */
    @Synchronized
    fun isReady(sensor: String): Boolean =
        trackers[sensor]?.ready ?: false

    /** Deviation sigma for [value] against [sensor]'s baseline, or null if not ready. */
    @Synchronized
    fun deviationSigma(sensor: String, value: Float): Double? =
        trackers[sensor]?.deviationSigma(value.toDouble())

    /** Clears all baselines (used when recording restarts). */
    @Synchronized
    fun resetAll() {
        trackers.clear()
    }

    private fun trackerFor(sensor: String): BaselineTracker = when (sensor) {
        "magnetometer" -> BaselineTracker(minSamples = 200, sigmaFloor = 1.0, relativeSigmaFloor = 0.03)
        "barometer" -> BaselineTracker(minSamples = 200, sigmaFloor = 1.0, relativeSigmaFloor = 0.0002)
        "light" -> BaselineTracker(minSamples = 100, sigmaFloor = 2.0, relativeSigmaFloor = 0.05)
        else -> BaselineTracker(minSamples = 100, sigmaFloor = 0.5)
    }

    companion object {
        private val DEFAULTS = mapOf(
            "magnetometer" to 48f,
            "barometer" to 1013.25f,
            "light" to 200f,
        )
    }
}