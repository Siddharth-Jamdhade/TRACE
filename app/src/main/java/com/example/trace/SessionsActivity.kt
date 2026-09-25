package com.example.trace

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.trace.databinding.ActivitySessionsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TRACE — Previous Sessions.
 *
 * The session-grouped entry point into recorded evidence. One row per session;
 * tapping opens [SessionReviewActivity] for that session (events, chain
 * verification, per-session export, delete and AI chat).
 *
 * Reloads on every resume: a session can end, be recovered or be deleted while
 * this screen sits in the back stack.
 */
class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private lateinit var database: TraceDatabase
    private lateinit var adapter: SessionAdapter
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Previous Sessions"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = TraceDatabase.getInstance(this)

        adapter = SessionAdapter { session ->
            startActivity(
                Intent(this, SessionReviewActivity::class.java)
                    .putExtra(SessionReviewActivity.EXTRA_SESSION_ID, session.id)
            )
        }
        binding.recyclerSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerSessions.adapter = adapter

        loadSessions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sessions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_full_timeline -> {
                startActivity(Intent(this, TimelineActivity::class.java))
                true
            }
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        // Sessions end / get recovered / get deleted while this screen is under
        // another one — the list must never show a stale world.
        loadSessions()
    }

    private fun loadSessions() {
        scope.launch {
            val sessions = database.sessionDao().getAllSessions()
            withContext(Dispatchers.Main) {
                adapter.submit(sessions)
                val empty = sessions.isEmpty()
                binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                binding.recyclerSessions.visibility = if (empty) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
