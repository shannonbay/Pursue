package com.github.shannonbay.pursue.ui.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.models.Group
import com.github.shannonbay.pursue.utils.RelativeTimeUtils

/**
 * RecyclerView adapter for displaying group cards.
 */
class GroupAdapter(
    private val groups: List<Group>,
    private val onGroupClick: (Group) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_card, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position], onGroupClick)
    }

    override fun getItemCount(): Int = groups.size

    class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val groupIconImage: ImageView = itemView.findViewById(R.id.group_icon_image)
        private val groupIconEmoji: TextView = itemView.findViewById(R.id.group_icon_emoji)
        private val groupName: TextView = itemView.findViewById(R.id.group_name)
        private val memberGoalCount: TextView = itemView.findViewById(R.id.member_goal_count)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val lastActivity: TextView = itemView.findViewById(R.id.last_activity)

        fun bind(group: Group, onGroupClick: (Group) -> Unit) {
            // Handle group icon display
            if (group.has_icon) {
                // Show image, hide emoji
                groupIconImage.visibility = View.VISIBLE
                groupIconEmoji.visibility = View.GONE
                
                // Build image URL with access token in header (Glide will use custom headers)
                val imageUrl = "${ApiClient.getBaseUrl()}/groups/${group.id}/icon"
                
                // Create a custom Glide request with authorization header
                val requestBuilder = Glide.with(itemView.context)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .error(R.drawable.ic_pursue_logo) // Fallback on error
                
                // Add cache signature based on updated_at for cache invalidation
                if (group.updated_at != null) {
                    requestBuilder.signature(ObjectKey(group.updated_at))
                }
                
                requestBuilder.into(groupIconImage)
            } else {
                // Show emoji, hide image (circle background tinted by icon_color)
                groupIconImage.visibility = View.GONE
                groupIconEmoji.visibility = View.VISIBLE
                groupIconEmoji.text = group.icon_emoji ?: "📁"
                val fallbackColor = ContextCompat.getColor(itemView.context, R.color.primary)
                try {
                    groupIconEmoji.backgroundTintList = ColorStateList.valueOf(
                        if (group.icon_color != null) Color.parseColor(group.icon_color) else fallbackColor
                    )
                } catch (_: IllegalArgumentException) {
                    groupIconEmoji.backgroundTintList = ColorStateList.valueOf(fallbackColor)
                }
            }
            
            groupName.text = group.name
            
            // TODO: active_goals count not in API response - use placeholder for MVP
            val activeGoals = 0 // Placeholder until we fetch from group detail
            memberGoalCount.text = itemView.context.getString(
                R.string.members_and_goals,
                group.member_count,
                activeGoals
            )
            
            // TODO: today_completion_percent not in API response - use placeholder for MVP
            val completionPercent = 0 // Placeholder until we fetch from group detail
            progressBar.progress = completionPercent
            
            // Format last activity with prefix (use updated_at when available, else joined_at)
            lastActivity.text = itemView.context.getString(
                R.string.joined_format,
                RelativeTimeUtils.formatRelativeTime(itemView.context, group.updated_at ?: group.joined_at)
            )

            itemView.setOnClickListener {
                onGroupClick(group)
            }
        }
    }
}
