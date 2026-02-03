# Backend Spec - Group Leave Auto-Promotion and Last-Member Cleanup

When the last admin or creator leaves a group, the backend automatically promotes one remaining member (the last-active member, with tie-break by join order). When the last member leaves, the server deletes the group and all related data (invite codes, activity log, goals, progress, memberships) via CASCADE.

---

## Current Behavior (Before This Spec)

- A **creator** cannot leave when other members exist; the API returns 400 `CANNOT_LEAVE_AS_CREATOR`.
- Group deletion only happens when the **creator** leaves and is the **sole** member.
- A non-creator can leave at any time; their membership is removed and `member_left` activity and FCM are sent.

---

## Modified Endpoint: DELETE /api/groups/:group_id/members/me (Leave Group)

**Summary of changes:**

1. **Auto-promotion:** If the leaving user is the last admin/creator and there are other active members, the server automatically promotes one remaining member (see selection rules below) before removing the leaver. The creator may leave; they are no longer blocked.
2. **Last-member cleanup:** After removing the leaving user's membership, if zero members remain, the server deletes the group (CASCADE removes all related data).

No new endpoints. Behavior changes only in `leaveGroup` (see [src/controllers/groups.ts](src/controllers/groups.ts)).

---

## Auto-Promotion When Last Admin/Creator Leaves

### Trigger

User calls `DELETE /api/groups/:group_id/members/me`. **Before** removing this member, the server checks: after this leave, will there be at least one remaining member and **zero** remaining members with role `creator` or `admin`? If yes, run auto-promotion first (select from the set of current members **excluding** the leaver).

### Candidate Set

All current **active** members of the group (`status = 'active'`) **except** the user who is leaving.

### "Last Active" Definition

For each candidate, compute a single **last activity** timestamp as the **maximum** of:

- Latest `group_activities.created_at` for that user in this group
- Latest `progress_entries.logged_at` for that user in this group (join via `goals`: `goals.group_id = :group_id`)
- Latest `devices.last_active` for that user (max across their devices)

If a user has no rows in any of these, use their `group_memberships.joined_at` as their last activity (they remain eligible but rank last).

### Selection Rule

1. Order candidates by last activity timestamp **descending** (most recent first).
2. If multiple candidates have last activity within a **48-hour window** of the most recent timestamp (i.e. "similarly active"), **tie-break by `joined_at` ascending** (member who joined first is promoted).
3. Pick the single chosen member.

### Promotion Action

- Set the chosen member's `group_memberships.role` to **`admin`** if the person leaving was an admin, or to **`creator`** if the person leaving was the creator (so the group always has exactly one creator).
- If the leaver was the **creator**: also update `groups.creator_user_id` to the promoted user's id and set their membership role to `creator`.

### Order of Operations in leaveGroup

1. Resolve membership; ensure user is a member (else 403).
2. Count current members and count current admins/creators (roles `creator` or `admin`).
3. **If** leaver is the last admin/creator **and** there are other active members:
   - Compute "last active" for each remaining active member (excluding leaver).
   - Select one member per selection rule.
   - Update that member's role (and optionally `groups.creator_user_id` if leaver was creator).
   - Create group_activity (see Group Activity below).
   - Send FCM to group and to promoted user (see FCM below).
4. Delete the leaving user's membership.
5. **If** no members remain after delete: run last-member cleanup (delete group); return 204.
6. **Else:** Create `member_left` group_activity, send `member_left` FCM (existing behavior), return 204.

---

## Group Activity for Auto-Promotion

**Activity type:** `member_promoted` (existing type; backend can set metadata to indicate automatic promotion).

**Row:**

- `group_id`: the group
- `user_id`: promoted user (or null if system-initiated)
- `activity_type`: `member_promoted`
- `metadata`: e.g. `{ "promoted_user_id": "<id>", "new_role": "admin" | "creator", "reason": "auto_last_admin_left" }` so the feed can show "X was promoted to admin" / "X is now the group creator"

Existing [activity.service](src/services/activity.service.ts) and `ACTIVITY_TYPES.MEMBER_PROMOTED` can be used; metadata distinguishes automatic vs manual promotion.

---

## FCM Notifications for Auto-Promotion

### To the group (all active members)

Use existing `sendGroupNotification` with `membershipStatus: 'active'`:

- **Title:** group name
- **Body:** `{display_name} is now an admin` or `{display_name} is now the group creator`
- **data:** `type: "member_promoted"`, `group_id`, `promoted_user_id`, `new_role` (`"admin"` or `"creator"`)

### To the promoted user (recommended)

Send a direct notification so they know they have new permissions:

- **Title:** e.g. "You're now an admin" / "You're now the group creator"
- **Body:** e.g. "You're now an admin of {group_name}" / "You're now the creator of {group_name}"
- **data:** `type: "member_promoted"`, `group_id`, `new_role`

Use existing FCM helpers (e.g. send to all devices for that user). See [src/services/fcm.service.ts](src/services/fcm.service.ts).

---

## Last-Member Cleanup

### Trigger

After deleting the leaving user's membership in `leaveGroup`, the group has **zero** members (no remaining rows in `group_memberships` for that `group_id`).

### Action

Delete the group row: `DELETE FROM groups WHERE id = :group_id`. Rely on existing **ON DELETE CASCADE** to remove:

- `group_memberships`
- **`invite_codes`** — current schema ([migrations/003_simplify_invite_codes.sql](migrations/003_simplify_invite_codes.sql)): columns `id`, `group_id`, `code`, `created_by_user_id`, `created_at`, `revoked_at`; one active code per group (`revoked_at` NULL). CASCADE removes all invite code rows for that group (active and revoked).
- `goals` (then `progress_entries` via goal CASCADE)
- `group_activities`

When the last member leaves, the server deletes the group. This removes the group and all related data: **invite codes** (including any active or revoked codes for that group), **activity log**, **goals**, **progress entries**, and **memberships**. No separate cleanup step is required.

### Client and API

- No FCM or group activity is sent for the deleted group (it no longer exists).
- API still returns 204. The client may show a short message such as "You left the group; the group was removed because there were no other members."

### Interaction with Current Behavior

Today, "creator + sole member" leaves → group is deleted. This spec generalizes: **any** leave that results in zero members triggers group delete. So: last member leaves (whether creator or not) → delete group and CASCADE cleanup.

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| One creator and one admin | When one leaves, the other remains admin/creator → no auto-promotion. When the last of them leaves, one "member" remains → promote that member, then remove leaver's membership; if that was the last member (only one member was left), then cleanup (delete group). |
| Last admin leaves, one member remains | Auto-promote that member to admin, remove leaver, create activity and FCM. One member remains; no cleanup. |
| Last creator leaves, multiple members remain | Auto-promote one member to creator (update `groups.creator_user_id` and their role to `creator`), remove leaver, activity and FCM. |
| Last member leaves (after promotion or was sole member) | After deleting membership, zero members remain → delete group (CASCADE). Return 204. |
| Creator leaves as sole member | Same as today: delete group (CASCADE). No promotion (no remaining members). |

---

## References

- [src/controllers/groups.ts](src/controllers/groups.ts) — `leaveGroup`
- [src/services/activity.service.ts](src/services/activity.service.ts) — `createGroupActivity`, `ACTIVITY_TYPES.MEMBER_PROMOTED`
- [src/services/fcm.service.ts](src/services/fcm.service.ts) — `sendGroupNotification`, single-user notification for promoted user
- [migrations/003_simplify_invite_codes.sql](migrations/003_simplify_invite_codes.sql) — invite_codes schema (revoked_at; CASCADE from groups)
- [specs/backend/02-database-schema.md](specs/backend/02-database-schema.md) — groups, group_memberships, group_activities

No database schema changes are required; only behavior changes in `leaveGroup` and optionally a small helper or query for "last active" and promotion selection.
