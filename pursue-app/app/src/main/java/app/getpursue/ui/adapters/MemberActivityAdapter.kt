package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.MemberProgressActivityEntry
import app.getpursue.utils.RelativeTimeUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RecyclerView adapter for the member activity log.
 * 
 * Displays progress entries grouped by date, with support for:
 * - Date headers (Today, Yesterday, or formatted date)
 * - Activity entry cards with goal info, value, note, photo, reactions
 * - Loading more indicator at the bottom
 */
class MemberActivityAdapter(
    private val onPhotoClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DATE_HEADER = 0
        private const val TYPE_ACTIVITY_ENTRY = 1
        private const val TYPE_LOADING_MORE = 2
    }

    private sealed class Item {
        data class DateHeader(val dateLabel: String) : Item()
        data class ActivityEntry(val entry: MemberProgressActivityEntry) : Item()
        object LoadingMore : Item()
    }

    private var items: List<Item> = emptyList()
    private var isLoadingMore: Boolean = false

    /**
     * Updates the adapter with new entries and loading state.
     * Groups entries by date and adds headers.
     */
    fun submitData(entries: List<MemberProgressActivityEntry>, loadingMore: Boolean) {
        val newItems = mutableListOf<Item>()
        
        // Group entries by date
        val entriesByDate = entries.groupBy { entry -> formatDateLabel(entry.logged_at) }
        
        for ((dateLabel, dateEntries) in entriesByDate) {
            newItems.add(Item.DateHeader(dateLabel))
            for (entry in dateEntries) {
                newItems.add(Item.ActivityEntry(entry))
            }
        }
        
        if (loadingMore) {
            newItems.add(Item.LoadingMore)
        }
        
        val oldItems = items
        items = newItems
        isLoadingMore = loadingMore
        
        // Use DiffUtil for efficient updates
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val oldItem = oldItems[oldPos]
                val newItem = newItems[newPos]
                return when {
                    oldItem is Item.DateHeader && newItem is Item.DateHeader -> 
                        oldItem.dateLabel == newItem.dateLabel
                    oldItem is Item.ActivityEntry && newItem is Item.ActivityEntry -> 
                        oldItem.entry.entry_id == newItem.entry.entry_id
                    oldItem is Item.LoadingMore && newItem is Item.LoadingMore -> true
                    else -> false
                }
            }
            
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return oldItems[oldPos] == newItems[newPos]
            }
        })
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is Item.DateHeader -> TYPE_DATE_HEADER
            is Item.ActivityEntry -> TYPE_ACTIVITY_ENTRY
            is Item.LoadingMore -> TYPE_LOADING_MORE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DATE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                DateHeaderViewHolder(view)
            }
            TYPE_LOADING_MORE -> {
                val progressBar = ProgressBar(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, 32, 0, 32)
                }
                val container = LinearLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    gravity = android.view.Gravity.CENTER
                    addView(progressBar)
                }
                LoadingMoreViewHolder(container)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_member_activity_entry, parent, false)
                ActivityEntryViewHolder(view, onPhotoClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.DateHeader -> (holder as DateHeaderViewHolder).bind(item.dateLabel)
            is Item.ActivityEntry -> (holder as ActivityEntryViewHolder).bind(item.entry)
            is Item.LoadingMore -> { /* Nothing to bind */ }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatDateLabel(isoTimestamp: String): String {
        return try {
            val timestamp = RelativeTimeUtils.parseIsoTimestamp(isoTimestamp) ?: return isoTimestamp
            val now = Date()
            val diffMs = now.time - timestamp.time
            val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)
            
            when {
                diffDays == 0L -> "today"
                diffDays == 1L -> "yesterday"
                else -> {
                    val dateFormat = SimpleDateFormat("MMMM d", Locale.US)
                    dateFormat.format(timestamp)
                }
            }
        } catch (e: Exception) {
            isoTimestamp
        }
    }

    class DateHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(dateLabel: String) {
            val labelText = when (dateLabel.lowercase()) {
                "today" -> itemView.context.getString(R.string.today)
                "yesterday" -> itemView.context.getString(R.string.yesterday)
                else -> dateLabel
            }
            textView.text = labelText
            textView.textSize = 16f
            textView.setTextColor(itemView.context.getColor(R.color.on_surface))
            textView.setPadding(16, 16, 16, 8)
        }
    }

    class ActivityEntryViewHolder(
        itemView: View,
        private val onPhotoClick: ((String) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val goalEmoji: TextView = itemView.findViewById(R.id.entry_goal_emoji)
        private val goalTitle: TextView = itemView.findViewById(R.id.entry_goal_title)
        private val entryValue: TextView = itemView.findViewById(R.id.entry_value)
        private val entryDate: TextView = itemView.findViewById(R.id.entry_date)
        private val entryNote: TextView = itemView.findViewById(R.id.entry_note)
        private val entryPhoto: ShapeableImageView = itemView.findViewById(R.id.entry_photo)
        private val reactionsContainer: LinearLayout = itemView.findViewById(R.id.reactions_container)
        private val reactionsEmojis: TextView = itemView.findViewById(R.id.reactions_emojis)
        private val reactionsCount: TextView = itemView.findViewById(R.id.reactions_count)

        fun bind(entry: MemberProgressActivityEntry) {
            // Goal info
            goalEmoji.text = entry.goal_emoji ?: "🎯"
            goalTitle.text = entry.goal_title
            
            // Value display
            entryValue.text = when (entry.metric_type) {
                "binary" -> "✓"
                "numeric" -> {
                    val valueInt = entry.value.toInt()
                    val valueStr = if (entry.value == valueInt.toDouble()) valueInt.toString() else entry.value.toString()
                    if (entry.unit != null) "$valueStr ${entry.unit}" else valueStr
                }
                "duration" -> {
                    val minutes = entry.value.toInt()
                    if (minutes >= 60) {
                        val hours = minutes / 60
                        val remainingMinutes = minutes % 60
                        if (remainingMinutes > 0) "${hours}h ${remainingMinutes}m" else "${hours}h"
                    } else {
                        "${minutes}m"
                    }
                }
                else -> entry.value.toString()
            }
            
            // Date formatting
            val context = itemView.context
            val dateFormatted = try {
                val timestamp = RelativeTimeUtils.parseIsoTimestamp(entry.logged_at)
                if (timestamp != null) {
                    val dayFormat = SimpleDateFormat("EEE d MMM", Locale.US)
                    val dayStr = dayFormat.format(timestamp)
                    val relativeTime = RelativeTimeUtils.formatRelativeTime(context, entry.logged_at)
                    "$dayStr · $relativeTime"
                } else {
                    entry.entry_date
                }
            } catch (e: Exception) {
                entry.entry_date
            }
            entryDate.text = dateFormatted
            
            // Note (optional)
            if (!entry.note.isNullOrBlank()) {
                entryNote.visibility = View.VISIBLE
                entryNote.text = entry.note
            } else {
                entryNote.visibility = View.GONE
            }
            
            // Photo (optional)
            if (!entry.photo_url.isNullOrBlank()) {
                entryPhoto.visibility = View.VISIBLE
                Glide.with(context)
                    .load(entry.photo_url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(entryPhoto)
                entryPhoto.setOnClickListener {
                    onPhotoClick?.invoke(entry.photo_url)
                }
            } else {
                entryPhoto.visibility = View.GONE
                entryPhoto.setOnClickListener(null)
            }
            
            // Reactions
            if (entry.reactions.isNotEmpty()) {
                reactionsContainer.visibility = View.VISIBLE
                // Concatenate first 3 emojis
                val emojis = entry.reactions.take(3).joinToString("") { it.emoji }
                reactionsEmojis.text = emojis
                // Total count
                val totalCount = entry.reactions.sumOf { it.count }
                reactionsCount.text = totalCount.toString()
            } else {
                reactionsContainer.visibility = View.GONE
            }
        }
    }

    class LoadingMoreViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
}
