package app.getpursue.ui.fragments.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.NotificationItem
import app.getpursue.data.notifications.UnreadBadgeManager
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.adapters.NotificationAdapter
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notification inbox screen. Shows list of notifications with pull-to-refresh,
 * pagination, swipe-to-dismiss, and tap-to-navigate. Marks all as read when opened.
 */
class NotificationsFragment : Fragment() {

    companion object {
        fun newInstance(): NotificationsFragment = NotificationsFragment()
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var skeletonContainer: LinearLayout
    private lateinit var emptyStateContainer: FrameLayout
    private lateinit var errorStateContainer: FrameLayout
    private var adapter: NotificationAdapter? = null
    private var errorStateView: ErrorStateView? = null

    private var cachedList: MutableList<NotificationItem> = mutableListOf()
    private var hasMore = false
    private var loadingMore = false
    private var currentState: NotificationsUiState = NotificationsUiState.LOADING

    enum class NotificationsUiState {
        LOADING,
        SUCCESS_WITH_DATA,
        SUCCESS_EMPTY,
        ERROR,
        OFFLINE
    }

    private val groupDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result not needed */ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_notifications, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.notifications_recycler_view)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        skeletonContainer = view.findViewById(R.id.skeleton_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = NotificationAdapter(emptyList(), requireContext(), ::onNotificationClick)
        recyclerView.adapter = adapter

        swipeRefreshLayout.setOnRefreshListener {
            cachedList.clear()
            loadNotifications(beforeId = null, isRefresh = true)
        }

        setupSwipeToDismiss()
        setupPagination()
        loadNotifications(beforeId = null)
    }

    private fun loadNotifications(beforeId: String?, isRefresh: Boolean = false) {
        val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
        if (token == null) {
            updateUiState(NotificationsUiState.ERROR, ErrorStateView.ErrorType.UNAUTHORIZED)
            swipeRefreshLayout.isRefreshing = false
            return
        }

        if (!isRefresh && beforeId == null) {
            updateUiState(NotificationsUiState.LOADING)
        }
        if (beforeId != null) loadingMore = true

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getNotifications(token, limit = 30, beforeId = beforeId)
                }
                if (beforeId == null) {
                    cachedList.clear()
                    cachedList.addAll(response.notifications)
                    markAllAsRead(token)
                    UnreadBadgeManager.clearCount()
                } else {
                    cachedList.addAll(response.notifications)
                }
                hasMore = response.has_more
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    loadingMore = false
                    swipeRefreshLayout.isRefreshing = false
                    if (cachedList.isEmpty()) {
                        updateUiState(NotificationsUiState.SUCCESS_EMPTY)
                    } else {
                        adapter = NotificationAdapter(cachedList.toList(), requireContext(), ::onNotificationClick)
                        recyclerView.adapter = adapter
                        updateUiState(NotificationsUiState.SUCCESS_WITH_DATA)
                    }
                }
            } catch (e: ApiException) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    loadingMore = false
                    swipeRefreshLayout.isRefreshing = false
                    if (cachedList.isEmpty()) {
                        val errorType = ErrorStateView.errorTypeFromApiException(e)
                        updateUiState(NotificationsUiState.ERROR, errorType)
                    } else {
                        view?.let { Snackbar.make(it, e.message ?: "Error loading notifications", Snackbar.LENGTH_SHORT).show() }
                    }
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    loadingMore = false
                    swipeRefreshLayout.isRefreshing = false
                    if (cachedList.isEmpty()) {
                        updateUiState(NotificationsUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
                    } else {
                        view?.let { Snackbar.make(it, getString(R.string.error_loading_notifications), Snackbar.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }

    private fun markAllAsRead(accessToken: String) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.markAllNotificationsRead(accessToken)
                }
                UnreadBadgeManager.setCount(0)
                Handler(Looper.getMainLooper()).post {
                    if (isAdded) {
                        cachedList = cachedList.map { it.copy(is_read = true) }.toMutableList()
                        adapter = NotificationAdapter(cachedList.toList(), requireContext(), ::onNotificationClick)
                        recyclerView.adapter = adapter
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun deleteNotification(id: String) {
        val token = SecureTokenManager.getInstance(requireContext()).getAccessToken() ?: return
        val previousList = cachedList.toList()
        cachedList.removeAll { it.id == id }
        adapter = NotificationAdapter(cachedList.toList(), requireContext(), ::onNotificationClick)
        recyclerView.adapter = adapter
        if (cachedList.isEmpty()) updateUiState(NotificationsUiState.SUCCESS_EMPTY)
        view?.let { Snackbar.make(it, getString(R.string.notification_deleted), Snackbar.LENGTH_SHORT).show() }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient.deleteNotification(token, id) }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    cachedList.clear()
                    cachedList.addAll(previousList)
                    adapter = NotificationAdapter(cachedList.toList(), requireContext(), ::onNotificationClick)
                    recyclerView.adapter = adapter
                    updateUiState(NotificationsUiState.SUCCESS_WITH_DATA)
                    view?.let { Snackbar.make(it, "Could not remove notification", Snackbar.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun onNotificationClick(item: NotificationItem) {
        val token = SecureTokenManager.getInstance(requireContext()).getAccessToken() ?: return
        if (!item.is_read) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { ApiClient.markNotificationRead(token, item.id) }
                    val idx = cachedList.indexOfFirst { it.id == item.id }
                    if (idx >= 0) {
                        cachedList[idx] = item.copy(is_read = true)
                        adapter = NotificationAdapter(cachedList.toList(), requireContext(), ::onNotificationClick)
                        recyclerView.adapter = adapter
                    }
                } catch (_: Exception) { /* ignore */ }
            }
        }

        when (item.type) {
            "membership_rejected" -> { /* no navigation */ }
            "removed_from_group" -> {
                Snackbar.make(requireView(), getString(R.string.no_longer_member_toast), Snackbar.LENGTH_SHORT).show()
            }
            "join_request_received" -> {
                val groupId = item.group?.id
                val groupName = item.group?.name ?: ""
                if (groupId != null) {
                    val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
                        putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
                        putExtra(GroupDetailActivity.EXTRA_INITIAL_TAB, 1) // Members tab
                        putExtra(GroupDetailActivity.EXTRA_OPEN_PENDING_APPROVALS, true)
                    }
                    groupDetailLauncher.launch(intent)
                }
            }
            "weekly_recap" -> {
                // Open the group's Goals tab so the user can dive straight into this week's activity
                val groupId = item.group?.id
                val groupName = item.group?.name ?: ""
                if (groupId != null) {
                    val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
                        putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
                        putExtra(GroupDetailActivity.EXTRA_INITIAL_TAB, 0) // Goals tab
                    }
                    groupDetailLauncher.launch(intent)
                }
            }
            else -> {
                val groupId = item.group?.id
                val groupName = item.group?.name ?: ""
                if (groupId != null) {
                    val initialTab = when (item.type) {
                        "nudge_received" -> 0
                        "reaction_received", "milestone_achieved" -> 2
                        else -> 1
                    }
                    val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
                        putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
                        putExtra(GroupDetailActivity.EXTRA_INITIAL_TAB, initialTab)
                    }
                    groupDetailLauncher.launch(intent)
                }
            }
        }
    }

    private fun setupSwipeToDismiss() {
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos in cachedList.indices) {
                    val id = cachedList[pos].id
                    deleteNotification(id)
                }
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)
    }

    private fun setupPagination() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (loadingMore || !hasMore || cachedList.isEmpty()) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible >= cachedList.size - 3) {
                    val lastId = cachedList.last().id
                    loadNotifications(beforeId = lastId)
                }
            }
        })
    }

    private fun updateUiState(state: NotificationsUiState, errorType: ErrorStateView.ErrorType? = null) {
        currentState = state
        when (state) {
            NotificationsUiState.LOADING -> {
                skeletonContainer.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            NotificationsUiState.SUCCESS_WITH_DATA -> {
                skeletonContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            NotificationsUiState.SUCCESS_EMPTY -> {
                skeletonContainer.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE
                if (emptyStateContainer.childCount == 0) {
                    val emptyView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.empty_state_generic, emptyStateContainer, false)
                    emptyView.findViewById<TextView>(R.id.empty_title).text = getString(R.string.empty_notifications_title)
                    emptyView.findViewById<TextView>(R.id.empty_message).text = getString(R.string.empty_notifications_message)
                    emptyView.findViewById<View>(R.id.empty_icon)?.let { (it as? TextView)?.text = "🔔" }
                    emptyView.findViewById<MaterialButton>(R.id.empty_action_button)?.visibility = View.GONE
                    emptyStateContainer.addView(emptyView)
                }
            }
            NotificationsUiState.ERROR -> {
                skeletonContainer.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE
                val currentErrorType = errorType ?: ErrorStateView.ErrorType.NETWORK
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener { loadNotifications(beforeId = null) }
                }
                errorStateView?.setErrorType(currentErrorType)
                errorStateView?.setCustomMessage(R.string.error_loading_notifications, R.string.retry)
            }
            NotificationsUiState.OFFLINE -> {
                if (cachedList.isNotEmpty()) updateUiState(NotificationsUiState.SUCCESS_WITH_DATA)
                else updateUiState(NotificationsUiState.ERROR, ErrorStateView.ErrorType.NETWORK)
            }
        }
    }
}
