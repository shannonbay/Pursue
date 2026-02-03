package com.github.shannonbay.pursue

import android.app.Application
import com.github.shannonbay.pursue.data.network.ApiClient

/**
 * Application class for Pursue app.
 * 
 * Initializes ApiClient with context for token management.
 */
class PursueApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize API client (buildClient uses applicationContext to avoid leaking)
        ApiClient.initialize(ApiClient.buildClient(this))
    }
}
