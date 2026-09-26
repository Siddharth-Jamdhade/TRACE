package com.example.trace

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.trace.databinding.ItemSensorLogBinding

/**
 * TRACE — RecyclerView adapter for Tier 1 continuous sensor log entries.
 *
 * Each row shows the sensor icon, source name, timestamp, and the raw
 * numeric value — no confidence, no status, just the reading.
 */
class SensorLogAdapter : ListAdapter<SensorLog, SensorLogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemSensorLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(private val binding: ItemSensorLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: SensorLog) {
            binding.tvIcon.text = SensorRegistry.iconFor(log.source)
            binding.tvSource.text = SensorRegistry.labelFor(log.source)
            binding.tvTimestamp.text = TimestampDisplay.formatTime(log.timestamp)
            binding.tvValue.text = "%.4f".format(log.value)
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<SensorLog>() {
        override fun areItemsTheSame(oldItem: SensorLog, newItem: SensorLog): Boolean =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: SensorLog, newItem: SensorLog): Boolean =
            oldItem == newItem
    }
}