package app.getpursue.data.notifications

import android.util.Log
import app.getpursue.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Singleton that holds the notification inbox unread count for the bell badge.
 * Updated on app launch (fetchUnreadCount), when inbox is opened (clearCount),
 * and when an FCM push is received (incrementCount).
 */
object UnreadBadgeManager {

    private const val TAG = "UnreadBadgeManager"

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    /**
     * Fetches the current unread count from the API and updates the badge.
     * Call from MainAppActivity onResume (or similar) when user is authenticated.
     */
    suspend fun fetchUnreadCount(accessToken: String) {
        Log.d(TAG, "fetchUnreadCount: calling API...")
        val response = withContext(Dispatchers.IO) {
            ApiClient.getUnreadCount(accessToken)
        }
        Log.d(TAG, "fetchUnreadCount: API returned unread_count=${response.unread_count}")
        _unreadCount.value = response.unread_count
    }

    /**
     * Increments the local unread count by one. Call when an FCM push is received
     * so the badge updates without a full refetch.
     */
    fun incrementCount() {
        val oldValue = _unreadCount.value
        _unreadCount.value = (oldValue + 1).coerceAtLeast(0)
        Log.d(TAG, "incrementCount: $oldValue -> ${_unreadCount.value}")
    }

    /**
     * Sets the unread count to zero. Call when the user opens the inbox screen
     * (after mark-all-read is invoked) so the badge clears immediately.
     */
    fun clearCount() {
        val oldValue = _unreadCount.value
        _unreadCount.value = 0
        Log.d(TAG, "clearCount: $oldValue -> 0")
        // Log stack trace to see who called this
        Log.d(TAG, "clearCount called from: ${Thread.currentThread().stackTrace.take(8).joinToString("\n") { it.toString() }}")
    }

    /**
     * Sets the unread count to a specific value.
     * Use this to sync badge with server after bulk actions.
     */
    fun setCount(count: Int) {
        val oldValue = _unreadCount.value
        _unreadCount.value = count.coerceAtLeast(0)
        Log.d(TAG, "setCount: $oldValue -> ${_unreadCount.value}")
        if (count == 0 && oldValue > 0) {
            // Log stack trace when zeroing to catch unexpected clears
            Log.d(TAG, "setCount(0) called from: ${Thread.currentThread().stackTrace.take(8).joinToString("\n") { it.toString() }}")
        }
    }
}
