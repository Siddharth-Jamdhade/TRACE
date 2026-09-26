package com.example.trace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ItemEventBinding

/**
 * TRACE — RecyclerView adapter for the incident timeline.
 *
 * Uses [ListAdapter] + [DiffUtil] for efficient, animated list updates.
 * Each row shows the sensor icon, source label, timestamp, and confidence
 * — no status badge or colour strip, since every entry is a raw deviation.
 */
class EventAdapter(
    private val onItemClick: (Event) -> Unit
) : ListAdapter<Event, EventAdapter.EventViewHolder>(EventDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class EventViewHolder(private val binding: ItemEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(event: Event) {
            // Source icon (full mapping lives in SensorRegistry)
            binding.tvIcon.text = SensorRegistry.iconFor(event.source)
            binding.tvEventType.text = event.type.replace("_", " ")
                .replaceFirstChar { it.uppercase() }
            binding.tvTimestamp.text = TimestampDisplay.formatTime(event.timestamp)
            // A human assertion has no sensor confidence behind it, so printing a
            // percentage would dress a claim up as a measurement. Say what it is.
            binding.tvSource.text = if (event.source == SensorRegistry.MANUAL.id) {
                "src: manual  |  operator tag"
            } else {
                "src: ${event.source}  |  ${"%.0f".format(event.confidence * 100)}%"
            }

            binding.root.setOnClickListener { onItemClick(event) }
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean =
            oldItem == newItem
    }
}