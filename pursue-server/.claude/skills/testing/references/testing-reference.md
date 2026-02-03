# Testing Reference

Detailed reference for the **testing** skill. Use when writing, fixing, or running tests. Source: [Testing-Guide.md](../../../Testing-Guide.md) and `tests/` in this project.

---

## Table of Contents

1. [Quick Reference](#1-quick-reference)
2. [Test Setup and Configuration](#2-test-setup-and-configuration)
3. [Test Helpers and Utilities](#3-test-helpers-and-utilities)
4. [Test Patterns by Endpoint Type](#4-test-patterns-by-endpoint-type)
5. [Common Test Scenarios](#5-common-test-scenarios)
6. [Test Organization](#6-test-organization)
7. [Best Practices](#7-best-practices)
8. [Common Pitfalls and Solutions](#8-common-pitfalls-and-solutions)
9. [Running Tests](#9-running-tests)
10. [Test Examples](#10-test-examples)
11. [Special Testing Scenarios](#11-special-testing-scenarios)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Quick Reference

### Test Commands

| Command | Purpose |
|--------|---------|
| `npm test` | Run all tests |
| `npm run test:watch` | Watch mode (re-runs on file changes) |
| `npm run test:coverage` | Run tests with coverage report |
| `npm run test:unit` | Unit tests only |
| `npm run test:integration` | Integration tests only |
| `npm test -- path/to/test.ts` | Run specific test file |
| `npm test -- --testNamePattern="pattern"` | Run tests matching name |
| `npm run test:ci` | CI mode (coverage, sequential, force exit) |

### Test File Structure

```
tests/
├── setup.ts              # Global test setup (load env, create schema, cleanup)
├── helpers.ts            # Reusable test utilities
├── unit/                 # Unit tests (services, utils)
├── integration/          # Integration tests (API endpoints)
│   ├── auth/
│   ├── groups/
│   ├── users/
│   └── devices/
└── e2e/                  # End-to-end tests (full user flows)
```

### Naming Conventions

- Test files: `*.test.ts`
- Match endpoint structure: `tests/integration/auth/login.test.ts`
- Describe: endpoint or feature, e.g. `describe('POST /api/auth/register', () => { ... })`
- It: descriptive behavior, e.g. `it('should login successfully with correct credentials', ...)`

### Common Patterns

- **AAA**: Arrange (setup) → Act (call API) → Assert (expect)
- **Isolation**: Each test independent; use `beforeEach` cleanup; no shared state
- **Helpers**: Use `createAuthenticatedUser`, `createTestUser`, etc. from `tests/helpers.ts`

### Helper Quick Reference

| Helper | Purpose |
|--------|---------|
| `createAuthenticatedUser()` | Create user via API; returns `{ userId, accessToken, refreshToken, user }` |
| `createTestUser()` | Create user directly in DB (email/password); returns user + password |
| `createGoogleUser()` | Create user with Google provider only |
| `createPasswordResetToken(userId)` | Create valid reset token; returns plain token |
| `createExpiredPasswordResetToken(userId)` | Create expired reset token |
| `createTestInviteCode(groupId, userId, opts?)` | Create invite code in DB |
| `createTestGroupActivity(groupId, userId, type, metadata?)` | Create activity in DB |
| `randomEmail()` | Generate unique test email |
| `wait(ms)` | Promise delay for async operations |

---

## 2. Test Setup and Configuration

### Test Database

- **Never run tests against dev or production.** Use a separate `pursue_test` database.
- Create: `CREATE DATABASE pursue_test;` (via `psql -U postgres`).
- Tests use `TEST_DATABASE_URL` (or default `postgresql://postgres:postgres@localhost:5432/pursue_test`).

### Environment Variables

- **Load `.env.test` before any app imports.** Done in `tests/setup.ts` via `dotenv.config({ path: '.env.test' })`.
- Set `process.env.DATABASE_URL = TEST_DATABASE_URL` so the app uses the test DB.
- Set `NODE_ENV=test`. Setup also sets `JWT_SECRET`, `JWT_REFRESH_SECRET`, `GOOGLE_CLIENT_ID` if missing.

### Jest Configuration (`jest.config.cjs`)

- **Use `.cjs`** when `package.json` has `"type": "module"`.
- Key options:
  - `setupFilesAfterEnv: ['<rootDir>/tests/setup.ts']`
  - `maxWorkers: 1` — run sequentially to avoid DB conflicts
  - `forceExit: true`
  - `testTimeout: 10000`
  - `coverageThreshold`: 70% for branches, functions, lines, statements
- Coverage excludes: `src/server.ts`, `src/types/**`, `*.d.ts`.

### Rate Limiting

- **Disable in tests.** Rate limiter checks `process.env.NODE_ENV === 'test'` and skips.

### Setup Lifecycle

- `beforeAll`: Create pool, Kysely `testDb`, run schema creation.
- `beforeEach`: Run `cleanDatabase` (delete data in reverse dependency order).
- `afterAll`: Destroy `testDb`, end pool.

---

## 3. Test Helpers and Utilities

All helpers live in `tests/helpers.ts`. Import from `'../../helpers'` (or appropriate relative path).

### `createAuthenticatedUser(email?, password?, displayName?)`

Creates a user via `POST /api/auth/register` and returns tokens. Use for protected endpoints.

```ts
const { userId, accessToken, refreshToken, user } = await createAuthenticatedUser();
// Defaults: test@example.com, Test123!@#, Test User
```

### `createTestUser(email?, password?, displayName?)`

Inserts user + email auth provider directly. Use for login, reset-password, etc.

```ts
const u = await createTestUser('test@example.com', 'Test123!@#', 'Test User');
// u.id, u.email, u.display_name, u.password
```

### `createGoogleUser(email?, googleUserId?, displayName?)`

Inserts user with only Google provider. Use for link/unlink Google tests.

### `createPasswordResetToken(userId)`

Inserts a valid reset token, returns plain token string.

### `createExpiredPasswordResetToken(userId)`

Inserts expired reset token, returns plain token string.

### `createTestInviteCode(groupId, userId, options?)`

Inserts invite code. Options: `code`, `max_uses`, `expires_at`.

### `createTestGroupActivity(groupId, userId, activityType, metadata?)`

Inserts a group activity row.

### `randomEmail()`

Returns `test-{random}@example.com` for unique users.

### `wait(ms)`

`Promise` that resolves after `ms` milliseconds.

### Groups and Goals

- Groups are created via **API** (`POST /api/groups`) using `createAuthenticatedUser` + `request(app).post(...).set('Authorization', ...)`.
- There is no `createTestGroup` in helpers; use the API or insert directly into `groups` + `group_memberships` if needed.

---

## 4. Test Patterns by Endpoint Type

### Authentication Endpoints

- **Registration**: Happy path (201, tokens, user); duplicate email (409, `EMAIL_EXISTS`); validation (400, weak password, invalid email); DB check (user exists, password hashed).
- **Login**: Success (200, tokens); wrong password (401, `INVALID_CREDENTIALS`); nonexistent email (401); case-insensitive email; refresh token stored in DB.
- **Refresh**: Valid refresh (200, new tokens); invalid/expired (401). Avoid asserting tokens differ if generated within same second.
- **Password reset**: Valid token + new password (200, tokens); login with new password works; old password rejected; token marked used.
- **Google OAuth**: Mock `verifyGoogleIdToken`; test success, audience mismatch, expired, invalid token.
- **Link/Unlink**: Link Google to email user; unlink; reject unlink when last provider (`CANNOT_UNLINK_LAST_PROVIDER`).

### Protected Endpoints

- **JWT**: Valid `Authorization: Bearer <token>` (200); missing header (401, `UNAUTHORIZED`); invalid format (401); invalid JWT (401, `INVALID_TOKEN`).
- **Authorization**: 403 when user lacks permission (e.g. not member, not admin).
- **User context**: Ensure `req.user` used correctly (e.g. `GET /api/users/me` returns own profile).

### CRUD Endpoints

- **Create**: 201 + body; validation errors (400); auth required (401).
- **Read**: 200 + body; 404 for missing resource; 403 when not allowed; pagination (limit, offset, ordering).
- **Update**: 200 + updated body; validation (400); 404; 403.
- **Delete**: 204 or 200; 404; 403. For soft delete, verify `deleted_at` set and that soft-deleted rows are excluded from queries.

### File Upload Endpoints (Avatars, Icons)

- **Happy path**: Upload PNG/JPG/WebP; assert 200, `success: true`, storage updated; verify via GET or DB.
- **Validation**: Unsupported type (400, `INVALID_FILE_TYPE`); file too large (400, `FILE_TOO_LARGE`); missing file (400, `MISSING_FILE`).
- **Processing**: Resize to expected dimensions; crop; convert to WebP when applicable.
- **Auth**: 401 without token; 404 for soft-deleted user.
- Use `request(app).post(...).attach('avatar', buffer, 'avatar.png')` with Supertest.

---

## 5. Common Test Scenarios

### Happy Path

- Expected status (200, 201, 204).
- Response shape (e.g. `toMatchObject`, `toHaveProperty`).
- DB state: row created/updated, relations correct.

### Validation

- Required fields; length limits; format (email, UUID); type.
- Expect 400 and often `error.code` (e.g. `VALIDATION_ERROR`, `INVALID_INPUT`).

### Authorization

- 401: missing/invalid token.
- 403: insufficient permission, wrong role, not owner.

### Error Handling

- 400, 404, 409, 500 as appropriate.
- Assert `response.body.error.code` and `message` when defined.

### Database State

- Query via `testDb` after action; assert inserts/updates/deletes.
- Soft deletes: check `deleted_at` and that deleted rows are excluded from reads.

---

## 6. Test Organization

### File Structure

Place integration tests under `tests/integration/<domain>/`, e.g. `integration/users/me.test.ts`, `integration/users/avatar.test.ts`.

### Describe / It

- `describe('GET /api/users/me', () => { ... })` or `describe('POST /api/auth/register', ...)`.
- `it('should ...', async () => { ... })`. Be specific: e.g. "should return 401 without Authorization header".

### Imports

- `request` from `supertest`, `app` from `src/app`, `testDb` from `setup`, helpers from `helpers`.
- For mocks: `jest.mock('...')` and `jest.clearAllMocks()` in `beforeEach` if needed.

---

## 7. Best Practices

### AAA Pattern

```ts
it('should create group', async () => {
  // Arrange
  const { accessToken } = await createAuthenticatedUser();
  // Act
  const res = await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'G', icon_emoji: '🏃' });
  // Assert
  expect(res.status).toBe(201);
  expect(res.body.name).toBe('G');
});
```

### Test Isolation

- No shared state between tests.
- Each test creates its own data via helpers or API.
- `beforeEach` cleans DB.

### Descriptive Names

- Start with "should"; describe behavior and conditions.

### Coverage

- Aim for 70%+ statements, branches, functions, lines.
- Use `npm run test:coverage` and `coverage/lcov-report/index.html`.

---

## 8. Common Pitfalls and Solutions

| Problem | Solution |
|--------|----------|
| Env not loaded | Load `.env.test` **before** importing app or DB. Set `DATABASE_URL` from `TEST_DATABASE_URL`. |
| DB conflicts | Use `maxWorkers: 1`. Clean DB in `beforeEach`. |
| Rate limiting blocks tests | Skip rate limiting when `NODE_ENV === 'test'`. |
| ES modules | Use `jest.config.cjs` and appropriate `moduleNameMapper` / `transform`. |
| Tokens "same" | JWT `iat` in same second can match; don't assert tokens differ. |
| Wrong error codes | Test actual controller behavior (e.g. `CANNOT_UNLINK_LAST_PROVIDER`), not idealized paths. |
| External APIs | Mock Google OAuth, email, FCM. **Do not** mock PostgreSQL. |
| Soft delete | Use `sql\`NOW()\`` or similar for `deleted_at` in Kysely updates. |
| UUID / Kysely types | Use `sql\`NOW()\`` for timestamp columns in raw updates if needed. |

---

## 9. Running Tests

### Local

- `npm test` — all tests.
- `npm run test:watch` — watch.
- `npm run test:coverage` — coverage.
- `npm run test:unit` / `npm run test:integration` — by type.
- `npm test -- tests/integration/auth/login.test.ts` — single file.

### CI (GitHub Actions)

- Workflow: `.github/workflows/test.yml`.
- PostgreSQL service container (`postgres:17-alpine`); health check via `pg_isready`.
- Env: `TEST_DATABASE_URL`, `JWT_SECRET`, `JWT_REFRESH_SECRET`, `GOOGLE_CLIENT_ID`, `NODE_ENV=test`.
- `npm run test:ci` runs tests; coverage uploaded to Codecov (if configured).

---

## 10. Test Examples

### Basic GET with Auth

```ts
it('should return user profile with valid token', async () => {
  const { accessToken, userId, user } = await createAuthenticatedUser();
  const res = await request(app)
    .get('/api/users/me')
    .set('Authorization', `Bearer ${accessToken}`);
  expect(res.status).toBe(200);
  expect(res.body).toMatchObject({
    id: userId,
    email: user.email,
    display_name: user.display_name,
    has_avatar: false,
  });
});
```

### 401 Unauthorized

```ts
it('should return 401 without Authorization header', async () => {
  const res = await request(app).get('/api/users/me');
  expect(res.status).toBe(401);
  expect(res.body.error.code).toBe('UNAUTHORIZED');
});
```

### Validation Error

```ts
it('should reject weak password', async () => {
  const res = await request(app)
    .post('/api/auth/register')
    .send({ email: 'a@b.com', password: '123', display_name: 'U' });
  expect(res.status).toBe(400);
  expect(res.body.error.code).toBe('INVALID_INPUT');
});
```

### Database Verification

```ts
it('should store user in database', async () => {
  await request(app)
    .post('/api/auth/register')
    .send({ email: 'a@b.com', password: 'Test123!@#', display_name: 'U' });
  const user = await testDb.selectFrom('users').selectAll().where('email', '=', 'a@b.com').executeTakeFirst();
  expect(user).toBeDefined();
  expect(user?.password_hash).toMatch(/^\$2[aby]\$/);
});
```

### Soft-Deleted User (404)

```ts
it('should return 404 for soft-deleted user', async () => {
  const { accessToken, userId } = await createAuthenticatedUser();
  await testDb.updateTable('users').set({ deleted_at: sql`NOW()` }).where('id', '=', userId).execute();
  const res = await request(app).get('/api/users/me').set('Authorization', `Bearer ${accessToken}`);
  expect(res.status).toBe(404);
  expect(res.body.error.code).toBe('NOT_FOUND');
});
```

### File Upload (Avatar)

```ts
const buf = await sharp({ create: { width: 10, height: 10, channels: 3, background: { r: 255, g: 0, b: 0 } } })
  .png().toBuffer();
const res = await request(app)
  .post('/api/users/me/avatar')
  .set('Authorization', `Bearer ${accessToken}`)
  .attach('avatar', buf, 'avatar.png');
expect(res.status).toBe(200);
expect(res.body).toEqual({ success: true, has_avatar: true });
```

### Mocking Google Auth

```ts
jest.mock('../../../src/services/googleAuth');
const mocked = require('../../../src/services/googleAuth') as jest.Mocked<typeof import('../../../src/services/googleAuth')>;
beforeEach(() => jest.clearAllMocks());
it('should authenticate with Google', async () => {
  mocked.verifyGoogleIdToken.mockResolvedValue({
    sub: 'google-123',
    email: 'u@gmail.com',
    name: 'User',
  });
  const res = await request(app).post('/api/auth/google').send({ id_token: 'fake' });
  expect(res.status).toBe(200);
});
```

---

## 11. Special Testing Scenarios

### Google OAuth

- Support multiple client IDs (web, Android). Service loops over them and uses `verifyIdToken` with each.
- Mock `verifyGoogleIdToken`; simulate success, audience mismatch, expired, invalid.
- Map errors to `ApplicationError` codes (`GOOGLE_TOKEN_AUDIENCE_MISMATCH`, `GOOGLE_TOKEN_EXPIRED`, `INVALID_GOOGLE_TOKEN`).
- Unit test `googleAuth` branches; integration test HTTP status and `error.code`.

### Soft Deletes

- Set `deleted_at` with `sql\`NOW()\`` in updates.
- Assert soft-deleted users get 404 on `/me` and cannot login.
- Assert endpoints exclude `deleted_at` rows (e.g. avatars, auth).

### Pagination

- Test `limit` and `offset` (or cursor); defaults and max.
- Test ordering (e.g. by `created_at` desc).

### ETag / Caching

- For `GET /api/users/:user_id/avatar`: send `If-None-Match` with ETag; expect 304 and empty body when unchanged.

### Invite Codes

- Use `createTestInviteCode` for valid, expired, or max-used codes.
- Test join: valid code, expired, max uses, already member, invalid code.

---

## 12. Troubleshooting

### Tests timeout / DB connection refused

- Ensure PostgreSQL is running and `TEST_DATABASE_URL` is correct.
- In CI, use health check (`pg_isready`) so DB is ready before tests.

### "relation X does not exist"

- Schema must be created before tests (e.g. in `beforeAll` in `setup.ts`). No separate migrate step in current setup; schema is applied in setup.

### Tests pass locally, fail in CI

- Compare env: `TEST_DATABASE_URL`, `NODE_ENV`, `JWT_*`, `GOOGLE_CLIENT_ID`.
- Ensure same Node version (e.g. 20) and `npm ci`.

### Rate limit errors in tests

- Confirm rate limiter skips when `NODE_ENV === 'test'`.

### Duplicate key / unique constraint

- `beforeEach` cleanup may be wrong or incomplete. Delete in reverse dependency order. Exact order in `cleanDatabase` (see `tests/setup.ts`): `progress_entries` → `goals` → `group_activities` → `invite_codes` → `group_memberships` → `groups` → `devices` → `password_reset_tokens` → `refresh_tokens` → `auth_providers` → `users`.

### Logger or other prod-only code in tests

- Logger level is `warn` in test; debug/info often not visible. Normal.

### Kysely type errors (`ValueExpression` etc.)

- Use `sql\`NOW()\`` for raw SQL in updates. Ensure `deleted_at` and similar columns match schema types.
