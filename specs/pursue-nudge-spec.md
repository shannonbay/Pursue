# Pursue — Nudge Feature Specification

## Overview

The Nudge feature lets group members send a lightweight motivational push notification
to a teammate who hasn't logged their goal yet for the current period. It transforms
passive accountability into an active social gesture — one tap from a friend is more
motivating than any automated reminder.

---

## Design Decisions

### Where does the nudge UI live?

**`GroupDetailFragment` — inline on each member row.**

The nudge is a contextual action: it only makes sense when you can see *which goal*
a member hasn't completed. `HomeFragment` shows groups at too high a level — you can't
tell there what's unlogged. `GroupDetailFragment` already shows each member's
goal-completion status, so the nudge button can appear naturally next to members who
haven't logged yet for today/this week.

A dedicated `MemberDetailFragment` is not required and should not be created
solely to host this button. If a `MemberDetailFragment` is added later (e.g., for
viewing streaks or a progress history), the nudge button should be surfaced there too
as a secondary entry point.

### Is the nudge delivered as an FCM push notification?

**Yes.** The entire value of a nudge is that the recipient feels it even when they're
not in the app. An in-app badge or activity feed entry alone provides no value if
the person isn't already open. FCM is the correct delivery mechanism.

An in-app activity entry is created *in addition* to the push, so recipients can see
who nudged them when they open the app.

---

## User-Facing Behaviour

### The Nudge Button

- Appears in `GroupDetailFragment` on the member progress list, next to any member
  who has **not yet logged a goal** for the current period.
- Displayed as a small icon button (e.g., a "poke" or bell icon) beside the member
  row — not a full-width button.
- Only visible to *other* members; you cannot nudge yourself.
- The button is **disabled (greyed out)** if you have already nudged that member today
  (per the one-nudge-per-sender-per-recipient-per-day rule).
- The button is **hidden entirely** once the member has completed all their goals for
  the current period.

### Nudge Rate Limit

One nudge per sender → recipient pair per calendar day (using the sender's local
date). This is enforced server-side. Attempts beyond this limit return a 429 and the
client shows a brief toast: *"You already nudged Alex today."*

### The Push Notification (Recipient)

```
Title:  "Morning Runners"
Body:   "Shannon is rooting for you! Don't forget your Jogging goal. 💪"
```

- The group name is used as the notification title for context, consistent with all
  other Pursue notifications.
- If the member has multiple incomplete goals in that group, the body references the
  **first incomplete goal** (by creation order). A future iteration could let the
  sender choose which goal to reference.
- Tapping the notification deep-links the recipient to `GroupDetailFragment` for
  that group.

### In-App Activity Entry (Recipient)

When the recipient opens the app, the nudge also appears in the group's activity feed:

```
Shannon nudged you on your Jogging goal.  · 2h ago
```

---

## Rate Limit Details

| Rule | Value |
|---|---|
| Max nudges from User A → User B per day | **1** |
| Resets at | Midnight in the **sender's** local timezone |
| Scope | Per group? No — cross-group. If A nudges B in Group 1, A cannot nudge B in Group 2 the same day either. |
| Max nudges a single user can *send* per day (total) | Soft cap: **20** (prevents abuse in large groups) |
| Max nudges a single user can *receive* per day | No hard cap server-side; naturally limited by group size |

> **Rationale for cross-group scope:** The goal is to prevent a user from feeling
> spammed. If you share multiple groups with someone, one nudge per day across all
> groups is the right social contract.

---

## Database Schema

```sql
CREATE TABLE nudges (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sender_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id         UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  goal_id          UUID REFERENCES goals(id) ON DELETE SET NULL,
  sent_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  sender_local_date DATE NOT NULL  -- sender's local date at time of nudge
);

-- Enforce one nudge per sender→recipient per sender-local-date
CREATE UNIQUE INDEX idx_nudges_daily_limit
  ON nudges(sender_user_id, recipient_user_id, sender_local_date);

-- For querying "has this user been nudged recently" on the recipient side
CREATE INDEX idx_nudges_recipient
  ON nudges(recipient_user_id, sent_at DESC);

-- For querying "how many nudges has this sender sent today"
CREATE INDEX idx_nudges_sender_daily
  ON nudges(sender_user_id, sender_local_date);
```

> The `sender_local_date` column (a `DATE`, not a timestamp) stores the sender's
> local date at send time, consistent with the existing Pursue convention of using
> local dates for period tracking rather than UTC.

---

## API

### POST /api/nudges

Send a nudge to a group member.

**Authorization:** Bearer token required. Sender must be an approved member of `group_id`.

**Request:**
```json
POST /api/nudges
Authorization: Bearer {access_token}

{
  "recipient_user_id": "user-uuid",
  "group_id": "group-uuid",
  "goal_id": "goal-uuid",          // optional but recommended
  "sender_local_date": "2026-02-12" // client sends their local date (YYYY-MM-DD)
}
```

**Response (201 Created):**
```json
{
  "nudge": {
    "id": "nudge-uuid",
    "recipient_user_id": "user-uuid",
    "group_id": "group-uuid",
    "goal_id": "goal-uuid",
    "sent_at": "2026-02-12T14:32:00Z"
  }
}
```

**Error Responses:**

| Status | Code | Condition |
|---|---|---|
| 400 | `CANNOT_NUDGE_SELF` | sender_user_id == recipient_user_id |
| 403 | `NOT_A_MEMBER` | Sender is not an approved member of group |
| 403 | `RECIPIENT_NOT_IN_GROUP` | Recipient is not a member of the group |
| 409 | `ALREADY_NUDGED_TODAY` | Unique constraint violation on daily limit |
| 429 | `DAILY_SEND_LIMIT` | Sender has hit the 20-nudge/day soft cap |

**Server Logic:**

1. Validate JWT, extract `sender_user_id`.
2. Confirm sender is an approved member of `group_id`.
3. Confirm recipient is an approved member of `group_id`.
4. Reject if `sender_user_id == recipient_user_id`.
5. Attempt `INSERT INTO nudges`. If unique constraint on
   `(sender_user_id, recipient_user_id, sender_local_date)` fires → return 409.
6. Count nudges sent by this sender today (by `sender_local_date`). If ≥ 20 → return 429.
7. Fetch recipient's FCM tokens from `devices` table.
8. Fetch goal title (if `goal_id` provided) for notification body.
9. Send FCM push to recipient (see payload below).
10. Insert a `group_activities` entry of type `nudge_sent`.
11. Return 201.

---

### GET /api/groups/:group_id/nudges/sent-today

Returns which recipient IDs the calling user has already nudged today in this group.
Used by the client to correctly disable nudge buttons on load without round-tripping
for each member individually.

**Response (200):**
```json
{
  "nudged_user_ids": ["user-uuid-1", "user-uuid-2"]
}
```

The client calls this once when `GroupDetailFragment` loads and caches it for the
session. The nudge button for any user_id in this list is rendered as disabled.

---

## FCM Notification Payload

```json
{
  "notification": {
    "title": "Morning Runners",
    "body": "Shannon is rooting for you! Don't forget your Jogging goal. 💪"
  },
  "data": {
    "type": "nudge_received",
    "group_id": "group-uuid",
    "goal_id": "goal-uuid",
    "sender_user_id": "sender-uuid",
    "sender_display_name": "Shannon"
  }
}
```

**Body construction logic (server):**

```typescript
function buildNudgeBody(
  senderName: string,
  goalTitle: string | null
): string {
  if (goalTitle) {
    return `${senderName} is rooting for you! Don't forget your ${goalTitle} goal. 💪`;
  }
  return `${senderName} is rooting for you! Keep going today. 💪`;
}
```

**Client deep-link handling:** When the user taps the notification, the `data.type`
of `nudge_received` navigates to `GroupDetailFragment` for `data.group_id`. This
reuses the existing FCM deep-link routing pattern.

---

## Android UI — GroupDetailFragment Changes

### Member Row State Machine

Each member row in the goal-progress list evaluates the following conditions in order:

| Condition | Nudge Button State |
|---|---|
| Member is the current user | Hidden |
| Member has completed all goals this period | Hidden |
| Current user has already nudged this member today | Visible, disabled (greyed) |
| Member has at least one incomplete goal | Visible, enabled |

### Button Interaction

1. User taps nudge button → button immediately enters loading state (spinner).
2. `POST /api/nudges` fires.
3. **On success:** Button transitions to disabled/greyed state. Show brief snackbar:
   *"Nudge sent to Alex! 👊"*
4. **On 409 (already nudged):** Show toast: *"You already nudged Alex today."*
   Button renders as disabled.
5. **On network error:** Show toast: *"Couldn't send nudge. Try again."* Button
   returns to enabled state.

### Loading GroupDetailFragment

On fragment load (or refresh), call `GET /api/groups/:group_id/nudges/sent-today`
in parallel with the existing group/progress data fetches. Use the returned
`nudged_user_ids` list to set initial button states. This avoids the nudge button
briefly appearing enabled for already-nudged members.

---

## Activity Feed Entry

A nudge creates a `group_activities` row of type `nudge_sent`. This appears in the
group activity feed visible to all members, keeping the social energy visible.

**Activity metadata (JSONB):**
```json
{
  "sender_user_id": "uuid",
  "sender_display_name": "Shannon",
  "recipient_user_id": "uuid",
  "recipient_display_name": "Alex",
  "goal_id": "uuid",
  "goal_title": "Jogging"
}
```

**Feed display:**
```
Shannon nudged Alex on their Jogging goal.  · 14m ago
```

> Only nudge_sent events where the *current user* is either sender or recipient are
> surfaced in the personal notification view. All members see nudges in the full group
> activity feed (it builds group culture).

---

## Notification Settings

Users should be able to opt out of receiving nudge push notifications without
disabling all Pursue notifications. Add a `nudges_enabled` preference to the existing
notification settings screen.

**Behaviour when disabled:**
- The FCM push is suppressed.
- The `group_activities` entry is still created (feed history preserved).
- The sender's nudge still counts against the daily rate limit (the action occurred).

**Settings storage:** Add `nudge_notifications_enabled BOOLEAN DEFAULT TRUE` to the
`user_notification_preferences` table (or equivalent settings table).

---

## Future Enhancements

These are intentionally out of scope for the initial release but are worth noting
for the roadmap:

- **Goal picker on nudge:** A bottom sheet that lets the sender choose *which*
  incomplete goal to reference, rather than defaulting to the first one.
- **Custom nudge messages:** Premium feature — let senders pick from 3–4 short
  pre-written motivational phrases.
- **Nudge streak:** Track how many times a pair of users have nudged each other.
  Surface a small badge like "You and Alex have a 5-day nudge streak."
- **Batch nudge ("Nudge All"):** Admin-only button to nudge all members who haven't
  logged yet. Rate-limited to once per group per day by admin.
