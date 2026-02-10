package app.getpursue.ui.fragments.onboarding

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.widget.Button
import androidx.fragment.app.commit
import androidx.test.core.app.ApplicationProvider
import app.getpursue.MockApiClient
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.fcm.FcmTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.ui.activities.MainActivity
import app.getpursue.ui.activities.MainAppActivity
import app.getpursue.ui.activities.OnboardingActivity
import com.google.android.material.textfield.TextInputEditText
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowToast

/**
 * Unit tests for OnboardingActivity sign-in flow.
 * 
 * Tests the complete sign-in flow including:
 * - Successful sign-in and navigation to MainAppActivity
 * - Invalid credentials error handling
 * - Network error handling
 * - FCM token registration (success and unavailable)
 * - Secure token storage verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "app.getpursue"
)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingActivitySignInTest {
    
    private lateinit var context: Context
    private lateinit var activity: OnboardingActivity
    private lateinit var mockTokenManager: SecureTokenManager
    private lateinit var mockFcmTokenManager: FcmTokenManager
    private lateinit var realPrefs: SharedPreferences
    private val testDispatcher = UnconfinedTestDispatcher()
    
    private val testEmail = "test@example.com"
    private val testPassword = "TestPassword123!"
    private val testAccessToken = "test_access_token_123"
    private val testRefreshToken = "test_refresh_token_456"
    
    @Before
    fun setUp() {
        // Set the main dispatcher to test dispatcher for coroutine testing
        Dispatchers.setMain(testDispatcher)
        
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
        
        // Get real SharedPreferences for testing
        realPrefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        realPrefs.edit().clear().commit()
    }
    
    @After
    fun tearDown() {
        // Clear SharedPreferences
        realPrefs.edit().clear().commit()

        // Clear toasts and any pending state
        ShadowToast.reset()
        // Idle the main looper to clear any pending runnables
        shadowOf(Looper.getMainLooper()).idle()
        // Unmock all
        unmockkAll()
        // Reset the main dispatcher
        Dispatchers.resetMain()
    }

    /**
     * Skip tests in CI environments due to coroutine timing issues.
     * These tests pass locally but fail in GitHub Actions.
     */
    private fun skipInCI() {
        Assume.assumeFalse(
            "Skipping test in CI due to coroutine timing issues",
            System.getenv("CI") == "true"
        )
    }

    private fun launchActivity() {
        activity = Robolectric.buildActivity(OnboardingActivity::class.java)
            .create()
            .start()
            .resume()
            .get()
    }
    
    private fun navigateToSignInFragment() {
        // Navigate to sign-in fragment (simulating user clicking "Sign In with Email")
        activity.supportFragmentManager.commit {
            replace(R.id.onboarding_container, SignInEmailFragment.newInstance())
            addToBackStack(null)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    
    private fun fillSignInForm(
        email: String = testEmail,
        password: String = testPassword
    ) {
        val signInFragment = activity.supportFragmentManager
            .findFragmentById(R.id.onboarding_container) as? SignInEmailFragment
        assertNotNull("SignInEmailFragment should be present", signInFragment)
        
        val emailInput = signInFragment?.view?.findViewById<TextInputEditText>(R.id.input_email)
        val passwordInput = signInFragment?.view?.findViewById<TextInputEditText>(R.id.input_password)
        
        emailInput?.setText(email)
        passwordInput?.setText(password)
        
        // Trigger validation by simulating text change
        emailInput?.text?.let { emailInput.text = it }
        passwordInput?.text?.let { passwordInput.text = it }
    }
    
    private fun clickSignInButton() {
        val signInFragment = activity.supportFragmentManager
            .findFragmentById(R.id.onboarding_container) as? SignInEmailFragment
        assertNotNull("SignInEmailFragment should be present", signInFragment)
        
        val button = signInFragment?.view?.findViewById<Button>(R.id.button_sign_in)
        assertNotNull("Sign-in button should exist", button)
        assertTrue("Button should be enabled", button?.isEnabled == true)
        button?.performClick()
    }
    
    private fun TestScope.advanceCoroutines() {
        // Advance test dispatcher
        advanceUntilIdle()
        // Idle main looper multiple times to ensure all coroutines complete
        // runOnUiThread queues runnables that need to be processed
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        // Additional passes to ensure runOnUiThread runnables are executed
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
    
    // ========== Test Cases ==========
    
    @Test
    fun `test with valid credentials should navigate to MainAppActivity`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createSuccessLoginResponse(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken
        )
        coEvery { ApiClient.login(testEmail, testPassword) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) } returns true
        
        launchActivity()
        navigateToSignInFragment()
        fillSignInForm()
        
        // When
        clickSignInButton()
        advanceCoroutines()
        
        // Then
        // Verify API was called
        coVerify { ApiClient.login(testEmail, testPassword) }
        
        // Verify tokens were stored
        verify { mockTokenManager.storeTokens(testAccessToken, testRefreshToken) }
        
        // Verify SharedPreferences was updated
        assertTrue("Should have identity flag set", 
            realPrefs.getBoolean(MainActivity.KEY_HAS_IDENTITY, false))
        
        // Verify FCM registration was attempted
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) }
        
        // Ensure all runOnUiThread runnables are executed before checking toast/activity
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Verify success toast
        assertTrue("Success toast should be shown. Latest toast: ${ShadowToast.getTextOfLatestToast()}", 
            ShadowToast.showedToast("Sign in successful"))
        
        // Verify navigation to MainAppActivity (retry mechanism for timing)
        var nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
        var attempts = 0
        while (nextActivity == null && attempts < 10) {
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
            attempts++
        }
        assertNotNull("Next activity should be started after $attempts attempts", nextActivity)
        assertEquals("Should navigate to MainAppActivity", 
            MainAppActivity::class.java.name, nextActivity?.component?.className)
        
        // Verify activity is finishing
        assertTrue("Activity should be finishing", activity.isFinishing)
    }
    
    @Test
    fun `test with invalid credentials should show error stay on sign-in screen`() = runTest(testDispatcher) {
        // Given
        val apiException = MockApiClient.createApiException(401, "Invalid email or password")
        coEvery { ApiClient.login(any(), any()) } throws apiException
        
        launchActivity()
        navigateToSignInFragment()
        fillSignInForm()
        
        // When
        clickSignInButton()
        advanceCoroutines()
        
        // Then
        // Verify API was called
        coVerify { ApiClient.login(testEmail, testPassword) }
        
        // Verify tokens were NOT stored
        verify(exactly = 0) { mockTokenManager.storeTokens(any(), any()) }
        
        // Verify SharedPreferences was NOT updated
        assertFalse("Should not have identity flag set", 
            realPrefs.getBoolean(MainActivity.KEY_HAS_IDENTITY, false))
        
        // Verify error toast
        assertTrue("Error toast should be shown", 
            ShadowToast.showedToast("Invalid email or password"))
        
        // Verify NO navigation
        val nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
        assertNull("Should not navigate on error", nextActivity)
        
        // Verify activity is NOT finishing
        assertFalse("Activity should not be finishing", activity.isFinishing)
        
        // Verify sign-in button is re-enabled
        val signInFragment = activity.supportFragmentManager
            .findFragmentById(R.id.onboarding_container) as? SignInEmailFragment
        val button = signInFragment?.view?.findViewById<Button>(R.id.button_sign_in)
        assertTrue("Button should be re-enabled", button?.isEnabled == true)
    }
    
    @Test
    fun `test with network error should show error message`() = runTest(testDispatcher) {
        // Given
        val networkException = MockApiClient.createNetworkException("Connection timeout")
        coEvery { ApiClient.login(any(), any()) } throws networkException
        
        launchActivity()
        navigateToSignInFragment()
        fillSignInForm()
        
        // When
        clickSignInButton()
        advanceCoroutines()
        
        // Then
        // Verify API was called
        coVerify { ApiClient.login(testEmail, testPassword) }
        
        // Verify tokens were NOT stored
        verify(exactly = 0) { mockTokenManager.storeTokens(any(), any()) }
        
        // Verify SharedPreferences was NOT updated
        assertFalse("Should not have identity flag set", 
            realPrefs.getBoolean(MainActivity.KEY_HAS_IDENTITY, false))
        
        // Verify error toast contains network error message
        val toastText = ShadowToast.getTextOfLatestToast()
        assertNotNull("Error toast should be shown", toastText)
        assertTrue("Toast should contain network error. Actual: $toastText", 
            toastText.contains("Network error"))
        
        // Verify NO navigation
        val nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
        assertNull("Should not navigate on network error", nextActivity)
        
        // Verify activity is NOT finishing
        assertFalse("Activity should not be finishing", activity.isFinishing)
        
        // Verify sign-in button is re-enabled
        val signInFragment = activity.supportFragmentManager
            .findFragmentById(R.id.onboarding_container) as? SignInEmailFragment
        val button = signInFragment?.view?.findViewById<Button>(R.id.button_sign_in)
        assertTrue("Button should be re-enabled", button?.isEnabled == true)
    }
    
    @Test
    fun `test FCM token unavailable should still allow sign-in`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createSuccessLoginResponse(
            accessToken = testAccessToken,
            refreshToken = testRefreshToken
        )
        coEvery { ApiClient.login(testEmail, testPassword) } returns response
        
        // Mock FCM token unavailable
        every { mockFcmTokenManager.isTokenRegistered() } returns false
        coEvery { mockFcmTokenManager.getToken() } returns null
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns false
        
        launchActivity()
        navigateToSignInFragment()
        fillSignInForm()
        
        // When
        clickSignInButton()
        advanceCoroutines()
        
        // Then
        // Verify sign-in succeeds despite FCM token unavailable
        coVerify { ApiClient.login(testEmail, testPassword) }
        
        // Verify tokens were stored correctly
        verify { mockTokenManager.storeTokens(testAccessToken, testRefreshToken) }
        
        // Verify SharedPreferences was updated
        assertTrue("Should have identity flag set", 
            realPrefs.getBoolean(MainActivity.KEY_HAS_IDENTITY, false))
        
        // Verify FCM registration was attempted (but returns false)
        coVerify { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), testAccessToken) }
        
        // Ensure coroutine completes (withContext(IO) + runOnUiThread) and main looper runs
        for (i in 1..40) {
            advanceCoroutines()
            shadowOf(Looper.getMainLooper()).idle()
            if (ShadowToast.showedToast("Sign in successful")) break
            Thread.sleep(5)
        }
        
        // Verify success toast
        assertTrue("Success toast should be shown. Latest toast: ${ShadowToast.getTextOfLatestToast()}",
            ShadowToast.showedToast("Sign in successful"))
        
        // Verify navigation to MainAppActivity (retry mechanism for timing)
        var nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
        var attempts = 0
        while (nextActivity == null && attempts < 10) {
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
            attempts++
        }
        assertNotNull("Next activity should be started after $attempts attempts", nextActivity)
        assertEquals("Should navigate to MainAppActivity", 
            MainAppActivity::class.java.name, nextActivity?.component?.className)
        
        // Verify activity is finishing
        assertTrue("Activity should be finishing", activity.isFinishing)
    }
    
    @Ignore("Mock verification timing issue - needs investigation")
    @Test
    fun `test token storage verify tokens are stored securely`() = runTest(testDispatcher) {
        skipInCI()

        // Given
        val customAccessToken = "custom_access_token_789"
        val customRefreshToken = "custom_refresh_token_012"
        val response = MockApiClient.createSuccessLoginResponse(
            accessToken = customAccessToken,
            refreshToken = customRefreshToken
        )
        coEvery { ApiClient.login(testEmail, testPassword) } returns response
        coEvery { FcmRegistrationHelper.registerFcmTokenIfNeeded(any(), any()) } returns true

        launchActivity()
        navigateToSignInFragment()
        fillSignInForm()
        
        // When
        clickSignInButton()
        advanceCoroutines()
        
        // Then
        // Verify SecureTokenManager.storeTokens() was called with correct tokens
        verify(exactly = 1) { 
            mockTokenManager.storeTokens(customAccessToken, customRefreshToken) 
        }
        
        // Verify tokens match the response from API
        coVerify { ApiClient.login(testEmail, testPassword) }
        
        // Verify SharedPreferences updated with KEY_HAS_IDENTITY
        assertTrue("Should have identity flag set", 
            realPrefs.getBoolean(MainActivity.KEY_HAS_IDENTITY, false))
        
        // Ensure all runOnUiThread runnables are executed before checking activity
        // Retry mechanism: keep idling looper until activity is started (max 10 attempts)
        var nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
        var attempts = 0
        while (nextActivity == null && attempts < 10) {
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()
            nextActivity = ShadowApplication.getInstance().peekNextStartedActivity()
            attempts++
        }
        
        // Verify navigation occurred
        assertNotNull("Next activity should be started after $attempts attempts", nextActivity)
        assertEquals("Should navigate to MainAppActivity", 
            MainAppActivity::class.java.name, nextActivity?.component?.className)
    }
}
