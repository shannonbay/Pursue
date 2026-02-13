package app.getpursue.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.NotificationItem
import app.getpursue.utils.RelativeTimeUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/**
 * RecyclerView adapter for the notification inbox list.
 * Renders each notification with avatar, body text, context line, and unread dot.
 */
class NotificationAdapter(
    private val items: List<NotificationItem>,
    private val context: Context,
    private val onItemClick: (NotificationItem) -> Unit
) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(items[position], context, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): NotificationItem = items[position]

    class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: ImageView = itemView.findViewById(R.id.notification_avatar)
        private val avatarOverlay: TextView = itemView.findViewById(R.id.notification_avatar_overlay)
        private val body: TextView = itemView.findViewById(R.id.notification_body)
        private val contextLine: TextView = itemView.findViewById(R.id.notification_context)
        private val unreadDot: View = itemView.findViewById(R.id.notification_unread_dot)
        private val leftAccent: View = itemView.findViewById(R.id.notification_left_accent)

        fun bind(item: NotificationItem, context: Context, onItemClick: (NotificationItem) -> Unit) {
            val bodyText = formatBody(context, item)
            val contextText = formatContext(context, item)

            body.text = bodyText
            body.setTypeface(null, if (item.is_read) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
            contextLine.text = contextText
            unreadDot.visibility = if (item.is_read) View.GONE else View.VISIBLE

            setupAvatar(item, context)
            setupLeftAccent(item, context)
            setupOverlay(item)

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun formatBody(context: Context, item: NotificationItem): String {
            val actorName = item.actor?.display_name ?: ""
            val groupName = item.group?.name ?: ""
            val goalTitle = item.goal?.title ?: ""
            return when (item.type) {
                "reaction_received" -> {
                    val emoji = (item.metadata?.get("emoji") as? String) ?: "❤️"
                    context.getString(R.string.notification_reaction, actorName, emoji, goalTitle)
                }
                "nudge_received" -> context.getString(R.string.notification_nudge, actorName, goalTitle.ifEmpty { groupName })
                "membership_approved" -> context.getString(R.string.notification_approved, groupName)
                "membership_rejected" -> context.getString(R.string.notification_rejected, groupName)
                "promoted_to_admin" -> context.getString(R.string.notification_promoted, actorName, groupName)
                "removed_from_group" -> context.getString(R.string.notification_removed, groupName)
                "milestone_achieved" -> {
                    val milestoneType = item.metadata?.get("milestone_type") as? String
                    when (milestoneType) {
                        "first_log" -> context.getString(R.string.notification_milestone_first)
                        "streak" -> {
                            val count = (item.metadata?.get("streak_count") as? Number)?.toInt() ?: 0
                            context.getString(R.string.notification_milestone_streak, count, goalTitle.ifEmpty { "your goal" })
                        }
                        "total_logs" -> context.getString(R.string.notification_milestone_first)
                        else -> context.getString(R.string.notification_milestone_first)
                    }
                }
                else -> (item.actor?.display_name ?: "Someone") + " — notification"
            }
        }

        private fun formatContext(context: Context, item: NotificationItem): String {
            val groupName = item.group?.name ?: ""
            val relativeTime = RelativeTimeUtils.formatRelativeTime(context, item.created_at)
            return if (groupName.isNotEmpty()) "$groupName · $relativeTime" else relativeTime
        }

        private fun setupAvatar(item: NotificationItem, context: Context) {
            if (item.type == "milestone_achieved") {
                avatar.setImageResource(R.drawable.ic_pursue_logo)
                avatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                avatar.setBackgroundResource(R.color.surface_variant)
                avatarOverlay.visibility = View.GONE
                return
            }
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            val actor = item.actor
            val imageUrl = actor?.avatar_url?.let { url ->
                if (url.startsWith("http")) url else {
                    val base = ApiClient.getBaseUrl()
                    val origin = base.substringBeforeLast("/")
                    "$origin$url"
                }
            }
            if (!imageUrl.isNullOrBlank()) {
                Glide.with(context)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .placeholder(R.drawable.ic_pursue_logo)
                    .error(R.drawable.ic_pursue_logo)
                    .into(avatar)
                avatar.setBackgroundResource(0)
            } else {
                avatar.setImageResource(R.drawable.ic_pursue_logo)
                avatar.setBackgroundResource(R.color.surface_variant)
            }
        }

        private fun setupOverlay(item: NotificationItem) {
            when (item.type) {
                "reaction_received" -> {
                    val emoji = (item.metadata?.get("emoji") as? String) ?: "❤️"
                    avatarOverlay.text = emoji
                    avatarOverlay.visibility = View.VISIBLE
                }
                "nudge_received" -> {
                    avatarOverlay.text = "👋"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "membership_approved" -> {
                    avatarOverlay.text = "✅"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "membership_rejected", "removed_from_group" -> {
                    avatarOverlay.text = "✖"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "promoted_to_admin" -> {
                    avatarOverlay.text = "⭐"
                    avatarOverlay.visibility = View.VISIBLE
                }
                else -> avatarOverlay.visibility = View.GONE
            }
        }

        private fun setupLeftAccent(item: NotificationItem, context: Context) {
            val (colorRes, visible) = when (item.type) {
                "milestone_achieved" -> R.color.milestone_gold_border to true
                "membership_approved" -> R.color.approved_green_border to true
                "membership_rejected", "removed_from_group" -> R.color.on_surface_variant to true
                else -> 0 to false
            }
            leftAccent.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible && colorRes != 0) {
                leftAccent.setBackgroundColor(context.resources.getColor(colorRes, null))
            }
        }
    }
}
