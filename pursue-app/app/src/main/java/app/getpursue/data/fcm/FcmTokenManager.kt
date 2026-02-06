package app.getpursue.data.fcm

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Manages Firebase Cloud Messaging (FCM) token retrieval and caching.
 * 
 * Handles async token retrieval and caches the token locally to avoid
 * repeated Firebase API calls.
 */
class FcmTokenManager private constructor(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "fcm_token_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_FCM_REGISTERED = "fcm_token_registered"
        private const val TAG = "FcmTokenManager"
        
        @Volatile
        private var INSTANCE: FcmTokenManager? = null
        
        /**
         * Get the singleton instance of FcmTokenManager.
         * Thread-safe lazy initialization.
         */
        fun getInstance(context: Context): FcmTokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FcmTokenManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    /**
     * Get the FCM token, retrieving it from Firebase if not cached.
     * 
     * @return FCM token string, or null if retrieval fails
     */
    suspend fun getToken(): String? {
        // Check cache first
        val cachedToken = prefs.getString(KEY_FCM_TOKEN, null)
        if (!cachedToken.isNullOrEmpty()) {
            return cachedToken
        }
        
        // Retrieve from Firebase
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (!token.isNullOrEmpty()) {
                // Cache the token
                prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
                Log.d(TAG, "FCM token retrieved and cached: ${token.take(20)}...")
                token
            } else {
                Log.w(TAG, "FCM token is empty")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve FCM token", e)
            null
        }
    }
    
    /**
     * Cache a token directly (e.g., from onNewToken callback).
     * This is used when Firebase provides a new token via onNewToken().
     * 
     * @param token The FCM token to cache
     */
    fun cacheToken(token: String) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        Log.d(TAG, "FCM token cached: ${token.take(20)}...")
    }
    
    /**
     * Check if the FCM token has been successfully registered with the server.
     */
    fun isTokenRegistered(): Boolean {
        return prefs.getBoolean(KEY_FCM_REGISTERED, false)
    }
    
    /**
     * Mark the FCM token as registered with the server.
     */
    fun markTokenRegistered() {
        prefs.edit().putBoolean(KEY_FCM_REGISTERED, true).apply()
        Log.d(TAG, "FCM token marked as registered")
    }
    
    /**
     * Mark the FCM token as not registered (e.g., on token refresh or logout).
     */
    fun markTokenUnregistered() {
        prefs.edit().putBoolean(KEY_FCM_REGISTERED, false).apply()
        Log.d(TAG, "FCM token marked as unregistered")
    }
    
    /**
     * Clear the cached FCM token and registration status (e.g., on logout).
     */
    fun clearToken() {
        prefs.edit()
            .remove(KEY_FCM_TOKEN)
            .remove(KEY_FCM_REGISTERED)
            .apply()
        Log.d(TAG, "FCM token and registration status cleared")
    }
    
    /**
     * Get device name for device registration.
     * 
     * @return Human-readable device name
     */
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } else {
            val capitalizedManufacturer = manufacturer.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase() else it.toString() 
            }
            "$capitalizedManufacturer $model"
        }
    }
}
