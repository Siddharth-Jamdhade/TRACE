package com.example.trace

import android.content.Context
import android.content.pm.PackageManager

/**
 * Probes hardware capability at service start so capture can select
 * resolution, rates, and classification intervals appropriate to the device.
 *
 * Ported from ECHO's DeviceProfile. Tiers:
 *  - **LOW** (< 4 GB RAM, or old SoC): 320×240 camera, lower audio rate
 *  - **HIGH** (≥ 6 GB RAM, recent): 640×480 camera, full audio rate
 *
 * Defaults to LOW if probing fails.
 */
data class DeviceProfile(
    val isLowEnd: Boolean,
    val visionWidth: Int,
    val visionHeight: Int,
    val ramGb: Double,
    val sdkInt: Int,
) {
    companion object {
        fun detect(context: Context): DeviceProfile {
            val ram = ramGb(context)
            val sdk = android.os.Build.VERSION.SDK_INT
            val lowEnd = ram < 4.0 || sdk < 29
            return DeviceProfile(
                isLowEnd = lowEnd,
                visionWidth = if (lowEnd) 320 else 640,
                visionHeight = if (lowEnd) 240 else 480,
                ramGb = ram,
                sdkInt = sdk,
            )
        }

        private fun ramGb(context: Context): Double {
            val memInfoClass = try {
                context.getClassLoader().loadClass("android.app.ActivityManager\$MemoryInfo")
            } catch (e: Exception) { return 0.0 }
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                val mi = memInfoClass.getDeclaredConstructor().newInstance()
                android.app.ActivityManager::class.java
                    .getMethod("getMemoryInfo", memInfoClass)
                    .invoke(am, mi)
                val totalMem = memInfoClass.getDeclaredField("totalMem")
                    .apply { isAccessible = true }
                    .getLong(mi)
                return totalMem / (1024.0 * 1024.0 * 1024.0)
            } catch (e: Exception) {
                return 0.0
            }
        }
    }
}