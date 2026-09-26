package com.example.trace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ItemEventBinding

/**
 * TRACE — RecyclerView adapter for the incident timeline.
 *
 * Uses [ListAdapter] + [DiffUtil] for efficient, animated list updates.
 * Status colours: CONFIRMED = green, UNCONFIRMED = amber, REJECTED = red,
 * MANUAL (operator assertion) = violet.
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
            binding.tvStatus.text    = event.status

            // Fixed semantic colours — a status badge must not change colour with
            // the user's wallpaper palette.
            val statusColorRes = when (event.status) {
                "CONFIRMED"   -> R.color.trace_status_confirmed
                "UNCONFIRMED" -> R.color.trace_status_unconfirmed
                "REJECTED"    -> R.color.trace_status_rejected
                "MANUAL"      -> R.color.trace_status_manual
                else          -> R.color.trace_status_unknown
            }
            val (stripColor, badgeTextColor) = Pair(
                ContextCompat.getColor(binding.root.context, statusColorRes),
                ContextCompat.getColor(binding.root.context, statusColorRes)
            )

            binding.statusStrip.setBackgroundColor(stripColor)
            binding.tvStatus.setTextColor(badgeTextColor)

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
