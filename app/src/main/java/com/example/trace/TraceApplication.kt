package com.example.trace

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * TRACE — application entry point.
 *
 * Applies Material You dynamic colour to every activity on Android 12+. On older
 * devices this is a no-op, and `Theme.TRACE`'s own palette is used — which is the
 * intended fallback, not a degraded mode: the TRACE values mirror the colours the
 * layouts used before the theme existed.
 *
 * Dynamic colour has to be wired here rather than in an activity, because it must
 * be applied before each activity is created.
 */
class TraceApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                // Lets the TRACE palette be pinned from preferences. Without a
                // precondition the wallpaper palette could never be turned off.
                .setPrecondition { context, _ -> TraceAppearance.useWallpaperPalette(context) }
                .build()
        )
    }
}
