package app.getpursue.ui.fragments.onboarding

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.widget.Button
import android.widget.Toast
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputEditText
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import app.getpursue.MockApiClient
import com.github.shannonbay.pursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.fcm.FcmTokenManager
import app.getpursue.data.network.ApiClient

/**
 * Unit tests for SignUpEmailFragment.
 * 
 * Tests the complete create account flow including:
 * - Successful registration and navigation
 * - FCM token registration (success and failure)
 * - Network error handling
 * - Token storage verification
 * - Toast message verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "com.github.shannonbay.pursue"
)
@OptIn(ExperimentalCoroutinesApi::class)
class SignUpEmailFragmentTest {
    
    private lateinit var context: Context
    private lateinit var fragment: SignUpEmailFragment
    private lateinit var activity: FragmentActivity
    private lateinit var mockCallbacks: SignUpEmailFragment.Callbacks
    private lateinit var mockTokenManager: SecureTokenManager
    private lateinit var mockFcmTokenManager: FcmTokenManager
    
    private val testDisplayName = "Test User"
    private val testEmail = "test@example.com"
    private val testPassword = "TestPassword123!"
    private val testAccessToken = "test_access_token_123"
    private val testRefreshToken = "test_refresh_token_456"
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Mock SecureTokenManager
        mockkObject(SecureTokenManager.Companion)
        mockTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.getInstance(any()) } returns mockTokenManager
        
        // Mock FcmTokenManager
        mockkObject(FcmTokenManager.Companion)
        mockFcmTokenManager = mockk(relaxed = true)
        every { FcmTokenManager.getInstance(any()) } returns mockFcmTokenManager
        
        // Mock FcmRegistrationHelper
        mockkObject(FcmRegistrationHelper)
        
        // Mock ApiClient
        mockkObject(ApiClient)
        
        // Mock SharedPreferences
        val mockPrefs = mockk<SharedPreferences>(relaxed = true)
        val mockEditor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.apply() } returns Unit
        
        // Setup mock callbacks
        mockCallbacks = mockk(relaxed = true)
    }
    
    @After
    fun tearDown() {
        unmockkAll()
    }
    
    private fun launchFragment() {
        // Create a test activity to host the fragment
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
        
        fragment = SignUpEmailFragment.newInstance()
        
        // Add fragment to activity - this will trigger onAttach, onCreate, etc.
        // onAttach will try to get callbacks from context, which will fail, setting it to null
        activity.supportFragmentManager.beginTransaction()
            .add(fragment, "test")
            .commitNow()
        
        // Set callbacks using reflection AFTER onAttach is called
        // This ensures our mock callbacks are used instead of the null from onAttach
        try {
            val field = SignUpEmailFragment::class.java.getDeclaredField("callbacks")
            field.isAccessible = true
            field.set(fragment, mockCallbacks)
            
            // Verify callbacks were set correctly
            val callbacksValue = field.get(fragment) as? SignUpEmailFragment.Callbacks
            assertNotNull("Callbacks should be set", callbacksValue)
        } catch (e: Exception) {
            // Reflection failed - tests may need adjustment
            throw AssertionError("Failed to set callbacks via reflection: ${e.message}", e)
        }
        
        // Ensure fragment is in resumed state so lifecycleScope is active
        activity.supportFragmentManager.beginTransaction()
            .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            .commitNow()
    }
    
    private fun fillForm(
        displayName: String = testDisplayName,
        email: String = testEmail,
        password: String = testPassword,
        confirmPassword: String = testPassword
    ) {
        val displayNameInput = fragment.view?.findViewById<TextInputEditText>(R.id.input_display_name)
        val emailInput = fragment.view?.findViewById<TextInputEditText>(R.id.input_email)
        val passwordInput = fragment.view?.findViewById<TextInputEditText>(R.id.input_password)
        val confirmPasswordInput = fragment.view?.findViewById<TextInputEditText>(R.id.input_confirm_password)
        
        displayNameInput?.setText(displayName)
        emailInput?.setText(email)
        passwordInput?.setText(password)
        confirmPasswordInput?.setText(confirmPassword)
    }
    
    private fun clickCreateAccountButton() {
        // Ensure fragment view is created and button is available
        val button = fragment.view?.findViewById<Button>(R.id.button_create_account)
        assertNotNull("Create account button should exist", button)
        assertTrue("Button should be enabled", button?.isEnabled == true)
        button?.performClick()
    }
    
    // Test 1: Successful Registration Flow
    @Test
    fun `test successful registration navigates to home screen`() = runTest {
        // Given
        val response = MockApiClient.createSuccessRegistrationResponse(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken
        )
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        verify { mockTokenManager.storeTokens(testAccessToken, testRefreshToken) }
        verify { mockCallbacks.onSignUp(testDisplayName, testEmail, testPassword) }
        
        // Verify toast is shown
        val toast = ShadowToast.getLatestToast()
        assertNotNull("Toast should be shown", toast)
        assertEquals("Account created!", ShadowToast.getTextOfLatestToast())
        assertTrue("Toast should be shown", ShadowToast.showedToast("Account created!"))
        assertEquals(Toast.LENGTH_SHORT, toast?.duration)
    }
    
    // Test 2: FCM Token Registration Success
    @Test
    fun `test FCM token registration success`() = runTest {
        // Given
        val response = MockApiClient.createSuccessRegistrationResponse(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken
        )
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) } returns true
        
        launchFragment()
        fillForm()
        
        // Verify callbacks are set before clicking button
        try {
            val field = SignUpEmailFragment::class.java.getDeclaredField("callbacks")
            field.isAccessible = true
            val callbacksBefore = field.get(fragment) as? SignUpEmailFragment.Callbacks
            assertNotNull("Callbacks should be set before clicking button", callbacksBefore)
        } catch (e: Exception) {
            // Ignore reflection errors
        }
        
        // When
        clickCreateAccountButton()
        
        // Wait for coroutine to complete
        // The coroutine uses Dispatchers.IO and lifecycleScope (main dispatcher)
        // So we need to advance both the test dispatcher and the main looper multiple times
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Verify the coroutine ran by checking if toast was shown
        // This confirms the coroutine completed successfully
        val toast = ShadowToast.getLatestToast()
        assertNotNull("Toast should be shown if coroutine completed", toast)
        
        // Verify callbacks are still set after coroutine
        try {
            val field = SignUpEmailFragment::class.java.getDeclaredField("callbacks")
            field.isAccessible = true
            val callbacksAfter = field.get(fragment) as? SignUpEmailFragment.Callbacks
            assertNotNull("Callbacks should still be set after coroutine", callbacksAfter)
        } catch (e: Exception) {
            // Ignore reflection errors
        }
        
        // Then
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) }
        verify { mockCallbacks.onSignUp(testDisplayName, testEmail, testPassword) }
    }
    
    // Test 3: FCM Token Registration Failure (Should Still Navigate)
    @Test
    fun `test FCM token registration failure still navigates`() = runTest {
        // Given
        val response = MockApiClient.createSuccessRegistrationResponse(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken
        )
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } throws Exception("FCM registration failed")
        // Mock getMyGroups to prevent HomeFragment from showing error toast when MainAppActivity starts
        coEvery { ApiClient.getMyGroups(any()) } returns MockApiClient.createGroupsResponse(emptyList())
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        // Additional passes to ensure all coroutines and UI updates complete
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then - Registration should still succeed and navigate
        verify { mockTokenManager.storeTokens(testAccessToken, testRefreshToken) }
        verify { mockCallbacks.onSignUp(testDisplayName, testEmail, testPassword) }
        
        // Verify success toast was shown (use showedToast to check if it appeared, not just latest)
        assertTrue("Success toast should be shown", ShadowToast.showedToast("Account created!"))
    }
    
    // Test 4: Network Errors During Registration
    @Test
    fun `test network error 400 shows appropriate error message`() = runTest {
        // Given
        val apiException = MockApiClient.createBadRequestException("Invalid registration data")
        coEvery { ApiClient.register(any(), any(), any()) } throws apiException
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        verify(exactly = 0) { mockCallbacks.onSignUp(any(), any(), any()) }
        val toast = ShadowToast.getLatestToast()
        assertNotNull("Error toast should be shown", toast)
        assertTrue("Toast should contain error message", 
            ShadowToast.getTextOfLatestToast().contains("Invalid registration data"))
        
        // Verify button is re-enabled
        val button = fragment.view?.findViewById<Button>(R.id.button_create_account)
        assertTrue("Button should be enabled", button?.isEnabled == true)
    }
    
    @Test
    fun `test network error 409 shows email exists message`() = runTest {
        // Given
        val apiException = MockApiClient.createConflictException("An account with this email already exists")
        coEvery { ApiClient.register(any(), any(), any()) } throws apiException
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        verify(exactly = 0) { mockCallbacks.onSignUp(any(), any(), any()) }
        assertTrue("Toast should contain conflict message",
            ShadowToast.getTextOfLatestToast().contains("An account with this email already exists"))
    }
    
    @Test
    fun `test network error 500 shows server error message`() = runTest {
        // Given
        val apiException = MockApiClient.createServerErrorException("Internal server error")
        coEvery { ApiClient.register(any(), any(), any()) } throws apiException
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        verify(exactly = 0) { mockCallbacks.onSignUp(any(), any(), any()) }
        val toastText = ShadowToast.getTextOfLatestToast()
        assertTrue("Toast should contain error message. Actual: $toastText",
            toastText.contains("Internal server error") || toastText.contains("Registration failed"))
    }
    
    @Test
    fun `test generic network exception shows network error message`() = runTest {
        // Given
        val networkException = MockApiClient.createNetworkException("Network error: Connection timeout")
        coEvery { ApiClient.register(any(), any(), any()) } throws networkException
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        verify(exactly = 0) { mockCallbacks.onSignUp(any(), any(), any()) }
        assertTrue("Toast should contain network error",
            ShadowToast.getTextOfLatestToast().contains("Network error"))
        
        // Verify button is re-enabled
        val button = fragment.view?.findViewById<Button>(R.id.button_create_account)
        assertTrue("Button should be enabled", button?.isEnabled == true)
    }
    
    // Test 5: Verify Tokens Stored in Android Keystore
    @Test
    fun `test tokens are stored correctly in SecureTokenManager`() = runTest {
        // Given
        val response = MockApiClient.createSuccessRegistrationResponse(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken
        )
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then - verify tokens were stored exactly once with the correct values
        verify(exactly = 1) { 
            mockTokenManager.storeTokens(testAccessToken, testRefreshToken) 
        }
    }
    
    @Test
    fun `test tokens match API response`() = runTest {
        // Given
        val customAccessToken = "custom_access_token_789"
        val customRefreshToken = "custom_refresh_token_012"
        val response = MockApiClient.createSuccessRegistrationResponse(
            accessToken = customAccessToken,
            refreshToken = customRefreshToken
        )
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        verify { mockTokenManager.storeTokens(customAccessToken, customRefreshToken) }
    }
    
    // Test 6: Verify Success Toast Appears
    @Test
    fun `test success toast appears with correct message`() = runTest {
        // Given
        val response = MockApiClient.createSuccessRegistrationResponse()
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true
        // Mock getMyGroups to prevent HomeFragment from showing error toast when MainAppActivity starts
        coEvery { ApiClient.getMyGroups(any()) } returns MockApiClient.createGroupsResponse(emptyList())
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        // Additional passes to ensure all coroutines and UI updates complete
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        // Verify success toast was shown (use showedToast to check if it appeared, not just latest)
        assertTrue("Success toast should be shown", ShadowToast.showedToast("Account created!"))
        val toast = ShadowToast.getLatestToast()
        assertNotNull("Toast should be shown", toast)
    }
    
    @Test
    fun `test success toast has correct duration`() = runTest {
        // Given
        val response = MockApiClient.createSuccessRegistrationResponse()
        coEvery { ApiClient.register(any(), any(), any()) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true
        
        launchFragment()
        fillForm()
        
        // When
        clickCreateAccountButton()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        val toast = ShadowToast.getLatestToast()
        assertNotNull("Toast should be shown", toast)
        assertEquals(Toast.LENGTH_SHORT, toast?.duration)
    }
}
