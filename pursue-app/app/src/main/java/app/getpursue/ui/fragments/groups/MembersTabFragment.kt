package app.getpursue.ui.fragments.groups

import android.text.Editable
import android.text.TextWatcher
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.ui.adapters.GroupMembersAdapter
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.models.GroupMember
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import androidx.appcompat.app.AlertDialog
import app.getpursue.utils.RelativeTimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Members Tab Fragment for Group Detail (UI spec section 4.3.2).
 * 
 * Displays members grouped by role (Admins, Members)
 * with pull-to-refresh and 5-state pattern.
 */
class MembersTabFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_IS_ADMIN = "is_admin"
        private const val MENU_PROMOTE = 1
        private const val MENU_REMOVE = 2

        fun newInstance(groupId: String, isAdmin: Boolean = false): MembersTabFragment {
            return MembersTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putBoolean(ARG_IS_ADMIN, isAdmin)
                }
            }
        }
    }

    private var groupId: String? = null
    private var isAdmin: Boolean = false
    private lateinit var membersRecyclerView: RecyclerView
    private lateinit var membersScrollView: NestedScrollView
    private lateinit var membersListContainer: LinearLayout
    private lateinit var pendingRequestsCard: MaterialCardView
    private lateinit var pendingRequestsLabel: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var errorStateContainer: FrameLayout
    private var adapter: GroupMembersAdapter? = null
    private var errorStateView: ErrorStateView? = null

    enum class MembersUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private var currentState: MembersUiState = MembersUiState.LOADING
    private var cachedMembers: List<GroupMember> = emptyList()
    private var currentUserId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
        isAdmin = arguments?.getBoolean(ARG_IS_ADMIN) ?: false
    }

    fun updateAdminStatus(newIsAdmin: Boolean) {
        if (isAdmin != newIsAdmin) {
            isAdmin = newIsAdmin
            loadMembers()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_members_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        membersRecyclerView = view.findViewById(R.id.members_recycler_view)
        membersScrollView = view.findViewById(R.id.members_scroll_view)
        membersListContainer = view.findViewById(R.id.members_list_container)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)
        pendingRequestsCard = view.findViewById(R.id.pending_requests_card)
        pendingRequestsLabel = view.findViewById(R.id.pending_requests_label)

        pendingRequestsCard.setOnClickListener {
            val gid = groupId ?: return@setOnClickListener
            val groupName = (requireActivity() as? GroupDetailActivity)?.intent?.getStringExtra(
                GroupDetailActivity.Companion.EXTRA_GROUP_NAME) ?: ""
            requireActivity().supportFragmentManager.commit {
                replace(R.id.fragment_container, PendingApprovalsFragment.newInstance(gid, groupName))
                addToBackStack(null)
            }
        }

        // Setup RecyclerView
        membersRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GroupMembersAdapter(emptyList())
        membersRecyclerView.adapter = adapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadMembers()
        }

        // Load members on first view
        loadMembers()
    }

    private fun loadMembers() {
        val groupId = this.groupId ?: return

        lifecycleScope.launch {
            // Show loading state (unless this is a pull-to-refresh)
            if (!swipeRefreshLayout.isRefreshing) {
                updateUiState(MembersUiState.LOADING)
            }

            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    updateUiState(MembersUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                    Handler(Looper.getMainLooper()).post {
                        swipeRefreshLayout.isRefreshing = false
                    }
                    return@launch
                }

                if (currentUserId == null) {
                    try {
                        currentUserId = withContext(Dispatchers.IO) {
                            ApiClient.getMyUser(accessToken).id
                        }
                    } catch (_: Exception) { /* ignore */ }
                }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.getGroupMembers(accessToken, groupId)
                }

                var pendingCount = 0
                if (isAdmin) {
                    try {
                        val pendingResponse = withContext(Dispatchers.IO) {
                            ApiClient.getPendingMembers(accessToken, groupId)
                        }
                        pendingCount = pendingResponse.pending_members.size
                    } catch (_: Exception) {
                        // Non-admin or error: keep pending count 0
                    }
                }

                val finalPendingCount = pendingCount
                cachedMembers = response.members

                Handler(Looper.getMainLooper()).post {
                    if (isAdmin && finalPendingCount > 0) {
                        pendingRequestsCard.visibility = View.VISIBLE
                        pendingRequestsLabel.text = getString(R.string.pending_requests_card, finalPendingCount)
                        pendingRequestsCard.contentDescription = getString(R.string.content_description_pending_count, finalPendingCount)
                    } else {
                        pendingRequestsCard.visibility = View.GONE
                    }
                    if (response.members.isEmpty()) {
                        updateUiState(MembersUiState.SUCCESS_EMPTY)
                    } else {
                        populateMembersScrollView(response.members)
                        updateUiState(MembersUiState.SUCCESS_WITH_DATA)
                    }
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: ApiException) {
                cachedMembers = emptyList()
                Handler(Looper.getMainLooper()).post {
                    val errorType = ErrorStateView.errorTypeFromApiException(e)
                    updateUiState(MembersUiState.ERROR, errorType)
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: Exception) {
                cachedMembers = emptyList()
                Handler(Looper.getMainLooper()).post {
                    updateUiState(MembersUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    /**
     * Populate the ScrollView with role headers and member cards (same order as GroupMembersAdapter).
     */
    private fun populateMembersScrollView(members: List<GroupMember>) {
        membersListContainer.layoutTransition = null
        membersListContainer.removeAllViews()

        val admins = members.filter { it.role == "creator" || it.role == "admin" }
        val regularMembers = members.filter { it.role == "member" }

        if (admins.isNotEmpty()) {
            val headerView = TextView(requireContext()).apply {
                text = getString(R.string.admins)
                textSize = 16f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                setPadding(16, 16, 16, 8)
            }
            membersListContainer.addView(headerView)
            admins.forEach { member ->
                val cardView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_member_card, membersListContainer, false)
                bindMemberCardView(cardView, member)
                membersListContainer.addView(cardView)
            }
        }
        if (regularMembers.isNotEmpty()) {
            val headerView = TextView(requireContext()).apply {
                text = getString(R.string.members_section, regularMembers.size)
                textSize = 16f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                setPadding(16, 16, 16, 8)
            }
            membersListContainer.addView(headerView)
            regularMembers.forEach { member ->
                val cardView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_member_card, membersListContainer, false)
                bindMemberCardView(cardView, member)
                membersListContainer.addView(cardView)
            }
        }

        membersScrollView.visibility = View.VISIBLE
        membersRecyclerView.visibility = View.GONE
    }

    private fun bindMemberCardView(view: View, member: GroupMember) {
        val memberAvatar = view.findViewById<ImageView>(R.id.member_avatar)
        val memberAvatarFallback = view.findViewById<TextView>(R.id.member_avatar_fallback)
        val memberDisplayName = view.findViewById<TextView>(R.id.member_display_name)
        val adminBadge = view.findViewById<TextView>(R.id.admin_badge)
        val lastActive = view.findViewById<TextView>(R.id.last_active)

        val displayName = if (member.user_id == currentUserId) {
            "${member.display_name} ${requireContext().getString(R.string.you)}"
        } else {
            member.display_name
        }
        memberDisplayName.text = displayName

        adminBadge.visibility = if (member.role == "creator" || member.role == "admin") {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (member.has_avatar) {
            memberAvatar.visibility = View.VISIBLE
            memberAvatarFallback.visibility = View.GONE
            val imageUrl = "${ApiClient.getBaseUrl()}/users/${member.user_id}/avatar"
            Glide.with(requireContext())
                .load(imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .error(R.drawable.ic_pursue_logo)
                .into(memberAvatar)
        } else {
            memberAvatar.visibility = View.GONE
            memberAvatarFallback.visibility = View.VISIBLE
            val firstLetter = member.display_name.takeIf { it.isNotEmpty() }?.first()?.uppercaseChar() ?: '?'
            memberAvatarFallback.text = firstLetter.toString()
        }

        lastActive.text = RelativeTimeUtils.formatRelativeTime(requireContext(), member.joined_at)

        if (isAdmin) {
            view.setOnLongClickListener {
                showMemberContextMenu(view, member)
                true
            }
        } else {
            view.setOnLongClickListener(null)
        }
    }

    private fun showMemberContextMenu(anchor: View, member: GroupMember) {
        val popup = PopupMenu(requireContext(), anchor)
        val showPromote = member.role == "member"
        val showRemove = member.role != "creator" && member.user_id != currentUserId
        if (!showPromote && !showRemove) return
        if (showPromote) {
            popup.menu.add(0, MENU_PROMOTE, 0, getString(R.string.promote_to_admin))
        }
        if (showRemove) {
            popup.menu.add(0, MENU_REMOVE, 0, getString(R.string.remove_from_group))
        }
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                MENU_PROMOTE -> {
                    showConfirmPromoteDialog(member)
                    true
                }
                MENU_REMOVE -> {
                    showConfirmRemoveDialog(member)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showConfirmPromoteDialog(member: GroupMember) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_promote_title, member.display_name))
            .setMessage(getString(R.string.confirm_promote_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.promote_to_admin)) { _, _ -> performPromote(member) }
            .show()
    }

    private fun showConfirmRemoveDialog(member: GroupMember) {
        val view = layoutInflater.inflate(R.layout.dialog_remove_member_confirm, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.confirm_remove_member_title, member.display_name))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.remove_from_group)) { _, _ -> performRemove(member) }
            .create()
        dialog.show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.isEnabled = false
        positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
        val editText = view.findViewById<TextInputEditText>(R.id.remove_confirm_input)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                positiveButton?.isEnabled = (editText.text?.toString()?.trim() == "remove")
            }
        })
    }

    private fun performPromote(member: GroupMember) {
        val gid = groupId ?: return
        lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ApiClient.updateMemberRole(token, gid, member.user_id, "admin")
                }
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), getString(R.string.member_promoted_toast), Toast.LENGTH_SHORT).show()
                loadMembers()
            } catch (e: ApiException) {
                if (isAdded) {
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performRemove(member: GroupMember) {
        val gid = groupId ?: return
        lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ApiClient.removeMember(token, gid, member.user_id)
                }
                if (!isAdded) return@launch
                Toast.makeText(requireContext(), getString(R.string.member_removed_toast), Toast.LENGTH_SHORT).show()
                loadMembers()
            } catch (e: ApiException) {
                if (isAdded) {
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUiState(state: MembersUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            MembersUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                membersRecyclerView.visibility = View.GONE
                membersScrollView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            MembersUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                membersScrollView.visibility = View.VISIBLE
                membersRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            MembersUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                membersRecyclerView.visibility = View.GONE
                membersScrollView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE

                // Setup empty state if not already set
                if (emptyStateContainer.childCount == 0) {
                    val emptyStateView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.empty_state_generic, emptyStateContainer, false)
                    emptyStateView.findViewById<TextView>(R.id.empty_title).text = getString(R.string.empty_members_title)
                    emptyStateView.findViewById<TextView>(R.id.empty_message).text = getString(R.string.empty_members_message)
                    val actionButton = emptyStateView.findViewById<MaterialButton>(R.id.empty_action_button)
                    actionButton?.text = getString(R.string.fab_invite)
                    actionButton?.visibility = View.VISIBLE
                    actionButton?.setOnClickListener {
                        (requireParentFragment() as? GroupDetailFragment)?.showInviteMembersSheet()
                    }
                    emptyStateContainer.addView(emptyStateView)
                }
            }
            MembersUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                membersRecyclerView.visibility = View.GONE
                membersScrollView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE

                // Setup error state if not already set or error type changed
                val currentErrorType = errorType ?: ErrorStateView.ErrorType.NETWORK
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.Companion.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener {
                        loadMembers()
                    }
                }
                errorStateView?.setErrorType(currentErrorType)
                if (currentErrorType != ErrorStateView.ErrorType.PENDING_APPROVAL && currentErrorType != ErrorStateView.ErrorType.FORBIDDEN) {
                    errorStateView?.setCustomMessage(R.string.error_loading_members, R.string.retry)
                }
            }
            MembersUiState.OFFLINE -> {
                // TODO: Implement offline state with cached data
                updateUiState(MembersUiState.SUCCESS_WITH_DATA)
            }
        }
    }
}
