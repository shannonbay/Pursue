package app.getpursue.ui.fragments.groups

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.NestedScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.fragments.goals.CreateGoalFragment
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.ui.fragments.goals.GoalDetailFragment
import app.getpursue.ui.adapters.GroupGoalsAdapter
import app.getpursue.ui.views.OnboardingTooltip
import app.getpursue.data.prefs.OnboardingPrefs
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.models.GroupGoal
import app.getpursue.models.MemberProgress
import app.getpursue.ui.handlers.GoalLogProgressHandler
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.core.content.ContextCompat
import app.getpursue.models.GroupGoalResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Goals Tab Fragment for Group Detail (UI spec section 4.3.1).
 * 
 * Displays goals grouped by cadence (Daily, Weekly, Monthly, Yearly)
 * with pull-to-refresh and 5-state pattern.
 */
class GoalsTabFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_IS_ADMIN = "is_admin"

        fun newInstance(groupId: String, isAdmin: Boolean = false): GoalsTabFragment {
            return GoalsTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putBoolean(ARG_IS_ADMIN, isAdmin)
                }
            }
        }
    }

    private var groupId: String? = null
    private var isAdmin: Boolean = false
    private var isChallengeGroup: Boolean = false
    private var challengeStatus: String? = null
    private lateinit var goalsRecyclerView: RecyclerView
    private lateinit var goalsScrollView: NestedScrollView
    private lateinit var goalsListContainer: LinearLayout
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var errorStateContainer: FrameLayout
    private var adapter: GroupGoalsAdapter? = null
    private var errorStateView: ErrorStateView? = null
    private var currentUserId: String? = null

    /**
     * Update the admin status and refresh the empty state if needed.
     * Called after groupDetail loads in GroupDetailFragment.
     */
    fun updateAdminStatus(newIsAdmin: Boolean) {
        if (isAdmin != newIsAdmin) {
            isAdmin = newIsAdmin
            // If we're currently showing the empty state, refresh it with the new admin status
            if (currentState == GoalsUiState.SUCCESS_EMPTY) {
                // Clear the existing empty state view so it gets recreated with correct admin status
                emptyStateContainer.removeAllViews()
                updateUiState(GoalsUiState.SUCCESS_EMPTY)
            }
        }
    }

    fun updateChallengeLoggingStatus(isChallenge: Boolean, status: String?) {
        isChallengeGroup = isChallenge
        challengeStatus = status
    }

    private fun canLogProgress(): Boolean {
        return !isChallengeGroup || challengeStatus == "active"
    }

    /**
     * Get the current active goals count.
     * Returns the count of goals that have been loaded (excluding archived goals).
     */
    fun getActiveGoalsCount(): Int {
        return cachedGoals.size
    }

    /**
     * Notify parent fragment (GroupDetailFragment) that goals have been loaded.
     * This allows the parent to update the header with the actual goal count.
     */
    private fun notifyParentGoalsLoaded(goalCount: Int) {
        val parent = parentFragment as? GroupDetailFragment
        parent?.updateGoalsCount(goalCount)
    }

    enum class GoalsUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private var currentState: GoalsUiState = GoalsUiState.LOADING
    private var cachedGoals: List<GroupGoal> = emptyList()
    private var nudgedUserIds: MutableSet<String> = mutableSetOf()
    private var loadingNudgeUserIds: MutableSet<String> = mutableSetOf()

    private var logProgressHandler: GoalLogProgressHandler? = null

    /**
     * Get the parent fragment's root view (CoordinatorLayout) for creating snackbars.
     * This ensures the snackbar is in the same CoordinatorLayout as the FAB,
     * so it renders properly without being obscured by the FAB.
     */
    private fun getSnackbarParentView(): View {
        return parentFragment?.view ?: goalsRecyclerView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
        isAdmin = arguments?.getBoolean(ARG_IS_ADMIN) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_goals_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        goalsRecyclerView = view.findViewById(R.id.goals_recycler_view)
        goalsScrollView = view.findViewById(R.id.goals_scroll_view)
        goalsListContainer = view.findViewById(R.id.goals_list_container)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadGoals()
        }

        val userDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val userTimezone = ZoneId.systemDefault().id
        logProgressHandler = GoalLogProgressHandler(
            fragment = this,
            snackbarParentView = getSnackbarParentView(),
            tokenSupplier = {
                context?.let { SecureTokenManager.Companion.getInstance(it).getAccessToken() }
            },
            userDate = userDate,
            userTimezone = userTimezone,
            onOptimisticUpdate = { goalId, completed, progressValue ->
                val goal = cachedGoals.find { it.id == goalId } ?: return@GoalLogProgressHandler

                // Update the current user's entry in member_progress
                val updatedMemberProgress = if (currentUserId != null) {
                    goal.member_progress.map { member ->
                        if (member.user_id == currentUserId) {
                            member.copy(completed = completed, progress_value = progressValue)
                        } else {
                            member
                        }
                    }
                } else {
                    goal.member_progress
                }

                val newGoal = goal.copy(
                    completed = completed,
                    progress_value = progressValue,
                    member_progress = updatedMemberProgress
                )
                updateGoalInAdapter(goal, newGoal)
            },
            onRefresh = { silent -> loadGoals(silent = silent) }
        )

        // Load goals on first view
        loadGoals()
    }

    /**
     * @param silent If true, skip loading/skeleton state (e.g. after optimistic delete); just fetch and update.
     */
    private fun loadGoals(silent: Boolean = false) {
        val groupId = this.groupId ?: return

        lifecycleScope.launch {
            // Show loading state unless pull-to-refresh (spinner) or silent refresh (e.g. post-delete)
            if (!silent && !swipeRefreshLayout.isRefreshing) {
                updateUiState(GoalsUiState.LOADING)
            }

            try {
                val ctx = context ?: return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    updateUiState(GoalsUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                    if (isAdded) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                    return@launch
                }

                // Fetch current user ID if not already cached
                if (currentUserId == null) {
                    try {
                        val user = withContext(Dispatchers.IO) {
                            ApiClient.getMyUser(accessToken)
                        }
                        currentUserId = user.id
                    } catch (e: Exception) {
                        // Continue without user ID - member progress won't update optimistically
                    }
                }

                // Fetch goals and nudges sent today in parallel
                val senderLocalDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                val (response, nudgesResponse) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val goalsDeferred = async {
                            ApiClient.getGroupGoals(
                                accessToken = accessToken,
                                groupId = groupId,
                                archived = false,
                                includeProgress = true,
                                userTimezone = ZoneId.systemDefault().id
                            )
                        }
                        val nudgesDeferred = async {
                            try {
                                ApiClient.getNudgesSentToday(
                                    accessToken = accessToken,
                                    groupId = groupId,
                                    senderLocalDate = senderLocalDate
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                        Pair(goalsDeferred.await(), nudgesDeferred.await())
                    }
                }

                if (!isAdded) return@launch
                nudgedUserIds = (nudgesResponse?.nudged_user_ids ?: emptyList()).toMutableSet()

                // Map API response to GroupGoal model
                val goals = response.goals.map { goalResponse ->
                    mapGoalResponseToGroupGoal(goalResponse)
                }

                cachedGoals = goals

                // Notify parent fragment to update header with goal count
                notifyParentGoalsLoaded(goals.size)

                if (goals.isEmpty()) {
                    updateUiState(GoalsUiState.SUCCESS_EMPTY)
                } else {
                    // Populate ScrollView with goal cards
                    populateGoalsScrollView(goals)
                    updateUiState(GoalsUiState.SUCCESS_WITH_DATA)
                }
                swipeRefreshLayout.isRefreshing = false
            } catch (e: ApiException) {
                cachedGoals = emptyList()
                if (isAdded) {
                    val errorType = ErrorStateView.errorTypeFromApiException(e)
                    updateUiState(GoalsUiState.ERROR, errorType)
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: Exception) {
                cachedGoals = emptyList()
                if (isAdded) {
                    updateUiState(GoalsUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    /**
     * Update a goal in the adapter (optimistic update).
     * Uses in-place update to preserve scroll position.
     */
    private fun updateGoalInAdapter(oldGoal: GroupGoal, newGoal: GroupGoal) {
        // Update cached goals
        val currentGoals = cachedGoals.toMutableList()
        val index = currentGoals.indexOfFirst { it.id == oldGoal.id }
        if (index >= 0) {
            currentGoals[index] = newGoal
            cachedGoals = currentGoals
        }
        // Update ScrollView by repopulating with updated goals
        populateGoalsScrollView(cachedGoals)
    }

    /**
     * Populate the ScrollView with goal cards grouped by cadence.
     */
    private fun populateGoalsScrollView(goals: List<GroupGoal>) {
        // Disable layout transition so add/remove of children does not animate
        goalsListContainer.layoutTransition = null
        // Clear existing views
        goalsListContainer.removeAllViews()

        // Group goals by cadence
        val goalsByCadence = goals.groupBy { it.cadence }
        val cadences = listOf("daily", "weekly", "monthly", "yearly")

        cadences.forEach { cadence ->
            val cadenceGoals = goalsByCadence[cadence] ?: emptyList()
            if (cadenceGoals.isNotEmpty()) {
                // Add cadence header
                val headerView = TextView(requireContext()).apply {
                    val cadenceText = when (cadence.lowercase()) {
                        "daily" -> getString(R.string.daily_goals)
                        "weekly" -> getString(R.string.weekly_goals)
                        "monthly" -> getString(R.string.monthly_goals)
                        "yearly" -> getString(R.string.yearly_goals)
                        else -> cadence.replaceFirstChar { it.uppercaseChar() } + " Goals"
                    }
                    text = cadenceText
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface))
                    setPadding(16, 16, 16, 8)
                }
                goalsListContainer.addView(headerView)

                // Add goal cards for this cadence
                cadenceGoals.forEachIndexed { index, goal ->
                    val goalCardView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_goal_card, goalsListContainer, false)

                    // Bind goal data to view
                    bindGoalCardView(goalCardView, goal)

                    // Set up click listeners
                    val goalCard = goalCardView.findViewById<MaterialCardView>(R.id.goal_card)
                    val arrowButton = goalCardView.findViewById<ImageButton>(R.id.arrow_button)

                    goalCard.setOnClickListener {
                        if (!canLogProgress()) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.challenge_progress_locked_not_active),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                        logProgressHandler?.handleCardBodyClick(GoalLogProgressHandler.Companion.fromGroupGoal(goal))
                    }

                    arrowButton.setOnClickListener {
                        try {
                            val goalDetailFragment = GoalDetailFragment.Companion.newInstance(goal.id)
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, goalDetailFragment)
                                addToBackStack(null)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Navigation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    goalsListContainer.addView(goalCardView)

                    // Show tooltip on the very first goal card ever seen
                    if (cadence == cadences.first { goalsByCadence[it]?.isNotEmpty() == true } && index == 0) {
                        val prefs = OnboardingPrefs.getInstance(requireContext())
                        if (!prefs.hasShownTapToLogTooltip) {
                            goalCardView.post {
                                if (isAdded) {
                                    OnboardingTooltip.show(goalCard, R.string.onboarding_tap_to_log_tooltip)
                                    prefs.hasShownTapToLogTooltip = true
                                }
                            }
                        }
                    }
                }
            }
        }

        // Show ScrollView and hide RecyclerView
        goalsScrollView.visibility = View.VISIBLE
        goalsRecyclerView.visibility = View.GONE
        updateUiState(GoalsUiState.SUCCESS_WITH_DATA)
    }

    /**
     * Bind goal data to a goal card view.
     */
    private fun bindGoalCardView(view: View, goal: GroupGoal) {
        val statusIcon = view.findViewById<TextView>(R.id.status_icon)
        val goalTitle = view.findViewById<TextView>(R.id.goal_title)
        val restDayText = view.findViewById<TextView>(R.id.rest_day_text)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val progressText = view.findViewById<TextView>(R.id.progress_text)
        val memberStatusContainer = view.findViewById<LinearLayout>(R.id.member_status_container)

        // Rest day indicator for daily goals with active_days set
        val activeDays = goal.active_days
        if (goal.cadence == "daily" && activeDays != null) {
            val todayDow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            // Calendar: Sun=1..Sat=7 → API: Sun=0..Sat=6
            val todayIndex = todayDow - 1
            if (todayIndex !in activeDays) {
                restDayText.visibility = View.VISIBLE
            } else {
                restDayText.visibility = View.GONE
            }
        } else {
            restDayText.visibility = View.GONE
        }

        // Status icon (✓ for completed, ○ for incomplete)
        if (goal.completed) {
            statusIcon.text = "✓"
            statusIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
        } else {
            statusIcon.text = "○"
            statusIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_surface_variant))
        }

        goalTitle.text = goal.title

        // Progress bar and text for numeric/duration goals
        if ((goal.metric_type == "numeric" || goal.metric_type == "duration") && goal.target_value != null) {
            progressBar.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE

            val progress = goal.progress_value ?: 0.0
            val target = goal.target_value
            val displayPercent = ((progress / target) * 100).toInt()
            val barPercent = displayPercent.coerceIn(0, 100)

            progressBar.progress = barPercent
            progressText.text = "$displayPercent% (${progress.toInt()}/${target.toInt()})"
        } else {
            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE
        }

        // Member status: show other members only (completed, or incomplete not-yet-nudged). Hide (You) and incomplete+already-nudged until they complete.
        val memberNudgeColumn = view.findViewById<LinearLayout>(R.id.member_nudge_column)
        memberStatusContainer.removeAllViews()
        memberNudgeColumn.removeAllViews()

        val visibleMembers = goal.member_progress.filter { member ->
            val isCurrentUser = member.user_id == currentUserId
            if (isCurrentUser) return@filter false
            val isCompleted = member.completed
            val alreadyNudged = member.user_id in nudgedUserIds
            isCompleted || !alreadyNudged
        }

        if (visibleMembers.isNotEmpty()) {
            memberStatusContainer.visibility = View.VISIBLE
            memberNudgeColumn.visibility = View.VISIBLE
            val gId = groupId ?: return
            visibleMembers.forEach { member ->
                val memberRowView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_member_status, memberStatusContainer, false)
                val nudgeCellView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_member_nudge_cell, memberNudgeColumn, false)
                bindMemberStatusRow(memberRowView, nudgeCellView, member, goal, gId)
                memberStatusContainer.addView(memberRowView)
                memberNudgeColumn.addView(nudgeCellView)
            }
        } else {
            memberStatusContainer.visibility = View.GONE
            memberNudgeColumn.visibility = View.GONE
        }
    }

    /**
     * Bind a member row (left: circle/check + name) and nudge cell (right: bell or progress, same row as name).
     */
    private fun bindMemberStatusRow(
        rowView: View,
        nudgeCellView: View,
        member: MemberProgress,
        goal: GroupGoal,
        groupId: String
    ) {
        val nameView = rowView.findViewById<TextView>(R.id.member_status_name)
        val statusIconView = rowView.findViewById<TextView>(R.id.member_status_icon)
        val nudgeButton = nudgeCellView.findViewById<ImageButton>(R.id.nudge_button)
        val nudgeProgress = nudgeCellView.findViewById<ProgressBar>(R.id.nudge_progress)

        nameView.text = member.display_name
        statusIconView.text = if (member.completed) "✓" else "○"
        statusIconView.setTextColor(
            if (member.completed) ContextCompat.getColor(requireContext(), R.color.primary)
            else ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
        )

        val isCurrentUser = member.user_id == currentUserId
        val isCompleted = member.completed
        val alreadyNudged = member.user_id in nudgedUserIds
        val isLoading = member.user_id in loadingNudgeUserIds

        when {
            isCurrentUser || isCompleted -> {
                nudgeButton.visibility = View.GONE
                nudgeProgress.visibility = View.GONE
            }
            isLoading -> {
                nudgeButton.visibility = View.GONE
                nudgeProgress.visibility = View.VISIBLE
            }
            else -> {
                nudgeButton.visibility = View.VISIBLE
                nudgeProgress.visibility = View.GONE
                nudgeButton.isEnabled = !alreadyNudged
                nudgeButton.alpha = if (alreadyNudged) 0.5f else 1f
                nudgeButton.setOnClickListener {
                    sendNudge(member.user_id, member.display_name, goal.id, groupId)
                }
            }
        }
    }

    /**
     * Send a nudge to a group member. Updates UI optimistically and handles errors.
     */
    private fun sendNudge(
        recipientUserId: String,
        recipientDisplayName: String,
        goalId: String,
        groupId: String
    ) {
        val ctx = context ?: return
        val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
        val accessToken = tokenManager.getAccessToken() ?: return

        loadingNudgeUserIds.add(recipientUserId)
        populateGoalsScrollView(cachedGoals)

        lifecycleScope.launch {
            try {
                val senderLocalDate = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                withContext(Dispatchers.IO) {
                    ApiClient.sendNudge(
                        accessToken = accessToken,
                        recipientUserId = recipientUserId,
                        groupId = groupId,
                        goalId = goalId,
                        senderLocalDate = senderLocalDate
                    )
                }
                if (!isAdded) return@launch
                nudgedUserIds.add(recipientUserId)
                loadingNudgeUserIds.remove(recipientUserId)
                populateGoalsScrollView(cachedGoals)
                Snackbar.make(getSnackbarParentView(), getString(R.string.nudge_sent, recipientDisplayName), Snackbar.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                if (!isAdded) return@launch
                loadingNudgeUserIds.remove(recipientUserId)
                populateGoalsScrollView(cachedGoals)
                val message = when (e.errorCode) {
                    "ALREADY_NUDGED_TODAY" -> getString(R.string.already_nudged_today, recipientDisplayName)
                    "DAILY_SEND_LIMIT" -> getString(R.string.nudge_daily_limit)
                    else -> getString(R.string.nudge_failed)
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                if (e.errorCode == "ALREADY_NUDGED_TODAY") {
                    nudgedUserIds.add(recipientUserId)
                    populateGoalsScrollView(cachedGoals)
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                loadingNudgeUserIds.remove(recipientUserId)
                populateGoalsScrollView(cachedGoals)
                Toast.makeText(requireContext(), R.string.nudge_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUiState(state: GoalsUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            GoalsUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                goalsRecyclerView.visibility = View.GONE
                goalsScrollView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            GoalsUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                goalsScrollView.visibility = View.VISIBLE
                goalsRecyclerView.visibility = View.GONE

                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            GoalsUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                goalsRecyclerView.visibility = View.GONE
                goalsScrollView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE

                // Setup empty state if not already set
                if (emptyStateContainer.childCount == 0) {
                    val emptyStateView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.empty_state_generic, emptyStateContainer, false)
                    emptyStateView.findViewById<TextView>(R.id.empty_title).text = getString(R.string.empty_goals_title)
                    
                    val actionButton = emptyStateView.findViewById<MaterialButton>(R.id.empty_action_button)
                    val emptyMessage = emptyStateView.findViewById<TextView>(R.id.empty_message)
                    
                    if (isAdmin) {
                        // Admin view: Show clickable link "Create goals to get started"
                        val messageText = getString(R.string.empty_goals_message_admin_link)
                        val spannableString = SpannableString(messageText)
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                // Navigate to CreateGoalFragment
                                val groupId = this@GoalsTabFragment.groupId ?: return
                                val createGoalFragment = CreateGoalFragment.Companion.newInstance(groupId)
                                requireActivity().supportFragmentManager.commit {
                                    replace(R.id.fragment_container, createGoalFragment)
                                    addToBackStack(null)
                                }
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                                ds.color = requireContext().getColor(R.color.primary)
                                ds.isUnderlineText = true
                            }
                        }
                        spannableString.setSpan(clickableSpan, 0, messageText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        emptyMessage.text = spannableString
                        emptyMessage.movementMethod = LinkMovementMethod.getInstance()
                        emptyMessage.highlightColor = Color.TRANSPARENT // Remove default link highlight
                        
                        // Hide the action button
                        actionButton?.visibility = View.GONE
                    } else {
                        // Member view: No button, just message (not clickable)
                        emptyMessage.text = getString(R.string.empty_goals_message_member)
                        actionButton?.visibility = View.GONE
                    }
                    
                    emptyStateContainer.addView(emptyStateView)
                }
            }
            GoalsUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                goalsRecyclerView.visibility = View.GONE
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
                        loadGoals()
                    }
                }
                errorStateView?.setErrorType(currentErrorType)
                if (currentErrorType != ErrorStateView.ErrorType.PENDING_APPROVAL && currentErrorType != ErrorStateView.ErrorType.FORBIDDEN) {
                    errorStateView?.setCustomMessage(R.string.error_loading_goals, R.string.retry)
                }
            }
            GoalsUiState.OFFLINE -> {
                // TODO: Implement offline state with cached data
                updateUiState(GoalsUiState.SUCCESS_WITH_DATA)
            }
        }
    }

    /**
     * Map GroupGoalResponse from API to GroupGoal model for UI.
     */
    private fun mapGoalResponseToGroupGoal(goalResponse: GroupGoalResponse): GroupGoal {
        val progress = goalResponse.current_period_progress
        
        // Determine completed status and progress value based on metric type
        val completed: Boolean
        val progressValue: Double?
        
        if (progress != null) {
            when (goalResponse.metric_type) {
                "binary" -> {
                    // For binary goals: completed if user has any entries
                    completed = progress.user_progress.completed > 0
                    progressValue = null
                }
                "numeric", "duration" -> {
                    // For numeric goals: use the completed value (sum of entries)
                    completed = progress.user_progress.completed >= (goalResponse.target_value ?: 0.0)
                    progressValue = progress.user_progress.completed
                }
                else -> {
                    completed = false
                    progressValue = null
                }
            }
            
            // Map member progress
            val memberProgress = progress.member_progress.map { memberProgressResponse ->
                val memberCompleted = when (goalResponse.metric_type) {
                    "binary" -> memberProgressResponse.completed > 0
                    "numeric", "duration" -> memberProgressResponse.completed >= (goalResponse.target_value ?: 0.0)
                    else -> false
                }
                val memberProgressValue = if (goalResponse.metric_type == "numeric" || goalResponse.metric_type == "duration") {
                    memberProgressResponse.completed
                } else {
                    null
                }

                MemberProgress(
                    user_id = memberProgressResponse.user_id,
                    display_name = memberProgressResponse.display_name,
                    completed = memberCompleted,
                    progress_value = memberProgressValue
                )
            }
            
            return GroupGoal(
                id = goalResponse.id,
                group_id = goalResponse.group_id,
                title = goalResponse.title,
                description = goalResponse.description,
                cadence = goalResponse.cadence,
                metric_type = goalResponse.metric_type,
                target_value = goalResponse.target_value,
                unit = goalResponse.unit,
                active_days = goalResponse.active_days,
                created_at = goalResponse.created_at,
                completed = completed,
                progress_value = progressValue,
                member_progress = memberProgress
            )
        } else {
            // No progress data available (shouldn't happen with include_progress=true, but handle gracefully)
            return GroupGoal(
                id = goalResponse.id,
                group_id = goalResponse.group_id,
                title = goalResponse.title,
                description = goalResponse.description,
                cadence = goalResponse.cadence,
                metric_type = goalResponse.metric_type,
                target_value = goalResponse.target_value,
                unit = goalResponse.unit,
                active_days = goalResponse.active_days,
                created_at = goalResponse.created_at,
                completed = false,
                progress_value = null,
                member_progress = emptyList()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        logProgressHandler = null
    }
}
