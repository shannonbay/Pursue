# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm run dev              # Start dev server with hot reload (tsx watch)
npm run build            # TypeScript compile to dist/
npm start                # Run production build from dist/
npm test                 # Run all tests (sequential, --runInBand)
npm run test:integration # Integration tests only
npm run test:unit        # Unit tests only
npm run test:coverage    # Tests with coverage report (70% threshold)
npm run test:watch       # Tests in watch mode
```

Run a single test file:
```bash
npx jest tests/integration/auth/register.test.ts --runInBand
```

Run tests matching a pattern:
```bash
npx jest --testNamePattern="should register" --runInBand
```

## Architecture

### Request Flow

```
Request → helmet → cors → body parser → rate limiter → route → authenticate middleware → controller → service → Kysely → PostgreSQL
                                                                                                          ↓
Response ← errorHandler ← controller ←──────────────────────────────────────────────────────────────────────
```

### Layer Responsibilities

- **Routes** (`src/routes/`): Define endpoints, attach middleware (auth, multer, rate limiters), delegate to controllers. Zod validation happens here via middleware.
- **Controllers** (`src/controllers/`): Handle request/response. Extract params, call services/DB, format responses. Most DB queries live here directly (no separate repository layer).
- **Services** (`src/services/`): Business logic that's complex enough to extract — authorization checks, FCM notifications, image processing, subscription management, activity feeds.
- **Middleware** (`src/middleware/`): `authenticate.ts` verifies JWT and attaches `req.userId`. `errorHandler.ts` catches `ApplicationError`, Zod errors, multer errors, and DB trigger errors.

### Database

- **Kysely** is the query builder — not an ORM. Queries are written as type-safe SQL chains directly in controllers.
- Types are in `src/database/types.ts`. The `Database` interface maps table names to their column types. Use `Generated<T>` for auto-generated columns, `ColumnType<Select, Insert, Update>` for columns with different read/write types.
- **DATE columns** return `"YYYY-MM-DD"` strings (not Date objects) due to a custom pg type parser in `src/database/index.ts`.
- **Images** (avatars, group icons) are stored as BYTEA in PostgreSQL, processed through Sharp (WebP, 256x256).
- **Soft deletes** on users, groups, and goals — always filter with `.where('deleted_at', 'is', null)`.
- **DB triggers** enforce hard caps (10 groups/user, 50 members/group, 100 goals/group). These throw errors caught by errorHandler as `RESOURCE_LIMIT_EXCEEDED`.

### Authentication & Authorization

- JWT access tokens (1h) + refresh tokens (30d, hashed in DB).
- `authenticate` middleware sets `req.userId` (typed via `AuthRequest` in `src/types/express.ts`).
- Authorization helpers in `src/services/authorization.ts`: `requireGroupMember()`, `requireActiveGroupMember()`, `requireGroupAdmin()`, `requireGroupCreator()`. These throw `ApplicationError` with 403.
- Google OAuth supports both web and Android client IDs.

### Error Handling

Throw `ApplicationError(message, statusCode, errorCode)` from anywhere — the global error handler catches it. Error codes: `VALIDATION_ERROR`, `NOT_FOUND`, `FORBIDDEN`, `PENDING_APPROVAL`, `RESOURCE_LIMIT_EXCEEDED`, `FILE_TOO_LARGE`, `INVALID_IMAGE`.

### Module System

The project uses **ES modules** (`"type": "module"` in package.json). All internal imports must use `.js` extensions (e.g., `import { db } from './database/index.js'`), even though source files are `.ts`. Jest is configured with `moduleNameMapper` to strip `.js` extensions during testing.

## Testing

- Tests use a real PostgreSQL database (`pursue_test`). No mocking of the database layer.
- `tests/setup.ts` creates schema in `beforeAll`, cleans all tables in `beforeEach`.
- Test helpers in `tests/helpers.ts`: `createAuthenticatedUser()` (via API, returns tokens), `createTestUser()` (direct DB insert), `createGroupWithGoal()`, `addMemberToGroup()`, `setUserPremium()`.
- Google OAuth is mocked in tests — see `jest.mock('google-auth-library')` patterns in auth tests.
- FCM is mocked — `jest.mock('firebase-admin')`.
- Rate limiting is automatically disabled when `NODE_ENV=test`.
- All tests must run sequentially (`--runInBand`, `maxWorkers: 1`) to avoid DB conflicts.
