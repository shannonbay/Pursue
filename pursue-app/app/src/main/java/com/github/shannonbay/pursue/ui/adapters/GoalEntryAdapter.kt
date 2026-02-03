package com.github.shannonbay.pursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.models.GoalEntryUiModel

/**
 * RecyclerView adapter for displaying progress entries in Goal Detail screen.
 * Supports long press on own entries to show edit/delete options.
 */
class GoalEntryAdapter(
    private val entries: List<GoalEntryUiModel>,
    private val currentUserId: String,
    private val onLongPress: (GoalEntryUiModel) -> Unit = {}
) : RecyclerView.Adapter<GoalEntryAdapter.EntryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_goal_entry, parent, false)
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        val entry = entries[position]
        holder.bind(entry, currentUserId, onLongPress)
    }

    override fun getItemCount(): Int = entries.size

    class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateHeader: TextView = itemView.findViewById(R.id.date_header)
        private val checkmarkIcon: TextView = itemView.findViewById(R.id.checkmark_icon)
        private val memberName: TextView = itemView.findViewById(R.id.member_name)
        private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
        private val note: TextView = itemView.findViewById(R.id.note)

        fun bind(
            entry: GoalEntryUiModel,
            currentUserId: String,
            onLongPress: (GoalEntryUiModel) -> Unit
        ) {
            // Show date header if present
            if (entry.date_header != null) {
                dateHeader.visibility = View.VISIBLE
                dateHeader.text = entry.date_header
            } else {
                dateHeader.visibility = View.GONE
            }

            // Member name - show "You" for current user
            memberName.text = if (entry.is_current_user) {
                "You"
            } else {
                entry.display_name
            }

            // Timestamp
            timestamp.text = entry.formatted_timestamp

            // Note (if present)
            if (!entry.note.isNullOrBlank()) {
                note.visibility = View.VISIBLE
                note.text = entry.note
            } else {
                note.visibility = View.GONE
            }

            // Long press listener for own entries
            if (entry.is_current_user) {
                itemView.setOnLongClickListener {
                    onLongPress(entry)
                    true
                }
            } else {
                itemView.setOnLongClickListener(null)
                itemView.isLongClickable = false
            }
        }
    }
}
