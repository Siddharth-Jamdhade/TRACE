package com.example.trace

import android.content.Context

/**
 * TRACE — Evidence Lock.
 *
 * When locked, TRACE must not add, modify or import any evidence: no new
 * sensor events. The flag lives in
 * SharedPreferences so it survives an app restart.
 *
 * Every writer checks this before touching the database
 * ([MainActivity.onObservation] and the Timeline
 * controls in [TimelineActivity]).
 *
 * This is user-facing state, not a security boundary: it prevents accidental
 * writes while evidence is under review. It does not defend against a rooted
 * device or a modified APK.
 */
object EvidenceLock {

    private const val PREFS_NAME = "trace_prefs"
    private const val KEY_LOCKED = "evidence_locked"

    /** True when the user has sealed the timeline against new evidence. */
    fun isLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKED, false)

    /** Seals (or re-opens) the timeline. */
    fun setLocked(context: Context, locked: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCKED, locked)
            .apply()
    }
}
