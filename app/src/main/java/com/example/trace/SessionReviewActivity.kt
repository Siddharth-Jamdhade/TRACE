package com.example.trace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.trace.databinding.ActivitySessionReviewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * TRACE — per-session review.
 *
 * The screen the "Previous Sessions" list opens: everything about one session
 * in one place — header, chain + header verification, its events, the
 * session-scoped AI chat, a per-session JSON export and a delete that removes
 * the evidence files along with the rows.
 *
 * Deletion rules, both enforced here rather than trusted to the caller:
 *   - an ACTIVE session is never deletable (stop the recording first), and
 *   - Evidence Lock blocks deletion, same as it blocks every other write —
 *     a locked timeline is sealed against modification in either direction.
 */
class SessionReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionReviewBinding
    private lateinit var database: TraceDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var adapter: EventAdapter

    private var sessionId: Long = -1L
    private var session: Session? = null
    private var loadedEvents: List<Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Session review"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        database = TraceDatabase.getInstance(this)

        adapter = EventAdapter { event ->
            startActivity(
                Intent(this, EventDetailActivity::class.java)
                    .putExtra("eventId", event.id)
            )
        }
        binding.recyclerEvents.layoutManager = LinearLayoutManager(this)
        binding.recyclerEvents.adapter = adapter

        binding.btnChat.setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_SESSION_ID, sessionId)
            )
        }

        loadSession()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the AI chat or the event detail screen may have
        // changed nothing, but Confirm/Reject in detail can — re-render is cheap.
        if (session != null) loadSession()
    }

    private fun loadSession() {
        scope.launch {
            val s = database.sessionDao().getSessionById(sessionId)
            if (s == null) {
                withContext(Dispatchers.Main) { finish() }
                return@launch
            }

            val events = database.eventDao().getEventsForSession(sessionId)
            val chainValid = HashChain.verifySession(s, events)
            val headerValid = s.legacy || s.sessionHash == HashChain.computeSessionHash(s)

            withContext(Dispatchers.Main) {
                session = s
                loadedEvents = events
                render(s, events, chainValid && headerValid)
            }
        }
    }

    private fun render(s: Session, events: List<Event>, valid: Boolean) {
        val header = SessionDigest.buildHeader(s)
        val stats = SessionDigest.buildStats(s, events)
        binding.tvHeader.text = "$header\n$stats"

        binding.tvIntegrity.text = if (valid) {
            "Integrity: VALID — header bound, chain intact (${events.size} events)"
        } else {
            "Integrity: INVALID — this session's evidence may have been tampered with"
        }
        binding.tvIntegrity.setTextColor(
            ContextCompat.getColor(
                this,
                if (valid) R.color.trace_integrity_ok else R.color.trace_integrity_broken
            )
        )

        adapter.submitList(events.sortedByDescending { it.timestamp })
        // Menu visibility depends on the now-loaded session; force re-prepare.
        invalidateOptionsMenu()
    }

    // ── Menu: per-session export + delete ─────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_session_review, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Delete is removed entirely for the live session (stop the recording
        // first) and for the legacy timeline (whose rows predate sessions and
        // whose deletion is the Timeline's job).
        val s = session
        menu.findItem(R.id.action_delete_session)?.isVisible =
            s != null && s.state != Session.STATE_ACTIVE && !s.legacy
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_export_session -> { exportSession(); true }
            R.id.action_delete_session -> { confirmDelete(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Per-session export ────────────────────────────────────────────────

    /**
     * Writes this session's events to JSON and opens the share sheet.
     *
     * Written under the session's own name so exports from different sessions
     * cannot overwrite each other; the wrapper carries the session header and
     * verification verdict so the laptop viewer can show provenance, not just
     * rows.
     */
    private fun exportSession() {
        val s = session ?: return
        scope.launch {
            try {
                val events = database.eventDao().getEventsForSession(s.id)
                val array = JSONArray()
                for (e in events.sortedBy { it.timestamp }) {
                    array.put(JSONObject().apply {
                        put("id", e.id)
                        put("type", e.type)
                        put("timestamp", e.timestamp)
                        put("timestamp_human", SessionDigest.formatTime(e.timestamp))
                        put("source", e.source)
                        put("confidence", "%.2f".format(e.confidence))
                        put("status", e.status)
                        put("camera_confidence", e.cameraConfidence?.let { "%.2f".format(it) } ?: "null")
                        put("audio_confidence", e.audioConfidence?.let { "%.2f".format(it) } ?: "null")
                        put("motion_confidence", e.motionConfidence?.let { "%.2f".format(it) } ?: "null")
                        put("sensor_breakdown", e.sensorBreakdown ?: "none")
                        put("evidence_clip", e.evidenceClipPath ?: "none")
                        put("clip_hash", e.clipHash ?: "none")
                        put("evidence_photo", e.evidencePhotoPath ?: "none")
                        put("photo_hash", e.photoHash ?: "none")
                        put("hash", e.hash ?: "none")
                        put("previous_hash", e.previousHash ?: "none")
                    })
                }

                val wrapper = JSONObject().apply {
                    put("exported_at", SessionDigest.formatTime(System.currentTimeMillis()))
                    put("device", android.os.Build.MODEL)
                    put("session_id", s.id)
                    put("nature_of_work", s.natureOfWork)
                    put("state", s.state)
                    put("session_hash", s.sessionHash)
                    put("event_count", events.size)
                    put("events", array)
                }

                val dir = File(getExternalFilesDir(null), "TRACE").also { it.mkdirs() }
                val file = File(dir, "session_${s.id}_events.json")
                file.writeText(wrapper.toString(2))

                withContext(Dispatchers.Main) { shareFile(file) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SessionReviewActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Share sheet for an exported JSON file, via FileProvider. */
    private fun shareFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this, "${applicationInfo.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share session export"))
    }

    // ── Deletion ──────────────────────────────────────────────────────────

    private fun confirmDelete() {
        val s = session ?: return
        if (s.state == Session.STATE_ACTIVE || s.legacy) return

        if (EvidenceLock.isLocked(this)) {
            Toast.makeText(this, "Evidence Lock is on — unlock to delete", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete this session?")
            .setMessage(
                "\"${s.natureOfWork.ifBlank { "Untitled" }}\" — ${loadedEvents.size} event(s), " +
                    "including their audio clips and snapshots, will be permanently deleted.\n\n" +
                    "This cannot be undone. Other sessions are unaffected and stay verifiable."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteSession() }
            .show()
    }

    private fun deleteSession() {
        scope.launch {
            val s = session ?: return@launch
            val events = database.eventDao().getEventsForSession(s.id)
            // Files first, then rows: if the process dies between the two, the
            // worst case is orphaned rows (visible, verifiable, deletable
            // again) rather than orphaned files pretending to be evidence.
            val removedFiles = EvidenceFiles.deleteEventFiles(events)
            database.eventDao().deleteEventsForSession(s.id)
            database.sessionDao().deleteSession(s.id)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SessionReviewActivity,
                    "Session deleted (${events.size} events, $removedFiles files)",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
