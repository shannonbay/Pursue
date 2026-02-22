package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.DiscoverGroupItem

class DiscoverGroupAdapter(
    private val groups: List<DiscoverGroupItem>,
    private val onGroupClick: (DiscoverGroupItem) -> Unit
) : RecyclerView.Adapter<DiscoverGroupAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val groupIconEmoji: TextView = itemView.findViewById(R.id.group_icon_emoji)
        val groupName: TextView = itemView.findViewById(R.id.group_name)
        val groupCategory: TextView = itemView.findViewById(R.id.group_category)
        val heatTierBadge: TextView = itemView.findViewById(R.id.heat_tier_badge)
        val groupStats: TextView = itemView.findViewById(R.id.group_stats)
        val spotsBadge: TextView = itemView.findViewById(R.id.spots_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discover_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]

        // Icon: show emoji (icon images require auth, not available for unauthenticated browse)
        holder.groupIconEmoji.text = group.icon_emoji?.takeIf { it.isNotBlank() } ?: "👥"

        holder.groupName.text = group.name

        val category = group.category
        if (category != null) {
            holder.groupCategory.text = category.replaceFirstChar { it.uppercase() }
            holder.groupCategory.visibility = View.VISIBLE
        } else {
            holder.groupCategory.visibility = View.GONE
        }

        // Show heat badge for warm and above (tier > 1)
        if (group.heat_tier > 1) {
            holder.heatTierBadge.text = group.heat_tier_name
            holder.heatTierBadge.visibility = View.VISIBLE
        } else {
            holder.heatTierBadge.visibility = View.GONE
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

    override fun getItemCount(): Int = groups.size
}
