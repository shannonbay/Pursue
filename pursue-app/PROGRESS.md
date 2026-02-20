# Testing Task Progress

## Overview
Implementing comprehensive unit tests for the three main data-loading screens (HomeFragment, TodayFragment, MyProgressFragment) that implement the 5-state UI pattern.

## Status: COMPILATION COMPLETE, RUNTIME FAILURES REMAIN
All test files compile successfully. Applied fixes to all three test files:
- Added null checks to verification methods
- Fixed Float/Double type mismatches in alpha comparisons
- Enhanced coroutine advancement with additional looper idle calls

### Current Test Status:
- **HomeFragmentTest**: 6 failures remaining (down from 9)
  - Issues: Offline state not being set correctly (alpha 1.0f instead of 0.6f), skeleton visibility checks failing
- **TodayFragmentTest**: 10 failures
  - Similar issues to HomeFragmentTest - view visibility and state transitions
- **MyProgressFragmentTest**: 10 failures  
  - Similar issues to other tests - view visibility and state transitions

### Applied Fixes:
1. ✅ Added null assertions to all verification methods
2. ✅ Fixed alpha comparison to use Double with delta (0.6f.toDouble() with 0.01 delta)
3. ✅ Enhanced `advanceCoroutines()` to include 3 iterations of coroutine + looper advancement
4. ✅ Fixed compilation errors (nullable receivers, type mismatches)

### Remaining Issues:
The remaining failures appear to be related to:
- **LifecycleScope coroutines**: UI state updates from lifecycleScope may not be completing before assertions
- **View visibility**: Views may not be getting their visibility set correctly, or state transitions aren't happening
- **Offline state**: Alpha values aren't being set to 0.6f when offline state is triggered

These issues likely require deeper investigation into:
- How lifecycleScope coroutines work in Robolectric tests
- Whether UI updates need to be explicitly dispatched to the main thread
- If state transitions need more time or different coroutine handling

## Completed Work

### 1. MockApiClient Extensions ✅
**File**: `app/src/test/java/com/github/shannonbay/pursue/MockApiClient.kt`

Added helper methods for creating mock API responses:
- `createGroupsResponse()` - Creates mock GroupsResponse with default test groups
- `createEmptyGroupsResponse()` - Creates empty groups response
- `createTodayGoalsResponse()` - Creates mock TodayGoalsResponse with groups and goals
- `createEmptyTodayGoalsResponse()` - Creates empty today's goals response
- `createMyProgressResponse()` - Creates mock MyProgressResponse with streak, weekly activity, heatmap, and goal breakdown
- `createEmptyMyProgressResponse()` - Creates empty progress response

All methods use default parameters for easy test customization.

### 2. HomeFragmentTest.kt ✅
**File**: `app/src/test/java/com/github/shannonbay/pursue/HomeFragmentTest.kt`

Comprehensive test suite with 24 test cases covering:

#### State Tests (7 tests)
- ✅ `test loading state shows skeleton`
- ✅ `test success state with groups`
- ✅ `test success empty state`
- ✅ `test error state network`
- ✅ `test error state server`
- ✅ `test error state unauthorized`
- ✅ `test offline state with cached groups`

#### State Transition Tests (4 tests)
- ✅ `test transition loading to success`
- ✅ `test transition loading to empty`
- ✅ `test transition loading to error`
- ✅ `test transition loading to offline`

#### Pull-to-Refresh Tests (4 tests)
- ✅ `test pull to refresh triggers reload`
- ✅ `test pull to refresh shows swipe indicator`
- ✅ `test pull to refresh updates data`
- ✅ `test pull to refresh error handling`

#### Error Recovery Tests (4 tests)
- ✅ `test retry button triggers reload`
- ✅ `test retry shows loading state`
- ✅ `test retry success transitions to success`
- ✅ `test retry failure stays in error`

#### Offline Mode Tests (4 tests)
- ✅ `test offline shows cached data`
- ✅ `test offline data is dimmed`
- ✅ `test offline retry transitions to success`
- ✅ `test offline retry transitions to error if no cache`

**Helper Methods**:
- `launchFragment()` - Launches fragment with mocked callbacks
- `advanceCoroutines()` - Advances coroutines and main looper
- `verifyLoadingState()` - Verifies loading state UI
- `verifySuccessStateWithData()` - Verifies success state with data
- `verifyEmptyState()` - Verifies empty state UI
- `verifyErrorState()` - Verifies error state UI
- `verifyOfflineState()` - Verifies offline state UI

**Notes**:
- Uses reflection to set callbacks after fragment attachment
- Mocks SecureTokenManager and ApiClient
- Uses Robolectric for Android framework testing
- Uses MockK for mocking

### 3. TodayFragmentTest.kt ✅
**File**: `app/src/test/java/com/github/shannonbay/pursue/TodayFragmentTest.kt`

Comprehensive test suite with 18 test cases covering:

#### State Tests (5 tests)
- ✅ `test loading state shows skeleton`
- ✅ `test success state with goals`
- ✅ `test success empty state`
- ✅ `test error state with specific message` - Verifies screen-specific error message
- ✅ `test offline state with cached goals`

#### State Transition Tests (4 tests)
- ✅ `test transition loading to success`
- ✅ `test transition loading to empty`
- ✅ `test transition loading to error`
- ✅ `test transition loading to offline`

#### Pull-to-Refresh Tests (3 tests)
- ✅ `test pull to refresh triggers reload`
- ✅ `test pull to refresh updates date and progress`
- ✅ `test pull to refresh updates goals list`

#### Error Recovery Tests (3 tests)
- ✅ `test retry button shows loading`
- ✅ `test retry success shows goals`
- ✅ `test error message is screen-specific`

#### Offline Mode Tests (3 tests)
- ✅ `test offline shows cached goals`
- ✅ `test offline banner retry`
- ✅ `test offline to online transition`

**Helper Methods**:
- Similar structure to HomeFragmentTest
- Verifies header (date and progress) display
- Tests Today-specific empty state with "Browse Groups" CTA

### 4. MyProgressFragmentTest.kt ✅
**File**: `app/src/test/java/com/github/shannonbay/pursue/MyProgressFragmentTest.kt`

Comprehensive test suite with 21 test cases covering:

#### State Tests (5 tests)
- ✅ `test loading state shows skeleton`
- ✅ `test success state displays all components`
- ✅ `test success empty state`
- ✅ `test error state with specific message` - Verifies screen-specific error message
- ✅ `test offline state with cached progress`

#### State Transition Tests (4 tests)
- ✅ `test transition loading to success`
- ✅ `test transition loading to empty`
- ✅ `test transition loading to error`
- ✅ `test transition loading to offline`

#### Component-Specific Tests (4 tests)
- ✅ `test streak card displays correctly`
- ✅ `test weekly activity displays correctly`
- ✅ `test heatmap displays correctly`
- ✅ `test goal breakdown displays correctly`

#### Pull-to-Refresh Tests (2 tests)
- ✅ `test pull to refresh triggers reload`
- ✅ `test pull to refresh updates all components`

#### Error Recovery Tests (3 tests)
- ✅ `test retry button shows loading`
- ✅ `test retry success shows progress`
- ✅ `test error message is screen-specific`

#### Offline Mode Tests (3 tests)
- ✅ `test offline shows cached progress`
- ✅ `test offline data is dimmed`
- ✅ `test offline banner retry`

**Helper Methods**:
- Similar structure to other test files
- Component-specific verification methods for streak, weekly, heatmap, and breakdown

## Next Steps

### 1. Compile and Fix Errors 🔄
**Priority**: HIGH

Run tests to identify compilation errors:
```bash
./gradlew test --tests "com.github.shannonbay.pursue.HomeFragmentTest" --no-daemon
./gradlew test --tests "com.github.shannonbay.pursue.TodayFragmentTest" --no-daemon
./gradlew test --tests "com.github.shannonbay.pursue.MyProgressFragmentTest" --no-daemon
```

**Known Issues to Check**:
- Reflection access to private fields (`cachedGroups`, `cachedData`, `loadGroups()`, `loadTodayGoals()`, `loadProgress()`)
- View ID references (verify all R.id.* references exist in layouts)
- Adapter types (GroupAdapter, TodayGoalAdapter, GoalBreakdownAdapter)
- Import statements for model classes

### 2. Fix Reflection Issues 🔄
**Priority**: HIGH

If reflection fails, consider:
- Making test-visible fields/methods
- Using @VisibleForTesting annotation
- Creating test helpers in fragments

**Fields accessed via reflection**:
- `HomeFragment.cachedGroups`
- `HomeFragment.loadGroups()`
- `TodayFragment.cachedData`
- `TodayFragment.loadTodayGoals()`
- `MyProgressFragment.cachedData`
- `MyProgressFragment.loadProgress()`

### 3. Verify View IDs 🔄
**Priority**: MEDIUM

Check that all view IDs used in tests exist in layouts:
- `R.id.skeleton_container`
- `R.id.groups_recycler_view`
- `R.id.goals_recycler_view`
- `R.id.goal_breakdown_recycler_view`
- `R.id.swipe_refresh`
- `R.id.empty_state`
- `R.id.empty_state_container`
- `R.id.error_state_container`
- `R.id.header_container`
- `R.id.content_container`
- `R.id.streak_card`
- `R.id.weekly_activity`
- `R.id.heatmap`
- `R.id.heatmap_grid`
- `R.id.weekly_grid`
- `R.id.retry_button`
- `R.id.date_text`
- `R.id.progress_text`
- `R.id.streak_days`
- `R.id.streak_progress`
- `R.id.weekly_summary`

### 4. Verify Adapter Types 🔄
**Priority**: MEDIUM

Ensure adapter classes exist and match expected signatures:
- `GroupAdapter` - Should accept List<Group> and click listener
- `TodayGoalAdapter` - Should accept List<TodayGroup> and click listener
- `GoalBreakdownAdapter` - Should accept List<GoalBreakdown>

### 5. Test Execution 🔄
**Priority**: HIGH

After fixing compilation errors:
1. Run individual test suites
2. Fix runtime errors (null checks, view hierarchy issues)
3. Verify test assertions pass
4. Check for flaky tests

### 6. Test Coverage Verification 🔄
**Priority**: LOW

After all tests pass:
1. Generate coverage report: `./gradlew testDebugUnitTestCoverage`
2. Verify coverage for all 5 states
3. Verify coverage for state transitions
4. Verify coverage for error handling
5. Verify coverage for offline mode

## Environment Setup

### Required for Gradle Execution

**JAVA_HOME** must be set in environment properties:
```
JAVA_HOME=<path_to_java_jdk>
```

**Example** (Windows):
```
JAVA_HOME=C:\Program Files\Java\jdk-17
```

**Example** (macOS/Linux):
```
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
```

### Gradle Commands

**Run all tests**:
```bash
./gradlew test --no-daemon
```

**Run specific test class**:
```bash
./gradlew test --tests "com.github.shannonbay.pursue.HomeFragmentTest" --no-daemon
```

**Run specific test method**:
```bash
./gradlew test --tests "com.github.shannonbay.pursue.HomeFragmentTest.test loading state shows skeleton" --no-daemon
```

**Clean and rebuild**:
```bash
./gradlew clean test --no-daemon
```

## Test Structure Summary

### Test Patterns Used
1. **Fragment Launch**: Uses Robolectric.setupActivity() and reflection for callbacks
2. **Coroutine Testing**: Uses `runTest` and `advanceUntilIdle()` with main looper idle
3. **Mocking**: Uses MockK for ApiClient and SecureTokenManager
4. **State Verification**: Helper methods check view visibility and data
5. **Error Testing**: Uses MockApiClient.createApiException() and createNetworkException()

### Test Categories
- **State Tests**: Verify each of the 5 UI states
- **Transition Tests**: Verify state transitions work correctly
- **Interaction Tests**: Verify user interactions (pull-to-refresh, retry button)
- **Offline Tests**: Verify offline mode with cached data
- **Component Tests**: Verify specific UI components render correctly

## Files Created/Modified

### New Files
- ✅ `app/src/test/java/com/github/shannonbay/pursue/HomeFragmentTest.kt` (646 lines)
- ✅ `app/src/test/java/com/github/shannonbay/pursue/TodayFragmentTest.kt` (520 lines)
- ✅ `app/src/test/java/com/github/shannonbay/pursue/MyProgressFragmentTest.kt` (520 lines)

### Modified Files
- ✅ `app/src/test/java/com/github/shannonbay/pursue/MockApiClient.kt` (Added 190 lines)

## Test Count Summary

- **HomeFragmentTest**: 24 tests
- **TodayFragmentTest**: 18 tests
- **MyProgressFragmentTest**: 21 tests
- **Total**: 63 test cases

## Implementation Notes

### Reflection Usage
Tests use reflection to:
1. Set callbacks after fragment attachment (to avoid null callback issues)
2. Access private fields for cached data
3. Invoke private load methods for state transitions

This is necessary because:
- Callbacks are set in `onAttach()` before test setup
- Cached data fields are private
- Load methods are private and handle lifecycle

### Coroutine Testing
All tests use `runTest` with proper coroutine advancement:
1. `advanceUntilIdle()` - Advances test coroutines
2. `shadowOf(Looper.getMainLooper()).idle()` - Advances main looper
3. Repeat both for complete coroutine + UI updates

### Mocking Strategy
- **SecureTokenManager**: Mocked as singleton with `mockkObject()`
- **ApiClient**: Mocked as object with `mockkObject()`
- **Callbacks**: Mocked with `mockk(relaxed = true)` for HomeFragment

### Error State Verification
Tests verify:
- Correct error icon shown
- Correct error message shown
- Screen-specific error messages (Today: "Failed to load today's goals", Progress: "Failed to load progress")
- Toast messages shown with correct text

### Offline State Verification
Tests verify:
- Cached data displayed
- Alpha set to 0.6f for dimmed appearance
- Offline banner/snackbar shown (via showOfflineBanner())
- Retry from offline state works

## Potential Issues to Watch For

1. **View IDs**: Some view IDs may not match between tests and layouts
2. **Adapter Constructors**: Adapter signatures may differ from expected
3. **Reflection Failures**: Private field/method access may fail in some environments
4. **Coroutine Timing**: Tests may need additional coroutine advancement
5. **Layout Inflation**: Some views may not be inflated in Robolectric
6. **Offline Banner**: Implementation may differ from test expectations

## Resumption Instructions

1. Set JAVA_HOME in environment properties
2. Verify Gradle can run: `./gradlew --version`
3. Compile tests: `./gradlew compileDebugUnitTestKotlin --no-daemon`
4. Run first test class: `./gradlew testDebugUnitTest --tests "com.github.shannonbay.pursue.HomeFragmentTest" --no-daemon`
5. Fix any compilation errors ✅ (All fixed)
6. Run full test suite ✅ (All tests compile)
7. Fix any runtime errors 🔄 (26 failures remaining across 3 test files)
8. Verify all tests pass

## Summary for Claude Opus

### Completed Work ✅
- All three test files compile successfully
- Fixed compilation errors (Float/Double mismatches, nullable receivers)
- Added null checks to all verification methods
- Enhanced coroutine advancement with additional looper idle calls
- Applied consistent fixes across all three test files

### Remaining Issues 🔄
**Total: 26 test failures**
- HomeFragmentTest: 6 failures
- TodayFragmentTest: 10 failures  
- MyProgressFragmentTest: 10 failures

### Common Failure Patterns
1. **View Visibility Issues**: Views showing as VISIBLE (0) when they should be GONE (8)
   - Skeleton containers not being hidden
   - Error/empty containers not showing correctly
   
2. **Offline State Alpha**: Alpha values not being set to 0.6f
   - Expected: 0.6f, Actual: 1.0f
   - Suggests offline state transitions aren't completing

3. **State Transitions**: State updates from lifecycleScope coroutines may not be completing
   - UI updates may need explicit main thread dispatching
   - May need different coroutine testing approach for lifecycleScope

### Recommended Next Steps
1. Investigate lifecycleScope coroutine handling in Robolectric tests
2. Check if UI updates need explicit `runOnUiThread` or `post()` calls
3. Consider using `Dispatchers.setMain()` for test dispatcher
4. Verify fragment lifecycle state is correct when calling load methods
5. Check if state updates are happening synchronously or need more time

### Test Execution Commands
```bash
# Run all tests
./gradlew testDebugUnitTest --no-daemon

# Run specific test class
./gradlew testDebugUnitTest --tests "com.github.shannonbay.pursue.HomeFragmentTest" --no-daemon

# Run specific test method
./gradlew testDebugUnitTest --tests "com.github.shannonbay.pursue.HomeFragmentTest.test offline data is dimmed" --no-daemon
```
