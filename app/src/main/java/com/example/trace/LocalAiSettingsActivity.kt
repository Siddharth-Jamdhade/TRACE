package com.example.trace

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.trace.databinding.ActivityLocalAiSettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TRACE — Local AI configuration screen.
 *
 * Lets the operator select a GGUF model file on device storage, load it into
 * memory, or clear the configuration. The model file is never bundled — the
 * user downloads it separately (2+ GB) and picks it via the system file picker.
 *
 * Uses Storage Access Framework (ACTION_OPEN_DOCUMENT) for model selection,
 * so it works across all Android 7+ (API 24+) devices without special
 * storage permissions.
 */
class LocalAiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLocalAiSettingsBinding
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val PICK_MODEL_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Local AI"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        refreshUi()

        binding.btnSelectModel.setOnClickListener { pickModelFile() }
        binding.btnLoadModel.setOnClickListener { loadModel() }
        binding.btnClearModel.setOnClickListener { clearModel() }
    }

    // ── UI refresh ──────────────────────────────────────────────────────

    private fun refreshUi() {
        val config = LocalAiConfig.loadConfig(this)
        if (config != null) {
            val (path, name) = config
            binding.tvModelName.text = name
            binding.tvModelPath.text = path
            binding.tvModelPath.visibility = android.view.View.VISIBLE
            val file = File(path)
            if (file.exists()) {
                val mb = file.length() / (1024 * 1024)
                binding.tvModelSize.text = "${mb} MB"
                binding.tvModelSize.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = "Configured — tap LOAD MODEL to activate"
                binding.btnLoadModel.isEnabled = true
            } else {
                binding.tvModelSize.text = "File not found at saved path"
                binding.tvModelSize.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = "Model file missing — select a new file"
                binding.btnLoadModel.isEnabled = false
            }
        } else {
            binding.tvModelName.text = "No model selected"
            binding.tvModelPath.visibility = android.view.View.GONE
            binding.tvModelSize.visibility = android.view.View.GONE
            binding.tvStatus.text = "No configuration saved."
            binding.btnLoadModel.isEnabled = false
        }

        // Show loaded state
        if (LocalAiClient.isLoaded) {
            binding.btnLoadModel.text = "RELOAD MODEL"
            binding.tvStatus.text = "Model loaded and ready for chat."
        } else {
            binding.btnLoadModel.text = "LOAD MODEL"
        }
    }

    // ── Model file picker ───────────────────────────────────────────────

    private fun pickModelFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Prefer GGUF files if the file picker supports filtering
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream",
                "application/gguf"
            ))
        }
        startActivityForResult(intent, PICK_MODEL_REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL_REQUEST || resultCode != Activity.RESULT_OK || data?.data == null) return

        val uri = data.data!!
        // Take persistent read access so the path survives a reboot
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // Resolve the file name from the content URI
        val modelName = resolveDisplayName(uri) ?: "Unknown model"
        val modelPath = resolveFilePath(uri)

        if (modelPath == null) {
            binding.tvStatus.text = "Could not resolve file path. Try copying the model to a non-removable folder."
            Toast.makeText(this, "File picker returned an unresolvable path", Toast.LENGTH_LONG).show()
            return
        }

        val file = File(modelPath)
        val sizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0L

        // Sanity check: a real LLM GGUF is at least 100 MB
        if (file.exists() && sizeMb < 100) {
            binding.tvStatus.text = "File seems too small for an LLM model ($sizeMb MB). Select a .gguf file."
            Toast.makeText(this, "That file looks too small for a real model", Toast.LENGTH_LONG).show()
            return
        }

        LocalAiConfig.saveConfig(this, modelPath, modelName)
        binding.tvModelName.text = modelName
        binding.tvModelPath.text = modelPath
        binding.tvModelPath.visibility = android.view.View.VISIBLE
        binding.tvModelSize.text = if (file.exists()) "${sizeMb} MB" else "Size unknown"
        binding.tvModelSize.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "Model selected. Tap LOAD MODEL to activate."
        binding.btnLoadModel.isEnabled = true

        Toast.makeText(this, "Model configured: $modelName", Toast.LENGTH_SHORT).show()
    }

    /** Resolves a content URI to a human-readable display name. */
    private fun resolveDisplayName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
        }
    }

    /**
     * Resolves a content URI to an absolute file path suitable for llama.cpp.
     *
     * Content URIs from the Storage Access Framework are not direct file paths.
     * For SAF URIs, copies the content to a stable app-internal location so
     * the model survives device reboots and removable-volume removals.
     */
    private fun resolveFilePath(uri: Uri): String? {
        // For file:// URIs we can use the path directly
        if ("file" == uri.scheme) return uri.path

        // For content:// URIs, copy to internal storage so llama.cpp can
        // mmap the file without SAF permission issues
        return try {
            val internalDir = File(filesDir, "models")
            internalDir.mkdirs()
            val displayName = resolveDisplayName(uri) ?: "model.gguf"
            val dest = File(internalDir, displayName)

            // Only copy if the file doesn't already exist (avoids re-copy on reboot)
            if (!dest.exists()) {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            dest.absolutePath
        } catch (e: Exception) {
            // Fallback: try to use the URI path directly (works on some devices)
            uri.path
        }
    }

    // ── Load / Clear ────────────────────────────────────────────────────

    private fun loadModel() {
        val config = LocalAiConfig.loadConfig(this) ?: run {
            Toast.makeText(this, "Select a model file first", Toast.LENGTH_SHORT).show()
            return
        }
        val (path, name) = config

        binding.tvStatus.text = "Loading $name… (this may take 10–30 seconds)"
        binding.btnLoadModel.isEnabled = false

        scope.launch {
            try {
                LocalAiClient.load(this@LocalAiSettingsActivity, path)
                withContext(Dispatchers.Main) {
                    binding.btnLoadModel.text = "RELOAD MODEL"
                    binding.tvStatus.text = "Model loaded and ready for chat."
                    Toast.makeText(this@LocalAiSettingsActivity, "$name loaded", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Failed to load: ${e.message}"
                    Toast.makeText(this@LocalAiSettingsActivity, "Load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { binding.btnLoadModel.isEnabled = true }
            }
        }
    }

    private fun clearModel() {
        LocalAiClient.unload()
        LocalAiConfig.clearConfig(this)
        binding.tvModelName.text = "No model selected"
        binding.tvModelPath.visibility = android.view.View.GONE
        binding.tvModelSize.visibility = android.view.View.GONE
        binding.tvStatus.text = "Configuration cleared."
        binding.btnLoadModel.isEnabled = false
        binding.btnLoadModel.text = "LOAD MODEL"
        Toast.makeText(this, "Local AI configuration cleared", Toast.LENGTH_SHORT).show()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}