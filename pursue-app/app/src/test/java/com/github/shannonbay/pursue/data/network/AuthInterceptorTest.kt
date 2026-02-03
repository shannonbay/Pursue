package com.github.shannonbay.pursue.data.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import io.mockk.*
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AuthInterceptor.
 * 
 * Tests header addition logic and skip conditions for different endpoints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthInterceptorTest {

    private lateinit var context: Context
    private lateinit var interceptor: AuthInterceptor
    private lateinit var mockTokenManager: SecureTokenManager
    private lateinit var mockChain: Interceptor.Chain
    
    private val testAccessToken = "test_access_token_123"
    private val baseUrl = "http://localhost/api"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Mock SecureTokenManager
        mockkObject(SecureTokenManager.Companion)
        mockTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.getInstance(any()) } returns mockTokenManager
        
        // Mock Interceptor.Chain
        mockChain = mockk(relaxed = true)
        
        interceptor = AuthInterceptor(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // Helper function to create mock request
    private fun createRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .build()
    }

    // Helper function to create mock response
    private fun createResponse(request: Request, code: Int = 200): Response {
        return mockk<Response>(relaxed = true) {
            every { this@mockk.request } returns request
            every { this@mockk.code } returns code
            every { this@mockk.isSuccessful } returns (code in 200..299)
        }
    }

    @Test
    fun `test intercept adds Authorization header when token exists`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/users/me")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertEquals("Should add Authorization header", 
            "Bearer $testAccessToken", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header when token is null`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns null
        val request = createRequest("$baseUrl/users/me")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header when token is null", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header for auth login endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/auth/login")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header for auth/login", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header for auth register endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/auth/register")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header for auth/register", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header for auth refresh endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/auth/refresh")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header for auth/refresh", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header for auth google endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/auth/google")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header for auth/google", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header for public avatar endpoints`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/users/user123/avatar")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header for public avatar", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept adds header for private avatar endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/users/me/avatar")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertEquals("Should add Authorization header for /users/me/avatar", 
            "Bearer $testAccessToken", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept does not add header for public group icon endpoints`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/groups/group123/icon")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertNull("Should not add Authorization header for public group icon", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept adds header for authenticated endpoints`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/users/me")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertEquals("Should add Authorization header for /users/me", 
            "Bearer $testAccessToken", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept adds header for users me groups endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/users/me/groups")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertEquals("Should add Authorization header for /users/me/groups", 
            "Bearer $testAccessToken", 
            interceptedRequest.header("Authorization"))
    }

    @Test
    fun `test intercept adds header for users me today-goals endpoint`() {
        // Given
        every { mockTokenManager.getAccessToken() } returns testAccessToken
        val request = createRequest("$baseUrl/users/me/today-goals")
        every { mockChain.request() } returns request
        
        val response = createResponse(request)
        every { mockChain.proceed(any()) } returns response
        
        val requestSlot = slot<Request>()

        // When
        interceptor.intercept(mockChain)

        // Then
        verify { mockChain.proceed(capture(requestSlot)) }
        val interceptedRequest = requestSlot.captured
        assertEquals("Should add Authorization header for /users/me/today-goals", 
            "Bearer $testAccessToken", 
            interceptedRequest.header("Authorization"))
    }
}
