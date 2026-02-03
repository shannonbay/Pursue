# Approval Queue Spec Alignment Plan

## Overview

Align the existing pending-member-approval implementation with `specs/Backend-Spec-Approval-Queue-Updates.md` and make three pending-member restriction tests pass: (1) `member_count` excludes pending members, (2) pending members cannot list group goals, (3) pending members cannot log progress.

---

## Part A: Spec alignment (existing plan items)

### A1. Join FCM body: use display name, not email

**Spec:** "Shannon wants to join Morning Runners" (display name).

**Change:** In [src/controllers/groups.ts](src/controllers/groups.ts) join handler, fetch the requesting user's `display_name` and use it in the FCM body: `${displayName} wants to join ${group.name}`.

---

### A2. GET /api/groups/:group_id/members: order by role

**Spec:** "Order by role (creator first, then admins, then members)."

**Change:** In `listMembers`, add ordering by role (e.g. `CASE role WHEN 'creator' THEN 1 WHEN 'admin' THEN 2 ELSE 3 END`), then by `joined_at` asc.

---

### A3. Approval FCM: notify only active members

**Spec:** "Send FCM to all active members."

**Change:** In [src/services/fcm.service.ts](src/services/fcm.service.ts), add optional `membershipStatus?: 'active'` to `sendGroupNotification` and filter devices by it. In `approveMember`, call with `membershipStatus: 'active'`.

---

### A4. Approve/decline: 404 for "no pending request"

**Spec:** "404: User not found or no pending request."

**Change:** In approve and decline handlers, throw 404 (not 400) when membership exists but status is not 'pending'. Update tests that expect 400 to expect 404.

---

## Part B: Pending member restriction tests (new)

The following tests **already exist**; backend changes are required so they pass.

### B1. Groups: pending member does not count towards group member_count

**Test:** [tests/integration/groups/groups.test.ts](tests/integration/groups/groups.test.ts) — `describe('Pending member restrictions')` → `pending member does not count towards group member_count`.

**Expectation:** Creator creates group → GET group → `member_count === 1`. Second user joins (no approve) → GET group again → `member_count === 1`.

**Backend change:** Count only `status='active'` memberships wherever `member_count` is returned:

| Location | File | Current behavior | Change |
|----------|------|------------------|--------|
| GET /api/groups/:group_id | [src/controllers/groups.ts](src/controllers/groups.ts) `getGroup` | Left join all `group_memberships`, count all | Restrict count to active: e.g. add `.andOn(sql\`group_memberships.status = 'active'\`)` to the join, or use a conditional count so only active rows are counted. |
| POST /api/groups (create) | [src/controllers/groups.ts](src/controllers/groups.ts) `createGroup` | Count all memberships for new group | Add `.where('status', '=', 'active')` to the count query (creator is active; result stays 1). |
| GET /api/users/me/groups | [src/controllers/users.ts](src/controllers/users.ts) | Subquery `COUNT(*) FROM group_memberships gm WHERE gm.group_id = groups.id` | Add `AND gm.status = 'active'` in the subquery. |

POST /api/groups/join response already uses active-only count (verified in code). No change there.

---

### B2. Goals: pending member cannot list group goals

**Test:** [tests/integration/goals/goals.test.ts](tests/integration/goals/goals.test.ts) — `describe('Pending member restrictions')` → `pending member cannot list group goals`.

**Expectation:** Pending user calls GET /api/groups/:groupId/goals → 403, `error.code === 'FORBIDDEN'`.

**Backend change:**

- In [src/services/authorization.ts](src/services/authorization.ts):
  - Have `getGroupMembership` also select `status` (add to `GroupMembershipResult`: `status?: string`).
  - Add `requireActiveGroupMember(userId, groupId)`: get membership; if none or `status !== 'active'`, throw 403 FORBIDDEN; otherwise return membership.
- In [src/controllers/goals.ts](src/controllers/goals.ts) for **listGoals** (GET /api/groups/:group_id/goals): use `requireActiveGroupMember(req.user.id, group_id)` instead of `requireGroupMember`. That way pending (and declined) members get 403.

---

### B3. Progress: pending member cannot log progress

**Test:** [tests/integration/progress/progress.test.ts](tests/integration/progress/progress.test.ts) — `describe('Pending member restrictions')` → `pending member cannot log progress`.

**Expectation:** Pending user POSTs /api/progress with goal_id, value, user_date, user_timezone → 403, `error.code === 'FORBIDDEN'`.

**Backend change:** In [src/controllers/progress.ts](src/controllers/progress.ts) for **createProgress** (POST /api/progress), after resolving the goal, call `requireActiveGroupMember(req.user.id, goal.group_id)` instead of `requireGroupMember`. That way pending (and declined) members get 403 when logging progress.

---

## Implementation order

1. **Authorization:** Add `status` to `getGroupMembership` result; add `requireActiveGroupMember`.
2. **member_count:** Update getGroup, createGroup, and GET /api/users/me/groups to count only active members (so B1 passes).
3. **Goals:** Use `requireActiveGroupMember` in listGoals (so B2 passes).
4. **Progress:** Use `requireActiveGroupMember` in createProgress (so B3 passes).
5. **FCM service:** Add optional `membershipStatus` to `sendGroupNotification`; use it in approveMember.
6. **Groups controller:** Join FCM body use display_name; listMembers order by role; approve pass membershipStatus; approve/decline throw 404 for non-pending.
7. **Tests:** Update any approve/decline tests that expect 400 for non-pending to expect 404; run groups, goals, and progress integration tests.

---

## Files to touch

| File | Changes |
|------|--------|
| [src/services/authorization.ts](src/services/authorization.ts) | Select `status` in `getGroupMembership`; extend `GroupMembershipResult`; add `requireActiveGroupMember`. |
| [src/controllers/groups.ts](src/controllers/groups.ts) | getGroup: count only active members. createGroup: count only active. Join: fetch display_name for FCM body. listMembers: order by role then joined_at. approve: pass membershipStatus 'active'; throw 404 for non-pending. decline: throw 404 for non-pending. |
| [src/controllers/goals.ts](src/controllers/goals.ts) | listGoals: use `requireActiveGroupMember` instead of `requireGroupMember`. |
| [src/controllers/progress.ts](src/controllers/progress.ts) | createProgress: use `requireActiveGroupMember` instead of `requireGroupMember`. |
| [src/controllers/users.ts](src/controllers/users.ts) | GET /api/users/me/groups: in member_count subquery, add `AND gm.status = 'active'`. |
| [src/services/fcm.service.ts](src/services/fcm.service.ts) | Add optional `membershipStatus?: 'active'` to `sendGroupNotification`; filter by status when provided. |
| [tests/integration/groups/groups.test.ts](tests/integration/groups/groups.test.ts) | If any test expects 400 for "member is not pending", change to expect 404. |

No new tests to add; the three "Pending member restrictions" tests already exist in groups, goals, and progress test files.
