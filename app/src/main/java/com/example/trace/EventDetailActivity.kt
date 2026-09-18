package com.example.trace

import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.trace.databinding.ActivityEventDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE — Event Detail screen.
 *
 * Shows full evidence breakdown for a single [Event]:
 *   - Fusion status with colour-coded banner
 *   - Per-sensor confidence bars (camera / audio / motion)
 *   - QueryEngine explanation text
 *   - Confirm / Reject human-review buttons
 *   - Evidence clip playback (if a clip was saved)
 *   - SHA-256 hash-chain integrity indicator
 */
class EventDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventDetailBinding
    private lateinit var database: TraceDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentEvent: Event? = null
    private var mediaPlayer: MediaPlayer? = null

    private val dateFmt = SimpleDateFormat("MMM dd, yyyy  HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Event Detail"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = TraceDatabase.getInstance(this)

        val eventId = intent.getLongExtra("eventId", -1L)
        if (eventId == -1L) { finish(); return }

        loadEvent(eventId)

        binding.btnConfirm.setOnClickListener { updateStatus("CONFIRMED") }
        binding.btnReject.setOnClickListener  { updateStatus("REJECTED")  }
        binding.btnPlayEvidence.setOnClickListener { playEvidence() }
    }

    // ------ Data loading ------------------------------------------------

    private fun loadEvent(eventId: Long) {
        scope.launch {
            val e          = database.eventDao().getEventById(eventId) ?: return@launch
            val allEvents  = database.eventDao().getAllEvents()
            currentEvent   = e

            // Verify this event and the full chain
            val singleValid = HashChain.verifySingle(e)
            val chainValid  = HashChain.verifyChain(allEvents)

            withContext(Dispatchers.Main) {
                renderEvent(e, singleValid, chainValid)
            }
        }
    }

    // ------ Rendering ---------------------------------------------------

    private fun renderEvent(e: Event, singleValid: Boolean, chainValid: Boolean) {
        // Status banner
        val statusColor = when (e.status) {
            "CONFIRMED"   -> Color.parseColor("#1B5E20")
            "UNCONFIRMED" -> Color.parseColor("#E65100")
            "REJECTED"    -> Color.parseColor("#B71C1C")
            else          -> Color.parseColor("#263238")
        }
        binding.statusCard.setBackgroundColor(statusColor)
        binding.tvStatus.text    = e.status
        binding.tvEventType.text = e.type.replace("_", " ").replaceFirstChar { it.uppercase() }
        binding.tvTimestamp.text = dateFmt.format(Date(e.timestamp))
        binding.tvSource.text    = "Primary source: ${e.source}"

        // Sensor confidence bars
        // If the event was triggered by a single sensor, its own confidence is the
        // best evidence we have — motionConfidence is only set when another source
        // was also in the 2-second fusion window.
        val camConf    = e.cameraConfidence ?: if (e.source == "camera") e.confidence else 0f
        val audioConf  = e.audioConfidence  ?: if (e.source == "audio")  e.confidence else 0f
        val motionConf = e.motionConfidence ?: if (e.source == "motion") e.confidence else 0f
        binding.progressCamera.progress = (camConf    * 100).toInt()
        binding.progressAudio.progress  = (audioConf  * 100).toInt()
        binding.progressMotion.progress = (motionConf * 100).toInt()
        binding.tvCameraConf.text  = "Camera:  ${"%.0f".format(camConf    * 100)}%"
        binding.tvAudioConf.text   = "Audio:   ${"%.0f".format(audioConf  * 100)}%"
        binding.tvMotionConf.text  = "Motion:  ${"%.0f".format(motionConf * 100)}%"

        // Explanation text
        binding.tvExplanation.text = QueryEngine.explainConfidence(e)

        // Evidence clip
        val clip = e.evidenceClipPath
        if (!clip.isNullOrEmpty() && File(clip).exists()) {
            binding.tvEvidenceStatus.text = "Audio evidence clip available."
            binding.btnPlayEvidence.visibility = View.VISIBLE
        } else {
            binding.tvEvidenceStatus.text = "No evidence clip saved for this event."
            binding.btnPlayEvidence.visibility = View.GONE
        }

        // Hash-chain integrity
        if (e.hash != null) {
            val overallOk = singleValid && chainValid
            binding.tvHashStatus.text = if (overallOk)
                "Integrity: VALID - record not tampered"
            else
                "Integrity: INVALID - record may have been tampered"
            binding.tvHashStatus.setTextColor(
                if (overallOk) Color.parseColor("#00C853") else Color.parseColor("#D50000")
            )
            binding.tvHashValue.text = "SHA-256: ${e.hash.take(20)}..."
        } else {
            binding.tvHashStatus.text = "Integrity: hash not yet computed"
            binding.tvHashValue.text = ""
        }
    }

    // ------ User actions ------------------------------------------------

    private fun updateStatus(newStatus: String) {
        val e = currentEvent ?: return
        scope.launch {
            database.eventDao().updateStatus(e.id, newStatus)
            val updated = e.copy(status = newStatus)
            currentEvent = updated
            withContext(Dispatchers.Main) {
                renderEvent(updated, HashChain.verifySingle(updated), true)
                Toast.makeText(
                    this@EventDetailActivity,
                    "Status set to $newStatus",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun playEvidence() {
        val clip = currentEvent?.evidenceClipPath ?: return
        val file = File(clip)
        if (!file.exists()) {
            Toast.makeText(this, "Evidence file not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(clip)
                prepare()
                start()
                setOnCompletionListener {
                    Toast.makeText(this@EventDetailActivity, "Playback complete", Toast.LENGTH_SHORT).show()
                }
            }
            Toast.makeText(this, "Playing evidence clip...", Toast.LENGTH_SHORT).show()
        } catch (ex: Exception) {
            Toast.makeText(this, "Playback failed: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------ Lifecycle ---------------------------------------------------

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        scope.cancel()
    }
}
