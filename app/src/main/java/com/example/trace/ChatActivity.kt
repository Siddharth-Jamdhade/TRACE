package com.example.trace

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ActivityChatBinding
import com.example.trace.databinding.ItemChatMsgBinding
import com.google.android.material.color.MaterialColors
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private lateinit var adapter: ChatAdapter
    private lateinit var markwon: Markwon

    private var sessionId: Long = -1L
    private var session: Session? = null

    /** True = use on-device LocalAiClient; false = use cloud AiClient. */
    private var useLocal: Boolean = LocalAiConfig.isConfigured(this)

    /** Animation job for the typing-indicator dots. */
    private var typingDotsJob: kotlinx.coroutines.Job? = null

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "AI Chat"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        database = TraceDatabase.getInstance(this)

        // ── Markwon (markdown renderer) ───────────────────────────────────
        markwon = Markwon.builder(this)
            .build()

        adapter = ChatAdapter(markwon, timeFormat)

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
            showStatus("Switched to cloud AI")
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

            // Auto-load model on first switch
            if (!LocalAiClient.isLoaded) {
                showStatus("Loading local model…")
                scope.launch {
                    try {
                        val (path, name) = LocalAiConfig.loadConfig(this@ChatActivity)!!
                        LocalAiClient.load(this@ChatActivity, path)
                        withContext(Dispatchers.Main) {
                            showStatus("Switched to local AI ($name)")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showStatus("Failed to load model: ${e.message}")
                            useLocal = false
                            item.setTitle("Use local AI")
                            item.isChecked = false
                        }
                    }
                }
            } else {
                showStatus("Switched to local AI")
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
        setInputEnabled(false)
        showTypingIndicator(true)

        if (useLocal) {
            sendLocal(question, s)
        } else {
            sendCloud(question, s)
        }
    }

    private fun sendCloud(question: String, s: Session?) {
        val config = CloudAi.loadConfig(this)
        if (config == null) {
            showStatus("Cloud AI not configured")
            Toast.makeText(this, "Configure Cloud AI in Settings first", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, CloudAiSettingsActivity::class.java))
            setInputEnabled(true)
            showTypingIndicator(false)
            return
        }
        val (provider, apiKey, model) = config
        showStatus("Asking ${provider.displayName} (${model})…")

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
                    showTypingIndicator(false)
                    hideStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus("Failed: ${e.message}")
                    Toast.makeText(this@ChatActivity, "Request failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    setInputEnabled(true)
                    showTypingIndicator(false)
                }
            }
        }
    }

    private fun sendLocal(question: String, s: Session?) {
        if (!LocalAiClient.isLoaded) {
            showStatus("Loading local model…")
            scope.launch {
                try {
                    val (path, _) = LocalAiConfig.loadConfig(this@ChatActivity)!!
                    LocalAiClient.load(this@ChatActivity, path)
                    performLocalSend(question, s)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showStatus("Failed to load model: ${e.message}")
                        setInputEnabled(true)
                        showTypingIndicator(false)
                    }
                }
            }
        } else {
            performLocalSend(question, s)
        }
    }

    private fun performLocalSend(question: String, s: Session?) {
        showStatus("Thinking (local)…")

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
                    showTypingIndicator(false)
                    hideStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus("Local AI failed: ${e.message}")
                    Toast.makeText(this@ChatActivity, "Local AI failed", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    setInputEnabled(true)
                    showTypingIndicator(false)
                }
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
        adapter.add(message.content, isUser, System.currentTimeMillis())
        binding.chatRecycler.post {
            binding.chatRecycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    // ── UI helpers ──────────────────────────────────────────────────────

    private fun setInputEnabled(enabled: Boolean) {
        binding.btnSend.isEnabled = enabled
        binding.inputQuestion.isEnabled = enabled
    }

    private fun showStatus(text: String) {
        binding.tvChatStatus.visibility = View.VISIBLE
        binding.tvChatStatus.text = text
    }

    private fun hideStatus() {
        binding.tvChatStatus.visibility = View.GONE
    }

    private fun showTypingIndicator(visible: Boolean) {
        if (visible) {
            binding.typingIndicator.visibility = View.VISIBLE
            // Animate the dots
            typingDotsJob?.cancel()
            typingDotsJob = scope.launch {
                val dots = listOf(".", "..", "...")
                var i = 0
                while (true) {
                    withContext(Dispatchers.Main) {
                        binding.tvTypingDots.text = dots[i]
                    }
                    i = (i + 1) % dots.size
                    delay(500)
                }
            }
        } else {
            binding.typingIndicator.visibility = View.GONE
            typingDotsJob?.cancel()
            typingDotsJob = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        typingDotsJob?.cancel()
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
 * Chat adapter with Markdown rendering for AI responses, proper
 * left/right bubble alignment, and timestamps.
 */
class ChatAdapter(
    private val markwon: Markwon,
    private val timeFormat: SimpleDateFormat
) : RecyclerView.Adapter<ChatAdapter.VH>() {

    private data class Entry(
        val text: String,
        val isUser: Boolean,
        val timestampMs: Long
    )

    private val entries = mutableListOf<Entry>()

    fun add(text: String, isUser: Boolean, timestampMs: Long) {
        entries.add(Entry(text, isUser, timestampMs))
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
        holder.bind(e.text, e.isUser, e.timestampMs)
    }

    override fun getItemCount(): Int = entries.size

    inner class VH(private val binding: ItemChatMsgBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(text: String, isUser: Boolean, timestampMs: Long) {
            val ctx = binding.root.context

            // ── Render text ──────────────────────────────────────────────
            if (isUser) {
                // Plain text for user messages (no markdown needed).
                // Clear any previous markdown spans by setting raw text.
                binding.tvBubble.text = text
            } else {
                // Render markdown for AI responses (bold, italic, lists,
                // code blocks, headers, inline code, etc.).
                markwon.setMarkdown(binding.tvBubble, text)
            }

            // ── Align bubble (left for AI, right for user) ───────────────
            val lp = binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (isUser) Gravity.END else Gravity.START
            binding.bubbleContainer.layoutParams = lp

            // ── Bubble background tint ───────────────────────────────────
            val bgRes = if (isUser) R.drawable.bubble_sender else R.drawable.bubble_receiver
            val bgColor = MaterialColors.getColor(
                binding.tvBubble,
                if (isUser) com.google.android.material.R.attr.colorPrimaryContainer
                else com.google.android.material.R.attr.colorSurfaceVariant
            )
            val textColor = MaterialColors.getColor(
                binding.tvBubble,
                if (isUser) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
            binding.tvBubble.setTextColor(textColor)

            val bg = ContextCompat.getDrawable(ctx, bgRes)?.mutate()
            bg?.let { DrawableCompat.setTint(it, bgColor) }
            binding.tvBubble.background = bg

            // ── Timestamp ────────────────────────────────────────────────
            val formatted = timeFormat.format(Date(timestampMs))
            binding.tvTimestamp.text = formatted
            binding.tvTimestamp.gravity = if (isUser) Gravity.END else Gravity.START
            binding.tvTimestamp.visibility = View.VISIBLE
        }
    }
}