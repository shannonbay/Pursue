package com.github.shannonbay.pursue.e2e.config

import java.net.HttpURLConnection
import java.net.URL

/**
 * Configuration for connecting to local dev server during E2E tests.
 */
object LocalServerConfig {
    const val BASE_URL = "http://localhost:3000"
    const val API_BASE_URL = "$BASE_URL/api"
    const val INTERNAL_JOB_KEY = "test-internal-job-key"
    
    /**
     * Check if local dev server is running and accessible.
     * 
     * @return true if server responds with 200 OK, false otherwise
     */
    fun isServerAvailable(): Boolean {
        return try {
            val connection = URL("$BASE_URL/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            connection.connect()
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            responseCode == 200
        } catch (e: Exception) {
            println("❌ Server check failed: ${e.message}")
            false
        }
    }
}
