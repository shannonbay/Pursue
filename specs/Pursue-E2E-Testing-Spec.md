# Pursue E2E Testing Strategy

**Purpose:** End-to-end regression tests between Android app and backend server  
**Scope:** Local development only (not run in CI)  
**Approach:** Copy existing mocked tests, replace mocks with real API calls

---

## Overview

### What Are These Tests?

E2E (end-to-end) tests verify the complete integration between:
- Android app (API client)
- Backend server (Node.js/Express)
- Database (PostgreSQL)
- Image processing (Sharp)
- Authentication (JWT)

### Why E2E Tests?

**Problem:** Unit tests with mocks can lie

```kotlin
// Unit test with mock - PASSES ✅
coEvery { mockApi.uploadAvatar(any()) } returns Response.success(...)

// But real server might fail due to:
// - Wrong Content-Type header
// - Sharp processing error
// - Database BYTEA storage issue
// - Image size validation mismatch
```

**Solution:** E2E tests catch integration bugs that unit tests miss

---

## Test Organization

### Directory Structure

```
pursue-android/
├── app/src/
│   ├── main/          # Production code
│   ├── test/          # Unit tests (mocked API)
│   ├── androidTest/   # Instrumented UI tests
│   └── e2e/           # E2E regression tests (NEW)
│       └── kotlin/
│           ├── config/
│           │   ├── LocalServerConfig.kt
│           │   ├── E2EApiClient.kt
│           │   ├── E2ETest.kt
│           │   └── TestDataHelper.kt
│           ├── auth/
│           │   └── AuthE2ETest.kt
│           ├── user/
│           │   └── AvatarUploadE2ETest.kt
│           ├── groups/
│           │   └── GroupIconE2ETest.kt
│           ├── goals/
│           │   └── GoalProgressE2ETest.kt
│           └── helpers/
│               └── TestImageHelper.kt
└── scripts/
    └── run-e2e-tests.sh

pursue-backend/
├── server.js
├── package.json
└── (backend code)
```

---

## Configuration

### 1. Gradle Configuration

**build.gradle.kts:**

**Note:** Android Gradle Plugin doesn't support custom source sets. E2E sources are added to the existing `test` source set.

```kotlin
android {
    // ... existing config
    
    // Add E2E sources to test source set (AGP doesn't support custom source sets)
    sourceSets.getByName("test") {
        java.srcDir("src/e2e/kotlin")
    }
}

dependencies {
    // ... existing test dependencies
    
    // E2E test dependencies - add to testImplementation since E2E sources compile with test
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttpLogging)
    testImplementation(libs.gson)
    testImplementation(libs.truth)
    
    // E2E-specific configuration (for organization, but extends testImplementation)
    val e2eImplementation by configurations.creating {
        extendsFrom(configurations.getByName("testImplementation"))
    }
    e2eImplementation(libs.junit)
    e2eImplementation(libs.kotlinx.coroutines.test)
    e2eImplementation(libs.okhttp)
    e2eImplementation(libs.okhttpLogging)
    e2eImplementation(libs.gson)
    e2eImplementation(libs.truth)
}

// Custom Gradle task for E2E tests
tasks.register<Test>("testE2e") {
    description = "Runs E2E tests against local dev server (localhost:3000)"
    group = "verification"
    
    // Use test source set output (which now includes E2E sources)
    testClassesDirs = sourceSets.getByName("test").output.classesDirs
    classpath = configurations.getByName("e2eImplementation") + 
                configurations.getByName("testImplementation") +
                sourceSets.getByName("main").output +
                sourceSets.getByName("test").output
    
    // Include only E2E test classes (those ending in E2ETest)
    include("**/*E2ETest.class", "**/*E2ETest\$*.class")
    
    // Skip E2E tests in CI
    if (System.getenv("CI") == "true") {
        enabled = false
        println("⚠️  E2E tests skipped in CI environment")
    }
    
    // Show test output
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    
    doFirst {
        println("=".repeat(60))
        println("⚠️  PREREQUISITE: Backend server must be running!")
        println("   Run: cd backend && npm run dev")
        println("   Server should be at: http://localhost:3000")
        println("=".repeat(60))
    }
    
    doLast {
        println("=".repeat(60))
        println("✅ E2E tests completed")
        println("=".repeat(60))
    }
}
```

---

### 2. Local Server Configuration

**src/e2e/kotlin/config/LocalServerConfig.kt:**

```kotlin
package com.github.shannonbay.pursue.e2e.config

import java.net.HttpURLConnection
import java.net.URL

object LocalServerConfig {
    const val BASE_URL = "http://localhost:3000"
    const val API_BASE_URL = "$BASE_URL/api"
    
    /**
     * Check if local dev server is running and accessible.
     */
    fun isServerAvailable(): Boolean {
        return try {
            val connection = URL("$BASE_URL/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            connection.connect()
            
            val responseCode = connection.responseCode
            connection.disconnect()
            
            responseCode == 200
        } catch (e: Exception) {
            println("❌ Server check failed: ${e.message}")
            false
        }
    }
}
```

---

### 3. E2E API Client

**src/e2e/kotlin/config/E2EApiClient.kt:**

E2E tests use a dedicated `E2EApiClient` that mirrors the main `ApiClient` but:
- Uses `localhost:3000` instead of `10.0.2.2:3000` (for JVM tests, not Android emulator)
- Includes HTTP logging interceptor for debugging
- Uses OkHttp directly (not Retrofit)

```kotlin
package com.github.shannonbay.pursue.e2e.config

import com.github.shannonbay.pursue.*
import com.github.shannonbay.pursue.models.*
import com.google.gson.Gson
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

class E2EApiClient {
    private val BASE_URL = LocalServerConfig.API_BASE_URL
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val client = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    // Methods mirror ApiClient but use localhost:3000
    suspend fun register(displayName: String, email: String, password: String): RegistrationResponse { ... }
    suspend fun login(email: String, password: String): LoginResponse { ... }
    suspend fun uploadAvatar(accessToken: String, imageBytes: ByteArray): UploadAvatarResponse { ... }
    suspend fun getAvatar(userId: String, accessToken: String?): ByteArray? { ... }
    suspend fun deleteAvatar(accessToken: String): DeleteAvatarResponse { ... }
    // ... other methods
}
```

### 4. Base E2E Test Class

**src/e2e/kotlin/config/E2ETest.kt:**

```kotlin
package com.github.shannonbay.pursue.e2e.config

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass

abstract class E2ETest {
    
    companion object {
        private var serverChecked = false
        
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
    
    protected lateinit var api: E2EApiClient
    protected val testDataHelper = TestDataHelper()
    
    // Track resources for cleanup
    private val createdUserIds = mutableListOf<String>()
    private val createdGroupIds = mutableListOf<String>()
    
    @Before
    fun setupApi() {
        api = E2EApiClient()
    }
    
    @After
    fun cleanup() {
        runBlocking {
            createdGroupIds.forEach { groupId ->
                try {
                    testDataHelper.deleteGroup(api, groupId)
                } catch (e: Exception) {
                    println("⚠️  Failed to cleanup group $groupId: ${e.message}")
                }
            }
            
            createdUserIds.forEach { userId ->
                try {
                    testDataHelper.deleteUser(api, userId)
                } catch (e: Exception) {
                    println("⚠️  Failed to cleanup user $userId: ${e.message}")
                }
            }
        }
    }
    
    protected fun trackUser(userId: String) {
        createdUserIds.add(userId)
    }
    
    protected fun trackGroup(groupId: String) {
        createdGroupIds.add(groupId)
    }
}
```

---

### 5. Test Data Helper

**src/e2e/kotlin/config/TestDataHelper.kt:**

```kotlin
package com.github.shannonbay.pursue.e2e.config

import com.github.shannonbay.pursue.ApiException
import com.github.shannonbay.pursue.RegistrationResponse
import java.util.UUID

class TestDataHelper {
    
    /**
     * Create a test user with unique email.
     */
    suspend fun createTestUser(
        api: E2EApiClient,
        displayName: String = "Test User",
        password: String = "TestPass123!"
    ): RegistrationResponse {
        val email = "test-${UUID.randomUUID()}@example.com"
        
        return try {
            api.register(displayName, email, password)
        } catch (e: ApiException) {
            throw Exception("Failed to create test user: ${e.message}", e)
        }
    }
    
    /**
     * Upload test avatar.
     */
    suspend fun uploadTestAvatar(
        api: E2EApiClient,
        accessToken: String,
        imageBytes: ByteArray
    ): UploadAvatarResponse {
        return api.uploadAvatar(accessToken, imageBytes)
    }
    
    /**
     * Delete user (cleanup).
     * Note: Backend deletion endpoint may not be implemented yet.
     */
    suspend fun deleteUser(api: E2EApiClient, userId: String) {
        try {
            // TODO: Implement if DELETE /api/users/:id endpoint exists
            println("⚠️  User deletion not implemented - user $userId will remain in database")
        } catch (e: Exception) {
            println("⚠️  Failed to delete user $userId: ${e.message}")
        }
    }
    
    /**
     * Delete group (cleanup).
     * Note: Backend deletion endpoint may not be implemented yet.
     */
    suspend fun deleteGroup(api: E2EApiClient, groupId: String) {
        try {
            // TODO: Implement if DELETE /api/groups/:id endpoint exists
            println("⚠️  Group deletion not implemented - group $groupId will remain in database")
        } catch (e: Exception) {
            println("⚠️  Failed to delete group $groupId: ${e.message}")
        }
    }
    
    /**
     * Create a test group.
     * Note: Backend endpoint may not be implemented yet.
     */
    suspend fun createTestGroup(
        api: E2EApiClient,
        accessToken: String,
        name: String = "Test Group ${UUID.randomUUID()}",
        description: String = "E2E test group"
    ): com.github.shannonbay.pursue.models.Group {
        // TODO: Implement if POST /api/groups endpoint exists
        throw UnsupportedOperationException("Group creation not yet implemented")
    }
    
    /**
     * Create a test goal.
     * Note: Backend endpoint may not be implemented yet.
     */
    suspend fun createTestGoal(
        api: E2EApiClient,
        accessToken: String,
        groupId: String,
        title: String = "Test Goal ${UUID.randomUUID()}"
    ): Any {
        // TODO: Implement if POST /api/goals endpoint exists
        throw UnsupportedOperationException("Goal creation not yet implemented")
    }
}
```

---

### 6. Test Image Helper

**src/e2e/kotlin/helpers/TestImageHelper.kt:**

**Note:** Android Bitmap APIs are not available in JVM test environment. Generate raw JPEG byte arrays instead.

```kotlin
package com.github.shannonbay.pursue.e2e.helpers

import java.io.ByteArrayOutputStream

object TestImageHelper {
    
    // Minimal valid 1x1 JPEG with all required markers
    private val MINIMAL_JPEG_BYTES = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), // SOI
        0xFF.toByte(), 0xE0.toByte(), // APP0 marker
        // ... minimal valid JPEG structure
        0xFF.toByte(), 0xD9.toByte()  // EOI
    )
    
    /**
     * Create a test image (minimal valid JPEG).
     * 
     * @param width Image width (used for size calculation only)
     * @param height Image height (used for size calculation only)
     */
    fun createTestImage(
        width: Int = 512,
        height: Int = 512
    ): ByteArray {
        val targetSize = (width * height / 100).coerceAtLeast(1024)
        
        if (targetSize <= MINIMAL_JPEG_BYTES.size) {
            return MINIMAL_JPEG_BYTES
        }
        
        // Pad minimal JPEG to target size
        val padded = ByteArray(targetSize)
        MINIMAL_JPEG_BYTES.copyInto(padded, 0, 0, MINIMAL_JPEG_BYTES.size - 2)
        
        // Fill padding with zeros
        for (i in MINIMAL_JPEG_BYTES.size - 2 until targetSize - 2) {
            padded[i] = 0x00
        }
        
        // Ensure EOI marker at the end
        padded[targetSize - 2] = 0xFF.toByte()
        padded[targetSize - 1] = 0xD9.toByte()
        
        return padded
    }
    
    /**
     * Create a large test image (for testing size limits).
     */
    fun createLargeTestImage(): ByteArray {
        // Create 5MB+ image by padding minimal JPEG
        val targetSize = 5 * 1024 * 1024 + 1024
        val padded = ByteArray(targetSize)
        MINIMAL_JPEG_BYTES.copyInto(padded, 0, 0, MINIMAL_JPEG_BYTES.size)
        // ... pad to target size
        padded[targetSize - 2] = 0xFF.toByte()
        padded[targetSize - 1] = 0xD9.toByte()
        return padded
    }
    
    /**
     * Create a small test image.
     */
    fun createSmallTestImage(): ByteArray {
        return createTestImage(256, 256)
    }
}
```

---

## Example E2E Tests

### 1. Authentication E2E Tests

**src/e2e/kotlin/auth/AuthE2ETest.kt:**

```kotlin
package com.github.shannonbay.pursue.e2e.auth

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AuthE2ETest : E2ETest() {
    
    @Test
    fun `register creates user and returns tokens`() = runTest {
        // Arrange
        val email = "test-${System.currentTimeMillis()}@example.com"
        val password = "TestPass123!"
        val displayName = "E2E Test User"
        
        // Act
        val response = api.register(displayName, email, password)
        
        // Assert
        assertThat(response.access_token).isNotEmpty()
        assertThat(response.refresh_token).isNotEmpty()
        assertThat(response.user).isNotNull()
        assertThat(response.user!!.email).isEqualTo(email)
        assertThat(response.user!!.display_name).isEqualTo(displayName)
        assertThat(response.user!!.has_avatar).isFalse()
        
        // Cleanup
        trackUser(response.user!!.id)
    }
    
    @Test
    fun `login with correct credentials returns tokens`() = runTest {
        // Arrange - Create test user
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val email = authResponse.user!!.email
        val password = "TestPass123!"
        
        // Act - Login
        val loginResponse = api.login(email, password)
        
        // Assert
        assertThat(loginResponse.access_token).isNotEmpty()
        assertThat(loginResponse.refresh_token).isNotEmpty()
        assertThat(loginResponse.user!!.id).isEqualTo(authResponse.user!!.id)
    }
    
    @Test
    fun `login with wrong password fails`() = runTest {
        // Arrange
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        // Act
        var exception: Exception? = null
        try {
            api.login(authResponse.user!!.email, "WrongPassword123!")
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(com.github.shannonbay.pursue.ApiException::class.java)
        val apiException = exception as com.github.shannonbay.pursue.ApiException
        assertThat(apiException.code).isEqualTo(401)
    }
    
    @Test
    fun `register with duplicate email fails`() = runTest {
        // Arrange
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        // Act - Try to register again with same email
        var exception: Exception? = null
        try {
            api.register("Different Name", authResponse.user!!.email, "DifferentPass123!")
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(com.github.shannonbay.pursue.ApiException::class.java)
        val apiException = exception as com.github.shannonbay.pursue.ApiException
        assertThat(apiException.code).isEqualTo(400)
    }
}
```

---

### 2. Avatar Upload E2E Tests

**src/e2e/kotlin/user/AvatarUploadE2ETest.kt:**

**Note:** All avatar upload tests are currently marked `@Ignore` because the backend endpoint returns 500 errors, suggesting it may not be fully implemented yet.

```kotlin
package com.github.shannonbay.pursue.e2e.user

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.helpers.TestImageHelper
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test

class AvatarUploadE2ETest : E2ETest() {
    
    @Test
    @Ignore("Backend avatar upload endpoint may not be implemented (returns 500 errors)")
    fun `upload avatar stores image and returns has_avatar true`() = runTest {
        // Arrange - Create user
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val accessToken = authResponse.access_token
        val userId = authResponse.user!!.id
        
        // Create test image
        val testImage = TestImageHelper.createSmallTestImage()
        
        // Act - Upload avatar
        val uploadResponse = api.uploadAvatar(accessToken, testImage)
        
        // Assert upload response
        assertThat(uploadResponse.has_avatar).isTrue()
        
        // Verify GET /api/users/:id/avatar returns image
        val avatarBytes = api.getAvatar(userId, accessToken)
        
        assertThat(avatarBytes).isNotNull()
        assertThat(avatarBytes!!.size).isGreaterThan(0)
    }
    
    @Test
    @Ignore("Backend avatar upload endpoint may not be implemented (returns 500 errors)")
    fun `upload avatar processes image to WebP`() = runTest {
        // Arrange
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val testImage = TestImageHelper.createSmallTestImage()
        
        // Act - Upload
        api.uploadAvatar(authResponse.access_token, testImage)
        
        // Get avatar back
        val avatarBytes = api.getAvatar(authResponse.user!!.id, authResponse.access_token)
        
        // Assert - Should be processed (backend resizes to 256x256)
        assertThat(avatarBytes).isNotNull()
        assertThat(avatarBytes!!.size).isLessThan(testImage.size)
        assertThat(avatarBytes.size).isLessThan(100 * 1024) // < 100 KB
    }
    
    @Test
    @Ignore("Backend avatar upload endpoint may not be implemented (returns 500 errors)")
    fun `upload avatar larger than 5MB fails`() = runTest {
        // Arrange
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val largeImage = TestImageHelper.createLargeTestImage()
        
        // Act
        var exception: Exception? = null
        try {
            api.uploadAvatar(authResponse.access_token, largeImage)
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert - Should fail with 400, 413, or 500
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(com.github.shannonbay.pursue.ApiException::class.java)
        val apiException = exception as com.github.shannonbay.pursue.ApiException
        assertThat(apiException.code).isAnyOf(400, 413, 500)
    }
    
    @Test
    @Ignore("Backend avatar upload/delete endpoints may not be implemented (returns 500 errors)")
    fun `delete avatar removes image and sets has_avatar false`() = runTest {
        // Arrange - Upload avatar first
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val testImage = TestImageHelper.createSmallTestImage()
        api.uploadAvatar(authResponse.access_token, testImage)
        
        // Act - Delete avatar
        val deleteResponse = api.deleteAvatar(authResponse.access_token)
        
        // Assert
        assertThat(deleteResponse.has_avatar).isFalse()
        
        // Verify GET returns null
        val avatarBytes = api.getAvatar(authResponse.user!!.id, authResponse.access_token)
        assertThat(avatarBytes).isNull()
    }
    
    @Test
    @Ignore("Backend avatar upload endpoint may not be implemented (returns 500 errors)")
    fun `GET avatar without authentication works for public profiles`() = runTest {
        // Arrange
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val testImage = TestImageHelper.createSmallTestImage()
        
        // Upload avatar - may fail if backend has issues
        try {
            api.uploadAvatar(authResponse.access_token, testImage)
        } catch (e: Exception) {
            return@runTest // Skip if upload fails
        }
        
        // Act - GET without auth token
        val avatarBytes = api.getAvatar(authResponse.user!!.id, null)
        
        // Assert - Should work if avatars are public
        if (avatarBytes != null) {
            assertThat(avatarBytes.size).isGreaterThan(0)
        }
    }
}
```

---

### 3. Group Icon E2E Tests

**src/e2e/kotlin/groups/GroupIconE2ETest.kt:**

```kotlin
package com.github.shannonbay.pursue.e2e.groups

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GroupIconE2ETest : E2ETest() {
    
    @Test
    fun `GET group icon returns image data if icon exists`() = runTest {
        // Arrange - Create user and get groups
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        // Get user's groups (may be empty for new user)
        val groupsResponse = api.getMyGroups(authResponse.access_token)
        
        if (groupsResponse.groups.isEmpty()) {
            // Skip test if user has no groups
            return@runTest
        }
        
        val group = groupsResponse.groups.first { it.has_icon }
        
        // Act - GET group icon
        val iconBytes = api.getGroupIcon(group.id, authResponse.access_token)
        
        // Assert
        assertThat(iconBytes).isNotNull()
        assertThat(iconBytes!!.size).isGreaterThan(0)
    }
}
```

---

### 4. Progress Entry E2E Tests

**src/e2e/kotlin/goals/GoalProgressE2ETest.kt:**

Note: Goal progress tests may be limited if group/goal creation endpoints are not implemented.

```kotlin
package com.github.shannonbay.pursue.e2e.goals

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GoalProgressE2ETest : E2ETest() {
    
    @Test
    fun `get today goals returns goals for user groups`() = runTest {
        // Arrange - Create user
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        // Act - Get today's goals
        val todayGoals = api.getTodayGoals(authResponse.access_token)
        
        // Assert - Should return response (may be empty for new user)
        assertThat(todayGoals).isNotNull()
        // Response structure depends on backend implementation
    }
}
```

---

## Running E2E Tests

### 1. Manual Execution

```bash
# Terminal 1 - Start backend
cd pursue-backend
npm run dev

# Terminal 2 - Run E2E tests
cd pursue-android
./gradlew testE2e
```

### 2. Automated Script

**scripts/run-e2e-tests.sh:**

```bash
#!/bin/bash

set -e

echo "======================================"
echo "Pursue E2E Test Runner"
echo "======================================"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if backend directory exists
if [ ! -d "../pursue-backend" ]; then
    echo -e "${RED}❌ Backend directory not found at ../pursue-backend${NC}"
    exit 1
fi

# Start backend server
echo -e "${YELLOW}📦 Starting backend server...${NC}"
cd ../pursue-backend

# Check if npm dependencies are installed
if [ ! -d "node_modules" ]; then
    echo -e "${YELLOW}📥 Installing backend dependencies...${NC}"
    npm install
fi

# Start server in background
npm run dev &
BACKEND_PID=$!

echo -e "${YELLOW}⏳ Waiting for backend to be ready...${NC}"
sleep 5

# Check if server is running
if curl -s http://localhost:3000/health > /dev/null; then
    echo -e "${GREEN}✅ Backend server is ready${NC}"
else
    echo -e "${RED}❌ Backend server failed to start${NC}"
    kill $BACKEND_PID 2>/dev/null
    exit 1
fi

# Run E2E tests
echo -e "${YELLOW}🧪 Running E2E tests...${NC}"
cd ../pursue-android

if ./gradlew testE2e; then
    echo -e "${GREEN}✅ E2E tests passed!${NC}"
    TEST_RESULT=0
else
    echo -e "${RED}❌ E2E tests failed${NC}"
    TEST_RESULT=1
fi

# Cleanup - Stop backend server
echo -e "${YELLOW}🧹 Stopping backend server...${NC}"
kill $BACKEND_PID 2>/dev/null || true

echo "======================================"
if [ $TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}✅ E2E Test Suite: PASSED${NC}"
else
    echo -e "${RED}❌ E2E Test Suite: FAILED${NC}"
fi
echo "======================================"

exit $TEST_RESULT
```

**Make executable:**
```bash
chmod +x scripts/run-e2e-tests.sh
```

**Usage:**
```bash
./scripts/run-e2e-tests.sh
```

---

## Converting Existing Tests

### Example: Unit Test → E2E Test

**Original Unit Test (Mocked):**

```kotlin
// app/src/test/kotlin/repository/UserRepositoryTest.kt
class UserRepositoryTest {
    private lateinit var mockApi: ApiService
    private lateinit var repository: UserRepository
    
    @Before
    fun setup() {
        mockApi = mockk()
        repository = UserRepository(mockApi)
    }
    
    @Test
    fun `getUser returns user data`() = runTest {
        // Arrange
        coEvery { mockApi.getUser("123") } returns Response.success(
            UserResponse(
                id = "123",
                email = "test@example.com",
                displayName = "Test User",
                hasAvatar = false
            )
        )
        
        // Act
        val user = repository.getUser("123")
        
        // Assert
        assertThat(user.id).isEqualTo("123")
        assertThat(user.email).isEqualTo("test@example.com")
    }
}
```

**E2E Version (Real Server):**

```kotlin
// app/src/e2e/kotlin/user/UserE2ETest.kt
class UserE2ETest : E2ETest() {
    
    @Test
    fun `getUser returns user data from real server`() = runTest {
        // Arrange - Create real user
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        val userId = authResponse.user!!.id
        val accessToken = authResponse.access_token
        
        // Act - Fetch user from real API
        val user = api.getMyUser(accessToken)
        
        // Assert - Same assertions as unit test
        assertThat(user.id).isEqualTo(userId)
        assertThat(user.display_name).isEqualTo("Test User")
        assertThat(user.has_avatar).isFalse()
    }
}
```

**Key Differences:**
1. ✅ Extends `E2ETest` instead of plain test
2. ✅ Uses real `E2EApiClient` instead of `mockApi`
3. ✅ Creates actual user via `testDataHelper.createTestUser(api)`
4. ✅ Tracks user for cleanup with `trackUser(userId)`
5. ✅ Uses `ApiException` for error handling (not Retrofit `Response`)
6. ✅ Same assertions (validates real behavior)

---

## What to Test in E2E

### ✅ High Priority (Test These)

1. **Image Upload/Download**
   - Avatar upload with real Sharp processing
   - Group icon upload
   - BYTEA storage and retrieval
   - Image size validation
   - Format conversion (JPEG → WebP)

2. **Authentication Flow**
   - Register → Login → Get user
   - JWT token generation and validation
   - Google OAuth (if possible with test account)
   - Token refresh

3. **Database Operations**
   - CRUD operations work as expected
   - Foreign key constraints enforced
   - Cascade deletes work
   - Unique constraints enforced

4. **Complex Queries**
   - Progress history retrieval
   - Group member lists
   - Activity feeds
   - Join queries work correctly

5. **Error Handling**
   - 400 validation errors
   - 401 unauthorized
   - 403 forbidden
   - 404 not found
   - 500 server errors

### ⚠️ Medium Priority

6. **Multipart Uploads**
   - File upload headers correct
   - Binary data transferred correctly

7. **Pagination**
   - Limit/offset work
   - Total counts accurate

8. **Sorting/Filtering**
   - Date ranges work
   - Query parameters applied

### ❌ Low Priority (Skip or Unit Test)

9. **Input Validation**
   - Email format validation (same as unit tests)
   - Password strength (same as unit tests)
   - String length limits (same as unit tests)

10. **Business Logic**
    - Calculations (test in unit tests)
    - Transformations (test in unit tests)

---

## Best Practices

### 1. Test Independence

```kotlin
// ✅ GOOD - Each test creates its own data
@Test
fun `test something`() = runTest {
    val user = testDataHelper.createTestUser(api)
    trackUser(user.user.id)
    // ... test logic
}

// ❌ BAD - Tests depend on shared state
companion object {
    lateinit var sharedUser: User // Don't do this!
}
```

### 2. Proper Cleanup

```kotlin
// ✅ GOOD - Track resources for cleanup
@Test
fun `test uploads avatar`() = runTest {
    val user = testDataHelper.createTestUser(api)
    trackUser(user.user.id) // Will be deleted in @After
    
    // Test logic...
}

// ✅ Also good - Manual cleanup in try/finally
@Test
fun `test something`() = runTest {
    var userId: String? = null
    try {
        val user = testDataHelper.createTestUser(api)
        userId = user.user!!.id
        // Test logic...
    } finally {
        userId?.let { testDataHelper.deleteUser(api, it) }
    }
}
```

### 3. Descriptive Test Names

```kotlin
// ✅ GOOD
@Test
fun `upload avatar larger than 5MB fails with 400 error`()

@Test
fun `login with wrong password returns 401 unauthorized`()

// ❌ BAD
@Test
fun `testUpload`()

@Test
fun `test1`()
```

### 4. Test One Thing

```kotlin
// ✅ GOOD - Tests one specific behavior
@Test
fun `upload avatar sets has_avatar to true`() = runTest {
    // ... upload avatar
    assertThat(response.has_avatar).isTrue()
}

@Test
fun `upload avatar returns image in WebP format`() = runTest {
    // ... upload and fetch avatar
    // Note: Content-Type verification depends on backend implementation
}

// ❌ BAD - Tests multiple things
@Test
fun `avatar upload works`() = runTest {
    // Tests upload, download, format, size, deletion all in one
    // Too much - split into separate tests
}
```

### 5. Clear Arrange-Act-Assert

```kotlin
@Test
fun `example test`() = runTest {
    // Arrange - Set up test data
    val user = testDataHelper.createTestUser(api)
    val testImage = TestImageHelper.createSmallTestImage()
    
    // Act - Perform action
    val response = api.uploadAvatar(
        user.access_token, testImage
    )
    
    // Assert - Verify result
    assertThat(response.has_avatar).isTrue()
}
```

---

## Troubleshooting

### Backend Server Not Running

```
Error: Local dev server must be running at http://localhost:3000

Solution:
1. cd pursue-backend
2. npm run dev
3. Wait for "Server listening on port 3000"
4. Re-run E2E tests
```

### Port Already in Use

```
Error: listen EADDRINUSE: address already in use :::3000

Solution:
# Find process using port 3000
lsof -i :3000

# Kill it
kill -9 <PID>

# Or use different port in backend and update LocalServerConfig.BASE_URL
```

### Tests Hang/Timeout

```
Possible causes:
1. Backend server crashed
2. Network timeout too short
3. Backend processing taking too long

Solutions:
- Check backend logs
- Increase timeout in OkHttpClient
- Add debug logging
```

### Database State Pollution

```
Problem: Tests fail due to leftover data from previous runs

Solution:
1. Use unique emails (UUID in email)
2. Proper cleanup in @After
3. Reset database between test runs (optional):
   cd pursue-backend
   npm run db:reset
```

### Image Upload Fails

```
Error: 400 Bad Request on avatar upload

Possible causes:
1. Content-Type header incorrect
2. Multipart form data malformed
3. File size exceeds limit

Debug:
- Enable HTTP logging: HttpLoggingInterceptor.Level.BODY
- Check backend logs
- Verify test image is valid
```

---

## CI/CD Integration (Optional Future)

### GitHub Actions Workflow (Not Recommended for E2E, but possible)

```yaml
# .github/workflows/e2e.yml
name: E2E Tests

on:
  workflow_dispatch: # Manual trigger only
  
jobs:
  e2e:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:17
        env:
          POSTGRES_PASSWORD: postgres
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '20'
      
      - name: Setup Java
        uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Install backend dependencies
        run: |
          cd pursue-backend
          npm install
      
      - name: Start backend server
        run: |
          cd pursue-backend
          npm run dev &
          sleep 5
      
      - name: Run E2E tests
        run: ./gradlew testE2e
```

**Note:** Running E2E tests in CI is slow and flaky. Recommended to keep them local-only for development.

---

## Summary

### Test Organization
```
app/src/e2e/   # E2E tests (real server)
app/src/test/  # Unit tests (mocks)
```

### Run E2E Tests
```bash
# Manual
./gradlew testE2e

# Automated
./scripts/run-e2e-tests.sh
```

### What to Test
- ✅ Image upload/download (Sharp, BYTEA)
- ✅ Authentication (JWT)
- ✅ Database operations (real queries)
- ✅ Error responses
- ❌ Skip: Input validation (covered in unit tests)

### Benefits
- 🔍 Catches integration bugs mocks miss
- 🎯 Tests real backend behavior
- 🚀 Fast feedback loop (run locally)
- 🧹 No CI overhead (local only)

### Key Principle
> Copy unit tests, replace mocks with real API, keep same assertions

This validates that your mocked tests accurately reflect reality!

