package com.example.trace

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TRACE — Local LLM inference client.
 *
 * Wraps the llama.cpp Android library (dev.ffmpegkit.llama) to load a GGUF
 * model and run chat inference entirely on-device. Follows the same
 * [AiClient.Message] convention so [ChatActivity] can switch between cloud
 * and local with a single field change.
 *
 * The model file (2+ GB for Qwen 3 4B Q4_K_M) is loaded once and kept in
 * memory between chat turns for fast warm inference. Call [unload] when
 * the chat activity is destroyed to free RAM.
 */
object LocalAiClient {

    data class Message(val role: String, val content: String)

    /** Model handle returned by llama.cpp after a successful load. */
    private var modelHandle: LlamaModel? = null
    private var loadedPath: String? = null

    /** True when a model is loaded and ready for inference. */
    val isLoaded: Boolean get() = modelHandle != null

    /** The path of the currently loaded model, or null. */
    val currentModelPath: String? get() = loadedPath

    /**
     * Loads a GGUF model from [modelPath].
     *
     * Idempotent: skips the load when the same path is already loaded.
     * Call from a background coroutine — this is a heavyweight operation
     * (2+ GB file load, ~5–15 s on a Snapdragon 8 Elite).
     */
    suspend fun load(context: android.content.Context, modelPath: String) = withContext(Dispatchers.IO) {
        if (loadedPath == modelPath && modelHandle != null) return@withContext // already loaded
        unload()
        val file = File(modelPath)
        if (!file.exists()) throw IllegalStateException("Model file not found: $modelPath")
        modelHandle = Llama.loadModel(
            modelPath = modelPath,
            config = LlamaConfig(
                contextSize = 2048,
                threads = 4,
            )
        )
        loadedPath = modelPath
    }

    /**
     * Runs chat inference and returns the assistant's full response text.
     *
     * Builds a prompt from [systemPrompt] + [messages] in ChatML format
     * (the native format for Qwen 3), runs inference, and returns the
     * generated text. Non-streaming by design — consistent with [AiClient].
     *
     * @throws IllegalStateException if no model is loaded.
     */
    suspend fun chat(systemPrompt: String, messages: List<Message>): String = withContext(Dispatchers.IO) {
        val handle = modelHandle ?: throw IllegalStateException("Local AI model not loaded")

        // Build ChatML prompt: system first, then alternating user/assistant
        val prompt = buildString {
            append("<|im_start|>system\n$systemPrompt<|im_end|>\n")
            for (msg in messages) {
                if (msg.role == "system") continue // already prepended
                append("<|im_start|>${msg.role}\n${msg.content}<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }

        val result = Llama.complete(
            model = handle,
            prompt = prompt,
            systemPrompt = "", // already baked into the ChatML prompt
            maxTokens = 512,
        )
        result.text.trim()
    }

    /**
     * Unloads the model and frees RAM (2+ GB).
     *
     * Safe to call multiple times — no-op when already unloaded. Call this
     * from [ChatActivity.onDestroy] so the model does not occupy RAM while
     * the user browses the timeline.
     */
    fun unload() {
        val handle = modelHandle
        if (handle != null) {
            Llama.releaseModel(handle)
        }
        modelHandle = null
        loadedPath = null
    }
}