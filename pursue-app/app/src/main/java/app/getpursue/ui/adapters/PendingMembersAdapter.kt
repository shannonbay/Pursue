package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.data.network.ApiClient
import app.getpursue.models.PendingMember
import app.getpursue.utils.RelativeTimeUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import app.getpursue.R
import com.google.android.material.button.MaterialButton

/**
 * Adapter for pending join request cards (Approve / Decline per item).
 */
class PendingMembersAdapter(
    private var items: MutableList<PendingMember>,
    private val onApprove: (PendingMember) -> Unit,
    private val onDecline: (PendingMember) -> Unit
) : RecyclerView.Adapter<PendingMembersAdapter.ViewHolder>() {

    fun setLoadingForMember(userId: String, loading: Boolean) {
        if (loading) loadingUserIds.add(userId) else loadingUserIds.remove(userId)
        val index = items.indexOfFirst { it.user_id == userId }
        if (index >= 0) notifyItemChanged(index)
    }

    fun removeMember(member: PendingMember) {
        val index = items.indexOfFirst { it.user_id == member.user_id }
        if (index >= 0) {
            loadingUserIds.remove(member.user_id)
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun getMemberAt(position: Int): PendingMember? = items.getOrNull(position)

    private val loadingUserIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_member_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = items.getOrNull(position) ?: return
        val loading = loadingUserIds.contains(member.user_id)
        holder.bind(member, loading, onApprove, onDecline)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: ImageView = itemView.findViewById(R.id.pending_member_avatar)
        private val avatarFallback: TextView = itemView.findViewById(R.id.pending_member_avatar_fallback)
        private val displayName: TextView = itemView.findViewById(R.id.pending_member_display_name)
        private val requestedAt: TextView = itemView.findViewById(R.id.pending_member_requested_at)
        private val approveButton: MaterialButton = itemView.findViewById(R.id.pending_member_approve)
        private val declineButton: MaterialButton = itemView.findViewById(R.id.pending_member_decline)
        private val progress: ProgressBar = itemView.findViewById(R.id.pending_member_progress)

        fun bind(
            member: PendingMember,
            loading: Boolean,
            onApprove: (PendingMember) -> Unit,
            onDecline: (PendingMember) -> Unit
        ) {
            displayName.text = member.display_name
            requestedAt.text = itemView.context.getString(
                R.string.requested_ago,
                RelativeTimeUtils.formatRelativeTime(itemView.context, member.requested_at)
            )

            if (member.has_avatar) {
                avatar.visibility = View.VISIBLE
                avatarFallback.visibility = View.GONE
                val imageUrl = "${ApiClient.getBaseUrl()}/users/${member.user_id}/avatar"
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .error(R.drawable.ic_pursue_logo)
                    .into(avatar)
            } else {
                avatar.visibility = View.GONE
                avatarFallback.visibility = View.VISIBLE
                val firstLetter = member.display_name.takeIf { it.isNotEmpty() }?.first()?.uppercaseChar() ?: '?'
                avatarFallback.text = firstLetter.toString()
            }

            progress.visibility = if (loading) View.VISIBLE else View.GONE
            approveButton.isEnabled = !loading
            declineButton.isEnabled = !loading

            approveButton.contentDescription = itemView.context.getString(R.string.content_description_approve, member.display_name)
            declineButton.contentDescription = itemView.context.getString(R.string.content_description_decline, member.display_name)

            approveButton.setOnClickListener { onApprove(member) }
            declineButton.setOnClickListener { onDecline(member) }
        }
    }
}
