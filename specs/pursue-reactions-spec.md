# Pursue — Reactions System Spec

**Status:** Draft  
**Version:** 1.0  
**Date:** February 2026  
**Component:** Social / Activity Feed

---

## 1. Overview

The Reactions system lets group members express quick, lightweight responses to activity log entries (progress logged, member joined, goal achieved, etc.) using emoji. This adds a social layer to the activity feed without requiring full comments, driving engagement and reinforcing the accountability loop that makes Pursue sticky.

> **Design principle:** Reactions should feel instant and delightful — a one-tap dopamine hit that rewards people for showing up. The interaction must never interrupt the user's flow.

### 1.1 Goals

- Increase daily active engagement by giving members a reason to open the activity feed
- Reward progress-logging behaviour with positive social reinforcement
- Keep the interaction frictionless — single tap to react, single tap to un-react
- Surface reactions prominently on log entries without cluttering the feed
- Support a curated emoji set that is expressive but not overwhelming

### 1.2 Non-Goals

- Full comment threads (separate future feature)
- Custom / user-uploaded emoji
- Reaction counts as a public leaderboard metric
- Reactions on non-activity items (goals, group settings, etc.)

---

## 2. Design Recommendations

### 2.1 How to Input a Reaction

**Recommendation: long-press on an activity log entry to reveal the emoji picker.**

This is the established mobile pattern (iMessage, Slack, WhatsApp, Instagram) that users already understand intuitively. It keeps the feed clean — there are no persistent action buttons on every row — and feels natural on Android with Jetpack Compose.

#### Interaction Flow

1. User scrolls the activity feed
2. Long-press on any log entry (haptic feedback fires immediately on press-down)
3. Feed entry scales up slightly (subtle spring animation) and blurs the background
4. Emoji picker appears anchored above the entry as a floating horizontal pill
5. User taps an emoji to react — picker dismisses, reaction is applied
6. If the user already reacted with that emoji: the reaction is removed (toggle)
7. If the user already reacted with a different emoji: their old reaction is replaced

> **Alternative considered:** A small "+" icon on each feed row that opens the picker on single tap. Rejected because it adds persistent visual clutter to every row and is less discoverable for new users than long-press, which they already know from messaging apps.

---

### 2.2 Emoji Set

Offer exactly 6 reactions. Six gives expressive range without decision paralysis. The set is chosen to cover the primary emotional responses to goal progress and to be colorblind-accessible (no red/green ambiguity for meaning).

| Emoji | Name | When to use |
|-------|------|-------------|
| 🔥 | fire | Crushing it / on a streak |
| 💪 | flex | Strong effort / physical goal |
| ❤️ | heart | Love / support / proud of you |
| 👏 | clap | Applause / well done |
| 🤩 | star-struck | Impressive / exceeded expectations |
| 😂 | laugh | Funny note / lighthearted |

> **Why not a thumbs-up?** It reads as neutral acknowledgement rather than genuine encouragement. Fire, flex and clap test better for motivation in social fitness/habit contexts. Thumbs-up can be added as a 7th option later if user research demands it.

---

### 2.3 Displaying Reactions on Feed Entries

Reactions appear directly below the activity text on a log entry, above the timestamp.

#### Display Format — Collapsed (Default)

Show up to 3 unique emoji (those with the most reactions), followed by a summary label:

```
🔥💪❤️  Robert J. and 2 others
```

Rules for the label:

- **1 reactor:** show their first name + last initial only — e.g. "Robert J."
- **2 reactors:** "Robert J. and Sarah K."
- **3+ reactors:** "Robert J. and 2 others" (always name the most recent reactor first)
- **If the current user reacted:** "You and 2 others" or "You and Robert J." (current user always named first)
- **If only the current user reacted:** just "You"

#### Display Format — Tapping the Reaction Row

**Recommendation: tapping the reaction summary row opens a Modal Bottom Sheet (not a separate screen).**

A bottom sheet is the right choice here because:

- It is a temporary, lightweight interaction — no navigation needed
- The user's context (which feed entry they tapped) is visually preserved behind the sheet
- Material Design 3 `ModalBottomSheet` is a first-class Compose component with built-in drag-to-dismiss
- It mirrors patterns the user already knows from share sheets, sort sheets, etc.

#### Bottom Sheet Content

The bottom sheet shows the full reactor list grouped by emoji tab:

```
+-----------------------------------+
| [————] drag handle                |
|                                   |
| Reactions              [X close]  |
|                                   |
| [All 5] [🔥 3] [💪 1] [❤️ 1]     |
|___________________________________|
| 🖼  Robert Johnson       🔥       |
| 🖼  Sarah Kim            🔥       |
| 🖼  You                  🔥       |
| 🖼  Alex Torres          💪       |
| 🖼  Jordan Lee           ❤️       |
+-----------------------------------+
```

- Default tab is "All" showing every reactor in reverse-chronological order
- Tabs for each emoji that has at least one reaction, showing the count
- Each row: avatar thumbnail + display name + the emoji they used
- List is scrollable if the group is large
- Sheet height is content-driven, max 60% of screen height, drag-to-dismiss supported

---

## 3. Database Schema

### 3.1 New Table: `activity_reactions`

```sql
CREATE TABLE activity_reactions (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  activity_id UUID NOT NULL REFERENCES group_activities(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji       VARCHAR(10) NOT NULL,   -- unicode emoji e.g. '🔥'
  created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

  -- One reaction per user per activity (any emoji)
  CONSTRAINT uq_reaction_user_activity UNIQUE (activity_id, user_id)
);

CREATE INDEX idx_reactions_activity ON activity_reactions(activity_id);
CREATE INDEX idx_reactions_user     ON activity_reactions(user_id);
```

> **One reaction per user per activity.** Users can change which emoji they used (replace), but cannot stack multiple emoji on the same entry. This keeps the aggregation simple and prevents gaming.

### 3.2 Emoji Allowlist

Valid emoji values are enforced at the API layer via a constant set, not a DB constraint (so adding new emoji later requires only a server deploy, not a migration):

```typescript
const ALLOWED_REACTIONS = ['🔥', '💪', '❤️', '👏', '🤩', '😂'] as const;
type ReactionEmoji = typeof ALLOWED_REACTIONS[number];
```

### 3.3 Reaction Aggregation Query

Fetched alongside activity feed entries to avoid a second round trip:

```sql
SELECT
  ar.activity_id,
  ar.emoji,
  COUNT(*)                                          AS reaction_count,
  array_agg(ar.user_id ORDER BY ar.created_at DESC) AS reactor_ids
FROM activity_reactions ar
WHERE ar.activity_id = ANY($1::uuid[])  -- batch fetch for all visible activities
GROUP BY ar.activity_id, ar.emoji
ORDER BY ar.activity_id, reaction_count DESC;
```

The `$1` parameter is the array of activity IDs for the current page. This single query replaces N individual lookups and avoids N+1 issues.

---

## 4. API Endpoints

### 4.1 Add or Replace a Reaction

```
PUT /api/activities/:activity_id/reactions
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "emoji": "🔥"
}
```

**Response `200 OK`:**
```json
{
  "reaction": {
    "activity_id": "activity-uuid",
    "user_id":     "user-uuid",
    "emoji":       "🔥",
    "created_at":  "2026-02-12T10:30:00Z"
  },
  "replaced": false
}
```

`replaced: true` if an existing reaction was swapped for a different emoji.

**Server logic:**
1. Verify JWT and that user is a member of the group that owns this activity
2. Validate emoji is in `ALLOWED_REACTIONS`
3. Upsert: `INSERT ... ON CONFLICT (activity_id, user_id) DO UPDATE SET emoji = $emoji, created_at = NOW()`
4. Set `replaced: true` in response if a previous emoji existed and was different
5. Fan out FCM notification to the activity's owner only (not the whole group — see Section 6)
6. Return reaction object

**Errors:**
- `400 Bad Request` — emoji not in allowed set
- `403 Forbidden` — user not a member of the group
- `404 Not Found` — activity_id does not exist

---

### 4.2 Remove a Reaction

```
DELETE /api/activities/:activity_id/reactions
Authorization: Bearer {access_token}
```

**Response `204 No Content`**

**Server logic:**
1. Verify JWT and group membership
2. `DELETE FROM activity_reactions WHERE activity_id = $1 AND user_id = $2`
3. If no row was deleted, return `404` (user had not reacted)
4. No FCM notification sent on removal

---

### 4.3 Reactions Embedded in Activity Feed

Reactions are returned inline with the existing activity feed response — not a separate endpoint — to avoid a second round trip on every page load:

```
GET /api/groups/:group_id/activity?limit=50&offset=0
```

**Response `200 OK`:**
```json
{
  "activities": [
    {
      "id": "activity-uuid",
      "activity_type": "progress_logged",
      "user": { "id": "user-uuid", "display_name": "Shannon O." },
      "metadata": { "goal_title": "30 min run", "value": 1 },
      "created_at": "2026-02-12T07:30:00Z",

      "reactions": [
        {
          "emoji":               "🔥",
          "count":               3,
          "reactor_ids":         ["user-a", "user-b", "user-c"],
          "current_user_reacted": true
        },
        {
          "emoji":               "💪",
          "count":               1,
          "reactor_ids":         ["user-d"],
          "current_user_reacted": false
        }
      ],
      "reaction_summary": {
        "total_count":  4,
        "top_reactors": [
          { "user_id": "user-a", "display_name": "Robert J." }
        ]
      }
    }
  ],
  "total": 84
}
```

The `reaction_summary` object is pre-computed server-side so the Android client can render the feed row label directly without any local aggregation logic.

---

### 4.4 Full Reactor List (for Bottom Sheet)

Only called on demand when the user taps the reaction summary row — not on initial feed load.

```
GET /api/activities/:activity_id/reactions
Authorization: Bearer {access_token}
```

**Response `200 OK`:**
```json
{
  "activity_id": "activity-uuid",
  "reactions": [
    {
      "emoji": "🔥",
      "user": {
        "id":           "user-uuid",
        "display_name": "Robert Johnson",
        "avatar_url":   "https://..."
      },
      "created_at": "2026-02-12T10:30:00Z"
    }
  ],
  "total": 5
}
```

---

## 5. Android Client Implementation

### 5.1 Composables Overview

| Composable | Responsibility |
|---|---|
| `ActivityFeedItem` | Renders a single feed row; wraps with `combinedClickable` for long-press detection |
| `ReactionPicker` | Floating horizontal emoji pill shown on long-press; handles tap-to-react |
| `ReactionSummaryRow` | Collapsed display (emoji + label) below the activity text; tappable |
| `ReactorsBottomSheet` | `ModalBottomSheet` with tab filter and full reactor list |
| `ReactorListItem` | Single row inside the bottom sheet: avatar + name + emoji |

---

### 5.2 Long-Press and Picker Behaviour

```kotlin
// ActivityFeedItem.kt (simplified)
@Composable
fun ActivityFeedItem(
    activity: ActivityFeedEntry,
    onReact: (activityId: String, emoji: String) -> Unit,
    onRemoveReaction: (activityId: String) -> Unit,
    onViewReactors: (activityId: String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { /* no-op or navigate */ },
                onLongClick = {
                    HapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    showPicker = true
                }
            )
    ) {
        ActivityContent(activity)

        if (activity.reactions.isNotEmpty()) {
            ReactionSummaryRow(
                reactions = activity.reactions,
                summary   = activity.reactionSummary,
                onClick   = { onViewReactors(activity.id) }
            )
        }

        if (showPicker) {
            ReactionPicker(
                currentUserEmoji = activity.reactions
                    .firstOrNull { it.currentUserReacted }?.emoji,
                onSelect = { emoji ->
                    showPicker = false
                    onReact(activity.id, emoji)
                },
                onDismiss = { showPicker = false }
            )
        }
    }
}
```

---

### 5.3 Optimistic UI Updates

Reactions must feel instant. Apply the update to local state before the API call completes, then reconcile with the server response:

1. User taps emoji in picker
2. Immediately update the `ActivityFeedEntry` in the local ViewModel state (add/replace/remove reaction)
3. Fire API call in background
4. On success: state is already correct — no UI update needed
5. On failure: roll back the local state and show a brief Snackbar ("Couldn't react — try again")

---

### 5.4 ReactionPicker Composable

```kotlin
val REACTION_EMOJIS = listOf("🔥", "💪", "❤️", "👏", "🤩", "😂")

@Composable
fun ReactionPicker(
    currentUserEmoji: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    Surface(
        shape = RoundedCornerShape(50),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.wrapContentWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            REACTION_EMOJIS.forEach { emoji ->
                val isSelected = emoji == currentUserEmoji
                Text(
                    text = emoji,
                    style = TextStyle(fontSize = if (isSelected) 32.sp else 28.sp),
                    modifier = Modifier
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                            CircleShape
                        )
                        .padding(6.dp)
                        .clickable { onSelect(emoji) }
                )
            }
        }
    }
}
```

---

### 5.5 ReactorsBottomSheet

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactorsBottomSheet(
    activityId: String,
    onDismiss: () -> Unit,
    viewModel: ReactorsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(activityId) { viewModel.load(activityId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        windowInsets = WindowInsets.navigationBars
    ) {
        var selectedTab by remember { mutableStateOf("all") }
        val tabs = buildTabs(uiState.reactions)

        ScrollableTabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(tabLabel(tab, uiState.reactions)) }
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(filteredReactors(uiState.reactions, selectedTab)) { reactor ->
                ReactorListItem(reactor)
            }
        }
    }
}
```

---

### 5.6 Room Cache Updates

Extend the existing cached activity entity to store reactions:

```kotlin
// Add to CachedActivityEntity
@ColumnInfo(name = "reactions_json")
val reactionsJson: String = "[]"   // serialized List<CachedReaction>

data class CachedReaction(
    val emoji: String,
    val count: Int,
    val reactorIds: List<String>,
    val currentUserReacted: Boolean
)

// Invalidation: when a PUT/DELETE /reactions call completes,
// update the cached entry in-place rather than re-fetching the
// whole activity page. Only refetch the full page on next app open.
```

---

## 6. Push Notifications

### 6.1 When to Notify

| Scenario | Notify? | Recipient |
|---|---|---|
| User A reacts to User B's log entry | Yes | User B only (the log entry owner) |
| User A reacts to their own entry | No | N/A |
| User A changes their reaction | No | N/A — avoid notification spam |
| User A removes their reaction | No | N/A |
| Multiple users react in quick succession | Batch within 60s window | User B gets one notification |

### 6.2 Notification Copy

```
// Single reactor
title: "Morning Runners"
body:  "Robert J. reacted 🔥 to your run"

// Two reactors in the same 60s window
title: "Morning Runners"
body:  "Robert J. and Sarah K. reacted to your run"

// Three or more in the window
title: "Morning Runners"
body:  "Robert J. and 2 others reacted to your run"
```

The notification deep-links directly to the activity entry in the feed, scrolled to position and with the `ReactorsBottomSheet` pre-opened.

### 6.3 Batching Logic (Server-Side)

1. When a reaction is added, check for a pending reaction notification for this `(activity_id, activity_owner_id)` pair within the last 60 seconds
2. If one exists: update the pending notification's reactor list and reschedule delivery for 60 seconds from now (sliding window)
3. If none exists: schedule a new notification for 60 seconds in the future
4. Use a simple job queue (existing Cloud Run task or `pg_cron`) to flush pending notifications

> **Simpler v1 approach:** notify immediately on the first reaction, suppress all subsequent reactions from different users on the same entry within 5 minutes. Implement batching as a v1.1 improvement.

---

## 7. Rate Limiting & Abuse Prevention

| Rule | Limit |
|---|---|
| `PUT /reactions` per user per minute | 30 requests / minute |
| `PUT /reactions` per user per activity | No additional limit (replace is idempotent) |
| `DELETE /reactions` per user per minute | 30 requests / minute |
| `GET /reactions` full list | 60 requests / minute (read-only, cheap) |

Rate limits use the existing per-user rate limiter middleware. No new infrastructure required.

---

## 8. Future Considerations

### 8.1 Reactions as an Engagement Metric

Once reaction data accumulates, the most-reacted log entries per week can surface as a "Best Moments" section on the group detail screen — a lightweight highlight reel that reinforces group identity and rewards consistent loggers.

### 8.2 Premium Differentiation

Do not gate reactions behind premium. Reactions benefit the whole group and drive retention for free users, which supports conversion. All 6 emoji and full reaction visibility should be available on the free tier.

### 8.3 Expanding the Emoji Set

Adding emoji later requires: (1) update `ALLOWED_REACTIONS` on the server, (2) update `REACTION_EMOJIS` in the Android client, (3) ship both together. No database migration required.

### 8.4 Reactions on All Activity Types

The schema uses `activity_id` as the foreign key, so reactions work on any activity type today (`member_joined`, `goal_added`, etc.). The UI should enable reactions on all activity types from the start — celebrating a new member joining is just as social as celebrating a logged workout.

---

## 9. Implementation Checklist

### Backend
- [ ] Create `activity_reactions` table and indexes (migration)
- [ ] Add `ALLOWED_REACTIONS` constant to shared constants file
- [ ] Implement `PUT /api/activities/:id/reactions` (upsert)
- [ ] Implement `DELETE /api/activities/:id/reactions`
- [ ] Implement `GET /api/activities/:id/reactions` (full list for bottom sheet)
- [ ] Update `GET /api/groups/:id/activity` to embed reactions in response
- [ ] Add batch reaction aggregation query to activity feed service
- [ ] Add rate limiting middleware to reaction endpoints
- [ ] Implement reaction notification logic (v1: immediate, suppress duplicates within 5 min)
- [ ] Add integration tests for all reaction endpoints

### Android
- [ ] Add reactions fields to `ActivityFeedEntry` data model and Room entity
- [ ] Update `ActivityFeedItem` composable with `combinedClickable` long-press
- [ ] Build `ReactionPicker` composable (floating pill)
- [ ] Build `ReactionSummaryRow` composable (emoji + label)
- [ ] Build `ReactorsBottomSheet` composable with tab filter
- [ ] Build `ReactorListItem` composable
- [ ] Implement optimistic UI update logic in `ActivityFeedViewModel`
- [ ] Handle rollback on API failure (Snackbar)
- [ ] Update Room cache invalidation to handle reaction changes
- [ ] Handle deep-link from reaction notification (open feed + bottom sheet)
- [ ] Haptic feedback on long-press

### Testing
- [ ] Unit test: emoji allowlist validation
- [ ] Unit test: reaction summary label logic (you / 1 other / N others)
- [ ] Unit test: aggregation query returns correct counts
- [ ] Integration test: add reaction, verify DB state
- [ ] Integration test: replace reaction (upsert), verify old emoji gone
- [ ] Integration test: remove reaction, verify 404 on second remove
- [ ] Integration test: rate limit enforcement
- [ ] UI test: long-press opens picker
- [ ] UI test: tapping reaction summary opens bottom sheet
- [ ] UI test: optimistic rollback on network failure

---

## Revision History

| Version | Date | Notes |
|---|---|---|
| 1.0 | February 2026 | Initial draft |
