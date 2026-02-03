package com.github.shannonbay.pursue.e2e.auth

import com.github.shannonbay.pursue.data.network.ApiException
import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for authentication endpoints.
 * 
 * Tests register, login, and error handling with real backend server.
 */
class AuthE2ETest : E2ETest() {
    
    @Test
    fun `register creates user and returns tokens`() = runTest {
        // Arrange
        val email = "test-${System.currentTimeMillis()}@example.com"
        val password = "TestPass123!"
        val displayName = "E2E Test User"
        
        // Act
        val response = api.register(displayName, email, password)
        
        // Assert
        assertThat(response.access_token).isNotEmpty()
        assertThat(response.refresh_token).isNotEmpty()
        assertThat(response.user).isNotNull()
        assertThat(response.user!!.email).isEqualTo(email)
        assertThat(response.user!!.display_name).isEqualTo(displayName)
        assertThat(response.user!!.has_avatar).isFalse()
        
        // Cleanup
        trackUser(response.user!!.id)
    }
    
    @Test
    fun `login with correct credentials returns tokens`() = runTest {
        // Arrange - Use shared user (password is TestPass123! from createTestUser)
        val authResponse = getOrCreateSharedUser()
        
        val email = authResponse.user!!.email
        val password = "TestPass123!"
        
        // Act - Login
        val loginResponse = api.login(email, password)
        
        // Assert
        assertThat(loginResponse.access_token).isNotEmpty()
        assertThat(loginResponse.refresh_token).isNotEmpty()
        assertThat(loginResponse.user).isNotNull()
        assertThat(loginResponse.user!!.id).isEqualTo(authResponse.user!!.id)
        assertThat(loginResponse.user!!.email).isEqualTo(email)
    }
    
    @Test
    fun `login with wrong password fails`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        // Act
        var exception: Exception? = null
        try {
            api.login(authResponse.user!!.email, "WrongPassword123!")
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(401)
    }
    
    @Test
    fun `register with duplicate email fails`() = runTest {
        // Arrange - Use shared user's email to try duplicate registration
        val authResponse = getOrCreateSharedUser()
        
        // Act - Try to register again with same email
        var exception: Exception? = null
        try {
            api.register(
                displayName = "Different Name",
                email = authResponse.user!!.email,
                password = "DifferentPass123!"
            )
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        // Backend may return 400 or 409 for duplicate email
        assertThat(apiException.code).isAnyOf(400, 409)
    }
}
