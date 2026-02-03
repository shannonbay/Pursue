package com.github.shannonbay.pursue.data.network

import android.content.Context
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Authorization header to all API requests (except auth endpoints).
 * 
 * Automatically retrieves access token from SecureTokenManager and adds
 * it to requests that require authentication.
 */
class AuthInterceptor(
    private val context: Context
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        
        // Skip auth header for these endpoints (they don't need it)
        if (shouldSkipAuth(url)) {
            return chain.proceed(request)
        }
        
        // Add access token to request
        val tokenManager = SecureTokenManager.Companion.getInstance(context)
        val accessToken = tokenManager.getAccessToken()
        
        val authenticatedRequest = if (accessToken != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            request
        }
        
        return chain.proceed(authenticatedRequest)
    }
    
    /**
     * Check if the URL should skip authentication header.
     * 
     * Skip for:
     * - Auth endpoints (login, register, google, refresh)
     * - Public avatar endpoints (/users/{id}/avatar where id != "me")
     * - Public group icon endpoints (/groups/{id}/icon)
     */
    private fun shouldSkipAuth(url: String): Boolean {
        return url.contains("/auth/login") ||
               url.contains("/auth/register") ||
               url.contains("/auth/refresh") ||
               url.contains("/auth/google") ||
               (url.contains("/users/") && url.endsWith("/avatar") && !url.contains("/users/me/avatar")) ||
               (url.contains("/groups/") && url.endsWith("/icon"))
    }
}
