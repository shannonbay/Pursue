package app.getpursue.ui.fragments.home

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.R
import app.getpursue.models.Group
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import android.os.Handler
import android.os.Looper
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.adapters.GroupAdapter
import app.getpursue.ui.views.ErrorStateView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home Fragment showing the Groups List (UI spec section 4.2).
 * 
 * Displays a list of groups the user belongs to, with pull-to-refresh
 * and empty state handling. Implements 5-state pattern: Loading, Success-With Data,
 * Success-Empty, Error, Offline-Cached.
 */
class HomeFragment : Fragment() {

    interface Callbacks {
        fun onGroupSelected(group: Group)
        fun onCreateGroup()
        fun onStartChallenge()
        fun onJoinGroup()
        fun onGroupsLoaded(groups: List<Group>)
    }

    enum class GroupsUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private var callbacks: Callbacks? = null
    private lateinit var groupsRecyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var speedDialScrim: View
    private lateinit var speedDialContainer: View
    private lateinit var fabSpeedDialMain: FloatingActionButton
    private lateinit var fabJoinGroup: FloatingActionButton
    private lateinit var fabStartChallenge: FloatingActionButton
    private lateinit var fabCreateGroup: FloatingActionButton
    private lateinit var speedDialRowJoin: View
    private lateinit var speedDialRowChallenge: View
    private lateinit var speedDialRowCreate: View
    private lateinit var emptyStateView: View
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var errorStateContainer: FrameLayout
    private var adapter: GroupAdapter? = null
    private var errorStateView: ErrorStateView? = null
    private var currentState: GroupsUiState = GroupsUiState.LOADING
    private var cachedGroups: List<Group> = emptyList()
    private var isSpeedDialExpanded = false
    private val speedDialAnimDurationMs = 200L
    private var hasResumedOnce = false

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onDetach() {
        super.onDetach()
        callbacks = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        groupsRecyclerView = view.findViewById<RecyclerView>(R.id.groups_recycler_view)
        swipeRefreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        speedDialScrim = view.findViewById(R.id.speed_dial_scrim)
        speedDialContainer = view.findViewById(R.id.speed_dial_container)
        fabSpeedDialMain = view.findViewById(R.id.fab_speed_dial_main)
        fabJoinGroup = view.findViewById(R.id.fab_join_group)
        fabStartChallenge = view.findViewById(R.id.fab_start_challenge)
        fabCreateGroup = view.findViewById(R.id.fab_create_group)
        speedDialRowJoin = view.findViewById(R.id.speed_dial_row_join)
        speedDialRowChallenge = view.findViewById(R.id.speed_dial_row_challenge)
        speedDialRowCreate = view.findViewById(R.id.speed_dial_row_create)
        emptyStateView = view.findViewById<View>(R.id.empty_state)
        skeletonContainer = view.findViewById<LinearLayout>(R.id.skeleton_container)
        errorStateContainer = view.findViewById<FrameLayout>(R.id.error_state_container)

        // Setup RecyclerView
        groupsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GroupAdapter(emptyList()) { group ->
            callbacks?.onGroupSelected(group)
        }
        groupsRecyclerView.adapter = adapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadGroups()
        }

        // Setup speed dial FAB
        setupSpeedDial()

        // Setup top app bar menu
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // Top app bar is handled by MainAppActivity
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // Listen for join group result (e.g., pending status from JoinGroupBottomSheet)
        parentFragmentManager.setFragmentResultListener("join_group_result", viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean("refresh_needed", false)) {
                refreshGroups()
            }
        }

        // Load groups on first view
        loadGroups()
    }

    override fun onResume() {
        super.onResume()
        if (!hasResumedOnce) {
            hasResumedOnce = true
            return
        }
        refreshGroups()
    }

    private fun setupSpeedDial() {
        fabSpeedDialMain.contentDescription = getString(R.string.fab_open_menu)
        fabSpeedDialMain.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (isSpeedDialExpanded) collapseSpeedDial() else expandSpeedDial()
        }
        speedDialScrim.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            collapseSpeedDial()
        }
        fabJoinGroup.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks?.onJoinGroup()
            collapseSpeedDial()
        }
        fabCreateGroup.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks?.onCreateGroup()
            collapseSpeedDial()
        }
        fabStartChallenge.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks?.onStartChallenge()
            collapseSpeedDial()
        }
        // Label clicks also trigger action (same as mini FAB)
        speedDialRowJoin.findViewById<View>(R.id.speed_dial_label_join).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks?.onJoinGroup()
            collapseSpeedDial()
        }
        speedDialRowCreate.findViewById<View>(R.id.speed_dial_label_create).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks?.onCreateGroup()
            collapseSpeedDial()
        }
        speedDialRowChallenge.findViewById<View>(R.id.speed_dial_label_challenge).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            callbacks?.onStartChallenge()
            collapseSpeedDial()
        }
    }

    private fun expandSpeedDial() {
        if (isSpeedDialExpanded) return
        isSpeedDialExpanded = true
        fabSpeedDialMain.contentDescription = getString(R.string.fab_close_menu)
        speedDialScrim.visibility = View.VISIBLE
        speedDialScrim.alpha = 0f
        speedDialScrim.animate().alpha(1f).setDuration(speedDialAnimDurationMs).start()
        fabSpeedDialMain.animate().rotation(45f).setDuration(speedDialAnimDurationMs).start()
        speedDialRowJoin.visibility = View.VISIBLE
        speedDialRowChallenge.visibility = View.VISIBLE
        speedDialRowCreate.visibility = View.VISIBLE
        speedDialRowJoin.alpha = 0f
        speedDialRowJoin.translationY = 24f
        speedDialRowChallenge.alpha = 0f
        speedDialRowChallenge.translationY = 24f
        speedDialRowCreate.alpha = 0f
        speedDialRowCreate.translationY = 24f
        speedDialRowJoin.animate()
            .alpha(1f).translationY(0f).setDuration(speedDialAnimDurationMs).start()
        speedDialRowChallenge.animate()
            .alpha(1f).translationY(0f).setDuration(speedDialAnimDurationMs).start()
        speedDialRowCreate.animate()
            .alpha(1f).translationY(0f).setDuration(speedDialAnimDurationMs).start()
    }

    private fun collapseSpeedDial() {
        if (!isSpeedDialExpanded) return
        isSpeedDialExpanded = false
        fabSpeedDialMain.contentDescription = getString(R.string.fab_open_menu)
        speedDialScrim.animate().alpha(0f).setDuration(speedDialAnimDurationMs).withEndAction {
            speedDialScrim.visibility = View.GONE
        }.start()
        fabSpeedDialMain.animate().rotation(0f).setDuration(speedDialAnimDurationMs).start()
        speedDialRowJoin.animate()
            .alpha(0f).translationY(24f).setDuration(speedDialAnimDurationMs).withEndAction {
                speedDialRowJoin.visibility = View.GONE
            }.start()
        speedDialRowChallenge.animate()
            .alpha(0f).translationY(24f).setDuration(speedDialAnimDurationMs).withEndAction {
                speedDialRowChallenge.visibility = View.GONE
            }.start()
        speedDialRowCreate.animate()
            .alpha(0f).translationY(24f).setDuration(speedDialAnimDurationMs).withEndAction {
                speedDialRowCreate.visibility = View.GONE
            }.start()
    }

    /**
     * Refresh groups from API. Called when returning from Group Detail after delete.
     */
    fun refreshGroups() {
        loadGroups()
    }

    /**
     * Load groups from API and update the UI.
     */
    private fun loadGroups() {
        lifecycleScope.launch {
            // Show loading state (unless this is a pull-to-refresh, which uses the swipe indicator)
            if (!swipeRefreshLayout.isRefreshing) {
                updateUiState(GroupsUiState.LOADING)
            }
            
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                
                if (accessToken == null) {
                    updateUiState(GroupsUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        swipeRefreshLayout.isRefreshing = false
                    }
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.getMyGroups(accessToken)
                }

                if (!isAdded) return@launch

                // Cache the groups
                cachedGroups = response.groups

                // Update adapter (GlideModule handles authorization automatically)
                adapter?.let { currentAdapter ->
                    val newAdapter = GroupAdapter(response.groups) { group ->
                        callbacks?.onGroupSelected(group)
                    }
                    groupsRecyclerView.adapter = newAdapter
                    adapter = newAdapter
                } ?: run {
                    adapter = GroupAdapter(response.groups) { group ->
                        callbacks?.onGroupSelected(group)
                    }
                    groupsRecyclerView.adapter = adapter
                }

                // Update state based on data
                if (response.groups.isEmpty()) {
                    updateUiState(GroupsUiState.SUCCESS_EMPTY)
                } else {
                    updateUiState(GroupsUiState.SUCCESS_WITH_DATA)
                }

                // Notify activity that groups have loaded (used for over-limit check)
                callbacks?.onGroupsLoaded(cachedGroups)

            } catch (e: ApiException) {
                // Determine error type
                val errorType = when (e.code) {
                    401 -> ErrorStateView.ErrorType.UNAUTHORIZED
                    500, 503 -> ErrorStateView.ErrorType.SERVER
                    0 -> ErrorStateView.ErrorType.NETWORK
                    else -> ErrorStateView.ErrorType.GENERIC
                }

                // Check if we have cached data for offline state
                if (cachedGroups.isNotEmpty() && (e.code == 0 || e.message?.contains("Network", ignoreCase = true) == true)) {
                    updateUiState(GroupsUiState.OFFLINE)
                    showOfflineBanner()
                } else {
                    updateUiState(GroupsUiState.ERROR, errorType)
                }

                // Ensure Toast runs on main thread with looper to avoid issues in tests
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    Toast.makeText(requireContext(), getString(R.string.error_loading_groups), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Check if we have cached data for offline state
                if (cachedGroups.isNotEmpty()) {
                    updateUiState(GroupsUiState.OFFLINE)
                    showOfflineBanner()
                } else {
                    updateUiState(GroupsUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                }

                // Ensure Toast runs on main thread with looper to avoid issues in tests
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    Toast.makeText(requireContext(), getString(R.string.error_loading_groups), Toast.LENGTH_SHORT).show()
                }
            } finally {
                // Ensure setRefreshing runs on main thread with looper to avoid issues in tests
                // Use Handler.post to guarantee execution on main looper thread
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    /**
     * Update UI state and show/hide appropriate views.
     */
    private fun updateUiState(state: GroupsUiState, errorType: ErrorStateView.ErrorType? = null) {
        if (!isAdded) return
        currentState = state

        when (state) {
            GroupsUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                groupsRecyclerView.visibility = View.GONE
                emptyStateView.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
                speedDialContainer.visibility = View.GONE
            }
            GroupsUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                groupsRecyclerView.visibility = View.VISIBLE
                emptyStateView.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
                speedDialContainer.visibility = View.VISIBLE
            }
            GroupsUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                groupsRecyclerView.visibility = View.GONE
                emptyStateView.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE
                speedDialContainer.visibility = View.VISIBLE
            }
            GroupsUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                groupsRecyclerView.visibility = View.GONE
                emptyStateView.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE
                
                // Create or update error state view
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.Companion.inflate(LayoutInflater.from(requireContext()), errorStateContainer, false)
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener {
                        loadGroups()
                    }
                }
                errorType?.let { errorStateView?.setErrorType(it) }
                speedDialContainer.visibility = View.VISIBLE
            }
            GroupsUiState.OFFLINE -> {
                skeletonContainer.visibility = View.GONE
                // Show cached data (slightly dimmed)
                if (cachedGroups.isNotEmpty()) {
                    adapter = GroupAdapter(cachedGroups) { group ->
                        callbacks?.onGroupSelected(group)
                    }
                    groupsRecyclerView.adapter = adapter
                    groupsRecyclerView.visibility = View.VISIBLE
                    groupsRecyclerView.alpha = 0.6f // Dim the view
                } else {
                    groupsRecyclerView.visibility = View.GONE
                }
                emptyStateView.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
                speedDialContainer.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Show offline banner with retry action.
     */
    private fun showOfflineBanner() {
        val snackbar = Snackbar.make(swipeRefreshLayout, R.string.offline_banner_title, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.retry) {
            loadGroups()
        }
        snackbar.show()
    }

    companion object {
        fun newInstance(): HomeFragment = HomeFragment()
    }
}
