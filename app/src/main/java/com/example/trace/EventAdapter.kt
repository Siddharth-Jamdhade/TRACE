package com.example.trace

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ItemEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TRACE — RecyclerView adapter for the incident timeline.
 *
 * Uses [ListAdapter] + [DiffUtil] for efficient, animated list updates.
 * Status colours: CONFIRMED = green, UNCONFIRMED = amber, REJECTED = red.
 */
class EventAdapter(
    private val onItemClick: (Event) -> Unit
) : ListAdapter<Event, EventAdapter.EventViewHolder>(EventDiffCallback()) {

    private val timeFmt = SimpleDateFormat("MMM dd  HH:mm:ss", Locale.getDefault())

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
            binding.tvTimestamp.text = timeFmt.format(Date(event.timestamp))
            binding.tvSource.text    = "src: ${event.source}  |  ${"%.0f".format(event.confidence * 100)}%"
            binding.tvStatus.text    = event.status

            val (stripColor, badgeTextColor) = when (event.status) {
                "CONFIRMED"   -> Pair(Color.parseColor("#00E676"), Color.parseColor("#00E676"))
                "UNCONFIRMED" -> Pair(Color.parseColor("#FFD600"), Color.parseColor("#FFD600"))
                "REJECTED"    -> Pair(Color.parseColor("#F85149"), Color.parseColor("#F85149"))
                else          -> Pair(Color.parseColor("#484F58"), Color.parseColor("#484F58"))
            }

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
