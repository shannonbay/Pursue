package app.getpursue.ui.adapters

import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.DiscoverGroupItem
import app.getpursue.utils.HeatUtils
import app.getpursue.utils.IconUrlUtils

class DiscoverGroupAdapter(
    private val groups: List<DiscoverGroupItem>,
    private val onGroupClick: (DiscoverGroupItem) -> Unit
) : RecyclerView.Adapter<DiscoverGroupAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val groupIconImage: ImageView = itemView.findViewById(R.id.group_icon_image)
        val groupIconEmoji: TextView = itemView.findViewById(R.id.group_icon_emoji)
        val groupName: TextView = itemView.findViewById(R.id.group_name)
        val groupCategory: TextView = itemView.findViewById(R.id.group_category)
        val groupHeatIcon: ImageView = itemView.findViewById(R.id.group_heat_icon)
        val groupStats: TextView = itemView.findViewById(R.id.group_stats)
        val spotsBadge: TextView = itemView.findViewById(R.id.spots_badge)

        private var heatAnimationCallback: Animatable2.AnimationCallback? = null

        fun stopHeatAnimation() {
            heatAnimationCallback?.let {
                (groupHeatIcon.drawable as? AnimatedVectorDrawable)?.unregisterAnimationCallback(it)
            }
            (groupHeatIcon.drawable as? AnimatedVectorDrawable)?.stop()
            heatAnimationCallback = null
        }

        fun startHeatAnimation() {
            val avd = groupHeatIcon.drawable as? AnimatedVectorDrawable ?: return
            heatAnimationCallback = object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    avd.start()
                }
            }
            avd.registerAnimationCallback(heatAnimationCallback!!)
            avd.start()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discover_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]

        // Icon: use icon_url if available, fall back to emoji
        val iconLoaded = IconUrlUtils.loadInto(holder.itemView.context, group.icon_url, holder.groupIconImage)
        if (iconLoaded) {
            holder.groupIconImage.visibility = View.VISIBLE
            holder.groupIconEmoji.visibility = View.GONE
        } else {
            holder.groupIconImage.visibility = View.GONE
            holder.groupIconEmoji.visibility = View.VISIBLE
            holder.groupIconEmoji.text = group.icon_emoji?.takeIf { it.isNotBlank() } ?: "👥"
        }

        holder.groupName.text = group.name

        val category = group.category
        if (category != null) {
            holder.groupCategory.text = category.replaceFirstChar { it.uppercase() }
            holder.groupCategory.visibility = View.VISIBLE
        } else {
            holder.groupCategory.visibility = View.GONE
        }

        val heatDrawableRes = HeatUtils.getTierDrawable(group.heat_tier)
        if (heatDrawableRes != null) {
            holder.stopHeatAnimation()
            holder.groupHeatIcon.setImageResource(heatDrawableRes)
            holder.groupHeatIcon.contentDescription = HeatUtils.getContentDescription(
                holder.itemView.context, group.heat_tier_name, group.heat_score
            )
            holder.groupHeatIcon.visibility = View.VISIBLE
            holder.groupHeatIcon.post { holder.startHeatAnimation() }
        } else {
            holder.stopHeatAnimation()
            holder.groupHeatIcon.visibility = View.GONE
        }

        val memberText = if (group.member_count == 1) "1 member" else "${group.member_count} members"
        val goalText = if (group.goal_count == 1) "1 goal" else "${group.goal_count} goals"
        holder.groupStats.text = "$memberText · $goalText"

        val spotLimit = group.spot_limit
        val spotsLeft = group.spots_left
        if (spotLimit != null && spotsLeft != null) {
            if (group.is_full) {
                holder.spotsBadge.text = holder.itemView.context.getString(R.string.discover_group_full)
                holder.spotsBadge.visibility = View.VISIBLE
            } else {
                val spotsText = if (spotsLeft == 1) "1 spot left" else "$spotsLeft spots left"
                holder.spotsBadge.text = spotsText
                holder.spotsBadge.visibility = View.VISIBLE
            }
        } else {
            holder.spotsBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onGroupClick(group) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.stopHeatAnimation()
    }

    override fun getItemCount(): Int = groups.size
}
