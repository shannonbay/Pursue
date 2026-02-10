# Testing Guide for Pursue Android App

This document outlines the testing approach, key learnings, and best practices for writing unit tests in the Pursue Android application.

## Overview

The test suite uses:
- **JUnit 4** - Test framework
- **MockK** - Mocking library for Kotlin
- **Robolectric** - Android unit testing framework (runs Android code on JVM)
- **kotlinx-coroutines-test** - Coroutine testing utilities

## Test Structure

### Test Files

- `SignUpEmailFragmentTest.kt` - Fragment UI tests with lifecycle and coroutines
- `FcmRegistrationHelperTest.kt` - Pure unit tests for helper functions
- `FcmTokenManagerTest.kt` - FCM token retrieval, caching, and registration status tests
- `MainAppActivityFcmTest.kt` - FCM retry logic and network callback tests
- `PursueFirebaseMessagingServiceTest.kt` - FCM token refresh handling tests
- `SecureTokenManagerTest.kt` - Placeholder (requires instrumented tests)
- `GroupDetailFragmentTest.kt` - Fragment with ViewPager2 tabs, FAB behavior, API integration
- `GoalsTabFragmentTest.kt` - Tab fragment with 5-state pattern and error handling
- `MockApiClient.kt` - Test utilities for creating mock API responses
- `TestUtils.kt` - General test utilities

## Key Learnings

### 1. Robolectric

`@Config(sdk = [28], application = android.app.Application::class, packageName = "app.getpursue")` and `testOptions { unitTests { isIncludeAndroidResources = true } }` in `build.gradle.kts`. Fixes "No package ID 7f found".

### 2. Fragment and lifecycleScope

**Fragment**: Use `Robolectric.setupActivity(FragmentActivity::class.java)` (not FragmentScenario). Set callbacks via reflection *after* `onAttach()`. Ensure `setMaxLifecycle(fragment, Lifecycle.State.RESUMED)` for `lifecycleScope`.

**Coroutines**: `lifecycleScope` uses `Dispatchers.Main`. Call `advanceUntilIdle()` *first*, then `shadowOf(Looper.getMainLooper()).idle()`; use multiple passes (3+). Use `TestScope.advanceUntilIdle()`, not `testDispatcher.scheduler.advanceUntilIdle()`. Start `advanceCoroutines()` with `advanceUntilIdle()`.

**State/visibility**: When view visibility is flaky, verify internal state via reflection (e.g. `currentState`) before visibility. Float: `assertEquals(..., 0.6f.toDouble(), actual?.toDouble() ?: 0.0, 0.01)`.

```kotlin
private fun TestScope.advanceCoroutines() {
    advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
    advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
    advanceUntilIdle()
    shadowOf(Looper.getMainLooper()).idle()
}
```

### 3. MockK

`coEvery`/`coVerify` for suspend; `mockkObject` + `unmockkAll` in `@After`; mock `ApiClient` to prevent network. Properties: `every { this@mockk.prop } returns value`. Do not mock real objects (e.g. OkHttp `Request`). **Set up mocks before `launchFragment()`** when fragment calls APIs in `onViewCreated()`.

**Parent fragment callbacks**: When testing callbacks to parent fragments, set the mock parent via reflection BEFORE triggering the action. Since `onViewCreated()` already called `loadGoals()`, use `triggerSwipeRefresh()` to reload after setting the mock parent:

```kotlin
launchFragment()
val parentField = Fragment::class.java.getDeclaredField("mParentFragment")
parentField.isAccessible = true
parentField.set(fragment, mockParent)
triggerSwipeRefresh()  // Now parent is set before callback fires
advanceCoroutines()
verify(atLeast = 1) { mockParent.updateGoalsCount(2) }
```

```kotlin
@Before
fun setUp() {
    mockkObject(ApiClient)
    coEvery { ApiClient.getMyGroups(any()) } returns MockApiClient.createGroupsResponse()
}
@After
fun tearDown() { unmockkAll() }
```

### 4. Singletons and SharedPreferences

Reset singleton: `MyClass::class.java.getDeclaredField("INSTANCE")` on the **outer** class (not Companion), `field.set(null, null)`. Use `commit()` not `apply()` in tests, or idle looper after `apply()`. Use `applicationContext` to match singletons and avoid SharedPreferences mismatches.

### 5. Toast, SwipeRefresh, Handler.post, postDelayed

**Toast**: `ShadowToast.getLatestToast()`, `getTextOfLatestToast()`, `showedToast(text)`. With UnconfinedTestDispatcher, Toast after `withContext(IO)` can fail—verify error path via UI state/`coVerify` instead.

**SwipeRefresh**: Trigger via reflection `SwipeRefreshLayout::class.java.getDeclaredField("mListener")`; call `listener?.onRefresh()`. In production, wrap `isRefreshing`/Toast/View updates after `withContext(IO)` in `Handler(Looper.getMainLooper()).post { }`.

**Handler.post in tests**: View visibility may lag; prefer verifying API calls (`coVerify`) and cached/internal state. When you must assert on visibility (e.g. empty state visible, skeleton hidden), wait for the posted update: loop with `advanceCoroutines()` and `shadowOf(Looper.getMainLooper()).idle()` until internal state (e.g. `currentState == SUCCESS_EMPTY`) via reflection, then `idleFor(100)` and `idle()`, then assert. For chained actions (e.g. pull-to-refresh after initial load), optionally wait until the relevant view visibility is as expected before triggering the next action.

**postDelayed tasks**: Use `shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))` to advance time AND execute delayed tasks. Do NOT use `ShadowSystemClock.advanceBy()` alone—it advances time but doesn't execute pending looper tasks.

```kotlin
// WRONG - advances time but may not execute postDelayed callbacks
org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100))
shadowOf(Looper.getMainLooper()).idle()

// CORRECT - advances time AND executes delayed tasks
shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
shadowOf(Looper.getMainLooper()).idle()
```

### 6. UnconfinedTestDispatcher

**Works**: loading (delayed mock via `coAnswers { delay(100); response }`), success (immediate mock), view existence/config. **Fails**: Toast after IO; SwipeRefresh animations; `andThenThrows` sequencing. Use `@LooperMode(LooperMode.Mode.PAUSED)`, `Dispatchers.setMain(testDispatcher)`, `runTest(testDispatcher)`, tearDown `Dispatchers.resetMain`. For error/offline, consider instrumented tests or ViewModel tests.

**Error paths with `withContext(Dispatchers.IO)`**: The test dispatcher only controls `Dispatchers.Main`, not `Dispatchers.IO`. Setting a mock to throw before `launchFragment()` may not work because the exception is thrown on the real IO dispatcher. Use a two-phase approach:

```kotlin
@Test
fun `test error handling`() = runTest(testDispatcher) {
    // Phase 1: Launch with successful mock
    val successResponse = MockApiClient.createEmptyGroupGoalsResponse()
    coEvery { ApiClient.getGroupGoals(any(), any(), any(), any(), any()) } returns successResponse
    launchFragment()
    advanceCoroutines()

    // Phase 2: Change mock to throw, then trigger reload
    coEvery { ApiClient.getGroupGoals(any(), any(), any(), any(), any()) } coAnswers {
        throw ApiException(401, "Unauthorized")
    }
    triggerSwipeRefresh()  // Triggers loadGoals() again
    for (i in 1..10) { advanceUntilIdle(); shadowOf(Looper.getMainLooper()).idle() }

    // Verify error state
    coVerify(atLeast = 2) { ApiClient.getGroupGoals(any(), any(), any(), any(), any()) }
    val currentState = currentStateField.get(fragment) as GoalsUiState
    assertEquals(GoalsUiState.ERROR, currentState)
}
```

```kotlin
@Before fun setUp() { Dispatchers.setMain(testDispatcher); mockkObject(ApiClient) }
@After fun tearDown() { ShadowToast.reset(); shadowOf(Looper.getMainLooper()).idle(); unmockkAll(); Dispatchers.resetMain() }
```

### 7. Context, Keystore, Services

**Context**: Use `any()` for context in verify; `applicationContext` to match singletons. **Keystore**: SecureTokenManager—`@Ignore` in unit, use instrumented or mock. **Services**: Attach via `ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).invoke(service, context)`.

### 8. Network, Connectivity, Idempotence

Mock ApiClient (§3). **Idempotent**: `every { } answers { }` for stateful mocks, `coVerify(exactly = 1)`. **Connectivity**: `ShadowApplication.getInstance().setSystemService(CONNECTIVITY_SERVICE, mockConnectivityManager)`; capture `NetworkCallback` in `answers { secondArg() }`, trigger `onAvailable(mockNetwork)`; idle looper.

### 9. Fragment State-Based UI

5-state: `verifyLoadingState`, `verifySuccessStateWithData`, `verifyOfflineState` (alpha 0.6f via `0.6f.toDouble()` and delta 0.01). Offline: first load success (cache), then `andThenThrows` + call `loadGroups` via reflection; advance multiple times. Use `assertNotNull` before visibility.

**Null defaults in comparisons**: When code uses `value ?: 0.0` for comparisons, null defaults affect results. E.g., `completed >= (targetValue ?: 0.0)` means `10.0 >= 0.0` is true when `targetValue` is null. Write tests expecting the actual behavior, not the "ideal" behavior.

### 10. JAVA_HOME and Gradle

Set in System env or `$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME","Machine")` (PowerShell). Use `--no-daemon`. Optionally `org.gradle.java.home` in `gradle.properties`.

### 11. Flaky, OkHttp, Integration

**Assume**: `Assume.assumeFalse("...", System.getenv("CI") == "true")` for known-flaky. **OkHttp**: `ResponseBody.create("application/json".toMediaType(), "Unauthorized")` (MediaType first). Do not mock real `Request`/`Response`. **Integration**: Use real component (e.g. `TokenAuthenticator.authenticate()`), not raw `ApiClient.refreshToken()`, to test side effects.

### 12. ViewPager2 and Tab-Based UI

**Initial page selection**: `ViewPager2.OnPageChangeCallback.onPageSelected()` is NOT called for the initial page (page 0). If your fragment updates UI (like FAB visibility) in `onPageSelected`, you must manually trigger that update after data loads.

```kotlin
// In fragment: After data loads, manually update for current tab
viewPager.postDelayed({
    updateFABForTab(viewPager.currentItem, -1) // -1 indicates initial load
}, 50)
```

**Testing tab-based FAB**: If FAB visibility depends on data loaded via API AND tab position, tests may need a fallback to manually trigger tab changes:

```kotlin
// Advance coroutines and execute postDelayed
advanceCoroutines()
shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
shadowOf(Looper.getMainLooper()).idle()

// Fallback: manually trigger tab change if FAB still not visible
if (fab?.visibility != View.VISIBLE) {
    viewPager?.currentItem = 1
    shadowOf(Looper.getMainLooper()).idle()
    viewPager?.currentItem = 0
    shadowOf(Looper.getMainLooper()).idle()
}
```

**Tab switching order**: When testing content descriptions or icons that change per tab, switch tabs in test order that matches your assertions. First switch to one tab, capture value, then switch to another:

```kotlin
// Switch to Members tab first
viewPager?.currentItem = 1
shadowOf(Looper.getMainLooper()).idle()
val membersDesc = fab?.contentDescription

// Then switch to Goals tab
viewPager?.currentItem = 0
shadowOf(Looper.getMainLooper()).idle()
val goalsDesc = fab?.contentDescription

// Assert in same order as captured
assertEquals("Invite members", membersDesc)
assertEquals("Add goal", goalsDesc)
```

### 13. Error Handling Without Error State UI

**Fragment vs Tab error handling**: Some fragments show errors via Toast only (no error state container), while child tab fragments may have dedicated error state views. When testing error handling:

1. Check what error UI the fragment actually has (Toast vs error_state_container)
2. For Toast-only fragments, verify error path via `coVerify` and internal state instead:

```kotlin
// Verify API was called
coVerify(exactly = 1) { ApiClient.getGroupDetails(testAccessToken, testGroupId) }

// Verify internal state reflects error (groupDetail stays null)
val groupDetailField = GroupDetailFragment::class.java.getDeclaredField("groupDetail")
groupDetailField.isAccessible = true
val storedDetail = groupDetailField.get(fragment) as? GroupDetailResponse
assertNull("Group detail should be null after API error", storedDetail)
```

**Error state views vs. internal state**: When fragments have dedicated error views (e.g., `errorStateView`), the view object may not be initialized when you check it due to Handler.post timing. Prefer checking `currentState` enum and container visibility:

```kotlin
// Less reliable - errorStateView may be null due to timing
val errorStateView = errorStateViewField.get(fragment) as? ErrorStateView
assertNotNull(errorStateView)  // Can fail!

// More reliable - check internal state enum and container visibility
val currentState = currentStateField.get(fragment) as GoalsUiState
assertEquals(GoalsUiState.ERROR, currentState)
assertEquals(View.VISIBLE, errorStateContainer?.visibility)
```

## Best Practices

### 1. Test Organization

`@Before` (mocks, test data), `@After` (unmockkAll). Use `runTest { }` and Given/When/Then where it helps. Descriptive test names.

### 2. Helper Methods

`launchFragment()`, `fillForm(...)`, `clickButton()`, `advanceCoroutines()` to avoid duplication.

### 3. Mock Data Factories

`MockApiClient.createSuccessRegistrationResponse(...)`, `createApiException(code, message)` (or similar) for consistent test data.

### 4. Error Message Assertions

Include actuals: `assertTrue("Expected X. Actual: $toastText", toastText.contains("X"))`.

### 5. Patterns

**Fragment + coroutines**: `coEvery { apiCall() } returns response`; `launchFragment()`; `clickButton()`; `advanceUntilIdle()`; `shadowOf(Looper.getMainLooper()).idle()`; `verify { callbacks.onSuccess() }`.

**Error handling**: `coEvery { apiCall() } throws exception`; after trigger, `verify(exactly = 0) { callbacks.onSuccess() }` and assert toast or error state.

**Idempotent**: `every { manager.isRegistered() } returns true`; call twice; `coVerify(exactly = 1) { ApiClient.register(...) }` and `verify(exactly = 0) { manager.register(any()) }` for skip path.

## Troubleshooting

| Issue | See |
|-------|-----|
| No package ID 7f found | §1 Robolectric |
| Callbacks not called; Coroutine not completing | §2 Fragment and lifecycleScope |
| Mock not working; Test coroutine timeout | §3 MockK, §10 JAVA_HOME |
| Context mismatch | §7 Context, Keystore, Services |
| Singleton stale; SharedPreferences not found | §4 Singletons and SharedPreferences |
| JAVA_HOME not set | §10 JAVA_HOME and Gradle |
| Argument type mismatch Float; View visibility; Offline alpha | §2, §9 Fragment state-based UI |
| Service applicationContext null | §7 Context, Keystore, Services |
| postDelayed not executing; ShadowSystemClock not working | §5 postDelayed tasks |
| ViewPager2 onPageSelected not called for initial page | §12 ViewPager2 and Tab-Based UI |
| FAB visibility flaky after data load | §12 ViewPager2 and Tab-Based UI |
| Test checks for non-existent error_state_container | §13 Error Handling Without Error State UI |
| Parent fragment callback not triggered | §3 MockK (Parent fragment callbacks) |
| errorStateView is null but error occurred | §13 Error Handling (Error state views vs. internal state) |
| Completion logic wrong with null target | §9 Fragment State-Based UI (Null defaults) |
| Error mock not triggering; state stuck in LOADING | §6 UnconfinedTestDispatcher (Error paths with IO) |
| Empty state / skeleton visibility assertion fails (expected VISIBLE/GONE) | §5 Handler.post (wait for state then assert visibility) |

## Future Considerations

Instrumented tests (SecureTokenManager); Espresso; >80% coverage; performance tests; integration tests for user flows.

## Running Tests

**Prerequisites**: JAVA_HOME (§10); `./gradlew --version`.

```bash
./gradlew compileDebugUnitTestKotlin --no-daemon
./gradlew testDebugUnitTest --no-daemon
./gradlew testDebugUnitTest --tests "app.getpursue.ui.fragments.home.HomeFragmentTest" --no-daemon
./gradlew testDebugUnitTest --tests "app.getpursue.ui.fragments.home.HomeFragmentTest.test loading state shows skeleton" --no-daemon
# Reports: app/build/reports/tests/testDebugUnitTest/index.html
```

**PowerShell**: `$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")`; then `& ".\gradlew.bat" testDebugUnitTest --no-daemon` (use `Test-Path ".\gradlew.bat"` to check).

## References

[Robolectric](https://robolectric.org/) · [MockK](https://mockk.io/) · [Kotlin Coroutines Testing](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/) · [Android Testing](https://developer.android.com/training/testing) · [Gradle Testing](https://docs.gradle.org/current/userguide/java_testing.html)