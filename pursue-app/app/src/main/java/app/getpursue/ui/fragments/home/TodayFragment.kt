package app.getpursue.ui.fragments.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.views.DailyPulseWidget
import app.getpursue.ui.views.EmptyStateView
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import androidx.fragment.app.commit
import app.getpursue.models.GroupMember
import app.getpursue.models.TodayGoalsResponse
import app.getpursue.ui.adapters.TodayGoalAdapter
import app.getpursue.ui.fragments.goals.GoalDetailFragment
import app.getpursue.ui.handlers.GoalLogProgressHandler
import app.getpursue.ui.views.OnboardingTooltip
import app.getpursue.data.prefs.OnboardingPrefs
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Today Screen fragment (UI spec section 4.4).
 * 
 * Displays today's daily goals with 5-state pattern: Loading, Success-With Data,
 * Success-Empty, Error, Offline-Cached.
 */
class TodayFragment : Fragment() {

    interface Callbacks {
        fun onViewGroups()
    }

    enum class TodayUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private lateinit var dailyPulseWidget: DailyPulseWidget
    private lateinit var goalsRecyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var headerContainer: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var progressText: TextView
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var errorStateContainer: FrameLayout
    private var adapter: TodayGoalAdapter? = null
    private var errorStateView: ErrorStateView? = null
    private var emptyStateView: EmptyStateView? = null
    private var currentState: TodayUiState = TodayUiState.LOADING
    private var cachedData: TodayGoalsResponse? = null
    private var logProgressHandler: GoalLogProgressHandler? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_today, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dailyPulseWidget = view.findViewById(R.id.daily_pulse_widget)
        goalsRecyclerView = view.findViewById<RecyclerView>(R.id.goals_recycler_view)
        swipeRefreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        headerContainer = view.findViewById<LinearLayout>(R.id.header_container)
        dateText = view.findViewById<TextView>(R.id.date_text)
        progressText = view.findViewById<TextView>(R.id.progress_text)
        skeletonContainer = view.findViewById<LinearLayout>(R.id.skeleton_container)
        emptyStateContainer = view.findViewById<FrameLayout>(R.id.empty_state_container)
        errorStateContainer = view.findViewById<FrameLayout>(R.id.error_state_container)

        dailyPulseWidget.setFragmentManager(childFragmentManager, "")
        dailyPulseWidget.showLoading()

        // Setup RecyclerView
        goalsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        goalsRecyclerView.isNestedScrollingEnabled = false
        val userDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val userTimezone = ZoneId.systemDefault().id
        logProgressHandler = GoalLogProgressHandler(
            fragment = this,
            snackbarParentView = swipeRefreshLayout,
            tokenSupplier = {
                context?.let { SecureTokenManager.Companion.getInstance(it).getAccessToken() }
            },
            userDate = userDate,
            userTimezone = userTimezone,
            onOptimisticUpdate = { goalId, completed, progressValue ->
                updateCachedGoal(goalId, completed, progressValue)
            },
            onRefresh = { silent -> loadTodayGoals(silent = silent) }
        )
        adapter = TodayGoalAdapter(emptyList(), onGoalClick = { goal, groupId ->
            logProgressHandler?.handleCardBodyClick(
                GoalLogProgressHandler.Companion.fromTodayGoal(
                    goal,
                    groupId
                )
            )
        }, onArrowClick = { goal ->
            if (isAdded) {
                try {
                    val goalDetailFragment = GoalDetailFragment.Companion.newInstance(goal.goal_id)
                    requireActivity().supportFragmentManager.commit {
                        replace(R.id.fragment_container, goalDetailFragment)
                        addToBackStack(null)
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Navigation failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, onFirstGoalBound = { anchor ->
            val prefs = OnboardingPrefs.getInstance(requireContext())
            if (!prefs.hasShownTapToLogTooltip) {
                anchor.post {
                    if (isAdded) {
                        OnboardingTooltip.show(anchor, R.string.onboarding_tap_to_log_tooltip)
                        prefs.hasShownTapToLogTooltip = true
                    }
                }
            }
        })
        goalsRecyclerView.adapter = adapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadTodayGoals()
        }

        // Load goals on first view
        loadTodayGoals()
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.today_title)
    }

    /**
     * Update a goal in cached data (optimistic update) and refresh the adapter.
     */
    private fun updateCachedGoal(goalId: String, completed: Boolean, progressValue: Double?) {
        // Update cached data for consistency
        val data = cachedData ?: return
        val updatedGroups = data.groups.map { group ->
            val updatedGoals = group.goals.map { goal ->
                if (goal.goal_id == goalId) {
                    goal.copy(
                        completed = completed,
                        progress_value = progressValue?.toInt()
                    )
                } else goal
            }
            group.copy(goals = updatedGoals)
        }
        cachedData = data.copy(groups = updatedGroups)

        // Recalculate and update header completion percentage
        val totalGoals = updatedGroups.sumOf { it.goals.size }
        val completedGoals = updatedGroups.sumOf { group -> group.goals.count { it.completed } }
        val newPercent = if (totalGoals > 0) (completedGoals * 100 / totalGoals) else 0
        progressText.text = getString(R.string.today_complete, newPercent)

        // Update adapter in-place to preserve scroll position
        adapter?.updateGoal(goalId, completed, progressValue?.toInt())
    }

    /**
     * Load today's goals from API and update the UI.
     * @param silent If true, skip loading/skeleton state (e.g. after optimistic delete); just fetch and update.
     */
    private fun loadTodayGoals(silent: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Show loading state unless pull-to-refresh (spinner) or silent refresh (e.g. post-delete)
            if (!silent && !swipeRefreshLayout.isRefreshing) {
                updateUiState(TodayUiState.LOADING)
            }

            try {
                val ctx = context ?: return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    if (isAdded) {
                        updateUiState(TodayUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                        swipeRefreshLayout.isRefreshing = false
                    }
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.getTodayGoals(accessToken)
                }

                // Check if fragment is still attached before updating UI
                if (!isAdded) return@launch

                // Cache the data
                cachedData = response

                // Update UI with data
                updateDateAndProgress(response)
                adapter = TodayGoalAdapter(response.groups, onGoalClick = { goal, groupId ->
                    logProgressHandler?.handleCardBodyClick(
                        GoalLogProgressHandler.Companion.fromTodayGoal(
                            goal,
                            groupId
                        )
                    )
                }, onArrowClick = { goal ->
                    if (isAdded) {
                        try {
                            val goalDetailFragment =
                                GoalDetailFragment.Companion.newInstance(goal.goal_id)
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, goalDetailFragment)
                                addToBackStack(null)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                requireContext(),
                                "Navigation failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }, onFirstGoalBound = { anchor ->
                    val onboardingPrefs = OnboardingPrefs.getInstance(requireContext())
                    if (!onboardingPrefs.hasShownTapToLogTooltip) {
                        anchor.post {
                            if (isAdded) {
                                OnboardingTooltip.show(anchor, R.string.onboarding_tap_to_log_tooltip)
                                onboardingPrefs.hasShownTapToLogTooltip = true
                            }
                        }
                    }
                })
                goalsRecyclerView.adapter = adapter

                // Update state based on data
                val totalGoals = response.groups.sumOf { it.goals.size }
                if (totalGoals == 0) {
                    updateUiState(TodayUiState.SUCCESS_EMPTY)
                } else {
                    updateUiState(TodayUiState.SUCCESS_WITH_DATA)
                }

                // Load pulse in parallel — goals are already visible
                viewLifecycleOwner.lifecycleScope.launch {
                    loadAndBindPulse(accessToken)
                }

            } catch (e: ApiException) {
                // Check if fragment is still attached before updating UI
                if (!isAdded) return@launch

                // Determine error type
                val errorType = when (e.code) {
                    401 -> ErrorStateView.ErrorType.UNAUTHORIZED
                    500, 503 -> ErrorStateView.ErrorType.SERVER
                    0 -> ErrorStateView.ErrorType.NETWORK
                    else -> ErrorStateView.ErrorType.GENERIC
                }

                // Check if we have cached data for offline state
                if (cachedData != null && (e.code == 0 || e.message?.contains("Network", ignoreCase = true) == true)) {
                    updateUiState(TodayUiState.OFFLINE)
                    showOfflineBanner()
                } else {
                    updateUiState(TodayUiState.ERROR, errorType)
                }

                // Show error toast
                Toast.makeText(context ?: return@launch, getString(R.string.error_failed_to_load_goals), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Check if fragment is still attached before updating UI
                if (!isAdded) return@launch

                // Check if we have cached data for offline state
                if (cachedData != null) {
                    updateUiState(TodayUiState.OFFLINE)
                    showOfflineBanner()
                } else {
                    updateUiState(TodayUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                }

                // Show error toast
                Toast.makeText(context ?: return@launch, getString(R.string.error_failed_to_load_goals), Toast.LENGTH_SHORT).show()
            } finally {
                if (isAdded) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    /**
     * Update date and progress header.
     */
    private fun updateDateAndProgress(response: TodayGoalsResponse) {
        // Check if fragment is attached before updating views
        if (!isAdded) return

        // Format date
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val date = dateFormat.parse(response.date)
            val displayFormat = SimpleDateFormat("EEEE, MMMM d", Locale.US)
            dateText.text = date?.let { displayFormat.format(it) } ?: response.date
        } catch (e: Exception) {
            dateText.text = response.date
        }

        // Update progress
        progressText.text = getString(R.string.today_complete, response.overall_completion_percent)
    }

    /**
     * Update UI state and show/hide appropriate views.
     */
    private fun updateUiState(state: TodayUiState, errorType: ErrorStateView.ErrorType? = null) {
        // Check if fragment is attached before updating UI
        if (!isAdded) return

        currentState = state

        when (state) {
            TodayUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                headerContainer.visibility = View.GONE
                goalsRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            TodayUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                headerContainer.visibility = View.VISIBLE
                goalsRecyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            TodayUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                headerContainer.visibility = View.GONE
                goalsRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE

                // Create or show empty state view
                if (emptyStateView == null && isAdded) {
                    emptyStateView = EmptyStateView.Companion.inflateToday(
                        LayoutInflater.from(requireContext()),
                        emptyStateContainer
                    )
                    emptyStateContainer.addView(emptyStateView?.view)
                    emptyStateView?.setOnBrowseGroupsClickListener {
                        (activity as? Callbacks)?.onViewGroups()
                    }
                }
            }
            TodayUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                headerContainer.visibility = View.GONE
                goalsRecyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE

                // Create or update error state view
                if (errorStateView == null && isAdded) {
                    errorStateView = ErrorStateView.Companion.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener {
                        loadTodayGoals()
                    }
                }
                errorType?.let { 
                    errorStateView?.setErrorType(it)
                    // Override error title with screen-specific message for Today screen
                    if (it == ErrorStateView.ErrorType.GENERIC) {
                        errorStateView?.setCustomMessage(
                            R.string.error_failed_to_load_goals,
                            R.string.error_network_message
                        )
                    } else {
                        // For other error types, keep the error type message but update title
                        errorStateView?.setCustomMessage(
                            R.string.error_failed_to_load_goals,
                            when (it) {
                                ErrorStateView.ErrorType.NETWORK -> R.string.error_network_message
                                ErrorStateView.ErrorType.SERVER -> R.string.error_server_message
                                ErrorStateView.ErrorType.TIMEOUT -> R.string.error_timeout_message
                                ErrorStateView.ErrorType.UNAUTHORIZED -> R.string.error_unauthorized_message
                                else -> R.string.error_network_message
                            }
                        )
                    }
                }
            }
            TodayUiState.OFFLINE -> {
                skeletonContainer.visibility = View.GONE
                // Show cached data (slightly dimmed)
                if (cachedData != null) {
                    updateDateAndProgress(cachedData!!)
                    adapter = TodayGoalAdapter(cachedData!!.groups, onGoalClick = { goal, groupId ->
                        logProgressHandler?.handleCardBodyClick(
                            GoalLogProgressHandler.Companion.fromTodayGoal(
                                goal,
                                groupId
                            )
                        )
                    }, onArrowClick = { goal ->
                        if (isAdded) {
                            try {
                                val goalDetailFragment =
                                    GoalDetailFragment.Companion.newInstance(goal.goal_id)
                                requireActivity().supportFragmentManager.commit {
                                    replace(R.id.fragment_container, goalDetailFragment)
                                    addToBackStack(null)
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    requireContext(),
                                    "Navigation failed: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    })
                    goalsRecyclerView.adapter = adapter
                    headerContainer.visibility = View.VISIBLE
                    goalsRecyclerView.visibility = View.VISIBLE
                    goalsRecyclerView.alpha = 0.6f // Dim the view
                } else {
                    headerContainer.visibility = View.GONE
                    goalsRecyclerView.visibility = View.GONE
                }
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
        }
    }

    /**
     * Show offline banner with retry action.
     */
    private fun showOfflineBanner() {
        // Check if fragment is attached before showing snackbar
        if (!isAdded) return

        val snackbar = Snackbar.make(swipeRefreshLayout, R.string.offline_banner_title, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.retry) {
            loadTodayGoals()
        }
        snackbar.show()
    }

    private suspend fun loadAndBindPulse(accessToken: String) {
        try {
            val allGroups = withContext(Dispatchers.IO) {
                ApiClient.getMyGroups(accessToken).groups
            }
            if (allGroups.isEmpty()) {
                if (isAdded) dailyPulseWidget.showHidden()
                return
            }

            // Fetch all group member lists in parallel
            val membersByGroup: List<Pair<String, List<GroupMember>>> =
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        allGroups.map { group ->
                            async {
                                val members = ApiClient.getGroupMembers(accessToken, group.id).members
                                group.id to members
                            }
                        }.map { it.await() }
                    }
                }

            // Deduplicate: for each user, merge logged status across groups.
            // For nudging, prefer a group where the user has NOT yet logged.
            data class Entry(val member: GroupMember, val nudgeGroupId: String)
            val seen = mutableMapOf<String, Entry>()

            for ((groupId, members) in membersByGroup) {
                for (member in members) {
                    val existing = seen[member.user_id]
                    if (existing == null) {
                        seen[member.user_id] = Entry(member, groupId)
                    } else {
                        val mergedLogged = existing.member.logged_this_period || member.logged_this_period
                        val mergedLastLog = listOfNotNull(existing.member.last_log_at, member.last_log_at)
                            .maxOrNull()
                        val merged = existing.member.copy(
                            logged_this_period = mergedLogged,
                            last_log_at = mergedLastLog
                        )
                        val nudgeGroup = if (!member.logged_this_period) groupId else existing.nudgeGroupId
                        seen[member.user_id] = Entry(merged, nudgeGroup)
                    }
                }
            }

            val aggregated = seen.values.map { it.member }
            val nudgeMap = seen.mapValues { it.value.nudgeGroupId }

            if (!isAdded) return
            val currentUserId = try {
                withContext(Dispatchers.IO) { ApiClient.getMyUser(accessToken).id }
            } catch (_: Exception) { "" }

            if (!isAdded) return
            dailyPulseWidget.bindMembers(
                members = aggregated,
                currentUserId = currentUserId,
                hasGoals = true,
                memberNudgeGroups = nudgeMap
            )
        } catch (_: Exception) {
            if (isAdded) dailyPulseWidget.showHidden()
        }
    }

    companion object {
        fun newInstance(): TodayFragment = TodayFragment()
    }
}
