package com.github.shannonbay.pursue.ui.fragments.goals

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.models.CurrentPeriodProgressUiModel
import com.github.shannonbay.pursue.models.GoalDetailGoal
import com.github.shannonbay.pursue.models.GoalDetailUiModel
import com.github.shannonbay.pursue.models.GoalEntryUiModel
import com.github.shannonbay.pursue.models.GroupDetailResponse
import com.github.shannonbay.pursue.models.StreakUiModel
import com.github.shannonbay.pursue.ui.adapters.GoalEntryAdapter
import com.github.shannonbay.pursue.ui.views.ErrorStateView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Goal Detail Fragment (UI spec section 4.3.4).
 * 
 * Displays complete goal information, progress history, and analytics.
 */
class GoalDetailFragment : Fragment() {

    companion object {
        private const val ARG_GOAL_ID = "goal_id"

        fun newInstance(goalId: String): GoalDetailFragment {
            return GoalDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GOAL_ID, goalId)
                }
            }
        }
    }

    private var goalId: String? = null
    private var currentUserId: String? = null
    private var isAdmin: Boolean = false
    
    // Views
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressCard: com.google.android.material.card.MaterialCardView
    private lateinit var progressTitle: TextView
    private lateinit var progressIndicatorContainer: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var streakContainer: LinearLayout
    private lateinit var currentStreak: TextView
    private lateinit var bestStreak: TextView
    private lateinit var recentEntriesHeader: TextView
    private lateinit var entriesRecyclerView: RecyclerView
    private lateinit var viewAllHistoryButton: MaterialButton
    private lateinit var emptyStateContainer: ViewGroup
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var errorStateContainer: ViewGroup
    
    // Data
    private var goalDetail: GoalDetailUiModel? = null
    private var entries: List<GoalEntryUiModel> = emptyList()
    private var allEntriesLoaded: Boolean = false
    private var adapter: GoalEntryAdapter? = null
    private var errorStateView: ErrorStateView? = null
    
    enum class GoalDetailUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR
    }
    
    private var currentState: GoalDetailUiState = GoalDetailUiState.LOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goalId = arguments?.getString(ARG_GOAL_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_goal_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        progressCard = view.findViewById(R.id.progress_card)
        progressTitle = view.findViewById(R.id.progress_title)
        progressIndicatorContainer = view.findViewById(R.id.progress_indicator_container)
        progressText = view.findViewById(R.id.progress_text)
        streakContainer = view.findViewById(R.id.streak_container)
        currentStreak = view.findViewById(R.id.current_streak)
        bestStreak = view.findViewById(R.id.best_streak)
        recentEntriesHeader = view.findViewById(R.id.recent_entries_header)
        entriesRecyclerView = view.findViewById(R.id.entries_recycler_view)
        viewAllHistoryButton = view.findViewById(R.id.view_all_history_button)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)

        // Setup toolbar
        setupToolbar()

        // Setup RecyclerView
        entriesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = GoalEntryAdapter(
            entries = emptyList(),
            currentUserId = currentUserId ?: "",
            onLongPress = { entry -> handleEntryLongPress(entry) }
        )
        entriesRecyclerView.adapter = adapter

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadGoalData()
        }

        // Setup "View All History" button
        viewAllHistoryButton.setOnClickListener {
            loadAllHistory()
        }

        // Load data
        loadGoalData()
    }

    private fun setupToolbar() {
        val activity = requireActivity() as? AppCompatActivity
        activity?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        activity?.supportActionBar?.setDisplayShowHomeEnabled(true)

        // Setup overflow menu
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.goal_detail_overflow, menu)
                
                // Show/hide menu items based on admin status
                menu.findItem(R.id.menu_edit_goal).isVisible = isAdmin
                menu.findItem(R.id.menu_delete_goal).isVisible = isAdmin
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.menu_edit_goal -> {
                        // TODO: Navigate to Edit Goal screen (future)
                        Toast.makeText(requireContext(), "Edit Goal (coming soon)", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.menu_delete_goal -> {
                        handleDeleteGoal()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun loadGoalData() {
        val goalId = this.goalId ?: return

        lifecycleScope.launch {
            if (!swipeRefreshLayout.isRefreshing) {
                updateUiState(GoalDetailUiState.LOADING)
            }

            try {
                val tokenManager = SecureTokenManager.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    updateUiState(GoalDetailUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                    Handler(Looper.getMainLooper()).post {
                        swipeRefreshLayout.isRefreshing = false
                    }
                    return@launch
                }

                // Load goal details and progress in parallel
                val goalResponse = withContext(Dispatchers.IO) {
                    ApiClient.getGoal(accessToken, goalId)
                }

                // Get current period dates
                val periodDates = calculatePeriodDates(goalResponse.cadence)
                
                // Load progress entries (get all, we'll limit to 10 in UI initially)
                val progressResponse = withContext(Dispatchers.IO) {
                    ApiClient.getGoalProgress(
                        accessToken = accessToken,
                        goalId = goalId,
                        startDate = null, // Get all entries for streak calculation
                        endDate = null
                    )
                }

                // Extract current user ID from progress entries (find first entry by matching user_id)
                // For now, we'll use the first user_id we find that matches our token
                // In a real implementation, we'd decode the JWT or call /api/users/me
                currentUserId = progressResponse.progress.firstOrNull()?.user_id

                // Load group details to check if user is admin
                try {
                    val groupDetail = withContext(Dispatchers.IO) {
                        ApiClient.getGroupDetails(accessToken, goalResponse.group_id)
                    }
                    isAdmin = groupDetail.user_role == "admin" || groupDetail.user_role == "creator"
                } catch (e: Exception) {
                    // If we can't load group details, assume not admin
                    isAdmin = false
                }

                // Calculate current period progress
                val currentPeriodProgress = calculateCurrentPeriodProgress(
                    goalResponse,
                    progressResponse,
                    periodDates.first,
                    periodDates.second
                )

                // Calculate streaks
                val streak = calculateStreaks(goalResponse, progressResponse)

                // Format entries for display
                val formattedEntries = formatEntries(progressResponse, currentUserId ?: "")

                // Create UI model
                goalDetail = GoalDetailUiModel(
                    goal = GoalDetailGoal(
                        id = goalResponse.id,
                        group_id = goalResponse.group_id,
                        title = goalResponse.title,
                        description = goalResponse.description,
                        cadence = goalResponse.cadence,
                        metric_type = goalResponse.metric_type,
                        target_value = null, // Not in GetGoalResponse, would need to fetch from group goals
                        unit = null,
                        created_at = goalResponse.created_at
                    ),
                    currentPeriodProgress = currentPeriodProgress,
                    streak = streak
                )

                entries = formattedEntries.take(10) // Show last 10 initially
                allEntriesLoaded = formattedEntries.size <= 10

                Handler(Looper.getMainLooper()).post {
                    updateToolbarTitle(goalResponse.title)
                    updateProgressSection(currentPeriodProgress, streak)
                    updateEntriesList(entries)
                    updateUiState(if (entries.isEmpty()) GoalDetailUiState.SUCCESS_EMPTY else GoalDetailUiState.SUCCESS_WITH_DATA)
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: ApiException) {
                Handler(Looper.getMainLooper()).post {
                    val errorType = when (e.code) {
                        401 -> ErrorStateView.ErrorType.UNAUTHORIZED
                        0 -> ErrorStateView.ErrorType.NETWORK
                        500, 502, 503 -> ErrorStateView.ErrorType.SERVER
                        504 -> ErrorStateView.ErrorType.TIMEOUT
                        else -> ErrorStateView.ErrorType.SERVER
                    }
                    updateUiState(GoalDetailUiState.ERROR, errorType)
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    updateUiState(GoalDetailUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    private fun calculatePeriodDates(cadence: String): Pair<String, String> {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        when (cadence.lowercase()) {
            "daily" -> {
                val today = dateFormat.format(calendar.time)
                return Pair(today, today)
            }
            "weekly" -> {
                // Get Monday of current week
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val daysFromMonday = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - Calendar.MONDAY
                calendar.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
                val start = dateFormat.format(calendar.time)
                calendar.add(Calendar.DAY_OF_MONTH, 6)
                val end = dateFormat.format(calendar.time)
                return Pair(start, end)
            }
            "monthly" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val start = dateFormat.format(calendar.time)
                val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                calendar.set(Calendar.DAY_OF_MONTH, lastDay)
                val end = dateFormat.format(calendar.time)
                return Pair(start, end)
            }
            "yearly" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                val start = dateFormat.format(calendar.time)
                calendar.set(Calendar.MONTH, Calendar.DECEMBER)
                calendar.set(Calendar.DAY_OF_MONTH, 31)
                val end = dateFormat.format(calendar.time)
                return Pair(start, end)
            }
            else -> {
                val today = dateFormat.format(calendar.time)
                return Pair(today, today)
            }
        }
    }

    private fun calculateCurrentPeriodProgress(
        goal: com.github.shannonbay.pursue.data.network.GetGoalResponse,
        progressResponse: com.github.shannonbay.pursue.data.network.GoalProgressResponse,
        periodStart: String,
        periodEnd: String
    ): CurrentPeriodProgressUiModel? {
        // Find current user's progress
        val userProgress = progressResponse.progress.find { it.user_id == currentUserId }
            ?: return null

        // Filter entries for current period
        val periodEntries = userProgress.entries.filter { entry ->
            val entryDate = entry.period_start
            entryDate >= periodStart && entryDate <= periodEnd
        }

        val completed = when (goal.metric_type) {
            "binary" -> periodEntries.size.toDouble()
            "numeric", "duration" -> periodEntries.sumOf { it.value }
            else -> 0.0
        }

        val target = when (goal.metric_type) {
            "binary" -> 1.0 // Default target for binary
            "numeric", "duration" -> 1.0 // Would need target_value from goal
            else -> 1.0
        }

        val percentage = if (target > 0) ((completed / target) * 100).toInt() else 0

        val displayText = when (goal.metric_type) {
            "binary" -> requireContext().getString(R.string.progress_complete, completed.toInt(), target.toInt())
            "numeric", "duration" -> requireContext().getString(R.string.progress_percentage, percentage, completed.toInt(), target.toInt())
            else -> ""
        }

        return CurrentPeriodProgressUiModel(
            start_date = periodStart,
            end_date = periodEnd,
            period_type = goal.cadence,
            user_completed = completed,
            user_total = target,
            user_percentage = percentage,
            display_text = displayText
        )
    }

    private fun calculateStreaks(
        goal: com.github.shannonbay.pursue.data.network.GetGoalResponse,
        progressResponse: com.github.shannonbay.pursue.data.network.GoalProgressResponse
    ): StreakUiModel? {
        // Only calculate streaks for daily and weekly goals
        if (goal.cadence !in listOf("daily", "weekly")) {
            return null
        }

        val userProgress = progressResponse.progress.find { it.user_id == currentUserId }
            ?: return StreakUiModel(0, 0, if (goal.cadence == "daily") "days" else "weeks")

        // Sort entries by date (newest first)
        val sortedEntries = userProgress.entries.sortedByDescending { it.period_start }

        if (sortedEntries.isEmpty()) {
            return StreakUiModel(0, 0, if (goal.cadence == "daily") "days" else "weeks")
        }

        // Calculate current streak (consecutive periods from today backwards)
        var currentStreak = 0
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        for (i in 0 until sortedEntries.size) {
            val entryDate = sortedEntries[i].period_start
            // Check if this entry is for the expected period
            // For simplicity, we'll count consecutive entries
            if (i == 0 || isConsecutivePeriod(sortedEntries[i-1].period_start, entryDate, goal.cadence)) {
                currentStreak++
            } else {
                break
            }
        }

        // Calculate best streak (longest consecutive sequence)
        var bestStreak = 1
        var currentSequence = 1
        
        for (i in 1 until sortedEntries.size) {
            if (isConsecutivePeriod(sortedEntries[i-1].period_start, sortedEntries[i].period_start, goal.cadence)) {
                currentSequence++
                bestStreak = maxOf(bestStreak, currentSequence)
            } else {
                currentSequence = 1
            }
        }

        return StreakUiModel(
            current_streak = currentStreak,
            best_streak = bestStreak,
            unit = if (goal.cadence == "daily") "days" else "weeks"
        )
    }

    private fun isConsecutivePeriod(date1: String, date2: String, cadence: String): Boolean {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val d1 = format.parse(date1) ?: return false
        val d2 = format.parse(date2) ?: return false
        
        val calendar = Calendar.getInstance()
        calendar.time = d1
        
        when (cadence) {
            "daily" -> calendar.add(Calendar.DAY_OF_MONTH, -1)
            "weekly" -> calendar.add(Calendar.WEEK_OF_YEAR, -1)
            else -> return false
        }
        
        val expectedDate = format.format(calendar.time)
        return expectedDate == date2
    }

    private fun formatEntries(
        progressResponse: com.github.shannonbay.pursue.data.network.GoalProgressResponse,
        currentUserId: String
    ): List<GoalEntryUiModel> {
        val allEntries = mutableListOf<GoalEntryUiModel>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        val dateHeaderFormat = SimpleDateFormat("EEE MMM d", Locale.US)
        
        // Flatten all entries from all users, sorted by logged_at (newest first)
        val flattenedEntries = progressResponse.progress.flatMap { userProgress ->
            userProgress.entries.map { entry ->
                Triple(userProgress.user_id, userProgress.display_name, entry)
            }
        }.sortedByDescending { it.third.logged_at }

        var previousDate: String? = null
        
        flattenedEntries.forEach { (userId, displayName, entry) ->
            val loggedDate = dateFormat.parse(entry.period_start) ?: return@forEach
            val loggedTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(entry.logged_at)
                ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).parse(entry.logged_at)
                ?: return@forEach
            
            val dateHeader = if (entry.period_start != previousDate) {
                dateHeaderFormat.format(loggedDate)
            } else {
                null
            }
            
            val formattedTimestamp = requireContext().getString(
                R.string.logged_at,
                timeFormat.format(loggedTime)
            )
            
            allEntries.add(
                GoalEntryUiModel(
                    id = entry.id,
                    user_id = userId,
                    display_name = displayName,
                    is_current_user = userId == currentUserId,
                    value = entry.value,
                    note = entry.note,
                    period_start = entry.period_start,
                    logged_at = entry.logged_at,
                    date_header = dateHeader,
                    formatted_timestamp = formattedTimestamp
                )
            )
            
            previousDate = entry.period_start
        }
        
        return allEntries
    }

    private fun updateToolbarTitle(title: String) {
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = title
    }

    private fun updateProgressSection(
        progress: CurrentPeriodProgressUiModel?,
        streak: StreakUiModel?
    ) {
        if (progress == null) {
            progressCard.visibility = View.GONE
            return
        }

        progressCard.visibility = View.VISIBLE

        // Update progress title based on cadence
        val progressTitleText = when (progress.period_type) {
            "daily" -> requireContext().getString(R.string.progress_today)
            "weekly" -> requireContext().getString(R.string.progress_this_week)
            "monthly" -> requireContext().getString(R.string.progress_this_month)
            "yearly" -> requireContext().getString(R.string.progress_this_year)
            else -> requireContext().getString(R.string.progress_this_week)
        }
        progressTitle.text = progressTitleText

        // Update progress indicator based on metric type
        progressIndicatorContainer.removeAllViews()
        val goal = goalDetail?.goal ?: return
        
        when (goal.metric_type) {
            "binary" -> {
                // Show checkmarks
                val target = progress.user_total.toInt()
                val completed = progress.user_completed.toInt()
                for (i in 0 until target) {
                    val checkmark = TextView(requireContext()).apply {
                        text = if (i < completed) "✅" else "⬜"
                        textSize = 24f
                        setPadding(4, 0, 4, 0)
                    }
                    progressIndicatorContainer.addView(checkmark)
                }
            }
            "numeric", "duration" -> {
                // Show progress bar
                val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal)
                progressBar.max = 100
                progressBar.progress = progress.user_percentage
                progressBar.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 4 // Use 8dp height
                ).apply {
                    setMargins(0, 0, 0, 0)
                }
                progressIndicatorContainer.addView(progressBar)
            }
        }

        // Update progress text
        progressText.text = progress.display_text

        // Update streak information
        if (streak != null && streak.current_streak > 0) {
            streakContainer.visibility = View.VISIBLE
            currentStreak.text = if (streak.unit == "days") {
                requireContext().getString(R.string.current_streak_days, streak.current_streak)
            } else {
                requireContext().getString(R.string.current_streak_weeks, streak.current_streak)
            }
            bestStreak.text = if (streak.unit == "days") {
                requireContext().getString(R.string.best_streak_days, streak.best_streak)
            } else {
                requireContext().getString(R.string.best_streak_weeks, streak.best_streak)
            }
        } else {
            streakContainer.visibility = View.GONE
        }
    }

    private fun updateEntriesList(newEntries: List<GoalEntryUiModel>) {
        entries = newEntries
        adapter = GoalEntryAdapter(
            entries = entries,
            currentUserId = currentUserId ?: "",
            onLongPress = { entry -> handleEntryLongPress(entry) }
        )
        entriesRecyclerView.adapter = adapter
        
        // Show/hide "View All History" button
        viewAllHistoryButton.visibility = if (allEntriesLoaded || entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun handleEntryLongPress(entry: GoalEntryUiModel) {
        if (!entry.is_current_user) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.display_name)
            .setItems(arrayOf(
                requireContext().getString(R.string.edit_entry),
                requireContext().getString(R.string.delete_entry)
            )) { _, which ->
                when (which) {
                    0 -> {
                        // TODO: Edit entry (future)
                        Toast.makeText(requireContext(), "Edit Entry (coming soon)", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        handleDeleteEntry(entry)
                    }
                }
            }
            .setNegativeButton(requireContext().getString(R.string.cancel), null)
            .show()
    }

    private fun handleDeleteEntry(entry: GoalEntryUiModel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(requireContext().getString(R.string.delete_entry_confirmation))
            .setPositiveButton(requireContext().getString(R.string.delete)) { _, _ ->
                deleteEntry(entry)
            }
            .setNegativeButton(requireContext().getString(R.string.cancel), null)
            .show()
    }

    private fun deleteEntry(entry: GoalEntryUiModel) {
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken() ?: return@launch

                withContext(Dispatchers.IO) {
                    ApiClient.deleteProgressEntry(accessToken, entry.id)
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), requireContext().getString(R.string.entry_deleted), Toast.LENGTH_SHORT).show()
                    // Reload data
                    loadGoalData()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), "Failed to delete entry: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleDeleteGoal() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(requireContext().getString(R.string.delete_goal_confirmation))
            .setPositiveButton(requireContext().getString(R.string.delete)) { _, _ ->
                deleteGoal()
            }
            .setNegativeButton(requireContext().getString(R.string.cancel), null)
            .show()
    }

    private fun deleteGoal() {
        val goalId = this.goalId ?: return

        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken() ?: return@launch

                withContext(Dispatchers.IO) {
                    ApiClient.deleteGoal(accessToken, goalId)
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), requireContext().getString(R.string.goal_deleted), Toast.LENGTH_SHORT).show()
                    // Navigate back
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), "Failed to delete goal: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadAllHistory() {
        val goalId = this.goalId ?: return
        
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken() ?: return@launch

                // Load all progress entries (no date limit)
                val progressResponse = withContext(Dispatchers.IO) {
                    ApiClient.getGoalProgress(
                        accessToken = accessToken,
                        goalId = goalId,
                        startDate = null, // Get all entries
                        endDate = null
                    )
                }

                // Format all entries
                val allFormattedEntries = formatEntries(progressResponse, currentUserId ?: "")
                
                Handler(Looper.getMainLooper()).post {
                    entries = allFormattedEntries
                    allEntriesLoaded = true
                    updateEntriesList(entries)
                    viewAllHistoryButton.visibility = View.GONE
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), "Failed to load history: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateUiState(state: GoalDetailUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            GoalDetailUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                swipeRefreshLayout.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            GoalDetailUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            GoalDetailUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                swipeRefreshLayout.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE

                // Setup empty state
                if (emptyStateContainer.childCount == 0) {
                    val emptyView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.empty_state_generic, emptyStateContainer, false)
                    emptyView.findViewById<TextView>(R.id.empty_title).text = requireContext().getString(R.string.no_entries_yet)
                    emptyStateContainer.addView(emptyView)
                }
            }
            GoalDetailUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                swipeRefreshLayout.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE

                // Setup error state
                val currentErrorType = errorType ?: ErrorStateView.ErrorType.NETWORK
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener {
                        loadGoalData()
                    }
                }
                errorStateView?.setErrorType(currentErrorType)
                errorStateView?.setCustomMessage(R.string.error_loading_goal, R.string.retry)
            }
        }
    }
}
