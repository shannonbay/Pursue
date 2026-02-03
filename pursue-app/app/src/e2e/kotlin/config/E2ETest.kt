package com.github.shannonbay.pursue.e2e.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.data.network.RegistrationResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for E2E tests.
 *
 * Provides:
 * - Server availability check before running tests
 * - E2E API client (proxy over ApiClient) with localhost base URL
 * - Automatic cleanup of created test data
 * - Clear failure messages when ApiException is thrown (shows code + message)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
abstract class E2ETest {

    @get:Rule
    val apiExceptionReporter: TestRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement {
            return object : Statement() {
                override fun evaluate() {
                    try {
                        base.evaluate()
                    } catch (e: Throwable) {
                        var cause: Throwable? = e
                        while (cause != null) {
                            if (cause is ApiException) {
                                throw AssertionError(
                                    "API call failed: code=${cause.code}, message=${cause.message}",
                                    e
                                )
                            }
                            cause = cause.cause
                        }
                        throw e
                    }
                }
            }
        }
    }

    companion object {
        private var serverChecked = false
        private val sharedUserCache = mutableMapOf<Class<*>, RegistrationResponse>()

        @BeforeClass
        @JvmStatic
        fun checkServerOnce() {
            if (!serverChecked) {
                val available = LocalServerConfig.isServerAvailable()

                if (!available) {
                    println("=".repeat(60))
                    println("⚠️  LOCAL DEV SERVER NOT RUNNING")
                    println("   Start the backend server before running E2E tests:")
                    println("   \$ cd backend")
                    println("   \$ npm run dev")
                    println("   Server should be at: ${LocalServerConfig.BASE_URL}")
                    println("=".repeat(60))
                }

                assumeTrue(
                    "Local dev server must be running at ${LocalServerConfig.BASE_URL}",
                    available
                )

                serverChecked = true
            }
        }
    }

    protected lateinit var context: Context
    protected lateinit var api: E2EApiClient
    protected lateinit var testDataHelper: TestDataHelper

    // Track resources for cleanup
    private val createdUserIds = mutableListOf<String>()
    private val createdGroupIds = mutableListOf<String>()

    @Before
    fun setupApi() {
        context = ApplicationProvider.getApplicationContext<Context>()
        ApiClient.initialize(ApiClient.buildClient(context))
        ApiClient.setBaseUrlForE2E(LocalServerConfig.API_BASE_URL)
        api = E2EApiClient(context)
        testDataHelper = TestDataHelper(context)
    }

    @After
    fun cleanup() {
        runBlocking {
            // Clean up in reverse order (groups first, then users)
            createdGroupIds.forEach { groupId ->
                try {
                    testDataHelper.deleteGroup(groupId)
                } catch (e: Exception) {
                    println("⚠️  Failed to cleanup group $groupId: ${e.message}")
                }
            }

            createdUserIds.forEach { userId ->
                try {
                    testDataHelper.deleteUser(userId)
                } catch (e: Exception) {
                    println("⚠️  Failed to cleanup user $userId: ${e.message}")
                }
            }
        }
        ApiClient.resetBaseUrlForE2E()
    }
    
    /**
     * Get or create a shared user for this test class. Reuses the same user across
     * tests in the class to reduce registration (auth) requests and avoid rate limits.
     * Do not track shared users for cleanup.
     */
    protected suspend fun getOrCreateSharedUser(): RegistrationResponse {
        return sharedUserCache.getOrPut(javaClass) {
            testDataHelper.createTestUser(api)
        }
    }

    /**
     * Track a created user for automatic cleanup.
     */
    protected fun trackUser(userId: String) {
        createdUserIds.add(userId)
    }
    
    /**
     * Track a created group for automatic cleanup.
     */
    protected fun trackGroup(groupId: String) {
        createdGroupIds.add(groupId)
    }
}
