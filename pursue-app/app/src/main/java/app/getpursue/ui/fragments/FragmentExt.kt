package app.getpursue.ui.fragments

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.notifications.SessionEventManager
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Observes [SessionEventManager.memberLoggedTodayFlow] while the fragment is RESUMED.
 * Fetches a token and calls [onRefresh] for each event. Silently ignores errors.
 *
 * @param groupId If non-null, only events for this group trigger [onRefresh].
 *                Pass null to respond to events from any group (e.g. TodayFragment).
 */
fun Fragment.observeMemberLoggedToday(
    groupId: String? = null,
    onRefresh: suspend (token: String) -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SessionEventManager.memberLoggedTodayFlow
                .let { flow -> if (groupId != null) flow.filter { it == groupId } else flow }
                .collect {
                    try {
                        val ctx = context ?: return@collect
                        val token = SecureTokenManager.getInstance(ctx).getAccessToken()
                            ?: return@collect
                        onRefresh(token)
                    } catch (_: Exception) {}
                }
        }
    }
}
