package com.github.shannonbay.pursue.data.fcm

import android.content.Context
import android.util.Log
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper class for FCM token registration with the server.
 * 
 * Provides centralized logic for registering FCM tokens and handles
 * retry scenarios. Tracks registration status to avoid unnecessary API calls.
 */
object FcmRegistrationHelper {
    private const val TAG = "FcmRegistrationHelper"
    
    /**
     * Register FCM token with the server if needed.
     * 
     * Checks if the token is already registered, and if not, attempts to register it.
     * This method is idempotent and safe to call multiple times.
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
            Log.d(TAG, "FCM token already registered, skipping")
            return true
        }
        
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
