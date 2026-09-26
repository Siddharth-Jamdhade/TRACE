package com.example.trace

import java.util.concurrent.ConcurrentHashMap

/**
 * TRACE — shared live-sensor state bridge between Tier 1 logging and Tier 2
 * transition detection.
 *
 * Every capture source writes its current value here as it computes it:
 * - [CameraSource] writes the frame-diff ratio after each analysis hop
 * - [AudioSource] writes the RMS after each PCM hop
 * - [MotionSensorSource] writes the jerk / gyro magnitude / linear magnitude
 * - [EnvironmentSensorSource] writes the mag / hPa / lux
 *
 * [CaptureService]'s Tier 1 logger polls [currentValues] at a fixed interval
 * and persists the readings to the [SensorLog] table.  [CaptureService]'s
 * Tier 2 transition detector reads [recentRing] to gather cross-sensor
 * context when one sensor triggers a capture.
 *
 * All maps are thread-safe ConcurrentHashMaps.  Sources call [publishValue]
 * on their analysis thread; the logger calls [readAndClear] on its own
 * coroutine.
 */
object SensorLogger {

    /**
     * Latest value per sensor, updated by sources on each reading.
     *
     * Because this is a live snapshot (not a queue), a single sensor writing
     * 50 times/second and a logger reading once/second means most intermediate
     * values are harmlessly overwritten.  That is exactly the desired behaviour:
     * the logger records the *latest* value each second, not 50 redundant
     * copies.
     */
    val currentValues = ConcurrentHashMap<String, Double>()

    /**
     * Ring buffer of recent (timestamp, value) pairs per sensor.
     *
     * Sources call [publishValue] which appends here too.  The buffer trims
     * entries older than [RING_SECONDS].  Used by Tier 2 cross-sensor
     * corroboration to see what other sensors were doing in the seconds
     * around a trigger.
     *
     * Entries older than [RING_SECONDS] are trimmed on each write, so the
     * buffer never grows unbounded.
     */
    val recentRing = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Double>>>()

    private const val RING_SECONDS = 60L
    private const val RING_NANOS = RING_SECONDS * 1_000_000_000L

    /**
     * Called by sensor sources on each analysis hop to publish their latest
     * value.  Thread-safe: updates [currentValues] and [recentRing].
     */
    fun publishValue(source: String, value: Double, monoTimeNs: Long) {
        currentValues[source] = value
        val deque = recentRing.getOrPut(source) { ArrayDeque() }
        val cutoff = monoTimeNs - RING_NANOS
        synchronized(deque) {
            deque.addLast(monoTimeNs to value)
            while (deque.size > 1 && deque.first().first < cutoff) {
                deque.removeFirst()
            }
        }
    }

    /** Returns the ring buffer entries for [source] within [lookbackMs] of [nowMs]. */
    fun readRecent(source: String, nowMs: Long, lookbackMs: Long): List<Pair<Long, Double>> {
        val deque = recentRing[source] ?: return emptyList()
        val cutoffNs = nowMs * 1_000_000
        synchronized(deque) {
            return deque.filter { it.first >= cutoffNs - lookbackMs * 1_000_000 }
        }
    }

    /** Clears all state — call on session end. */
    fun reset() {
        currentValues.clear()
        recentRing.clear()
    }
}