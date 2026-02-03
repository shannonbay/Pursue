package com.github.shannonbay.pursue.ui.fragments.home

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ApplicationProvider
import com.github.shannonbay.pursue.MockApiClient
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.data.network.ApiClient
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * Unit tests for MyProgressFragment (My Progress screen).
 *
 * Tests the loading state which verifies the skeleton UI is shown during data loading.
 * Note: Other tests are skipped due to coroutine dispatcher issues with the test framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "com.github.shannonbay.pursue"
)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class MyProgressFragmentTest {

    private lateinit var context: Context
    private lateinit var fragment: MyProgressFragment
    private lateinit var activity: FragmentActivity
    private lateinit var mockTokenManager: SecureTokenManager
    private val testDispatcher = UnconfinedTestDispatcher()

    private val testAccessToken = "test_access_token_123"

    @Before
    fun setUp() {
        // Set the main dispatcher to test dispatcher for coroutine testing
        Dispatchers.setMain(testDispatcher)

        context = ApplicationProvider.getApplicationContext()

        // Mock SecureTokenManager
        mockkObject(SecureTokenManager.Companion)
        mockTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.getInstance(any()) } returns mockTokenManager
        every { mockTokenManager.getAccessToken() } returns testAccessToken

        // Mock ApiClient
        mockkObject(ApiClient)
    }

    @After
    fun tearDown() {
        // Clear toasts and any pending state
        ShadowToast.reset()

        // IMPORTANT: Complete all pending coroutines BEFORE resetting the dispatcher
        // This prevents "UncaughtExceptionsBeforeTest" in subsequent tests caused by
        // orphaned coroutines trying to update UI after the activity context is gone
        testDispatcher.scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        testDispatcher.scheduler.advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        // Unmock all
        unmockkAll()
        // Reset the main dispatcher
        Dispatchers.resetMain()
    }

    private fun launchFragment() {
        activity = Robolectric.setupActivity(FragmentActivity::class.java)

        fragment = MyProgressFragment.newInstance()

        activity.supportFragmentManager.beginTransaction()
            .add(fragment, "test")
            .commitNow()

        activity.supportFragmentManager.beginTransaction()
            .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            .commitNow()
    }

    private fun verifyLoadingState() {
        val skeletonContainer = fragment.view?.findViewById<LinearLayout>(R.id.skeleton_container)
        val contentContainer = fragment.view?.findViewById<LinearLayout>(R.id.content_container)
        val emptyContainer = fragment.view?.findViewById<FrameLayout>(R.id.empty_state_container)
        val errorContainer = fragment.view?.findViewById<FrameLayout>(R.id.error_state_container)

        assertEquals("Skeleton should be visible", View.VISIBLE, skeletonContainer?.visibility)
        assertEquals("Content should be hidden", View.GONE, contentContainer?.visibility)
        assertEquals("Empty container should be hidden", View.GONE, emptyContainer?.visibility)
        assertEquals("Error container should be hidden", View.GONE, errorContainer?.visibility)
    }

    // ========== Loading State Tests ==========

    @Test
    fun `test loading state shows skeleton`() = runTest(testDispatcher) {
        // Given - delay the API response so we can observe loading state
        coEvery { ApiClient.getMyProgress(any()) } coAnswers {
            delay(100)
            MockApiClient.createMyProgressResponse()
        }

        // When
        launchFragment()
        advanceUntilIdle()

        // Then
        verifyLoadingState()
    }

    @Test
    fun `test fragment creates view correctly`() = runTest(testDispatcher) {
        // Given - delay the API response so we can observe initial state
        coEvery { ApiClient.getMyProgress(any()) } coAnswers {
            delay(100)
            MockApiClient.createMyProgressResponse()
        }

        // When
        launchFragment()

        // Then - verify all views exist
        val skeletonContainer = fragment.view?.findViewById<LinearLayout>(R.id.skeleton_container)
        val contentContainer = fragment.view?.findViewById<LinearLayout>(R.id.content_container)
        val emptyContainer = fragment.view?.findViewById<FrameLayout>(R.id.empty_state_container)
        val errorContainer = fragment.view?.findViewById<FrameLayout>(R.id.error_state_container)
        val swipeRefresh = fragment.view?.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)

        assertNotNull("Skeleton container should exist", skeletonContainer)
        assertNotNull("Content container should exist", contentContainer)
        assertNotNull("Empty container should exist", emptyContainer)
        assertNotNull("Error container should exist", errorContainer)
        assertNotNull("SwipeRefreshLayout should exist", swipeRefresh)
    }

    @Test
    fun `test swipe refresh layout is configured`() = runTest(testDispatcher) {
        // Given
        coEvery { ApiClient.getMyProgress(any()) } coAnswers {
            delay(100)
            MockApiClient.createMyProgressResponse()
        }

        // When
        launchFragment()

        // Then - verify SwipeRefreshLayout has listener
        val swipeRefresh = fragment.view?.findViewById<SwipeRefreshLayout>(R.id.swipe_refresh)
        assertNotNull("SwipeRefreshLayout should exist", swipeRefresh)
        val listenerField = SwipeRefreshLayout::class.java.getDeclaredField("mListener")
        listenerField.isAccessible = true
        val listener = listenerField.get(swipeRefresh)
        assertNotNull("SwipeRefreshLayout should have a listener", listener)
    }
}
