package app.getpursue.data.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Signals focus session lifecycle events in a specific group.
 * Called from FCM service; observed by GroupDetailFragment.
 */
object SessionEventManager {
    private val _sessionStartedFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val sessionStartedFlow: SharedFlow<String> = _sessionStartedFlow.asSharedFlow()

    /** Call from any thread when a session_started FCM notification is received. */
    fun emitSessionStarted(groupId: String) {
        _sessionStartedFlow.tryEmit(groupId)
    }

    private val _sessionEndedFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val sessionEndedFlow: SharedFlow<String> = _sessionEndedFlow.asSharedFlow()

    /** Call from any thread when a session_ended FCM notification is received. */
    fun emitSessionEnded(groupId: String) {
        _sessionEndedFlow.tryEmit(groupId)
    }

    private val _memberLoggedTodayFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val memberLoggedTodayFlow: SharedFlow<String> = _memberLoggedTodayFlow.asSharedFlow()

    /** Call from any thread when a member_logged_today FCM notification is received. */
    fun emitMemberLoggedToday(groupId: String) {
        _memberLoggedTodayFlow.tryEmit(groupId)
    }
}
