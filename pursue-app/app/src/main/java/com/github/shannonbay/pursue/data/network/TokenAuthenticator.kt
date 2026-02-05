package com.github.shannonbay.pursue.data.network

import android.content.Context
import android.util.Log
import com.github.shannonbay.pursue.data.auth.AuthRepository
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Automatically refreshes access token when receiving 401 Unauthorized.
 * 
 * Flow:
 * 1. Request returns 401
 * 2. Call POST /api/auth/refresh with refresh token
 * 3. Get new access token and refresh token
 * 4. Save new tokens
 * 5. Retry original request with new token
 * 6. If refresh fails, return null (triggers sign out)
 */
class TokenAuthenticator(
    private val context: Context
) : Authenticator {
    
    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val HEADER_RETRY_COUNT = "X-Retry-Count"
    }
    
    // Prevent multiple simultaneous refresh attempts
    private val refreshMutex = Mutex()
    
    override fun authenticate(route: Route?, response: Response): Request? {
        Log.d(TAG, "authenticate: Received 401, attempting token refresh")

        val requestToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")?.trim()

        // Don't retry if this is already a retry
        if (response.request.header(HEADER_RETRY_COUNT) != null) {
            Log.w(TAG, "authenticate: Already retried once, giving up")
            signOutIfCurrentToken(requestToken)
            return null
        }

        // Don't retry if this is a refresh or auth request
        val url = response.request.url.toString()
        if (url.contains("/auth/refresh") ||
            url.contains("/auth/login") ||
            url.contains("/auth/register") ||
            url.contains("/auth/google")) {
            Log.w(TAG, "authenticate: Auth endpoint failed, clearing tokens")
            signOutIfCurrentToken(requestToken)
            return null
        }

        // Get refresh token
        val tokenManager = SecureTokenManager.Companion.getInstance(context)
        val refreshToken = tokenManager.getRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "authenticate: No refresh token available")
            signOutIfCurrentToken(requestToken)
            return null
        }
        
        // Synchronously refresh token (blocks this request)
        return runBlocking {
            refreshMutex.withLock {
                Log.d(TAG, "authenticate: Acquired lock, checking if token already refreshed")
                
                // Check if another thread already refreshed the token (or a new sign-in occurred)
                val currentToken = tokenManager.getAccessToken()

                if (currentToken != null && currentToken != requestToken) {
                    Log.d(TAG, "authenticate: Token already refreshed by another request")
                    // Token was already refreshed, just retry with new token
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer $currentToken")
                        .header(HEADER_RETRY_COUNT, "1")
                        .build()
                }
                
                // Perform token refresh
                Log.d(TAG, "authenticate: Calling /api/auth/refresh")
                
                try {
                    val refreshResponse = ApiClient.refreshToken(refreshToken)
                    
                    // Malformed response: success but missing or empty access_token
                    if (refreshResponse.access_token.isBlank()) {
                        Log.e(TAG, "authenticate: Refresh succeeded but access_token is missing or empty")
                        signOutIfCurrentToken(requestToken)
                        return@withLock null
                    }
                    
                    Log.d(TAG, "authenticate: Token refresh successful")
                    
                    // Save new tokens
                    // Backend may not return refresh_token (it's reused, not rotated)
                    // If refresh_token is null, only update access token
                    if (refreshResponse.refresh_token != null) {
                        tokenManager.storeTokens(
                            accessToken = refreshResponse.access_token,
                            refreshToken = refreshResponse.refresh_token
                        )
                    } else {
                        // Only update access token, keep existing refresh token
                        tokenManager.updateAccessToken(refreshResponse.access_token)
                    }
                    
                    // Retry original request with new token
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer ${refreshResponse.access_token}")
                        .header(HEADER_RETRY_COUNT, "1")
                        .build()
                } catch (e: ApiException) {
                    Log.e(TAG, "authenticate: Token refresh failed with code ${e.code}: ${e.message}")
                    signOutIfCurrentToken(requestToken)
                    return@withLock null
                } catch (e: Exception) {
                    Log.e(TAG, "authenticate: Token refresh exception: ${e.message}", e)
                    signOutIfCurrentToken(requestToken)
                    return@withLock null
                }
            }
        }
    }
    
    /**
     * Sign out only if the request's token still matches the current stored token.
     * If they differ, a new sign-in has occurred and we must not invalidate the new session.
     */
    private fun signOutIfCurrentToken(requestToken: String?) {
        val currentToken = SecureTokenManager.Companion.getInstance(context).getAccessToken()
        if (requestToken != null && currentToken != null && requestToken != currentToken) {
            Log.d(TAG, "signOutIfCurrentToken: Token changed since request, skipping sign-out (stale request)")
            return
        }
        clearTokensAndSignOut()
    }

    private fun clearTokensAndSignOut() {
        Log.d(TAG, "clearTokensAndSignOut: Clearing tokens and signing out")
        val authRepository = AuthRepository.Companion.getInstance(context)
        authRepository.signOut()
    }
}
