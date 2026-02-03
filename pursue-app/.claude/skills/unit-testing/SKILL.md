---
name: unit-testing
description: This skill should be used when the user asks to "write unit tests", "create unit tests", "test fragments", "test Android components", or needs to write unit tests for the Pursue Android app following project-specific conventions documented in TESTING.md.
---

# Unit Testing for Pursue Android App

Write unit tests for the Pursue Android app following project-specific conventions and patterns documented in `TESTING.md`.

**Reference**: See `TESTING.md` for comprehensive documentation of all testing learnings, troubleshooting guides, and detailed explanations.

## Testing Stack

- **JUnit 4** - Test framework
- **MockK** - Mocking library for Kotlin
- **Robolectric** - Android unit testing framework (runs Android code on JVM)
- **kotlinx-coroutines-test** - Coroutine testing utilities
- **Truth** - Assertion library (for E2E tests)

## Core Testing Patterns

### 1. Robolectric Configuration

Always include these annotations and configuration:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = android.app.Application::class,
    packageName = "com.github.shannonbay.pursue"  // Required for resource loading
)
```

Also required in `build.gradle.kts`:
```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
    }
}
```

**Key Learning**: Robolectric needs explicit package name configuration to load Android resources correctly.

### 2. Fragment Testing Setup

Use `Robolectric.setupActivity()` instead of `FragmentScenario`:

```kotlin
private fun launchFragment() {
    activity = Robolectric.setupActivity(FragmentActivity::class.java)
    
    fragment = MyFragment.newInstance()
    
    // Add fragment to activity
    activity.supportFragmentManager.beginTransaction()
        .add(fragment, "test")
        .commitNow()
    
    // Set callbacks AFTER onAttach is called (via reflection)
    val field = MyFragment::class.java.getDeclaredField("callbacks")
    field.isAccessible = true
    field.set(fragment, mockCallbacks)
    
    // Ensure fragment is in RESUMED state for lifecycleScope
    activity.supportFragmentManager.beginTransaction()
        .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        .commitNow()
}
```

**Key Learning**: 
- Use `Robolectric.setupActivity()` instead of `FragmentScenario` for better resource loading
- Set callbacks via reflection AFTER `onAttach()` is called, otherwise they'll be null
- Ensure fragment lifecycle is RESUMED for `lifecycleScope` to work

### 3. Coroutine Testing

Use `UnconfinedTestDispatcher` and advance coroutines properly:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyFragmentTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }
    
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    @Test
    fun `test example`() = runTest(testDispatcher) {
        // Trigger coroutine
        clickButton()
        
        // Advance test dispatcher AND main looper (for lifecycleScope)
        advanceCoroutines()
    }
    
    private fun TestScope.advanceCoroutines() {
        // Use test scope's advanceUntilIdle (not testDispatcher.scheduler.advanceUntilIdle)
        advanceUntilIdle()  // ✅ Correct - tracks all coroutines including lifecycleScope
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        // Sometimes need multiple passes (3+ iterations)
    }
}
```

**Key Learning**: 
- `lifecycleScope` uses `Dispatchers.Main`, which must be tracked by the test scope
- Use `advanceUntilIdle()` from the test scope, not `testDispatcher.scheduler.advanceUntilIdle()`
- Need to idle the main looper multiple times to ensure all coroutines complete
- Mock suspend functions return immediately, but the coroutine still needs to complete

### 4. MockK Patterns

Use `coEvery`/`coVerify` for suspend functions and `mockkObject` for singletons:

```kotlin
@Before
fun setUp() {
    // Mock singleton
    mockkObject(SecureTokenManager.Companion)
    mockTokenManager = mockk(relaxed = true)
    every { SecureTokenManager.getInstance(any()) } returns mockTokenManager

    // Mock object methods
    mockkObject(ApiClient)
    
    // Setup mock responses for suspend functions
    coEvery { ApiClient.register(any(), any(), any()) } returns response
}

@After
fun tearDown() {
    unmockkAll()
}

// In tests
coEvery { ApiClient.getData(any()) } returns response
coVerify { ApiClient.getData(any()) }

// Property mocking requires explicit this@mockk
return mockk<Response>(relaxed = true) {
    every { this@mockk.code } returns 401  // ✅ Correct
    every { this@mockk.isSuccessful } returns false
}
```

**Key Learning**:
- Use `coEvery`/`coVerify` for suspend functions
- Use `mockkObject()` for singleton objects
- MockK requires explicit `this@mockk` reference for property mocking inside `mockk { }` blocks
- Always call `unmockkAll()` in `@After` to clean up

### 5. Toast Verification

Use Robolectric's `ShadowToast`, but be aware of limitations:

```kotlin
// Verify toast was shown
val toast = ShadowToast.getLatestToast()
assertNotNull("Toast should be shown", toast)

// Verify toast text
assertEquals("Account created!", ShadowToast.getTextOfLatestToast())

// Verify toast was shown (returns boolean)
assertTrue("Toast should be shown", ShadowToast.showedToast("Account created!"))

@After
fun tearDown() {
    ShadowToast.reset()
}
```

**Limitations with UnconfinedTestDispatcher**:
- Toast verification for generic `Exception` fails with `UnconfinedTestDispatcher` (see TESTING.md section 29)
- `ApiException` toast verification works, but generic `Exception` toast verification doesn't
- When toast verification is unreliable, verify the error handling code path through UI state checks instead:
  ```kotlin
  // Verify error handling code path executed (not toast)
  assertEquals("Loading indicator should be hidden", View.GONE, loadingIndicator?.visibility)
  assertTrue("Form fields should be re-enabled", groupNameEdit?.isEnabled ?: false)
  coVerify(exactly = 1) { ApiClient.createGroup(any(), any(), any(), any(), any()) }
  ```

### 6. Handler.post Pattern

Use `Handler(Looper.getMainLooper()).post { }` for UI operations after `withContext(Dispatchers.IO)`:

```kotlin
// In coroutine after withContext(Dispatchers.IO)
val response = withContext(Dispatchers.IO) {
    ApiClient.uploadAvatar(accessToken, imageFile)
}

// Wrap all UI operations in Handler.post
Handler(Looper.getMainLooper()).post {
    // UI operations that require looper thread
    removeAvatarButton.visibility = View.VISIBLE
    loadAvatar(user)
    Toast.makeText(requireContext(), getString(R.string.success), Toast.LENGTH_SHORT).show()
}

// Also wrap in finally blocks
finally {
    Handler(Looper.getMainLooper()).post {
        swipeRefreshLayout.isRefreshing = false
    }
}
```

**Key Learning**:
- `Handler(Looper.getMainLooper()).post { }` ensures execution on the actual main looper thread, not the test dispatcher
- Use this pattern for: Toast, MaterialButton animations (via `setEnabled`), SwipeRefreshLayout operations, any View visibility/state changes after `withContext(Dispatchers.IO)`
- In tests, idle the looper multiple times after triggering operations: `shadowOf(Looper.getMainLooper()).idle()` multiple times

### 7. CI Test Skipping

Use `Assume.assumeFalse()` to skip flaky tests in CI:

```kotlin
/**
 * Skip tests in CI environments due to timing issues.
 */
private fun skipInCI() {
    Assume.assumeFalse(
        "Skipping test in CI due to timing issues with UnconfinedTestDispatcher",
        System.getenv("CI") == "true"
    )
}

@Test
fun `test flaky test`() = runTest(testDispatcher) {
    skipInCI()
    
    // Test implementation...
}
```

**Key Learning**:
- Use `Assume.assumeFalse()` to skip tests conditionally based on environment
- GitHub Actions sets `CI=true` environment variable
- Skipped tests show as "skipped" not "failed" in test results
- Only use this when all reasonable fixes have been exhausted and the test is genuinely flaky in CI
- Document why the test is skipped in the assumption message

## Test Structure Template

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = android.app.Application::class,
    packageName = "com.github.shannonbay.pursue"
)
@OptIn(ExperimentalCoroutinesApi::class)
class MyFragmentTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Mock ApiClient to prevent real network calls
        mockkObject(ApiClient)
        // ... other mocks
    }
    
    @After
    fun tearDown() {
        ShadowToast.reset()
        shadowOf(Looper.getMainLooper()).idle()
        unmockkAll()
        Dispatchers.resetMain()
    }
    
    @Test
    fun `test descriptive test name`() = runTest(testDispatcher) {
        // Given
        coEvery { ApiClient.getData(any()) } returns response
        
        // When
        launchFragment()
        clickButton()
        advanceCoroutines()
        
        // Then
        verify { callbacks.onSuccess() }
    }
    
    private fun TestScope.advanceCoroutines() {
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
```

## Common Patterns

### Fragment Testing with Coroutines

```kotlin
@Test
fun `test fragment coroutine flow`() = runTest(testDispatcher) {
    // Given
    coEvery { ApiClient.getData(any()) } returns response
    
    launchFragment()
    fillForm()
    
    // When
    clickButton()
    advanceCoroutines()
    
    // Then
    verify { callbacks.onSuccess() }
}
```

### Error Handling Tests

```kotlin
@Test
fun `test error handling`() = runTest(testDispatcher) {
    // Given
    val exception = MockApiClient.createApiException(500, "Server error")
    coEvery { ApiClient.getData(any()) } throws exception
    
    launchFragment()
    fillForm()
    
    // When
    clickButton()
    advanceCoroutines()
    
    // Then
    verify(exactly = 0) { callbacks.onSuccess() }
    // For ApiException, toast verification works:
    assertTrue("Error toast shown", 
        ShadowToast.getTextOfLatestToast().contains("error"))
}
```

### State-Based UI Pattern Testing

For fragments with 5-state UI patterns (Loading, Success-With Data, Success-Empty, Error, Offline-Cached):

```kotlin
private fun verifySuccessStateWithData(expectedCount: Int) {
    val skeletonContainer = fragment.view?.findViewById<LinearLayout>(R.id.skeleton_container)
    val recyclerView = fragment.view?.findViewById<RecyclerView>(R.id.groups_recycler_view)
    
    // Add null checks for better error messages
    assertNotNull("Skeleton container should exist", skeletonContainer)
    assertNotNull("RecyclerView should exist", recyclerView)
    
    assertEquals("Skeleton should be hidden", View.GONE, skeletonContainer?.visibility)
    assertEquals("RecyclerView should be visible", View.VISIBLE, recyclerView?.visibility)
    assertEquals("RecyclerView should have correct item count", expectedCount, recyclerView?.adapter?.itemCount)
}

// Verify internal state first (more reliable than view visibility)
private fun verifyEmptyState() {
    val currentStateField = HomeFragment::class.java.getDeclaredField("currentState")
    currentStateField.isAccessible = true
    val currentState = currentStateField.get(fragment) as HomeFragment.GroupsUiState
    assertEquals("Fragment should be in SUCCESS_EMPTY state", 
        HomeFragment.GroupsUiState.SUCCESS_EMPTY, currentState)
    
    // Then verify view visibility
    // ...
}
```

## Anti-Patterns / What Not to Do

❌ **Don't use `FragmentScenario` with Robolectric** - Use `Robolectric.setupActivity()` instead

❌ **Don't mock methods on real objects** - Only mock methods on mock objects created with `mockk<T>()`. Real objects (like OkHttp `Request`, `Response`) work directly without mocking.

❌ **Don't use `testDispatcher.scheduler.advanceUntilIdle()` directly** - Use the test scope's `advanceUntilIdle()` method instead, which properly tracks `lifecycleScope` coroutines.

❌ **Don't test error/offline states with `UnconfinedTestDispatcher`** - Toast verification fails due to threading issues. Consider instrumented tests or verify UI state instead.

❌ **Don't forget to use `Handler.post` for UI operations after IO context** - UI operations after `withContext(Dispatchers.IO)` must be wrapped in `Handler(Looper.getMainLooper()).post { }`.

❌ **Don't use `apply()` for SharedPreferences in tests** - Use `commit()` for synchronous writes, or idle looper multiple times after `apply()`.

❌ **Don't use specific context instances in verifications** - Use `any()` matcher for context parameters since `requireContext()` returns a different instance.

## Helper Method Patterns

### advanceCoroutines() Extension

```kotlin
private fun TestScope.advanceCoroutines() {
    // Use test scope's advanceUntilIdle (not testDispatcher.scheduler.advanceUntilIdle)
    advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
    advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
    advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
}
```

### launchFragment() Helper

```kotlin
private fun launchFragment() {
    activity = Robolectric.setupActivity(FragmentActivity::class.java)
    fragment = MyFragment.newInstance()
    
    activity.supportFragmentManager.beginTransaction()
        .add(fragment, "test")
        .commitNow()
    
    // Set callbacks via reflection AFTER onAttach
    val field = MyFragment::class.java.getDeclaredField("callbacks")
    field.isAccessible = true
    field.set(fragment, mockCallbacks)
    
    // Ensure RESUMED state
    activity.supportFragmentManager.beginTransaction()
        .setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        .commitNow()
}
```

### skipInCI() Helper

```kotlin
/**
 * Skip tests in CI environments due to timing issues.
 */
private fun skipInCI() {
    Assume.assumeFalse(
        "Skipping test in CI due to timing issues with UnconfinedTestDispatcher",
        System.getenv("CI") == "true"
    )
}
```

## Key Learnings Summary

### Critical Gotchas

- **Float/Double Type Mismatches**: Convert Float to Double with delta: `assertEquals(..., 0.6f.toDouble(), actual?.toDouble() ?: 0.0, 0.01)`
- **Context Parameter Verification**: Use `any()` matcher for context parameters, not specific instances
- **Singleton Reset**: Reset singletons via reflection using the **outer class**, not the Companion class: `MyManager::class.java.getDeclaredField("INSTANCE")`
- **SharedPreferences**: Use `commit()` instead of `apply()` in tests, or idle looper multiple times
- **View Visibility**: Internal state updates synchronously, view visibility updates may be queued - check internal state first
- **ResponseBody.create()**: Use `ResponseBody.create(MediaType, content)` order (MediaType first) for OkHttp 4.x

### Testing Limitations

- **Android Keystore**: Not available in Robolectric - use instrumented tests or mock `SecureTokenManager`
- **UnconfinedTestDispatcher**: Best for happy paths and loading states. Avoid testing code paths that show Toast or manipulate SwipeRefreshLayout after `withContext(Dispatchers.IO)`
- **Toast Verification**: Works for `ApiException` but unreliable for generic `Exception` with `UnconfinedTestDispatcher`

## Documentation Maintenance

**At the end of each testing session**: Update `TESTING.md` with any new flawed patterns discovered and their solutions.

### Documentation Format

When documenting a new pattern in `TESTING.md`:

1. Add as a new numbered section (e.g., "### 30. [New Pattern Name]")
2. Include:
   - **Problem**: Description of the issue encountered
   - **Solution**: Code examples showing the fix
   - **Key Learning**: Concise summary of the takeaway
3. Keep documentation concise - only record what an AI Agent wouldn't already know
4. Reference specific sections if related to existing patterns

### Example Documentation

```markdown
### 30. New Pattern Name

**Problem**: Brief description of the issue.

**Solution**: 
```kotlin
// Code example showing the fix
```

**Key Learning**: 
- Concise summary of the takeaway
- Additional important points
```

This ensures `TESTING.md` stays current with project-specific learnings and helps future AI assistants avoid the same pitfalls.

## Running Tests

### Windows PowerShell

When running Gradle commands in PowerShell, use this pattern:

```powershell
# Set JAVA_HOME and run tests
$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
if (Test-Path ".\gradlew.bat") {
    & ".\gradlew.bat" testDebugUnitTest --tests "com.github.shannonbay.pursue.GroupDetailFragmentTest" --no-daemon 2>&1 | Select-Object -First 250
} else {
    Write-Host "gradlew.bat not found"
}

# Run all tests
$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
if (Test-Path ".\gradlew.bat") {
    & ".\gradlew.bat" testDebugUnitTest --no-daemon 2>&1 | Select-Object -First 250
}
```

**Key Learning**: 
- Use `& ".\gradlew.bat"` to invoke the batch file in PowerShell
- Use `Test-Path ".\gradlew.bat"` to check if the file exists before running
- Pipe output with `2>&1 | Select-Object -First 250` to limit output and capture both stdout and stderr
- Always set `JAVA_HOME` from system environment variables before running Gradle commands

### Unix/macOS

```bash
./gradlew testDebugUnitTest --tests "com.github.shannonbay.pursue.GroupDetailFragmentTest" --no-daemon
```

## References

- **Full Documentation**: See `TESTING.md` for comprehensive testing guide with all 29+ documented patterns
- **Robolectric**: https://robolectric.org/
- **MockK**: https://mockk.io/
- **Kotlin Coroutines Testing**: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/
