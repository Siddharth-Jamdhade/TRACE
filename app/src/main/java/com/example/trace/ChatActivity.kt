package com.example.trace

import android.os.Bundle
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
 * TRACE — Cloud AI chat, scoped to one session.
 *
 * The operator asks questions about a recorded session; each request sends the
 * [SessionDigest] (grounding) plus the recent conversation turns to the
 * configured [CloudAi.Provider]. Answers are advisory review text only —
 * nothing said here is ever written to the evidence chain or the database.
 *
 * Deliberately stateless across process death: chat history is an in-memory
 * list, so a killed activity starts a fresh conversation over the same
 * session data. Evidence lives in the chain; the chat is just a lens on it.
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

    private fun send() {
        val question = binding.inputQuestion.text?.toString()?.trim().orEmpty()
        if (question.isEmpty()) return

        val config = CloudAi.loadConfig(this)
        if (config == null) {
            Toast.makeText(this, "Configure Cloud AI in Settings first", Toast.LENGTH_LONG).show()
            startActivity(android.content.Intent(this, CloudAiSettingsActivity::class.java))
            return
        }
        val (provider, apiKey, model) = config
        val s = session
        if (sessionId != ALL_SESSIONS && s == null) {
            Toast.makeText(this, "Session still loading…", Toast.LENGTH_SHORT).show()
            return
        }

        binding.inputQuestion.setText("")
        appendAndRender(AiClient.Message("user", question), isUser = true)
        binding.tvChatStatus.visibility = View.VISIBLE
        binding.tvChatStatus.text = "Asking ${provider.displayName} (${model})…"
        binding.btnSend.isEnabled = false

        scope.launch {
            try {
                // Grounding: rebuilt per request, so answers always reflect the
                // store as it is right now — including events added since the
                // chat started.
                val userTurn = if (s != null) {
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

                // Recent turns keep follow-ups coherent; the digest carries the
                // facts, so only the last few are needed.
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

    private fun appendAndRender(message: AiClient.Message, isUser: Boolean) {
        adapter.add(message, isUser)
        binding.chatRecycler.post {
            binding.chatRecycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
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
 * Minimal chat adapter: bubbles for the user and the assistant, plus a
 * transient "thinking" placeholder while a request is in flight.
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
