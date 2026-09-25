package com.example.trace

import android.content.Context

/**
 * TRACE — appearance preferences.
 *
 * Chooses between the user's wallpaper palette (Material You) and the TRACE
 * palette. Only meaningful on Android 12 and above: older devices have no
 * dynamic palette to apply, so they always use the TRACE palette.
 *
 * The switch currently lives in the Timeline overflow menu; Stage 6 moves it into
 * Settings. The stored key is already the one Settings will read.
 */
object TraceAppearance {

    private const val PREFS_NAME = "trace_prefs"
    private const val KEY_WALLPAPER_PALETTE = "wallpaper_palette"

    /** True when the wallpaper palette should be used where the platform offers one. */
    fun useWallpaperPalette(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WALLPAPER_PALETTE, true)

    fun setUseWallpaperPalette(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WALLPAPER_PALETTE, enabled)
            .apply()
    }
}
