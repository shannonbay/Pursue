---
name: testing
description: This skill should be used when the user asks to "write tests", "fix failing tests", "run tests", "add test coverage", "add tests for X", or needs to create, fix, or run Jest/Supertest integration or unit tests for the Pursue backend API.
---

# Testing

Use this skill when working on tests: writing new tests, fixing failures, running tests, or improving coverage. It applies to the Pursue backend (Jest, Supertest, PostgreSQL test DB).

## Overview

- **Stack**: Jest, Supertest, ts-jest, PostgreSQL test DB. No mocks for the database; mock external services (Google OAuth, email, FCM).
- **Layout**: `tests/setup.ts`, `tests/helpers.ts`, `tests/unit/`, `tests/integration/` (e.g. `auth/`, `groups/`, `users/`, `devices/`).
- **Patterns**: AAA (Arrange, Act, Assert); isolated tests; `beforeEach` cleanup; helpers for users, tokens, invite codes, etc.

## How to Use

1. **Run tests**: Use `npm test`, `npm run test:integration`, `npm test -- path/to/test.ts`, or `npm run test:coverage` as needed.
2. **Write tests**: Follow patterns in `tests/integration/`. Use helpers from `tests/helpers.ts` (`createAuthenticatedUser`, `createTestUser`, `createGoogleUser`, `createPasswordResetToken`, `createTestInviteCode`, `randomEmail`, etc.). Place integration tests under `tests/integration/<domain>/*.test.ts`.
3. **Fix failures**: Check **references/testing-reference.md** for common pitfalls (env, DB cleanup order, rate limiting, Kysely types, soft deletes). Ensure `tests/setup.ts` loads `.env.test` first and sets `DATABASE_URL`; use `maxWorkers: 1` in Jest.
4. **Coverage**: Aim for 70%+ (branches, functions, lines, statements). Use `npm run test:coverage` and review `coverage/lcov-report/index.html`.

## Optional Resources

- **`references/testing-reference.md`** – Full reference: commands, setup, helpers, patterns by endpoint type, examples, troubleshooting. Load when you need detailed guidance.

## Quick Reference

| Command | Purpose |
|--------|---------|
| `npm test` | Run all tests |
| `npm run test:watch` | Watch mode |
| `npm run test:coverage` | Coverage report |
| `npm run test:unit` | Unit only |
| `npm run test:integration` | Integration only |
| `npm test -- path/to/test.ts` | Single file |

Helpers: `createAuthenticatedUser`, `createTestUser`, `createGoogleUser`, `createPasswordResetToken`, `createExpiredPasswordResetToken`, `createTestInviteCode`, `createTestGroupActivity`, `randomEmail`, `wait`. See `tests/helpers.ts`.

Source: [Testing-Guide.md](../../../Testing-Guide.md) (project root) and existing tests in `tests/integration/`.
