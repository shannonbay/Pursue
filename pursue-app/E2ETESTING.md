# E2E Testing Guide for Pursue Android App

## Design

E2E uses the **real ApiClient** (AuthInterceptor, SecureTokenManager), not a separate HTTP client.

- **ApiClient**: Configurable base URL. `setBaseUrlForE2E(LocalServerConfig.API_BASE_URL)` in `@Before`; `resetBaseUrlForE2E()` in `@After`. Default ngrok.
- **E2EApiClient**: Proxy over ApiClient. Before each auth call, `storeTokenIfPresent(accessToken)` so AuthInterceptor sees it; delegates to ApiClient with `""`. Adapts `getAvatar`/`getGroupIcon` → `ByteArray?`, `(userId, accessToken?)` / `(groupId, accessToken?)`.
- **SecureTokenManager**: On JVM, EncryptedSharedPreferences can fail; then `encryptedPrefs == null` and getters/`storeTokens` use in-memory `testAccessToken`/`testRefreshToken`. Production uses encrypted storage.
- **E2ETest**: Robolectric, `ApplicationProvider.getApplicationContext()`. `@Before`: `ApiClient.initialize(context)`, `setBaseUrlForE2E`, `api = E2EApiClient(context)`, `testDataHelper = TestDataHelper(context)`. `@After`: `deleteGroup`/`deleteUser` for tracked ids (groups first), then `resetBaseUrlForE2E()`. Use `trackUser`/`trackGroup` so cleanup runs.
- **TestDataHelper(context)**: `createTestUser(api)` → `api.register` then `storeTokens`. `createTestGroup(api, accessToken, ...)`. `deleteUser(userId)` / `deleteGroup(groupId)` (no api; no-ops if backend has no DELETE).

## Shared Test Users

To avoid rate limits, most tests use `getOrCreateSharedUser()` instead of `createTestUser()`:

```kotlin
val auth = getOrCreateSharedUser()  // Cached per test class
val group = testDataHelper.createTestGroup(api, auth.access_token)
```

- **One user per class**: `E2ETest.getOrCreateSharedUser()` caches by `javaClass`. First test in class creates; others reuse.
- **One group per class**: `E2ETest.getOrCreateSharedGroup()` caches a shared group per test class to avoid hitting the 10 groups per user limit.
- **No cleanup**: Shared users and groups are not tracked/deleted; unique emails prevent collisions.
- **When to use `createTestUser()`**: Tests requiring a second user (e.g., non-member 403 checks) or testing registration itself.
- **When to use `createTestGroup()`**: Tests that need a fresh group (e.g., LeaveGroupE2ETest where the group is deleted when the user leaves) or a group with specific params (e.g., iconEmoji/iconColor).

Impact: ~15 registrations (vs ~70 before) across the full suite.

## List Endpoints with Query Params

For endpoints like `GET /api/groups/:group_id/goals` that support filters (e.g. `cadence`, `archived`, `include_progress`), add separate tests that call with each param and assert the response shape (e.g. `includeProgress = false` → goals have `current_period_progress == null`; `cadence = "daily"` → all returned goals have `cadence == "daily"`). Use `getOrCreateSharedUser()`, create group and goals via `testDataHelper`, then call the list API with the param under test.

**Key learning:** One test per query combination keeps failures easy to diagnose; assert both the filtered list content and the `total` count when the backend returns it.

## Running E2E Tests

### JAVA_HOME (PowerShell)

Gradle needs JAVA_HOME; PowerShell may not inherit it. Set from Machine:  
`$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")`

### Packages

`e2e.config` (E2ETest, E2EApiClient, TestDataHelper, LocalServerConfig), `e2e.user`, `e2e.auth`, `e2e.groups`, `e2e.goals`, `e2e.helpers`.

Run a class: `./gradlew :app:testE2e --tests "com.github.shannonbay.pursue.e2e.user.AvatarUploadE2ETest"`

## Gradle

AGP only recognizes `main`, `test`, `androidTest`. Put E2E in `test`: `sourceSets.getByName("test") { java.srcDir("src/e2e/kotlin") }`. Filter: `include("**/*E2ETest.class", "**/*E2ETest\$*.class")`. E2E deps in `testImplementation` (okhttp, gson, truth, etc.).

## Dates in Progress Logging

The backend validates that `user_date` cannot be in the future. Because Robolectric tests run in a JVM whose timezone may differ from the server's, `LocalDate.now()` can return a date the server considers "tomorrow" — causing a **400 "Date cannot be in the future"** error.

**Fix:** Use a fixed past date (e.g. `"2026-02-01"`) for `userDate` in `logProgress` calls instead of `LocalDate.now()`. All existing E2E tests follow this pattern.

```kotlin
// GOOD — fixed past date, always valid
api.logProgress(accessToken = token, goalId = goalId, value = 1.0,
    userDate = "2026-02-01", userTimezone = "America/New_York")

// BAD — may fail if JVM timezone is ahead of server
api.logProgress(accessToken = token, goalId = goalId, value = 1.0,
    userDate = LocalDate.now().toString(), userTimezone = "America/New_York")
```

`LocalDate.now()` is fine for fields like `sender_local_date` on nudges, which don't have a "no future" validation.

## Test Images

Robolectric doesn't mock Bitmap/Color. Use minimal valid JPEG bytes (SOI, APP0, DQT, SOF0, DHT, SOS, EOI); pad for size, keep EOI at end.

## Backend / Server

- **500s**: If an endpoint isn't implemented, `@Ignore("Backend X not implemented")` until ready.
- **Server**: E2ETest uses `@BeforeClass` + `LocalServerConfig.isServerAvailable()` and `assumeTrue`; skips class if server down.
- **Cleanup**: `deleteUser`/`deleteGroup` are no-ops if backend has no DELETE. `@After` still invokes them; use `trackUser`/`trackGroup`.
- **Premium for E2E:** Start the backend with `NODE_ENV=test` so the mock token is accepted. The shared test user is upgraded to premium via `POST /api/subscriptions/upgrade` with `purchase_token: 'mock-token-e2e'`. Example: `NODE_ENV=test npm run dev` (PowerShell: `$env:NODE_ENV='test'; npm run dev`).

### Rate Limiting (429)

E2E tests create many users (one per test). The auth rate limiter in `specs/backend/07-rate-limiting.md` allows **5 requests per 15 minutes** for `/api/auth/register`. Tests will fail with **HTTP 429** once the limit is hit.

**Fix:** Relax rate limits for localhost in development. Example ( Express backend ):

```javascript
// Skip or relax rate limiting for localhost (E2E tests)
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 100, // Higher for dev; use 5 in production
  skip: (req) => req.ip === '127.0.0.1' || req.ip === '::1' || req.ip === '::ffff:127.0.0.1'
});
```

Alternatively, run a smaller subset of E2E tests when iterating:  
`./gradlew :app:testE2e --tests "com.github.shannonbay.pursue.e2e.groups.CreateGroupE2ETest.create group with name only succeeds"`

When tests fail with 429, the failure message will show: `API call failed: code=429, message=...`

### Resource Limits (e.g. 10 groups per user)

To ensure concurrent requests cannot bypass resource limits (see `specs/backend/02-database-schema.md`), add an E2E test that fires more than the limit concurrently (e.g. 12 create-group requests for a 10-group limit). Use a fresh user (`testDataHelper.createTestUser`) so the user starts with 0 groups. Assert exactly the limit succeed and the rest fail with 400/403/429. If this test fails with "expected N successes," the backend may not be enforcing the limit under concurrent load (e.g. check API-layer count or DB trigger).
