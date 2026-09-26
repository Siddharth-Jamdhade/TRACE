package com.example.trace

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ActivityChatBinding
import com.example.trace.databinding.ItemChatMsgBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TRACE — AI chat (Cloud or Local), scoped to one session.
 *
 * The operator asks questions about a recorded session; each request sends the
 * [SessionDigest] (grounding) plus the recent conversation turns to either the
 * configured [CloudAi.Provider] or the on-device [LocalAiClient] (Qwen 3 4B).
 * Use the toolbar menu to toggle between cloud and local inference.
 *
 * Answers are advisory review text only — nothing said here is ever written to
 * the evidence chain or the database. Deliberately stateless across process
 * death: chat history is an in-memory list, so a killed activity starts a
 * fresh conversation over the same session data.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var database: TraceDatabase
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** In-memory conversation, oldest first. */
    private val history = ArrayDeque<AiClient.Message>()

    private val adapter = ChatAdapter()

    private var sessionId: Long = -1L
    private var session: Session? = null

    /** True = use on-device LocalAiClient; false = use cloud AiClient. */
    private var useLocal: Boolean = LocalAiConfig.isConfigured(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "AI Chat"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        database = TraceDatabase.getInstance(this)

        binding.chatRecycler.layoutManager = LinearLayoutManager(this)
        binding.chatRecycler.adapter = adapter

        binding.btnSend.setOnClickListener { send() }

        scope.launch {
            if (sessionId == ALL_SESSIONS) {
                withContext(Dispatchers.Main) {
                    binding.tvSessionScope.text =
                        "Grounded in ALL recorded sessions (every session's log)"
                }
            } else {
                session = database.sessionDao().getSessionById(sessionId)
                withContext(Dispatchers.Main) {
                    val s = session
                    if (s == null) {
                        Toast.makeText(this@ChatActivity, "Session not found", Toast.LENGTH_SHORT).show()
                        finish()
                        return@withContext
                    }
                    binding.tvSessionScope.text =
                        "Grounded in session #${s.id} — \"${s.natureOfWork.ifBlank { "untitled" }}\""
                }
            }
        }
    }

    // ── Toolbar menu (cloud / local toggle) ─────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_chat, menu)
        val item = menu.findItem(R.id.action_toggle_ai)
        if (useLocal) {
            item.setTitle("Use cloud AI")
            item.isChecked = true
        } else {
            item.setTitle("Use local AI")
            item.isChecked = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.action_toggle_ai -> {
                toggleAiMode(item)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleAiMode(item: MenuItem) {
        if (useLocal) {
            // Switch to cloud
            useLocal = false
            item.setTitle("Use local AI")
            item.isChecked = false
            binding.tvChatStatus.visibility = View.VISIBLE
            binding.tvChatStatus.text = "Switched to cloud AI"
        } else {
            // Switch to local
            if (!LocalAiConfig.isConfigured(this)) {
                Toast.makeText(this, "No local model configured — go to Settings > Local AI", Toast.LENGTH_LONG).show()
                startActivity(android.content.Intent(this, LocalAiSettingsActivity::class.java))
                return
            }
            useLocal = true
            item.setTitle("Use cloud AI")
            item.isChecked = true
            binding.tvChatStatus.visibility = View.VISIBLE

            // Auto-load model on first switch
            if (!LocalAiClient.isLoaded) {
                binding.tvChatStatus.text = "Loading local model…"
                scope.launch {
                    try {
                        val (path, name) = LocalAiConfig.loadConfig(this@ChatActivity)!!
                        LocalAiClient.load(this@ChatActivity, path)
                        withContext(Dispatchers.Main) {
                            binding.tvChatStatus.text = "Switched to local AI ($name)"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            binding.tvChatStatus.text = "Failed to load model: ${e.message}"
                            useLocal = false
                            item.setTitle("Use local AI")
                            item.isChecked = false
                        }
                    }
                }
            } else {
                binding.tvChatStatus.text = "Switched to local AI"
            }
        }
    }

    // ── Send ────────────────────────────────────────────────────────────

    private fun send() {
        val question = binding.inputQuestion.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) return

        val s = session
        if (sessionId != ALL_SESSIONS && s == null) {
            Toast.makeText(this, "Session still loading…", Toast.LENGTH_SHORT).show()
            return
        }

        binding.inputQuestion.setText("")
        appendAndRender(AiClient.Message("user", question), isUser = true)
        binding.tvChatStatus.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false

        if (useLocal) {
            sendLocal(question, s)
        } else {
            sendCloud(question, s)
        }
    }

    private fun sendCloud(question: String, s: Session?) {
        val config = CloudAi.loadConfig(this)
        if (config == null) {
            binding.tvChatStatus.text = "Cloud AI not configured"
            Toast.makeText(this, "Configure Cloud AI in Settings first", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, CloudAiSettingsActivity::class.java))
            binding.btnSend.isEnabled = true
            return
        }
        val (provider, apiKey, model) = config
        binding.tvChatStatus.text = "Asking ${provider.displayName} (${model})…"

        scope.launch {
            try {
                val userTurn = buildUserTurn(question, s)
                val recent = history.takeLast(6)
                val messages = buildList {
                    add(AiClient.Message("system", SessionDigest.buildSystemPrompt()))
                    recent.forEach { add(it) }
                    add(AiClient.Message("user", userTurn))
                }

                val answer = AiClient.chat(provider, apiKey, model, messages)

                withContext(Dispatchers.Main) {
                    history.addLast(AiClient.Message("user", question))
                    history.addLast(AiClient.Message("assistant", answer))
                    appendAndRender(AiClient.Message("assistant", answer), isUser = false)
                    binding.tvChatStatus.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvChatStatus.visibility = View.VISIBLE
                    binding.tvChatStatus.text = "Failed: ${e.message}"
                    Toast.makeText(this@ChatActivity, "Request failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { binding.btnSend.isEnabled = true }
            }
        }
    }

    private fun sendLocal(question: String, s: Session?) {
        if (!LocalAiClient.isLoaded) {
            binding.tvChatStatus.text = "Local model not loaded — loading…"
            scope.launch {
                try {
                    val (path, _) = LocalAiConfig.loadConfig(this@ChatActivity)!!
                    LocalAiClient.load(this@ChatActivity, path)
                    // Now proceed with the actual send
                    performLocalSend(question, s)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.tvChatStatus.text = "Failed to load model: ${e.message}"
                        binding.btnSend.isEnabled = true
                    }
                }
            }
        } else {
            performLocalSend(question, s)
        }
    }

    private fun performLocalSend(question: String, s: Session?) {
        binding.tvChatStatus.text = "Thinking (local)…"

        scope.launch {
            try {
                val userTurn = buildUserTurn(question, s)
                val recent = history.takeLast(6)
                val messages = buildList {
                    add(LocalAiClient.Message("system", SessionDigest.buildSystemPrompt()))
                    recent.forEach { add(LocalAiClient.Message(it.role, it.content)) }
                    add(LocalAiClient.Message("user", userTurn))
                }

                val answer = LocalAiClient.chat(SessionDigest.buildSystemPrompt(), messages)

                withContext(Dispatchers.Main) {
                    history.addLast(AiClient.Message("user", question))
                    history.addLast(AiClient.Message("assistant", answer))
                    appendAndRender(AiClient.Message("assistant", answer), isUser = false)
                    binding.tvChatStatus.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvChatStatus.visibility = View.VISIBLE
                    binding.tvChatStatus.text = "Local AI failed: ${e.message}"
                    Toast.makeText(this@ChatActivity, "Local AI failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) { binding.btnSend.isEnabled = true }
            }
        }
    }

    /** Builds the grounded user turn from session data + question. */
    private suspend fun buildUserTurn(question: String, s: Session?): String {
        return if (s != null) {
            val events = database.eventDao().getEventsForSession(s.id)
            SessionDigest.buildUserTurn(SessionDigest.buildDigest(s, events), question)
        } else {
            val sessions = database.sessionDao().getAllSessions()
            val bySession = sessions.associate {
                it.id to database.eventDao().getEventsForSession(it.id)
            }
            SessionDigest.buildUserTurn(
                SessionDigest.buildAllSessionsDigest(sessions, bySession), question
            )
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private fun appendAndRender(message: AiClient.Message, isUser: Boolean) {
        adapter.add(message, isUser)
        binding.chatRecycler.post {
            binding.chatRecycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        LocalAiClient.unload()
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"

        /** Sentinel for the Timeline entry point: chat over every session. */
        const val ALL_SESSIONS = -1L

        /** Convenience launch from the Timeline (all sessions in scope). */
        fun intentForAllSessions(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, ChatActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, ALL_SESSIONS)
    }
}

/**
 * Minimal chat adapter: bubbles for the user and the assistant.
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private data class Entry(val text: String, val isUser: Boolean)

    private val entries = mutableListOf<Entry>()

    fun add(message: AiClient.Message, isUser: Boolean) {
        entries.add(Entry(message.content, isUser))
        notifyItemInserted(entries.size - 1)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val binding = ItemChatMsgBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = entries[position]
        holder.bind(e.text, e.isUser)
    }

    override fun getItemCount(): Int = entries.size

    class VH(private val binding: ItemChatMsgBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(text: String, isUser: Boolean) {
            binding.tvBubble.text = text
            binding.tvBubble.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
        }
    }
}