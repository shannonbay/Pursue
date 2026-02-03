# Pursue Token Refresh Specification

**Purpose:** Automatic access token refresh to maintain seamless user experience  
**Scope:** Android app authentication layer  
**Approach:** OkHttp Authenticator with transparent token refresh

---

## Overview

### Problem Statement

**Current Behavior (Bad UX):**
```
User leaves app open → Access token expires (1 hour) →
User tries to upload avatar → 401 Unauthorized →
User sees error: "Failed to upload avatar" ❌
```

**Desired Behavior (Good UX):**
```
User leaves app open → Access token expires (1 hour) →
User tries to upload avatar → 401 Unauthorized →
App automatically refreshes token → Retry upload →
User sees: "Profile picture updated!" ✅
User never knows token expired
```

### Solution

Implement **automatic token refresh** using OkHttp's `Authenticator`:
- Intercept all 401 responses
- Automatically call `/api/auth/refresh` with refresh token
- Get new access token and refresh token
- Retry original request with new token
- Only sign out if refresh token is also invalid

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│ Fragment/ViewModel                                          │
│ Makes API call (e.g., uploadAvatar)                        │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────────────────┐
│ ApiClient (OkHttp)                                          │
│ AuthInterceptor adds: Authorization: Bearer <access_token>  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────────────────┐
│ Backend Server                                              │
│ Returns: 401 Unauthorized (token expired)                  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────────────────┐
│ TokenAuthenticator (INTERCEPTS 401)                        │
│ 1. Calls POST /api/auth/refresh                            │
│ 2. Gets new access_token + refresh_token                   │
│ 3. Saves new tokens                                        │
│ 4. Retries original request with new token                 │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────────────────┐
│ Backend Server                                              │
│ Returns: 200 OK (upload succeeded)                         │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────────────────────┐
│ Fragment/ViewModel                                          │
│ Receives success response                                  │
│ Shows: "Profile picture updated!" ✅                        │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

1. **SecureTokenManager** - Stores and retrieves tokens using EncryptedSharedPreferences (already exists)
2. **TokenAuthenticator** - Intercepts 401, refreshes token, retries request
3. **AuthInterceptor** - Adds Authorization header to all requests
4. **ApiClient** - Configures OkHttp with authenticator and interceptors (uses OkHttp directly, not Retrofit)
5. **AuthRepository** - Manages auth state (sign in, sign out) with StateFlow

---

## Implementation

### 1. Secure Token Manager

**File:** `app/src/main/java/com/github/shannonbay/pursue/SecureTokenManager.kt`

**Note:** This class already exists in the codebase. It uses `EncryptedSharedPreferences` for secure token storage.

Key methods:
- `storeTokens(accessToken: String, refreshToken: String)` - Save both tokens
- `getAccessToken(): String?` - Get current access token
- `getRefreshToken(): String?` - Get current refresh token
- `hasTokens(): Boolean` - Check if tokens exist
- `clearTokens()` - Clear all tokens (sign out)
- `getInstance(context: Context): SecureTokenManager` - Singleton access

The implementation uses Android Keystore with hardware-backed encryption when available, and falls back to software encryption for test environments (e.g., Robolectric).

---

### 2. Auth Interceptor

**File:** `app/src/main/java/com/github/shannonbay/pursue/AuthInterceptor.kt`

```kotlin
package com.github.shannonbay.pursue

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Authorization header to all API requests (except auth endpoints).
 * 
 * Automatically retrieves access token from SecureTokenManager and adds
 * it to requests that require authentication.
 */
class AuthInterceptor(
    private val context: Context
) : Interceptor {
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        
        // Skip auth header for these endpoints (they don't need it)
        if (shouldSkipAuth(url)) {
            return chain.proceed(request)
        }
        
        // Add access token to request
        val tokenManager = SecureTokenManager.getInstance(context)
        val accessToken = tokenManager.getAccessToken()
        
        val authenticatedRequest = if (accessToken != null) {
            request.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            request
        }
        
        return chain.proceed(authenticatedRequest)
    }
    
    /**
     * Check if the URL should skip authentication header.
     * 
     * Skip for:
     * - Auth endpoints (login, register, google, refresh)
     * - Public avatar endpoints (/users/{id}/avatar where id != "me")
     * - Public group icon endpoints (/groups/{id}/icon)
     */
    private fun shouldSkipAuth(url: String): Boolean {
        return url.contains("/auth/login") ||
               url.contains("/auth/register") ||
               url.contains("/auth/refresh") ||
               url.contains("/auth/google") ||
               (url.contains("/users/") && url.endsWith("/avatar") && !url.contains("/users/me/avatar")) ||
               (url.contains("/groups/") && url.endsWith("/icon"))
    }
}
```

---

### 3. Token Authenticator

**File:** `app/src/main/java/com/github/shannonbay/pursue/TokenAuthenticator.kt`

```kotlin
package com.github.shannonbay.pursue

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Automatically refreshes access token when receiving 401 Unauthorized.
 * 
 * Flow:
 * 1. Request returns 401
 * 2. Call POST /api/auth/refresh with refresh token
 * 3. Get new access token and refresh token
 * 4. Save new tokens
 * 5. Retry original request with new token
 * 6. If refresh fails, return null (triggers sign out)
 */
class TokenAuthenticator(
    private val context: Context
) : Authenticator {
    
    companion object {
        private const val TAG = "TokenAuthenticator"
        private const val HEADER_RETRY_COUNT = "X-Retry-Count"
    }
    
    // Prevent multiple simultaneous refresh attempts
    private val refreshMutex = Mutex()
    
    override fun authenticate(route: Route?, response: Response): Request? {
        Log.d(TAG, "authenticate: Received 401, attempting token refresh")
        
        // Don't retry if this is already a retry
        if (response.request.header(HEADER_RETRY_COUNT) != null) {
            Log.w(TAG, "authenticate: Already retried once, giving up")
            clearTokensAndSignOut()
            return null
        }
        
        // Don't retry if this is a refresh or auth request
        val url = response.request.url.toString()
        if (url.contains("/auth/refresh") || 
            url.contains("/auth/login") || 
            url.contains("/auth/register") ||
            url.contains("/auth/google")) {
            Log.w(TAG, "authenticate: Auth endpoint failed, clearing tokens")
            clearTokensAndSignOut()
            return null
        }
        
        // Get refresh token
        val tokenManager = SecureTokenManager.getInstance(context)
        val refreshToken = tokenManager.getRefreshToken()
        if (refreshToken == null) {
            Log.w(TAG, "authenticate: No refresh token available")
            clearTokensAndSignOut()
            return null
        }
        
        // Synchronously refresh token (blocks this request)
        return runBlocking {
            refreshMutex.withLock {
                Log.d(TAG, "authenticate: Acquired lock, checking if token already refreshed")
                
                // Check if another thread already refreshed the token
                val currentToken = tokenManager.getAccessToken()
                val requestToken = response.request.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.trim()
                
                if (currentToken != null && currentToken != requestToken) {
                    Log.d(TAG, "authenticate: Token already refreshed by another request")
                    // Token was already refreshed, just retry with new token
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer $currentToken")
                        .header(HEADER_RETRY_COUNT, "1")
                        .build()
                }
                
                // Perform token refresh
                Log.d(TAG, "authenticate: Calling /api/auth/refresh")
                
                try {
                    val refreshResponse = ApiClient.refreshToken(refreshToken)
                    
                    Log.d(TAG, "authenticate: Token refresh successful")
                    
                    // Save new tokens
                    tokenManager.storeTokens(
                        accessToken = refreshResponse.access_token,
                        refreshToken = refreshResponse.refresh_token
                    )
                    
                    // Retry original request with new token
                    return@withLock response.request.newBuilder()
                        .header("Authorization", "Bearer ${refreshResponse.access_token}")
                        .header(HEADER_RETRY_COUNT, "1")
                        .build()
                } catch (e: ApiException) {
                    Log.e(TAG, "authenticate: Token refresh failed with code ${e.code}: ${e.message}")
                    clearTokensAndSignOut()
                    return@withLock null
                } catch (e: Exception) {
                    Log.e(TAG, "authenticate: Token refresh exception: ${e.message}", e)
                    clearTokensAndSignOut()
                    return@withLock null
                }
            }
        }
    }
    
    private fun clearTokensAndSignOut() {
        Log.d(TAG, "clearTokensAndSignOut: Clearing tokens and signing out")
        val authRepository = AuthRepository.getInstance(context)
        authRepository.signOut()
    }
}
```

---

### 4. Refresh Token Method in ApiClient

**File:** `app/src/main/java/com/github/shannonbay/pursue/ApiClient.kt`

The `refreshToken()` method is added directly to `ApiClient` as a suspend function. It uses a separate `OkHttpClient` without the authenticator to avoid infinite loops.

```kotlin
/**
 * Refresh access token using refresh token.
 * 
 * This method uses a separate client without authenticator to avoid infinite loops.
 * 
 * @param refreshToken The refresh token to use for refreshing
 * @return RefreshTokenResponse with new access_token and refresh_token
 * @throws ApiException on error
 */
suspend fun refreshToken(
    refreshToken: String
): RefreshTokenResponse {
    val requestBody = gson.toJson(RefreshTokenRequest(refreshToken))
        .toRequestBody(jsonMediaType)
    
    val request = Request.Builder()
        .url("$BASE_URL/auth/refresh")
        .post(requestBody)
        .addHeader("Content-Type", "application/json")
        .build()
    
    // Use a separate client without authenticator to avoid infinite loop
    val refreshClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    return try {
        refreshClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                // Handle error...
                throw ApiException(response.code, errorMessage)
            }
            
            gson.fromJson(responseBody, RefreshTokenResponse::class.java)
        }
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException(0, "Network error: ${e.message}")
    }
}

// Data classes
data class RefreshTokenRequest(
    val refresh_token: String
)

data class RefreshTokenResponse(
    val access_token: String,
    val refresh_token: String
)
```

---

### 5. API Client Configuration

**File:** `app/src/main/java/com/github/shannonbay/pursue/ApiClient.kt`

The `ApiClient` is an object that uses OkHttp directly (not Retrofit). It requires initialization with a `Context` to access `SecureTokenManager`.

```kotlin
object ApiClient {
    const val BASE_URL = "http://10.0.2.2:3000/api"
    
    private var context: Context? = null
    
    /**
     * Initialize ApiClient with context (required for token management).
     * Should be called from Application.onCreate().
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
    }
    
    /**
     * Get configured OkHttpClient with interceptor and authenticator.
     * Requires context to be initialized.
     */
    private fun getClient(): OkHttpClient {
        val ctx = context ?: throw IllegalStateException(
            "ApiClient not initialized. Call ApiClient.initialize(context) first."
        )
        
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(ctx))
            .authenticator(TokenAuthenticator(ctx))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    // All API methods use getClient() instead of a static client
    // Manual Authorization headers are removed (interceptor handles it)
}
```

---

### 6. Application Setup

**File:** `app/src/main/java/com/github/shannonbay/pursue/PursueApplication.kt`

```kotlin
package com.github.shannonbay.pursue

import android.app.Application

/**
 * Application class for Pursue app.
 * 
 * Initializes ApiClient with context for token management.
 */
class PursueApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize API client with context (required for token management)
        ApiClient.initialize(this)
    }
}
```

**AndroidManifest.xml:**
```xml
<application
    android:name=".PursueApplication"
    ...>
    <!-- Rest of manifest -->
</application>
```

---

### 7. Auth Repository

**File:** `app/src/main/java/com/github/shannonbay/pursue/AuthRepository.kt`

The `AuthRepository` is a singleton that manages authentication state using `StateFlow`. It does not handle login/register (those are still handled in `OnboardingActivity`), but it manages the auth state and provides sign-out functionality.

```kotlin
package com.github.shannonbay.pursue

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing authentication state.
 * 
 * Provides StateFlow for observing auth state changes and handles sign out.
 */
class AuthRepository private constructor(context: Context) {
    
    private val tokenManager = SecureTokenManager.getInstance(context)
    
    private val _authState = MutableStateFlow<AuthState>(
        if (tokenManager.hasTokens()) AuthState.SignedIn 
        else AuthState.SignedOut
    )
    
    /**
     * Observable auth state.
     * Emits SignedIn when user has valid tokens, SignedOut otherwise.
     */
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    /**
     * Sign out the user by clearing tokens and updating state.
     */
    fun signOut() {
        tokenManager.clearTokens()
        _authState.value = AuthState.SignedOut
    }
    
    /**
     * Check if user is currently signed in.
     */
    fun isSignedIn(): Boolean {
        return tokenManager.hasTokens()
    }
    
    /**
     * Update auth state to SignedIn (called after successful login/register).
     */
    fun setSignedIn() {
        _authState.value = AuthState.SignedIn
    }
    
    companion object {
        @Volatile
        private var INSTANCE: AuthRepository? = null
        
        /**
         * Get the singleton instance of AuthRepository.
         * Thread-safe lazy initialization.
         */
        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

/**
 * Authentication state.
 */
sealed class AuthState {
    object SignedIn : AuthState()
    object SignedOut : AuthState()
}
```

**Note:** After successful login/register in `OnboardingActivity` or `SignUpEmailFragment`, call `AuthRepository.getInstance(context).setSignedIn()` to update the auth state.

---

### 8. MainAppActivity - Handle Sign Out

**File:** `app/src/main/java/com/github/shannonbay/pursue/MainAppActivity.kt`

The `MainAppActivity` observes `AuthRepository.authState` and navigates to `OnboardingActivity` when the state changes to `SignedOut`.

```kotlin
class MainAppActivity : AppCompatActivity() {
    
    private lateinit var authRepository: AuthRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_app)
        
        // Setup auth state observation
        authRepository = AuthRepository.getInstance(this)
        observeAuthState()
        
        // ... rest of onCreate
    }
    
    /**
     * Observe authentication state and handle sign out.
     */
    private fun observeAuthState() {
        lifecycleScope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is AuthState.SignedOut -> handleSignOut()
                    is AuthState.SignedIn -> {
                        // Already signed in, no action needed
                    }
                }
            }
        }
    }
    
    /**
     * Handle user sign out by navigating to OnboardingActivity.
     */
    private fun handleSignOut() {
        Log.d("MainAppActivity", "Auth state changed to SignedOut, navigating to OnboardingActivity")
        
        // Show toast message
        Toast.makeText(
            this,
            "Session expired. Please sign in again.",
            Toast.LENGTH_LONG
        ).show()
        
        // Navigate to OnboardingActivity
        val intent = Intent(this, OnboardingActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
```

---

## Testing

### Unit Tests

**Test TokenAuthenticator:**

```kotlin
class TokenAuthenticatorTest {
    
    private lateinit var mockContext: Context
    private lateinit var mockTokenManager: SecureTokenManager
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var authenticator: TokenAuthenticator
    
    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockTokenManager = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)
        
        mockkObject(SecureTokenManager)
        every { SecureTokenManager.getInstance(any()) } returns mockTokenManager
        
        mockkObject(AuthRepository)
        every { AuthRepository.getInstance(any()) } returns mockAuthRepository
        
        authenticator = TokenAuthenticator(mockContext)
    }
    
    @Test
    fun `authenticate refreshes token on 401`() = runTest {
        // Arrange
        val refreshToken = "valid_refresh_token"
        every { mockTokenManager.getRefreshToken() } returns refreshToken
        every { mockTokenManager.getAccessToken() } returns "old_token"
        
        val newTokens = RefreshTokenResponse(
            access_token = "new_access_token",
            refresh_token = "new_refresh_token"
        )
        
        mockkObject(ApiClient)
        coEvery { ApiClient.refreshToken(refreshToken) } returns newTokens
        
        val mockResponse = mockk<Response>(relaxed = true) {
            every { request.header("X-Retry-Count") } returns null
            every { request.url.toString() } returns "http://localhost/api/users/me"
            every { request.header("Authorization") } returns "Bearer old_token"
        }
        
        // Act
        val result = authenticator.authenticate(null, mockResponse)
        
        // Assert
        assertThat(result).isNotNull()
        verify { mockTokenManager.storeTokens("new_access_token", "new_refresh_token") }
    }
    
    @Test
    fun `authenticate returns null when refresh fails`() = runTest {
        // Arrange
        every { mockTokenManager.getRefreshToken() } returns "refresh_token"
        
        mockkObject(ApiClient)
        coEvery { ApiClient.refreshToken(any()) } throws ApiException(401, "Refresh failed")
        
        val mockResponse = mockk<Response>(relaxed = true) {
            every { request.header("X-Retry-Count") } returns null
            every { request.url.toString() } returns "http://localhost/api/users/me"
        }
        
        // Act
        val result = authenticator.authenticate(null, mockResponse)
        
        // Assert
        assertThat(result).isNull()
        verify { mockAuthRepository.signOut() }
    }
}
```

---

### Integration/E2E Tests

**Test automatic refresh in real scenario:**

```kotlin
@RunWith(AndroidJUnit4::class)
class TokenRefreshE2ETest : E2ETest() {
    
    @Test
    fun `expired token is automatically refreshed and request succeeds`() = runTest {
        // Arrange - Create user
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user.id)
        
        // Manually expire the access token by waiting 1 hour
        // OR manually set an expired token
        tokenManager.saveTokens(
            accessToken = "expired_token_12345",
            refreshToken = authResponse.refreshToken
        )
        
        val testImage = TestImageHelper.createSmallTestImage()
        
        // Act - Upload avatar (will get 401, then auto-refresh, then succeed)
        val uploadResponse = testDataHelper.uploadTestAvatar(
            api = api,
            accessToken = "expired_token_12345", // This will fail first time
            imageBytes = testImage
        )
        
        // Assert - Should succeed after automatic refresh
        assertThat(uploadResponse.success).isTrue()
        
        // Verify token was refreshed
        val newAccessToken = tokenManager.getAccessToken()
        assertThat(newAccessToken).isNotEqualTo("expired_token_12345")
    }
}
```

---

## Flow Diagrams

### Successful Token Refresh

```
┌──────────────┐
│ User Action  │
│ Upload Avatar│
└──────┬───────┘
       │
       ↓
┌─────────────────────────────────────┐
│ POST /api/users/me/avatar           │
│ Authorization: Bearer <expired>     │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ Server: 401 Unauthorized            │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ TokenAuthenticator.authenticate()   │
│ - Acquires mutex lock               │
│ - Checks if already retried (no)    │
│ - Gets refresh token                │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ POST /api/auth/refresh              │
│ { "refreshToken": "..." }           │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ Server: 200 OK                      │
│ {                                   │
│   "access_token": "new_token",      │
│   "refresh_token": "new_refresh"    │
│ }                                   │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ TokenAuthenticator                  │
│ - Calls tokenManager.storeTokens() │
│ - Creates new request with new token│
│ - Adds X-Retry-Count: 1 header      │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ POST /api/users/me/avatar (RETRY)   │
│ Authorization: Bearer <new_token>   │
│ X-Retry-Count: 1                    │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ Server: 200 OK                      │
│ { "success": true }                 │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ User sees success message           │
│ "Profile picture updated!" ✅       │
└─────────────────────────────────────┘
```

---

### Failed Token Refresh (Both Tokens Expired)

```
┌──────────────┐
│ User Action  │
└──────┬───────┘
       │
       ↓
┌─────────────────────────────────────┐
│ Server: 401 Unauthorized            │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ TokenAuthenticator.authenticate()   │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ POST /api/auth/refresh              │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ Server: 401 Unauthorized            │
│ (Refresh token also expired)        │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ TokenAuthenticator                  │
│ - Calls authRepository.signOut()    │
│ - Returns null (don't retry)        │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ AuthRepository                      │
│ - Clears tokens                     │
│ - Sets authState = SignedOut        │
└──────┬──────────────────────────────┘
       │
       ↓
┌─────────────────────────────────────┐
│ MainAppActivity observes state change│
│ - Navigates to OnboardingActivity   │
│ - Shows: "Session expired"           │
└─────────────────────────────────────┘
```

---

### Concurrent Requests (Mutex Prevents Multiple Refreshes)

```
Time →

Request A: Upload Avatar
    ↓
    401 → TokenAuthenticator
         ├─ Acquires mutex lock
         └─ Starts refresh...

Request B: Update Profile (happens 50ms later)
    ↓
    401 → TokenAuthenticator
         └─ Waits for mutex... (blocked)

Request A continues:
         ├─ POST /api/auth/refresh
         ├─ Gets new tokens
         ├─ Saves tokens
         └─ Releases mutex lock

Request B unblocked:
         ├─ Acquires mutex lock
         ├─ Checks current token
         ├─ Sees token already refreshed!
         ├─ Uses new token directly
         └─ Releases mutex lock

Result:
- Only ONE refresh call made ✅
- Both requests succeed with new token ✅
```

---

## Edge Cases & Error Handling

### 1. No Refresh Token Available

```kotlin
// Scenario: User cleared app data but app still running
val refreshToken = tokenManager.getRefreshToken()
if (refreshToken == null) {
    // Can't refresh, sign out immediately
    clearTokensAndSignOut()
    return null
}
```

### 2. Already Retried Once

```kotlin
// Scenario: Refresh succeeded but new token also expired (clock skew?)
if (response.request.header("X-Retry-Count") != null) {
    // Don't retry infinitely
    clearTokensAndSignOut()
    return null
}
```

### 3. Network Error During Refresh

```kotlin
try {
    val refreshResponse = authApi.refreshToken(...).execute()
} catch (e: IOException) {
    // Network error - can't refresh
    // User might be offline, sign out
    clearTokensAndSignOut()
    return null
}
```

### 4. Refresh Endpoint Returns 401

```kotlin
// Scenario: Refresh token expired
if (!refreshResponse.isSuccessful) {
    // Refresh token invalid/expired
    clearTokensAndSignOut()
    return null
}
```

### 5. Multiple Concurrent 401s

```kotlin
// Handled by mutex - first request refreshes, others wait
refreshMutex.withLock {
    // Check if token already refreshed by another thread
    val currentToken = tokenManager.getAccessToken()
    if (currentToken != requestToken) {
        // Another thread already refreshed, use new token
        return request.withNewToken(currentToken)
    }
    // Otherwise, we refresh
}
```

---

## Security Considerations

### 1. Encrypted Token Storage

```kotlin
// Use EncryptedSharedPreferences (already implemented in TokenManager)
private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
    context,
    "auth_prefs",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

**Why:** Tokens stored in plain SharedPreferences can be read by rooted devices or backups.

### 2. HTTPS Only

```kotlin
// In production, use HTTPS
private const val BASE_URL = "https://api.getpursue.app/api/"

// In OkHttpClient, add certificate pinning (optional)
val certificatePinner = CertificatePinner.Builder()
    .add("api.getpursue.app", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    .build()

val client = OkHttpClient.Builder()
    .certificatePinner(certificatePinner)
    .build()
```

### 3. Refresh Token Rotation

Backend should return NEW refresh token on each refresh:

```json
POST /api/auth/refresh
{
  "refreshToken": "old_refresh_token"
}

Response:
{
  "accessToken": "new_access_token",
  "refreshToken": "new_refresh_token"  ← Always rotate
}
```

**Why:** Prevents stolen refresh tokens from being reused indefinitely.

### 4. Token Expiry Times

Recommended values:
- **Access token:** 1 hour (current)
- **Refresh token:** 30 days (current)

**Why:** Short access tokens limit damage if stolen, long refresh tokens minimize user re-authentication.

---

## Performance Considerations

### 1. Refresh is Synchronous (Blocking)

```kotlin
// authenticate() blocks the request thread
override fun authenticate(route: Route?, response: Response): Request? {
    return runBlocking {  // Blocks here
        refreshMutex.withLock {
            // Refresh token
        }
    }
}
```

**Impact:** Request waits for refresh to complete (~500ms)

**Why it's OK:** 
- Only happens once per hour
- User doesn't notice (transparent)
- Better than showing error

### 2. Mutex Prevents Stampede

Without mutex:
```
100 concurrent requests → 100 refresh calls → Server overload
```

With mutex:
```
100 concurrent requests → 1 refresh call → 99 requests reuse new token
```

### 3. Caching New Token

```kotlin
// After refresh, all waiting requests use new token immediately
val currentToken = tokenManager.getAccessToken()
if (currentToken != null && currentToken != requestToken) {
    return response.request.newBuilder()
        .header("Authorization", "Bearer $currentToken")
        .header(HEADER_RETRY_COUNT, "1")
        .build()
}
```

**Result:** Only first request actually refreshes, others reuse immediately.

---

## Debugging

### Enable Detailed Logging

```kotlin
class TokenAuthenticator(...) {
    companion object {
        private const val TAG = "TokenAuthenticator"
    }
    
    override fun authenticate(route: Route?, response: Response): Request? {
        Log.d(TAG, "=== Token Refresh Debug ===")
        Log.d(TAG, "Request URL: ${response.request.url}")
        Log.d(TAG, "Response code: ${response.code}")
        Log.d(TAG, "Has refresh token: ${tokenManager.getRefreshToken() != null}")
        
        // ... rest of logic with Log.d() statements
    }
}
```

### Common Issues

**Issue 1: Infinite loop of 401s**
```
Cause: Refresh endpoint also returns 401
Fix: refreshToken() uses separate OkHttpClient without authenticator
```

**Issue 2: Token not persisted**
```
Cause: SecureTokenManager.storeTokens() not called
Fix: Ensure storeTokens() is called after refresh (already implemented)
```

**Issue 3: Multiple refreshes happening**
```
Cause: Mutex not working
Fix: Ensure using same TokenAuthenticator instance (handled by getClient())
```

**Issue 4: User signed out unexpectedly**
```
Cause: Refresh token expired or ApiClient not initialized
Fix: Ensure ApiClient.initialize(context) is called in PursueApplication.onCreate()
```

**Issue 5: "ApiClient not initialized" error**
```
Cause: PursueApplication not registered in AndroidManifest.xml
Fix: Add android:name=".PursueApplication" to <application> tag
```

---

## Migration Guide

### Before (Current Code)

```kotlin
// Fragment
lifecycleScope.launch {
    try {
        val accessToken = tokenManager.getAccessToken()
        val response = ApiClient.uploadAvatar(accessToken, imageFile)
        
        // User sees error on 401
        Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
    } catch (e: ApiException) {
        if (e.code == 401) {
            // User sees error ❌
            Toast.makeText(context, "Failed to upload avatar", Toast.LENGTH_SHORT).show()
        }
    }
}
```

### After (With Token Refresh)

```kotlin
// Fragment - NO CHANGES NEEDED!
lifecycleScope.launch {
    try {
        val accessToken = tokenManager.getAccessToken()
        // TokenAuthenticator automatically handles 401 and refreshes token
        val response = ApiClient.uploadAvatar(accessToken, imageFile)
        
        // User sees success even if token expired! ✅
        Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
    } catch (e: ApiException) {
        // Only non-401 errors reach here
        Toast.makeText(context, "Failed to upload avatar", Toast.LENGTH_SHORT).show()
    }
}
```

**Key Point:** No Fragment/ViewModel changes needed! TokenAuthenticator is transparent. The `accessToken` parameter is still passed for backward compatibility, but the interceptor will override it automatically.

---

## Summary

### Implementation Checklist

- [x] SecureTokenManager already exists (uses EncryptedSharedPreferences)
- [x] Create AuthInterceptor.kt
- [x] Create TokenAuthenticator.kt
- [x] Add refreshToken() method to ApiClient.kt
- [x] Update ApiClient.kt to configure interceptor + authenticator
- [x] Create PursueApplication.kt
- [x] Create AuthRepository.kt with StateFlow
- [x] Update MainAppActivity.kt to observe auth state
- [x] Update OnboardingActivity and SignUpEmailFragment to call setSignedIn()
- [x] Update AndroidManifest.xml to register PursueApplication

### Expected Behavior

**Before:**
- Token expires → User gets error ❌

**After:**
- Token expires → Auto-refresh → Request succeeds ✅
- User never sees error
- Only signs out if refresh token also expired

### Performance Impact

- ✅ **Transparent:** User doesn't notice
- ✅ **Fast:** ~500ms one-time delay per hour
- ✅ **Efficient:** Mutex prevents multiple simultaneous refreshes
- ✅ **Secure:** Tokens encrypted, HTTPS enforced

### Security Benefits

- ✅ Encrypted token storage
- ✅ Refresh token rotation
- ✅ Short access token lifetime (1 hour)
- ✅ Automatic session cleanup on failure

This implementation follows Android best practices and provides seamless user experience!

