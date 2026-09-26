package com.example.trace

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ItemSessionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE — adapter for the Previous Sessions list.
 *
 * One row per session: title (nature of work or a stated fallback), a meta
 * line (start time · duration · counts), and a state pill whose colours use
 * the fixed semantic palette — the same discipline as the event status pills,
 * so "what does green mean" has one answer across the app.
 */
class SessionAdapter(
    private val onItemClick: (Session) -> Unit
) : RecyclerView.Adapter<SessionAdapter.SessionViewHolder>() {

    private var sessions: List<Session> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    fun submit(sessions: List<Session>) {
        this.sessions = sessions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(sessions[position])
    }

    override fun getItemCount(): Int = sessions.size

    inner class SessionViewHolder(private val binding: ItemSessionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: Session) {
            binding.tvTitle.text = session.natureOfWork.ifBlank {
                if (session.legacy) "Legacy timeline (pre-session)" else "Untitled session"
            }

            val duration = session.durationMs?.let { Durations.format(it) }
                ?: Durations.format(System.currentTimeMillis() - session.startedAt)
            val stateWord = when (session.state) {
                Session.STATE_ACTIVE -> "recording"
                else -> session.state.lowercase()
            }
            binding.tvMeta.text =
                "${dateFmt.format(Date(session.startedAt))} · $duration · " +
                "${session.eventCount} event(s)"

            binding.tvStatePill.text = stateWord.uppercase()
            val (bg, fg) = when (session.state) {
                Session.STATE_ACTIVE -> R.color.trace_primary_container to R.color.trace_on_primary_container
                Session.STATE_INTERRUPTED -> R.color.trace_status_unconfirmed_surface to R.color.trace_on_tertiary_container
                Session.STATE_COMPLETED -> R.color.trace_surface_container_high to R.color.trace_on_surface_variant
                else -> R.color.trace_status_unknown_surface to R.color.trace_status_unknown
            }
            binding.tvStatePill.setBackgroundColor(ContextCompat.getColor(binding.root.context, bg))
            binding.tvStatePill.setTextColor(ContextCompat.getColor(binding.root.context, fg))

            binding.root.setOnClickListener { onItemClick(session) }
        }
    }
}
