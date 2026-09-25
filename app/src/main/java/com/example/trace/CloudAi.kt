package com.example.trace

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * TRACE — Cloud AI configuration.
 *
 * Holds the three OpenAI-compatible providers TRACE can chat with, and stores
 * the operator's choice, API key and model name in EncryptedSharedPreferences
 * (AES-256, key held in the Android Keystore) — never in the database, never in
 * plaintext prefs, and never uploaded anywhere except to the provider the key
 * belongs to.
 *
 * All three providers expose an OpenAI-compatible /chat/completions endpoint,
 * so one client serves all of them; only the base URL differs. The model name
 * is typed by the operator exactly as the provider expects it — TRACE does not
 * maintain a model catalogue it cannot keep current.
 */
object CloudAi {

    /** One chat provider: where to POST and what to call it. */
    data class Provider(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val keyHint: String
    )

    val GROQ = Provider(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
        keyHint = "gsk_..."
    )
    val GOOGLE_AI_STUDIO = Provider(
        id = "google_ai_studio",
        displayName = "Google AI Studio",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        keyHint = "AIza..."
    )
    val OPENROUTER = Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/chat/completions",
        keyHint = "sk-or-..."
    )

    val PROVIDERS = listOf(GROQ, GOOGLE_AI_STUDIO, OPENROUTER)

    fun byId(id: String?): Provider? = PROVIDERS.firstOrNull { it.id == id }

    // ── Encrypted storage ─────────────────────────────────────────────────

    private const val PREFS_FILE = "trace_cloud_ai"
    private const val KEY_PROVIDER = "provider_id"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

    /** Load-bearing lazily-created store; null only if Keystore init fails. */
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

    /** True when the operator has everything needed for a chat request. */
    fun isConfigured(context: Context): Boolean {
        val p = prefs(context) ?: return false
        return !p.getString(KEY_PROVIDER, null).isNullOrBlank() &&
            !p.getString(KEY_API_KEY, null).isNullOrBlank() &&
            !p.getString(KEY_MODEL, null).isNullOrBlank()
    }

    fun saveConfig(context: Context, provider: Provider, apiKey: String, model: String) {
        val p = prefs(context) ?: throw IllegalStateException("Secure storage unavailable")
        p.edit()
            .putString(KEY_PROVIDER, provider.id)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    fun clearConfig(context: Context) {
        prefs(context)?.edit()?.clear()?.apply()
    }

    /** The stored configuration, or null when not (fully) configured. */
    fun loadConfig(context: Context): Triple<Provider, String, String>? {
        val p = prefs(context) ?: return null
        val provider = byId(p.getString(KEY_PROVIDER, null)) ?: return null
        val key = p.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val model = p.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: return null
        return Triple(provider, key, model)
    }
}
