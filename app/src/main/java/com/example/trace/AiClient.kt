package com.example.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * TRACE — minimal OpenAI-compatible chat client.
 *
 * Groq, Google AI Studio (OpenAI-compat endpoint) and OpenRouter all expose
 * the same /chat/completions shape, so one POST serves all three. Deliberately
 * no HTTP library: one endpoint, no streaming, no retries — HttpURLConnection
 * keeps the dependency surface and the audit surface minimal.
 *
 * Not part of the evidence chain: responses are advisory review text and are
 * never written to the database.
 */
object AiClient {

    data class Message(val role: String, val content: String)

    /**
     * Sends [messages] to [provider] and returns the assistant's reply text.
     *
     * @throws IOException with a short, operator-readable reason on any failure
     *   (network, auth, rate limit, malformed response).
     */
    suspend fun chat(
        provider: CloudAi.Provider,
        apiKey: String,
        model: String,
        messages: List<Message>,
        readTimeoutMs: Long = 60_000L
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
        }

        val conn = (URL(provider.baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = readTimeoutMs.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            conn.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw IOException("HTTP $code from ${provider.displayName}: ${shortReason(text)}")
            }

            val json = JSONObject(text)
            val choices = json.optJSONArray("choices")
                ?: throw IOException("Malformed response from ${provider.displayName}")
            if (choices.length() == 0) {
                throw IOException("${provider.displayName} returned no answer")
            }
            val content = choices.getJSONObject(0)
                .getJSONObject("message").optString("content")
            if (content.isBlank()) {
                throw IOException("${provider.displayName} returned an empty answer")
            }
            content
        } finally {
            conn.disconnect()
        }
    }

    /** Pulls the provider's error message out of a JSON error body, if any. */
    private fun shortReason(errorBody: String): String {
        val fromJson = runCatching {
            JSONObject(errorBody)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return (fromJson ?: errorBody.replace("\n", " ")).take(220)
    }
}
