package com.github.shannonbay.pursue

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Custom Glide module to add authorization headers for authenticated image requests.
 */
@GlideModule
class PursueGlideModule : AppGlideModule() {
    
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Create OkHttpClient with interceptor to add authorization header
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                
                // Try to get access token from SecureTokenManager
                val tokenManager = SecureTokenManager.getInstance(context)
                val accessToken = tokenManager.getAccessToken()
                
                // Add authorization header if token is available
                val newRequest = if (accessToken != null) {
                    originalRequest.newBuilder()
                        .header("Authorization", "Bearer $accessToken")
                        .build()
                } else {
                    originalRequest
                }
                
                chain.proceed(newRequest)
            }
            .build()
        
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }
    
    override fun isManifestParsingEnabled(): Boolean {
        return false
    }
}
