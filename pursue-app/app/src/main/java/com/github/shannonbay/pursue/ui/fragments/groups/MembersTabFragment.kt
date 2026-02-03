package com.github.shannonbay.pursue.ui.fragments.groups

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.ui.activities.GroupDetailActivity
import com.github.shannonbay.pursue.ui.views.ErrorStateView
import com.github.shannonbay.pursue.ui.adapters.GroupMembersAdapter
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.models.GroupMember
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
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
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)
        pendingRequestsCard = view.findViewById(R.id.pending_requests_card)
        pendingRequestsLabel = view.findViewById(R.id.pending_requests_label)

        pendingRequestsCard.setOnClickListener {
            val gid = groupId ?: return@setOnClickListener
            val groupName = (requireActivity() as? GroupDetailActivity)?.intent?.getStringExtra(GroupDetailActivity.EXTRA_GROUP_NAME) ?: ""
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
                        adapter?.let { currentAdapter ->
                            val newAdapter = GroupMembersAdapter(response.members)
                            membersRecyclerView.adapter = newAdapter
                            adapter = newAdapter
                        } ?: run {
                            adapter = GroupMembersAdapter(response.members)
                            membersRecyclerView.adapter = adapter
                        }
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

    private fun updateUiState(state: MembersUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            MembersUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                membersRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            MembersUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                membersRecyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            MembersUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                membersRecyclerView.visibility = View.GONE
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
