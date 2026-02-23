# Public Group Listings & Discovery

**Version:** 1.0  
**Last Updated:** February 22, 2026  
**Status:** Proposed  
**Depends On:** group-heat-spec.md, challenges-spec.md, Spec.md

---

## 1. Overview

Public Group Listings transforms Pursue from a closed social network (invite-only) into an open discovery platform. Users can opt their groups into a public directory where others can find, evaluate, and request to join them. This enables organic growth, connects strangers around shared goals, and provides a path for new users without invite codes to immediately find accountability partners.

The system has two interconnected components:

- **Public group visibility settings** — admin-controlled per group or challenge
- **Discovery dashboard** — browseable directory with filters, heat scores, and goal previews

Additionally, members of low-heat groups receive smart group suggestions via pgvector goal similarity, and new users without invite codes are guided through an interest quiz that feeds them into the discovery dashboard.

---

## 2. Group Visibility & Spot Limit Settings

### 2.1 Visibility Toggle

Group admins can set a group's visibility to **Public** or **Private**. This setting is available in two places:

- **Group creation flow** — as a toggle on the group setup screen
- **Challenge creation flow** — on the challenge settings screen
- **Group settings screen** — at any time post-creation by the admin

**Default:** Private (new groups are not listed publicly unless the admin opts in).

**What public listing exposes:**

| Data | Public Listing | Members Only |
|------|---------------|--------------|
| Group name | ✅ | ✅ |
| Group category | ✅ | ✅ |
| Member count | ✅ | ✅ |
| Heat score & tier | ✅ | ✅ |
| Number of active goals | ✅ | ✅ |
| Goal titles & active days | ✅ (expanded view) | ✅ |
| Member names / avatars | ❌ | ✅ |
| Activity log / progress entries | ❌ | ✅ |
| Admin communication link | ❌ | ✅ |

**Tooltip text** (shown as an info icon `ⓘ` next to the toggle):

> "Public listing lets others discover your group and request to join. They'll see your group name, member count, heat score, and goals — but not your activity log or member details."

### 2.2 Spot Limits

Admins can optionally cap how many members the group will accept. This setting appears directly below the visibility toggle.

**Options:**
- **Unlimited** (default)
- **Custom limit** — numeric input, range 2–500, validated on input

When a group is at capacity, it still appears in the public directory with a "Full" badge, but the join request button is disabled. This lets users save the group for future reference. The admin can raise or remove the limit at any time.

**Spot limit display on listing card:** Shows remaining spots when ≤10 remain (e.g., "3 spots left"). Otherwise shows total limit or nothing if unlimited.

### 2.3 Group Category

Each group (and challenge) must have exactly one category. Categories are used for directory filtering and pgvector suggestion matching.

**Category list (V1):**

| ID | Label |
|----|-------|
| `fitness` | Fitness & Exercise |
| `nutrition` | Nutrition & Diet |
| `mindfulness` | Mindfulness & Mental Health |
| `learning` | Learning & Skills |
| `creativity` | Creativity & Arts |
| `productivity` | Productivity & Career |
| `finance` | Finance & Savings |
| `social` | Social & Relationships |
| `lifestyle` | Lifestyle & Habits |
| `sports` | Sports & Training |
| `other` | Other |

Category is a required field for all groups. Existing groups without a category are prompted to set one the next time the admin opens group settings.

---

## 3. Group Join Requests

### 3.1 Request Flow (User Side)

When a user taps a public group card and clicks **Request to Join**, they are presented with a single-screen modal:

- Group name and heat tier (read only)
- A text field: "Add a note for the admin (optional)" — max 300 characters, placeholder: *"Tell the group why you want to join…"*
- **Send Request** button

The request is submitted to the backend and the user receives an in-app notification when approved or declined. While the request is pending, the group card shows a "Request Pending" state and the join button is disabled.

### 3.2 Request Review (Admin Side)

Admins see pending join requests in the group's member management screen. Each request card shows:

- Requester's display name and avatar
- Join request note (if provided)
- Account creation date ("Member since X months ago")
- **Approve** / **Decline** buttons

Approving sends the standard group welcome notification. Declining sends a generic "your request was not approved" notification (no custom reason — keeps it simple and avoids conflict).

Admins can also enable **Auto-approve** in group settings, which bypasses the review queue entirely. Auto-approve is only available to groups with a spot limit set (unlimited groups cannot auto-approve, as it creates spam risk).

---

## 4. Discovery Dashboard

### 4.1 Navigation Entry Point

The Discovery Dashboard is accessible from the home screen via a **Discover** tab or button in the top navigation. It is available to all users (not premium-gated).

### 4.2 Browse View

The dashboard displays public groups as scrollable cards in a single-column list. Each card shows:

- Group icon (uploaded image, or emoji + colour background if no image set)
- Group name
- Category tag (pill label)
- Heat flame icon + tier name
- Member count ("12 members")
- Active goal count ("5 goals")
- Remaining spots if limited and ≤10 left ("3 spots left")
- **Full** badge if at capacity

**Sort order (default):** Heat score descending (hottest groups first). Users can switch to "Newest" or "Most Members" via a sort menu.

### 4.3 Category Filters

A horizontally scrolling chip row at the top of the dashboard. Chips correspond to the category list in §2.3. Tapping a chip filters to that category. Multiple chips can be selected simultaneously (OR filter). An "All" chip clears filters.

### 4.4 Expanded Goal Details

Tapping a group card expands it (or navigates to a group preview screen) showing:

- Group icon (large, 56dp), group name, and category tag
- Heat flame icon + tier name, member count, spot availability
- Goal list: each goal shows title, cadence (daily / weekly / etc.), and active days (e.g., "Mon, Wed, Fri")
- Admin's optional group description (if set)
- **Request to Join** CTA (or "Request Pending" / "Full" state)

This expanded view does not show member names, avatars, or any progress entries.

### 4.5 Search

A search bar above the chip row supports free-text search against group names and goal titles. Search is client-side for small result sets, server-side for pagination beyond the first page.

---

## 5. Inactive Group Suggestions (pgvector)

### 5.1 Trigger Condition

A user is eligible for group suggestions when their **group's heat score falls below 33** (Tier 0–2: Cold, Spark, or Ember) for **14 or more consecutive days**. This uses the existing `group_daily_gcr` data and `group_heat.streak_days` from the heat spec.

The suggestion check runs nightly as part of the heat calculation job. It only considers groups where the user is an approved member.

### 5.2 Suggestion Algorithm

For each user who meets the trigger condition, identify candidate public groups using pgvector similarity on goal embeddings.

**Embedding strategy:**

Each goal is represented as a text string combining its title and active days:

```
"{goal_title} [{active_days_abbreviated}]"
// e.g. "Morning run [Mon Wed Fri]"
// e.g. "Read 30 minutes [Daily]"
```

Embeddings are generated using a lightweight embedding model (e.g., `text-embedding-3-small` via OpenAI API, or equivalent self-hosted). Embeddings are computed when a goal is created or its title/active days change, and stored in a `goal_embeddings` table with a `vector(1536)` column indexed with `pgvector`.

**Matching logic:**

1. Take all goals from the user's low-heat group(s).
2. For each goal, find the top 10 most similar goals in the `goal_embeddings` table using cosine distance (`<=>` operator), filtered to:
   - Goals belonging to public groups
   - Groups with heat score ≥ 33 (Tier 3+: Flicker or higher — active groups only)
   - Groups with available spots (not full)
   - Groups the user is not already a member of or has a pending request for
3. A candidate group qualifies if **at least one of its goals has a similarity score ≥ 0.75** (cosine similarity). Partial match is intentional — the suggested group doesn't need to be an exact fit, just meaningfully related.
4. Rank qualifying candidate groups by their heat score descending.
5. Return the top 3 suggestions per user.

**Cost management:** Embeddings are cached. Only recompute when a goal title or active days changes. New group goals are embedded at creation time.

### 5.3 Suggestion Presentation

Suggestions appear as a **"Groups you might like"** section inside the Discover dashboard, visible only when the user qualifies. The section is also surfaced as a push notification (max once per 7 days, not on consecutive nights).

**Notification text:** "Your group has cooled off — here are some active groups working on similar goals."

Each suggestion card includes the standard public listing card content (including group icon) plus a brief similarity hint label (e.g., "Similar goal: Morning run").

Users can dismiss individual suggestions or the entire section ("Not interested"). Dismissed suggestions are excluded for 30 days.

---

## 6. New User Orientation — Interest Quiz

### 6.1 When It Triggers

The interest quiz appears during onboarding when a new user does **not** have an invite code. Users with a valid invite code skip directly to the standard group join flow.

### 6.2 Quiz Flow

**Screen: "What are you working on?"**

Users select one or more categories from a visually appealing grid (icons + labels, same list as §2.3). At least one selection is required to proceed. A **"Skip"** link is available.

**Screen: "Here are some active groups"**

The app fetches public groups that:
- Match one or more selected categories
- Have heat score ≥ 47 (Tier 4+: Steady or higher)
- Have available spots

Groups are sorted by heat score descending. The UI shows the standard discovery card with a **Request to Join** CTA.

Below the list, a prominent secondary CTA:

> **"I'd rather create my own group →"**

Tapping this skips to the **Challenges** section of onboarding (where users can pick a challenge template or create a custom group from scratch).

If no matching public groups are found (unlikely but possible early on), show:

> "No groups found for your interests yet — be the first!"  
> [**Create a Group**] [**Browse all groups**]

### 6.3 Post-Orientation

After the user has submitted at least one join request or created a group, the orientation is marked complete and they proceed to the standard home screen. Their selected category interests are stored and used to pre-filter the Discover dashboard on first visit.

---

## 7. Admin Communication Links

### 7.1 Purpose

Public groups may bring together people who don't know each other. Admins need a way to share an external communication channel without Pursue having to build its own messaging infrastructure.

### 7.2 Supported Platforms

Admins can optionally add one of the following group links in group settings:

| Platform | Field label | URL format validation |
|----------|-------------|----------------------|
| Discord | Discord invite link | `discord.gg/...` or `discord.com/invite/...` |
| WhatsApp | WhatsApp group invite | `chat.whatsapp.com/...` |
| Telegram | Telegram group link | `t.me/...` |

Only one link is supported at a time (the platform that works best for their community).

### 7.3 Visibility

The communication link is **visible only to approved group members**, not to anyone browsing public listings or with a pending request. It appears on the group detail screen below the member list, with the platform logo and a **"Join {Platform} group"** tap action that opens the link in the system browser.

Setting the link is optional. Groups without a link show nothing in this section.

### 7.4 Admin UX

In group settings, a new "External Communication" section contains:

- Platform selector (Discord / WhatsApp / Telegram)
- URL input field with inline validation
- Save button

A helper note: *"This link is only visible to approved members."*

---

## 8. Database Schema Changes

### 8.1 Groups Table Additions

```sql
ALTER TABLE groups
  ADD COLUMN visibility        TEXT    NOT NULL DEFAULT 'private' CHECK (visibility IN ('public', 'private')),
  ADD COLUMN category          TEXT    CHECK (category IN ('fitness','nutrition','mindfulness','learning','creativity','productivity','finance','social','lifestyle','sports','other')),
  ADD COLUMN spot_limit        INTEGER CHECK (spot_limit IS NULL OR (spot_limit >= 2 AND spot_limit <= 500)),
  ADD COLUMN comm_platform     TEXT    CHECK (comm_platform IN ('discord','whatsapp','telegram') OR comm_platform IS NULL),
  ADD COLUMN comm_link         TEXT;
```

### 8.2 Users Table Additions

```sql
ALTER TABLE users
  ADD COLUMN interest_categories   TEXT[]  DEFAULT '{}';
```

### 8.3 Goal Embeddings Table

```sql
CREATE TABLE goal_embeddings (
  goal_id     UUID        PRIMARY KEY REFERENCES goals(id) ON DELETE CASCADE,
  embedding   VECTOR(1536) NOT NULL,
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX goal_embeddings_hnsw ON goal_embeddings
  USING hnsw (embedding vector_cosine_ops)
  WITH (m = 16, ef_construction = 64);
```

### 8.4 Join Requests Table

```sql
CREATE TABLE join_requests (
  id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id     UUID          NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id      UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status       TEXT          NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','declined')),
  note         TEXT          CHECK (char_length(note) <= 300),
  created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  reviewed_at  TIMESTAMPTZ,
  reviewed_by  UUID          REFERENCES users(id),
  UNIQUE (group_id, user_id)
);

CREATE INDEX join_requests_group_pending ON join_requests(group_id) WHERE status = 'pending';
```

### 8.5 Group Suggestion Dismissals Table

```sql
CREATE TABLE suggestion_dismissals (
  user_id       UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id      UUID          NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  dismissed_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, group_id)
);
```

---

## 9. API Endpoints

### 9.1 Public Group Discovery

```
GET /v1/discover/groups
```

Query params:
- `categories` — comma-separated category IDs (optional filter)
- `sort` — `heat` (default) | `newest` | `members`
- `q` — search string (optional)
- `cursor` — pagination cursor
- `limit` — default 20, max 50

Response: paginated array of public group cards (see §4.2 fields), including `has_icon`, `icon_emoji`, and `icon_color` so the client can render the group icon without a separate request. Icon image binary is fetched separately via `GET /api/groups/:groupId/icon` only when displayed. Does not require authentication for browsing, but requires auth for join request actions.

```
GET /v1/discover/groups/:groupId
```

Returns expanded group detail including goal list. No auth required.

### 9.2 Join Requests

```
POST /v1/groups/:groupId/join-requests
```

Body: `{ note?: string }`  
Auth required.

```
GET /v1/groups/:groupId/join-requests
```

Admin only. Returns pending requests with requester name, avatar, account age, and note.

```
PATCH /v1/groups/:groupId/join-requests/:requestId
```

Body: `{ status: 'approved' | 'declined' }`  
Admin only.

### 9.3 Group Suggestions

```
GET /v1/discover/suggestions
```

Auth required. Returns up to 3 suggested public groups for the authenticated user. Returns empty array if user does not meet the trigger condition.

```
DELETE /v1/discover/suggestions/:groupId
```

Dismisses a suggestion for 30 days.

### 9.4 Group Settings Updates

```
PATCH /v1/groups/:groupId
```

Existing endpoint extended to accept:
```json
{
  "visibility": "public" | "private",
  "category": "fitness" | ...,
  "spotLimit": 50 | null,
  "commPlatform": "discord" | "whatsapp" | "telegram" | null,
  "commLink": "https://..."
}
```

---

## 10. Android UI

### 10.1 Group Creation / Settings Screens

**Visibility section** (added to group creation and settings):

```
[ Public Listing ]  [Toggle]  [ⓘ]

(when Public is ON)
──────────────────────────────
Spot limit
( ) Unlimited
( ) Limit to [___] members  (2–500)
──────────────────────────────
Category
[ Fitness & Exercise ▼ ]
```

### 10.2 Discover Tab

```
┌─────────────────────────────────────┐
│ 🔍 Search groups and goals...       │
├─────────────────────────────────────┤
│ [All] [Fitness] [Mindfulness] [→]   │
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐ │
│ │ [🏃] Morning Crew    🔥 Blaze   │ │
│ │      Fitness • 8 members        │ │
│ │      4 goals • 2 spots left     │ │
│ └─────────────────────────────────┘ │
│ ┌─────────────────────────────────┐ │
│ │ [📚] Read More Books 🔥 Steady  │ │
│ │      Learning • 5 members       │ │
│ │      2 goals                    │ │
│ └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

Group icon is displayed as a 40dp circle on the leading edge of each card. If the group has an uploaded image (`has_icon = true`), fetch it from `GET /api/groups/:groupId/icon` with standard disk caching. If no image, render the `icon_emoji` centred on an `icon_color` background circle.

Expanded card (tapped):

```
┌─────────────────────────────────────┐
│          [🏃 large icon]            │
│          Morning Crew               │
│          Fitness  •  🔥 Blaze       │
│          8/10 members  •  2 left    │
├─────────────────────────────────────┤
│ Goals                               │
│  • Morning run        Mon Wed Fri   │
│  • 10,000 steps       Daily         │
│  • Stretching routine Daily         │
│  • Weekly long run    Sunday        │
├─────────────────────────────────────┤
│         [ Request to Join ]         │
└─────────────────────────────────────┘
```

The large icon is 56dp, centred above the group name.

### 10.3 Join Request Modal

```
┌──────────────────────────────────────┐
│  Request to join Morning Crew        │
│                                      │
│  Add a note for the admin (optional) │
│  ┌────────────────────────────────┐  │
│  │ Tell the group why you want   │  │
│  │ to join…                      │  │
│  └────────────────────────────────┘  │
│                          0 / 300     │
│                                      │
│         [ Send Request ]             │
└──────────────────────────────────────┘
```

### 10.4 Admin Join Request Review

```
Pending Requests (3)

┌────────────────────────────────────────┐
│  [Avatar] Alex T.                      │
│  Member since 4 months                 │
│  "I've been running solo for a year   │
│   and want some accountability!"       │
│                                        │
│  [ Approve ]           [ Decline ]     │
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│  [Avatar] Jamie K.                     │
│  New member                            │
│  "Looking to build better habits."    │
│                                        │
│  [ Approve ]           [ Decline ]     │
└────────────────────────────────────────┘
```

### 10.5 Onboarding Interest Quiz

**Screen 1 — Category grid:**

```
What are you working on?
Select one or more areas you want to focus on.

[ 🏃 Fitness ]   [ 🧘 Mindfulness ]
[ 📚 Learning ]  [ 💰 Finance     ]
[ 🎨 Creativity] [ ⚡ Productivity ]
...

                        [ Continue → ]
                           Skip
```

**Screen 2 — Group suggestions:**

```
Here are some active groups

┌─────────────────────────────────────┐
│ [🏃] Morning Crew      🔥 Blaze     │
│      Fitness • 8 members • 4 goals  │
│              [ Request to Join ]    │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│ [📚] Book Club Buddies  🔥 Steady   │
│      Learning • 6 members • 2 goals │
│              [ Request to Join ]    │
└─────────────────────────────────────┘

──────────────────────────────────────
  I'd rather create my own group →
```

### 10.6 Group Communication Link (Members Only)

On the group detail screen, below the member list:

```
External Community
[Discord logo]  Join the Discord group  →
```

Tapping opens the URL in the system browser. Not shown to non-members or pending requesters.

---

## 11. Scheduled Jobs

### 11.1 Group Suggestion Job

**Schedule:** Nightly, after the group heat job (e.g., 5:00 AM UTC).

**Logic:**
1. Find all groups with `heat_score < 33` for the last 14+ consecutive days (from `group_daily_gcr`)
2. For each approved member of those groups:
   - Collect their goal embeddings
   - Run pgvector similarity query against goals in public, active, non-full groups the user isn't in
   - Filter matches with cosine similarity ≥ 0.75
   - Rank candidate groups by heat score
   - Store top 3 in a `group_suggestions` cache table (or compute on-demand via the API)
3. Send FCM notification to eligible users (rate limited: max once per 7 days per user)

---

## 12. Freemium Considerations

All discovery and public listing features are available to free users. This is intentional — discovery drives new user acquisition and group growth, which benefits the whole platform.

| Feature | Free | Premium |
|---------|------|---------|
| Browse public listings | ✅ | ✅ |
| Submit join requests | ✅ | ✅ |
| Set group as public | ✅ | ✅ |
| Group suggestions (pgvector) | ✅ | ✅ |
| Spot limit configuration | ✅ | ✅ |
| Communication link | ✅ | ✅ |
| Auto-approve requests | ❌ | ✅ |

Auto-approve is a premium feature because it reduces admin overhead, which is a convenience benefit rather than a core accountability feature.

---

## 13. Privacy & Safety

**Public listings do not expose:**
- Individual member identities or avatars
- Progress entries or activity logs
- Communication links (members only)

**Abuse prevention:**
- Join requests are rate-limited: max 10 pending requests per user at any time
- Admins can block a user from re-requesting (stores a `blocked_requesters` relationship)
- Repeated declines from the same user to the same group are blocked after 2 attempts (30-day cooldown)
- Communication links are validated against known platform URL formats; arbitrary URLs are rejected
- Group names and goal titles in public listings are subject to existing content moderation rules

**Data retention:**
- Declined join requests are soft-deleted after 90 days
- Goal embeddings are deleted when the goal is archived or deleted
- Suggestion dismissals expire after 30 days

---

## 14. Testing Strategy

### 14.1 Unit Tests

- pgvector similarity threshold logic
- Spot limit validation (boundary: 2, 500, null)
- Communication link URL format validation for each platform

### 14.2 Integration Tests

- Full join request lifecycle: submit → admin review → approve → member added
- Public group listing API returns correct fields and excludes private groups
- Discovery search returns matching groups by name and goal title
- Group suggestions job identifies correct candidates and respects dismissals

### 14.3 Manual Testing Checklist

- [ ] Private group does not appear in discover dashboard
- [ ] Public group appears in discover and shows correct heat tier
- [ ] Goal list visible in expanded view, activity log is not
- [ ] Communication link not visible to non-members or pending requesters
- [ ] Spot limit badge ("3 spots left") appears correctly
- [ ] Full group shows disabled join button
- [ ] Interest quiz pre-filters discover tab on first visit
- [ ] "I'd rather create my own group" navigates to Challenges
- [ ] Join request note is optional but displayed in admin review
- [ ] Admin review shows requester name and account age only — no scoring or classification
- [ ] pgvector suggestions appear for users in cold groups (heat < 33 for 14+ days)
- [ ] Dismissing a suggestion removes it for 30 days
- [ ] Auto-approve (premium) adds member without admin action
- [ ] Communication link opens correct platform URL in browser

---

## 15. Rollout Plan

### Phase 1 — Backend & Database (2–3 days)
1. Run migrations: add columns to `groups` and `users`, create `join_requests`, `goal_embeddings`, `suggestion_dismissals` tables
2. Implement `/v1/discover/groups` and `/v1/discover/groups/:id` endpoints
3. Implement join request endpoints (submit, list, approve/decline)
4. Set up pgvector extension (if not already enabled on Supabase project)
5. Implement goal embedding generation on goal create/update
6. Implement group suggestion job (can ship disabled initially)

### Phase 2 — Android: Discovery Dashboard (2–3 days)
1. Discover tab with category chips, search, sorted card list
2. Expanded group detail / preview screen
3. Join request modal
4. Request Pending state on group cards
5. Admin join request review screen

### Phase 3 — Android: Settings & Communication (1–2 days)
1. Visibility toggle + spot limit UI in group creation and settings
2. Category selector in group creation and settings
3. Communication link section in group settings and group detail (members only)

### Phase 4 — Android: Onboarding Quiz (1 day)
1. Interest category selection screen
2. Suggested groups screen with "I'd rather create my own group" CTA
3. Connect quiz selections to discover pre-filter

### Phase 5 — Suggestions & Notifications (1–2 days)
1. Enable and test group suggestion job
2. "Groups you might like" section in Discover
3. FCM notification for suggestion prompt
4. Dismissal flow

**Total estimated effort: 7–11 days**

---

## 16. Future Enhancements

- **Verified groups** — admin-verified community groups with a checkmark badge
- **Group reviews** — brief star ratings from former members (post-departure only)
- **Waiting list** — join a queue when a group is full, get notified when a spot opens
- **Rich group profiles** — admin-written description with photo banner
- **Suggested members for admins** — show users who match the group's goal profile
- **Interest profile refinement** — users can update their interest categories from profile settings
- **pgvector for user-to-group matching** — match individual user goals (not just group goals) to public groups
