package app.getpursue.ui.activities

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.getpursue.MockApiClient
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.fcm.FcmTokenManager
import app.getpursue.data.network.ApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

/**
 * Unit tests for MainAppActivity FCM token management.
 *
 * Tests FCM retry logic including:
 * - App startup retry
 * - Network connectivity callback handling
 * - Multiple retry attempts (idempotent)
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "com.github.shannonbay.pursue"
)
@OptIn(ExperimentalCoroutinesApi::class)
class MainAppActivityFcmTest {

    private lateinit var context: Context
    private lateinit var mockSecureTokenManager: SecureTokenManager
    private lateinit var mockFcmTokenManager: FcmTokenManager
    private lateinit var mockConnectivityManager: ConnectivityManager
    private var capturedNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val testAccessToken = "test_access_token_123"
    private val testFcmToken = "test_fcm_token_456"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Mock SecureTokenManager
        mockkObject(SecureTokenManager.Companion)
        mockSecureTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.Companion.getInstance(any()) } returns mockSecureTokenManager

        // Mock FcmTokenManager
        mockkObject(FcmTokenManager.Companion)
        mockFcmTokenManager = mockk(relaxed = true)
        every { FcmTokenManager.Companion.getInstance(any()) } returns mockFcmTokenManager

        // Mock FcmRegistrationHelper
        mockkObject(FcmRegistrationHelper)

        // Mock ApiClient to prevent real network calls
        mockkObject(ApiClient)

        // Mock ConnectivityManager and set it up in ShadowApplication
        mockConnectivityManager = mockk(relaxed = true)
        every {
            mockConnectivityManager.registerNetworkCallback(
                any<NetworkRequest>(),
                any<ConnectivityManager.NetworkCallback>()
            )
        } answers {
            capturedNetworkCallback = secondArg() as ConnectivityManager.NetworkCallback
        }

        // Set up system service before activity creation
        ShadowApplication.getInstance().setSystemService(
            Context.CONNECTIVITY_SERVICE,
            mockConnectivityManager
        )
    }

    @After
    fun tearDown() {
        // Clear ShadowApplication system services to prevent test interference
        try {
            val shadowApp = ShadowApplication.getInstance()
            // Remove the ConnectivityManager service we added
            shadowApp.setSystemService(Context.CONNECTIVITY_SERVICE, null)
        } catch (e: Exception) {
            // Ignore if ShadowApplication is not available
        }
        unmockkAll()
        capturedNetworkCallback = null
    }

    // Test 1: FCM Token Retrieval Failure → Should Retry on App Startup
    @Test
    fun `test FCM token retrieval failure should retry on app startup`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { mockFcmTokenManager.getToken() } returns null // Token retrieval fails
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns false

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for coroutines to complete
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify retry was attempted even though token retrieval failed
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) }
    }

    // Test 2: FCM Registration Failure → Should Retry on Network Restore
    @Test
    fun `test FCM registration failure should retry on network restore`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        every { mockFcmTokenManager.getDeviceName() } returns "Test Device"

        // First registration attempt fails
        var registrationAttempts = 0
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } answers {
            registrationAttempts++
            if (registrationAttempts == 1) {
                false // First attempt fails
            } else {
                true // Retry succeeds
            }
        }

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for initial startup retry
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // When - simulate network restore
        val mockNetwork = mockk<Network>(relaxed = true)
        capturedNetworkCallback?.onAvailable(mockNetwork)

        // Wait for network callback retry
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify retry was called when network restored
        Assert.assertEquals("Should have attempted registration twice", 2, registrationAttempts)
        coVerify(atLeast = 2) {
            FcmRegistrationHelper.registerFcmTokenIfNeeded(
                any(),
                testAccessToken
            )
        }
    }

    // Test 3: Registration Status Tracking → Should Not Retry If Already Registered
    @Test
    fun `test should not retry if already registered`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        every { mockFcmTokenManager.isTokenRegistered() } returns true // Already registered

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for startup
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // When - simulate network restore
        val mockNetwork = mockk<Network>(relaxed = true)
        capturedNetworkCallback?.onAvailable(mockNetwork)

        // Wait for network callback
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify registration helper was called but should skip registration
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) }
        // Verify that getToken was never called (because already registered)
        coVerify(exactly = 0) { mockFcmTokenManager.getToken() }
    }

    // Test 5: Network Connectivity Changes → Should Trigger Retry
    @Test
    fun `test network connectivity changes should trigger retry`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for startup
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // When - simulate network capabilities change with valid network
        val mockNetwork = mockk<Network>(relaxed = true)
        val mockCapabilities = mockk<NetworkCapabilities>(relaxed = true)
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true

        capturedNetworkCallback?.onCapabilitiesChanged(mockNetwork, mockCapabilities)

        // Wait for network callback
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify retry was triggered
        coVerify(atLeast = 2) {
            FcmRegistrationHelper.registerFcmTokenIfNeeded(
                any(),
                testAccessToken
            )
        }
    }

    @Test
    fun `test network capabilities change without internet should not trigger retry`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for startup
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // When - simulate network capabilities change without internet
        val mockNetwork = mockk<Network>(relaxed = true)
        val mockCapabilities = mockk<NetworkCapabilities>(relaxed = true)
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns false
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        capturedNetworkCallback?.onCapabilitiesChanged(mockNetwork, mockCapabilities)

        // Wait for network callback
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify retry was NOT triggered (only startup retry should have happened)
        coVerify(exactly = 1) {
            FcmRegistrationHelper.registerFcmTokenIfNeeded(
                any(),
                testAccessToken
            )
        }
    }

    // Test 6: Multiple Retry Attempts → Should Be Safe (Idempotent)
    @Test
    fun `test multiple retry attempts should be idempotent`() = runTest {
        // Given
        // Unmock FcmRegistrationHelper for this test so we can test the real implementation
        // with mocked dependencies (ApiClient, FcmTokenManager)
        unmockkObject(FcmRegistrationHelper)

        every { mockSecureTokenManager.getAccessToken() } returns testAccessToken
        coEvery { mockFcmTokenManager.getToken() } returns testFcmToken
        every { mockFcmTokenManager.getDeviceName() } returns "Test Device"

        // Track registration status changes
        var isRegistered = false
        every { mockFcmTokenManager.isTokenRegistered() } answers { isRegistered }
        every { mockFcmTokenManager.markTokenRegistered() } answers { isRegistered = true }

        // First call succeeds, subsequent calls should skip
        coEvery {
            ApiClient.registerDevice(any(), any(), any(), any())
        } returns MockApiClient.createDeviceRegistrationResponse()

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for startup retry
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // When - trigger multiple network events
        val mockNetwork = mockk<Network>(relaxed = true)
        capturedNetworkCallback?.onAvailable(mockNetwork)

        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val mockCapabilities = mockk<NetworkCapabilities>(relaxed = true)
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { mockCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        capturedNetworkCallback?.onCapabilitiesChanged(mockNetwork, mockCapabilities)

        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify registration only happened once (idempotent)
        coVerify(exactly = 1) {
            ApiClient.registerDevice(
                accessToken = testAccessToken,
                fcmToken = testFcmToken,
                deviceName = "Test Device",
                platform = "android"
            )
        }
        verify(exactly = 1) { mockFcmTokenManager.markTokenRegistered() }
    }

    @Test
    fun `test retry when no access token should not crash`() = runTest {
        // Given
        every { mockSecureTokenManager.getAccessToken() } returns null // No access token

        // Create activity (ConnectivityManager is already mocked via ShadowApplication)
        val activity = Robolectric.setupActivity(MainAppActivity::class.java)

        // Wait for startup
        advanceUntilIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Then - verify no registration attempt was made
        coVerify(exactly = 0) { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) }
    }
}