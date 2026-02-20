package app.getpursue.data.fcm

import android.content.Context
import android.util.Log
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for FCM token registration with the server.
 *
 * Provides centralized logic for registering FCM tokens and handles
 * retry scenarios. Tracks registration status to avoid unnecessary API calls.
 * Includes time-based throttling to prevent retry storms from rapid network
 * state changes.
 */
object FcmRegistrationHelper {
    private const val TAG = "FcmRegistrationHelper"
    private const val MIN_RETRY_INTERVAL_MS = 60_000L // 1 minute between attempts

    @Volatile
    private var lastAttemptTime: Long = 0L

    /**
     * Register FCM token with the server if needed.
     *
     * Checks if the token is already registered, and if not, attempts to register it.
     * This method is idempotent and safe to call multiple times. Calls within
     * [MIN_RETRY_INTERVAL_MS] of the last attempt are silently skipped.
     *
     * @param context Application context
     * @param accessToken JWT access token for authentication
     * @return true if token is registered (or was already registered), false if registration failed
     */
    suspend fun registerFcmTokenIfNeeded(
        context: Context,
        accessToken: String
    ): Boolean {
        val fcmManager = FcmTokenManager.getInstance(context)

        // Check if already registered
        if (fcmManager.isTokenRegistered()) {
            return true
        }

        // Throttle: skip if called too recently
        val now = System.currentTimeMillis()
        if (now - lastAttemptTime < MIN_RETRY_INTERVAL_MS) {
            Log.d(TAG, "FCM registration throttled (last attempt ${(now - lastAttemptTime) / 1000}s ago)")
            return false
        }
        lastAttemptTime = now

        // Get FCM token
        val fcmToken = fcmManager.getToken()
        if (fcmToken.isNullOrEmpty()) {
            Log.w(TAG, "FCM token not available, cannot register")
            return false
        }

        // Register with server
        return try {
            val deviceName = fcmManager.getDeviceName()
            withContext(Dispatchers.IO) {
                ApiClient.registerDevice(
                    accessToken = accessToken,
                    fcmToken = fcmToken,
                    deviceName = deviceName,
                    platform = "android"
                )
            }
            // Mark as registered on success
            fcmManager.markTokenRegistered()
            Log.d(TAG, "FCM token registered successfully")
            true
        } catch (e: ApiException) {
            Log.w(TAG, "Failed to register FCM token: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register FCM token", e)
            false
        }
    }
}
