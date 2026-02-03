package com.github.shannonbay.pursue.data.fcm

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.google.firebase.messaging.RemoteMessage

/**
 * Unit tests for PursueFirebaseMessagingService.
 *
 * Tests FCM token refresh handling:
 * - Token refresh clears old status
 * - New token is cached
 * - Re-registration is triggered
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class PursueFirebaseMessagingServiceTest {

    private lateinit var context: Context
    private lateinit var service: PursueFirebaseMessagingService
    private lateinit var mockFcmTokenManager: FcmTokenManager
    private lateinit var mockSecureTokenManager: SecureTokenManager
    private val oldToken = "old_fcm_token_123"
    private val newToken = "new_fcm_token_456"
    private val testAccessToken = "test_access_token_789"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Mock FcmTokenManager
        mockkObject(FcmTokenManager.Companion)
        mockFcmTokenManager = mockk(relaxed = true)
        every { FcmTokenManager.getInstance(any()) } returns mockFcmTokenManager

        // Mock SecureTokenManager
        mockkObject(SecureTokenManager.Companion)
        mockSecureTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.getInstance(any()) } returns mockSecureTokenManager

        // Mock FcmRegistrationHelper
        mockkObject(FcmRegistrationHelper)

        // Create service instance and attach context using reflection
        // Services need a context attached to access applicationContext
        // attachBaseContext() is protected in ContextWrapper, so we use reflection
        service = PursueFirebaseMessagingService()
        val attachBaseContextMethod = ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext",
            Context::class.java
        )
        attachBaseContextMethod.isAccessible = true
        attachBaseContextMethod.invoke(service, context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // Test 4: Token Refresh → Should Clear Registration Status and Re-Register
    @Test
    fun `test token refresh should clear old status and re-register when authenticated`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true

        // When
        service.onNewToken(newToken)

        // Wait for coroutines to complete
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - verify old token and status were cleared
        verify { mockFcmTokenManager.clearToken() }
        verify { mockFcmTokenManager.markTokenUnregistered() }
        
        // Verify new token was cached
        verify { mockFcmTokenManager.cacheToken(newToken) }
        
        // Verify re-registration was triggered
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken) }
    }

    @Test
    fun `test token refresh should not register when not authenticated`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns null // Not authenticated

        // When
        service.onNewToken(newToken)

        // Wait for coroutines to complete
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - verify token was still cleared and cached
        verify { mockFcmTokenManager.clearToken() }
        verify { mockFcmTokenManager.markTokenUnregistered() }
        verify { mockFcmTokenManager.cacheToken(newToken) }
        
        // But registration should not be attempted
        coVerify(exactly = 0) { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) }
    }

    @Test
    fun `test token refresh should handle registration failure gracefully`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns false

        // When
        service.onNewToken(newToken)

        // Wait for coroutines to complete
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - verify token was still cleared and cached (even if registration fails)
        verify { mockFcmTokenManager.clearToken() }
        verify { mockFcmTokenManager.markTokenUnregistered() }
        verify { mockFcmTokenManager.cacheToken(newToken) }
        
        // Registration was attempted but failed
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken) }
    }

    @Test
    fun `test token refresh should clear token before caching new one`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true

        // When
        service.onNewToken(newToken)

        // Wait for coroutines
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - verify order: clearToken is called before cacheToken
        verifyOrder {
            mockFcmTokenManager.clearToken()
            mockFcmTokenManager.markTokenUnregistered()
            mockFcmTokenManager.cacheToken(newToken)
        }
    }

    @Test
    fun `test token refresh with exception during registration should not crash`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } throws Exception("Registration failed")

        // When
        service.onNewToken(newToken)

        // Wait for coroutines
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // Then - verify token was still cleared and cached (exception handled gracefully)
        verify { mockFcmTokenManager.clearToken() }
        verify { mockFcmTokenManager.markTokenUnregistered() }
        verify { mockFcmTokenManager.cacheToken(newToken) }
    }

    @Test
    fun `test onMessageReceived is a placeholder`() {
        // Given
        val mockRemoteMessage = mockk<RemoteMessage>(relaxed = true)
        every { mockRemoteMessage.messageId } returns "test_message_id"

        // When
        service.onMessageReceived(mockRemoteMessage)

        // Then - should not crash (placeholder implementation)
        // This test just ensures the method exists and doesn't throw
        assertTrue("onMessageReceived should be callable", true)
    }
}
