package com.example.trace

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.trace.databinding.ActivityCloudAiSettingsBinding

/**
 * TRACE — Cloud AI configuration screen.
 *
 * Lets the operator pick a provider (Groq / Google AI Studio / OpenRouter),
 * paste that provider's API key and type the model name exactly as the
 * provider expects it. Saved into [CloudAi]'s encrypted store.
 *
 * The key field is write-only from the operator's point of view: it always
 * starts blank and is never refilled from storage, so a shoulder-surfer or a
 * screenshot of this screen never shows a key. The status line says only
 * whether a key is saved — never the key itself.
 */
class CloudAiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudAiSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Cloud AI"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Pre-select the saved provider (if any); fields stay blank by design.
        CloudAi.loadConfig(this)?.let { (provider, _, model) ->
            when (provider.id) {
                CloudAi.GROQ.id -> binding.radioGroq.isChecked = true
                CloudAi.GOOGLE_AI_STUDIO.id -> binding.radioGoogle.isChecked = true
                CloudAi.OPENROUTER.id -> binding.radioOpenrouter.isChecked = true
            }
            binding.inputModel.setText(model)
            binding.tvStatus.text = "A key is saved for ${provider.displayName}."
        } ?: run {
            binding.tvStatus.text = "No configuration saved."
        }

        binding.btnSave.setOnClickListener { save() }
        binding.btnClear.setOnClickListener {
            CloudAi.clearConfig(this)
            binding.inputApiKey.setText("")
            binding.inputModel.setText("")
            binding.providerGroup.clearCheck()
            binding.tvStatus.text = "No configuration saved."
            Toast.makeText(this, "Cloud AI config cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val provider = when {
            binding.radioGroq.isChecked -> CloudAi.GROQ
            binding.radioGoogle.isChecked -> CloudAi.GOOGLE_AI_STUDIO
            binding.radioOpenrouter.isChecked -> CloudAi.OPENROUTER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, "Pick a provider first", Toast.LENGTH_SHORT).show()
            return
        }
        val key = binding.inputApiKey.text?.toString()?.trim().orEmpty()
        if (key.isEmpty()) {
            binding.inputApiKey.error = "Paste the provider's API key"
            return
        }
        val model = binding.inputModel.text?.toString()?.trim().orEmpty()
        if (model.isEmpty()) {
            binding.inputModel.error = "Type the model name (provider's exact spelling)"
            return
        }

        try {
            CloudAi.saveConfig(this, provider, key, model)
            binding.inputApiKey.setText("")
            binding.tvStatus.text = "Saved. A key is stored for ${provider.displayName}, model \"$model\"."
            Toast.makeText(this, "Cloud AI configured", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
