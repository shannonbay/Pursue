package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.models.GoalEntryUiModel
import app.getpursue.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

/**
 * RecyclerView adapter for displaying progress entries in Goal Detail screen.
 * Supports long press on own entries to show edit/delete options.
 * Supports tap on photo to open fullscreen viewer.
 */
class GoalEntryAdapter(
    private val entries: List<GoalEntryUiModel>,
    private val currentUserId: String,
    private val onLongPress: (GoalEntryUiModel) -> Unit = {},
    private val onPhotoClick: ((photoUrl: String) -> Unit)? = null,
    private val onReport: ((GoalEntryUiModel) -> Unit)? = null
) : RecyclerView.Adapter<GoalEntryAdapter.EntryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_goal_entry, parent, false)
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        val entry = entries[position]
        holder.bind(entry, currentUserId, onLongPress, onPhotoClick, onReport)
    }

    override fun getItemCount(): Int = entries.size

    class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateHeader: TextView = itemView.findViewById(R.id.date_header)
        private val checkmarkIcon: TextView = itemView.findViewById(R.id.checkmark_icon)
        private val memberName: TextView = itemView.findViewById(R.id.member_name)
        private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
        private val note: TextView = itemView.findViewById(R.id.note)
        private val entryPhoto: ImageView = itemView.findViewById(R.id.entry_photo)

        fun bind(
            entry: GoalEntryUiModel,
            currentUserId: String,
            onLongPress: (GoalEntryUiModel) -> Unit,
            onPhotoClick: ((photoUrl: String) -> Unit)?,
            onReport: ((GoalEntryUiModel) -> Unit)?
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

            // Progress photo (if present)
            val photoUrl = entry.photoUrl
            if (!photoUrl.isNullOrBlank()) {
                entryPhoto.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(photoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .fitCenter()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(entryPhoto)
                entryPhoto.setOnClickListener { onPhotoClick?.invoke(photoUrl) }
            } else {
                entryPhoto.visibility = View.GONE
                entryPhoto.setOnClickListener(null)
                Glide.with(itemView.context).clear(entryPhoto)
            }

            // Long press listener: own entries get edit/delete; others can be reported
            if (entry.is_current_user) {
                itemView.setOnLongClickListener {
                    onLongPress(entry)
                    true
                }
            } else {
                if (onReport != null) {
                    itemView.setOnLongClickListener {
                        onReport.invoke(entry)
                        true
                    }
                } else {
                    itemView.setOnLongClickListener(null)
                    itemView.isLongClickable = false
                }
            }
        }
    }
}
