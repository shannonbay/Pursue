# Pursue — Daily Pulse Widget Specification

**Feature:** Daily Pulse — "Who's In?" Avatar Row  
**Version:** 1.0  
**Date:** February 2026  
**Status:** Draft  
**Platform:** Android (Material Design 3 / Jetpack Compose)  
**Placement:** `GroupDetailFragment` — above the tab row (Goals / Activity / Members)

---

## 1. Overview

Daily Pulse is a lightweight, glanceable widget that answers the most emotionally
compelling question in any accountability group: *did everyone show up today?*

It presents a horizontal row of member avatars. Avatars are greyed out until a member
logs any goal for the current period, at which point they light up with a satisfying
animation. The widget is person-first — it's not about which goal was done, but whether
your teammate is here today.

This is the thing people open the app to check. That check becomes a habit itself.

---

## 2. Placement

The widget sits in `GroupDetailFragment` **between the challenge header card (if
present) and the tab row** (Goals / Activity / Members). It is always visible
regardless of which tab is active.

```
┌─────────────────────────────────────────────┐
│  ← No Sugar March 🍬                    ⋮  │
├─────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────┐│  ← Challenge header (challenges only)
│  │  🍬 30-Day No Sugar Challenge           ││
│  │  Day 18 of 30  ████████████░░░░  60%   ││
│  │  [Share Challenge]  [Invite Friends]    ││
│  └─────────────────────────────────────────┘│
│                                             │
│  ┌─────────────────────────────────────────┐│  ← Daily Pulse widget (always present)
│  │  Who's in today?                        ││
│  │  [😊][😊][ ][ ][😊]  +3 more ›         ││
│  └─────────────────────────────────────────┘│
│                                             │
│  [Goals] [Activity] [Members]               │  ← Tab row
│  ─────────────────────────────────────────  │
│  ... tab content ...                        │
└─────────────────────────────────────────────┘
```

For **ongoing groups** (no challenge header), the widget sits directly below the
group toolbar and above the tab row, giving it even more visual prominence.

---

## 3. Size Tiers

Member count determines the widget's presentation mode. Count is based on **active
members** (approved, not removed).

| Tier | Members | Presentation |
|------|---------|--------------|
| Small | 1–5 | Single row, all avatars visible, no scroll |
| Medium | 6–20 | Single row, horizontally scrollable, fade at right edge |
| Large | 21+ | Single scrollable row with `+N more` pill; tap pill opens bottom sheet grid |

---

## 4. Avatar States

Each avatar has exactly two visual states:

### 4.1 Logged (Active)

The member has logged at least one goal for the current period (today for daily goals,
this week for weekly goals). Uses the group's most common goal cadence to determine
"current period". If the group has mixed cadences, daily takes precedence.

- Full-colour avatar image (or initials placeholder at full opacity)
- No overlay
- `contentDescription`: "[Name] has logged today"

### 4.2 Not Yet Logged (Inactive)

The member has not logged any goal for the current period.

- Avatar rendered in greyscale (`ColorMatrix` with saturation = 0)
- Opacity: 50%
- No additional overlay needed — greyscale + opacity together are sufficient
- `contentDescription`: "[Name] hasn't logged yet"

### 4.3 Current User's Avatar

The current user's own avatar follows the same active/inactive rules but is always
rendered **first** in the row, regardless of sort order.

---

## 5. Sort Order

Within the widget, avatars are sorted as follows:

1. Current user (always first)
2. Members who have logged today — sorted by log time, most recent first
3. Members who have not yet logged — sorted by display name alphabetically

This order ensures the most recent activity is visible at the front of the row (after
the current user), and unlogged members are predictably alphabetical.

---

## 6. Avatar Appearance

- **Size:** 40dp diameter circles
- **Spacing:** 8dp between avatars
- **Border:** 2dp white/surface border around each avatar (prevents blending on
  coloured backgrounds)
- **Initials placeholder:** When no avatar photo exists, show initials (first letter
  of display name) on a deterministic colour derived from the user's UUID. Use the
  same placeholder logic already in use elsewhere in the app.
- **Loading state:** Shimmer placeholder circles matching avatar size while data loads

---

## 7. Interaction

### 7.1 Tapping a Logged Avatar (Active State)

Opens a lightweight **member summary bottom sheet** (not full navigation). This avoids
the ambiguity of which group context to use when navigating to a full member detail
screen, since the same member may share multiple groups with the current user.

**Member summary bottom sheet:**
```
┌─────────────────────────────────────┐
│ [——] drag handle                    │
│                                     │
│  [Avatar]  Alex Torres              │
│            Logged 2 goals today ✓  │
│                                     │
│  Morning Run        ✓  7:14 AM      │
│  Meditation         ✓  7:45 AM      │
│                                     │
│  🔥 12-day streak                   │
│                                     │
└─────────────────────────────────────┘
```

- Shows only goals belonging to **this group**
- Streak shown if > 1 day
- No actions available from this sheet in v1 (view only)
- Drag-to-dismiss, `ModalBottomSheet`

### 7.2 Tapping an Unlogged Avatar (Inactive State)

Opens the **nudge bottom sheet**, consistent with the existing nudge flow.

```
┌─────────────────────────────────────┐
│ [——] drag handle                    │
│                                     │
│  [Avatar]  Jordan Lee               │
│            Hasn't logged yet today  │
│                                     │
│  [👋 Send Jordan a nudge]           │
│                                     │
│  You nudged Jordan yesterday        │  ← shown if applicable, greyed CTA
│                                     │
└─────────────────────────────────────┘
```

- Nudge CTA disabled and shows "You already nudged [Name] today" if rate-limited
- On nudge sent: dismiss sheet, show brief snackbar "Nudge sent to Jordan! 👊"
- Uses existing `POST /api/nudges` endpoint

### 7.3 Tapping the +N Pill (Large Groups)

Opens a **full member grid bottom sheet** showing all members in the same
logged/unlogged sort order as the main row.

```
┌─────────────────────────────────────┐
│ [——] drag handle                    │
│                                     │
│  Who's in today?          8 / 24   │
│  ─────────────────────────────────  │
│                                     │
│  [😊] Alex    [😊] Sam    [😊] You  │  ← logged row
│  [😊] Priya   [😊] Jamie  [ ] Kim  │
│  [ ] Jordan   [ ] Lee     [ ] Bo   │
│  ...                                │
│                                     │
└─────────────────────────────────────┘
```

- Grid: 3 columns, avatars at 48dp with name label below (truncated to ~8 chars)
- Logged avatars shown first (same sort order as main row)
- Tapping any avatar in the grid triggers the same interaction as §7.1 / §7.2
- Sheet height: content-driven, max 75% of screen, scrollable

---

## 8. The +N Pill

Displayed at the end of the scrollable row for large groups (21+ members) and
optionally for medium groups when not all avatars are currently visible on screen.

```
┌──────────────┐
│  +12 more ›  │
└──────────────┘
```

- Background: `surfaceVariant`
- Text: `labelMedium`, `onSurfaceVariant`
- Corner radius: 20dp (pill shape)
- Height: 40dp (matches avatar height)
- Minimum width: 64dp
- Tap → opens full member grid bottom sheet (§7.3)

The N value reflects the count of members **not visible** in the current scroll
position, not the total count. As the user scrolls, N decreases. When all avatars are
visible, the pill disappears. This makes the pill an honest indicator of hidden content.

---

## 9. Entry Animation

When a member logs a goal, their avatar transitions from inactive to active state with
a brief animation. This animation plays:

- When `GroupDetailFragment` loads and already-logged members are revealed
- In real-time if the fragment is open and a log event is received (via periodic
  refresh or WebSocket — see §13 on polling)

**Animation sequence (logged → active):**

1. Scale avatar up to 110% over 150ms (spring easing)
2. Simultaneously animate saturation from 0 → 1 and opacity from 50% → 100% over
   200ms
3. Scale back to 100% over 100ms
4. Optional: brief particle burst (3–4 small circles expanding outward and fading)
   using `Canvas` — keep subtle, not distracting

If multiple members log between refreshes, animate them sequentially with 80ms stagger,
most recently logged first.

**Do not animate on initial load** if the member was already logged before the screen
was opened. Only animate transitions that happen after the widget first renders.

---

## 10. Widget Header

A small label sits above the avatar row:

```
Who's in today?                    3 / 7 ›
```

- Left: label text in `labelMedium`, `onSurfaceVariant`
- Right: `[logged count] / [total members]` in `labelMedium`, `onSurfaceVariant`
  with a `›` chevron. Tapping the right side opens the full grid bottom sheet
  (same as tapping the +N pill for large groups).
- The `›` chevron is hidden for small groups (≤5) where all avatars are fully visible
  and no overflow exists.
- "today" changes to "this week" for weekly-cadence-only groups.

---

## 11. Empty and Edge States

| State | Display |
|-------|---------|
| Only member in group | Widget hidden — no point showing a pulse for one person |
| Group has no goals | Widget hidden — nothing to log, nothing to pulse |
| All members logged | Full-colour row, header reads "Everyone's in! 🎉" |
| No one logged yet (early in day) | All grey, header reads "Who's in today?" |
| Data loading | Shimmer placeholder circles |
| Load failed | Widget hidden silently — do not show an error state in the widget |

---

## 12. Backend — API Changes

No new endpoints required. The widget is powered by data already fetched for
`GroupDetailFragment`. Specifically:

The existing group detail response (or a parallel goals/progress fetch) must include
per-member logged status for the current period. If this is not already included,
add a `logged_today` boolean (or `logged_this_period`) to each member object in the
group members response.

**Suggested addition to the members array in `GET /api/groups/:group_id`:**

```json
{
  "members": [
    {
      "user_id": "uuid",
      "display_name": "Alex Torres",
      "avatar_url": "https://...",
      "role": "member",
      "logged_this_period": true,
      "last_log_at": "2026-02-27T07:14:00Z"
    }
  ]
}
```

`logged_this_period` is `true` if the member has at least one progress entry in the
current period across any goal in this group. `last_log_at` is the timestamp of their
most recent log (used for sort order in the widget).

The period is determined server-side using the member's local date (derived from their
timezone, stored on the user record) and the group's primary cadence.

---

## 13. Refresh Strategy

The widget reflects the state at load time. To show new logs without requiring a
manual pull-to-refresh:

- Poll `GET /api/groups/:group_id/members?include_log_status=true` every **60 seconds**
  while `GroupDetailFragment` is in the resumed state
- On receiving updated data, diff against current state and trigger entry animations
  for any newly-logged members (§9)
- Polling stops when the fragment is paused or destroyed

This is intentionally simple. A WebSocket or FCM-triggered refresh can be added in a
future iteration if the polling approach proves insufficient.

---

## 14. Analytics

| Event | When | Params |
|-------|------|--------|
| `daily_pulse_viewed` | Widget renders with data | `group_id`, `member_count`, `logged_count` |
| `daily_pulse_avatar_tapped` | Any avatar tapped | `group_id`, `target_logged` (bool) |
| `daily_pulse_nudge_sent` | Nudge sent via pulse sheet | `group_id` |
| `daily_pulse_grid_opened` | +N pill or header chevron tapped | `group_id`, `member_count` |

---

## 15. Implementation Checklist

### Android

**Widget composable:**
- [ ] `DailyPulseWidget` composable accepting `List<PulseMember>` state
- [ ] `PulseMember` data class: `userId`, `displayName`, `avatarUrl`, `loggedThisPeriod`, `lastLogAt`
- [ ] Small tier (≤5): `LazyRow` with `userScrollEnabled = false`
- [ ] Medium tier (9–20): `LazyRow` with `userScrollEnabled = true`, right-edge fade gradient
- [ ] Large tier (21+): scrollable row + `+N more` pill composable
- [ ] `DailyPulseAvatarItem` composable with greyscale `ColorMatrix` for inactive state
- [ ] Entry animation: scale + saturation + opacity transition on state change
- [ ] Staggered animation for multiple simultaneous state changes
- [ ] Widget header row with label + count + chevron
- [ ] Empty/edge state handling (§11)
- [ ] Shimmer loading state (match existing shimmer pattern in app)

**Bottom sheets:**
- [ ] `PulseMemberLoggedSheet` — logged member summary with goal list
- [ ] `PulseNudgeSheet` — nudge CTA for unlogged members (reuse nudge logic)
- [ ] `PulseGridSheet` — full member grid for large groups

**Integration:**
- [ ] Insert `DailyPulseWidget` in `GroupDetailFragment` above tab row
- [ ] Wire to `GroupDetailViewModel` — add `pulseMembers: StateFlow<List<PulseMember>>`
- [ ] 60-second polling coroutine in ViewModel, active only when fragment is resumed
- [ ] Diff logic to detect newly-logged members and trigger animations
- [ ] Fire analytics events (§14)

### Backend

- [ ] Add `logged_this_period` and `last_log_at` to members array in group detail response
- [ ] Confirm period logic uses member's local date + group primary cadence
- [ ] Verify query performance — add index on `progress_logs(group_id, user_id, logged_date)` if not present

---

## 16. Design Decisions Log

**Why not navigate to MemberDetailFragment on avatar tap?**
The same member may share multiple groups with the current user. Navigation to a
full member detail screen raises the question of which group's context to show.
A contextual bottom sheet scoped to the current group avoids this ambiguity cleanly
and keeps the user in flow.

**Why not real-time via WebSocket?**
Pursue's current architecture uses REST polling. 60-second polling is sufficient for
this feature — the emotional hook doesn't require sub-second updates. Add WebSocket
push as a future enhancement once polling proves insufficient.

**Why is the widget per-group rather than a top-level aggregated view?**
A cross-group aggregate raises identity problems (same person in multiple groups),
cadence conflicts (daily vs weekly), and significant additional complexity. Per-group
is simpler, immediately useful, and the right scope for launch. A home-screen
aggregate can be validated as a follow-on once per-group pulse is in users' hands.

**Why show the widget above the tabs rather than inside the Goals tab?**
The pulse is about presence, not goals. Placing it inside the Goals tab would imply
it's goal-related. Above the tabs, it's clearly a group-level concept that persists
regardless of which tab is active — which is the correct mental model.
