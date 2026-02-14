# Member Detail Screen Spec

**Feature:** MemberDetailFragment  
**Version:** 1.1  
**Date:** February 2026  
**Status:** Proposed

---

## 1. Overview

MemberDetailFragment provides a per-member view within a group, combining a progress overview (per-goal completion stats for a selected timeframe) with a chronological activity log. This is a single screen — no separate overview/log split — following the established Pursue pattern of a summary header above a scrollable list.

**Entry point:** Tap any member row in the Members tab of GroupDetailFragment.

**Who can view:** Any approved group member can view any other member's detail screen. This is consistent with existing group-wide progress visibility.

---

## 2. Timeframe Selection

The screen supports a fixed set of preset windows, surfaced as a horizontally scrollable chip row. No custom date picker.

| Chip Label | Date Range | Tier |
|---|---|---|
| This Week | Current Mon–Sun | Free |
| Last 7 Days | Today minus 6 days | Free |
| This Month | 1st of current month to today | Free |
| Last 30 Days | Today minus 29 days | **Free (max for free users)** |
| Last 3 Months | Today minus 89 days | **Premium** |
| Last 6 Months | Today minus 179 days | **Premium** |
| Last 12 Months | Today minus 364 days | **Premium** |

**Default selection:** Last 7 Days.

### 2.1 Premium Gating

Timeframes beyond 30 days are a premium feature, consistent with the progress history limit in the freemium model and the group progress export feature.

**Free user behaviour:** Premium chips are visible but shown with a lock icon (🔒) and the `pursue-gold` accent colour. Tapping a locked chip does not change the selection — instead it triggers an upsell bottom sheet (see Section 5).

**Premium user behaviour:** All chips are selectable. No lock icons shown.

**Enforcement:** The date range is sent as query parameters to the API. The server validates the requested range against the user's subscription status and returns `403 SUBSCRIPTION_REQUIRED` if a free user requests more than 30 days of history. The client enforces this in the UI as a first layer, but the server is authoritative.

---

## 3. Screen Layout

```
┌─────────────────────────────────────────┐
│  ←  [Group Name]                  ⋮     │  ← TopAppBar
├─────────────────────────────────────────┤
│                                         │
│   [Avatar 56dp]  Display Name           │
│                  Member · Joined Jan 26 │  ← Header card
│                                         │
├─────────────────────────────────────────┤
│  [Chip: This Week][Last 7D ✓][Month]    │
│  [Last 30D][🔒 3 Mo][🔒 6 Mo][🔒 12 Mo] │  ← Timeframe chips (scrollable)
├─────────────────────────────────────────┤
│  GOAL OVERVIEW                          │  ← Section header
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  🏃 Run 3x per week             │    │
│  │  ████████░░  67%   2 / 3 days   │    │
│  └─────────────────────────────────┘    │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  📖 Read 50 pages               │    │
│  │  ███████░░░░  70%   35 / 50 pg  │    │
│  └─────────────────────────────────┘    │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  💧 Drink 2L water (daily)      │    │
│  │  ██████████ 100%   5 / 5 days   │    │
│  └─────────────────────────────────┘    │
│                                         │
│  ACTIVITY LOG                           │  ← Section header
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  💧 Drink 2L water              │    │
│  │  Thu 12 Feb · 2 days ago  ✓    │    │
│  └─────────────────────────────────┘    │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  📖 Read 50 pages               │    │
│  │  Thu 12 Feb · 2 days ago  20pg  │    │
│  └─────────────────────────────────┘    │
│           [older entries ...]           │
└─────────────────────────────────────────┘
```

### 3.1 Header Card

- Member avatar (56dp, circular, falls back to initials on `MaterialTheme` surface)
- `display_name` as title text
- Role badge: "Admin" (gold chip) or "Member" (surface chip)
- "Joined [Month Year]" subtitle — sourced from `group_memberships.joined_at`
- If viewing own profile: no change in layout (viewing your own stats is valid and useful)

### 3.2 Goal Overview Cards

One card per active (non-archived) goal in the group.

Each card contains:
- Goal emoji + title
- Linear progress bar (`LinearProgressIndicator`, `pursue-blue` fill, grey track)
- Percentage label (integer, e.g. `67%`)
- Completion fraction appropriate to metric type:
  - **Binary goal:** `{completed} / {total} days` (or `weeks`, `months` depending on cadence)
  - **Numeric goal:** `{completed_value} / {target_value} {unit}` (e.g. `35 / 50 pg`)
- If no progress logged for this timeframe: progress bar at 0%, label shows `0 / {total} days` — do not hide the card

**Cadence note for binary goals:** "total" reflects the number of periods within the selected timeframe, not a fixed number. For example, a daily goal over "Last 7 Days" has a total of 7; a weekly goal has a total of 1.

### 3.3 Activity Log

Chronological list of the member's progress entries within the selected timeframe, newest first. Each row matches the existing activity feed item style:

- Goal emoji + title
- Human-readable date (e.g. `Thu 12 Feb · 2 days ago`)
- Value: `✓` for binary completions; numeric value + unit for numeric goals (e.g. `20 pg`, `35 min`)
- If a note or photo was attached to the entry, show a secondary line with note text (truncated to 1 line) or a thumbnail (32dp)
- Reactions row if any reactions exist (reusing the existing reaction component)

**Pagination:** The activity log uses cursor-based infinite scroll. The server returns 50 entries per page. When the user scrolls within 5 items of the bottom of the list, the ViewModel automatically fetches the next page using the `next_cursor` from the previous response. A subtle `CircularProgressIndicator` (24dp) is shown at the bottom of the list while a page is loading. Once `next_cursor` is `null`, no further fetches are triggered.

If no entries exist in the selected timeframe, show an empty state illustration with copy: `"[Name] hasn't logged any progress in this period."`

---

## 4. API

### 4.1 New Endpoint

```
GET /api/groups/{group_id}/members/{user_id}/progress
    ?start_date=2026-02-06
    &end_date=2026-02-13
    &cursor=<opaque_string>   (optional — omit for first page)
    &limit=50                 (optional — default and max: 50)
```

**Auth:** Bearer token. Caller must be an approved member of the group.

**Authorization rules:**
- Any approved group member may request progress for any other member in the same group.
- `user_id` in the path must be an approved member of `group_id`.
- If `start_date` is more than 30 days before `end_date` AND the requesting user is not premium: return `403 SUBSCRIPTION_REQUIRED`.

**Pagination behaviour:**
- `goal_summaries` is always returned in full on every page — it is not paginated, as it aggregates over the whole timeframe.
- `activity_log` is paginated. The first request omits `cursor`. Subsequent requests pass the `next_cursor` value from the previous response.
- The cursor is an opaque base64-encoded string encoding `{ logged_at, entry_id }` of the last item returned. Using both fields ensures stable ordering even when multiple entries share the same `logged_at` timestamp.
- The server orders `activity_log` by `logged_at DESC, entry_id DESC` and uses a keyset (seek) query: `WHERE (logged_at, entry_id) < (cursor_logged_at, cursor_entry_id)`. This is more efficient than `OFFSET` at large page depths.

**First page response (200 OK):**

```json
{
  "member": {
    "user_id": "uuid",
    "display_name": "Alex",
    "avatar_url": "https://...",
    "role": "member",
    "joined_at": "2026-01-10T00:00:00Z"
  },
  "timeframe": {
    "start_date": "2026-02-06",
    "end_date": "2026-02-13"
  },
  "goal_summaries": [
    {
      "goal_id": "uuid",
      "title": "Run 3x per week",
      "emoji": "🏃",
      "cadence": "weekly",
      "metric_type": "binary",
      "target_value": 3,
      "unit": null,
      "completed": 2,
      "total": 3,
      "percentage": 67
    },
    {
      "goal_id": "uuid",
      "title": "Read 50 pages",
      "emoji": "📖",
      "cadence": "weekly",
      "metric_type": "numeric",
      "target_value": 50,
      "unit": "pg",
      "completed": 35,
      "total": 50,
      "percentage": 70
    }
  ],
  "activity_log": [
    {
      "entry_id": "uuid",
      "goal_id": "uuid",
      "goal_title": "Read 50 pages",
      "goal_emoji": "📖",
      "value": 20,
      "unit": "pg",
      "metric_type": "numeric",
      "entry_date": "2026-02-12",
      "logged_at": "2026-02-12T18:45:00Z",
      "note": "Finally finished chapter 3",
      "photo_url": null,
      "reactions": [
        { "emoji": "🔥", "count": 2 }
      ]
    }
    // ... up to 50 entries
  ],
  "pagination": {
    "next_cursor": "eyJsb2dnZWRfYXQiOiIyMDI2LTAyLTEwVDA5OjAwOjAwWiIsImVudHJ5X2lkIjoidXVpZC14eXoifQ==",
    "has_more": true,
    "total_in_timeframe": 87
  }
}
```

**Last page response — `next_cursor` is null:**

```json
{
  "pagination": {
    "next_cursor": null,
    "has_more": false,
    "total_in_timeframe": 87
  }
}
```

**Note:** `total_in_timeframe` is returned on every page and reflects the total count of activity log entries across the full timeframe, regardless of the current page. This lets the client show e.g. "Showing 50 of 87 entries" without a separate count query. Use a `COUNT(*)` window function in the same query rather than a second round-trip.

**Error responses:**

| Status | Code | Condition |
|---|---|---|
| 400 | `INVALID_DATE_RANGE` | `end_date` before `start_date`, or dates malformed |
| 400 | `INVALID_CURSOR` | `cursor` parameter is malformed or expired |
| 403 | `NOT_A_MEMBER` | Requesting user not an approved member of the group |
| 403 | `TARGET_NOT_A_MEMBER` | `user_id` in path is not a member of the group |
| 403 | `SUBSCRIPTION_REQUIRED` | Date range exceeds 30 days and user is not premium |
| 404 | `GROUP_NOT_FOUND` | Group does not exist |

### 4.2 Server Implementation Notes

- Use a single aggregating query to fetch `goal_summaries` — join `goals` and `progress_entries`, filter by `user_id` and date range, aggregate in SQL. This is not paginated and always returns the full summary.
- `activity_log` uses a separate keyset pagination query:
  ```sql
  SELECT pe.*, g.title, g.emoji, COUNT(*) OVER() AS total_count
  FROM progress_entries pe
  JOIN goals g ON g.id = pe.goal_id
  WHERE g.group_id = $group_id
    AND pe.user_id = $user_id
    AND pe.entry_date BETWEEN $start_date AND $end_date
    AND ($cursor IS NULL OR (pe.logged_at, pe.id) < ($cursor_logged_at, $cursor_entry_id))
  ORDER BY pe.logged_at DESC, pe.id DESC
  LIMIT $limit + 1
  ```
  Fetch `limit + 1` rows — if `limit + 1` rows are returned, there is a next page; strip the extra row before responding and set `has_more: true`. If `≤ limit` rows are returned, set `has_more: false` and `next_cursor: null`.
- Cursor encoding: `base64(JSON.stringify({ logged_at: <ISO string>, entry_id: <uuid> }))`. Validate on decode — return `400 INVALID_CURSOR` if malformed.
- Left-join reactions onto the activity log entries in the same query to avoid N+1.
- Reuse `calculateCompleted` and `calculateTotal` helpers from the existing `attachProgressToGoals` implementation for `goal_summaries`.
- Premium check: compare `start_date` to `end_date` — if the interval exceeds 30 days, verify the requesting user's subscription status before executing any data queries.

---

## 5. Premium Upsell

When a free user taps a locked timeframe chip, show a `ModalBottomSheet` with:

**Title:** "Unlock Full Progress History"

**Body:** "See up to 12 months of progress history for any group member. Premium also includes unlimited group creation and data export."

**CTA button (primary):** "Upgrade to Premium – $30/year" → navigates to the existing subscription flow

**Secondary link:** "Maybe later" → dismisses the sheet

This is the same upsell pattern used by the group progress export feature for consistency.

---

## 6. Navigation

**Entry:** `GroupDetailFragment` → Members tab → tap member row → navigate to `MemberDetailFragment` with args: `groupId`, `userId`.

**Back:** Standard back navigation returns to GroupDetailFragment (Members tab).

**Navigation args (Safe Args):**

```kotlin
// In nav_graph.xml
<fragment
    android:id="@+id/memberDetailFragment"
    android:name="app.pursue.ui.group.MemberDetailFragment">
    <argument
        android:name="groupId"
        app:argType="string" />
    <argument
        android:name="userId"
        app:argType="string" />
</fragment>
```

---

## 7. ViewModel

```kotlin
class MemberDetailViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    val selectedTimeframe = MutableStateFlow(Timeframe.LAST_7_DAYS)

    // First-page state (member info, goal summaries, first 50 log entries)
    val memberDetail: StateFlow<UiState<MemberDetailUiModel>> = selectedTimeframe
        .flatMapLatest { timeframe ->
            groupRepository.getMemberProgress(
                groupId = groupId,
                userId = userId,
                startDate = timeframe.startDate,
                endDate = timeframe.endDate,
                cursor = null
            )
        }
        .onEach { result ->
            // Reset pagination state on timeframe change
            _activityLog.value = result.activityLog
            _nextCursor.value = result.pagination.nextCursor
            _hasMore.value = result.pagination.hasMore
            _totalInTimeframe.value = result.pagination.totalInTimeframe
        }
        .map { result -> UiState.Success(result.toUiModel()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    // Accumulated activity log entries across all loaded pages
    private val _activityLog = MutableStateFlow<List<ActivityLogEntry>>(emptyList())
    val activityLog: StateFlow<List<ActivityLogEntry>> = _activityLog.asStateFlow()

    private val _nextCursor = MutableStateFlow<String?>(null)
    private val _hasMore = MutableStateFlow(false)
    private val _totalInTimeframe = MutableStateFlow(0)
    val totalInTimeframe: StateFlow<Int> = _totalInTimeframe.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    fun onTimeframeSelected(timeframe: Timeframe) {
        selectedTimeframe.value = timeframe
    }

    /** Called by the UI when the user scrolls near the bottom of the activity log. */
    fun onLoadMoreRequested() {
        val cursor = _nextCursor.value ?: return
        if (_isLoadingMore.value || !_hasMore.value) return

        viewModelScope.launch {
            _isLoadingMore.value = true
            val result = groupRepository.getMemberProgress(
                groupId = groupId,
                userId = userId,
                startDate = selectedTimeframe.value.startDate,
                endDate = selectedTimeframe.value.endDate,
                cursor = cursor
            )
            _activityLog.value = _activityLog.value + result.activityLog
            _nextCursor.value = result.pagination.nextCursor
            _hasMore.value = result.pagination.hasMore
            _isLoadingMore.value = false
        }
    }
}

enum class Timeframe(val labelRes: Int, val isPremium: Boolean) {
    THIS_WEEK(R.string.timeframe_this_week, isPremium = false),
    LAST_7_DAYS(R.string.timeframe_last_7_days, isPremium = false),
    THIS_MONTH(R.string.timeframe_this_month, isPremium = false),
    LAST_30_DAYS(R.string.timeframe_last_30_days, isPremium = false),
    LAST_3_MONTHS(R.string.timeframe_last_3_months, isPremium = true),
    LAST_6_MONTHS(R.string.timeframe_last_6_months, isPremium = true),
    LAST_12_MONTHS(R.string.timeframe_last_12_months, isPremium = true);

    // Computed start/end dates (using user's local date)
    val startDate: LocalDate get() = /* calculate based on enum */ LocalDate.now()
    val endDate: LocalDate get() = LocalDate.now()
}
```

**Load-more trigger in the Composable:**

```kotlin
val listState = rememberLazyListState()

// Trigger load-more when within 5 items of the bottom
val shouldLoadMore by remember {
    derivedStateOf {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = listState.layoutInfo.totalItemsCount
        lastVisible >= total - 5
    }
}

LaunchedEffect(shouldLoadMore) {
    if (shouldLoadMore) viewModel.onLoadMoreRequested()
}
```

---

## 8. Caching

- Cache first-page responses (no cursor) in Room under a composite key: `(groupId, userId, startDate, endDate)`.
- Do not cache subsequent pages — only the first page is cached, as subsequent pages are loaded on demand via scroll and are unlikely to be revisited before a TTL expires.
- TTL: 5 minutes. After TTL, refetch the first page silently in the background, reset the accumulated activity log to the fresh first page, and update the UI.
- On FCM push for a new progress entry in this group, invalidate the cached first page for the affected `userId` in this `groupId` so the next screen open reflects the new entry.
- Display cached first-page data immediately on load (no blank loading screen), then refresh.

---

## 9. Touch Budget

This screen adds zero touches to any existing flow — it is reached via an existing member list tap which was previously a no-op. The timeframe chip selection is a single tap. No new interaction patterns are introduced.

---

## 10. Accessibility

- Progress bar: `contentDescription = "${goal.title}: ${goal.percentage}% complete, ${goal.completed} of ${goal.total}"`
- Locked chips: `contentDescription = "${label}, requires Premium subscription"`
- Avatar: `contentDescription = "${member.displayName}'s avatar"`
- All touch targets minimum 48dp.

---

## 11. Open Questions

- Should admins be able to see a member's progress detail before that member is approved (i.e., while pending)? Recommendation: no — only approved members appear in the member list and are viewable.
- Should the member detail screen be accessible from the activity feed (tap the author of a log entry) in addition to the members tab? Yes - This would be a natural entry point.
