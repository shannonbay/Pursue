# Pursue Backend Testing Guide

**Complete guide to automated API testing with Jest, Supertest, and GitHub Actions**

---

## Table of Contents

1. [Overview](#overview)
2. [Testing Stack](#testing-stack)
3. [Installation](#installation)
4. [Configuration](#configuration)
5. [Test Database Setup](#test-database-setup)
6. [Writing Tests](#writing-tests)
7. [Test Helpers](#test-helpers)
8. [Running Tests](#running-tests)
9. [GitHub Actions CI/CD](#github-actions-cicd)
10. [Best Practices](#best-practices)
11. [Coverage Reports](#coverage-reports)

---

## Overview

Automated testing ensures your API endpoints work correctly and prevents regressions when making changes. This guide covers:

- ✅ Unit tests (services, utilities)
- ✅ Integration tests (API endpoints)
- ✅ End-to-end tests (full user flows)
- ✅ Database testing with PostgreSQL
- ✅ CI/CD with GitHub Actions

---

## Testing Stack

| Tool | Purpose | Why We Use It |
|------|---------|---------------|
| **Jest** | Test runner & assertions | Industry standard, great DX |
| **Supertest** | HTTP assertions | Test Express apps easily |
| **ts-jest** | TypeScript support | Run .ts test files directly |
| **PostgreSQL** | Test database | Same as production |

---

## Installation

### Install Testing Dependencies

```bash
npm install --save-dev jest @types/jest ts-jest supertest @types/supertest
```

### Complete `package.json` devDependencies:

```json
{
  "devDependencies": {
    "jest": "^29.7.0",
    "@types/jest": "^29.5.14",
    "ts-jest": "^29.2.5",
    "supertest": "^7.0.0",
    "@types/supertest": "^6.0.2",
    "@types/express": "^5.0.0",
    "@types/node": "^22.10.5",
    "@types/pg": "^8.11.10",
    "typescript": "^5.7.2",
    "tsx": "^4.19.2"
  }
}
```

---

## Configuration

### Create `jest.config.js`:

```javascript
/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src', '<rootDir>/tests'],
  testMatch: ['**/__tests__/**/*.ts', '**/?(*.)+(spec|test).ts'],
  collectCoverageFrom: [
    'src/**/*.ts',
    '!src/**/*.d.ts',
    '!src/server.ts', // Exclude entry point
    '!src/types/**'
  ],
  coverageThreshold: {
    global: {
      branches: 70,
      functions: 70,
      lines: 70,
      statements: 70
    }
  },
  setupFilesAfterEnv: ['<rootDir>/tests/setup.ts'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1'
  },
  testTimeout: 10000 // 10 seconds
};
```

### Add Test Scripts to `package.json`:

```json
{
  "scripts": {
    "test": "jest",
    "test:watch": "jest --watch",
    "test:coverage": "jest --coverage",
    "test:unit": "jest --testPathPattern=tests/unit",
    "test:integration": "jest --testPathPattern=tests/integration",
    "test:e2e": "jest --testPathPattern=tests/e2e",
    "test:ci": "jest --ci --coverage --maxWorkers=2"
  }
}
```

---

## Test Database Setup

### Why a Separate Test Database?

**Critical: Never run tests against your development or production database!**

- ✅ Tests clean up after themselves (delete all data)
- ✅ Tests run in parallel (isolated data)
- ✅ No risk of corrupting real data
- ✅ Consistent starting state for each test

### Create Test Database (Local)

```bash
# Connect to PostgreSQL
psql -U postgres

# Create test database
CREATE DATABASE pursue_test;

# Verify
\l  # List databases - should see pursue_test
\q  # Quit
```

### Environment Variables

```bash
# .env.test
TEST_DATABASE_URL=postgresql://postgres:password@localhost:5432/pursue_test
JWT_SECRET=test-secret-key-change-in-production
JWT_REFRESH_SECRET=test-refresh-secret-key
NODE_ENV=test
```

### Test Setup File: `tests/setup.ts`

```typescript
import { Pool } from 'pg';
import { Kysely, PostgresDialect } from 'kysely';
import { migrate } from '../src/database/migrate'; // Your migration runner

const TEST_DATABASE_URL = process.env.TEST_DATABASE_URL || 
  'postgresql://postgres:password@localhost:5432/pursue_test';

let testDb: Kysely<any>;
let pool: Pool;

// Run once before all tests
beforeAll(async () => {
  // Create database connection
  pool = new Pool({ connectionString: TEST_DATABASE_URL });
  testDb = new Kysely({ dialect: new PostgresDialect({ pool }) });
  
  // Run migrations to create schema
  await migrate(testDb);
  
  console.log('✅ Test database setup complete');
});

// Run after all tests
afterAll(async () => {
  // Close database connection
  await testDb.destroy();
  await pool.end();
  console.log('✅ Test database connection closed');
});

// Run before each test (clean slate)
beforeEach(async () => {
  // Delete all data in reverse order of dependencies
  await testDb.deleteFrom('progress_entries').execute();
  await testDb.deleteFrom('goals').execute();
  await testDb.deleteFrom('group_activities').execute();
  await testDb.deleteFrom('invite_codes').execute();
  await testDb.deleteFrom('group_memberships').execute();
  await testDb.deleteFrom('groups').execute();
  await testDb.deleteFrom('devices').execute();
  await testDb.deleteFrom('password_reset_tokens').execute();
  await testDb.deleteFrom('refresh_tokens').execute();
  await testDb.deleteFrom('auth_providers').execute();
  await testDb.deleteFrom('users').execute();
});

export { testDb };
```

**Why this works:**
- `beforeAll`: Runs once at the start (setup migrations)
- `beforeEach`: Runs before every test (clean data)
- `afterAll`: Runs once at the end (cleanup connections)

---

## Writing Tests

### Test Organization Structure

```
pursue-server/
├── src/
│   ├── routes/
│   ├── controllers/
│   ├── services/
│   └── app.ts
├── tests/
│   ├── setup.ts              # Global test setup
│   ├── helpers.ts            # Reusable test utilities
│   ├── unit/                 # Unit tests (services, utils)
│   │   ├── auth.service.test.ts
│   │   └── validation.test.ts
│   ├── integration/          # Integration tests (API endpoints)
│   │   ├── auth/
│   │   │   ├── register.test.ts
│   │   │   ├── login.test.ts
│   │   │   └── refresh.test.ts
│   │   ├── groups/
│   │   │   ├── create.test.ts
│   │   │   ├── get.test.ts
│   │   │   └── members.test.ts
│   │   ├── goals/
│   │   │   └── create.test.ts
│   │   └── progress/
│   │       └── log.test.ts
│   └── e2e/                  # End-to-end tests (full user flows)
│       └── user-journey.test.ts
└── jest.config.js
```

---

### Example Test 1: Auth Registration

**File: `tests/integration/auth/register.test.ts`**

```typescript
import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';

describe('POST /api/auth/register', () => {
  it('should register a new user successfully', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!',
        display_name: 'Test User'
      });

    expect(response.status).toBe(201);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.user).toMatchObject({
      email: 'test@example.com',
      display_name: 'Test User'
    });

    // Verify user exists in database
    const user = await testDb
      .selectFrom('users')
      .selectAll()
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    expect(user).toBeDefined();
    expect(user?.password_hash).toBeTruthy();
  });

  it('should reject duplicate email', async () => {
    // Create first user
    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!',
        display_name: 'First User'
      });

    // Try to register with same email
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Different123!',
        display_name: 'Second User'
      });

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('EMAIL_EXISTS');
  });

  it('should reject weak password', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: '123',
        display_name: 'Test User'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_INPUT');
  });

  it('should reject invalid email', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'not-an-email',
        password: 'Test123!',
        display_name: 'Test User'
      });

    expect(response.status).toBe(400);
  });

  it('should hash password (not store plaintext)', async () => {
    const password = 'Test123!';
    
    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: password,
        display_name: 'Test User'
      });

    const user = await testDb
      .selectFrom('users')
      .select(['password_hash'])
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    // Password should be hashed (bcrypt hash starts with $2b$)
    expect(user?.password_hash).not.toBe(password);
    expect(user?.password_hash).toMatch(/^\$2[aby]\$/);
  });
});
```

---

### Example Test 2: Auth Login

**File: `tests/integration/auth/login.test.ts`**

```typescript
import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import bcrypt from 'bcrypt';

describe('POST /api/auth/login', () => {
  beforeEach(async () => {
    // Create test user
    const passwordHash = await bcrypt.hash('Test123!', 10);
    await testDb
      .insertInto('users')
      .values({
        email: 'test@example.com',
        display_name: 'Test User',
        password_hash: passwordHash
      })
      .execute();
  });

  it('should login successfully with correct credentials', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'Test123!'
      });

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.user.email).toBe('test@example.com');
  });

  it('should reject incorrect password', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'WrongPassword123!'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_CREDENTIALS');
  });

  it('should reject non-existent email', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'nonexistent@example.com',
        password: 'Test123!'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_CREDENTIALS');
  });

  it('should create refresh token in database', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'Test123!'
      });

    // Verify refresh token stored in database
    const user = await testDb
      .selectFrom('users')
      .select(['id'])
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    const refreshToken = await testDb
      .selectFrom('refresh_tokens')
      .selectAll()
      .where('user_id', '=', user!.id)
      .executeTakeFirst();

    expect(refreshToken).toBeDefined();
    expect(refreshToken?.revoked_at).toBeNull();
  });
});
```

---

### Example Test 3: Protected Endpoints (JWT Auth)

**File: `tests/integration/users/me.test.ts`**

```typescript
import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import jwt from 'jsonwebtoken';

describe('GET /api/users/me', () => {
  let accessToken: string;
  let userId: string;

  beforeEach(async () => {
    // Create test user
    const user = await testDb
      .insertInto('users')
      .values({
        email: 'test@example.com',
        display_name: 'Test User'
      })
      .returning(['id'])
      .executeTakeFirst();

    userId = user!.id;

    // Generate valid JWT token
    accessToken = jwt.sign(
      { userId: userId, email: 'test@example.com' },
      process.env.JWT_SECRET!,
      { expiresIn: '1h' }
    );
  });

  it('should return user profile with valid token', async () => {
    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: userId,
      email: 'test@example.com',
      display_name: 'Test User'
    });
  });

  it('should reject request without token', async () => {
    const response = await request(app)
      .get('/api/users/me');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('MISSING_TOKEN');
  });

  it('should reject request with invalid token', async () => {
    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', 'Bearer invalid-token');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_TOKEN');
  });

  it('should reject expired token', async () => {
    const expiredToken = jwt.sign(
      { userId: userId, email: 'test@example.com' },
      process.env.JWT_SECRET!,
      { expiresIn: '0s' } // Already expired
    );

    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${expiredToken}`);

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('TOKEN_EXPIRED');
  });

  it('should reject token with missing Bearer prefix', async () => {
    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', accessToken); // Missing "Bearer "

    expect(response.status).toBe(401);
  });
});
```

---

### Example Test 4: Groups Endpoint (Complex)

**File: `tests/integration/groups/create.test.ts`**

```typescript
import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';

describe('POST /api/groups', () => {
  let accessToken: string;
  let userId: string;

  beforeEach(async () => {
    const auth = await createAuthenticatedUser();
    accessToken = auth.accessToken;
    userId = auth.userId;
  });

  it('should create group successfully', async () => {
    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Morning Runners',
        description: 'Daily accountability for morning runs',
        icon_emoji: '🏃'
      });

    expect(response.status).toBe(201);
    expect(response.body).toMatchObject({
      name: 'Morning Runners',
      description: 'Daily accountability for morning runs',
      icon_emoji: '🏃',
      creator_user_id: userId
    });

    // Verify group in database
    const group = await testDb
      .selectFrom('groups')
      .selectAll()
      .where('id', '=', response.body.id)
      .executeTakeFirst();

    expect(group).toBeDefined();

    // Verify creator is automatically added as member
    const membership = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', response.body.id)
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(membership).toBeDefined();
    expect(membership?.role).toBe('creator');
  });

  it('should reject group without name', async () => {
    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        description: 'Missing name',
        icon_emoji: '🏃'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_INPUT');
  });

  it('should reject group name longer than 100 characters', async () => {
    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'A'.repeat(101),
        icon_emoji: '🏃'
      });

    expect(response.status).toBe(400);
  });

  it('should reject unauthenticated request', async () => {
    const response = await request(app)
      .post('/api/groups')
      .send({
        name: 'Test Group',
        icon_emoji: '🏃'
      });

    expect(response.status).toBe(401);
  });

  it('should use default icon if not provided', async () => {
    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Test Group',
        description: 'Test'
      });

    expect(response.status).toBe(201);
    expect(response.body.icon_emoji).toBeTruthy();
  });
});
```

---

## Test Helpers

**File: `tests/helpers.ts`**

```typescript
import request from 'supertest';
import { app } from '../src/app';
import { testDb } from './setup';

/**
 * Create and authenticate a test user
 */
export async function createAuthenticatedUser(
  email: string = 'test@example.com',
  password: string = 'Test123!',
  displayName: string = 'Test User'
) {
  const response = await request(app)
    .post('/api/auth/register')
    .send({
      email,
      password,
      display_name: displayName
    });

  if (response.status !== 201) {
    throw new Error(`Failed to create user: ${JSON.stringify(response.body)}`);
  }

  return {
    userId: response.body.user.id,
    accessToken: response.body.access_token,
    refreshToken: response.body.refresh_token,
    user: response.body.user
  };
}

/**
 * Create a test group
 */
export async function createTestGroup(
  accessToken: string,
  name: string = 'Test Group',
  description: string = 'Test group description',
  iconEmoji: string = '🏃'
) {
  const response = await request(app)
    .post('/api/groups')
    .set('Authorization', `Bearer ${accessToken}`)
    .send({
      name,
      description,
      icon_emoji: iconEmoji
    });

  if (response.status !== 201) {
    throw new Error(`Failed to create group: ${JSON.stringify(response.body)}`);
  }

  return response.body;
}

/**
 * Create a test goal
 */
export async function createTestGoal(
  accessToken: string,
  groupId: string,
  title: string = 'Test Goal',
  cadence: 'daily' | 'weekly' | 'monthly' = 'daily',
  metricType: 'binary' | 'numeric' | 'duration' = 'binary'
) {
  const response = await request(app)
    .post(`/api/groups/${groupId}/goals`)
    .set('Authorization', `Bearer ${accessToken}`)
    .send({
      title,
      description: 'Test goal description',
      cadence,
      metric_type: metricType
    });

  if (response.status !== 201) {
    throw new Error(`Failed to create goal: ${JSON.stringify(response.body)}`);
  }

  return response.body;
}

/**
 * Log progress for a goal
 */
export async function logProgress(
  accessToken: string,
  goalId: string,
  value: number,
  userDate: string = '2026-01-17',
  userTimezone: string = 'America/New_York',
  note?: string
) {
  const response = await request(app)
    .post('/api/progress')
    .set('Authorization', `Bearer ${accessToken}`)
    .send({
      goal_id: goalId,
      value,
      user_date: userDate,
      user_timezone: userTimezone,
      note
    });

  if (response.status !== 201) {
    throw new Error(`Failed to log progress: ${JSON.stringify(response.body)}`);
  }

  return response.body;
}

/**
 * Wait for async operations (useful for testing background jobs)
 */
export function wait(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Generate random email for tests
 */
export function randomEmail(): string {
  const random = Math.random().toString(36).substring(7);
  return `test-${random}@example.com`;
}
```

**Usage Example:**

```typescript
import { createAuthenticatedUser, createTestGroup, createTestGoal } from '../../helpers';

describe('POST /api/progress', () => {
  it('should log progress successfully', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const group = await createTestGroup(accessToken);
    const goal = await createTestGoal(accessToken, group.id);

    const response = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goal.id,
        value: 1,
        user_date: '2026-01-17',
        user_timezone: 'America/New_York'
      });

    expect(response.status).toBe(201);
  });
});
```

---

## Running Tests

### Local Development

```bash
# Run all tests
npm test

# Run tests in watch mode (re-runs on file changes)
npm run test:watch

# Run with coverage report
npm run test:coverage

# Run specific test file
npm test -- tests/integration/auth/login.test.ts

# Run tests matching pattern
npm test -- --testNamePattern="should login successfully"

# Run only unit tests
npm run test:unit

# Run only integration tests
npm run test:integration

# Run in verbose mode (see all test names)
npm test -- --verbose
```

### Coverage Reports

```bash
npm run test:coverage

# Output:
# ----------------------------|---------|----------|---------|---------|
# File                        | % Stmts | % Branch | % Funcs | % Lines |
# ----------------------------|---------|----------|---------|---------|
# All files                   |   85.3  |   78.2   |   88.1  |   85.7  |
#  routes/                    |   92.1  |   85.4   |   95.2  |   92.3  |
#   auth.routes.ts            |   94.5  |   88.2   |   96.1  |   94.8  |
#   groups.routes.ts          |   89.7  |   82.6   |   94.3  |   89.9  |
#  services/                  |   78.5  |   71.0   |   81.0  |   78.9  |
#   auth.service.ts           |   81.2  |   74.3   |   84.6  |   81.5  |
# ----------------------------|---------|----------|---------|---------|
```

HTML coverage report generated at: `coverage/lcov-report/index.html`

---

## GitHub Actions CI/CD

### How Database Tests Work in CI

**The Challenge:**
- Your tests need a PostgreSQL database
- GitHub Actions runners don't have PostgreSQL by default
- Tests need to run in isolated environment

**The Solution:**
- GitHub Actions "Services" feature
- Runs PostgreSQL in a Docker container
- Available to your tests during CI run

### GitHub Actions Workflow

**File: `.github/workflows/test.yml`**

```yaml
name: Tests

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest

    # PostgreSQL Service Container
    services:
      postgres:
        image: postgres:17-alpine
        env:
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: test_password
          POSTGRES_DB: pursue_test
        # Health check ensures database is ready before tests run
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
        ports:
          - 5432:5432

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Run database migrations
        env:
          TEST_DATABASE_URL: postgresql://postgres:test_password@localhost:5432/pursue_test
        run: npm run migrate

      - name: Run tests
        env:
          TEST_DATABASE_URL: postgresql://postgres:test_password@localhost:5432/pursue_test
          JWT_SECRET: test-secret-key-for-ci
          JWT_REFRESH_SECRET: test-refresh-secret-for-ci
          NODE_ENV: test
        run: npm run test:ci

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v4
        with:
          files: ./coverage/lcov.info
          token: ${{ secrets.CODECOV_TOKEN }}
          fail_ci_if_error: true

      - name: Comment coverage on PR
        if: github.event_name == 'pull_request'
        uses: romeovs/lcov-reporter-action@v0.3.1
        with:
          lcov-file: ./coverage/lcov.info
          github-token: ${{ secrets.GITHUB_TOKEN }}
```

### How It Works (Step-by-Step)

#### **1. PostgreSQL Service Container Starts**

```yaml
services:
  postgres:
    image: postgres:17-alpine  # ← Downloads PostgreSQL 17 Docker image
    env:
      POSTGRES_DB: pursue_test  # ← Creates 'pursue_test' database
    ports:
      - 5432:5432  # ← Exposes PostgreSQL on localhost:5432
```

**What happens:**
- GitHub Actions downloads PostgreSQL Docker image
- Starts PostgreSQL in a container
- Creates `pursue_test` database automatically
- Makes it available at `localhost:5432`

#### **2. Health Check Waits for Database**

```yaml
options: >-
  --health-cmd pg_isready  # ← Checks if PostgreSQL is ready
  --health-interval 10s    # ← Check every 10 seconds
  --health-retries 5       # ← Try 5 times before failing
```

**What happens:**
- GitHub Actions runs `pg_isready` command
- Waits until PostgreSQL responds "ready"
- Only then proceeds to next steps
- Prevents "connection refused" errors in tests

#### **3. Migrations Run**

```yaml
- name: Run database migrations
  env:
    TEST_DATABASE_URL: postgresql://postgres:test_password@localhost:5432/pursue_test
  run: npm run migrate
```

**What happens:**
- Connects to PostgreSQL container at `localhost:5432`
- Runs all migrations (creates tables, indexes, etc.)
- Database schema now matches your local setup

#### **4. Tests Run**

```yaml
- name: Run tests
  env:
    TEST_DATABASE_URL: postgresql://postgres:test_password@localhost:5432/pursue_test
  run: npm run test:ci
```

**What happens:**
- Tests connect to PostgreSQL container
- Each test gets clean database state (beforeEach cleanup)
- Tests run exactly like they do locally
- Database is destroyed when job finishes

---

### Why This Approach Works

| Aspect | How It Works |
|--------|--------------|
| **Isolation** | Each CI run gets fresh PostgreSQL container |
| **Speed** | PostgreSQL 17 Alpine image is small (~80MB) |
| **Reliability** | Health checks ensure database is ready |
| **Same as Production** | PostgreSQL 17 matches your production version |
| **No Mocking** | Real database, real queries, real results |
| **Cost** | Free on GitHub Actions (2000 min/month for public repos) |

---

### Troubleshooting CI Issues

#### **Issue 1: Tests timeout waiting for database**

**Symptom:**
```
Error: connect ECONNREFUSED 127.0.0.1:5432
```

**Solution:**
Add health check to service configuration (already in example above)

---

#### **Issue 2: Migrations fail in CI**

**Symptom:**
```
Error: relation "users" does not exist
```

**Solution:**
Ensure migrations run before tests:

```yaml
- name: Run migrations
  run: npm run migrate
  
- name: Run tests  # ← Runs after migrations
  run: npm test
```

---

#### **Issue 3: Different behavior local vs CI**

**Symptom:**
Tests pass locally but fail in CI

**Solution:**
Check environment variables:

```yaml
env:
  TEST_DATABASE_URL: postgresql://postgres:test_password@localhost:5432/pursue_test
  NODE_ENV: test  # ← Important!
  JWT_SECRET: test-secret-key
```

---

### Advanced: Parallel Test Runs

For faster CI, run tests in parallel:

```yaml
jobs:
  test:
    strategy:
      matrix:
        test-group: [unit, integration, e2e]
    
    steps:
      - name: Run ${{ matrix.test-group }} tests
        run: npm run test:${{ matrix.test-group }}
```

Each test group gets its own PostgreSQL container!

---

## Best Practices

### 1. Follow AAA Pattern

```typescript
it('should create group successfully', async () => {
  // Arrange: Setup test data
  const { accessToken } = await createAuthenticatedUser();
  
  // Act: Perform the action
  const response = await request(app)
    .post('/api/groups')
    .set('Authorization', `Bearer ${accessToken}`)
    .send({ name: 'Test Group' });
  
  // Assert: Verify the results
  expect(response.status).toBe(201);
  expect(response.body.name).toBe('Test Group');
});
```

---

### 2. Test Edge Cases

```typescript
describe('POST /api/auth/register', () => {
  // Happy path
  it('should register user successfully', async () => { /* ... */ });
  
  // Edge cases
  it('should reject empty email', async () => { /* ... */ });
  it('should reject empty password', async () => { /* ... */ });
  it('should reject password < 8 characters', async () => { /* ... */ });
  it('should reject duplicate email', async () => { /* ... */ });
  it('should reject invalid email format', async () => { /* ... */ });
  it('should reject missing display_name', async () => { /* ... */ });
  it('should trim whitespace from email', async () => { /* ... */ });
  it('should handle SQL injection attempts', async () => { /* ... */ });
});
```

---

### 3. Use Descriptive Test Names

```typescript
// ✅ Good: Clear what's being tested
it('should reject login with incorrect password', async () => {});
it('should return 401 when JWT token is expired', async () => {});
it('should create group membership with creator role', async () => {});

// ❌ Bad: Vague, unclear
it('test login', async () => {});
it('should work', async () => {});
it('api test', async () => {});
```

---

### 4. Isolate Tests (No Shared State)

```typescript
// ✅ Good: Each test is independent
describe('Groups API', () => {
  beforeEach(async () => {
    // Clean state for each test
    await testDb.deleteFrom('groups').execute();
  });

  it('should create group', async () => {
    const { accessToken } = await createAuthenticatedUser();
    // Test creates its own data
  });

  it('should get group', async () => {
    const { accessToken } = await createAuthenticatedUser();
    // Test creates its own data (doesn't depend on previous test)
  });
});

// ❌ Bad: Tests depend on each other
describe('Groups API', () => {
  let groupId: string; // Shared state!

  it('should create group', async () => {
    // Creates group, saves to groupId
  });

  it('should get group', async () => {
    // Depends on previous test creating group
  });
});
```

---

### 5. Test Database State

```typescript
it('should create user in database', async () => {
  await request(app)
    .post('/api/auth/register')
    .send({ email: 'test@example.com', password: 'Test123!' });

  // Verify database state
  const user = await testDb
    .selectFrom('users')
    .selectAll()
    .where('email', '=', 'test@example.com')
    .executeTakeFirst();

  expect(user).toBeDefined();
  expect(user?.email).toBe('test@example.com');
});
```

---

### 6. Mock External Services (Not Database)

```typescript
// ✅ Good: Mock FCM push notifications
jest.mock('../src/services/fcm.service', () => ({
  sendNotification: jest.fn().mockResolvedValue({ success: true })
}));

// ✅ Good: Mock email service
jest.mock('../src/services/email.service', () => ({
  sendPasswordResetEmail: jest.fn().mockResolvedValue(true)
}));

// ❌ Bad: Don't mock database
// We want to test real database queries!
```

---

### 7. Use Test Timeouts for Slow Operations

```typescript
it('should handle large file upload', async () => {
  // This test might take longer
  const response = await request(app)
    .post('/api/upload')
    .attach('file', largFile);
  
  expect(response.status).toBe(201);
}, 30000); // 30 second timeout
```

---

### 8. Clean Up Resources

```typescript
afterEach(async () => {
  // Close any open connections
  await cleanupConnections();
});

afterAll(async () => {
  // Close database connection
  await testDb.destroy();
});
```

---

## Coverage Reports

### Viewing Coverage Locally

```bash
# Generate coverage report
npm run test:coverage

# Open HTML report in browser
# Windows
start coverage/lcov-report/index.html

# macOS
open coverage/lcov-report/index.html

# Linux
xdg-open coverage/lcov-report/index.html
```

### Coverage Targets

| Metric | Target | Why |
|--------|--------|-----|
| **Statements** | 70%+ | Most code paths tested |
| **Branches** | 70%+ | Most if/else paths tested |
| **Functions** | 70%+ | Most functions called |

---

## Google OAuth Testing – Key Learnings

These patterns are non‑obvious and worth preserving for future agents:

1. **Multi‑client ID verification**
   - Backend must often accept tokens for **both** Web and Android client IDs:
     - `GOOGLE_CLIENT_ID` (Web)
     - `GOOGLE_ANDROID_CLIENT_ID` (Android)
   - Service logic: build an `allowedClientIds` array and **loop**:
     - For each `clientId` → new `OAuth2Client(clientId)` → `verifyIdToken({ idToken, audience: clientId })`
     - If **all** fail → throw a single `GOOGLE_TOKEN_AUDIENCE_MISMATCH` error including the list of allowed IDs.

2. **Unit‑testing services that read env at import time**
   - If a service reads env vars during module init, tests should:
     - Set `process.env` **before** importing the service (or rely on runtime env only).
     - Prefer a single import of the service and then only mutate env for simple branches (e.g., “no client IDs configured”), instead of repeatedly re‑importing with different env.

3. **Error mapping strategy**
   - The Google auth service maps errors to `ApplicationError` codes in two layers:
     - **Before payload parsing**: when all `verifyIdToken` calls fail → `GOOGLE_TOKEN_AUDIENCE_MISMATCH`.
     - **After payload parsing**: catch block inspects `error.message` for substrings (`'audience'`, `'expired'`, `'signature'`) and maps to:
       - `GOOGLE_TOKEN_AUDIENCE_MISMATCH`
       - `GOOGLE_TOKEN_EXPIRED`
       - `INVALID_GOOGLE_TOKEN`
       - Fallback: `INVALID_GOOGLE_TOKEN`
   - Tests should **match the actual control flow** (e.g., generic verify‑time failures typically surface as audience mismatch, not fine‑grained codes).

4. **Where to put coverage pressure**
   - Integration tests for `/api/auth/google` focus on:
     - HTTP status codes
     - `error.code` values (`INVALID_GOOGLE_TOKEN`, `GOOGLE_TOKEN_AUDIENCE_MISMATCH`, `GOOGLE_TOKEN_EXPIRED`, etc.)
   - Unit tests for `googleAuth` focus on:
     - All branches of env/config handling
     - All branches of token payload validation
     - All branches of catch‑block error mapping

| **Lines** | 70%+ | Most lines executed |

### Improving Coverage

```typescript
// Uncovered code shows in red in coverage report
// Add tests for uncovered branches

if (user.role === 'admin') {
  // ← Add test for admin role
} else {
  // ← Add test for non-admin role
}
```

---

## Summary

### Quick Start Checklist

```
Setup:
☐ npm install --save-dev jest @types/jest ts-jest supertest @types/supertest
☐ Create jest.config.js
☐ Create tests/setup.ts
☐ Create pursue_test database
☐ Add test scripts to package.json

Writing Tests:
☐ Create tests/integration/auth/register.test.ts
☐ Create tests/integration/auth/login.test.ts
☐ Create test helpers in tests/helpers.ts
☐ Follow AAA pattern (Arrange, Act, Assert)

Running Tests:
☐ npm test (local)
☐ npm run test:coverage (check coverage)
☐ Create .github/workflows/test.yml (CI)

Coverage:
☐ Aim for 70%+ coverage
☐ View HTML report: coverage/lcov-report/index.html
☐ Upload to Codecov (optional)
```

---

## Implementation Lessons Learned

These are practical lessons learned while implementing tests for this project:

### 1. Environment Variable Loading

The test setup must load `.env.test` BEFORE other imports that use environment variables:

```typescript
// tests/setup.ts - MUST be at the top
import dotenv from 'dotenv';
import path from 'path';

dotenv.config({ path: path.resolve(process.cwd(), '.env.test') });

// Now import modules that use process.env
import { db } from '../src/database';
```

**Also set `DATABASE_URL`** so the app's database module uses the test database:

```typescript
const TEST_DATABASE_URL = process.env.TEST_DATABASE_URL;
process.env.DATABASE_URL = TEST_DATABASE_URL; // App module uses this
```

### 2. Jest Config with ES Modules

When `package.json` has `"type": "module"`, Jest config must use `.cjs` extension:

```bash
# Rename jest.config.js to jest.config.cjs
mv jest.config.js jest.config.cjs
```

### 3. Disable Rate Limiting in Tests

Rate limiters will block tests that make multiple requests. Add a skip condition:

```typescript
// src/middleware/rateLimiter.ts
const isTest = process.env.NODE_ENV === 'test';

export const authLimiter = rateLimit({
  skip: () => isTest,  // Skip rate limiting in tests
  windowMs: 15 * 60 * 1000,
  max: 5,
  // ...
});
```

### 4. Run Tests Sequentially

Database tests can conflict when running in parallel. Use `maxWorkers: 1`:

```javascript
// jest.config.cjs
module.exports = {
  // ...
  maxWorkers: 1,  // Run tests sequentially
  forceExit: true // Exit after tests complete
};
```

### 5. Mock External Services

Mock external APIs (like Google OAuth) that can't be called in tests:

```typescript
// tests/integration/auth/google.test.ts
import * as googleAuth from '../../../src/services/googleAuth';

jest.mock('../../../src/services/googleAuth');
const mockedGoogleAuth = googleAuth as jest.Mocked<typeof googleAuth>;

beforeEach(() => {
  jest.clearAllMocks();
});

it('should authenticate with Google', async () => {
  mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
    sub: 'google-user-123',
    email: 'user@gmail.com',
    name: 'Test User',
  });

  // Test code...
});
```

### 6. Test What Actually Happens

Write tests that match actual controller behavior, not idealized behavior:

```typescript
// BAD: Testing unreachable code path
it('should return PROVIDER_NOT_FOUND', async () => {
  // This error can never occur given the constraints
});

// GOOD: Test actual behavior
it('should reject unlinking when user has only one provider', async () => {
  // User has 1 provider, tries to unlink any provider
  // Gets CANNOT_UNLINK_LAST_PROVIDER (not PROVIDER_NOT_FOUND)
});
```

### 7. JWT Tokens Within Same Second

JWT tokens generated within the same second have identical `iat` claims and may be identical:

```typescript
// Don't assert tokens are different if generated quickly
it('should work multiple times', async () => {
  const response1 = await refresh(token);
  const response2 = await refresh(token);

  expect(response1.status).toBe(200);
  expect(response2.status).toBe(200);
  // Note: tokens may be identical if generated within same second
});
```

---

### Key Takeaways

✅ **Use separate test database** - Never test against dev/prod
✅ **Clean state between tests** - Delete all data in beforeEach
✅ **Test real database** - Don't mock PostgreSQL
✅ **GitHub Actions services** - PostgreSQL in Docker container
✅ **Health checks** - Wait for database to be ready
✅ **Coverage targets** - Aim for 70%+ across all metrics
✅ **Test edge cases** - Not just happy paths
✅ **Load env vars first** - Before importing app modules
✅ **Disable rate limiting** - Skip in test environment
✅ **Run sequentially** - Avoid database conflicts
✅ **Mock external APIs** - Google OAuth, email services, etc.

---

**Version:** 1.1
**Last Updated:** January 2026
**PostgreSQL Version:** 17.x
