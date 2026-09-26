package com.example.trace

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * TRACE — Local AI configuration.
 *
 * Stores the path to a downloaded GGUF model file on device storage, and a
 * human-readable model name (parsed from the filename). The actual model file
 * (2+ GB) lives on the user's SD card or internal storage — only the path and
 * a label are stored in EncryptedSharedPreferences (AES-256, Keystore-backed).
 *
 * Design mirrors [CloudAi] exactly so the caller never needs to know which AI
 * backend is active.
 */
object LocalAiConfig {

    private const val PREFS_FILE = "trace_local_ai"
    private const val KEY_MODEL_PATH = "model_path"
    private const val KEY_MODEL_NAME = "model_name"

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    private fun prefs(context: Context): android.content.SharedPreferences? {
        return prefs ?: synchronized(this) {
            prefs ?: runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrNull()
        }.also { prefs = it }
    }

    /** True when a model file path is saved. */
    fun isConfigured(context: Context): Boolean = runCatching {
        val p = prefs(context) ?: return false
        !p.getString(KEY_MODEL_PATH, null).isNullOrBlank()
    }.getOrDefault(false)

    /** Saves the model location and a display name. */
    fun saveConfig(context: Context, modelPath: String, modelName: String) {
        val p = prefs(context) ?: throw IllegalStateException("Secure storage unavailable")
        p.edit()
            .putString(KEY_MODEL_PATH, modelPath.trim())
            .putString(KEY_MODEL_NAME, modelName.trim())
            .apply()
    }

    /** The stored model path and name, or null when not configured. */
    fun loadConfig(context: Context): Pair<String, String>? = runCatching {
        val p = prefs(context) ?: return null
        val path = p.getString(KEY_MODEL_PATH, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = p.getString(KEY_MODEL_NAME, null)?.takeIf { it.isNotBlank() } ?: "Unknown model"
        path to name
    }.getOrNull()

    /** Removes the saved config (does not delete the model file). */
    fun clearConfig(context: Context) {
        prefs(context)?.edit()?.clear()?.apply()
    }
}