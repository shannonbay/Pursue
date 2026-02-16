package app.getpursue.ui.adapters

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
import app.getpursue.data.network.ApiClient
import app.getpursue.models.Group
import app.getpursue.utils.GrayscaleTransformation
import app.getpursue.utils.HeatUtils
import app.getpursue.utils.RelativeTimeUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import app.getpursue.R

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
        private val heatIcon: ImageView = itemView.findViewById(R.id.heat_icon)
        private val readOnlyBadge: TextView = itemView.findViewById(R.id.read_only_badge)
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

                if (group.is_read_only) {
                    requestBuilder.transform(GrayscaleTransformation())
                } else {
                    groupIconImage.colorFilter = null
                }
                requestBuilder.into(groupIconImage)
            } else {
                // Show emoji, hide image (circle background tinted by icon_color)
                groupIconImage.visibility = View.GONE
                groupIconEmoji.visibility = View.VISIBLE
                groupIconEmoji.text = group.icon_emoji ?: "📁"
                if (group.is_read_only) {
                    groupIconEmoji.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(itemView.context, R.color.surface_variant)
                    )
                    groupIconEmoji.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_surface_variant))
                } else {
                    val fallbackColor = ContextCompat.getColor(itemView.context, R.color.primary)
                    try {
                        groupIconEmoji.backgroundTintList = ColorStateList.valueOf(
                            if (group.icon_color != null) Color.parseColor(group.icon_color) else fallbackColor
                        )
                    } catch (_: IllegalArgumentException) {
                        groupIconEmoji.backgroundTintList = ColorStateList.valueOf(fallbackColor)
                    }
                    groupIconEmoji.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_surface))
                }
            }
            
            groupName.text = group.name

            // Heat icon display
            group.heat?.let { heat ->
                val drawableRes = HeatUtils.getTierDrawable(heat.tier)
                if (drawableRes != null) {
                    heatIcon.setImageResource(drawableRes)
                    heatIcon.contentDescription = HeatUtils.getContentDescription(
                        itemView.context, heat.tier_name, heat.score
                    )
                    heatIcon.visibility = View.VISIBLE
                } else {
                    heatIcon.visibility = View.GONE
                }
            } ?: run {
                heatIcon.visibility = View.GONE
            }
            
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

            // Read-only visual treatment (free user over limit; only kept group is editable)
            if (group.is_read_only) {
                itemView.alpha = 0.6f
                readOnlyBadge.visibility = View.VISIBLE
            } else {
                itemView.alpha = 1.0f
                readOnlyBadge.visibility = View.GONE
            }

            itemView.setOnClickListener {
                onGroupClick(group)
            }
        }
    }
}
