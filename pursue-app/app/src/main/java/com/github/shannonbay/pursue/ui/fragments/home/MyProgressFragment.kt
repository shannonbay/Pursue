package com.github.shannonbay.pursue.ui.fragments.home

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.gridlayout.widget.GridLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.shannonbay.pursue.R
import android.widget.Toast
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.ui.views.EmptyStateView
import com.github.shannonbay.pursue.ui.views.ErrorStateView
import com.github.shannonbay.pursue.ui.adapters.GoalBreakdownAdapter
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.models.MyProgressResponse
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * My Progress detail screen (UI spec section 4.5.1).
 * 
 * Displays user's progress with streak, weekly activity, heatmap, and goal breakdown.
 * Implements 5-state pattern: Loading, Success-With Data, Success-Empty, Error, Offline-Cached.
 */
class MyProgressFragment : Fragment() {

    enum class ProgressUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var errorStateContainer: FrameLayout
    private lateinit var streakCard: View
    private lateinit var weeklyActivity: View
    private lateinit var heatmap: View
    private lateinit var goalBreakdownRecyclerView: RecyclerView
    private var errorStateView: ErrorStateView? = null
    private var emptyStateView: EmptyStateView? = null
    private var adapter: GoalBreakdownAdapter? = null
    private var currentState: ProgressUiState = ProgressUiState.LOADING
    private var cachedData: MyProgressResponse? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_my_progress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        contentContainer = view.findViewById<LinearLayout>(R.id.content_container)
        skeletonContainer = view.findViewById<LinearLayout>(R.id.skeleton_container)
        emptyStateContainer = view.findViewById<FrameLayout>(R.id.empty_state_container)
        errorStateContainer = view.findViewById<FrameLayout>(R.id.error_state_container)
        streakCard = view.findViewById<View>(R.id.streak_card)
        weeklyActivity = view.findViewById<View>(R.id.weekly_activity)
        heatmap = view.findViewById<View>(R.id.heatmap)
        goalBreakdownRecyclerView = view.findViewById<RecyclerView>(R.id.goal_breakdown_recycler_view)

        // Setup RecyclerView
        goalBreakdownRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GoalBreakdownAdapter(emptyList())
        goalBreakdownRecyclerView.adapter = adapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadProgress()
        }

        // Load progress on first view
        loadProgress()
    }

    /**
     * Load progress data from API and update the UI.
     */
    private fun loadProgress() {
        lifecycleScope.launch {
            // Show loading state (unless this is a pull-to-refresh)
            if (!swipeRefreshLayout.isRefreshing) {
                updateUiState(ProgressUiState.LOADING)
            }

            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    updateUiState(ProgressUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                    swipeRefreshLayout.isRefreshing = false
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.getMyProgress(accessToken)
                }

                // Cache the data
                cachedData = response

                // Update UI with data
                updateStreakCard(response)
                updateWeeklyActivity(response)
                updateHeatmap(response)
                adapter = GoalBreakdownAdapter(response.goal_breakdown)
                goalBreakdownRecyclerView.adapter = adapter

                // Update state based on data
                if (response.goal_breakdown.isEmpty() && response.streak.current_streak_days == 0) {
                    updateUiState(ProgressUiState.SUCCESS_EMPTY)
                } else {
                    updateUiState(ProgressUiState.SUCCESS_WITH_DATA)
                }

            } catch (e: ApiException) {
                // Determine error type
                val errorType = when (e.code) {
                    401 -> ErrorStateView.ErrorType.UNAUTHORIZED
                    500, 503 -> ErrorStateView.ErrorType.SERVER
                    0 -> ErrorStateView.ErrorType.NETWORK
                    else -> ErrorStateView.ErrorType.GENERIC
                }

                // Check if we have cached data for offline state
                if (cachedData != null && (e.code == 0 || e.message?.contains("Network", ignoreCase = true) == true)) {
                    updateUiState(ProgressUiState.OFFLINE)
                    showOfflineBanner()
                } else {
                    updateUiState(ProgressUiState.ERROR, errorType)
                }

                Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_progress), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // Check if we have cached data for offline state
                if (cachedData != null) {
                    updateUiState(ProgressUiState.OFFLINE)
                    showOfflineBanner()
                } else {
                    updateUiState(ProgressUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                }

                Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_progress), Toast.LENGTH_SHORT).show()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    /**
     * Update streak card with data.
     */
    private fun updateStreakCard(response: MyProgressResponse) {
        val streakDaysText: TextView = streakCard.findViewById(R.id.streak_days)
        val streakProgress: ProgressBar = streakCard.findViewById(R.id.streak_progress)
        val streakGoalText: TextView = streakCard.findViewById(R.id.streak_goal_text)
        val longestStreakText: TextView = streakCard.findViewById(R.id.longest_streak_text)

        streakDaysText.text = getString(R.string.streak_days, response.streak.current_streak_days)

        if (response.streak.streak_goal_days != null && response.streak.streak_goal_days!! > 0) {
            val progressPercent = (response.streak.current_streak_days * 100) / response.streak.streak_goal_days!!
            streakProgress.progress = progressPercent
            streakGoalText.text = getString(R.string.streak_goal, response.streak.streak_goal_days!!, progressPercent)
        } else {
            streakProgress.visibility = View.GONE
            streakGoalText.visibility = View.GONE
        }

        longestStreakText.text = getString(R.string.longest_streak, response.streak.longest_streak_days)
    }

    /**
     * Update weekly activity grid.
     */
    private fun updateWeeklyActivity(response: MyProgressResponse) {
        val weeklyGrid: LinearLayout = weeklyActivity.findViewById(R.id.weekly_grid)
        val weeklySummary: TextView = weeklyActivity.findViewById(R.id.weekly_summary)

        weeklyGrid.removeAllViews()

        val dayLabels = arrayOf("M", "T", "W", "T", "F", "S", "S")
        var completedCount = 0
        var totalCount = 0

        response.weekly_activity.completion_data.forEachIndexed { index, day ->
            val dayView = TextView(requireContext()).apply {
                text = if (day.completed) "✓" else "○"
                textSize = 16f
                setPadding(8, 8, 8, 8)
                gravity = Gravity.CENTER
                setTextColor(
                    if (day.completed) ContextCompat.getColor(requireContext(), R.color.primary)
                    else ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
                )
            }
            weeklyGrid.addView(dayView)

            totalCount++
            if (day.completed) completedCount++
        }

        weeklySummary.text = getString(R.string.goals_completed, completedCount, totalCount)
    }

    /**
     * Update heatmap grid.
     */
    private fun updateHeatmap(response: MyProgressResponse) {
        val heatmapGrid: GridLayout = heatmap.findViewById(R.id.heatmap_grid)

        // Remove all views by iterating and removing
        val childCount = heatmapGrid.childCount
        for (i in childCount - 1 downTo 0) {
            heatmapGrid.removeViewAt(i)
        }

        // Calculate grid dimensions (7 columns for days of week, rows for weeks)
        val daysPerWeek = 7
        val totalDays = response.heatmap.days.size

        response.heatmap.days.forEachIndexed { index, day ->
            val row = index / daysPerWeek
            val col = index % daysPerWeek

            val cellSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                32f,
                resources.displayMetrics
            ).toInt()

            val cellView = View(requireContext()).apply {
                // Set color based on completion percent
                val color = when {
                    day.completion_percent >= 80 -> ContextCompat.getColor(requireContext(), R.color.primary)
                    day.completion_percent >= 50 -> {
                        val colorInt = ContextCompat.getColor(requireContext(), R.color.primary)
                        Color.argb(
                            (255 * 0.6).toInt(),
                            Color.red(colorInt),
                            Color.green(colorInt),
                            Color.blue(colorInt)
                        )
                    }
                    day.completion_percent >= 20 -> {
                        val colorInt = ContextCompat.getColor(requireContext(), R.color.primary)
                        Color.argb(
                            (255 * 0.3).toInt(),
                            Color.red(colorInt),
                            Color.green(colorInt),
                            Color.blue(colorInt)
                        )
                    }
                    else -> ContextCompat.getColor(requireContext(), R.color.surface_variant)
                }
                setBackgroundColor(color)
            }

            val params = GridLayout.LayoutParams().apply {
                width = cellSize
                height = cellSize
                columnSpec = GridLayout.spec(col, 1f)
                rowSpec = GridLayout.spec(row, 1f)
                setMargins(4, 4, 4, 4)
            }
            cellView.layoutParams = params
            heatmapGrid.addView(cellView)
        }
    }

    /**
     * Update UI state and show/hide appropriate views.
     */
    private fun updateUiState(state: ProgressUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            ProgressUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                contentContainer.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            ProgressUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                contentContainer.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            ProgressUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                contentContainer.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE

                // Create or show empty state view
                if (emptyStateView == null) {
                    emptyStateView = EmptyStateView.Companion.inflateProgress(
                        LayoutInflater.from(requireContext()),
                        emptyStateContainer
                    )
                    emptyStateContainer.addView(emptyStateView?.view)
                    emptyStateView?.setOnViewTodaysGoalsClickListener {
                        // TODO: Navigate to Today screen
                        Toast.makeText(requireContext(), "Navigate to Today", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ProgressUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                contentContainer.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE

                // Create or update error state view
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.Companion.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener {
                        loadProgress()
                    }
                }
                errorType?.let { 
                    errorStateView?.setErrorType(it)
                    // Override error title with screen-specific message for Progress screen
                    if (it == ErrorStateView.ErrorType.GENERIC) {
                        errorStateView?.setCustomMessage(
                            R.string.error_failed_to_load_progress,
                            R.string.error_network_message
                        )
                    } else {
                        // For other error types, keep the error type message but update title
                        errorStateView?.setCustomMessage(
                            R.string.error_failed_to_load_progress,
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
            ProgressUiState.OFFLINE -> {
                skeletonContainer.visibility = View.GONE
                // Show cached data (slightly dimmed)
                if (cachedData != null) {
                    updateStreakCard(cachedData!!)
                    updateWeeklyActivity(cachedData!!)
                    updateHeatmap(cachedData!!)
                    adapter = GoalBreakdownAdapter(cachedData!!.goal_breakdown)
                    goalBreakdownRecyclerView.adapter = adapter
                    contentContainer.visibility = View.VISIBLE
                    contentContainer.alpha = 0.6f // Dim the view
                } else {
                    contentContainer.visibility = View.GONE
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
        val snackbar = Snackbar.make(swipeRefreshLayout, R.string.offline_banner_title, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.retry) {
            loadProgress()
        }
        snackbar.show()
    }

    companion object {
        fun newInstance(): MyProgressFragment = MyProgressFragment()
    }
}
