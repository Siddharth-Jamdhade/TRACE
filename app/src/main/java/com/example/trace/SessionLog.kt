package com.example.trace

/**
 * TRACE — live session log buffer.
 *
 * A capped, in-memory transcript of what the live view did during a session:
 * recorded events, operator tags, session lifecycle and capture errors. The
 * dashboard's log tail renders it newest-at-the-bottom so an operator watching
 * the phone sees the present at the bottom edge, next to the buttons.
 *
 * NOT evidence. The evidence of record is the hash-chained [Event] table; this
 * buffer is a surface for the operator and survives only this process. That is
 * also why it is capped: a day-long session must not grow it without bound.
 * When the dashboard re-attaches to a running session the tail is reseeded
 * from the database's most recent events, so entries recorded while the screen
 * was away are not lost even though the buffer itself is volatile.
 *
 * Thread-safe: appends come from sensor threads, the audio thread and the main
 * thread; reads happen on the main thread.
 */
class SessionLog(private val capacity: Int = DEFAULT_CAPACITY) {

    /** What produced a line — drives its colour in the log tail. */
    enum class Kind {
        /** A sensor event that passed extraction + cooldown, with its fusion status. */
        EVENT,

        /** A human assertion from the TAG INCIDENT control. */
        TAG,

        /** Session armed / ended / resumed — not itself evidence. */
        LIFECYCLE,

        /** Capture hardware failed for one pipeline; capture of the rest continues. */
        ERROR,

        /** Context that is neither an incident nor a fault (hardware warnings). */
        INFO
    }

    /** One rendered entry. [timestamp] is wall-clock ms, formatted by the UI. */
    data class Line(val kind: Kind, val text: String, val timestamp: Long)

    private val lines = ArrayDeque<Line>()

    /** Appends a line, dropping the oldest when [capacity] is exceeded. */
    @Synchronized
    fun append(kind: Kind, text: String, timestamp: Long = System.currentTimeMillis()) {
        lines.addLast(Line(kind, text, timestamp))
        while (lines.size > capacity) lines.removeFirst()
    }

    /** Snapshot of the buffer, oldest first. */
    @Synchronized
    fun all(): List<Line> = lines.toList()

    /** Empties the buffer — used when the tail is reseeded from the database. */
    @Synchronized
    fun clear() = lines.clear()

    companion object {
        /**
         * Fits roughly the bottom quarter of a phone screen at ~7 visible rows
         * while keeping a few minutes of dense incident traffic scrollable.
         */
        const val DEFAULT_CAPACITY = 60
    }
}
