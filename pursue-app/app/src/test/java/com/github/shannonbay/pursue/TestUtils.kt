package com.github.shannonbay.pursue

import com.github.shannonbay.pursue.data.network.LoginResponse
import com.github.shannonbay.pursue.data.network.RegistrationResponse
import com.github.shannonbay.pursue.data.network.User

/**
 * Test utilities and helper functions for creating test data.
 */
object TestUtils {
    
    /**
     * Create a test user object.
     */
    fun createTestUser(
        id: String = "user123",
        email: String = "test@example.com",
        displayName: String = "Test User",
        hasAvatar: Boolean = false,
        updatedAt: String? = null
    ): User {
        return User(id, email, displayName, has_avatar = hasAvatar, updated_at = updatedAt)
    }
    
    /**
     * Create a test registration response.
     */
    fun createTestRegistrationResponse(
        accessToken: String = "test_access_token",
        refreshToken: String = "test_refresh_token",
        user: User? = createTestUser()
    ): RegistrationResponse {
        return RegistrationResponse(accessToken, refreshToken, user)
    }
    
    /**
     * Create a test login response.
     */
    fun createTestLoginResponse(
        accessToken: String = "test_access_token",
        refreshToken: String = "test_refresh_token",
        user: User? = createTestUser()
    ): LoginResponse {
        return LoginResponse(accessToken, refreshToken, user)
    }
    
    /**
     * Create test registration credentials.
     */
    fun createTestCredentials(
        displayName: String = "Test User",
        email: String = "test@example.com",
        password: String = "TestPassword123!"
    ): Triple<String, String, String> {
        return Triple(displayName, email, password)
    }
    
    /**
     * Create a test FCM token.
     */
    fun createTestFcmToken(): String {
        return "test_fcm_token_123456789"
    }
    
    /**
     * Create a test device name.
     */
    fun createTestDeviceName(): String {
        return "Test Device"
    }
}
