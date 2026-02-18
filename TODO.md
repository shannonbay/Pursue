# TODO

## Testing

### Testing of Home Screen Implementation (Section 4.2)

**Plan**: `implement_home_screen_(section_4.2)_f1952564.plan.md`

Create comprehensive unit tests for the Home Screen implementation including:

- **HomeFragment Tests**:
  - Test groups list display with empty state
  - Test groups list display with multiple groups
  - Test pull-to-refresh functionality
  - Test API integration and error handling
  - Test FAB click handling
  - Test empty state button clicks (Join Group, Create Group)
  - Test group card click navigation

- **GroupAdapter Tests**:
  - Test group card data binding
  - Test timestamp formatting (relative time)
  - Test click listener invocation
  - Test progress bar display

- **MainAppActivity Tests**:
  - Test bottom navigation tab switching
  - Test fragment navigation
  - Test toolbar title updates

- **API Integration Tests**:
  - Test `getMyGroups()` API call
  - Test error handling (network errors, invalid tokens)
  - Test pagination (if implemented)

**Reference**: See `app/TESTING.md` for testing patterns and best practices.

---

### Testing of Sign In Flow Implementation

**Plan**: `implement_sign_in_flow_with_login_api,_token_storage,_and_fcm_registration_d28cee02.plan.md`

Create comprehensive unit tests for the Sign In Flow implementation including:

- **SignInEmailFragment Tests**:
  - Test successful sign-in flow
  - Test loading state during API call
  - Test error handling (401, 400, network errors)
  - Test button state management
  - Test navigation to MainAppActivity on success

- **OnboardingActivity Sign-In Tests**:
  - Test token storage after successful login
  - Test FCM token registration (success and failure scenarios)
  - Test navigation flow
  - Test error message display

- **ApiClient Login Tests**:
  - Test `login()` API call with valid credentials
  - Test `login()` API call with invalid credentials
  - Test error response handling
  - Test `registerDevice()` API call

- **FcmTokenManager Tests** (if implemented):
  - Test FCM token retrieval
  - Test token caching
  - Test error handling when Firebase unavailable

**Reference**: See `app/TESTING.md` for testing patterns and best practices. See `app/src/test/java/com/github/shannonbay/pursue/SignUpEmailFragmentTest.kt` for similar test patterns.

---
Blurb
Track goals
See each other's progress
---

My TODOs
- Ask sonnet to update the backend spec to support creating expired tokens for E2E testing
- Projects - sub-collections of goals within groups, new cadences (one-off and one-per-user)
- Fix skeleton dimensions to match actual cards
- Implement unit tests for plan @c:\Users\layen\.cursor\plans\implement_goal_detail_screen_(section_4.3.4)_7e1eec05.plan.md
- Write unit tests for plan implement_log_progress_feature_11cfecd7.plan.md
- Subscriptions
- Ads - What would Regan do
- Upload custom images for group icons
- Red teaming - completed up to bash password retry loop
- Create new group -> Back -> Fix activity toolbar title
- Join group -> Back -> not in any groups yet
- Caching
- Ads

- Subscriptions
- Context menu for ignoring goals (should be stored in frontend)
- Invite Deep links don't work yet
- Unit tests for plan pending_approvals_screen
- Go through Backend-Spec-Approval-Queue-Updates.md and implement and test
- E2E tests for Backend-Spec-Approval-Queue-Updates.md including declines and E2E tests group count shouldn't change til approved, can't see goals or log progress until approved
- Unit test for Speed dial FAB for join group and create group
- E2E Tests for progress endpoint (may already be done)

- My Progress and other Profile sub-screens from UI Spec
- When join request notification comes in, add join queue button to members list
- Put join requests on TodayFragment as well so they don't get overlooked
- test notifications again
- implementing sign out button with claude code
- blank space on goal creation and group creation screen after save button
- Site should merge features page with homepage
- app announcements
- Password reset e-mails
- about section in app with link to public issues repository and e-mail address for feedback
 

TODO: Redis-backed sliding-window limits

Goal

Implement Redis-backed sliding-window rate limiting for time-based API and resource-creation limits (accurate per-user / per-group burst control).
Limits to enforce in Redis

API rate: 100 requests / 1 minute per user
Auth endpoints (login/register/password): 5 attempts / 15 minutes (per IP and per account)
File uploads: 10 uploads / 15 minutes per user
Groups creation: 10 / day per user, burst 3 / hour per user
Group joins: 10 / day per user
Group invites (send): 20 / day per user
Goals creation: 20 / day per group, burst 5 / hour per group
Progress entries: 200 / day per user, burst 50 / minute per user
Implementation notes

Use Redis ZSET sliding-window or token-bucket implemented as an atomic Lua script (single script per check: add/trim/count/expire).
Key patterns: rate:api:user:<userId>, rate:auth:ip:<ip>, rate:uploads:user:<userId>, rate:group:<groupId>:creates, etc.
Middleware should use authenticated user id when available, fallback to ip (ensure IPv6-safe ipKey helper).
Emit RateLimit headers (Limit, Remaining, Reset) and structured logs/metrics.
Provide graceful fallback (deny-open vs deny-closed) and feature-flag rollout.
Add integration tests using local Redis (docker-compose) and unit tests for Lua scripts.
Deployment

Redis runs as separate service (managed Memorystore or container); do NOT bundle Redis inside the server image.
Add docker-compose for local dev (app + redis) and env var REDIS_URL for production.
Monitor memory/eviction and shard/cluster for scale as needed.
Tests & Rollout

CI: start ephemeral Redis container for integration tests or mock Redis client.
Rollout with feature flag, monitor errors and rate-limit metrics, and enable progressively.
Specs to reference

02-database-schema.md
specs\12-security-architecture-best-practices.md
Deliverables

Redis Lua scripts + TypeScript middleware
docker-compose.yml (dev)
ENV docs (REDIS_URL, REDIS_MAX_RETRIES)
Tests and migration/rollout plan