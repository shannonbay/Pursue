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
└── .claude/skills/       # Claude Code skills
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
