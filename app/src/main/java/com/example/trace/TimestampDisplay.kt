package com.example.trace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats wall-clock timestamps (from [System.currentTimeMillis]) to
 * human-readable date/time strings in the device's local timezone.
 *
 * All timestamps in the app now use the phone's own clock, so this object
 * simply formats dates — no monotonic-to-wall-clock offset is needed.
 *
 * Usage in UI code:
 * ```
 * binding.tvTimestamp.text = TimestampDisplay.format(event.timestamp)
 * ```
 */
object TimestampDisplay {

    private val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("MMM d, hh:mm:ss a", Locale.US)

    /** Formats [ms] as a short time string (e.g. "02:30:45 PM"). */
    fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

    /** Formats [ms] as a full date+time string (e.g. "Jan 15, 02:30:45 PM"). */
    fun formatDateTime(ms: Long): String = dateTimeFormat.format(Date(ms))

    /** Formats [ms] as a session-relative offset (falls back to [formatTime]). */
    fun formatOffset(ms: Long): String = formatTime(ms)
}