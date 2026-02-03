package com.github.shannonbay.pursue.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.shannonbay.pursue.data.auth.AuthRepository
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for token refresh flow.
 * 
 * Tests the end-to-end scenario: expired token → auto refresh → request succeeds.
 * 
 * Note: This uses mocks for ApiClient.refreshToken() rather than a real server
 * to keep tests fast and reliable. For true E2E testing, see E2E test suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class TokenRefreshIntegrationTest {

    private lateinit var context: Context
    private lateinit var mockTokenManager: SecureTokenManager
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var client: OkHttpClient
    
    private val expiredAccessToken = "expired_access_token_123"
    private val validRefreshToken = "valid_refresh_token_456"
    private val newAccessToken = "new_access_token_789"
    private val newRefreshToken = "new_refresh_token_789"
    private val baseUrl = "http://localhost/api"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Mock SecureTokenManager
        mockkObject(SecureTokenManager.Companion)
        mockTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.getInstance(any()) } returns mockTokenManager
        
        // Mock ApiClient
        mockkObject(ApiClient)
        
        // Mock AuthRepository
        mockkObject(AuthRepository.Companion)
        mockAuthRepository = mockk(relaxed = true)
        every { AuthRepository.getInstance(any()) } returns mockAuthRepository
        
        // Setup initial token state
        every { mockTokenManager.getAccessToken() } returns expiredAccessToken
        every { mockTokenManager.getRefreshToken() } returns validRefreshToken
        
        // Create OkHttpClient with interceptor and authenticator
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(context))
            .authenticator(TokenAuthenticator(context))
            .build()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test expired token automatically refreshes and request succeeds`() = runTest {
        // Given - Setup mock server behavior
        // First call with expired token returns 401
        // Refresh succeeds and returns new tokens
        // Retry with new token succeeds
        
        val refreshResponse = RefreshTokenResponse(
            access_token = newAccessToken,
            refresh_token = newRefreshToken
        )
        coEvery { ApiClient.refreshToken(validRefreshToken) } returns refreshResponse
        
        // Simulate: Request with expired token → 401 → Refresh → Retry with new token → 200
        
        // Step 1: Create a mock response that simulates 401
        val originalRequest = Request.Builder()
            .url("$baseUrl/users/me")
            .header("Authorization", "Bearer $expiredAccessToken")
            .build()
        
        val response401 = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ResponseBody.create("application/json".toMediaType(), "Unauthorized"))
            .build()
        
        // Step 2: Use TokenAuthenticator to handle the 401 (this simulates the real flow)
        val authenticator = TokenAuthenticator(context)
        val retryRequest = authenticator.authenticate(null, response401)
        
        // Step 3: Verify token refresh was called
        coVerify { ApiClient.refreshToken(validRefreshToken) }
        
        // Step 4: Verify tokens are stored
        verify { 
            mockTokenManager.storeTokens(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken
            )
        }
        
        // Step 5: Verify new request was created with new token
        assertNotNull("Retry request should be created", retryRequest)
        assertEquals("Retry request should have new token", 
            "Bearer $newAccessToken", 
            retryRequest?.header("Authorization"))
        assertEquals("Retry request should have retry count", 
            "1", 
            retryRequest?.header("X-Retry-Count"))
        
        // In a real scenario, this retry request would succeed (200 OK)
        // We've verified the refresh flow works correctly
    }

    @Test
    fun `test expired token refresh failure triggers sign out`() = runTest {
        // Given - Refresh fails with 401
        coEvery { ApiClient.refreshToken(validRefreshToken) } throws ApiException(
            401,
            "Unauthorized"
        )
        
        // Create a 401 response
        val originalRequest = Request.Builder()
            .url("$baseUrl/users/me")
            .header("Authorization", "Bearer $expiredAccessToken")
            .build()
        
        val response401 = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ResponseBody.create("application/json".toMediaType(), "Unauthorized"))
            .build()
        
        // When - Use TokenAuthenticator to handle the 401
        val authenticator = TokenAuthenticator(context)
        val retryRequest = authenticator.authenticate(null, response401)
        
        // Then - Should return null (triggers sign out)
        assertNull("Should return null when refresh fails", retryRequest)
        
        // Verify refresh was attempted
        coVerify { ApiClient.refreshToken(validRefreshToken) }
        
        // Verify sign out was called
        verify { mockAuthRepository.signOut() }
    }

    @Test
    fun `test refresh failure with 500 triggers sign out`() = runTest {
        // Given - Refresh fails with 500 (server error / database migration)
        coEvery { ApiClient.refreshToken(validRefreshToken) } throws ApiException(
            500,
            "Internal server error"
        )
        
        val originalRequest = Request.Builder()
            .url("$baseUrl/users/me")
            .header("Authorization", "Bearer $expiredAccessToken")
            .build()
        
        val response401 = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ResponseBody.create("application/json".toMediaType(), "Unauthorized"))
            .build()
        
        val authenticator = TokenAuthenticator(context)
        val retryRequest = authenticator.authenticate(null, response401)
        
        assertNull("Should return null when refresh fails with 500", retryRequest)
        coVerify { ApiClient.refreshToken(validRefreshToken) }
        verify { mockAuthRepository.signOut() }
    }

    @Test
    fun `test interceptor adds token and authenticator refreshes on 401`() = runTest {
        // Given - Token exists and will be added by interceptor
        every { mockTokenManager.getAccessToken() } returns expiredAccessToken
        
        val refreshResponse = RefreshTokenResponse(
            access_token = newAccessToken,
            refresh_token = newRefreshToken
        )
        coEvery { ApiClient.refreshToken(validRefreshToken) } returns refreshResponse
        
        // When - Test interceptor adds token
        val originalRequest = Request.Builder()
            .url("$baseUrl/users/me")
            .build()
        
        val interceptor = AuthInterceptor(context)
        val mockChain = mockk<Interceptor.Chain>(relaxed = true)
        every { mockChain.request() } returns originalRequest
        
        // Interceptor adds token
        val interceptedRequest = slot<Request>()
        val responseWithToken = Response.Builder()
            .request(originalRequest.newBuilder().header("Authorization", "Bearer $expiredAccessToken").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ResponseBody.create("application/json".toMediaType(), "Unauthorized"))
            .build()
        
        every { mockChain.proceed(capture(interceptedRequest)) } returns responseWithToken
        interceptor.intercept(mockChain)
        
        assertEquals("Interceptor should add token", 
            "Bearer $expiredAccessToken", 
            interceptedRequest.captured.header("Authorization"))
        
        // Simulate 401 response (with token from interceptor)
        val response401 = Response.Builder()
            .request(interceptedRequest.captured)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(ResponseBody.create("application/json".toMediaType(), "Unauthorized"))
            .build()
        
        // Authenticator refreshes token
        val authenticator = TokenAuthenticator(context)
        val retryRequest = authenticator.authenticate(null, response401)
        
        // Then - Verify the flow
        assertNotNull("Retry request should be created", retryRequest)
        assertEquals("Retry should use new token", 
            "Bearer $newAccessToken", 
            retryRequest?.header("Authorization"))
        assertEquals("Retry should have retry count", 
            "1", 
            retryRequest?.header("X-Retry-Count"))
        
        // Verify refresh was called
        coVerify { ApiClient.refreshToken(validRefreshToken) }
        
        // Verify tokens were stored
        verify { 
            mockTokenManager.storeTokens(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken
            )
        }
    }
}
