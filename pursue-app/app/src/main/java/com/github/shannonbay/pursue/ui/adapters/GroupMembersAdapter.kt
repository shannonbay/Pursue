package com.github.shannonbay.pursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.models.GroupMember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * RecyclerView adapter for displaying members grouped by role.
 * Shows role header (Admins, Members), then members under each role.
 */
class GroupMembersAdapter(
    private val members: List<GroupMember>,
    private val currentUserId: String? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ROLE_HEADER = 0
        private const val TYPE_MEMBER = 1
    }

    private data class Item(
        val type: Int,
        val role: String? = null,
        val member: GroupMember? = null
    )

    private val items: List<Item> = run {
        val admins = members.filter { it.role == "creator" || it.role == "admin" }
        val regularMembers = members.filter { it.role == "member" }
        
        buildList {
            if (admins.isNotEmpty()) {
                add(Item(TYPE_ROLE_HEADER, role = "admins"))
                addAll(admins.map { Item(TYPE_MEMBER, member = it) })
            }
            if (regularMembers.isNotEmpty()) {
                add(Item(TYPE_ROLE_HEADER, role = "members:${regularMembers.size}"))
                addAll(regularMembers.map { Item(TYPE_MEMBER, member = it) })
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return items[position].type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_ROLE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                RoleHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_member_card, parent, false)
                MemberViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item -> {
                when (holder) {
                    is RoleHeaderViewHolder -> holder.bind(item.role!!)
                    is MemberViewHolder -> holder.bind(item.member!!, currentUserId)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class RoleHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(role: String) {
            val roleText = when {
                role.startsWith("admins") -> itemView.context.getString(R.string.admins)
                role.startsWith("members:") -> {
                    val count = role.substringAfter(":").toIntOrNull() ?: 0
                    itemView.context.getString(R.string.members_section, count)
                }
                else -> role.replaceFirstChar { it.uppercaseChar() }
            }
            textView.text = roleText
            textView.textSize = 16f
            textView.setTextColor(itemView.context.getColor(R.color.on_surface))
            textView.setPadding(16, 16, 16, 8)
        }
    }

    class MemberViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val memberAvatar: ImageView = itemView.findViewById(R.id.member_avatar)
        private val memberAvatarFallback: TextView = itemView.findViewById(R.id.member_avatar_fallback)
        private val memberDisplayName: TextView = itemView.findViewById(R.id.member_display_name)
        private val adminBadge: TextView = itemView.findViewById(R.id.admin_badge)
        private val lastActive: TextView = itemView.findViewById(R.id.last_active)

        fun bind(member: GroupMember, currentUserId: String?) {
            // Display name with "(You)" suffix if current user
            val displayName = if (member.user_id == currentUserId) {
                "${member.display_name} ${itemView.context.getString(R.string.you)}"
            } else {
                member.display_name
            }
            memberDisplayName.text = displayName

            // Admin badge
            adminBadge.visibility = if (member.role == "creator" || member.role == "admin") {
                View.VISIBLE
            } else {
                View.GONE
            }

            // Avatar
            if (member.has_avatar) {
                memberAvatar.visibility = View.VISIBLE
                memberAvatarFallback.visibility = View.GONE
                
                val imageUrl = "${ApiClient.getBaseUrl()}/users/${member.user_id}/avatar"
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .error(R.drawable.ic_pursue_logo)
                    .into(memberAvatar)
            } else {
                memberAvatar.visibility = View.GONE
                memberAvatarFallback.visibility = View.VISIBLE
                
                // Show first letter
                val firstLetter = member.display_name.takeIf { it.isNotEmpty() }?.first()?.uppercaseChar() ?: '?'
                memberAvatarFallback.text = firstLetter.toString()
            }

            // Last active (using joined_at as placeholder - TODO: get last_active from API)
            lastActive.text = formatLastActive(member.joined_at)
        }

        private fun formatLastActive(isoTimestamp: String): String {
            return try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                val timestamp = format.parse(isoTimestamp) ?: return isoTimestamp
                val now = Date()
                val diffMs = now.time - timestamp.time
                val diffHours = TimeUnit.MILLISECONDS.toHours(diffMs)
                val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)
                
                val context = itemView.context
                when {
                    diffMs < 60000 -> context.getString(R.string.last_active_now)
                    diffHours < 1 -> context.getString(R.string.last_active_now)
                    diffHours == 1L -> context.getString(R.string.last_active, context.getString(R.string.hour_ago))
                    diffHours < 24 -> context.getString(R.string.last_active, context.getString(R.string.hours_ago, diffHours.toInt()))
                    diffDays == 1L -> context.getString(R.string.last_active, context.getString(R.string.day_ago))
                    else -> context.getString(R.string.last_active, context.getString(R.string.days_ago, diffDays.toInt()))
                }
            } catch (e: Exception) {
                isoTimestamp
            }
        }
    }
}
