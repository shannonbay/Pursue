# Group Heat — Dynamic Group Momentum Feature

**Version:** 1.0  
**Last Updated:** February 16, 2026  
**Status:** Proposed  
**Depends On:** Existing progress_entries, goals, groups, group_memberships tables

---

## 1. Overview

Group Heat is a visual momentum indicator displayed on each group card that reflects how consistently the group is performing relative to its own recent baseline. Rather than requiring 100% daily completion (which is fragile and punishing), Group Heat uses a **rolling 7-day average with a 1-day lag** to create a dynamic threshold that rewards sustained effort and improvement while being forgiving of individual bad days.

**Core Principle:** The flame reflects *trajectory*, not perfection. A group that's been steadily improving feels the heat rising. A group that's slipping sees the flame dim — but one bad day from one member doesn't kill a 20-day streak.

### 1.1 Why This Matters

- **Positive peer pressure** without the fragility of all-or-nothing streaks
- **Visual identity** for the group — the flame becomes something the group collectively owns
- **Retention driver** — groups with visible momentum are less likely to go dormant
- **Conversation starter** — "We're at Inferno, don't let it drop!" creates natural in-group banter

---

## 2. Heat Score Algorithm

### 2.1 Definitions

- **Group Completion Rate (GCR)** for a given day: The percentage of (member, goal) pairs that were completed that day, considering only active daily goals and approved members.
- **Trailing Average**: The average GCR over a rolling 7-day window.
- **Comparison Day**: Yesterday (D-1).
- **Baseline Window**: The 7 days ending 2 days ago (D-8 through D-2).

### 2.2 Daily GCR Calculation

For a given date `D`, considering only **daily cadence goals** that are active (not archived) and members with `status = 'approved'`:

```
GCR(D) = (number of completed (member, goal) pairs on D) / (total active members × total active daily goals on D)
```

**What counts as "completed" for a (member, goal) pair on day D:**
- **Binary goals:** A `progress_entries` row exists with `value = 1` and `period_start = D`
- **Numeric goals:** A `progress_entries` row exists with `value >= target_value` and `period_start = D`
- **Duration goals:** A `progress_entries` row exists with `value >= target_value` and `period_start = D`

**Edge cases:**
- If a group has 0 active daily goals, GCR is undefined → heat score stays at its current value (no change)
- If a group has 0 approved members, GCR is undefined → heat score = 0
- Members who joined mid-window are included from their join date forward (not retroactively penalized)
- Goals created mid-window are included from their creation date forward

### 2.3 Heat Score Update Formula

The heat score is a continuous value from **0 to 100**, updated once daily (via scheduled job or on-demand when the group card is fetched).

**Step 1:** Calculate yesterday's GCR:
```
gcr_yesterday = GCR(D-1)
```

**Step 2:** Calculate the trailing 7-day average GCR (D-8 through D-2):
```
baseline = AVG(GCR(D-8), GCR(D-7), ..., GCR(D-2))
```

**Step 3:** Calculate the delta:
```
delta = gcr_yesterday - baseline
```

**Step 4:** Update the heat score:
```
new_heat_score = clamp(current_heat_score + (delta × SENSITIVITY), 0, 100)
```

Where:
- `SENSITIVITY = 50` — Controls how responsive the heat score is to changes. A delta of +0.10 (10% improvement over baseline) would add 5 points; a delta of -0.10 would subtract 5 points.
- `clamp(value, min, max)` ensures the score stays within [0, 100].

**Step 5:** Apply natural decay:
```
final_heat_score = new_heat_score × DECAY_FACTOR
```

Where `DECAY_FACTOR = 0.98` — A small daily decay (2%) ensures the flame naturally needs "feeding" even for perfect groups. Without this, a group at 100 could coast indefinitely. This means a stagnant-but-perfect group loses ~1 point/day, creating a gentle pressure to keep going.

### 2.4 Initial Heat Score

When a group is newly created or when this feature launches for existing groups:
- **New groups:** `heat_score = 0` — the flame starts cold and must be earned.
- **Existing groups at feature launch:** Backfill by computing GCR for each of the prior 14 days, then simulate the heat score updates forward from an initial score of 50 (neutral starting point for groups with history).

### 2.5 Worked Example

A group with 4 members and 3 active daily goals (12 possible completions per day):

| Day | Completions | GCR |
|-----|------------|-----|
| D-8 | 9/12 | 75% |
| D-7 | 10/12 | 83% |
| D-6 | 8/12 | 67% |
| D-5 | 10/12 | 83% |
| D-4 | 11/12 | 92% |
| D-3 | 9/12 | 75% |
| D-2 | 10/12 | 83% |
| D-1 (yesterday) | 11/12 | 92% |

- Baseline (D-8 to D-2): (75 + 83 + 67 + 83 + 92 + 75 + 83) / 7 = **79.7%**
- Yesterday: **92%**
- Delta: 92 - 79.7 = **+12.3%**
- Score change: 12.3 × 50 / 100 = **+6.15 points**
- If current heat_score was 62: new = (62 + 6.15) × 0.98 = **66.8**

The group is trending up — the flame grows.

---

## 3. Heat Tiers & Visual Representation

### 3.1 Tier Definitions

The heat score maps to 8 tiers, with a color progression that mirrors real flame physics — from cool yellow embers through orange and red, up to the hottest blue-cyan flame:

| Tier | Score Range | Name | Icon Description | Dominant Color |
|------|-----------|------|-----------------|----------------|
| 0 | 0–5 | Cold | No icon displayed | N/A |
| 1 | 6–18 | Spark | Faint warm glow, barely visible ember | `#FFF9C4` (light yellow) |
| 2 | 19–32 | Ember | Small smoldering ember, gentle warmth | `#FFD54F` (amber yellow) |
| 3 | 33–46 | Flicker | Small flame taking shape, visible movement | `#FFB300` (deep amber/orange) |
| 4 | 47–60 | Steady | Solid medium flame, consistent burn | `#FF6D00` (vivid orange) |
| 5 | 61–74 | Blaze | Large intense flame with heat shimmer | `#E53935` (red) |
| 6 | 75–88 | Inferno | Fierce roaring flame, white-hot core | `#D50000` (deep red) with `#FF8A80` (white-hot) highlights |
| 7 | 89–100 | Supernova | Peak intensity flame with cyan core + pulse animation | `#00E5FF` (cyan) with `#E53935` (red) outer edges |

### 3.2 Icon Specifications

**Source Assets:** 7 pre-generated PNG images at 1024×1024px, named `flame1.png` through `flame7.png`, mapping to tiers 1–7 respectively. These are the master assets from which density-specific versions are derived.

**Density Variants:** Generate from the 1024×1024 source PNGs:
- `mdpi`: 20×20px
- `hdpi`: 30×30px
- `xhdpi`: 40×40px
- `xxhdpi`: 60×60px
- `xxxhdpi`: 80×80px

Place in `res/drawable-{density}/`:
- `ic_heat_spark.png` ← flame1.png (Tier 1)
- `ic_heat_ember.png` ← flame2.png (Tier 2)
- `ic_heat_flicker.png` ← flame3.png (Tier 3)
- `ic_heat_steady.png` ← flame4.png (Tier 4)
- `ic_heat_blaze.png` ← flame5.png (Tier 5)
- `ic_heat_inferno.png` ← flame6.png (Tier 6)
- `ic_heat_supernova.png` ← flame7.png (Tier 7)

**Display size on group card:** 20dp × 20dp, positioned to the right of the group name or alongside the member count.

**Note:** No icon is rendered for Tier 0 (Cold).

### 3.3 Animation (Supernova Tier Only)

At Tier 7 (Supernova), apply a subtle pulse animation to draw the eye:

```kotlin
// Compose animation for Inferno tier
val infiniteTransition = rememberInfiniteTransition(label = "inferno_pulse")

val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.4f,
    targetValue = 0.8f,
    animationSpec = infiniteRepeatable(
        animation = tween(1200, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
    ),
    label = "glow_alpha"
)

val scale by infiniteTransition.animateFloat(
    initialValue = 1.0f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
        animation = tween(1200, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse
    ),
    label = "scale"
)
```

The animation should be performant — no frame drops on mid-range devices. Use `graphicsLayer` for hardware-accelerated transforms.

### 3.4 Accessibility

- Each tier has a content description: e.g., `"Group heat: Blaze (score 65)"` for screen readers
- The flame icon is decorative alongside text information, so it supplements but does not replace the group card's existing content
- Color is not the sole indicator — the icon shape, size, and intensity changes meaningfully across all 7 visible tiers for colorblind users
- The progression from yellow → orange → red → cyan provides distinguishable hue shifts even for most forms of color vision deficiency

---

## 4. Database Schema

### 4.1 New Table: `group_heat`

```sql
CREATE TABLE group_heat (
  group_id UUID PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  heat_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  heat_tier SMALLINT NOT NULL DEFAULT 0, -- 0-7, derived from heat_score
  last_calculated_at TIMESTAMP WITH TIME ZONE,
  streak_days INTEGER NOT NULL DEFAULT 0, -- consecutive days heat_score increased
  peak_score DECIMAL(5,2) NOT NULL DEFAULT 0.00, -- all-time high
  peak_date DATE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Trigger for updated_at
CREATE TRIGGER update_group_heat_updated_at 
  BEFORE UPDATE ON group_heat
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
```

### 4.2 New Table: `group_daily_gcr`

Stores computed daily GCR values to avoid recalculating historical data every time:

```sql
CREATE TABLE group_daily_gcr (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  date DATE NOT NULL,
  total_possible INTEGER NOT NULL, -- (active members × active daily goals) on this day
  total_completed INTEGER NOT NULL, -- completed (member, goal) pairs
  gcr DECIMAL(5,4) NOT NULL, -- 0.0000 to 1.0000
  member_count INTEGER NOT NULL, -- snapshot for debugging
  goal_count INTEGER NOT NULL, -- snapshot for debugging
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  
  UNIQUE(group_id, date)
);

CREATE INDEX idx_gcr_group_date ON group_daily_gcr(group_id, date DESC);
```

### 4.3 Migration Script

```sql
-- Migration: 20260216_add_group_heat.sql
-- Description: Add Group Heat feature tables

BEGIN;

-- Create group_heat table
CREATE TABLE IF NOT EXISTS group_heat (
  group_id UUID PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  heat_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  heat_tier SMALLINT NOT NULL DEFAULT 0,
  last_calculated_at TIMESTAMP WITH TIME ZONE,
  streak_days INTEGER NOT NULL DEFAULT 0,
  peak_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  peak_date DATE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TRIGGER update_group_heat_updated_at 
  BEFORE UPDATE ON group_heat
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create group_daily_gcr table
CREATE TABLE IF NOT EXISTS group_daily_gcr (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  date DATE NOT NULL,
  total_possible INTEGER NOT NULL,
  total_completed INTEGER NOT NULL,
  gcr DECIMAL(5,4) NOT NULL,
  member_count INTEGER NOT NULL,
  goal_count INTEGER NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(group_id, date)
);

CREATE INDEX idx_gcr_group_date ON group_daily_gcr(group_id, date DESC);

-- Initialize group_heat for all existing groups
INSERT INTO group_heat (group_id, heat_score, heat_tier)
SELECT id, 0.00, 0 FROM groups WHERE deleted_at IS NULL
ON CONFLICT (group_id) DO NOTHING;

COMMIT;
```

### 4.4 Rollback Script

```sql
-- Rollback: 20260216_add_group_heat.sql

BEGIN;

DROP TABLE IF EXISTS group_daily_gcr;
DROP TABLE IF EXISTS group_heat;

COMMIT;
```

---

## 5. Backend API

### 5.1 Scheduled Job: Daily Heat Calculation

A scheduled job runs once daily (e.g., 04:00 UTC, configurable) to compute GCR for the previous day and update all group heat scores. This can be triggered by a Cloud Scheduler → Cloud Run endpoint, or a simple cron within the Node.js process.

**Endpoint (internal, secured by API key or Cloud Scheduler header):**

```
POST /api/internal/jobs/calculate-heat
Authorization: Bearer {INTERNAL_JOB_KEY}
```

**Server Logic:**

```typescript
async function calculateDailyHeat(): Promise<void> {
  const yesterday = getYesterdayDate(); // In UTC, or per-group timezone if needed
  
  // 1. Get all active groups
  const groups = await db
    .selectFrom('groups')
    .select(['id'])
    .where('deleted_at', 'is', null)
    .execute();
  
  for (const group of groups) {
    await calculateGroupHeat(group.id, yesterday);
  }
}

async function calculateGroupHeat(groupId: string, date: string): Promise<void> {
  // 1. Calculate yesterday's GCR (if not already computed)
  const existingGcr = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .where('date', '=', date)
    .executeTakeFirst();
  
  let yesterdayGcr: number;
  
  if (existingGcr) {
    yesterdayGcr = Number(existingGcr.gcr);
  } else {
    yesterdayGcr = await computeAndStoreGcr(groupId, date);
  }
  
  // 2. Get baseline: trailing 7-day average (D-8 through D-2)
  const baselineStart = subtractDays(date, 8);
  const baselineEnd = subtractDays(date, 2);
  
  const baselineRows = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .where('date', '>=', baselineStart)
    .where('date', '<=', baselineEnd)
    .execute();
  
  // If no baseline data (new group), use yesterday's GCR as baseline
  // This means delta = 0, and the score just decays slightly
  const baseline = baselineRows.length > 0
    ? baselineRows.reduce((sum, r) => sum + Number(r.gcr), 0) / baselineRows.length
    : yesterdayGcr;
  
  // 3. Calculate delta and update heat score
  const SENSITIVITY = 50;
  const DECAY_FACTOR = 0.98;
  
  const currentHeat = await db
    .selectFrom('group_heat')
    .select(['heat_score', 'streak_days', 'peak_score'])
    .where('group_id', '=', groupId)
    .executeTakeFirst();
  
  const currentScore = currentHeat ? Number(currentHeat.heat_score) : 0;
  const currentStreak = currentHeat ? currentHeat.streak_days : 0;
  const currentPeak = currentHeat ? Number(currentHeat.peak_score) : 0;
  
  const delta = yesterdayGcr - baseline;
  const rawNewScore = currentScore + (delta * SENSITIVITY);
  const decayedScore = Math.max(0, Math.min(100, rawNewScore)) * DECAY_FACTOR;
  const finalScore = Math.round(decayedScore * 100) / 100; // 2 decimal places
  
  // 4. Determine tier
  const tier = scoreToTier(finalScore);
  
  // 5. Update streak and peak
  const newStreak = finalScore > currentScore ? currentStreak + 1 : 0;
  const newPeak = finalScore > currentPeak ? finalScore : currentPeak;
  const peakDate = finalScore > currentPeak ? date : currentHeat?.peak_date;
  
  // 6. Upsert group_heat
  await db
    .insertInto('group_heat')
    .values({
      group_id: groupId,
      heat_score: finalScore,
      heat_tier: tier,
      last_calculated_at: new Date(),
      streak_days: newStreak,
      peak_score: newPeak,
      peak_date: peakDate,
    })
    .onConflict((oc) =>
      oc.column('group_id').doUpdateSet({
        heat_score: finalScore,
        heat_tier: tier,
        last_calculated_at: new Date(),
        streak_days: newStreak,
        peak_score: newPeak,
        peak_date: peakDate,
      })
    )
    .execute();
}

function scoreToTier(score: number): number {
  if (score <= 5) return 0;
  if (score <= 18) return 1;
  if (score <= 32) return 2;
  if (score <= 46) return 3;
  if (score <= 60) return 4;
  if (score <= 74) return 5;
  if (score <= 88) return 6;
  return 7;
}
```

### 5.2 GCR Computation

```typescript
async function computeAndStoreGcr(groupId: string, date: string): Promise<number> {
  // Get active members on this date
  const members = await db
    .selectFrom('group_memberships')
    .select(['user_id'])
    .where('group_id', '=', groupId)
    .where('status', '=', 'approved')
    .where('joined_at', '<=', `${date}T23:59:59Z`)
    .execute();
  
  // Get active daily goals on this date
  const goals = await db
    .selectFrom('goals')
    .select(['id', 'metric_type', 'target_value'])
    .where('group_id', '=', groupId)
    .where('cadence', '=', 'daily')
    .where('deleted_at', 'is', null)
    .where('created_at', '<=', `${date}T23:59:59Z`)
    .where((eb) =>
      eb.or([
        eb('archived_at', 'is', null),
        eb('archived_at', '>', `${date}T23:59:59Z`)
      ])
    )
    .execute();
  
  const memberCount = members.length;
  const goalCount = goals.length;
  const totalPossible = memberCount * goalCount;
  
  if (totalPossible === 0) {
    // Store GCR as 0 with 0 possible — no impact on heat
    await storeGcrRow(groupId, date, 0, 0, 0, memberCount, goalCount);
    return 0;
  }
  
  // Get all progress entries for this group's daily goals on this date
  const goalIds = goals.map(g => g.id);
  const memberIds = members.map(m => m.user_id);
  
  const entries = await db
    .selectFrom('progress_entries')
    .select(['goal_id', 'user_id', 'value'])
    .where('goal_id', 'in', goalIds)
    .where('user_id', 'in', memberIds)
    .where('period_start', '=', date)
    .execute();
  
  // Count completed pairs
  let completedCount = 0;
  for (const goal of goals) {
    for (const member of members) {
      const entry = entries.find(
        e => e.goal_id === goal.id && e.user_id === member.user_id
      );
      if (entry && isCompleted(entry.value, goal.metric_type, goal.target_value)) {
        completedCount++;
      }
    }
  }
  
  const gcr = completedCount / totalPossible;
  
  await storeGcrRow(groupId, date, totalPossible, completedCount, gcr, memberCount, goalCount);
  
  return gcr;
}

function isCompleted(value: number, metricType: string, targetValue: number | null): boolean {
  switch (metricType) {
    case 'binary':
      return value === 1;
    case 'numeric':
    case 'duration':
      return targetValue !== null && value >= targetValue;
    default:
      return false;
  }
}

async function storeGcrRow(
  groupId: string, date: string,
  totalPossible: number, totalCompleted: number, gcr: number,
  memberCount: number, goalCount: number
): Promise<void> {
  await db
    .insertInto('group_daily_gcr')
    .values({
      group_id: groupId,
      date,
      total_possible: totalPossible,
      total_completed: totalCompleted,
      gcr: Math.round(gcr * 10000) / 10000,
      member_count: memberCount,
      goal_count: goalCount,
    })
    .onConflict((oc) =>
      oc.columns(['group_id', 'date']).doUpdateSet({
        total_possible: totalPossible,
        total_completed: totalCompleted,
        gcr: Math.round(gcr * 10000) / 10000,
        member_count: memberCount,
        goal_count: goalCount,
      })
    )
    .execute();
}
```

### 5.3 API Changes: Include Heat in Group Responses

Add heat data to existing group endpoints so the client can display it without additional API calls.

**Modified: `GET /api/groups` (group list)**

Add to each group object in the response:

```json
{
  "id": "group-uuid",
  "name": "Morning Momentum",
  "member_count": 4,
  "heat": {
    "score": 72.45,
    "tier": 4,
    "tier_name": "Blaze",
    "streak_days": 5,
    "peak_score": 88.12,
    "last_calculated_at": "2026-02-16T04:00:00Z"
  }
}
```

**Modified: `GET /api/groups/:group_id` (group detail)**

Same `heat` object as above, plus additional context:

```json
{
  "heat": {
    "score": 72.45,
    "tier": 4,
    "tier_name": "Blaze",
    "streak_days": 5,
    "peak_score": 88.12,
    "peak_date": "2026-02-10",
    "last_calculated_at": "2026-02-16T04:00:00Z",
    "yesterday_gcr": 0.92,
    "baseline_gcr": 0.80
  }
}
```

**Implementation:** LEFT JOIN `group_heat` in the existing group queries:

```typescript
const groups = await db
  .selectFrom('groups')
  .leftJoin('group_heat', 'group_heat.group_id', 'groups.id')
  .select([
    'groups.id',
    'groups.name',
    // ... existing fields ...
    'group_heat.heat_score',
    'group_heat.heat_tier',
    'group_heat.streak_days',
    'group_heat.peak_score',
    'group_heat.peak_date',
    'group_heat.last_calculated_at',
  ])
  .where('groups.deleted_at', 'is', null)
  .execute();
```

### 5.4 New Endpoint: Group Heat History (Premium)

For premium users who want to see their group's heat trend over time:

```
GET /api/groups/:group_id/heat/history?days=30
Authorization: Bearer {access_token}
```

**Response (200 OK):**

```json
{
  "group_id": "group-uuid",
  "current": {
    "score": 72.45,
    "tier": 4,
    "tier_name": "Blaze"
  },
  "history": [
    { "date": "2026-02-15", "score": 70.21, "tier": 4, "gcr": 0.83 },
    { "date": "2026-02-14", "score": 68.50, "tier": 4, "gcr": 0.75 },
    { "date": "2026-02-13", "score": 69.88, "tier": 4, "gcr": 0.92 }
  ],
  "stats": {
    "avg_score_30d": 65.3,
    "peak_score": 88.12,
    "peak_date": "2026-02-10",
    "days_at_supernova": 3,
    "longest_increase_streak": 8
  }
}
```

**Authorization:** User must be a group member. The `history` and `stats` fields require premium subscription; free users receive only the `current` object.

---

## 6. Android UI Implementation

### 6.1 Group Card Integration

The heat icon appears on the group card in the home screen group list, positioned to the right of the group name:

```
┌─────────────────────────────────┐
│  Morning Momentum    🔥  4 👤   │
│  3 goals · 92% today           │
└─────────────────────────────────┘
```

**Compose Implementation:**

```kotlin
@Composable
fun GroupCard(
    group: GroupWithHeat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group icon/avatar
            GroupAvatar(group.iconUrl)
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    if (group.heat.tier > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        HeatIcon(
                            tier = group.heat.tier,
                            score = group.heat.score,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Text(
                    text = "${group.goalCount} goals · ${group.memberCount} members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

### 6.2 HeatIcon Composable

```kotlin
@Composable
fun HeatIcon(
    tier: Int,
    score: Float,
    modifier: Modifier = Modifier
) {
    val contentDescription = when (tier) {
        0 -> "Group heat: Cold"
        1 -> "Group heat: Spark (score ${score.toInt()})"
        2 -> "Group heat: Ember (score ${score.toInt()})"
        3 -> "Group heat: Flicker (score ${score.toInt()})"
        4 -> "Group heat: Steady (score ${score.toInt()})"
        5 -> "Group heat: Blaze (score ${score.toInt()})"
        6 -> "Group heat: Inferno (score ${score.toInt()})"
        7 -> "Group heat: Supernova (score ${score.toInt()})"
        else -> "Group heat"
    }
    
    if (tier == 0) return // Don't render anything for Cold
    
    val iconRes = when (tier) {
        1 -> R.drawable.ic_heat_spark
        2 -> R.drawable.ic_heat_ember
        3 -> R.drawable.ic_heat_flicker
        4 -> R.drawable.ic_heat_steady
        5 -> R.drawable.ic_heat_blaze
        6 -> R.drawable.ic_heat_inferno
        7 -> R.drawable.ic_heat_supernova
        else -> return
    }
    
    if (tier == 7) {
        // Supernova: animated pulse
        SupernovaHeatIcon(
            iconRes = iconRes,
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = modifier,
            tint = Color.Unspecified // Use drawable's native colors
        )
    }
}

@Composable
private fun SupernovaHeatIcon(
    iconRes: Int,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "supernova")
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // Cyan glow background circle
        Box(
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { alpha = glowAlpha }
                .background(
                    color = Color(0xFF00E5FF).copy(alpha = 0.3f),
                    shape = CircleShape
                )
        )
        
        // Flame icon with scale
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            tint = Color.Unspecified
        )
    }
}
```

### 6.3 Group Detail: Heat Section (Optional Enhancement)

On the group detail screen, show a more detailed heat view below the group header:

```
┌─────────────────────────────────────┐
│  🔥 Blaze · Score: 72              │
│  ▲ 5-day streak · Peak: 88         │
│  Yesterday: 92% (baseline: 80%)    │
└─────────────────────────────────────┘
```

This section is always visible to all members. The history chart (showing score over time) is premium-only, accessible via a "View History" link that prompts upgrade for free users.

### 6.4 Data Model (Client)

```kotlin
data class GroupHeat(
    val score: Float = 0f,
    val tier: Int = 0,
    val tierName: String = "Cold",
    val streakDays: Int = 0,
    val peakScore: Float = 0f,
    val peakDate: String? = null,
    val lastCalculatedAt: String? = null,
    val yesterdayGcr: Float? = null,   // group detail only
    val baselineGcr: Float? = null     // group detail only
)

data class GroupWithHeat(
    val id: String,
    val name: String,
    val memberCount: Int,
    val goalCount: Int,
    val iconUrl: String?,
    val heat: GroupHeat
)
```

---

## 7. Activity Feed Integration

### 7.1 Heat Milestone Activities

Generate group activity entries when the group reaches significant heat milestones:

**Activity types to add:**

| Activity Type | Trigger | Message |
|--------------|---------|---------|
| `heat_tier_up` | Tier increases | "🔥 Group heat is rising! Now at Blaze." |
| `heat_tier_down` | Tier decreases | "The group flame is cooling — keep logging!" |
| `heat_supernova_reached` | First time reaching Tier 7 | "💥 SUPERNOVA! The group is burning blue-hot!" |
| `heat_streak_milestone` | streak_days hits 7, 14, 30 | "🔥 7-day heat streak! Keep the momentum!" |

**Implementation:** During the daily heat calculation job, compare old tier/streak with new values and insert `group_activities` rows as needed.

### 7.2 Push Notifications

Send FCM notifications for positive milestones only (tier up, supernova reached, streak milestones). Do NOT send notifications for tier drops — that's visible in the app and doesn't need to be a push notification that could feel punishing.

---

## 8. Timezone Considerations

Group Heat calculation uses `period_start` from `progress_entries`, which already stores the user's local date. Since different members may be in different timezones, the daily GCR calculation uses a fixed reference: the **group creator's timezone** (or a group-level timezone setting if added later).

For V1, calculate GCR based on calendar date in UTC. Since GCR is a relative measure compared against its own baseline, timezone edge effects are minimal — they affect all days equally and cancel out in the delta calculation.

**Future Enhancement:** Add a `timezone` column to `groups` table and use it for GCR date boundaries.

---

## 9. Performance Considerations

### 9.1 Scheduled Job Performance

The daily job iterates all active groups. For scale targets of hundreds to low thousands of groups:

- **Sequential processing** is fine at this scale (~1-2 seconds per group)
- Each group requires 3-4 queries: members, goals, progress entries, upsert GCR + heat
- For 1,000 groups: ~15-60 minutes total (acceptable for a daily batch job at 4 AM)

**Future optimization (10K+ groups):** Batch process groups in parallel chunks of 50, use a single bulk query to fetch all progress entries for all groups for the target date.

### 9.2 API Response Impact

Adding `group_heat` data to group list responses adds one LEFT JOIN — negligible impact on query performance with proper indexing (the PRIMARY KEY on `group_heat.group_id` serves as the index).

### 9.3 GCR Table Growth

One row per group per day. For 1,000 groups over 1 year: ~365,000 rows. Very manageable. Add a retention policy to prune rows older than 90 days if desired (the heat score itself persists in `group_heat`).

---

## 10. Freemium Gating

| Feature | Free | Premium |
|---------|------|---------|
| Heat icon on group card | ✅ | ✅ |
| Heat tier name + score | ✅ | ✅ |
| Heat streak days | ✅ | ✅ |
| Heat detail on group screen | ✅ | ✅ |
| Heat history chart (30-day trend) | ❌ | ✅ |
| Heat statistics (peak, avg, supernova days) | ❌ | ✅ |

The heat icon and current state are always visible — this is a core engagement feature. The historical analytics are premium because they provide deeper insight that power users value.

---

## 11. Testing Strategy

### 11.1 Unit Tests

- `scoreToTier()` — verify all boundary values (10, 11, 25, 26, etc.)
- `isCompleted()` — binary, numeric, duration with various values
- Heat score update formula — verify delta calculation, decay, clamping at 0 and 100
- GCR calculation with edge cases: 0 members, 0 goals, members who joined mid-window

### 11.2 Integration Tests

- Full daily heat calculation for a test group with known progress data
- Verify GCR rows are created correctly
- Verify heat score updates match expected values from worked examples
- Verify tier transitions generate correct activity entries
- Verify group list API includes heat data
- Verify group detail API includes extended heat data

### 11.3 Manual Testing Checklist

- [ ] New group starts at heat score 0, no icon displayed
- [ ] Group with consistent daily logging sees heat rise over 7+ days
- [ ] Group with declining logging sees heat drop
- [ ] All 7 visible tier transitions are visually distinct on group card
- [ ] Color progression reads clearly: yellow → amber → orange → red → cyan
- [ ] Supernova animation runs smoothly on low-end device
- [ ] Screen reader correctly announces heat tier and score
- [ ] Heat data loads without adding noticeable latency to group list
- [ ] Heat history endpoint returns 403 for free users requesting stats
- [ ] Activity feed shows tier-up messages at correct moments

### 11.4 Test Data Scenarios

**Scenario A: Ramp Up**
- Days 1-14: GCR rises from 0.3 to 0.9 steadily
- Expected: Heat score climbs from 0 through Spark → Ember → Flicker → Steady → Blaze

**Scenario B: Plateau**
- Days 1-14: GCR stable at 0.75
- Expected: Heat score rises initially then stabilizes as baseline catches up, slow decay keeps it from hitting max

**Scenario C: One Bad Day**
- Days 1-10: GCR at 0.85, Day 11: GCR drops to 0.40, Day 12: back to 0.85
- Expected: Heat dips on day 12 calculation but recovers by day 14 — the flame dims but doesn't die

**Scenario D: Group Goes Dormant**
- Days 1-7: GCR at 0.80, Days 8+: GCR at 0.0
- Expected: Heat decays steadily toward 0 over ~2 weeks

**Scenario E: Sustained Excellence**
- Days 1-30: GCR consistently at 0.95+
- Expected: Heat climbs through all tiers to Supernova (89+), cyan flame with pulse animation

---

## 12. Rollout Plan

### Phase 1: Backend (1-2 days)
1. Run migration to create `group_heat` and `group_daily_gcr` tables
2. Deploy heat calculation job (disabled, manual trigger only)
3. Run backfill for existing groups (last 14 days of GCR data)
4. Verify heat scores look reasonable for test groups
5. Enable scheduled daily execution

### Phase 2: API (1 day)
1. Add LEFT JOIN to group list and group detail queries
2. Add heat object to group response serializers
3. Deploy and verify responses include heat data
4. Implement heat history endpoint (premium-gated)

### Phase 3: Android (2-3 days)
1. Create 5 heat icon drawables (ember through inferno)
2. Implement `HeatIcon` composable with Inferno animation
3. Integrate into `GroupCard` on home screen
4. Add heat detail section to group detail screen
5. Parse heat data from updated API responses
6. Test on multiple device sizes and densities

### Phase 4: Activity Feed (1 day)
1. Add tier transition activity generation to heat calculation job
2. Add FCM push for positive milestones
3. Test activity feed entries appear correctly

**Total estimated effort: 5-7 days**

---

## 13. Future Enhancements

- **Weekly/monthly goal inclusion:** Extend GCR to factor in non-daily cadences with appropriate weighting
- **Group timezone setting:** Allow groups to set their own timezone for GCR date boundaries
- **Heat leaderboard:** Compare heat scores across a user's groups (premium)
- **Heat-based nudges:** Auto-nudge members when the group's heat is declining and they haven't logged
- **Customizable flame colors:** Premium cosmetic option to change the flame color/style
- **Heat milestones/badges:** "Reached Supernova 5 times" badge on group profile
