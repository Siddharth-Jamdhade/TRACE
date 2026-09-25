package com.example.trace

/**
 * TRACE — duration formatting.
 *
 * Extracted from [MainActivity] so the session list, the review screen and the
 * live view all render one session length the same way, and so the format is
 * pinned by unit tests instead of by eyeballing a phone.
 */
object Durations {

    /** mm:ss, or h:mm:ss past the hour. Negative inputs clamp to zero. */
    fun format(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }
}
