package app.getpursue.ui.fragments.groups

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.models.GroupActivity
import app.getpursue.ui.adapters.GroupActivityAdapter
import app.getpursue.ui.dialogs.FullscreenPhotoDialog
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity Tab Fragment for Group Detail (UI spec section 4.3.3).
 * 
 * Displays activity feed grouped by date
 * with pull-to-refresh and 5-state pattern.
 */
class ActivityTabFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"

        fun newInstance(groupId: String): ActivityTabFragment {
            return ActivityTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                }
            }
        }
    }

    private var groupId: String? = null
    private lateinit var activityRecyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var errorStateContainer: FrameLayout
    private var adapter: GroupActivityAdapter? = null
    private var errorStateView: ErrorStateView? = null

    enum class ActivityUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private var currentState: ActivityUiState = ActivityUiState.LOADING
    private var cachedActivities: List<GroupActivity> = emptyList()
    private var currentUserId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_activity_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activityRecyclerView = view.findViewById(R.id.activity_recycler_view)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)

        // Setup RecyclerView
        activityRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GroupActivityAdapter(emptyList(), currentUserId, onPhotoClick = ::showFullscreenPhoto)
        activityRecyclerView.adapter = adapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadActivity()
        }

        // Load activity on first view
        loadActivity()
    }

    private fun loadActivity() {
        val groupId = this.groupId ?: return

        lifecycleScope.launch {
            // Show loading state (unless this is a pull-to-refresh)
            if (!swipeRefreshLayout.isRefreshing) {
                updateUiState(ActivityUiState.LOADING)
            }

            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    updateUiState(ActivityUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
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
                    ApiClient.getGroupActivity(accessToken, groupId)
                }

                cachedActivities = response.activities

                Handler(Looper.getMainLooper()).post {
                    if (response.activities.isEmpty()) {
                        updateUiState(ActivityUiState.SUCCESS_EMPTY)
                    } else {
                        adapter?.let { currentAdapter ->
                            val newAdapter = GroupActivityAdapter(response.activities, currentUserId, onPhotoClick = ::showFullscreenPhoto)
                            activityRecyclerView.adapter = newAdapter
                            adapter = newAdapter
                        } ?: run {
                            adapter = GroupActivityAdapter(response.activities, currentUserId, onPhotoClick = ::showFullscreenPhoto)
                            activityRecyclerView.adapter = adapter
                        }
                        updateUiState(ActivityUiState.SUCCESS_WITH_DATA)
                    }
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: ApiException) {
                cachedActivities = emptyList()
                Handler(Looper.getMainLooper()).post {
                    val errorType = ErrorStateView.errorTypeFromApiException(e)
                    updateUiState(ActivityUiState.ERROR, errorType)
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: Exception) {
                cachedActivities = emptyList()
                Handler(Looper.getMainLooper()).post {
                    updateUiState(ActivityUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun updateUiState(state: ActivityUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            ActivityUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                activityRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            ActivityUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                activityRecyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            ActivityUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                activityRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE

                // Setup empty state if not already set
                if (emptyStateContainer.childCount == 0) {
                    val emptyStateView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.empty_state_generic, emptyStateContainer, false)
                    emptyStateView.findViewById<TextView>(R.id.empty_title).text = getString(R.string.empty_activity_title)
                    emptyStateView.findViewById<TextView>(R.id.empty_message).text = getString(R.string.empty_activity_message)
                    emptyStateView.findViewById<MaterialButton>(R.id.empty_action_button)?.visibility = View.GONE
                    emptyStateContainer.addView(emptyStateView)
                }
            }
            ActivityUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                activityRecyclerView.visibility = View.GONE
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
                        loadActivity()
                    }
                }
                errorStateView?.setErrorType(currentErrorType)
                if (currentErrorType != ErrorStateView.ErrorType.PENDING_APPROVAL && currentErrorType != ErrorStateView.ErrorType.FORBIDDEN) {
                    errorStateView?.setCustomMessage(R.string.error_loading_activity, R.string.retry)
                }
            }
            ActivityUiState.OFFLINE -> {
                // TODO: Implement offline state with cached data
                updateUiState(ActivityUiState.SUCCESS_WITH_DATA)
            }
        }
    }

    private fun showFullscreenPhoto(photoUrl: String) {
        FullscreenPhotoDialog.newInstance(photoUrl)
            .show(childFragmentManager, "FullscreenPhotoDialog")
    }
}
