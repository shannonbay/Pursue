# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See also the parent [../CLAUDE.md](../CLAUDE.md) for monorepo-level commands, project structure, and backend information.

## Build & Test Commands

```powershell
# Required before any Gradle command in PowerShell
$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")

# Build
./gradlew build --no-daemon

# Unit tests
./gradlew testDebugUnitTest --no-daemon

# Single test class
./gradlew testDebugUnitTest --tests "app.getpursue.ui.fragments.home.TodayFragmentTest" --no-daemon

# E2E tests (backend must be running on localhost:3000)
./gradlew :app:testE2e --no-daemon

# Clean
./gradlew clean --no-daemon
```

Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`

## Architecture

### Package Layout (`app/src/main/java/app/getpursue/`)

- `data/network/` - Networking layer (ApiClient singleton, interceptors)
- `data/auth/` - Token storage (SecureTokenManager), auth flows (AuthRepository, GoogleSignInHelper)
- `data/fcm/` - Firebase Cloud Messaging (token management, topic subscriptions, push service)
- `models/` - API response data classes and UI models
- `ui/activities/` - Activities: MainActivity (launcher/deep links), OnboardingActivity, MainAppActivity, GroupDetailActivity
- `ui/fragments/` - Fragments organized by feature: `home/`, `groups/`, `goals/`, `onboarding/`
- `ui/adapters/` - RecyclerView adapters
- `ui/handlers/` - Shared logic (GoalLogProgressHandler) reused across fragments
- `ui/views/` - Custom views and bottom sheets (ErrorStateView, EmptyStateView, IconPickerBottomSheet, JoinGroupBottomSheet, InviteMembersBottomSheet)
- `ui/dialogs/` - Dialogs (LogProgressDialog)
- `utils/` - Utilities (ImageUtils, RelativeTimeUtils)

### Key Architectural Decisions

**ApiClient is a Kotlin `object` (singleton)** with 50+ suspend functions for all API endpoints. It holds a single OkHttpClient with an interceptor chain:
1. `AuthInterceptor` - adds Bearer token (skips auth/public endpoints)
2. `TokenAuthenticator` - refreshes token on 401
3. `UserNotFoundSignOutInterceptor` - signs out on 404 user errors

For E2E testing, `ApiClient.setBaseUrlForE2E()` overrides the base URL.

**SecureTokenManager** uses EncryptedSharedPreferences (Android Keystore) with an automatic in-memory fallback when Keystore is unavailable (Robolectric/E2E tests).

**Fragment State Pattern**: Fragments track UI state explicitly with an enum of 5 states: `LOADING`, `SUCCESS_WITH_DATA`, `SUCCESS_EMPTY`, `ERROR`, `OFFLINE`. Visibility of views is toggled based on this state.

**Coroutine pattern**: API calls use `lifecycleScope.launch { withContext(Dispatchers.IO) { ... } }`. Many UI updates are deferred via `Handler(Looper.getMainLooper()).post { }`.

**GoalLogProgressHandler**: Shared handler used by both `GoalsTabFragment` and `TodayFragment` to handle binary/numeric/duration goal progress logging, editing, and removal—avoiding duplication.

**GroupDetailFragment** uses ViewPager2 with 3 tabs (Goals, Members, Activity). The initial page (page 0) does NOT trigger `onPageSelected()`, so the fragment must manually update FAB/UI state after data loads.

### API Base URL Configuration

- Debug builds read `pursue.api.base.url` from `local.properties` (for ngrok tunneling to local server)
- Release builds hardcode the production URL
- `BuildConfig.API_BASE_URL` is the single source of truth

### Navigation

- `MainActivity` → checks auth → `OnboardingActivity` (sign in/up) or `MainAppActivity`
- `MainAppActivity` → bottom nav with HomeFragment (Today, My Progress tabs), PremiumFragment, ProfileFragment
- `HomeFragment` → group tap → `GroupDetailActivity` → `GroupDetailFragment` (Goals, Members, Activity tabs)
- FCM notifications deep link through `MainActivity` intent filters → `GroupDetailActivity`

## Testing

After making code edits, run the full test suite before committing. All tests must pass.

```powershell
$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")
./gradlew testDebugUnitTest --no-daemon
```

### Testing Patterns

See [TESTING.md](TESTING.md) for detailed unit test patterns and [E2ETESTING.md](E2ETESTING.md) for E2E patterns.

### Critical test setup

1. **Always** `mockkObject(ApiClient)` before launching a fragment under test
2. **Always** use `@Config(sdk = [28], application = android.app.Application::class, packageName = "app.getpursue")`
3. Use `advanceCoroutines()` helper (triple looper idle + `advanceUntilIdle()`) for full lifecycle/coroutine synchronization
4. Use `MockApiClient` factory object (~630 lines) for consistent test response creation
5. Use `TestUtils` for test data builders (User, credentials, etc.)

### E2E tests

- Located in `app/src/e2e/kotlin/` but compiled into the test source set
- Run against a real backend (localhost:3000)
- Use `getOrCreateSharedUser()` for test user reuse
- Use `trackGroup()`/`trackUser()` for cleanup
- E2E tests are excluded from `testDebugUnitTest` in CI (`CI=true` env var)

## Android-Specific Conventions

When working on Android layouts and fragments: (1) Check which Activity hosts the fragment before casting — fragments may be in GroupDetailActivity, not MainAppActivity. (2) Verify LinearLayout and other imports aren't accidentally removed during edits. (3) Test visibility of UI elements after layout changes.