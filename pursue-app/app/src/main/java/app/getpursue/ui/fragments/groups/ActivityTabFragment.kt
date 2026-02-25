package app.getpursue.ui.fragments.groups

import android.content.Context
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
import app.getpursue.models.ActivityReaction
import app.getpursue.models.GroupActivity
import app.getpursue.models.ReactionSummary
import app.getpursue.models.TopReactor
import app.getpursue.ui.adapters.GroupActivityAdapter
import app.getpursue.ui.adapters.ReactionListener
import app.getpursue.ui.dialogs.FullscreenPhotoDialog
import app.getpursue.ui.helpers.RecyclerViewLongPressHelper
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.ui.views.ReactionPickerPopup
import app.getpursue.ui.views.ReactorsBottomSheet
import app.getpursue.ui.views.ReportEntryBottomSheet
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity Tab Fragment for Group Detail (UI spec section 4.3.3).
 *
 * Displays activity feed grouped by date
 * with pull-to-refresh and 5-state pattern.
 * Supports reactions: long-press to add, tap summary to view reactors.
 */
class ActivityTabFragment : Fragment(), ReactionListener {

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
        adapter = GroupActivityAdapter(
            emptyList(),
            currentUserId,
            onPhotoClick = ::showFullscreenPhoto,
            reactionListener = this@ActivityTabFragment,
            onReportClick = { entryId -> ReportEntryBottomSheet.show(childFragmentManager, entryId) }
        )
        activityRecyclerView.adapter = adapter
        
        // Attach long-press helper to handle reactions (works around RecyclerView touch interception)
        RecyclerViewLongPressHelper.attach(activityRecyclerView)

        // Setup pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            loadActivity()
        }

        // Hide an entry immediately when a report is successfully submitted
        childFragmentManager.setFragmentResultListener(
            ReportEntryBottomSheet.RESULT_REPORTED,
            viewLifecycleOwner
        ) { _, result ->
            val entryId = result.getString(ReportEntryBottomSheet.KEY_ENTRY_ID) ?: return@setFragmentResultListener
            saveHiddenEntryId(entryId)
            cachedActivities = cachedActivities.filter { activity ->
                activity.metadata?.get("progress_entry_id") as? String != entryId
            }
            // Remove directly from the live adapter (no adapter swap = no scroll disruption).
            // Fall back to full refresh only if the item wasn't found.
            if (adapter?.removeActivityByEntryId(entryId) == true) {
                if (adapter?.hasActivities() == false) {
                    updateUiState(ActivityUiState.SUCCESS_EMPTY)
                }
            } else {
                refreshAdapterWithCachedActivities()
            }
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

                val hiddenIds = loadHiddenEntryIds()
                cachedActivities = if (hiddenIds.isEmpty()) {
                    response.activities
                } else {
                    response.activities.filter { activity ->
                        activity.metadata?.get("progress_entry_id") as? String !in hiddenIds
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (cachedActivities.isEmpty()) {
                        updateUiState(ActivityUiState.SUCCESS_EMPTY)
                    } else {
                        adapter?.let { currentAdapter ->
                            val newAdapter = GroupActivityAdapter(
                                cachedActivities,
                                currentUserId,
                                onPhotoClick = ::showFullscreenPhoto,
                                reactionListener = this@ActivityTabFragment,
                                onReportClick = { entryId -> ReportEntryBottomSheet.show(childFragmentManager, entryId) }
                            )
                            activityRecyclerView.adapter = newAdapter
                            adapter = newAdapter
                        } ?: run {
                            adapter = GroupActivityAdapter(
                                cachedActivities,
                                currentUserId,
                                onPhotoClick = ::showFullscreenPhoto,
                                reactionListener = this@ActivityTabFragment,
                                onReportClick = { entryId -> ReportEntryBottomSheet.show(childFragmentManager, entryId) }
                            )
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

    override fun onLongPress(activity: GroupActivity, anchorView: View, touchX: Float, touchY: Float) {
        val activityId = activity.id ?: return
        val currentUserEmoji = activity.reactions?.firstOrNull { it.current_user_reacted }?.emoji

        val popup = ReactionPickerPopup(
            context = requireContext(),
            currentUserEmoji = currentUserEmoji,
            onSelect = { emoji -> handleReactionSelect(activity, activityId, emoji) },
            onDismiss = { }
        )
        popup.show(anchorView, touchX, touchY)
    }

    override fun onReactionButtonClick(activity: GroupActivity, button: View) {
        val activityId = activity.id ?: return
        val currentUserEmoji = activity.reactions?.firstOrNull { it.current_user_reacted }?.emoji

        val popup = ReactionPickerPopup(
            context = requireContext(),
            currentUserEmoji = currentUserEmoji,
            onSelect = { emoji -> handleReactionSelect(activity, activityId, emoji) },
            onDismiss = { }
        )
        popup.show(button)
    }

    override fun onReactionSummaryClick(activityId: String) {
        ReactorsBottomSheet.show(childFragmentManager, activityId)
    }

    private fun handleReactionSelect(activity: GroupActivity, activityId: String, emoji: String) {
        val accessToken = SecureTokenManager.getInstance(requireContext()).getAccessToken() ?: return
        val currentUserEmoji = activity.reactions?.firstOrNull { it.current_user_reacted }?.emoji

        val previousActivities = cachedActivities.toList()
        val updatedActivity = if (emoji == currentUserEmoji) {
            applyReactionRemove(activity)
        } else {
            applyReactionAdd(activity, emoji)
        }

        cachedActivities = cachedActivities.map { if (it.id == activityId) updatedActivity else it }
        refreshAdapterWithCachedActivities()

        lifecycleScope.launch {
            try {
                if (emoji == currentUserEmoji) {
                    withContext(Dispatchers.IO) {
                        ApiClient.removeReaction(accessToken, activityId)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        ApiClient.addOrReplaceReaction(accessToken, activityId, emoji)
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    cachedActivities = previousActivities
                    refreshAdapterWithCachedActivities()
                    view?.let { v ->
                        Snackbar.make(v, getString(R.string.reaction_failed), Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun applyReactionRemove(activity: GroupActivity): GroupActivity {
        val currentReaction = activity.reactions?.firstOrNull { it.current_user_reacted } ?: return activity
        val updatedReactions = activity.reactions!!.map { r ->
            if (r.emoji == currentReaction.emoji) {
                val newCount = (r.count - 1).coerceAtLeast(0)
                val newReactorIds = r.reactor_ids.filter { it != currentUserId }
                r.copy(count = newCount, reactor_ids = newReactorIds, current_user_reacted = false)
            } else r
        }.filter { it.count > 0 }

        val newTotal = (activity.reaction_summary?.total_count ?: 0) - 1
        val newTopReactors = activity.reaction_summary?.top_reactors
            ?.filter { it.user_id != currentUserId }
            ?.take(3)
            ?: emptyList()

        return activity.copy(
            reactions = if (updatedReactions.isEmpty()) null else updatedReactions,
            reaction_summary = if (newTotal <= 0) null else ReactionSummary(
                total_count = newTotal.coerceAtLeast(0),
                top_reactors = newTopReactors
            )
        )
    }

    private fun applyReactionAdd(activity: GroupActivity, emoji: String): GroupActivity {
        val currentReaction = activity.reactions?.firstOrNull { it.current_user_reacted }
        val existingReactions = activity.reactions?.toMutableList() ?: mutableListOf()

        if (currentReaction != null && currentReaction.emoji != emoji) {
            val idx = existingReactions.indexOfFirst { it.emoji == currentReaction.emoji }
            if (idx >= 0) {
                val r = existingReactions[idx]
                val newCount = (r.count - 1).coerceAtLeast(0)
                val newReactorIds = r.reactor_ids.filter { it != currentUserId }
                if (newCount > 0) {
                    existingReactions[idx] = r.copy(count = newCount, reactor_ids = newReactorIds, current_user_reacted = false)
                } else {
                    existingReactions.removeAt(idx)
                }
            }
        }

        val emojiIdx = existingReactions.indexOfFirst { it.emoji == emoji }
        if (emojiIdx >= 0) {
            val r = existingReactions[emojiIdx]
            existingReactions[emojiIdx] = r.copy(
                count = r.count + 1,
                reactor_ids = listOfNotNull(currentUserId) + r.reactor_ids.filter { it != currentUserId },
                current_user_reacted = true
            )
        } else {
            existingReactions.add(
                ActivityReaction(
                    emoji = emoji,
                    count = 1,
                    reactor_ids = listOfNotNull(currentUserId),
                    current_user_reacted = true
                )
            )
        }

        val newTotal = (activity.reaction_summary?.total_count ?: 0) +
            if (currentReaction != null) 0 else 1
        val newTopReactors = listOf(TopReactor(currentUserId ?: "", "You")) +
            (activity.reaction_summary?.top_reactors?.filter { it.user_id != currentUserId } ?: emptyList()).take(2)

        return activity.copy(
            reactions = existingReactions.sortedByDescending { it.count },
            reaction_summary = ReactionSummary(
                total_count = newTotal.coerceAtLeast(1),
                top_reactors = newTopReactors.take(3)
            )
        )
    }

    private fun loadHiddenEntryIds(): Set<String> {
        val prefs = requireContext().getSharedPreferences("pursue_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("reported_progress_entry_ids", emptySet()) ?: emptySet()
    }

    private fun saveHiddenEntryId(entryId: String) {
        val prefs = requireContext().getSharedPreferences("pursue_prefs", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("reported_progress_entry_ids", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("reported_progress_entry_ids", current + entryId).apply()
    }

    private fun refreshAdapterWithCachedActivities() {
        if (cachedActivities.isEmpty()) {
            updateUiState(ActivityUiState.SUCCESS_EMPTY)
        } else {
            val layoutManager = activityRecyclerView.layoutManager as? LinearLayoutManager
            // Read position before swapping the adapter. Using scrollToPositionWithOffset()
            // rather than onSaveInstanceState()/onRestoreInstanceState() — the latter stores a
            // mPendingSavedState that can fire on any subsequent onLayoutChildren() (including
            // user-scroll-triggered ones), causing a snap-back mid-scroll.
            // scrollToPositionWithOffset() sets mPendingScrollPosition which is consumed and
            // cleared in the very first layout pass after the adapter swap.
            val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            val offset = if (firstVisible != RecyclerView.NO_POSITION) {
                layoutManager?.findViewByPosition(firstVisible)?.top ?: 0
            } else 0

            adapter = GroupActivityAdapter(
                cachedActivities,
                currentUserId,
                onPhotoClick = ::showFullscreenPhoto,
                reactionListener = this@ActivityTabFragment,
                onReportClick = { entryId -> ReportEntryBottomSheet.show(childFragmentManager, entryId) }
            )
            activityRecyclerView.adapter = adapter

            if (firstVisible != RecyclerView.NO_POSITION) {
                layoutManager?.scrollToPositionWithOffset(firstVisible, offset)
            }

            updateUiState(ActivityUiState.SUCCESS_WITH_DATA)
        }
    }
}
