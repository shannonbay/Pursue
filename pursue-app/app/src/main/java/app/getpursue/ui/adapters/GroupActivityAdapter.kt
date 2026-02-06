package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.models.GroupActivity
import app.getpursue.utils.RelativeTimeUtils
import com.github.shannonbay.pursue.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RecyclerView adapter for displaying activity feed grouped by date.
 * Shows date header (Today, Yesterday, or date), then activities under each date.
 */
class GroupActivityAdapter(
    private val activities: List<GroupActivity>,
    private val currentUserId: String? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DATE_HEADER = 0
        private const val TYPE_ACTIVITY = 1
    }

    private data class Item(
        val type: Int,
        val dateLabel: String? = null,
        val activity: GroupActivity? = null
    )

    private val items: List<Item> = run {
        val activitiesByDate = activities.groupBy { activity ->
            formatDateLabel(activity.created_at)
        }
        
        activitiesByDate.entries.flatMap { (dateLabel, dateActivities) ->
            listOf(Item(TYPE_DATE_HEADER, dateLabel = dateLabel)) +
            dateActivities.map { activity -> Item(TYPE_ACTIVITY, activity = activity) }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return items[position].type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_DATE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                DateHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_activity_item, parent, false)
                ActivityViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item -> {
                when (holder) {
                    is DateHeaderViewHolder -> holder.bind(item.dateLabel!!)
                    is ActivityViewHolder -> holder.bind(item.activity!!, currentUserId)
                }
            }
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

    class ActivityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val activityText: TextView = itemView.findViewById(R.id.activity_text)
        private val activityTimestamp: TextView = itemView.findViewById(R.id.activity_timestamp)

        fun bind(activity: GroupActivity, currentUserId: String?) {
            // Format activity text based on type
            val activityMessage = formatActivityMessage(activity, currentUserId)
            activityText.text = activityMessage

            // Format timestamp
            activityTimestamp.text = formatRelativeTime(activity.created_at)
        }

        private fun formatActivityMessage(activity: GroupActivity, currentUserId: String?): String {
            val userName = if (activity.user.id == currentUserId) {
                itemView.context.getString(R.string.you)
            } else {
                activity.user.display_name
            }

            return when (activity.activity_type) {
                "group_created" -> {
                    itemView.context.getString(R.string.activity_group_created, userName)
                }
                "progress_logged" -> {
                    val goalTitle = activity.metadata?.get("goal_title") as? String ?: "goal"
                    itemView.context.getString(R.string.activity_progress_logged, userName, goalTitle)
                }
                "member_joined" -> {
                    itemView.context.getString(R.string.activity_member_joined, userName)
                }
                "member_left" -> {
                    itemView.context.getString(R.string.activity_member_left, userName)
                }
                "member_promoted" -> {
                    itemView.context.getString(R.string.activity_member_promoted, userName)
                }
                "member_removed" -> {
                    itemView.context.getString(R.string.activity_member_removed, userName)
                }
                "goal_added" -> {
                    val goalTitle = activity.metadata?.get("goal_title") as? String ?: "goal"
                    itemView.context.getString(R.string.activity_goal_added, userName, goalTitle)
                }
                "goal_archived" -> {
                    val goalTitle = activity.metadata?.get("goal_title") as? String ?: "goal"
                    itemView.context.getString(R.string.activity_goal_archived, goalTitle)
                }
                "group_renamed" -> {
                    val newName = activity.metadata?.get("new_name") as? String ?: "group"
                    itemView.context.getString(R.string.activity_group_renamed, newName)
                }
                "join_request" -> {
                    itemView.context.getString(R.string.activity_join_request, userName)
                }
                "member_approved" -> {
                    // Backend sends approved user in metadata (activity.user is the approver)
                    val approvedName = approvedOrDeclinedUserName(
                        activity,
                        currentUserId,
                        "approved_user_display_name",
                        "approved_user_id"
                    )
                    itemView.context.getString(R.string.activity_member_approved, approvedName)
                }
                "member_declined" -> {
                    // Backend sends declined user in metadata (activity.user is the decliner)
                    val declinedName = approvedOrDeclinedUserName(
                        activity,
                        currentUserId,
                        "declined_user_display_name",
                        "declined_user_id"
                    )
                    itemView.context.getString(R.string.activity_member_declined, declinedName)
                }
                else -> "$userName performed an action"
            }
        }

        /**
         * For member_approved / member_declined the backend sends the approved/declined user in metadata
         * (activity.user is the approver/decliner). We show that user's name and "(You)" when the viewer is that user.
         */
        private fun approvedOrDeclinedUserName(
            activity: GroupActivity,
            currentUserId: String?,
            displayNameKey: String,
            userIdKey: String
        ): String {
            val meta = activity.metadata ?: return activity.user.display_name
            val displayName = (meta[displayNameKey] as? String)
                ?: (meta["target_display_name"] as? String)
                ?: activity.user.display_name
            val targetId = meta[userIdKey] as? String ?: activity.user.id
            return if (currentUserId != null && targetId == currentUserId) {
                itemView.context.getString(R.string.you)
            } else {
                displayName
            }
        }

        private fun formatRelativeTime(isoTimestamp: String): String {
            return RelativeTimeUtils.formatRelativeTime(itemView.context, isoTimestamp)
        }
    }
}
