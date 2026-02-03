# Spec: Group Leave Auto-Promotion and Last-Member Cleanup

## Overview

Add a spec in **specs/** that defines auto-promotion when the last admin/creator leaves (promote last-active member with tie-break by joined_at), FCM and group activity for that case, and full group cleanup when the last member leaves (delete group so CASCADE removes invite codes, activity log, goals, progress, memberships).

---

## Invite codes schema (for spec accuracy)

**Source:** [migrations/003_simplify_invite_codes.sql](migrations/003_simplify_invite_codes.sql) — simplified schema in use.

**Current `invite_codes` table:**
- `id`, `group_id`, `code`, `created_by_user_id`, `created_at`, **`revoked_at`**
- No `max_uses`, `current_uses`, or `expires_at` (removed in migration 003)
- `revoked_at` NULL = code is active; non-NULL = code was revoked (replaced by new code)
- One active code per group (partial unique index on `group_id` WHERE `revoked_at IS NULL`)
- `group_id` REFERENCES groups(id) **ON DELETE CASCADE**

When the spec says “clean up group from database incl invite codes”, it should state that deleting the group row CASCADE-deletes all `invite_codes` rows for that group (the simplified schema with `revoked_at` only).

---

## 1. Auto-promotion when last admin/creator leaves

**Context:** Today, a creator cannot leave if other members exist ([groups.ts](src/controllers/groups.ts) throws `CANNOT_LEAVE_AS_CREATOR`). The spec will define new behavior: when the last admin or creator leaves, the backend automatically promotes one remaining member instead of blocking the leave.

**Trigger:** User calls `DELETE /api/groups/:group_id/members/me`. After removing this member, there is at least one remaining member but **zero** remaining members with role `creator` or `admin`. Then run auto-promotion **before** completing the leave (so we promote from the set that includes everyone except the leaver).

**Candidate set:** All current **active** members of the group **except** the user who is leaving (only `status = 'active'` memberships).

**“Last active” definition:** For each candidate, define a single “last activity” timestamp as the **maximum** of:
- Latest `group_activities.created_at` for that user in this group
- Latest `progress_entries.logged_at` for that user in this group (join via goals: `goals.group_id = :group_id`)
- Latest `devices.last_active` for that user (max across their devices)

If a user has no rows in any of these, use `joined_at` as their “last activity” (so they are still eligible but rank last).

**Selection:** Order candidates by this “last activity” timestamp **descending**. If multiple candidates have last activity within a **48-hour window** of the most recent one (e.g. both active in last 48 hours), **tie-break by `joined_at` ascending** (member who joined first is promoted). Pick the single chosen member.

**Promotion action:**
- Set chosen member’s `group_memberships.role` to `admin` **or** to `creator` if the person leaving was the creator (so the group always has exactly one creator).
- If the leaver was the creator: update `groups.creator_user_id` to the promoted user’s id and set their role to `creator`.

**Group activity:** Insert one `group_activities` row:
- `activity_type`: use existing `member_promoted` (or add a dedicated type like `auto_promoted` if the spec prefers to distinguish system vs manual promotion).
- `user_id`: promoted user (or null if system-initiated; existing [activity.service](src/services/activity.service.ts) supports null).
- `metadata`: e.g. `{ "promoted_user_id": "<id>", "new_role": "admin" | "creator", "reason": "auto_last_admin_left" }` so the feed can show “X was promoted to admin” / “X is now the group creator”.

**FCM:**
- **To the group (all active members):** e.g. title = group name, body = “{display_name} is now an admin” or “{display_name} is now the group creator”, `data.type` = `member_promoted` (or `auto_promoted`), include `group_id`, `promoted_user_id`, `new_role`.
- **To the promoted user (optional but recommended):** e.g. “You’re now an admin of {group_name}” / “You’re now the creator of {group_name}” so they know they have new permissions.

**Order of operations in `leaveGroup`:**
1. Resolve membership and ensure user is member.
2. Count current members and count current admins/creators.
3. If leaver is last admin/creator and there are other active members: compute “last active” for remaining members, select one, run promotion (update role, optionally `groups.creator_user_id`), create group_activity, send FCM (group + promoted user).
4. Delete the leaving user’s membership.
5. If **no** members remain after delete: run last-member cleanup (see below).
6. Else: create `member_left` activity, send `member_left` FCM (current behavior), return 204.

**Edge cases to specify:** Group with one creator and one admin: when one leaves, the other remains admin/creator so no auto-promotion. When the last of them leaves, one “member” remains → promote that member; then leaver’s membership is removed; if that was the last member, cleanup.

---

## 2. Last member leaves – full group cleanup

**Trigger:** After deleting the leaving user’s membership in `leaveGroup`, the group has **zero** members (no remaining rows in `group_memberships` for that `group_id`).

**Action:** Delete the group row: `DELETE FROM groups WHERE id = :group_id`. Rely on existing **CASCADE** to remove:
- `group_memberships`
- **`invite_codes`** — current schema ([003_simplify_invite_codes.sql](migrations/003_simplify_invite_codes.sql)): one active code per group (`revoked_at` NULL); CASCADE removes all invite code rows for the group
- `goals` (then `progress_entries` via goal CASCADE)
- `group_activities`

The spec will state explicitly: “When the last member leaves, the server deletes the group. This removes the group and all related data: invite codes (including any active or revoked codes for that group), activity log, goals, progress entries, and memberships. No separate cleanup step is required.”

**No FCM or group activity** for the deleted group (it no longer exists). The API still returns 204. Optionally the spec can note that the client may show a short message like “You left the group; the group was removed because there were no other members.”

**Interaction with current behavior:** Today, “creator + sole member” leaves → group is deleted. The spec will generalize: **any** leave that results in zero members triggers group delete. So: last member leaves (whether creator or not) → delete group and CASCADE cleanup.

---

## 3. Spec document structure

- **Title and short summary** (auto-promotion + last-member cleanup).
- **Current behavior** (1–2 sentences): creator cannot leave when others exist; only creator-as-sole-member triggers group delete.
- **Invite codes / cleanup:** Reference the simplified invite_codes schema (revoked_at; one active code per group); CASCADE removes all invite code rows when group is deleted.
- **Auto-promotion:** trigger, “last active” definition, 48-hour window and tie-break, promotion rules (admin vs creator, `groups.creator_user_id`), order of operations in `leaveGroup`.
- **Group activity:** activity type and metadata for auto-promotion.
- **FCM:** payloads for group notification and promoted-user notification; `data.type` and keys.
- **Last-member cleanup:** when it runs, what gets removed (list CASCADE tables with correct invite_codes description), no FCM/activity.
- **Edge cases:** single admin/creator; creator and admin both leave in sequence; only one member left (promote then delete membership then cleanup).

No database schema changes are required; only behavior changes in `leaveGroup` (and possibly a small helper or query for “last active” and promotion logic). The spec will reference existing [activity.service](src/services/activity.service.ts) (`ACTIVITY_TYPES.MEMBER_PROMOTED` or new type), [fcm.service](src/services/fcm.service.ts) (`sendGroupNotification`, and optionally sending to a single user for the promoted user), and [groups.ts](src/controllers/groups.ts) `leaveGroup`.
