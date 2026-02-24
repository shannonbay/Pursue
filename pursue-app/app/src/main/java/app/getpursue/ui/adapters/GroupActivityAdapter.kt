package app.getpursue.ui.adapters

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.data.network.ApiClient
import app.getpursue.models.GroupActivity
import app.getpursue.models.ReactionSummary
import app.getpursue.utils.RelativeTimeUtils
import app.getpursue.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RecyclerView adapter for displaying activity feed grouped by date.
 * Shows date header (Today, Yesterday, or date), then activities under each date.
 */
interface ReactionListener {
    fun onLongPress(activity: GroupActivity, anchorView: View, touchX: Float, touchY: Float)
    fun onReactionButtonClick(activity: GroupActivity, button: View)
    fun onReactionSummaryClick(activityId: String)
}

class GroupActivityAdapter(
    private val activities: List<GroupActivity>,
    private val currentUserId: String? = null,
    private val onPhotoClick: ((photoUrl: String) -> Unit)? = null,
    private val reactionListener: ReactionListener? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DATE_HEADER = 0
        private const val TYPE_ACTIVITY = 1
        private const val TYPE_FOOTER_SPACER = 2

        private val HEAT_ACTIVITY_TYPES = setOf(
            "heat_tier_up",
            "heat_tier_down",
            "heat_supernova_reached",
            "heat_streak_milestone"
        )
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
        val contentItems = activitiesByDate.entries.flatMap { (dateLabel, dateActivities) ->
            listOf(Item(TYPE_DATE_HEADER, dateLabel = dateLabel)) +
            dateActivities.map { activity -> Item(TYPE_ACTIVITY, activity = activity) }
        }
        contentItems + Item(TYPE_FOOTER_SPACER)
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
            TYPE_FOOTER_SPACER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_activity_footer_spacer, parent, false)
                FooterViewHolder(view)
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
                    is ActivityViewHolder -> holder.bind(
                        item.activity!!,
                        currentUserId,
                        onPhotoClick,
                        reactionListener
                    )
                    else -> { /* Footer spacer: nothing to bind */ }
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

    class FooterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class ActivityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val activityAvatar: ImageView = itemView.findViewById(R.id.activity_avatar)
        private val activityAvatarFallback: TextView = itemView.findViewById(R.id.activity_avatar_fallback)
        private val activityText: TextView = itemView.findViewById(R.id.activity_text)
        private val activityTimestamp: TextView = itemView.findViewById(R.id.activity_timestamp)
        private val activityLogTitle: TextView = itemView.findViewById(R.id.activity_log_title)
        private val activityReactButton: View = itemView.findViewById(R.id.activity_react_button)
        private val reactionsContainer: View = itemView.findViewById(R.id.reactions_container)
        private val reactionsEmojis: TextView = itemView.findViewById(R.id.reactions_emojis)
        private val reactionsLabel: TextView = itemView.findViewById(R.id.reactions_label)
        private val activityPhoto: ImageView = itemView.findViewById(R.id.activity_photo)

        fun bind(
            activity: GroupActivity,
            currentUserId: String?,
            onPhotoClick: ((photoUrl: String) -> Unit)?,
            reactionListener: ReactionListener?
        ) {
            // Avatar: same URL pattern as MembersTabFragment / MemberDetailFragment; heat activities use Pursue logo
            val user = activity.user
            if (user == null) {
                val isHeatActivity = activity.activity_type in HEAT_ACTIVITY_TYPES
                if (isHeatActivity) {
                    activityAvatar.visibility = View.VISIBLE
                    activityAvatarFallback.visibility = View.GONE
                    Glide.with(itemView.context).clear(activityAvatar)
                    activityAvatar.setImageResource(R.drawable.ic_pursue_logo)
                    activityAvatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    activityAvatar.setBackgroundResource(R.drawable.circle_background_white)
                    activityAvatar.contentDescription = itemView.context.getString(R.string.app_name)
                } else {
                    activityAvatar.visibility = View.GONE
                    activityAvatarFallback.visibility = View.VISIBLE
                    activityAvatarFallback.text = "?"
                    activityAvatarFallback.contentDescription = itemView.context.getString(R.string.unknown)
                }
            } else {
                activityAvatar.visibility = View.VISIBLE
                activityAvatarFallback.visibility = View.GONE
                activityAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                activityAvatar.setBackgroundResource(R.drawable.circle_background)
                val imageUrl = "${ApiClient.getBaseUrl()}/users/${user.id}/avatar"
                activityAvatar.contentDescription = itemView.context.getString(R.string.content_description_member_avatar, user.display_name)
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .error(R.drawable.ic_pursue_logo)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            activityAvatar.post {
                                activityAvatar.visibility = View.GONE
                                activityAvatarFallback.visibility = View.VISIBLE
                                val firstLetter = user.display_name.firstOrNull()?.uppercaseChar() ?: '?'
                                activityAvatarFallback.text = firstLetter.toString()
                            }
                            return false
                        }
                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean = false
                    })
                    .into(activityAvatar)
            }

            // Format activity text based on type
            val activityMessage = formatActivityMessage(activity, currentUserId)
            activityText.text = activityMessage

            // Show journal log title when present (progress_logged journal entries)
            val logTitle = if (activity.activity_type == "progress_logged") {
                activity.metadata?.get("log_title") as? String
            } else null
            if (!logTitle.isNullOrBlank()) {
                activityLogTitle.text = "\u201C${logTitle}\u201D"
                activityLogTitle.visibility = View.VISIBLE
            } else {
                activityLogTitle.visibility = View.GONE
            }

            // Format timestamp
            activityTimestamp.text = formatRelativeTime(activity.created_at)

            // Reactions summary
            val reactions = activity.reactions
            val summary = activity.reaction_summary
            if (!reactions.isNullOrEmpty() && summary != null && summary.total_count > 0) {
                reactionsContainer.visibility = View.VISIBLE
                reactionsEmojis.text = reactions.take(3).joinToString("") { it.emoji }
                reactionsLabel.text = formatReactionLabel(summary, currentUserId)
                reactionsContainer.setOnClickListener {
                    activity.id?.let { id -> reactionListener?.onReactionSummaryClick(id) }
                }
                reactionsContainer.isClickable = true
            } else {
                reactionsContainer.visibility = View.GONE
                reactionsContainer.setOnClickListener(null)
                reactionsContainer.isClickable = false
            }

            // Store activity data in view tag for retrieval by RecyclerViewLongPressHelper
            itemView.setTag(R.id.activity_data_tag, activity)
            itemView.setTag(R.id.reaction_listener_tag, reactionListener)

            // React button: show picker (same as long-press)
            if (activity.id != null && reactionListener != null) {
                activityReactButton.visibility = View.VISIBLE
                activityReactButton.setOnClickListener {
                    reactionListener.onReactionButtonClick(activity, activityReactButton)
                }
            } else {
                activityReactButton.visibility = View.GONE
                activityReactButton.setOnClickListener(null)
            }

            // Show progress photo when present
            val photo = activity.photo
            if (photo != null && photo.url.isNotBlank()) {
                activityPhoto.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(photo.url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(photo.width, photo.height)
                    .fitCenter()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(activityPhoto)
                activityPhoto.setOnClickListener { onPhotoClick?.invoke(photo.url) }
            } else {
                activityPhoto.visibility = View.GONE
                activityPhoto.setOnClickListener(null)
                Glide.with(itemView.context).clear(activityPhoto)
            }
        }

        private fun formatReactionLabel(summary: ReactionSummary, currentUserId: String?): String {
            val topReactors = summary.top_reactors
            if (topReactors.isEmpty()) return ""

            val names = topReactors.map { t ->
                if (currentUserId != null && t.user_id == currentUserId) {
                    itemView.context.getString(R.string.reactions_you)
                } else {
                    t.display_name
                }
            }

            return when {
                summary.total_count == 1 -> names[0]
                summary.total_count == 2 -> itemView.context.getString(
                    R.string.reactions_one_and_one,
                    names[0],
                    names[1]
                )
                else -> itemView.context.getString(
                    R.string.reactions_one_and_others,
                    names[0],
                    summary.total_count - 1
                )
            }
        }

        private fun formatActivityMessage(activity: GroupActivity, currentUserId: String?): String {
            val user = activity.user
            val userName = when {
                user == null -> itemView.context.getString(R.string.unknown)
                user.id == currentUserId -> itemView.context.getString(R.string.you)
                else -> user.display_name
            }

            return when (activity.activity_type) {
                "group_created" -> {
                    itemView.context.getString(R.string.activity_group_created, userName)
                }
                "progress_logged" -> {
                    val goalTitle = activity.metadata?.get("goal_title") as? String ?: "goal"
                    val logTitle = activity.metadata?.get("log_title") as? String
                    if (!logTitle.isNullOrBlank()) {
                        itemView.context.getString(R.string.activity_progress_logged_journal, userName)
                    } else {
                        itemView.context.getString(R.string.activity_progress_logged, userName, goalTitle)
                    }
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
                "nudge_sent" -> {
                    val meta = activity.metadata ?: emptyMap()
                    val recipientId = meta["recipient_user_id"] as? String
                    val recipientName = (meta["recipient_display_name"] as? String) ?: "someone"
                    val recipientNameOrYou = if (currentUserId != null && recipientId == currentUserId) {
                        itemView.context.getString(R.string.you)
                    } else {
                        recipientName
                    }
                    val goalTitle = (meta["goal_title"] as? String) ?: "goal"
                    itemView.context.getString(R.string.activity_nudge_sent, userName, recipientNameOrYou, goalTitle)
                }
                "invite_code_regenerated" -> {
                    itemView.context.getString(R.string.activity_invite_code_regenerated, userName)
                }
                "export_progress" -> {
                    val meta = activity.metadata ?: emptyMap()
                    val startDate = (meta["start_date"] as? String) ?: ""
                    val endDate = (meta["end_date"] as? String) ?: ""
                    itemView.context.getString(R.string.activity_export_progress, userName, startDate, endDate)
                }
                "heat_tier_up" -> {
                    val tierName = activity.metadata?.get("tier_name") as? String ?: "this tier"
                    itemView.context.getString(R.string.activity_heat_tier_up, tierName)
                }
                "heat_tier_down" -> itemView.context.getString(R.string.activity_heat_tier_down)
                "heat_supernova_reached" -> itemView.context.getString(R.string.activity_heat_supernova_reached)
                "heat_streak_milestone" -> {
                    val days = (activity.metadata?.get("streak_days") as? Number)?.toInt() ?: 7
                    itemView.context.getString(R.string.activity_heat_streak_milestone, days)
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
            val user = activity.user
            val fallbackName = user?.display_name ?: itemView.context.getString(R.string.unknown)
            val meta = activity.metadata ?: return fallbackName
            val displayName = (meta[displayNameKey] as? String)
                ?: (meta["target_display_name"] as? String)
                ?: fallbackName
            val targetId = meta[userIdKey] as? String ?: user?.id
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
