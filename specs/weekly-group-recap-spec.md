# Weekly Group Recap — Push Notification & In-App Card

**Version:** 1.0  
**Date:** February 2026  
**Status:** Proposed  
**Depends On:** `group_daily_gcr`, `progress_entries`, `goals`, `group_memberships`, `user_notifications`, `group_heat`, FCM infrastructure, Smart Reminders scheduled job pattern

---

## 1. Overview

Every Sunday evening, each group member receives a push notification and an in-app card summarising how their group performed that week. The recap pulls from data Pursue already computes — Group Heat GCR, streaks, progress entries, reactions, nudges, and photo logs — and packages it into a single, shareable moment that closes out the week.

**Core Principle:** The recap is a celebration first, a nudge second. It should make people feel proud of what the group accomplished, highlight individual standouts, and create a natural "let's go again next week" energy.

### 1.1 Why This Matters

- **Re-engagement hook:** Sunday evening is the ideal time to prime users for Monday's goals. A recap that lands well brings dormant members back.
- **Social reinforcement:** Publicly surfacing individual achievements (streaks, most consistent, most reactions received) rewards the behaviours that keep groups alive.
- **Group identity:** The recap gives the group a shared narrative each week — "we" did this, not just "I" did this.
- **Retention driver:** Users who see their group performing well are less likely to churn. Users who see it slipping feel accountable to return.

### 1.2 Design Philosophy

The recap should feel like a WhatsApp message from a really organised friend — not a corporate dashboard email. Warm, concise, emoji-rich, and impossible to ignore in a notification tray.

---

## 2. Recap Content Design

### 2.1 Push Notification (Collapsed)

The push notification must be compelling in the ~100 characters visible on a lock screen. It leads with the group name and a single hook stat.

**Template:**

```
Title:   📊 {group_name} — Week in Review
Body:    {primary_headline}
```

**Primary headline selection** — pick the single most compelling stat using this priority waterfall:

| Priority | Condition | Headline |
|----------|-----------|----------|
| 1 | Group completion rate ≥ 90% | "{group_name} hit {rate}% this week! 💪" |
| 2 | A member reached a streak milestone (7, 14, 21, 30, 50, 100) this week | "{name} hit a {n}-day streak! 🔥 Your group finished at {rate}%." |
| 3 | Group Heat tier increased this week | "{group_name} is heating up — now at {tier_name}! 🔥" |
| 4 | Completion rate improved vs. last week | "{group_name} improved {delta}% this week — keep climbing! 📈" |
| 5 | Default | "{group_name} finished the week at {rate}% — here's your recap." |

**Examples:**

```
📊 Morning Momentum — Week in Review
Morning Momentum hit 87% this week! 💪

📊 Fitness Fam — Week in Review  
Sarah hit a 14-day streak! 🔥 Your group finished at 72%.

📊 Desk Warriors — Week in Review
Desk Warriors is heating up — now at Blaze! 🔥

📊 Book Club — Week in Review
Book Club improved 12% this week — keep climbing! 📈
```

### 2.2 In-App Recap Card

When the user taps the notification (or opens the notification inbox), they see a rich recap card. This is also inserted as a `weekly_recap` entry in `user_notifications`.

**Card Layout:**

```
┌─────────────────────────────────────────────┐
│  📊  WEEKLY RECAP · Feb 10–16               │
│  Morning Momentum                           │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │         87%                         │    │
│  │    Group Completion Rate            │    │
│  │    ████████████████████░░░          │    │
│  │    ↑ 5% vs last week               │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  🏆  HIGHLIGHTS                             │
│  ──────────────────────────────────────     │
│  🔥  Sarah — 14-day streak (longest!)       │
│  ⭐  Jamie — 100% completion this week      │
│  📸  12 photo check-ins this week           │
│  💬  34 reactions shared                    │
│                                             │
│  📋  GOAL BREAKDOWN                         │
│  ──────────────────────────────────────     │
│  Morning Run        92%  ████████████░      │
│  Meditate           78%  ██████████░░░      │
│  Read 30 min        88%  ███████████░░      │
│                                             │
│  🔥  GROUP HEAT                             │
│  ──────────────────────────────────────     │
│  Blaze (Score: 68)  · 9-day heat streak    │
│                                             │
│  [Open Group →]                             │
└─────────────────────────────────────────────┘
```

### 2.3 Content Sections (Detail)

#### Section 1: Group Completion Rate (Always shown)

The headline stat. Calculated from `group_daily_gcr` rows for the 7-day period (Monday–Sunday).

**Data source:** Average of `gcr` values from `group_daily_gcr` for the recap week.

**Display:**
- Percentage (rounded to nearest integer)
- Visual progress bar
- Week-over-week delta with ↑/↓ arrow (compare to prior 7-day average)

**Thresholds for colour/tone:**

| Rate | Colour | Tone |
|------|--------|------|
| ≥ 90% | Gold | Celebratory |
| 70–89% | Blue (primary) | Encouraging |
| 50–69% | Blue (muted) | Neutral |
| < 50% | Gray | Gentle — "Room to grow next week" |

#### Section 2: Highlights (Dynamic — show 2–4 items)

Pull from the week's data and select the most interesting highlights. Each highlight type has a priority score; show the top 2–4 items.

| Priority | Highlight | Source | Display |
|----------|-----------|--------|---------|
| 10 | Streak milestone reached (7/14/21/30/50/100) | `progress_entries` streak calculation | "🔥 {name} — {n}-day streak!" |
| 9 | Member with 100% completion rate | Weekly progress calculation | "⭐ {name} — 100% completion this week" |
| 8 | New longest streak in the group | Streak comparison | "🔥 {name} — {n}-day streak (longest!)" |
| 7 | Most improved member (biggest delta vs. last week) | Week-over-week completion delta | "📈 {name} — up {delta}% this week" |
| 6 | Photo check-in count (if ≥ 3) | `progress_entries` with `photo_url IS NOT NULL` | "📸 {count} photo check-ins this week" |
| 5 | Total reactions shared (if ≥ 5) | `reactions` table count for the week | "💬 {count} reactions shared" |
| 4 | Nudges sent (if ≥ 3) | `nudges` table count for the week | "👋 {count} nudges sent — the group's got each other's backs" |
| 3 | New member joined this week | `group_memberships.created_at` in range | "👋 Welcome {name} to the group!" |
| 2 | Group heat tier change (up) | `group_heat` tier comparison | "🔥 Group Heat is now {tier_name}!" |
| 1 | New goal added | `goals.created_at` in range | "🎯 New goal added: {goal_title}" |

**Rules:**
- Maximum 4 highlights per recap
- Each member can appear in at most 2 highlights (avoid one person dominating)
- If tied priority, prefer the highlight with the larger absolute number
- If fewer than 2 highlights qualify, fall back to generic encouragement: "Keep logging — consistency compounds! 💪"

#### Section 3: Goal Breakdown (Always shown, daily goals only)

Per-goal completion rate for the week. Shows all active daily goals sorted by completion rate (highest first).

**Data source:** For each goal, count completed (member, day) pairs / total possible (member, day) pairs for the 7-day window. Same logic as GCR but per-goal.

**Display:**
- Goal title
- Completion percentage
- Mini horizontal bar

**Cap:** Show top 5 goals if more than 5 exist. Add "and {n} more goals →" link.

#### Section 4: Group Heat (Shown if Group Heat feature is active)

Current heat tier, score, and heat streak. Pulled directly from `group_heat` table.

**Display:**
- Heat tier icon + tier name
- Numeric score (0–100)
- Heat streak days
- If heat went up this week: "↑ Rising" label
- If heat went down: omit (don't draw attention to decline in a celebratory card)

---

## 3. Data Aggregation

### 3.1 Recap Query

The recap requires data that's already being computed or is trivially derivable from existing tables. No new tracking tables needed — this is a read-only aggregation job.

```typescript
interface WeeklyRecapData {
  group: {
    id: string;
    name: string;
    iconUrl: string | null;
  };
  period: {
    startDate: string;  // Monday YYYY-MM-DD
    endDate: string;    // Sunday YYYY-MM-DD
  };
  completionRate: {
    current: number;      // 0–100
    previous: number;     // prior week, 0–100
    delta: number;        // current - previous
  };
  highlights: RecapHighlight[];
  goalBreakdown: GoalWeeklyStats[];
  heat: {
    score: number;
    tier: number;
    tierName: string;
    streakDays: number;
    tierDelta: number;    // current tier - tier at start of week
  } | null;
  memberCount: number;
}

interface RecapHighlight {
  type: string;
  priority: number;
  emoji: string;
  text: string;
  userId?: string;
  displayName?: string;
}

interface GoalWeeklyStats {
  goalId: string;
  title: string;
  completionRate: number;  // 0–100
  metricType: string;
}
```

### 3.2 Aggregation Queries

```typescript
async function buildWeeklyRecap(
  groupId: string,
  weekStartDate: string, // Monday
  weekEndDate: string     // Sunday
): Promise<WeeklyRecapData> {

  // 1. Group completion rate from existing group_daily_gcr
  const gcrRows = await db
    .selectFrom('group_daily_gcr')
    .select(['date', 'gcr', 'total_possible', 'total_completed'])
    .where('group_id', '=', groupId)
    .where('date', '>=', weekStartDate)
    .where('date', '<=', weekEndDate)
    .execute();

  const currentRate = gcrRows.length > 0
    ? Math.round((gcrRows.reduce((sum, r) => sum + r.gcr, 0) / gcrRows.length) * 100)
    : 0;

  // 2. Prior week for comparison
  const priorStart = subtractDays(weekStartDate, 7);
  const priorEnd = subtractDays(weekEndDate, 7);

  const priorGcrRows = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .where('date', '>=', priorStart)
    .where('date', '<=', priorEnd)
    .execute();

  const previousRate = priorGcrRows.length > 0
    ? Math.round((priorGcrRows.reduce((sum, r) => sum + r.gcr, 0) / priorGcrRows.length) * 100)
    : 0;

  // 3. Per-goal breakdown
  const goalStats = await calculatePerGoalCompletion(groupId, weekStartDate, weekEndDate);

  // 4. Per-member completion rates (for highlights)
  const memberStats = await calculatePerMemberCompletion(groupId, weekStartDate, weekEndDate);

  // 5. Streak data (current streaks for all members across all goals)
  const streakData = await getGroupStreaks(groupId);

  // 6. Photo count
  const photoCount = await db
    .selectFrom('progress_entries')
    .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
    .where('goals.group_id', '=', groupId)
    .where('progress_entries.period_start', '>=', weekStartDate)
    .where('progress_entries.period_start', '<=', weekEndDate)
    .where('progress_entries.photo_url', 'is not', null)
    .select(db.fn.count('progress_entries.id').as('count'))
    .executeTakeFirst();

  // 7. Reaction count
  const reactionCount = await db
    .selectFrom('reactions')
    .innerJoin('progress_entries', 'reactions.progress_entry_id', 'progress_entries.id')
    .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
    .where('goals.group_id', '=', groupId)
    .where('reactions.created_at', '>=', `${weekStartDate}T00:00:00Z`)
    .where('reactions.created_at', '<=', `${weekEndDate}T23:59:59Z`)
    .select(db.fn.count('reactions.id').as('count'))
    .executeTakeFirst();

  // 8. Nudge count
  const nudgeCount = await db
    .selectFrom('nudges')
    .where('group_id', '=', groupId)
    .where('created_at', '>=', `${weekStartDate}T00:00:00Z`)
    .where('created_at', '<=', `${weekEndDate}T23:59:59Z`)
    .select(db.fn.count('id').as('count'))
    .executeTakeFirst();

  // 9. New members
  const newMembers = await db
    .selectFrom('group_memberships')
    .innerJoin('users', 'group_memberships.user_id', 'users.id')
    .where('group_memberships.group_id', '=', groupId)
    .where('group_memberships.status', '=', 'approved')
    .where('group_memberships.created_at', '>=', `${weekStartDate}T00:00:00Z`)
    .where('group_memberships.created_at', '<=', `${weekEndDate}T23:59:59Z`)
    .select(['users.id', 'users.display_name'])
    .execute();

  // 10. Group heat
  const heat = await db
    .selectFrom('group_heat')
    .selectAll()
    .where('group_id', '=', groupId)
    .executeTakeFirst();

  // Build highlights from all the above
  const highlights = buildHighlights(memberStats, streakData, photoCount, reactionCount, nudgeCount, newMembers, heat);

  return {
    group: await getGroupBasicInfo(groupId),
    period: { startDate: weekStartDate, endDate: weekEndDate },
    completionRate: {
      current: currentRate,
      previous: previousRate,
      delta: currentRate - previousRate
    },
    highlights,
    goalBreakdown: goalStats.slice(0, 5),
    heat: heat ? {
      score: heat.score,
      tier: heat.tier,
      tierName: tierToName(heat.tier),
      streakDays: heat.streak_days,
      tierDelta: 0 // computed by comparing to stored start-of-week snapshot
    } : null,
    memberCount: memberStats.length
  };
}
```

### 3.3 Per-Member Completion

```typescript
async function calculatePerMemberCompletion(
  groupId: string,
  startDate: string,
  endDate: string
): Promise<Array<{ userId: string; displayName: string; rate: number }>> {

  const members = await getApprovedMembers(groupId);
  const goals = await getActiveDailyGoals(groupId);
  const dayCount = daysBetween(startDate, endDate) + 1; // inclusive

  const totalPossiblePerMember = goals.length * dayCount;
  if (totalPossiblePerMember === 0) return [];

  const entries = await db
    .selectFrom('progress_entries')
    .select(['user_id', 'goal_id', 'value', 'period_start'])
    .where('goal_id', 'in', goals.map(g => g.id))
    .where('period_start', '>=', startDate)
    .where('period_start', '<=', endDate)
    .execute();

  return members.map(member => {
    const memberEntries = entries.filter(e => e.user_id === member.userId);
    let completed = 0;

    for (const goal of goals) {
      for (let d = 0; d < dayCount; d++) {
        const date = addDays(startDate, d);
        const entry = memberEntries.find(
          e => e.goal_id === goal.id && e.period_start === date
        );
        if (entry && isCompleted(entry.value, goal.metricType, goal.targetValue)) {
          completed++;
        }
      }
    }

    return {
      userId: member.userId,
      displayName: member.displayName,
      rate: Math.round((completed / totalPossiblePerMember) * 100)
    };
  });
}
```

---

## 4. Scheduled Job

### 4.1 Timing

**When:** Every Sunday at 7:00 PM in the user's local timezone.

**Challenge:** Members in the same group may be in different timezones. 

**Solution:** Use the same approach as Smart Reminders — the job runs hourly (or every 30 minutes) and processes groups whose members are in the current timezone window.

```typescript
// Cloud Scheduler triggers every 30 minutes
// Job checks: "Which timezone offsets correspond to 7:00 PM right now?"
// Process all groups that have at least one member in those timezones

async function weeklyRecapJob() {
  const now = new Date();
  const dayOfWeek = now.getUTCDay(); // 0 = Sunday
  
  if (dayOfWeek !== 0) {
    console.log('Not Sunday, skipping.');
    return;
  }

  // Find timezone offsets where local time is 19:00 (7 PM)
  // For a job running at, say, 06:00 UTC, local 7 PM = UTC+13 (NZ in summer)
  const targetHour = 19;
  const currentUtcHour = now.getUTCHours();
  const targetOffset = targetHour - currentUtcHour; // hours ahead of UTC

  // Get all groups with members in this timezone band (±30 min)
  const groups = await getGroupsWithMembersInTimezone(targetOffset);

  for (const group of groups) {
    try {
      await generateAndSendRecap(group.id);
    } catch (err) {
      console.error(`Recap failed for group ${group.id}:`, err);
      // Continue with other groups — don't let one failure block all
    }
  }
}
```

**Deduplication:** Track which (group_id, week_end_date) combos have been sent in a `weekly_recaps_sent` table or by checking if the `user_notifications` entry already exists for that week. This prevents double-sends if the job runs multiple times.

### 4.2 Deduplication Table

```sql
CREATE TABLE weekly_recaps_sent (
  group_id    UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  week_end    DATE NOT NULL,  -- Sunday date
  sent_at     TIMESTAMP NOT NULL DEFAULT NOW(),
  PRIMARY KEY (group_id, week_end)
);
```

Before generating a recap, check:
```typescript
const alreadySent = await db
  .selectFrom('weekly_recaps_sent')
  .where('group_id', '=', groupId)
  .where('week_end', '=', weekEndDate)
  .executeTakeFirst();

if (alreadySent) return; // Skip — already sent for this week
```

### 4.3 Cloud Scheduler Configuration

```yaml
name: weekly-recap
schedule: "*/30 * * * 0"   # Every 30 minutes on Sundays
timezone: "UTC"
target:
  uri: https://api.getpursue.app/internal/jobs/weekly-recap
  httpMethod: POST
  headers:
    X-Job-Secret: ${INTERNAL_JOB_SECRET}
retryConfig:
  retryCount: 2
  minBackoffDuration: "60s"
```

---

## 5. Notification Delivery

### 5.1 FCM Push Notification

```json
{
  "notification": {
    "title": "📊 Morning Momentum — Week in Review",
    "body": "Morning Momentum hit 87% this week! 💪"
  },
  "data": {
    "type": "weekly_recap",
    "notification_id": "notif-uuid",
    "group_id": "group-uuid",
    "week_end": "2026-02-16"
  }
}
```

**Client handling:** Tapping the notification opens the group detail screen. The recap card is visible in the notification inbox.

### 5.2 Notification Inbox Entry

Insert into `user_notifications` for each member:

```typescript
await db.insertInto('user_notifications').values({
  user_id: memberId,
  type: 'weekly_recap',
  actor_user_id: null,         // system-generated
  group_id: groupId,
  goal_id: null,
  progress_entry_id: null,
  metadata: {
    week_start: weekStartDate,
    week_end: weekEndDate,
    completion_rate: recapData.completionRate.current,
    completion_delta: recapData.completionRate.delta,
    highlights: recapData.highlights,         // array of highlight objects
    goal_breakdown: recapData.goalBreakdown,  // array of goal stats
    heat: recapData.heat                      // heat snapshot or null
  },
  is_read: false
}).execute();
```

The `metadata` JSONB field stores the full recap payload so the client can render the card without additional API calls.

### 5.3 Notification Inbox — Visual Variant

Add to the existing notification type visual variants table:

| Type | Avatar | Accent | Icon overlay |
|------|--------|--------|--------------|
| Weekly recap | Group icon | Gold left border | 📊 |

### 5.4 Send Logic

For each group:

1. Build the `WeeklyRecapData`
2. Select the primary headline (§2.1 waterfall)
3. For each approved member in the group:
   a. Insert `user_notifications` row with full recap in metadata
   b. Send FCM push with headline
4. Insert deduplication row in `weekly_recaps_sent`

**Skip conditions:**
- Group has < 2 approved members (solo groups don't need a "group" recap)
- Group has 0 active daily goals
- Group was created this week (not enough data for a meaningful recap)
- All GCR values for the week are undefined (no possible completions)

---

## 6. Android Client

### 6.1 Recap Card Composable

The recap card is rendered inside the notification inbox when the user taps a `weekly_recap` notification row. The card is also accessible from the group detail screen as a dismissable banner during the first 48 hours after send.

```kotlin
@Composable
fun WeeklyRecapCard(
    recap: WeeklyRecapUiModel,
    onOpenGroup: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: period + group name
            RecapHeader(recap.groupName, recap.periodLabel)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Completion rate hero stat
            CompletionRateSection(
                rate = recap.completionRate,
                delta = recap.completionDelta
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Highlights
            if (recap.highlights.isNotEmpty()) {
                HighlightsSection(recap.highlights)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Goal breakdown
            if (recap.goalBreakdown.isNotEmpty()) {
                GoalBreakdownSection(recap.goalBreakdown)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Heat (if available)
            recap.heat?.let { heat ->
                HeatSection(heat)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // CTA
            TextButton(
                onClick = { onOpenGroup(recap.groupId) }
            ) {
                Text("Open Group →")
            }
        }
    }
}
```

### 6.2 Data Model (Client)

```kotlin
data class WeeklyRecapUiModel(
    val groupId: String,
    val groupName: String,
    val groupIconUrl: String?,
    val periodLabel: String,          // "Feb 10–16"
    val completionRate: Int,          // 0–100
    val completionDelta: Int,         // signed, vs last week
    val highlights: List<RecapHighlight>,
    val goalBreakdown: List<GoalWeeklyStat>,
    val heat: RecapHeat?
)

data class RecapHighlight(
    val emoji: String,
    val text: String
)

data class GoalWeeklyStat(
    val title: String,
    val completionRate: Int  // 0–100
)

data class RecapHeat(
    val score: Float,
    val tierName: String,
    val streakDays: Int
)
```

### 6.3 Parsing from Notification Metadata

The recap card is rendered by parsing the `metadata` JSONB from the `user_notifications` response. No additional API endpoint needed — the existing `GET /api/notifications` response carries the full payload.

```kotlin
fun parseRecapFromNotification(notification: NotificationResponse): WeeklyRecapUiModel? {
    if (notification.type != "weekly_recap") return null
    val meta = notification.metadata ?: return null
    
    return WeeklyRecapUiModel(
        groupId = notification.group?.id ?: return null,
        groupName = notification.group.name,
        groupIconUrl = notification.group.iconUrl,
        periodLabel = formatPeriod(meta.weekStart, meta.weekEnd),
        completionRate = meta.completionRate,
        completionDelta = meta.completionDelta,
        highlights = meta.highlights.map { RecapHighlight(it.emoji, it.text) },
        goalBreakdown = meta.goalBreakdown.map { GoalWeeklyStat(it.title, it.completionRate) },
        heat = meta.heat?.let { RecapHeat(it.score, it.tierName, it.streakDays) }
    )
}
```

---

## 7. Notification Preferences

### 7.1 Opt-Out

Weekly recaps are **on by default**. Users can disable them from:
- Group settings: "Receive weekly recap" toggle (per-group)
- Future notification preferences screen: global toggle for all recaps

Store preference as a column on `group_memberships`:

```sql
ALTER TABLE group_memberships
  ADD COLUMN weekly_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE;
```

The recap job checks this flag before sending.

### 7.2 Admin Control (Future)

Group admins may eventually want to disable recaps for the whole group (e.g. during a break). This could be a group-level setting, but is out of scope for v1.

---

## 8. Freemium Gating

| Feature | Free | Premium |
|---------|------|---------|
| Weekly recap push notification | ✅ | ✅ |
| Recap card in notification inbox | ✅ | ✅ |
| Highlights section | ✅ | ✅ |
| Goal breakdown (top 3 goals) | ✅ | ✅ |
| Goal breakdown (all goals) | ❌ | ✅ |
| Group Heat section in recap | ✅ | ✅ |
| Recap history (browse past weeks) | ❌ | ✅ |

The core recap is free — it's an engagement and retention feature. Premium users get the full goal breakdown and the ability to browse historical recaps.

**Recap history (premium):** Store recap snapshots in a `weekly_recap_history` table or rely on the `user_notifications` table (recaps older than 90 days would be purged under current retention policy). For premium, extend retention to 12 months for recap-type notifications.

---

## 9. Edge Cases

| Scenario | Handling |
|----------|----------|
| Group created this week | Skip recap — not enough data |
| Group has only 1 member | Skip recap — social features need ≥ 2 |
| Member joined mid-week | Include them from join date forward (don't penalise for days before joining) |
| All goals are non-daily (weekly/monthly/yearly) | Use weekly goal completion for the period if available; if no applicable goals have a completion window this week, skip recap |
| 0% completion rate | Still send — message: "Fresh start next week — your group's got this! 💪" |
| Member has notifications disabled at OS level | FCM will fail silently; inbox entry still created for when they open the app |
| Timezone edge: member in UTC-11 vs UTC+13 in same group | Each member receives their own recap at their local 7 PM Sunday; same recap content, different delivery times |
| Group archived or deleted mid-week | Skip recap — check group status before processing |

---

## 10. Message Copy Variants

### 10.1 Completion Rate Tone

| Rate | Card Header Tone |
|------|-----------------|
| ≥ 95% | "Incredible week! 🏆 Almost perfect." |
| 90–94% | "Crushing it! 💪 Outstanding week." |
| 80–89% | "Strong week! Keep the momentum going." |
| 70–79% | "Solid effort — consistency is building." |
| 50–69% | "Every log counts — keep showing up." |
| 25–49% | "Tough week, but you're still here. That matters." |
| < 25% | "Fresh start next week — your group's got this! 💪" |

### 10.2 Week-over-Week Delta

| Delta | Copy |
|-------|------|
| ≥ +20% | "🚀 Massive improvement!" |
| +10–19% | "📈 Nice jump from last week!" |
| +1–9% | "📈 Trending up — keep going." |
| 0% | "Holding steady." |
| -1 to -9% | "Slight dip — regroup and go again." |
| -10 to -19% | "Tougher week — everyone has them." |
| ≤ -20% | "Big reset ahead — rally the group next week." |

---

## 11. Testing Strategy

### 11.1 Unit Tests

- Headline priority waterfall selects correctly for all conditions
- Highlight builder respects max 4, max 2 per member rules
- Completion rate calculation handles partial weeks, mid-week joins
- Per-goal breakdown correctly counts only daily goals
- Deduplication prevents double-sends
- Skip conditions (< 2 members, 0 goals, new group) all work
- Timezone bucketing selects correct groups

### 11.2 Integration Tests

- Full recap generation for a group with known test data
- FCM push sent to all group members (mock FCM)
- `user_notifications` row created with correct metadata
- Opt-out flag respected (disabled member gets no push or inbox entry)
- Recap card renders correctly from notification metadata
- Tapping push opens correct group

### 11.3 Manual QA

- Create a group with 3+ members, log varied progress across a week, trigger recap manually
- Verify push text on lock screen is compelling and under character limit
- Verify recap card displays correctly on small (360dp) and large screens
- Verify highlights rotate correctly across weeks with different data profiles
- Test 0% week — ensure messaging is supportive, not punishing

---

## 12. Implementation Checklist

### Backend
- [ ] Create `weekly_recaps_sent` deduplication table
- [ ] Add `weekly_recap_enabled` column to `group_memberships`
- [ ] Implement `buildWeeklyRecap()` aggregation function
- [ ] Implement `buildHighlights()` with priority scoring
- [ ] Implement headline priority waterfall
- [ ] Create recap job function (timezone-aware scheduling)
- [ ] Configure Cloud Scheduler (every 30 min on Sundays)
- [ ] Add `weekly_recap` as notification type in `user_notifications` insert
- [ ] FCM push with recap headline
- [ ] Skip conditions (< 2 members, 0 goals, new group, dedup)
- [ ] Respect `weekly_recap_enabled` opt-out flag

### Android
- [ ] Add `weekly_recap` to notification type enum
- [ ] Implement `WeeklyRecapCard` composable
- [ ] Parse recap data from notification metadata
- [ ] Add gold left border + 📊 icon in notification inbox row
- [ ] Tap notification → open group detail
- [ ] Add "Receive weekly recap" toggle in group settings
- [ ] Recap banner on group detail screen (first 48 hours)

### Testing
- [ ] Unit tests for aggregation, headline selection, highlights
- [ ] Integration test for full recap pipeline
- [ ] Manual QA across data profiles

**Total estimated effort: 4–6 days**

---

## 13. Future Enhancements

- **Monthly recap:** A bigger, richer summary at month's end with 4-week trend charts and "Member of the Month" award
- **Shareable recap image:** Generate an Open Graph–style image card the user can share to Instagram Stories / WhatsApp (viral growth loop)
- **Personalised insights:** "You logged 3 more days than last week" — per-user stat overlaid on the group recap
- **Recap reactions:** Let members react to the recap itself (e.g., 🔥 on a great week), feeding back into the next week's highlights
- **Email digest:** Optional email version of the recap for users who prefer email over push
- **Weekly/monthly goal inclusion:** Extend recap to cover non-daily cadences once Group Heat supports them
