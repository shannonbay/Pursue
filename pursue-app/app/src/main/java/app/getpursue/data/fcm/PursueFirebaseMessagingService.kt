package app.getpursue.data.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.notifications.NotificationPreferences
import app.getpursue.data.notifications.UnreadBadgeManager
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.activities.MainActivity
import app.getpursue.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging service for handling push notifications and token refresh.
 * 
 * Handles:
 * - FCM token refresh (when Firebase generates a new token)
 * - Incoming push notifications
 */
class PursueFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    companion object {
        private const val TAG = "PursueFCMService"
        private const val CHANNEL_ID = "pursue_default"
        private const val NOTIFICATION_ID_BASE = 1000
    }

    /**
     * Called when a new FCM token is generated (e.g., app reinstall, token rotation).
     * 
     * When this happens, we need to:
     * 1. Clear the old token and registration status
     * 2. Cache the new token
     * 3. Re-register with the server (if user is authenticated)
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed: ${token.take(20)}...")
        
        val fcmManager = FcmTokenManager.getInstance(applicationContext)
        
        // Clear old token and registration status
        fcmManager.clearToken()
        fcmManager.markTokenUnregistered()
        
        // Cache the new token
        fcmManager.cacheToken(token)
        
        // Re-register with server if user is authenticated
        serviceScope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(applicationContext)
                val accessToken = tokenManager.getAccessToken()
                
                if (accessToken != null) {
                    // Register the new token with the server
                    FcmRegistrationHelper.registerFcmTokenIfNeeded(
                        applicationContext,
                        accessToken
                    )

                    // Resubscribe to all FCM topics after token refresh
                    try {
                        val groupsResponse = ApiClient.getMyGroups(accessToken)
                        val groupIds = groupsResponse.groups.map { it.id }
                        FcmTopicManager.resubscribeToAllTopics(applicationContext, groupIds)
                        Log.d(TAG, "Resubscribed to FCM topics for ${groupIds.size} groups")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resubscribe to FCM topics", e)
                    }
                } else {
                    Log.d(TAG, "User not authenticated, will register token on next login")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register new FCM token", e)
            }
        }
    }

    /**
     * Called when a push notification is received.
     * Filters by user preferences (progress logs vs group events) and shows notification if allowed.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Received FCM message: ${remoteMessage.messageId}")

        val type = remoteMessage.data["type"]
        if (!NotificationPreferences.shouldShowNotification(applicationContext, type)) {
            Log.d(TAG, "Skipping notification (user preference): type=$type")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Skipping notification (no POST_NOTIFICATIONS permission)")
                return
            }
        }

        UnreadBadgeManager.incrementCount()

        createNotificationChannelIfNeeded()
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: getString(R.string.app_name)
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        val notificationId = (remoteMessage.messageId?.hashCode()?.and(0x7FFFFFFF) ?: System.currentTimeMillis().toInt()) + NOTIFICATION_ID_BASE
        val contentIntent = buildContentIntent(remoteMessage.data)
        showNotification(notificationId, title, body, contentIntent)
    }

    /**
     * Builds the intent used when the user taps the notification.
     * If group_id is present, deep-links to GroupDetailActivity with routing (tab, pending approvals).
     * Otherwise launches MainActivity.
     */
    private fun buildContentIntent(data: Map<String, String>?): Intent {
        val type = data?.get("type")
        if (type == "removed_from_group") {
            return Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val groupId = data?.get("group_id")?.takeIf { it.isNotBlank() }
        if (groupId == null) {
            return Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val groupName = data?.get("group_name") ?: ""
        val (initialTab, openPendingApprovals) = when (type) {
            "join_request" -> 1 to true
            "progress_logged" -> 2 to false
            "nudge_received", "nudge_sent" -> 0 to false
            "reaction_received", "milestone_achieved" -> 2 to false
            "membership_approved", "membership_rejected", "promoted_to_admin" -> 1 to false
            "member_joined", "member_left", "member_promoted", "member_approved",
            "member_removed", "member_declined", "group_renamed", "invite_code_regenerated", "group_created" -> 1 to false
            "heat_tier_up", "heat_supernova_reached", "heat_streak_milestone" -> 2 to false
            else -> -1 to false
        }
        return Intent(applicationContext, GroupDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(GroupDetailActivity.Companion.EXTRA_GROUP_ID, groupId)
            putExtra(GroupDetailActivity.Companion.EXTRA_GROUP_NAME, groupName)
            putExtra(GroupDetailActivity.Companion.EXTRA_GROUP_HAS_ICON, false)
            putExtra(GroupDetailActivity.Companion.EXTRA_INITIAL_TAB, initialTab)
            putExtra(GroupDetailActivity.Companion.EXTRA_OPEN_PENDING_APPROVALS, openPendingApprovals)
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showNotification(notificationId: Int, title: String, body: String, contentIntent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pursue_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}
