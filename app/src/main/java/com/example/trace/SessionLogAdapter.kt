package com.example.trace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ItemLogLineBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE — adapter for the dashboard's live session log tail.
 *
 * Renders [SessionLog.Line] entries oldest-first (newest at the bottom, next to
 * the action buttons). Colours are the timeline's fixed semantic palette — a
 * green line means CONFIRMED in both places, and a violet one is always a human
 * assertion — because a colour means nothing if it changes between screens.
 */
class SessionLogAdapter : RecyclerView.Adapter<SessionLogAdapter.LogViewHolder>() {

    private var entries: List<SessionLog.Line> = emptyList()

    /** Replaces the whole list; the buffer is small, so a full rebind is fine. */
    fun submit(lines: List<SessionLog.Line>) {
        entries = lines
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class LogViewHolder(private val binding: ItemLogLineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun bind(line: SessionLog.Line) {
            binding.tvLogLine.text = "${timeFmt.format(Date(line.timestamp))}  ${line.text}"
            binding.tvLogLine.setTextColor(
                ContextCompat.getColor(binding.root.context, colorFor(line.kind))
            )
        }

        private fun colorFor(kind: SessionLog.Kind): Int = when (kind) {
            SessionLog.Kind.EVENT     -> R.color.trace_status_unconfirmed
            SessionLog.Kind.TAG       -> R.color.trace_status_manual
            SessionLog.Kind.LIFECYCLE -> R.color.trace_secondary
            SessionLog.Kind.ERROR     -> R.color.trace_status_rejected
            SessionLog.Kind.INFO      -> R.color.trace_on_surface_variant
        }
    }
}
