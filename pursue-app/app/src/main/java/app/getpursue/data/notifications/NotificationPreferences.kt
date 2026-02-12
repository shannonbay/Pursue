package app.getpursue.data.notifications

import android.content.Context
import android.content.SharedPreferences
import app.getpursue.data.fcm.FcmTopicManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Stores and exposes user preferences for fine-grained notification filtering.
 * Progress logs (goal completions) can be spammy; group events (joins, leaves, promotions, renames) are less frequent.
 *
 * With topic-based notifications, changing preferences also updates FCM topic subscriptions.
 */
object NotificationPreferences {

    private const val PREFS_NAME = "notification_prefs"
    private const val KEY_NOTIFY_PROGRESS_LOGS = "notify_progress_logs"
    private const val KEY_NOTIFY_GROUP_EVENTS = "notify_group_events"
    private const val KEY_NOTIFY_NUDGES = "notify_nudges"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getNotifyProgressLogs(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_PROGRESS_LOGS, true)

    fun setNotifyProgressLogs(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_PROGRESS_LOGS, enabled).apply()
    }

    fun getNotifyGroupEvents(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_GROUP_EVENTS, true)

    fun setNotifyGroupEvents(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_GROUP_EVENTS, enabled).apply()
    }

    fun getNotifyNudges(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_NUDGES, true)

    fun setNotifyNudges(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_NUDGES, enabled).apply()
    }

    /**
     * Update progress logs preference and manage FCM topic subscriptions.
     * Use this method instead of setNotifyProgressLogs when you have access to the user's group IDs.
     *
     * @param context Android context
     * @param enabled Whether to enable progress log notifications
     * @param groupIds List of group IDs the user is a member of
     */
    fun setNotifyProgressLogsWithTopics(
        context: Context,
        enabled: Boolean,
        groupIds: List<String>
    ) {
        setNotifyProgressLogs(context, enabled)
        CoroutineScope(Dispatchers.IO).launch {
            FcmTopicManager.updatePreferenceForAllGroups(
                groupIds,
                FcmTopicManager.TOPIC_PROGRESS_LOGS,
                enabled
            )
        }
    }

    /**
     * Update group events preference and manage FCM topic subscriptions.
     * Use this method instead of setNotifyGroupEvents when you have access to the user's group IDs.
     *
     * @param context Android context
     * @param enabled Whether to enable group event notifications
     * @param groupIds List of group IDs the user is a member of
     */
    fun setNotifyGroupEventsWithTopics(
        context: Context,
        enabled: Boolean,
        groupIds: List<String>
    ) {
        setNotifyGroupEvents(context, enabled)
        CoroutineScope(Dispatchers.IO).launch {
            FcmTopicManager.updatePreferenceForAllGroups(
                groupIds,
                FcmTopicManager.TOPIC_GROUP_EVENTS,
                enabled
            )
        }
    }

    /** FCM data type for progress / goal completions (can be spammy). */
    private const val TYPE_PROGRESS_LOGGED = "progress_logged"

    /** FCM data type for nudge notifications (when someone nudges you). */
    private const val TYPE_NUDGE_RECEIVED = "nudge_received"

    /** FCM data types that map to "group events" (joins, leaves, promotions, renames, etc.). */
    private val GROUP_EVENT_TYPES = setOf(
        "member_joined",
        "member_left",
        "member_promoted",
        "member_removed",
        "group_renamed",
        "join_request",
        "member_approved",
        "member_declined",
        "invite_code_regenerated",
        "group_created"
    )

    /**
     * Returns true if the app should show a notification for the given FCM data type based on user preferences.
     * Unknown types are shown for backward compatibility.
     */
    fun shouldShowNotification(context: Context, type: String?): Boolean {
        when (type) {
            TYPE_PROGRESS_LOGGED -> return getNotifyProgressLogs(context)
            TYPE_NUDGE_RECEIVED -> return getNotifyNudges(context)
            in GROUP_EVENT_TYPES -> return getNotifyGroupEvents(context)
            else -> return true
        }
    }
}
