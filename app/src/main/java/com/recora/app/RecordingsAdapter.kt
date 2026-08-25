package com.recora.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Адаптер списку записаних відео.
 * Тап — перегляд, довгий тап — видалення.
 */
class RecordingsAdapter(
    private var items: List<Recording>,
    private val onClick: (Recording) -> Unit,
    private val onLongClick: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textName)
        val info: TextView = view.findViewById(R.id.textInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name.removeSuffix(".mp4")
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            .format(Date(item.dateMillis))
        holder.info.text = holder.itemView.context.getString(
            R.string.recording_info, formatSize(item.sizeBytes), date
        )
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    @Suppress("NotifyDataSetChanged")
    fun submit(newItems: List<Recording>) {
        items = newItems
        notifyDataSetChanged() // для маленького списку достатньо
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576f)
        bytes >= 1_024L -> "%.0f КБ".format(bytes / 1_024f)
        else -> "$bytes Б"
    }
}
