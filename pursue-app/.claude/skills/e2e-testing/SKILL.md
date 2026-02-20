---
name: e2e-testing
description: This skill should be used when the user asks to "write E2E tests", "create E2E tests", "test backend integration", "write end-to-end tests", or needs to write E2E tests for the Pursue Android app that verify integration with the real backend server following project-specific conventions documented in E2ETESTING.md.
---

# E2E Testing for Pursue Android App

Write E2E (end-to-end) tests for the Pursue Android app that contact the backend server. These tests verify integration with a real backend running at `localhost:3000`.

**Reference**: See `E2ETESTING.md` for comprehensive documentation of all E2E testing learnings, troubleshooting guides, and detailed explanations.

## Testing Stack

- **JUnit 4** - Test framework
- **OkHttp** - HTTP client for making network requests
- **OkHttp Logging Interceptor** - HTTP request/response logging for debugging
- **Gson** - JSON serialization/deserialization
- **Truth** - Assertion library
- **kotlinx-coroutines-test** - Coroutine testing utilities

## Core Testing Patterns

### 1. E2E Test Package Structure

Organize E2E tests under the `com.github.shannonbay.pursue.e2e.*` package:
- `com.github.shannonbay.pursue.e2e.config` - E2E test base classes and API client
- `com.github.shannonbay.pursue.e2e.user` - User-related E2E tests
- `com.github.shannonbay.pursue.e2e.auth` - Authentication E2E tests
- `com.github.shannonbay.pursue.e2e.groups` - Group-related E2E tests
- `com.github.shannonbay.pursue.e2e.helpers` - Test helper utilities

When running specific tests, use the full package path:
```bash
./gradlew :app:testE2e --tests "com.github.shannonbay.pursue.e2e.user.AvatarUploadE2ETest"
```

### 2. Extend E2ETest Base Class

Extend `E2ETest` which provides:
- Server availability check before running tests
- E2E API client setup
- Automatic cleanup of created test data

```kotlin
import com.github.shannonbay.pursue.e2e.config.E2ETest

class MyE2ETest : E2ETest() {
    @Test
    fun `test something`() = runTest {
        // Test implementation
    }
}
```

**Key Learning**: The base class uses `@BeforeClass` to check server availability once per test class. If the server is unavailable, all tests are skipped with `assumeTrue`, providing clear feedback without test failures.

### 3. Server Availability Check

The `E2ETest` base class automatically checks server availability using `LocalServerConfig.isServerAvailable()`. The server should be running at `http://localhost:3000`.

**Prerequisites**: Start the backend server before running E2E tests:
```bash
cd backend
npm run dev
```

**Key Learning**: `assumeTrue` skips all tests in the class if the server is unavailable, providing clear feedback without test failures.

### 4. Using E2EApiClient

Use the `E2EApiClient` for making HTTP requests to the local dev server:

```kotlin
class MyE2ETest : E2ETest() {
    @Test
    fun `test API call`() = runTest {
        // Create test user
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        // Make API call
        val response = api.getMyGroups(authResponse.access_token)
        
        // Assert
        assertThat(response.groups).isNotEmpty()
    }
}
```

**Key Learning**: The `api` property is automatically initialized by `E2ETest`. Use `testDataHelper` for creating test data and `trackUser()`/`trackGroup()` for automatic cleanup.

### 5. Test Data Management

Use `TestDataHelper` for creating test data and track resources for cleanup:

```kotlin
@Test
fun `test with test data`() = runTest {
    // Create test user
    val authResponse = testDataHelper.createTestUser(api)
    trackUser(authResponse.user!!.id)  // Track for cleanup
    
    // Create test group
    val group = testDataHelper.createTestGroup(
        api = api,
        accessToken = authResponse.access_token,
        name = "Test Group"
    )
    trackGroup(group.id)  // Track for cleanup
    
    // Test implementation...
}
```

**Key Learning**: Always track created resources with `trackUser()` and `trackGroup()` so the base class can attempt cleanup in `@After`. Cleanup may fail if deletion endpoints don't exist - this is handled gracefully.

### 6. Test Image Generation

Android Bitmap APIs are not available in the test environment. Generate minimal valid JPEG byte arrays directly:

```kotlin
object TestImageHelper {
    fun createTestImage(
        width: Int = 512,
        height: Int = 512
    ): ByteArray {
        // Use base64-encoded valid JPEG
        val base64Jpeg = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxAPwA/8A"
        return Base64.getDecoder().decode(base64Jpeg)
    }
}
```

**Key Learning**: E2E tests run in a JVM environment without full Android graphics support. Generate raw image bytes instead of using Bitmap APIs. Use base64-encoded valid JPEG strings for reliability.

### 7. Marking Tests @Ignore When Backend Not Implemented

If backend endpoints aren't implemented yet, mark tests as `@Ignore` with clear messages:

```kotlin
@Test
@Ignore("Backend endpoint may not be implemented (returns 500 errors)")
fun `test endpoint`() = runTest {
    // Test implementation
}
```

**Key Learning**: E2E tests verify integration with a real backend. If endpoints return 500, the backend may not be implemented. Document this clearly and re-enable tests once backend is ready.

### 8. Handling Test Data Cleanup

Deletion endpoints may not exist. Track created resources and attempt cleanup, but handle failures gracefully:

```kotlin
@After
fun cleanup() {
    runBlocking {
        createdUserIds.forEach { userId ->
            try {
                testDataHelper.deleteUser(api, userId)
            } catch (e: Exception) {
                println("⚠️  User deletion not implemented - user $userId will remain in database")
            }
        }
    }
}
```

**Key Learning**: E2E tests create real data. Cleanup is important but may not be possible if backend doesn't provide deletion endpoints. Document this clearly with warning messages.

### 9. Running E2E Tests

**Windows PowerShell** - Set JAVA_HOME in session:
```powershell
$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
./gradlew :app:testE2e --tests "com.github.shannonbay.pursue.e2e.user.AvatarUploadE2ETest" --no-daemon
```

**Key Learning**: PowerShell session environment variables don't automatically inherit system environment variables. Use `[System.Environment]::GetEnvironmentVariable()` to read from the Machine scope.

## Test Structure Template

```kotlin
package com.github.shannonbay.pursue.e2e.user

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for user-related endpoints.
 * 
 * Tests verify integration with real backend server at localhost:3000.
 */
class UserE2ETest : E2ETest() {
    
    @Test
    fun `test user endpoint`() = runTest {
        // Arrange
        val authResponse = testDataHelper.createTestUser(api)
        trackUser(authResponse.user!!.id)
        
        // Act
        val response = api.getMyUser(authResponse.access_token)
        
        // Assert
        assertThat(response.id).isEqualTo(authResponse.user!!.id)
        assertThat(response.email).isNotEmpty()
    }
}
```

## Common Patterns

### Testing Authentication Flow

```kotlin
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
    
    // Cleanup
    trackUser(response.user!!.id)
}
```

### Testing Error Handling

```kotlin
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
```

### Testing File Upload

```kotlin
@Test
fun `upload avatar stores image`() = runTest {
    // Arrange
    val authResponse = testDataHelper.createTestUser(api)
    trackUser(authResponse.user!!.id)
    
    val testImage = TestImageHelper.createTestImage()
    
    // Act
    val uploadResponse = api.uploadAvatar(authResponse.access_token, testImage)
    
    // Assert
    assertThat(uploadResponse.has_avatar).isTrue()
    
    // Verify can fetch avatar
    val avatarBytes = api.getAvatar(authResponse.user!!.id, authResponse.access_token)
    assertThat(avatarBytes).isNotNull()
    assertThat(avatarBytes!!.size).isGreaterThan(0)
}
```

### Testing with Multiple Resources

```kotlin
@Test
fun `test multiple resources`() = runTest {
    // Arrange
    val authResponse = testDataHelper.createTestUser(api)
    trackUser(authResponse.user!!.id)
    
    // Create multiple groups
    val group1 = testDataHelper.createTestGroup(api, authResponse.access_token, "Group 1")
    trackGroup(group1.id)
    
    val group2 = testDataHelper.createTestGroup(api, authResponse.access_token, "Group 2")
    trackGroup(group2.id)
    
    // Act
    val groupsResponse = api.getMyGroups(authResponse.access_token)
    
    // Assert
    assertThat(groupsResponse.groups).hasSize(2)
}
```

## Anti-Patterns / What Not to Do

❌ **Don't use Android Bitmap APIs** - `Bitmap.createBitmap()`, `Bitmap.compress()`, and `java.awt.Color` are not mocked in test environment. Generate raw image bytes instead.

❌ **Don't create custom source sets** - AGP only recognizes `main`, `test`, and `androidTest`. Add E2E sources to the existing `test` source set.

❌ **Don't put E2E dependencies in `e2eImplementation` only** - All E2E dependencies must be in `testImplementation` since E2E sources compile with the `test` source set.

❌ **Don't forget to track resources** - Always use `trackUser()` and `trackGroup()` so cleanup can be attempted, even if deletion endpoints don't exist.

❌ **Don't run tests without server running** - The base class will skip tests, but it's better to start the server first to get meaningful results.

❌ **Don't ignore cleanup failures silently** - Print warning messages when cleanup fails so it's clear that test data remains in the database.

## Key Learnings Summary

### Critical Gotchas

- **JAVA_HOME in PowerShell**: Set from system environment: `$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")`
- **Package Structure**: Use full package path when running specific tests: `com.github.shannonbay.pursue.e2e.user.AvatarUploadE2ETest`
- **Image Generation**: Use base64-encoded valid JPEG strings, not Android Bitmap APIs
- **Test Data Cleanup**: Deletion endpoints may not exist - handle gracefully with warning messages
- **Server Availability**: Base class checks automatically - tests are skipped if server unavailable
- **Dependencies**: All E2E dependencies must be in `testImplementation`, not just `e2eImplementation`

### Testing Limitations

- **Backend Dependencies**: E2E tests require a running backend server at `localhost:3000`
- **Test Data Persistence**: Created test data may remain in database if deletion endpoints don't exist
- **Image Processing**: Cannot use Android graphics APIs - must generate raw image bytes
- **Network Timeouts**: Use appropriate timeouts in `E2EApiClient` (10s connect, 10s read, 30s write for uploads)

## Documentation Maintenance

**At the end of each E2E testing session**: Update `E2ETESTING.md` with any new failed patterns discovered and their solutions.

### Documentation Format

When documenting a new pattern in `E2ETESTING.md`:

1. Add as a new section with descriptive heading (e.g., "## New Pattern Name")
2. Include:
   - **Problem**: Description of the issue encountered
   - **Solution**: Code examples showing the fix
   - **Key Learning**: Concise summary of the takeaway
3. Keep documentation concise - only record what an AI Agent wouldn't already know
4. Reference related sections if applicable

### Example Documentation

```markdown
## New Pattern Name

**Problem**: Brief description of the issue encountered during E2E testing.

**Solution**: 
```kotlin
// Code example showing the fix
```

**Key Learning**: 
- Concise summary of the takeaway
- Additional important points
```

This ensures `E2ETESTING.md` stays current with project-specific E2E testing learnings and helps future AI assistants avoid the same pitfalls when writing E2E tests.

## References

- **Full Documentation**: See `E2ETESTING.md` for comprehensive E2E testing guide with all documented patterns
- **OkHttp**: https://square.github.io/okhttp/
- **Truth**: https://truth.dev/
- **Kotlin Coroutines Testing**: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/
