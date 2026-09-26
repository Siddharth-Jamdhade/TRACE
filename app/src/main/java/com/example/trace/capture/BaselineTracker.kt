package com.example.trace.capture

/**
 * Adaptive baseline tracker with sigma-gated event detection.
 *
 * Maintains an EWMA mean and variance over recent samples so that detectors
 * can fire on *deviations* from the learned environment rather than fixed
 * values. Three improvements over the original [com.example.trace.RollingBaseline]:
 *
 * 1. **Min samples** — the baseline is not `ready` until [minSamples] have been
 *    fed, preventing false positives during the warm-up period.
 * 2. **Sigma floors** — a minimum [sigmaFloor] prevents a sensor with vanishing
 *    noise (e.g. a barometer in a quiet room) from treating sub-0.1 hPa gusts
 *    as multi-sigma events.
 * 3. **Relative sigma floor** — for sensors whose baseline drifts (barometer
 *    varies with weather, light with time of day), [relativeSigmaFloor] sets
 *    the floor as a fraction of the current baseline mean, so a 0.3 hPa gust
 *    on a 1013 hPa day and a 1010 hPa storm front are treated comparably.
 *
 * Thread-safe: all public methods are synchronized.
 */
class BaselineTracker(
    private val minSamples: Int = 100,
    private val sigmaFloor: Double = 0.5,
    private val relativeSigmaFloor: Double = 0.0,
    /** EWMA alpha for mean (0.0–1.0). ~30 s warm-up at 50 Hz with alpha=0.05. */
    private val alpha: Double = 0.05,
) {
    private var count = 0
    private var mean = 0.0
    private var m2 = 0.0       // sum of squared differences from current mean
    private var last = 0.0

    /** True once [minSamples] have been fed. Detectors should check this. */
    @get:Synchronized
    val ready: Boolean get() = count >= minSamples

    /** Current EWMA baseline mean. */
    @get:Synchronized
    val baselineMean: Double get() = mean

    /** Most recent sample value. */
    @get:Synchronized
    val lastValue: Double get() = last

    /**
     * Feeds a sample and returns the absolute deviation from the baseline
     * *before* the sample was merged (compatible with [RollingBaseline.feed]).
     */
    @Synchronized
    fun feed(value: Float): Float = feed(value.toDouble()).toFloat()

    /** Double version of [feed]. */
    @Synchronized
    fun feed(value: Double): Double {
        val delta = kotlin.math.abs(value - mean)
        val residual = value - mean
        mean += alpha * residual
        m2 += alpha * (residual * residual - m2)
        count++
        last = value
        return delta
    }

    /**
     * Returns the number of standard deviations [value] is from the baseline,
     * or null if the baseline is not yet ready.
     *
     * The effective floor is max([sigmaFloor], [relativeSigmaFloor] * |mean|),
     * so sensors with tiny natural variance don't fire on trivial noise.
     */
    @Synchronized
    fun deviationSigma(value: Double): Double? {
        if (!ready) return null
        val std = kotlin.math.sqrt(m2.coerceAtLeast(0.0))
        val floor = maxOf(sigmaFloor, kotlin.math.abs(mean) * relativeSigmaFloor)
        val effectiveStd = maxOf(std, floor)
        return if (effectiveStd > 0.0) kotlin.math.abs(value - mean) / effectiveStd else 0.0
    }

    /** Clears all state. */
    @Synchronized
    fun reset() {
        count = 0
        mean = 0.0
        m2 = 0.0
        last = 0.0
    }
}