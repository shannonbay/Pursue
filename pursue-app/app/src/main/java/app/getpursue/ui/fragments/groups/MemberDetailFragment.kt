package app.getpursue.ui.fragments.groups

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.MemberProgressActivityEntry
import app.getpursue.data.network.MemberProgressGoalSummary
import app.getpursue.data.network.MemberProgressResponse
import app.getpursue.models.Timeframe
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.adapters.MemberActivityAdapter
import app.getpursue.ui.dialogs.FullscreenPhotoDialog
import app.getpursue.ui.views.ErrorStateView
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Member Detail Fragment (per specs/member-detail-spec.md).
 * 
 * Shows a member's progress within a group, with:
 * - Member header (avatar, name, role, joined date)
 * - Timeframe selection chips (with premium gating)
 * - Goal overview cards with progress bars
 * - Paginated activity log
 */
class MemberDetailFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_USER_ID = "user_id"
        private const val ARG_GROUP_NAME = "group_name"

        fun newInstance(groupId: String, userId: String, groupName: String = ""): MemberDetailFragment {
            return MemberDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_USER_ID, userId)
                    putString(ARG_GROUP_NAME, groupName)
                }
            }
        }
    }

    // Arguments
    private var groupId: String? = null
    private var userId: String? = null
    private var groupName: String? = null

    // Views
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var nestedScrollView: NestedScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var memberHeaderCard: MaterialCardView
    private lateinit var memberAvatar: ImageView
    private lateinit var memberAvatarFallback: TextView
    private lateinit var memberDisplayName: TextView
    private lateinit var memberRoleBadge: TextView
    private lateinit var memberJoinedDate: TextView
    private lateinit var timeframeChipGroup: ChipGroup
    private lateinit var goalSummariesContainer: LinearLayout
    private lateinit var activityRecyclerView: RecyclerView
    private lateinit var loadingMoreIndicator: ProgressBar
    private lateinit var activityEmptyState: LinearLayout
    private lateinit var activityEmptyMessage: TextView
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var errorStateContainer: FrameLayout
    private lateinit var emptyStateContainer: FrameLayout

    // State
    private var selectedTimeframe: Timeframe = Timeframe.DEFAULT
    private var isPremiumUser: Boolean = false
    private var memberProgress: MemberProgressResponse? = null
    private val activityEntries: MutableList<MemberProgressActivityEntry> = mutableListOf()
    private var nextCursor: String? = null
    private var hasMore: Boolean = false
    private var isLoadingMore: Boolean = false
    private var isInitialLoad: Boolean = true

    // Adapter
    private lateinit var activityAdapter: MemberActivityAdapter
    private var errorStateView: ErrorStateView? = null

    enum class MemberDetailUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR
    }

    private var currentState: MemberDetailUiState = MemberDetailUiState.LOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
        userId = arguments?.getString(ARG_USER_ID)
        groupName = arguments?.getString(ARG_GROUP_NAME)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_member_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        nestedScrollView = view.findViewById(R.id.nested_scroll_view)
        contentContainer = view.findViewById(R.id.content_container)
        memberHeaderCard = view.findViewById(R.id.member_header_card)
        memberAvatar = view.findViewById(R.id.member_avatar)
        memberAvatarFallback = view.findViewById(R.id.member_avatar_fallback)
        memberDisplayName = view.findViewById(R.id.member_display_name)
        memberRoleBadge = view.findViewById(R.id.member_role_badge)
        memberJoinedDate = view.findViewById(R.id.member_joined_date)
        timeframeChipGroup = view.findViewById(R.id.timeframe_chip_group)
        goalSummariesContainer = view.findViewById(R.id.goal_summaries_container)
        activityRecyclerView = view.findViewById(R.id.activity_recycler_view)
        loadingMoreIndicator = view.findViewById(R.id.loading_more_indicator)
        activityEmptyState = view.findViewById(R.id.activity_empty_state)
        activityEmptyMessage = view.findViewById(R.id.activity_empty_message)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)

        // Setup toolbar title
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = groupName

        // Setup RecyclerView
        activityAdapter = MemberActivityAdapter { photoUrl ->
            showFullscreenPhoto(photoUrl)
        }
        activityRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        activityRecyclerView.adapter = activityAdapter
        activityRecyclerView.isNestedScrollingEnabled = false

        // Setup infinite scroll
        setupInfiniteScroll()

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadMemberProgress()
        }

        // Setup timeframe chips
        setupTimeframeChips()

        // Fetch subscription status and load data
        fetchSubscriptionStatusAndLoad()
    }

    private fun setupTimeframeChips() {
        timeframeChipGroup.removeAllViews()
        
        for (timeframe in Timeframe.entries) {
            val chip = Chip(requireContext()).apply {
                text = getString(timeframe.labelRes)
                isCheckable = true
                isChecked = timeframe == selectedTimeframe
                tag = timeframe
                
                // Set chip style
                setChipBackgroundColorResource(R.color.surface)
                setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.on_surface))
                chipStrokeWidth = 1f
                setChipStrokeColorResource(R.color.on_surface_variant)
                
                // Premium gating visual
                if (timeframe.isPremium && !isPremiumUser) {
                    setChipIconResource(R.drawable.ic_lock)
                    chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.secondary)
                    setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.secondary))
                    contentDescription = getString(R.string.content_description_locked_timeframe, getString(timeframe.labelRes))
                }
                
                setOnClickListener {
                    onTimeframeChipClicked(timeframe)
                }
            }
            timeframeChipGroup.addView(chip)
        }
    }

    private fun onTimeframeChipClicked(timeframe: Timeframe) {
        // Premium gating check
        if (timeframe.isPremium && !isPremiumUser) {
            showPremiumUpsellSheet()
            // Reset chip selection to current
            updateChipSelection()
            return
        }
        
        if (timeframe != selectedTimeframe) {
            selectedTimeframe = timeframe
            updateChipSelection()
            // Reset pagination and reload
            activityEntries.clear()
            nextCursor = null
            hasMore = false
            loadMemberProgress()
        }
    }

    private fun updateChipSelection() {
        for (i in 0 until timeframeChipGroup.childCount) {
            val chip = timeframeChipGroup.getChildAt(i) as? Chip
            chip?.isChecked = chip?.tag == selectedTimeframe
        }
    }

    private fun setupInfiniteScroll() {
        nestedScrollView.setOnScrollChangeListener { v: NestedScrollView, _, scrollY, _, _ ->
            val childView = v.getChildAt(0)
            if (childView != null) {
                val diff = childView.height - (v.height + scrollY)
                // Trigger load more when within 200dp of bottom
                if (diff < 200 && !isLoadingMore && hasMore && !isInitialLoad) {
                    loadMoreEntries()
                }
            }
        }
    }

    private fun fetchSubscriptionStatusAndLoad() {
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken() ?: return@launch

                // Fetch subscription status
                val subscription = try {
                    withContext(Dispatchers.IO) {
                        ApiClient.getSubscription(accessToken)
                    }
                } catch (e: Exception) {
                    null
                }

                isPremiumUser = subscription?.tier == "premium"
                
                // Update chip visuals based on premium status
                Handler(Looper.getMainLooper()).post {
                    setupTimeframeChips()
                }

                // Load member progress
                loadMemberProgress()
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    updateUiState(MemberDetailUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                }
            }
        }
    }

    private fun loadMemberProgress(cursor: String? = null) {
        val gid = groupId ?: return
        val uid = userId ?: return

        lifecycleScope.launch {
            if (cursor == null && !swipeRefreshLayout.isRefreshing) {
                updateUiState(MemberDetailUiState.LOADING)
            }

            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        updateUiState(MemberDetailUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
                        swipeRefreshLayout.isRefreshing = false
                    }
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.getMemberProgress(
                        accessToken = accessToken,
                        groupId = gid,
                        userId = uid,
                        startDate = selectedTimeframe.getStartDateString(),
                        endDate = selectedTimeframe.getEndDateString(),
                        cursor = cursor,
                        limit = 50
                    )
                }

                Handler(Looper.getMainLooper()).post {
                    if (cursor == null) {
                        // First page - reset everything
                        memberProgress = response
                        activityEntries.clear()
                        activityEntries.addAll(response.activity_log)
                        
                        // Populate header
                        populateMemberHeader(response)
                        
                        // Populate goal summaries
                        populateGoalSummaries(response.goal_summaries)
                    } else {
                        // Subsequent page - append entries
                        activityEntries.addAll(response.activity_log)
                    }
                    
                    nextCursor = response.pagination.next_cursor
                    hasMore = response.pagination.has_more
                    isInitialLoad = false
                    isLoadingMore = false
                    
                    // Update activity adapter
                    activityAdapter.submitData(activityEntries, false)
                    loadingMoreIndicator.visibility = View.GONE
                    
                    // Update UI state
                    if (activityEntries.isEmpty() && response.goal_summaries.isEmpty()) {
                        updateUiState(MemberDetailUiState.SUCCESS_EMPTY)
                    } else {
                        updateUiState(MemberDetailUiState.SUCCESS_WITH_DATA)
                        
                        // Show activity empty state if no entries but has goals
                        if (activityEntries.isEmpty()) {
                            activityEmptyState.visibility = View.VISIBLE
                            activityEmptyMessage.text = getString(
                                R.string.empty_member_activity_message,
                                response.member.display_name
                            )
                        } else {
                            activityEmptyState.visibility = View.GONE
                        }
                    }
                    
                    swipeRefreshLayout.isRefreshing = false
                }
            } catch (e: ApiException) {
                Handler(Looper.getMainLooper()).post {
                    val errorType = ErrorStateView.errorTypeFromApiException(e)
                    updateUiState(MemberDetailUiState.ERROR, errorType)
                    swipeRefreshLayout.isRefreshing = false
                    isLoadingMore = false
                    loadingMoreIndicator.visibility = View.GONE
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    updateUiState(MemberDetailUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                    swipeRefreshLayout.isRefreshing = false
                    isLoadingMore = false
                    loadingMoreIndicator.visibility = View.GONE
                }
            }
        }
    }

    private fun loadMoreEntries() {
        val cursor = nextCursor ?: return
        if (isLoadingMore) return
        
        isLoadingMore = true
        loadingMoreIndicator.visibility = View.VISIBLE
        activityAdapter.submitData(activityEntries, true)
        
        loadMemberProgress(cursor)
    }

    private fun populateMemberHeader(response: MemberProgressResponse) {
        val member = response.member
        
        // Display name
        memberDisplayName.text = member.display_name
        
        // Avatar: use same URL as MembersTabFragment so Glide uses authenticated request
        memberAvatar.visibility = View.VISIBLE
        memberAvatarFallback.visibility = View.GONE
        val imageUrl = "${ApiClient.getBaseUrl()}/users/${member.user_id}/avatar"
        Glide.with(requireContext())
            .load(imageUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .circleCrop()
            .error(R.drawable.ic_pursue_logo)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    // No avatar or load failed: show initials fallback
                    memberAvatar.post {
                        memberAvatar.visibility = View.GONE
                        memberAvatarFallback.visibility = View.VISIBLE
                        val firstLetter = member.display_name.firstOrNull()?.uppercaseChar() ?: '?'
                        memberAvatarFallback.text = firstLetter.toString()
                    }
                    return false
                }
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(memberAvatar)
        memberAvatar.contentDescription = getString(R.string.content_description_member_avatar, member.display_name)
        
        // Role badge
        val roleText = when (member.role) {
            "creator", "admin" -> getString(R.string.role_admin)
            else -> getString(R.string.role_member)
        }
        memberRoleBadge.text = roleText
        
        // Joined date
        val joinedFormatted = try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = dateFormat.parse(member.joined_at.substringBefore('.').substringBefore('Z'))
            val monthYearFormat = SimpleDateFormat("MMM yyyy", Locale.US)
            getString(R.string.member_joined, monthYearFormat.format(date!!))
        } catch (e: Exception) {
            getString(R.string.member_joined, "")
        }
        memberJoinedDate.text = joinedFormatted
    }

    private fun populateGoalSummaries(summaries: List<MemberProgressGoalSummary>) {
        goalSummariesContainer.removeAllViews()
        
        for (summary in summaries) {
            val cardView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_goal_summary_card, goalSummariesContainer, false)
            
            val goalEmoji: TextView = cardView.findViewById(R.id.goal_emoji)
            val goalTitle: TextView = cardView.findViewById(R.id.goal_title)
            val goalProgressBar: LinearProgressIndicator = cardView.findViewById(R.id.goal_progress_bar)
            val goalPercentage: TextView = cardView.findViewById(R.id.goal_percentage)
            val goalCompletion: TextView = cardView.findViewById(R.id.goal_completion)
            
            // Emoji and title
            goalEmoji.text = summary.emoji ?: "🎯"
            goalTitle.text = summary.title
            
            // Progress bar
            goalProgressBar.progress = summary.percentage
            
            // Percentage
            goalPercentage.text = "${summary.percentage}%"
            
            // Completion text
            val completionText = when (summary.metric_type) {
                "binary" -> {
                    val cadenceUnit = when (summary.cadence) {
                        "daily" -> "days"
                        "weekly" -> "weeks"
                        "monthly" -> "months"
                        else -> "days"
                    }
                    getString(R.string.binary_completion, summary.completed.toInt(), summary.total.toInt(), cadenceUnit)
                }
                "numeric", "duration" -> {
                    val unit = summary.unit ?: ""
                    getString(R.string.numeric_completion, summary.completed, summary.total, unit)
                }
                else -> "${summary.completed.toInt()} / ${summary.total.toInt()}"
            }
            goalCompletion.text = completionText
            
            // Accessibility
            goalProgressBar.contentDescription = getString(
                R.string.content_description_progress_bar,
                summary.title,
                summary.percentage,
                completionText
            )
            
            goalSummariesContainer.addView(cardView)
        }
    }

    private fun showPremiumUpsellSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.bottom_sheet_premium_upsell, null)
        
        val upgradeButton: MaterialButton = view.findViewById(R.id.upsell_upgrade_button)
        val maybeLaterButton: MaterialButton = view.findViewById(R.id.upsell_maybe_later_button)
        
        upgradeButton.setOnClickListener {
            dialog.dismiss()
            (requireActivity() as? GroupDetailActivity)?.showPremiumScreen()
        }
        
        maybeLaterButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showFullscreenPhoto(photoUrl: String) {
        FullscreenPhotoDialog.newInstance(photoUrl)
            .show(childFragmentManager, "FullscreenPhotoDialog")
    }

    private fun updateUiState(state: MemberDetailUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state

        when (state) {
            MemberDetailUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                swipeRefreshLayout.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
            }
            MemberDetailUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE
                contentContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
            }
            MemberDetailUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                swipeRefreshLayout.visibility = View.VISIBLE
                contentContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                // The activity empty state is shown inline
            }
            MemberDetailUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                swipeRefreshLayout.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE
                
                val currentErrorType = errorType ?: ErrorStateView.ErrorType.NETWORK
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.Companion.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener {
                        fetchSubscriptionStatusAndLoad()
                    }
                }
                errorStateView?.setErrorType(currentErrorType)
                if (currentErrorType != ErrorStateView.ErrorType.PENDING_APPROVAL && currentErrorType != ErrorStateView.ErrorType.FORBIDDEN) {
                    errorStateView?.setCustomMessage(R.string.error_loading_member_progress, R.string.retry)
                }
            }
        }
    }
}
