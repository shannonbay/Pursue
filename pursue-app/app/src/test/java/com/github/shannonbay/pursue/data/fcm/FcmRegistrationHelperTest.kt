package com.github.shannonbay.pursue.data.fcm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.shannonbay.pursue.MockApiClient
import com.github.shannonbay.pursue.data.network.ApiClient
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for FcmRegistrationHelper.
 * 
 * Tests FCM token registration logic with mocked dependencies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class FcmRegistrationHelperTest {
    
    private lateinit var context: Context
    private lateinit var mockFcmTokenManager: FcmTokenManager
    private val testAccessToken = "test_access_token"
    private val testFcmToken = "test_fcm_token_123"
    private val testDeviceName = "Test Device"
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Mock FcmTokenManager singleton
        mockkObject(FcmTokenManager.Companion)
        mockFcmTokenManager = mockk(relaxed = true)
        every { FcmTokenManager.getInstance(any()) } returns mockFcmTokenManager
        
        // Mock ApiClient
        mockkObject(ApiClient)
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded returns true when already registered`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns true
        
        // When
        val result = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        assertTrue(result)
        coVerify(exactly = 0) { mockFcmTokenManager.getToken() }
        coVerify(exactly = 0) { ApiClient.registerDevice(any(), any(), any(), any()) }
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded returns false when token not available`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns null
        
        // When
        val result = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        assertFalse(result)
        coVerify(exactly = 0) { ApiClient.registerDevice(any(), any(), any(), any()) }
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded returns false when token is empty`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns ""
        
        // When
        val result = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        assertFalse(result)
        coVerify(exactly = 0) { ApiClient.registerDevice(any(), any(), any(), any()) }
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded successfully registers token`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.getDeviceName() } returns testDeviceName
        coEvery { 
            ApiClient.registerDevice(
                accessToken = testAccessToken,
                fcmToken = testFcmToken,
                deviceName = testDeviceName,
                platform = "android"
            )
        } returns MockApiClient.createDeviceRegistrationResponse()
        
        // When
        val result = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        assertTrue(result)
        verify { mockFcmTokenManager.markTokenRegistered() }
        coVerify {
            ApiClient.registerDevice(
                accessToken = testAccessToken,
                fcmToken = testFcmToken,
                deviceName = testDeviceName,
                platform = "android"
            )
        }
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded returns false on ApiException`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.getDeviceName() } returns testDeviceName
        val apiException = MockApiClient.createApiException(500, "Server error")
        coEvery { 
            ApiClient.registerDevice(any(), any(), any(), any())
        } throws apiException
        
        // When
        val result = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        assertFalse(result)
        verify(exactly = 0) { mockFcmTokenManager.markTokenRegistered() }
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded returns false on generic Exception`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.getDeviceName() } returns testDeviceName
        val networkException = Exception("Network error")
        coEvery { 
            ApiClient.registerDevice(any(), any(), any(), any())
        } throws networkException
        
        // When
        val result = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        assertFalse(result)
        verify(exactly = 0) { mockFcmTokenManager.markTokenRegistered() }
    }
    
    @Test
    fun `test registerFcmTokenIfNeeded uses correct device name`() = runTest {
        // Given
        val customDeviceName = "Custom Device Name"
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.getDeviceName() } returns customDeviceName
        coEvery { 
            ApiClient.registerDevice(any(), any(), any(), any())
        } returns MockApiClient.createDeviceRegistrationResponse()
        
        // When
        FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then
        coVerify {
            ApiClient.registerDevice(
                accessToken = testAccessToken,
                fcmToken = testFcmToken,
                deviceName = customDeviceName,
                platform = "android"
            )
        }
    }

    // Test 3: Registration Status Tracking → Should Not Retry If Already Registered
    @Test
    fun `test multiple calls when already registered should be idempotent`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns true // Already registered

        // When - call multiple times
        val result1 = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        val result2 = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        val result3 = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)

        // Then
        assertTrue("All calls should return true when already registered", result1)
        assertTrue("All calls should return true when already registered", result2)
        assertTrue("All calls should return true when already registered", result3)
        
        // Verify no API calls were made
        coVerify(exactly = 0) { mockFcmTokenManager.getToken() }
        coVerify(exactly = 0) { ApiClient.registerDevice(any(), any(), any(), any()) }
        verify(exactly = 0) { mockFcmTokenManager.markTokenRegistered() }
    }

    @Test
    fun `test registration status prevents unnecessary API calls`() = runTest {
        // Given
        every { mockFcmTokenManager.isTokenRegistered() } returns true

        // When
        FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)

        // Then
        // Should not call getToken or registerDevice
        coVerify(exactly = 0) { mockFcmTokenManager.getToken() }
        coVerify(exactly = 0) { ApiClient.registerDevice(any(), any(), any(), any()) }
        verify(exactly = 0) { mockFcmTokenManager.getDeviceName() }
    }

    @Test
    fun `test registration status changes after successful registration`() = runTest {
        // Given - not registered initially
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.getDeviceName() } returns testDeviceName
        coEvery {
            ApiClient.registerDevice(
                accessToken = testAccessToken,
                fcmToken = testFcmToken,
                deviceName = testDeviceName,
                platform = "android"
            )
        } returns MockApiClient.createDeviceRegistrationResponse()

        // When - first call
        val result1 = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)
        
        // Then - should succeed and mark as registered
        assertTrue("First call should succeed", result1)
        verify { mockFcmTokenManager.markTokenRegistered() }

        // When - second call (now registered)
        every { mockFcmTokenManager.isTokenRegistered() } returns true
        val result2 = FcmRegistrationHelper.registerFcmTokenIfNeeded(context, testAccessToken)

        // Then - should skip registration
        assertTrue("Second call should return true (already registered)", result2)
        coVerify(exactly = 1) { ApiClient.registerDevice(any(), any(), any(), any()) }
    }
}
