package app.getpursue.data.fcm

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/**
 * Unit tests for FcmTokenManager.
 *
 * Tests FCM token retrieval, caching, and registration status tracking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FcmTokenManagerTest {

    private lateinit var context: Context
    private lateinit var fcmTokenManager: FcmTokenManager
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Get preferences instance using applicationContext (same as FcmTokenManager)
        // FcmTokenManager.getInstance() uses context.applicationContext, so we need to match that
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("fcm_token_prefs", Context.MODE_PRIVATE)
        
        // Clear preferences before each test (this ensures fresh state)
        // Use commit() for synchronous behavior in tests
        prefs.edit().clear().commit()
        
        // Also ensure the token key is explicitly removed
        prefs.edit().remove("fcm_token").commit()
        
        // Get instance (singleton is fine since we cleared preferences)
        // Use applicationContext to match what FcmTokenManager uses internally
        fcmTokenManager = FcmTokenManager.getInstance(appContext)
        
        // Verify cache is actually empty
        val cachedAfterSetup = prefs.getString("fcm_token", null)
        if (cachedAfterSetup != null) {
            // Force clear if still present
            prefs.edit().clear().commit()
        }
    }

    @After
    fun tearDown() {
        // Clear preferences after each test
        prefs.edit().clear().commit() // Use commit() for synchronous behavior in tests
        
        // Reset singleton instance to ensure clean state between tests
        // This is important because the singleton might retain state from previous tests
        try {
            // The INSTANCE backing field is on FcmTokenManager class itself, not on the Companion object
            val field = FcmTokenManager::class.java.getDeclaredField("INSTANCE")
            field.isAccessible = true
            field.set(null, null)
        } catch (e: Exception) {
            // Ignore if reflection fails
        }
    }

    @Test
    fun `test getInstance returns singleton`() {
        // Given
        val instance1 = FcmTokenManager.getInstance(context)
        val instance2 = FcmTokenManager.getInstance(context)
        
        // Then
        assertSame("Should return same instance", instance1, instance2)
    }

    @Test
    fun `test getToken returns cached token`() = runTest {
        // Given
        val testToken = "cached_token_123"
        
        // Write directly to SharedPreferences using commit() for synchronous behavior
        // Use the same SharedPreferences instance that fcmTokenManager uses (set in setUp)
        prefs.edit().putString("fcm_token", testToken).commit()
        
        // Verify token is actually cached
        val cachedDirectly = prefs.getString("fcm_token", null)
        assertEquals("Token should be cached in SharedPreferences", testToken, cachedDirectly)
        
        // When - getToken() checks cache first, then tries Firebase (which will fail in Robolectric)
        // fcmTokenManager is already set up in setUp() and uses the same SharedPreferences as prefs
        val result = fcmTokenManager.getToken()
        
        // Then
        assertEquals("getToken() should return cached token", testToken, result)
    }

    @Test
    fun `test getToken returns null when not cached and Firebase fails`() = runTest {
        // Given - ensure no cached token exists
        // Clear preferences explicitly (setUp already did this, but be extra sure)
        // Use the actual key name from FcmTokenManager
        prefs.edit().remove("fcm_token").commit()
        
        // Verify cache is actually empty by reading directly from SharedPreferences
        val cachedBefore = prefs.getString("fcm_token", null)
        assertNull("Cache should be empty before test. Found: $cachedBefore", cachedBefore)
        
        // Also verify through the manager's internal prefs
        // Force a fresh read by creating a new instance if needed
        val freshManager = FcmTokenManager.getInstance(context)
        
        // When - Firebase not available in Robolectric, so this will fail
        // getToken() checks cache first, then tries Firebase
        val result = freshManager.getToken()
        
        // Then
        assertNull("Should return null when Firebase fails", result)
    }

    @Test
    fun `test cacheToken stores token`() = runTest {
        // Given
        val testToken = "new_token_456"
        
        // When
        fcmTokenManager.cacheToken(testToken)
        
        // Flush main looper to ensure apply() completes
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle() // Double flush to be sure
        
        // Then - verify through public API (getToken reads from cache)
        val cachedToken = fcmTokenManager.getToken()
        assertEquals("Token should be cached and retrievable", testToken, cachedToken)
    }

    @Test
    fun `test isTokenRegistered returns false initially`() {
        // When
        val result = fcmTokenManager.isTokenRegistered()
        
        // Then
        assertFalse("Should return false initially", result)
    }

    @Test
    fun `test isTokenRegistered returns true after marking as registered`() {
        // Given
        fcmTokenManager.markTokenRegistered()
        
        // When
        val result = fcmTokenManager.isTokenRegistered()
        
        // Then
        assertTrue("Should return true after marking as registered", result)
    }

    @Test
    fun `test markTokenRegistered sets registration status`() {
        // When
        fcmTokenManager.markTokenRegistered()
        
        // Flush looper multiple times to ensure apply() completes
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then - use the manager's method to check, which reads from the same SharedPreferences
        val isRegistered = fcmTokenManager.isTokenRegistered()
        assertTrue("Registration status should be true", isRegistered)
        
        // Also verify directly from SharedPreferences
        val prefsRegistered = prefs.getBoolean("fcm_token_registered", false)
        assertTrue("Registration status should be true in SharedPreferences", prefsRegistered)
    }

    @Test
    fun `test markTokenUnregistered clears registration status`() {
        // Given
        fcmTokenManager.markTokenRegistered()
        
        // When
        fcmTokenManager.markTokenUnregistered()
        
        // Then
        val isRegistered = prefs.getBoolean("fcm_token_registered", true)
        assertFalse("Registration status should be false", isRegistered)
    }

    @Test
    fun `test clearToken removes token and registration status`() {
        // Given
        fcmTokenManager.cacheToken("test_token")
        fcmTokenManager.markTokenRegistered()
        
        // Flush looper to ensure cacheToken and markTokenRegistered apply() complete
        shadowOf(Looper.getMainLooper()).idle()
        
        // When
        fcmTokenManager.clearToken()
        
        // Flush looper to ensure clearToken apply() completes
        shadowOf(Looper.getMainLooper()).idle()
        
        // Then
        val token = prefs.getString("fcm_token", null)
        val isRegistered = prefs.getBoolean("fcm_token_registered", false) // Default to false, not true
        
        assertNull("Token should be cleared", token)
        assertFalse("Registration status should be cleared", isRegistered)
    }

    @Test
    fun `test getDeviceName returns formatted device name`() {
        // When
        val deviceName = fcmTokenManager.getDeviceName()
        
        // Then
        assertNotNull("Device name should not be null", deviceName)
        assertTrue("Device name should not be empty", deviceName.isNotEmpty())
    }

    @Test
    fun `test registration status persists across instances`() {
        // Given
        fcmTokenManager.markTokenRegistered()
        
        // When - get new instance
        val newInstance = FcmTokenManager.getInstance(context)
        val isRegistered = newInstance.isTokenRegistered()
        
        // Then
        assertTrue("Registration status should persist", isRegistered)
    }
}
