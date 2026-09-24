package com.example.trace

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.trace.databinding.ActivityTimelineBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE -- Timeline screen.
 *
 * Shows all stored events newest-first with colour-coded status badges.
 * A query bar at the top routes free-text questions through [QueryEngine].
 *
 * Options menu:
 *   Refresh        -- reload from DB
 *   Export JSON    -- write events.json to Downloads and open Share sheet
 *   Clear All      -- wipe the DB (ask before demo!)
 */
class TimelineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimelineBinding
    private lateinit var database: TraceDatabase
    private lateinit var adapter: EventAdapter
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimelineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "TRACE -- Incident Timeline"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = TraceDatabase.getInstance(this)

        adapter = EventAdapter { event ->
            startActivity(
                Intent(this, EventDetailActivity::class.java)
                    .putExtra("eventId", event.id)
            )
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnAsk.setOnClickListener { runQuery() }

        binding.queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { runQuery(); true } else false
        }

        // Feature C: Lock Evidence button
        val prefs = getSharedPreferences("trace_prefs", MODE_PRIVATE)
        val alreadyLocked = prefs.getBoolean("evidence_locked", false)
        if (alreadyLocked) {
            binding.tvLockedBanner.visibility = View.VISIBLE
            binding.btnLockEvidence.isEnabled = false
            binding.btnLockEvidence.text = "🔒 LOCKED"
        }

        binding.btnLockEvidence.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🔒 Lock Evidence?")
                .setMessage("This will permanently stop recording new events. The current timeline will be sealed and cannot be modified.\n\nProceed?")
                .setPositiveButton("LOCK IT") { _, _ ->
                    prefs.edit().putBoolean("evidence_locked", true).apply()
                    binding.tvLockedBanner.visibility = View.VISIBLE
                    binding.btnLockEvidence.isEnabled = false
                    binding.btnLockEvidence.text = "🔒 LOCKED"
                    Toast.makeText(this, "Evidence chain locked. No new events will be recorded.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadEvents()
    }

    // ------ Data --------------------------------------------------------

    private fun loadEvents() {
        scope.launch {
            val events = database.eventDao().getAllEvents()
            withContext(Dispatchers.Main) {
                adapter.submitList(events.sortedByDescending { it.timestamp })
                if (events.isEmpty()) {
                    binding.queryAnswer.text =
                        "No events yet.\nTrigger a physical incident on the main screen\nto build the timeline."
                    binding.queryAnswer.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun runQuery() {
        val question = binding.queryInput.text.toString().trim()
        if (question.isEmpty()) return
        scope.launch {
            val events = database.eventDao().getAllEvents()
            val answer = QueryEngine.answerQuery(question, events)
            withContext(Dispatchers.Main) {
                binding.queryAnswer.text = answer
                binding.queryAnswer.visibility = View.VISIBLE
            }
        }
    }

    // ------ Office Kit export ------------------------------------------

    /**
     * Serialises all events to JSON and opens the Android Share sheet.
     *
     * On demo day:
     *   Phone  ->  Share  ->  "Save to Files" or "Send via USB"
     *   Laptop ->  Open trace_viewer.html  ->  drag-drop the JSON file
     *
     * The JSON is also written to getExternalFilesDir()/TRACE/events.json
     * so it is accessible from a file manager without sharing.
     */
    private fun exportJson() {
        scope.launch {
            try {
                val events = database.eventDao().getAllEvents()
                val array  = JSONArray()

                for (e in events.sortedBy { it.timestamp }) {
                    val obj = JSONObject().apply {
                        put("id",               e.id)
                        put("type",             e.type)
                        put("timestamp",        e.timestamp)
                        put("timestamp_human",  dateFmt.format(Date(e.timestamp)))
                        put("source",           e.source)
                        put("confidence",       "%.2f".format(e.confidence))
                        put("status",           e.status)
                        put("camera_confidence", e.cameraConfidence?.let { "%.2f".format(it) } ?: "null")
                        put("audio_confidence",  e.audioConfidence?.let  { "%.2f".format(it) } ?: "null")
                        put("motion_confidence", e.motionConfidence?.let { "%.2f".format(it) } ?: "null")
                        put("evidence_clip",    e.evidenceClipPath ?: "none")
                        put("hash",             e.hash ?: "none")
                        put("previous_hash",    e.previousHash ?: "none")
                    }
                    array.put(obj)
                }

                val wrapper = JSONObject().apply {
                    put("exported_at",    dateFmt.format(Date()))
                    put("device",         android.os.Build.MODEL)
                    put("event_count",    events.size)
                    put("events",         array)
                }

                val json = wrapper.toString(2)   // pretty-print with 2-space indent

                // Write to external files dir (no permission needed, API 29+)
                val dir  = File(getExternalFilesDir(null), "TRACE").also { it.mkdirs() }
                val file = File(dir, "events.json")
                file.writeText(json)

                // Also write to cache so FileProvider can share it
                val cacheFile = File(cacheDir, "events.json")
                cacheFile.writeText(json)

                val uri: Uri = FileProvider.getUriForFile(
                    this@TimelineActivity,
                    "${packageName}.fileprovider",
                    cacheFile
                )

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "TRACE Timeline Export")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Export TRACE Timeline"))

                    Toast.makeText(
                        this@TimelineActivity,
                        "Exported ${events.size} events  |  Also saved to TRACE/events.json",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TimelineActivity, "Export failed: ${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------ Lifecycle ---------------------------------------------------

    override fun onResume() {
        super.onResume()
        loadEvents()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, 1, "Refresh")
        menu.add(Menu.NONE, 2, 2, "Export JSON (Office Kit)")
        menu.add(Menu.NONE, 3, 3, "Clear All Events")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> { loadEvents(); true }
            2 -> { exportJson(); true }
            3 -> {
                scope.launch {
                    database.eventDao().clearAll()
                    withContext(Dispatchers.Main) {
                        adapter.submitList(emptyList())
                        binding.queryAnswer.text = "All events cleared."
                        binding.queryAnswer.visibility = View.VISIBLE
                        Toast.makeText(this@TimelineActivity, "Timeline cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}