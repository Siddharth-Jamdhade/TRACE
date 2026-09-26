package com.example.trace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Converts monotonic timestamps (stored on [Event]) to human-readable
 * wall-clock values for display.
 *
 * The key insight: [Event.timestamp] stores monotonic milliseconds (from
 * [System.nanoTime]) so that clock adjustments during a session can never
 * corrupt fusion windows or timeline ordering. At display time we add the
 * session's wall-clock offset, which was computed once when the session
 * started and is therefore immune to mid-session clock jumps.
 *
 * Usage in UI code:
 * ```
 * binding.tvTimestamp.text = TimestampDisplay.format(event.timestamp)
 * ```
 *
 * The offset is set once by [CaptureService] when a session arms, and cleared
 * when the session ends.
 */
object TimestampDisplay {

    /**
     * Offset added to monotonic ms to produce wall-clock ms.
     *
     * Computed as `System.currentTimeMillis() - System.nanoTime() / 1_000_000`
     * when the session arms. A wall-clock offset of 0 means "no session active"
     * and the formatter falls back to `Date(monotonicMs)` for safety.
     */
    @Volatile
    var wallClockOffsetMs: Long = 0L

    /** The session started-at wall-clock time, for relative-offset formatting. */
    @Volatile
    var sessionStartedAtWallClock: Long = 0L

    private val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("MMM d, hh:mm:ss a", Locale.US)

    /**
     * Converts a monotonic timestamp to a display [Date].
     *
     * Uses [wallClockOffsetMs] to convert. If no offset has been set (0),
     * falls back to treating the value as wall-clock for backward compat
     * with pre-refactor data.
     */
    fun toDate(monotonicMs: Long): Date {
        val wallMs = if (wallClockOffsetMs != 0L) monotonicMs + wallClockOffsetMs else monotonicMs
        return Date(wallMs)
    }

    /** Formats [monotonicMs] as a short time string (e.g. "02:30:45 PM"). */
    fun formatTime(monotonicMs: Long): String = timeFormat.format(toDate(monotonicMs))

    /** Formats [monotonicMs] as a full date+time string. */
    fun formatDateTime(monotonicMs: Long): String = dateTimeFormat.format(toDate(monotonicMs))

    /** Formats [monotonicMs] as a session-relative offset (e.g. "+12.3s"). */
    fun formatOffset(monotonicMs: Long): String {
        if (sessionStartedAtWallClock == 0L) return formatTime(monotonicMs)
        val wallMs = if (wallClockOffsetMs != 0L) monotonicMs + wallClockOffsetMs else monotonicMs
        val deltaMs = wallMs - sessionStartedAtWallClock
        return if (deltaMs < 1000) "+${deltaMs}ms"
        else "+${"%.1f".format(deltaMs / 1000.0)}s"
    }

    /** Resets the offset — call when a session ends. */
    fun reset() {
        wallClockOffsetMs = 0L
        sessionStartedAtWallClock = 0L
    }
}