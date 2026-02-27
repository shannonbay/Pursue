package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import app.getpursue.models.GroupMember
import app.getpursue.utils.ImageUtils
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Full-grid bottom sheet for Large groups (21+ members).
 * Shows all members in a 3-column grid. Tapping opens logged/nudge sub-sheet.
 */
class PulseGridBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "PulseGridBottomSheet"
        private const val ARG_MEMBERS_JSON      = "members_json"
        private const val ARG_CURRENT_USER_ID   = "current_user_id"
        private const val ARG_GROUP_ID          = "group_id"
        private val gson = Gson()

        fun show(
            fm: FragmentManager,
            members: List<GroupMember>,
            currentUserId: String,
            groupId: String
        ) {
            PulseGridBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MEMBERS_JSON, gson.toJson(members))
                    putString(ARG_CURRENT_USER_ID, currentUserId)
                    putString(ARG_GROUP_ID, groupId)
                }
            }.show(fm, TAG)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_pulse_grid, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val membersJson   = arguments?.getString(ARG_MEMBERS_JSON) ?: return
        val currentUserId = arguments?.getString(ARG_CURRENT_USER_ID) ?: ""
        val groupId       = arguments?.getString(ARG_GROUP_ID) ?: ""

        val type    = object : TypeToken<List<GroupMember>>() {}.type
        val members: List<GroupMember> = Gson().fromJson(membersJson, type) ?: emptyList()

        val headerLabel = view.findViewById<TextView>(R.id.pulse_grid_header_label)
        val headerCount = view.findViewById<TextView>(R.id.pulse_grid_header_count)
        val recycler    = view.findViewById<RecyclerView>(R.id.pulse_grid_recycler)

        val loggedCount = members.count { it.logged_this_period }
        val allLogged   = loggedCount == members.size
        headerLabel.text = if (allLogged)
            getString(R.string.pulse_header_everyone_logged)
        else
            getString(R.string.pulse_header_today)
        headerCount.text = getString(R.string.pulse_logged_count, loggedCount, members.size)

        recycler.layoutManager = GridLayoutManager(requireContext(), 3)
        recycler.adapter = PulseGridAdapter(members, currentUserId, groupId)
    }

    // --- Inner adapter ---

    private inner class PulseGridAdapter(
        private val members: List<GroupMember>,
        private val currentUserId: String,
        private val groupId: String
    ) : RecyclerView.Adapter<PulseGridAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val avatar: ImageView = root.findViewById(R.id.grid_avatar_image)
            val name: TextView    = root.findViewById(R.id.grid_member_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pulse_grid_member, parent, false))

        override fun getItemCount() = members.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val member   = members[position]
            val isActive = member.logged_this_period

            // Name (max 8 chars, ellipsize handled in XML)
            holder.name.text = member.display_name

            // Avatar
            if (member.has_avatar) {
                val avatarUrl = "${ApiClient.getBaseUrl()}/users/${member.user_id}/avatar"
                Glide.with(holder.root.context)
                    .load(avatarUrl)
                    .circleCrop()
                    .into(holder.avatar)
            } else {
                val color = DailyPulseWidget.avatarColorForUser(member.user_id)
                holder.avatar.setImageDrawable(
                    ImageUtils.createLetterAvatar(holder.root.context, member.display_name, color)
                )
            }

            holder.avatar.alpha = if (isActive) 1f else 0.5f
            holder.name.alpha   = if (isActive) 1f else 0.5f

            holder.root.setOnClickListener {
                val fm = childFragmentManager
                if (member.logged_this_period) {
                    PulseLoggedMemberBottomSheet.show(
                        fm,
                        memberId    = member.user_id,
                        displayName = member.display_name,
                        hasAvatar   = member.has_avatar,
                        lastLogAt   = member.last_log_at
                    )
                } else {
                    PulseNudgeBottomSheet.show(
                        fm,
                        memberId    = member.user_id,
                        displayName = member.display_name,
                        hasAvatar   = member.has_avatar,
                        groupId     = groupId
                    )
                }
            }
        }
    }
}
