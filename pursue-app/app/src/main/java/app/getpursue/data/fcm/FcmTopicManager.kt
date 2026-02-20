package app.getpursue.data.fcm

import android.content.Context
import android.util.Log
import app.getpursue.data.notifications.NotificationPreferences
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Manages FCM topic subscriptions for group notifications.
 *
 * Topics follow the pattern: {groupId}_{topicType}
 * - {groupId}_progress_logs - Progress logged notifications
 * - {groupId}_group_events - Group event notifications (goal_added, goal_archived, member_approved, member_removed, member_left, member_promoted)
 */
object FcmTopicManager {
    private const val TAG = "FcmTopicManager"

    const val TOPIC_PROGRESS_LOGS = "progress_logs"
    const val TOPIC_GROUP_EVENTS = "group_events"

    /**
     * Build a topic name for a specific group and topic type.
     * Format: {groupId}_{topicType}
     */
    fun buildTopicName(groupId: String, type: String): String {
        return "${groupId}_${type}"
    }

    /**
     * Subscribe to a specific topic.
     * @return true if successful, false otherwise
     */
    suspend fun subscribeToTopic(topic: String): Boolean {
        return try {
            FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
            Log.d(TAG, "Subscribed to topic: $topic")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to topic: $topic", e)
            false
        }
    }

    /**
     * Unsubscribe from a specific topic.
     * @return true if successful, false otherwise
     */
    suspend fun unsubscribeFromTopic(topic: String): Boolean {
        return try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
            Log.d(TAG, "Unsubscribed from topic: $topic")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unsubscribe from topic: $topic", e)
            false
        }
    }

    /**
     * Subscribe to all topics for a group based on current notification preferences.
     * Called when user joins a group.
     */
    suspend fun subscribeToGroupTopics(context: Context, groupId: String) {
        Log.d(TAG, "Subscribing to topics for group: $groupId")

        if (NotificationPreferences.getNotifyProgressLogs(context)) {
            subscribeToTopic(buildTopicName(groupId, TOPIC_PROGRESS_LOGS))
        }

        if (NotificationPreferences.getNotifyGroupEvents(context)) {
            subscribeToTopic(buildTopicName(groupId, TOPIC_GROUP_EVENTS))
        }
    }

    /**
     * Unsubscribe from all topics for a group.
     * Called when user leaves a group.
     */
    suspend fun unsubscribeFromGroupTopics(groupId: String) {
        Log.d(TAG, "Unsubscribing from all topics for group: $groupId")

        unsubscribeFromTopic(buildTopicName(groupId, TOPIC_PROGRESS_LOGS))
        unsubscribeFromTopic(buildTopicName(groupId, TOPIC_GROUP_EVENTS))
    }

    /**
     * Update subscription for a specific topic type across all user's groups.
     * Called when user toggles a notification preference.
     *
     * @param groupIds List of group IDs the user is a member of
     * @param topicType Either TOPIC_PROGRESS_LOGS or TOPIC_GROUP_EVENTS
     * @param enabled Whether to subscribe (true) or unsubscribe (false)
     */
    suspend fun updatePreferenceForAllGroups(
        groupIds: List<String>,
        topicType: String,
        enabled: Boolean
    ) {
        Log.d(TAG, "Updating topic preference: $topicType = $enabled for ${groupIds.size} groups")

        for (groupId in groupIds) {
            val topic = buildTopicName(groupId, topicType)
            if (enabled) {
                subscribeToTopic(topic)
            } else {
                unsubscribeFromTopic(topic)
            }
        }
    }

    /**
     * Resubscribe to all topics for all groups based on current preferences.
     * Called after FCM token refresh to restore subscriptions.
     *
     * @param context Android context for reading preferences
     * @param groupIds List of group IDs the user is a member of
     */
    suspend fun resubscribeToAllTopics(context: Context, groupIds: List<String>) {
        Log.d(TAG, "Resubscribing to all topics for ${groupIds.size} groups")

        val progressLogsEnabled = NotificationPreferences.getNotifyProgressLogs(context)
        val groupEventsEnabled = NotificationPreferences.getNotifyGroupEvents(context)

        for (groupId in groupIds) {
            if (progressLogsEnabled) {
                subscribeToTopic(buildTopicName(groupId, TOPIC_PROGRESS_LOGS))
            }
            if (groupEventsEnabled) {
                subscribeToTopic(buildTopicName(groupId, TOPIC_GROUP_EVENTS))
            }
        }
    }
}
