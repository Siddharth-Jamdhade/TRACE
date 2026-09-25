package com.example.trace

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.trace.databinding.ActivityVideoImportBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoImportBinding
    private lateinit var database: TraceDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pendingUris = mutableListOf<Uri>()

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingUris.clear()
            pendingUris.addAll(uris)
            binding.tvStatus.text = "${uris.size} video(s) selected. Tap Analyse to begin."
            binding.tvFileList.text = uris.joinToString("\n") { getFileName(it) }
            binding.btnAnalyse.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "TRACE -- Analyse Videos"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = TraceDatabase.getInstance(this)

        binding.btnPickVideos.setOnClickListener { pickVideos.launch("video/*") }
        binding.btnAnalyse.setOnClickListener { analyseAll() }
        binding.btnAnalyse.isEnabled = false

        // Evidence Lock: importing video evidence after the timeline is sealed
        // would append fresh rows to a chain the user declared final.
        if (EvidenceLock.isLocked(this)) {
            binding.tvLockedBanner.visibility = View.VISIBLE
            binding.btnPickVideos.isEnabled = false
            binding.btnAnalyse.isEnabled = false
        }
    }

    private fun analyseAll() {
        if (pendingUris.isEmpty()) return

        // Re-check the lock: it can be armed from the Timeline screen while this
        // activity is already open.
        if (EvidenceLock.isLocked(this)) {
            Toast.makeText(this, "Evidence is locked — no new events can be imported.", Toast.LENGTH_LONG).show()
            binding.tvLockedBanner.visibility = View.VISIBLE
            binding.btnPickVideos.isEnabled = false
            binding.btnAnalyse.isEnabled = false
            return
        }

        binding.btnPickVideos.isEnabled = false
        binding.btnAnalyse.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        scope.launch {
            // Sort videos by their embedded creation time so random-order
            // uploads are automatically arranged on the global timeline.
            val sorted = pendingUris.sortedBy { uri ->
                VideoAnalyzer.getVideoCreationTime(this@VideoImportActivity, uri)
            }

            var totalEvents = 0

            sorted.forEachIndexed { idx, uri ->
                val videoStart = VideoAnalyzer.getVideoCreationTime(this@VideoImportActivity, uri)
                val name = getFileName(uri)

                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Analysing video ${idx + 1}/${sorted.size}: $name"
                }

                val events = VideoAnalyzer.analyzeVideo(
                    context      = this@VideoImportActivity,
                    uri          = uri,
                    videoStartMs = videoStart,
                    onProgress   = { done, total ->
                        val pct = if (total > 0) (done * 100) / total else 0
                        runOnUiThread { binding.progressBar.progress = pct }
                    }
                )

                // Save every extracted event through the normal fusion+hash pipeline.
                // ChainWriter is the only writer allowed to touch the chain, so
                // imported events link correctly alongside live sensor events.
                for (event in events) {
                    ChainWriter.append(database.eventDao(), event) { inserted ->
                        FusionEngine.buildEnrichedEvent(inserted, listOf(inserted))
                    }
                }

                totalEvents += events.size
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.tvStatus.text = "Done! Extracted $totalEvents events from ${sorted.size} video(s)."
                binding.btnPickVideos.isEnabled = true
                Toast.makeText(
                    this@VideoImportActivity,
                    "Added $totalEvents events to TRACE timeline",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: uri.lastPathSegment ?: "video"
        } catch (e: Exception) { uri.lastPathSegment ?: "video" }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}