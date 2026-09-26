package com.example.trace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaPlayer
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.trace.databinding.ActivityEventDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The theme is NoActionBar, so the title and back arrow need a real toolbar.
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Event Detail"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = TraceDatabase.getInstance(this)

        val eventId = intent.getLongExtra("eventId", -1L)
        if (eventId == -1L) { finish(); return }

        loadEvent(eventId)   // binding.btnViewPhoto is wired after loading, below

        binding.btnConfirm.setOnClickListener { updateStatus("CONFIRMED") }
        binding.btnReject.setOnClickListener  { updateStatus("REJECTED")  }
        binding.btnPlayEvidence.setOnClickListener { playEvidence() }
        binding.btnViewPhoto.setOnClickListener { showSnapshot() }
    }

    // ------ Data loading ------------------------------------------------

    private fun loadEvent(eventId: Long) {
        scope.launch {
            val e          = database.eventDao().getEventById(eventId) ?: return@launch
            currentEvent   = e

            // Verify this event against itself, and against its own session's
            // chain — events in other sessions cannot affect this verdict.
            val session     = database.sessionDao().getSessionById(e.sessionId)
            val singleValid = HashChain.verifySingle(e)
            val chainValid  = session != null && HashChain.verifySession(
                session,
                database.eventDao().getEventsForSession(e.sessionId)
            )

            // Stage 7: verify the evidence FILES against their write-time
            // hashes. Done here in the IO coroutine — hashing a 3 MP snapshot
            // on the main thread would jank the screen.
            val clipOk  = e.evidenceClipPath?.let { FileIntegrity.matches(File(it), e.clipHash) }
            val photoOk = e.evidencePhotoPath?.let { FileIntegrity.matches(File(it), e.photoHash) }

            withContext(Dispatchers.Main) {
                renderEvent(e, singleValid, chainValid, clipOk, photoOk)
            }
        }
    }

    // ------ Rendering ---------------------------------------------------

    private fun renderEvent(
        e: Event,
        singleValid: Boolean,
        chainValid: Boolean,
        clipOk: Boolean?,
        photoOk: Boolean?
    ) {
        // Status banner
        // Fixed semantic colours, deliberately not theme attributes: a CONFIRMED
        // incident has to stay green whatever wallpaper palette is active.
        val statusColor = when (e.status) {
            "CONFIRMED"   -> ContextCompat.getColor(this, R.color.trace_status_confirmed_surface)
            "UNCONFIRMED" -> ContextCompat.getColor(this, R.color.trace_status_unconfirmed_surface)
            "REJECTED"    -> ContextCompat.getColor(this, R.color.trace_status_rejected_surface)
            "MANUAL"      -> ContextCompat.getColor(this, R.color.trace_status_manual_surface)
            else          -> ContextCompat.getColor(this, R.color.trace_status_unknown_surface)
        }
        val manual = e.source == SensorRegistry.MANUAL.id
        binding.statusCard.setBackgroundColor(statusColor)
        binding.tvStatus.text    = e.status
        binding.tvEventType.text = e.type.replace("_", " ").replaceFirstChar { it.uppercase() }
        binding.tvTimestamp.text = TimestampDisplay.formatDateTime(e.timestamp)
        binding.tvSource.text    =
            if (manual) "Primary source: manual (asserted by the operator)"
            else        "Primary source: ${e.source}"

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

        // Extra sensors recorded in the fusion window (magnetometer, barometer,
        // light, linear, gyroscope, step, sigmotion) — shown so no evidence is hidden.
        //
        // For a manual tag the three bars above stay empty by design (no sensor
        // measured anything for it), which would read like a fault; explain it
        // instead of leaving three bare zeros.
        binding.tvExtraSensors.text = if (manual) {
            "${SensorRegistry.MANUAL.icon} Asserted by the operator — no sensor contributed to this record.\n" +
            "Sensor events recorded around this moment are separate entries in the timeline."
        } else {
            e.sensorBreakdown
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n") { pair ->
                    val sensor = pair.substringBefore(':')
                    val conf   = pair.substringAfter(':').toFloatOrNull() ?: 0f
                    "${SensorRegistry.iconFor(sensor)} ${SensorRegistry.labelFor(sensor)}: ${"%.0f".format(conf * 100)}%"
                }
                ?.takeIf { it.isNotEmpty() } ?: "No additional sensor evidence in window."
        }

        // Explanation text
        binding.tvExplanation.text = QueryEngine.explainConfidence(e)

        // Evidence clip — with Stage 7 file-integrity verdict
        val clip = e.evidenceClipPath
        if (!clip.isNullOrEmpty() && File(clip).exists()) {
            binding.tvEvidenceStatus.text = when {
                e.clipHash == null      -> "Audio evidence clip available.\nNo integrity hash stored (recorded before hashing was added)."
                clipOk == true          -> "Audio evidence clip available.\nFile integrity verified against its stored SHA-256."
                else                    -> {
                    binding.tvEvidenceStatus.setTextColor(
                        ContextCompat.getColor(this, R.color.trace_integrity_broken)
                    )
                    "Audio evidence clip available.\n⚠ HASH MISMATCH — the clip file may have been replaced or altered."
                }
            }
            binding.btnPlayEvidence.visibility = View.VISIBLE
        } else {
            binding.tvEvidenceStatus.text = "No evidence clip saved for this event."
            binding.btnPlayEvidence.visibility = View.GONE
        }

        // Camera snapshot: a still is the weakest visual evidence, but it is the
        // only visual this event has. Hidden rather than shown when the file is
        // gone — an investigator must never be offered a button that cannot work.
        val photo = e.evidencePhotoPath
        if (!photo.isNullOrEmpty() && File(photo).exists()) {
            binding.tvPhotoStatus.text = when {
                e.photoHash == null     -> "Camera snapshot available.\nNo integrity hash stored (recorded before hashing was added)."
                photoOk == true         -> "Camera snapshot available.\nFile integrity verified against its stored SHA-256."
                else                    -> {
                    binding.tvPhotoStatus.setTextColor(
                        ContextCompat.getColor(this, R.color.trace_integrity_broken)
                    )
                    "Camera snapshot available.\n⚠ HASH MISMATCH — the image file may have been replaced or altered."
                }
            }
            binding.btnViewPhoto.visibility = View.VISIBLE
        } else {
            binding.tvPhotoStatus.text =
                if (manual) "No snapshot — a tag records your assertion, and the\n" +
                    "camera captures for events in the same session."
                else "No camera snapshot for this event."
            binding.btnViewPhoto.visibility = View.GONE
            binding.ivSnapshot.visibility = View.GONE
        }

        // Hash-chain integrity
        if (e.hash != null) {
            val overallOk = singleValid && chainValid
            binding.tvHashStatus.text = if (overallOk)
                "Integrity: VALID - record not tampered"
            else
                "Integrity: INVALID - record may have been tampered"
            binding.tvHashStatus.setTextColor(
                if (overallOk) ContextCompat.getColor(this, R.color.trace_integrity_ok)
                else ContextCompat.getColor(this, R.color.trace_integrity_broken)
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
            val clipOk  = updated.evidenceClipPath?.let { FileIntegrity.matches(File(it), updated.clipHash) }
            val photoOk = updated.evidencePhotoPath?.let { FileIntegrity.matches(File(it), updated.photoHash) }
            withContext(Dispatchers.Main) {
                renderEvent(updated, HashChain.verifySingle(updated), true, clipOk, photoOk)
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

    /**
     * Opens the event's snapshot in a dialog, decoded downsampled.
     *
     * Review is not forensic analysis: no screen here needs more than ~2048px,
     * so the file is decoded with an inSampleSize chosen from its dimensions —
     * a 12 MP frame read at full size would spike memory for no gain. EXIF
     * rotation is applied because CameraX writes sensor orientation, and a
     * sideways photo reads as a different camera position.
     */
    private fun showSnapshot() {
        val path = currentEvent?.evidencePhotoPath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Snapshot file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Pass 1: dimensions only.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            val opts = BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 2048)
            }
            val rotated = rotateFromExif(path, BitmapFactory.decodeFile(path, opts))

            val imageView = ImageView(this).apply {
                adjustViewBounds = true
                setImageBitmap(rotated)
            }
            AlertDialog.Builder(this)
                .setTitle("Snapshot · ${TimestampDisplay.formatDateTime(currentEvent?.timestamp ?: 0)}")
                .setView(imageView)
                .setPositiveButton("Close", null)
                .show()
        } catch (ex: Exception) {
            Toast.makeText(this, "Could not open snapshot: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Applies the file's EXIF orientation, falling back to the input bitmap. */
    private fun rotateFromExif(path: String, bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val rotation = when (
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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
