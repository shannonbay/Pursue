# Pursue

Pursue is a goal accountability app - a monorepo containing an Android app (pursue-app/), an Express.js backend (pursue-server/), and a marketing website (pursue-web/ submodule).

## Quick Reference

### Backend Server (pursue-server/)

```bash
cd pursue-server

# Install dependencies
npm install

# Development (hot reload)
npm run dev

# Build
npm run build

# Production
npm start

# Run all tests
npm test

# Run integration tests only
npm run test:integration

# Run tests with coverage
npm run test:coverage

# Run tests in watch mode
npm run test:watch
```

**Database**: PostgreSQL 17.x with Kysely query builder
**Test database**: `pursue_test` (create with `psql -U postgres -c "CREATE DATABASE pursue_test;"`)

### Android App (pursue-app/)

```powershell
cd pursue-app

# Set JAVA_HOME in PowerShell (required)
$env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable("JAVA_HOME", "Machine")

# Build
./gradlew build --no-daemon

# Run unit tests
./gradlew testDebugUnitTest --no-daemon

# Run specific test class
./gradlew testDebugUnitTest --tests "com.github.shannonbay.pursue.SomeTest" --no-daemon

# Run E2E tests (requires backend running)
./gradlew :app:testE2e --no-daemon

# Clean build
./gradlew clean --no-daemon
```

**Language**: Kotlin
**UI**: XML layouts with Material Design 3
**Test reports**: `app/build/reports/tests/testDebugUnitTest/index.html`

## Codebase Structure Index

The file map below provides instant orientation. For detailed export signatures and dependencies, read the relevant `.claude/structure/*.yaml` file for the directory you're working in.

After adding, removing, or renaming source files or public classes/functions, update both the file map below and the relevant structure YAML file.

### File Map — pursue-server/src/

```
# Core
app.ts - Express app setup, CORS, middleware, route mounting
server.ts - Server initialization, port binding, env validation

# Controllers
controllers/auth.ts - POST register, login, google, refresh, logout, password reset, link/unlink
controllers/users.ts - GET/PATCH/DELETE profile, avatar, subscription, groups listing
controllers/groups.ts - Group CRUD, members, invites, activity, icon, export
controllers/goals.ts - Goal CRUD, progress retrieval, period aggregation
controllers/progress.ts - POST/GET/DELETE progress entries
controllers/devices.ts - POST/GET/DELETE FCM device registration
controllers/subscriptions.ts - POST upgrade, verify, cancel, downgrade

# Services
services/authorization.ts - Group/goal access checks, membership role validation
services/subscription.service.ts - Tier logic, group limits, export date validation
services/fcm.service.ts - Push notifications, topic messaging, invalid token cleanup
services/activity.service.ts - Group activity event logging
services/googleAuth.ts - Google OAuth token verification
services/storage.service.ts - Avatar/icon upload (WebP 256x256), deletion
services/exportProgress.service.ts - Excel workbook generation for progress export

# Database
database/index.ts - Kysely connection initialization
database/types.ts - TypeScript interfaces for all tables

# Middleware
middleware/authenticate.ts - JWT Bearer token verification
middleware/errorHandler.ts - ApplicationError class + global error handler
middleware/rateLimiter.ts - Rate limiters for API, auth, password reset

# Routes
routes/auth.ts - Auth route definitions with rate limiting
routes/users.ts - User routes (profile, avatar, subscription)
routes/groups.ts - Group routes (CRUD, members, invites, activity, export)
routes/goals.ts - Goal routes (CRUD, progress)
routes/progress.ts - Progress entry routes
routes/devices.ts - Device registration routes
routes/subscriptions.ts - Subscription management routes

# Utils & Types
utils/jwt.ts - JWT generation, verification, token hashing
utils/password.ts - bcrypt hashing and verification
utils/logger.ts - Winston logger configuration
types/express.ts - AuthRequest, AuthUser type extensions

# Validations
validations/auth.ts - Zod schemas for auth endpoints
validations/users.ts - Zod schemas for user endpoints
validations/groups.ts - Zod schemas for group endpoints
validations/goals.ts - Zod schemas for goal endpoints
validations/progress.ts - Zod schemas for progress endpoints
validations/devices.ts - Zod schemas for device endpoints
validations/subscriptions.ts - Zod schemas for subscription endpoints
```

### File Map — pursue-app (app/src/main/java/app/getpursue/)

```
# Data Layer
data/network/ApiClient.kt - Singleton HTTP client with 50+ suspend API methods
data/network/AuthInterceptor.kt - Adds Bearer token to authenticated requests
data/network/TokenAuthenticator.kt - Refreshes token on 401 response
data/network/UserNotFoundSignOutInterceptor.kt - Signs out on 404 user errors
data/auth/AuthRepository.kt - Sign-in/sign-up and token refresh flows
data/auth/GoogleSignInHelper.kt - Google OAuth sign-in workflow
data/auth/SecureTokenManager.kt - Encrypted token storage (Android Keystore)
data/fcm/FcmRegistrationHelper.kt - Registers FCM token with backend
data/fcm/FcmTokenManager.kt - Local FCM token storage
data/fcm/FcmTopicManager.kt - Group notification topic subscriptions
data/fcm/PursueFirebaseMessagingService.kt - Incoming push notification handler
data/notifications/NotificationPreferences.kt - User notification settings

# Models
models/Group.kt - Group data model
models/GroupDetailResponse.kt - API response for group details
models/GroupActivity.kt - Group activity event model
models/GroupGoal.kt - Goal in group context
models/GroupGoalsResponse.kt - API response for group goals
models/GroupMember.kt - Group member data model
models/GroupMembersAdapter.kt - Members list adapter (in models/)
models/PendingMember.kt - Pending invitation data model
models/MyProgressResponse.kt - API response for user progress
models/TodayGoalsResponse.kt - API response for today's goals
models/GoalDetailUiModel.kt - Goal detail screen data
models/GoalEntryUiModel.kt - Goal entry UI data
models/GoalForLogging.kt - Goal data for progress logging

# Activities
ui/activities/MainActivity.kt - Launcher, auth check, deep linking
ui/activities/MainAppActivity.kt - Main container with bottom navigation
ui/activities/OnboardingActivity.kt - Sign-in/sign-up container
ui/activities/GroupDetailActivity.kt - Group detail container

# Fragments — Home
ui/fragments/home/HomeFragment.kt - Bottom nav with Today and Progress tabs
ui/fragments/home/TodayFragment.kt - Today's goals and progress logging
ui/fragments/home/MyProgressFragment.kt - User progress statistics
ui/fragments/home/ProfileFragment.kt - User profile and settings
ui/fragments/home/PremiumFragment.kt - Premium features and subscription

# Fragments — Groups
ui/fragments/groups/GroupDetailFragment.kt - ViewPager2 with Goals/Members/Activity tabs
ui/fragments/groups/GoalsTabFragment.kt - Group goals tab with progress logging
ui/fragments/groups/MembersTabFragment.kt - Group members tab
ui/fragments/groups/ActivityTabFragment.kt - Group activity feed tab
ui/fragments/groups/CreateGroupFragment.kt - Create new group
ui/fragments/groups/EditGroupFragment.kt - Edit group details
ui/fragments/groups/PendingApprovalsFragment.kt - Pending membership approvals

# Fragments — Goals & Onboarding
ui/fragments/goals/CreateGoalFragment.kt - Create new goal
ui/fragments/goals/GoalDetailFragment.kt - Goal details display
ui/fragments/onboarding/WelcomeFragment.kt - Welcome screen
ui/fragments/onboarding/SignInEmailFragment.kt - Email sign-in
ui/fragments/onboarding/SignUpEmailFragment.kt - Email sign-up

# UI Components
ui/adapters/GroupAdapter.kt - RecyclerView adapter for groups
ui/adapters/TodayGoalAdapter.kt - Adapter for today's goals
ui/adapters/GroupGoalsAdapter.kt - Adapter for group goals
ui/adapters/GroupMembersAdapter.kt - Adapter for group members
ui/adapters/GroupActivityAdapter.kt - Adapter for activity feed
ui/adapters/GoalBreakdownAdapter.kt - Adapter for goal breakdown
ui/adapters/GoalEntryAdapter.kt - Adapter for goal entries
ui/adapters/PendingMembersAdapter.kt - Adapter for pending invites
ui/handlers/GoalLogProgressHandler.kt - Shared progress logging logic
ui/dialogs/LogProgressDialog.kt - Progress logging dialog (binary/numeric/duration)
ui/views/EmptyStateView.kt - Empty state custom view
ui/views/ErrorStateView.kt - Error state custom view
ui/views/IconPickerBottomSheet.kt - Icon selection bottom sheet
ui/views/InviteMembersBottomSheet.kt - Member invitation bottom sheet
ui/views/JoinGroupBottomSheet.kt - Group join bottom sheet

# Utils
utils/ImageUtils.kt - Image loading and processing
utils/RelativeTimeUtils.kt - Relative time formatting
utils/GrayscaleTransformation.kt - Glide grayscale transformation
GlideModule.kt - Glide config with authenticated image requests
PursueApplication.kt - Application class initialization
```

## Project Structure

```
Pursue/
├── pursue-app/           # Android app (Kotlin)
│   ├── app/src/main/     # Main source
│   ├── app/src/test/     # Unit tests (JUnit + MockK + Robolectric)
│   ├── app/src/e2e/      # E2E tests (real API calls)
│   ├── TESTING.md        # Unit testing patterns
│   └── E2ETESTING.md     # E2E testing patterns
├── pursue-server/        # Backend (Node.js + TypeScript)
│   ├── src/              # Source code
│   │   ├── controllers/  # Route handlers
│   │   ├── services/     # Business logic
│   │   ├── database/     # Kysely DB connection
│   │   ├── middleware/   # Auth, rate limiting, errors
│   │   └── routes/       # Express routes
│   ├── tests/            # Integration tests (Jest + Supertest)
│   └── schema.sql        # PostgreSQL schema
├── pursue-web/           # Marketing site (git submodule)
├── specs/                # Design specifications
├── .claude/skills/       # Claude Code skills
└── .claude/structure/    # Detailed structure index (per-directory YAML)
```

## Tech Stack

### Backend
- Node.js 20.x + TypeScript
- Express.js
- PostgreSQL 17.x + Kysely (type-safe query builder)
- JWT authentication (access + refresh tokens)
- Google OAuth
- Zod for validation
- Jest + Supertest for testing

### Android
- Kotlin
- XML layouts (ConstraintLayout, Material Design 3)
- OkHttp + Retrofit for networking
- SQLite (Room) for local cache
- Firebase Cloud Messaging (FCM)
- JUnit 4 + MockK + Robolectric for testing

## Key Patterns

### Backend Testing
- Tests in `tests/integration/` organized by feature
- Use `testHelpers.ts` for creating test users/groups
- Tests clean up after themselves
- Rate limiting disabled for `NODE_ENV=test`

### Android Unit Testing
- Fragment tests use Robolectric with `@Config(sdk = [28])`
- Mock ApiClient with `mockkObject(ApiClient)` before fragment launch
- Use `advanceCoroutines()` helper for lifecycle + coroutine synchronization
- See `TESTING.md` for detailed patterns on handlers, toasts, coroutines

### Android E2E Testing
- Tests in `app/src/e2e/kotlin/`
- Use real ApiClient against running backend
- `getOrCreateSharedUser()` for test user reuse
- `trackGroup()`/`trackUser()` for cleanup
- See `E2ETESTING.md` for patterns

## Environment Variables (Backend)

```env
DATABASE_URL=postgresql://postgres:password@localhost:5432/pursue_db
JWT_SECRET=your-secret-min-32-chars
JWT_REFRESH_SECRET=your-refresh-secret-min-32-chars
GOOGLE_CLIENT_ID=web-client-id.apps.googleusercontent.com
GOOGLE_ANDROID_CLIENT_ID=android-client-id.apps.googleusercontent.com
NODE_ENV=development
PORT=3000
```

## Common Tasks

### Add a new API endpoint
1. Add route in `src/routes/`
2. Add controller in `src/controllers/`
3. Add validation schema in `src/validations/`
4. Add tests in `tests/integration/`
5. Add Kysely types if new tables in `src/database/types.ts`

### Add a new Android screen
1. Create layout XML in `res/layout/`
2. Create Fragment in `ui/fragments/`
3. Add navigation if needed
4. Add strings to `res/values/strings.xml`
5. Add unit tests following TESTING.md patterns

## Specifications

Detailed specs are in `specs/` and `pursue-app/specs/`:
- `Spec.md` - System architecture, API design, data model
- `Pursue-UI-Spec.md` - UI/UX specification
- `specs/backend/` - Backend-specific specs (rate limiting, etc.)
