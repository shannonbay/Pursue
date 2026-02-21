# Pursue — Active Days (Per-Goal Scheduling) Specification

**Feature:** Active Days — configurable day-of-week scheduling per goal  
**Version:** 1.0  
**Status:** Draft  
**Platform:** Android (Material Design 3)  
**Depends On:** Existing goals, progress_entries, smart reminders, nudges, group heat, streaks, weekly recap, challenges

---

## 1. Overview

### 1.1 Problem

Pursue currently supports daily goals (every day) and weekly numeric goals (e.g., "gym 3x per week"). But many real-world habits don't fit either pattern cleanly:

- **Exercise goals** where rest days matter: gym Mon–Fri, rest on weekends
- **Work-related goals** like "journal before work" that only apply on weekdays
- **Religious or cultural rest days**: Sundays off, or Fridays off
- **Alternating schedules**: Mon/Wed/Fri workout plans

Today, users must either track daily (and feel guilty on rest days) or use a weekly numeric target (which loses the daily accountability signal). Neither is ideal.

### 1.2 Solution

Add an optional **active days** configuration to daily goals. When set, the goal is only expected on the selected days of the week. Off-days are invisible to the accountability system: no check-ins expected, no nudges sent, no streak penalties, no heat impact.

### 1.3 Design Principles

- **Per-goal, not per-member.** Everyone in the group shares the same schedule for a given goal. This preserves the shared accountability model — you can always tell if a teammate is slacking vs resting.
- **Opt-in simplicity.** Default is "every day" (no change to current behaviour). Active days are an optional configuration during goal creation or editing.
- **Off-days are invisible.** The system pretends off-days don't exist. Streaks skip them, heat ignores them, reminders don't fire, nudge buttons don't appear.
- **Only applies to daily goals.** Weekly, monthly, and yearly goals already have natural flexibility built into their cadence.

### 1.4 Scope

- **In scope:** Goal-level active days for daily goals, with full integration across smart reminders, nudges, streaks, group heat, weekly recaps, progress export, and challenges.
- **Out of scope:** Per-member schedules (too complex, undermines shared accountability), sub-daily scheduling (e.g., "morning only"), and active days for non-daily cadences.

---

## 2. User Experience

### 2.1 Goal Creation Flow

When creating a daily goal, an optional "Active Days" selector appears below the cadence picker:

```
┌─────────────────────────────────────────────┐
│  ← Create Goal                              │
├─────────────────────────────────────────────┤
│                                             │
│  Title                                      │
│  ┌─────────────────────────────────────┐    │
│  │ Morning run                         │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  Cadence                                    │
│  [Daily ▾]                                  │
│                                             │
│  Active Days                                │
│  ┌─────────────────────────────────────┐    │
│  │ (S) (M) (T) (W) (T) (F) (S)       │    │
│  │  ○   ●   ●   ●   ●   ●   ○       │    │
│  └─────────────────────────────────────┘    │
│  Weekdays only · 5 days/week                │
│                                             │
│  Metric Type                                │
│  [Binary ▾]                                 │
│                                             │
│  ...                                        │
│                                             │
│  [Create Goal]                              │
│                                             │
└─────────────────────────────────────────────┘
```

**Day selector behaviour:**

- Seven circular toggle buttons, one for each day (Mon–Sun), displayed in the user's locale order
- All days selected by default (equivalent to current "every day" behaviour)
- Tapping a day toggles it on/off
- At least one day must remain selected; attempting to deselect the last active day shows a brief toast: "At least one day must be active"
- Below the selector, a helper label summarises the selection:
  - All 7 days: "Every day"
  - Mon–Fri: "Weekdays only · 5 days/week"
  - Sat–Sun: "Weekends only · 2 days/week"
  - Mon, Wed, Fri: "Mon, Wed, Fri · 3 days/week"
  - Custom: "Tue, Thu, Sat · 3 days/week"
- The active days selector is only visible when cadence is "daily"

### 2.2 Goal Editing

Admins can change active days on an existing goal via the goal edit screen. Changes take effect **from the next day forward** — the current day's status is not retroactively changed.

When active days are changed:
- A group activity entry is created: "Shannon updated 'Morning run' active days to Mon–Fri"
- Existing progress entries are not modified or deleted
- Streaks are recalculated from the next day forward using the new schedule

### 2.3 Goal Display

On the group detail screen and the Today screen:

- **Active day:** Goal appears normally with check-in UI
- **Off-day:** Goal row is either:
  - **Option A (recommended):** Hidden entirely from the Today screen and the group detail goal list. This keeps the interface clean and focused on what's actionable today.
  - On the group detail screen, an off-day goal can optionally show in a collapsed "Rest days" section at the bottom if the user wants visibility into the full goal list.

### 2.4 Off-Day Voluntary Logging

Users *can* still log progress on off-days if they choose to (e.g., they decided to run on Saturday even though it's a rest day). The progress entry is stored normally but is treated as a bonus — it doesn't affect streak calculations or completion rates. This is handled by allowing the user to navigate to the goal and manually log, but the goal won't appear in the default Today view.

**Implementation:** If a user navigates to the group detail screen, off-day goals appear in a "Rest day" collapsed section with a subtle label. Tapping the goal allows logging. The progress entry is saved with a `is_bonus = false` flag (no special flagging needed — the system simply doesn't penalise off-days).

---

## 3. Database Schema

### 3.1 Goals Table Changes

Add a single column to the existing `goals` table:

```sql
-- Migration: 20260220_add_active_days.sql

BEGIN;

-- Add active_days column to goals
-- Stored as a bitmask integer (0-127) where each bit represents a day:
--   Bit 0 (1)  = Sunday
--   Bit 1 (2)  = Monday
--   Bit 2 (4)  = Tuesday
--   Bit 3 (8)  = Wednesday
--   Bit 4 (16) = Thursday
--   Bit 5 (32) = Friday
--   Bit 6 (64) = Saturday
-- 
-- 127 (1111111) = every day (default)
-- 62  (0111110) = Mon-Fri (weekdays)
-- 65  (1000001) = Sat-Sun (weekends)
-- 42  (0101010) = Mon, Wed, Fri
-- NULL = every day (backward compatibility — treated as 127)

ALTER TABLE goals ADD COLUMN active_days INTEGER DEFAULT NULL;

-- Constraint: if set, must be between 1 and 127
ALTER TABLE goals ADD CONSTRAINT chk_active_days CHECK (
  active_days IS NULL OR (active_days >= 1 AND active_days <= 127)
);

-- Constraint: active_days only applies to daily goals
ALTER TABLE goals ADD CONSTRAINT chk_active_days_cadence CHECK (
  active_days IS NULL OR cadence = 'daily'
);

COMMIT;
```

**Why a bitmask instead of an array or separate table?**

- A single integer is the most compact representation (no joins, no array parsing)
- Bitwise operations in both SQL and application code are trivial and fast
- 7 bits fit cleanly in a small integer
- NULL means "every day" for backward compatibility — no migration needed for existing goals
- Sunday-first bit ordering (0=Sun, 6=Sat) matches JavaScript's `Date.getDay()` natively, eliminating day-of-week conversion logic

### 3.2 Helper Functions

```typescript
// Shared utility: src/utils/activeDays.ts

/** Day-of-week constants (bitmask values, matching JS getDay(): 0=Sun) */
export const DAYS = {
  SUN: 1,    // Bit 0
  MON: 2,    // Bit 1
  TUE: 4,    // Bit 2
  WED: 8,    // Bit 3
  THU: 16,   // Bit 4
  FRI: 32,   // Bit 5
  SAT: 64,   // Bit 6
} as const;

export const ALL_DAYS = 127; // 1111111
export const WEEKDAYS = 62;  // 0111110
export const WEEKENDS = 65;  // 1000001

/**
 * Check if a specific day is active for a goal.
 * @param activeDays - Bitmask from goals.active_days (null = all days)
 * @param date - The date to check
 * @returns true if the goal is active on this day
 */
export function isDayActive(activeDays: number | null, date: Date): boolean {
  if (activeDays === null) return true; // null = every day
  
  // JavaScript: getDay() returns 0=Sun, 1=Mon, ..., 6=Sat
  // Our bitmask: bit 0=Sun, bit 1=Mon, ..., bit 6=Sat (same ordering!)
  const jsDay = date.getDay(); // 0=Sun, 1=Mon, ..., 6=Sat
  
  return (activeDays & (1 << jsDay)) !== 0;
}

/**
 * Check if a specific day-of-week (0=Sun, 6=Sat) is active.
 * Used when you already know the day index (e.g., from pattern calculator).
 */
export function isDayOfWeekActive(activeDays: number | null, dayOfWeek: number): boolean {
  if (activeDays === null) return true;
  return (activeDays & (1 << dayOfWeek)) !== 0;
}

/**
 * Count the number of active days per week for a goal.
 */
export function countActiveDays(activeDays: number | null): number {
  if (activeDays === null) return 7;
  let count = 0;
  for (let i = 0; i < 7; i++) {
    if (activeDays & (1 << i)) count++;
  }
  return count;
}

/**
 * Count active days within a date range.
 * Used for calculating completion rates and heat GCR.
 */
export function countActiveDaysInRange(
  activeDays: number | null, 
  startDate: Date, 
  endDate: Date
): number {
  if (activeDays === null) {
    // All days active — simple day count
    return Math.floor((endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24)) + 1;
  }
  
  let count = 0;
  const current = new Date(startDate);
  while (current <= endDate) {
    if (isDayActive(activeDays, current)) count++;
    current.setDate(current.getDate() + 1);
  }
  return count;
}

/**
 * Convert bitmask to array of day names for display.
 */
export function activeDaysToLabels(activeDays: number | null): string {
  if (activeDays === null || activeDays === ALL_DAYS) return 'Every day';
  if (activeDays === WEEKDAYS) return 'Weekdays only';
  if (activeDays === WEEKENDS) return 'Weekends only';
  
  const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const active = dayNames.filter((_, i) => activeDays & (1 << i));
  return active.join(', ');
}

/**
 * Convert array of day indices to bitmask.
 * @param days - Array of day indices (0=Sun, 6=Sat)
 */
export function daysToBitmask(days: number[]): number {
  return days.reduce((mask, day) => mask | (1 << day), 0);
}
```

---

## 4. API Changes

### 4.1 Goal Creation

Update `POST /api/groups/:group_id/goals` to accept an optional `active_days` field:

```json
POST /api/groups/{group_id}/goals
Authorization: Bearer {access_token}

{
  "title": "Morning run",
  "cadence": "daily",
  "metric_type": "binary",
  "active_days": [1, 2, 3, 4, 5]  // Mon-Fri (client sends array of day indices, 0=Sun)
}
```

**Server logic:**
1. If `cadence` is not `daily` and `active_days` is provided → return 400: "Active days can only be set for daily goals"
2. If `active_days` is provided:
   - Validate it's an array of integers, each 0–6
   - Validate at least one day is included
   - Validate no duplicates
   - Convert to bitmask: `active_days.reduce((mask, day) => mask | (1 << day), 0)`
3. If `active_days` is omitted or `null` → store NULL (every day)

**Response:** The goal object includes `active_days` as an array (or `null`):

```json
{
  "id": "goal-uuid",
  "title": "Morning run",
  "cadence": "daily",
  "metric_type": "binary",
  "active_days": [1, 2, 3, 4, 5],
  "active_days_label": "Weekdays only",
  ...
}
```

### 4.2 Goal Update

Update `PATCH /api/goals/:goal_id` to accept `active_days`:

```json
PATCH /api/goals/{goal_id}
Authorization: Bearer {access_token}

{
  "active_days": [1, 3, 5]  // Mon, Wed, Fri
}
```

Same validation as creation. Changing to `null` resets to every day.

**Server logic (additional):**
1. Create activity entry: `goal_updated` with metadata `{ "field": "active_days", "old_value": [1,2,3,4,5], "new_value": [1,3,5] }`
2. Do NOT retroactively modify progress entries or streak data

### 4.3 Goal Response Serialisation

All endpoints that return goal objects include:

```typescript
// In goal serializer
function serializeGoal(goal: GoalRow): GoalResponse {
  const activeDaysArray = goal.active_days !== null
    ? bitmaskToDays(goal.active_days) // Convert bitmask back to array
    : null;
  
  return {
    ...goal,
    active_days: activeDaysArray,
    active_days_label: activeDaysToLabels(goal.active_days),
    active_days_count: countActiveDays(goal.active_days),
  };
}

function bitmaskToDays(bitmask: number): number[] {
  const days: number[] = [];
  for (let i = 0; i < 7; i++) {
    if (bitmask & (1 << i)) days.push(i);
  }
  return days;
}
```

### 4.4 Progress Endpoints with Active Days Context

When returning progress data (e.g., `GET /api/goals/:goal_id/progress/me`), include active day context so the client can correctly render off-days:

```json
{
  "goal": {
    "id": "goal-uuid",
    "active_days": [0, 1, 2, 3, 4],
    ...
  },
  "progress": [
    { "period_start": "2026-02-17", "value": 1, "is_active_day": true },
    { "period_start": "2026-02-18", "value": 1, "is_active_day": true },
    { "period_start": "2026-02-19", "value": 0, "is_active_day": true },
    // Feb 21 (Sat) and Feb 22 (Sun) are off-days — not included unless logged
  ]
}
```

---

## 5. Impact on Existing Systems

This is the most critical section. Every system that currently assumes "daily = every day" needs updating.

### 5.1 Smart Reminders

**Current behaviour:** Smart reminders fire for daily goals based on the user's logging pattern and escalation tiers.

**With active days:** Reminders must only fire on active days.

**Changes to `reminderEngine.ts`:**

```typescript
// In the reminder decision loop, add early exit:
async evaluateGoalForReminder(
  userId: string, 
  goal: Goal, 
  userTimezone: string
): Promise<ReminderDecision> {
  
  // NEW: Skip off-days entirely
  const userLocalDate = getCurrentDateInTimezone(userTimezone);
  if (!isDayActive(goal.active_days, userLocalDate)) {
    return { action: 'skip', reason: 'off_day' };
  }
  
  // ... existing reminder logic continues unchanged
}
```

**Changes to pattern calculator:**
- When calculating logging patterns for a goal with active days, only include logs from active days in the pattern data
- Day-of-week specific patterns should only be calculated for active days (no point calculating a Saturday pattern for a weekday-only goal)

```typescript
// In PatternCalculator.calculatePattern():
const relevantLogs = logs.filter(log => {
  const logDate = new Date(log.loggedAt);
  // Only include logs from active days in pattern calculation
  if (!isDayActive(goal.active_days, logDate)) return false;
  // Existing day-of-week filter
  if (dayOfWeek !== undefined && this.getDayOfWeek(logDate) !== dayOfWeek) return false;
  return true;
});
```

### 5.2 Nudges

**Current behaviour:** Nudge button appears for members who haven't logged a goal for the current period.

**With active days:** Nudge button is hidden on off-days (goal isn't expected today).

**Changes:**

Server-side (nudge eligibility check):
```typescript
// In POST /api/nudges validation:
// After confirming recipient is a member and goal exists:
const today = getSenderLocalDate(req.body.sender_local_date);
if (!isDayActive(goal.active_days, today)) {
  return res.status(400).json({ 
    error: 'GOAL_OFF_DAY', 
    message: 'This goal is not active today' 
  });
}
```

Client-side (nudge button visibility):
```kotlin
// In GroupDetailFragment member row state machine:
// Add condition: goal is active today
val isActiveDay = goal.activeDays?.let { days ->
    val today = LocalDate.now().dayOfWeek.value % 7 // 0=Sun, 1=Mon, ..., 6=Sat
    today in days
} ?: true // null = every day

// Button hidden if off-day (in addition to existing conditions)
if (!isActiveDay) nudgeButton.visibility = View.GONE
```

### 5.3 Streaks

**Current behaviour:** A streak counts consecutive days where the user logged a daily goal.

**With active days:** A streak counts consecutive **active days** where the user logged. Off-days are skipped — they neither break nor extend the streak.

**Example:** Goal active Mon–Fri. User logs Mon, Tue, Wed, Thu, Fri. Saturday and Sunday pass (off-days). User logs Monday. Their streak is 6 (not reset by the weekend gap).

**Changes to streak calculation:**

```typescript
function calculateStreak(
  progressEntries: ProgressEntry[], 
  activeDays: number | null,
  userLocalDate: Date
): number {
  let streak = 0;
  let checkDate = new Date(userLocalDate);
  
  // Build a set of dates with progress
  const loggedDates = new Set(
    progressEntries
      .filter(e => e.value > 0)
      .map(e => e.period_start) // 'YYYY-MM-DD'
  );
  
  // Walk backward from today
  while (true) {
    if (isDayActive(activeDays, checkDate)) {
      const dateStr = formatDate(checkDate); // 'YYYY-MM-DD'
      if (loggedDates.has(dateStr)) {
        streak++;
      } else {
        // Active day with no log — streak broken
        break;
      }
    }
    // Off-days are simply skipped (don't break or extend streak)
    
    checkDate.setDate(checkDate.getDate() - 1);
    
    // Safety: don't go back more than 1 year
    if (streak > 365) break;
  }
  
  return streak;
}
```

### 5.4 Group Heat

**Current behaviour:** GCR (Group Completion Rate) for a day = completed (member, goal) pairs / total (member, goal) pairs, considering all active daily goals.

**With active days:** Only include goals that are active on the given day in the GCR denominator.

**Changes to `computeAndStoreGcr()`:**

```typescript
async function computeAndStoreGcr(groupId: string, date: string): Promise<number> {
  const members = await getApprovedMembers(groupId, date);
  
  // Get active daily goals — NOW filtered by active days
  const allDailyGoals = await getActiveDailyGoals(groupId, date);
  const dateObj = new Date(date);
  const activeGoalsToday = allDailyGoals.filter(
    goal => isDayActive(goal.active_days, dateObj)
  );
  
  if (members.length === 0 || activeGoalsToday.length === 0) {
    // No active goals today — GCR is undefined, don't update heat
    return NaN; // Signal to skip heat update for this day
  }
  
  const totalPairs = members.length * activeGoalsToday.length;
  
  // Count completed pairs (only for today's active goals)
  const completedPairs = await countCompletedPairs(
    groupId, 
    date, 
    members.map(m => m.user_id),
    activeGoalsToday.map(g => g.id)
  );
  
  const gcr = completedPairs / totalPairs;
  
  await storeGcr(groupId, date, gcr);
  return gcr;
}
```

**Edge case:** If a group has 3 daily goals but all have different active days such that no goals are active on a particular day (e.g., Goal A is Mon/Wed, Goal B is Tue/Thu, Goal C is Fri), that day is effectively an off-day for the group. GCR is undefined for that day, and heat score applies only the decay factor (no delta).

### 5.5 Weekly Recap

**Current behaviour:** Weekly recap shows completion rate as completed/total days.

**With active days:** Show completion against active days only.

**Example:** "Morning run: 5/5 days ✓" (not "5/7 days") when active Mon–Fri.

```typescript
function calculateWeeklyGoalStats(
  goal: Goal, 
  progressEntries: ProgressEntry[],
  weekStart: Date,
  weekEnd: Date
): WeeklyStats {
  const activeDaysInWeek = countActiveDaysInRange(
    goal.active_days, weekStart, weekEnd
  );
  
  const completedDays = progressEntries.filter(e => {
    const entryDate = new Date(e.period_start);
    return e.value > 0 && isDayActive(goal.active_days, entryDate);
  }).length;
  
  return {
    completed: completedDays,
    total: activeDaysInWeek,
    percentage: activeDaysInWeek > 0 
      ? Math.round((completedDays / activeDaysInWeek) * 100) 
      : 100
  };
}
```

### 5.6 Progress Export

**Current behaviour:** Calendar grid shows checkmarks/dashes for every day.

**With active days:** Off-days show a distinct marker (e.g., grey background or "·") to differentiate from missed active days.

```typescript
// In export calendar grid generation:
const isActiveDay = isDayActive(goal.active_days, currentDate);

if (!isActiveDay) {
  // Off-day: show subtle dot or leave blank
  goalValues[col - 1] = '·'; 
  cell.font = { ...cell.font, color: { argb: 'FFD0D0D0' } }; // Very light gray
} else if (hasProgress) {
  goalValues[col - 1] = '✓';
  cell.font = { ...cell.font, color: { argb: 'FF2E7D32' } }; // Green
} else {
  goalValues[col - 1] = '-';
  cell.font = { ...cell.font, color: { argb: 'FFBDBDBD' } }; // Gray
}
```

Monthly summary denominator also adjusts:
```
Morning run: 22/22 days (100%) — only counts weekdays in the month
```

### 5.7 Challenges

**Active days work with challenges** with one constraint: active days are set at challenge creation time (from the template or custom setup) and cannot be changed while the challenge is active, consistent with the existing rule that goals cannot be modified on active challenges.

**Challenge template goals** can include active days:

```sql
-- Add to challenge_template_goals table
ALTER TABLE challenge_template_goals 
  ADD COLUMN active_days INTEGER DEFAULT NULL;

-- Same constraints as goals table
ALTER TABLE challenge_template_goals 
  ADD CONSTRAINT chk_template_goal_active_days CHECK (
    active_days IS NULL OR (active_days >= 1 AND active_days <= 127)
  );

ALTER TABLE challenge_template_goals 
  ADD CONSTRAINT chk_template_goal_active_days_cadence CHECK (
    active_days IS NULL OR cadence = 'daily'
  );
```

**Challenge duration calculation:** When a challenge has goals with active days, the challenge's duration still counts calendar days (e.g., "30-day challenge" = 30 calendar days). The active days only affect which of those 30 days require check-ins. A "30-day weekday workout" challenge runs for 30 calendar days but only expects logging on ~21–22 of those days.

### 5.8 Today Screen

**Current behaviour:** Shows all daily goals across all groups for the current date.

**With active days:** Only show goals that are active today. Off-day goals are hidden from the default view.

```kotlin
// In TodayViewModel, filter goals for display:
val todayGoals = allDailyGoals.filter { goal ->
    val activeDays = goal.activeDays
    if (activeDays == null) true // null = every day
    else {
        val todayIndex = LocalDate.now().dayOfWeek.value % 7 // 0=Sun
        todayIndex in activeDays
    }
}
```

### 5.9 Milestone Cards / Shareable Cards

**Changes:** Streak milestones and completion milestones should calculate against active days. A "30-day streak" on a weekday-only goal means 30 active days (approximately 6 calendar weeks), which is arguably more impressive and should be celebrated.

No changes needed to the card generation itself — it consumes streak values which are already correctly calculated per §5.3.

---

## 6. Android UI Implementation

### 6.1 Active Days Selector Composable

```kotlin
@Composable
fun ActiveDaysSelector(
    selectedDays: Set<Int>, // 0=Sun, 6=Sat
    onDaysChanged: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
    
    Column(modifier = modifier) {
        Text(
            text = "Active Days",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth()
        ) {
            dayLabels.forEachIndexed { index, label ->
                val isSelected = index in selectedDays
                
                DayToggle(
                    label = label,
                    isSelected = isSelected,
                    onClick = {
                        if (isSelected && selectedDays.size <= 1) {
                            // Don't allow deselecting the last day
                            // Show toast via callback or snackbar
                        } else {
                            val newDays = if (isSelected) {
                                selectedDays - index
                            } else {
                                selectedDays + index
                            }
                            onDaysChanged(newDays)
                        }
                    }
                )
            }
        }
        
        // Helper label
        Text(
            text = formatActiveDaysLabel(selectedDays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun DayToggle(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        contentColor = contentColor,
        modifier = Modifier
            .size(40.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatActiveDaysLabel(days: Set<Int>): String {
    if (days.size == 7) return "Every day"
    if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays only · 5 days/week"
    if (days == setOf(0, 6)) return "Weekends only · 2 days/week"
    
    val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val names = days.sorted().map { dayNames[it] }
    return "${names.joinToString(", ")} · ${days.size} days/week"
}
```

### 6.2 Data Model Updates

```kotlin
// Update Goal data class
data class Goal(
    val id: String,
    val groupId: String,
    val title: String,
    val description: String?,
    val cadence: String,
    val metricType: String,
    val targetValue: Double?,
    val unit: String?,
    val activeDays: Set<Int>?,      // NEW: null = every day, Set<0-6>
    val activeDaysLabel: String?,   // NEW: "Weekdays only", etc.
    val activeDaysCount: Int,       // NEW: 7 if null
    val createdByUserId: String?,
    val createdAt: String,
    val archivedAt: String?
) {
    /** Check if this goal is active on the given date */
    fun isActiveOn(date: LocalDate): Boolean {
        if (activeDays == null) return true
        val dayIndex = date.dayOfWeek.value % 7 // ISO Mon=1..Sun=7 → 0=Sun, 1=Mon, ..., 6=Sat
        return dayIndex in activeDays
    }
    
    /** Check if this goal is active today */
    fun isActiveToday(): Boolean = isActiveOn(LocalDate.now())
}
```

### 6.3 Quick Presets

To make common selections faster, add preset chips above the day selector:

```
  Active Days
  [Every day] [Weekdays] [Weekends] [Custom]
  
  (S) (M) (T) (W) (T) (F) (S)
   ○   ●   ●   ●   ●   ●   ○
```

Tapping "Weekdays" selects Mon–Fri. Tapping "Custom" (or manually toggling any day when a preset is active) switches to custom mode. This reduces touch count for the most common patterns.

---

## 7. Challenge Template Updates

Add active days to selected templates where they make natural sense:

| Template | Current | With Active Days |
|----------|---------|-----------------|
| Morning Workout | Daily | Mon–Fri (weekdays) |
| Couch to 5K | Daily | Mon, Wed, Fri (rest days between runs) |
| 10K Steps Daily | Daily | Every day (no change) |
| 30-Day Yoga | Daily | Every day (no change) |
| Plank Challenge | Daily | Mon–Sat (Sunday rest) |
| Read the Bible in a Year | Daily | Every day (no change) |
| 30-Day No Sugar | Daily | Every day (no change) |
| 21-Day Meditation | Daily | Every day (no change) |

Only set active days on templates where rest days are a natural, expected part of the activity. Most challenges should remain "every day" to maintain the urgency and simplicity of the challenge format.

---

## 8. API Schema Updates

### 8.1 OpenAPI Schema Changes

Add `active_days` to the Goal schema:

```yaml
Goal:
  type: object
  properties:
    # ... existing fields ...
    active_days:
      type: array
      items:
        type: integer
        minimum: 0
        maximum: 6
      nullable: true
      description: |
        Days of the week when this goal is active (0=Sun, 6=Sat).
        Null means every day. Only applicable for daily cadence goals.
      example: [1, 2, 3, 4, 5]
    active_days_label:
      type: string
      nullable: true
      description: Human-readable label for active days
      example: "Weekdays only"
    active_days_count:
      type: integer
      minimum: 1
      maximum: 7
      description: Number of active days per week
      example: 5
```

### 8.2 Goal Creation Request Update

```yaml
# In POST /api/groups/{group_id}/goals requestBody:
active_days:
  type: array
  items:
    type: integer
    minimum: 0
    maximum: 6
  nullable: true
  description: |
    Days of the week when this goal is active (0=Sun, 6=Sat).
    Only valid when cadence is 'daily'. Omit or pass null for every day.
    Must contain at least one day.
  example: [1, 2, 3, 4, 5]
```

---

## 9. Testing Strategy

### 9.1 Unit Tests

**Active days utility functions:**
- `isDayActive(null, date)` → always true
- `isDayActive(127, date)` → always true (all bits set)
- `isDayActive(62, monday)` → true (weekday)
- `isDayActive(62, saturday)` → false (weekend)
- `isDayActive(65, saturday)` → true (weekend)
- `isDayActive(65, monday)` → false (weekday)
- `countActiveDays(null)` → 7
- `countActiveDays(62)` → 5
- `countActiveDaysInRange(62, monJan6, sunJan12)` → 5
- `daysToBitmask([1,3,5])` → 42 (Mon, Wed, Fri)
- `bitmaskToDays(42)` → [1, 3, 5]

**Streak calculation:**
- Weekday-only goal, user logs Mon–Fri, skips Sat/Sun, logs Mon → streak = 6
- Weekday-only goal, user logs Mon–Thu, misses Fri, logs Mon → streak = 1
- MWF goal, user logs Mon, skips Tue (off), logs Wed → streak = 2
- All-days goal (null active_days) → existing behaviour unchanged

**GCR calculation:**
- Group with 2 goals: Goal A (weekdays), Goal B (every day). On Saturday: denominator only includes Goal B
- Group where all goals are off on Sunday: GCR = NaN, heat applies decay only

### 9.2 Integration Tests

- Create goal with active_days, verify stored correctly
- Update goal active_days, verify activity entry created
- Attempt to set active_days on weekly goal → 400 error
- Attempt to set active_days with empty array → 400 error
- Attempt to set active_days with value > 6 → 400 error
- Nudge on off-day → 400 GOAL_OFF_DAY
- Smart reminder job skips off-day goals
- Progress export shows distinct off-day markers
- Weekly recap counts against active days only
- Challenge with active-days goals calculates completion correctly

### 9.3 Manual Testing Checklist

- [ ] Create daily goal with "Weekdays only" preset → only Mon–Fri selected
- [ ] Create daily goal with custom days → correct days selected
- [ ] Try to deselect all days → prevented with toast
- [ ] Goal hidden from Today screen on off-day
- [ ] Goal visible in group detail "Rest days" section on off-day
- [ ] Can voluntarily log on off-day via group detail
- [ ] Nudge button hidden on off-day
- [ ] Smart reminder not received on off-day
- [ ] Streak survives off-days without breaking
- [ ] Weekly recap shows correct active-day denominator
- [ ] Group heat doesn't penalise group for off-day goals
- [ ] Edit active days → activity entry appears in feed
- [ ] Progress export shows dot (·) for off-days, dash (-) for missed active days
- [ ] Challenge template with active days creates goals correctly

---

## 10. Migration & Rollout

### 10.1 Database Migration

The migration (§3.1) is backward-compatible:
- New column `active_days` defaults to NULL
- NULL means "every day" — identical to current behaviour
- No existing data needs modification
- No backfill required

### 10.2 Backend Deployment

1. Run migration to add `active_days` column and constraints
2. Deploy backend with active days support in goal CRUD endpoints
3. Deploy updated smart reminder engine with off-day checks
4. Deploy updated nudge validation with off-day checks
5. Deploy updated GCR calculation with active-day filtering
6. Deploy updated weekly recap calculation
7. Deploy updated streak calculation

All changes are backward-compatible: goals without `active_days` (NULL) behave exactly as before.

### 10.3 Android Deployment

1. Update Goal data model to include `active_days` fields
2. Add `ActiveDaysSelector` composable to goal creation/edit screens
3. Update Today screen to filter by active days
4. Update group detail to show "Rest days" section
5. Update nudge button visibility logic
6. Update local streak display
7. Release as part of a regular app update

### 10.4 Rollout Strategy

This is a low-risk, backward-compatible feature. No feature flag or gradual rollout needed:
- Existing goals: unaffected (NULL = every day)
- New goals: users opt into active days during creation
- No data migration or backfill

**Estimated effort: 3–5 days**
- Backend: 1–2 days (schema, CRUD, system integrations)
- Android: 2–3 days (UI components, Today screen filtering, streak/display updates)

---

## 11. Future Enhancement: Grace Days (v2)

### 11.1 Concept

Active days solve the *structured rest* problem ("I exercise on weekdays"). Grace days solve the *life happens* problem ("I usually do this every day, but I was sick / travelling / exhausted").

A grace day is a streak-protection mechanism: when a user misses an active day, instead of the streak breaking immediately, the system preserves it and notifies the user the next morning. The user never explicitly "uses" a grace day — the app applies it automatically and tells them afterward.

**Example notification (morning after a miss):**

```
Title:  "Morning Momentum"
Body:   "Your 14-day streak on 'Morning run' is safe! 🛡️ You used your grace day for this week. Get back on track today."
```

### 11.2 Why Grace Days Are Separate from Active Days

| | Active Days | Grace Days |
|---|---|---|
| **Problem solved** | Structured rest (fixed schedule) | Unplanned misses (life happens) |
| **User awareness** | Declared upfront at goal creation | Applied silently after a miss |
| **Predictable?** | Yes — same days every week | No — triggered by unexpected misses |
| **Affects which days?** | Removes days from the schedule | Forgives missed days on the schedule |
| **Combinable?** | — | Yes — a weekday-only goal can also have 1 grace day |

A weekly numeric goal (e.g., "gym 3x per week") covers the *flexible frequency* case but loses the daily check-in ritual. Grace days preserve the daily cadence feel while adding forgiveness.

### 11.3 Design Decisions

**Allowance:** 1 grace day per week per goal, as an app-level constant (not configurable per goal or per group). Rationale:
- One per week is generous enough to help without undermining accountability
- It's a feature of how Pursue works, not a group-by-group policy
- Avoids UI complexity of per-goal configuration
- Can be revisited if user feedback suggests otherwise

**Reset:** Weekly, aligned with Sunday (week starts on Sunday).

**Who controls it:** Not configurable — it's a platform behaviour. Potential premium gate (see §11.7).

**Applies to:** Daily goals only (with or without active days). Weekly/monthly/yearly goals already have built-in flexibility.

### 11.4 Implementation Approach — Derived, Not Stored

Grace day usage does not require a new table. It can be **derived at calculation time** from existing progress data:

```typescript
/**
 * Determine if a user's streak is preserved by a grace day.
 * Called during streak calculation when a missed active day is encountered.
 * 
 * @returns true if the miss is covered by an unused grace day this week
 */
function isGraceDayCovered(
  userId: string,
  goal: Goal,
  missedDate: Date,
  progressEntries: ProgressEntry[]
): boolean {
  const graceAllowance = 1; // App-level constant
  
  const weekStart = getSunday(missedDate);
  const weekEnd = new Date(missedDate); // Check up to the missed date
  
  // Count active days in this week up to and including the missed date
  const activeDaysThisWeek = getActiveDaysBetween(
    goal.active_days, weekStart, weekEnd
  );
  
  // Count days actually logged this week
  const daysLogged = progressEntries.filter(e => {
    const d = new Date(e.period_start);
    return d >= weekStart 
      && d <= weekEnd 
      && e.value > 0
      && isDayActive(goal.active_days, d);
  }).length;
  
  const daysMissed = activeDaysThisWeek - daysLogged;
  
  // Grace covers the miss if total misses this week don't exceed allowance
  return daysMissed <= graceAllowance;
}
```

**Key insight:** Since progress entries already record what was logged, the absence of an entry on an active day *is* the miss. Counting misses against the weekly allowance is a pure function — no additional state to track or table to maintain.

### 11.5 Updated Streak Calculation

The streak calculator from §5.3 would be extended:

```typescript
function calculateStreak(
  progressEntries: ProgressEntry[], 
  activeDays: number | null,
  userLocalDate: Date,
  enableGraceDays: boolean = true
): { streak: number; graceDaysUsedThisWeek: number } {
  let streak = 0;
  let checkDate = new Date(userLocalDate);
  let graceDaysUsedThisWeek = 0;
  let currentWeekStart = getSunday(checkDate);
  
  const loggedDates = new Set(
    progressEntries
      .filter(e => e.value > 0)
      .map(e => e.period_start)
  );
  
  while (true) {
    if (isDayActive(activeDays, checkDate)) {
      const dateStr = formatDate(checkDate);
      
      if (loggedDates.has(dateStr)) {
        streak++;
      } else if (enableGraceDays && graceDaysUsedThisWeek < 1) {
        // Miss covered by grace day — streak preserved
        streak++; // Count as a "survived" day
        graceDaysUsedThisWeek++;
      } else {
        // Miss with no grace remaining — streak broken
        break;
      }
    }
    
    // Track week transitions for grace day reset
    checkDate.setDate(checkDate.getDate() - 1);
    const newWeekStart = getSunday(checkDate);
    if (newWeekStart.getTime() !== currentWeekStart.getTime()) {
      currentWeekStart = newWeekStart;
      graceDaysUsedThisWeek = 0; // Reset for previous week
    }
    
    if (streak > 365) break;
  }
  
  return { streak, graceDaysUsedThisWeek };
}
```

### 11.6 System Interactions

**Smart reminders:** Fire normally on all active days regardless of grace day availability. Grace days are a safety net, not an opt-out from reminders.

**Nudges:** Fire normally. Teammates don't know (and shouldn't care) whether someone has a grace day available. The social signal is still valuable.

**Group heat GCR:** A missed day still counts as a miss in GCR, even if it's covered by a grace day. Grace only protects the individual's streak, not the group's completion rate. This avoids a perverse incentive where everyone uses their grace day and the group appears to have 100% completion.

**Weekly recap:** Shows grace day usage for transparency: "Morning run: 4/5 days (1 grace day used) · 22-day streak preserved 🛡️"

**Progress export:** Missed-but-graced days could show a distinct marker (e.g., "🛡️" or a yellow cell) to differentiate from ungraced misses.

**Notification:** The morning after a grace day is used, send a single notification per goal where grace was applied. Batch multiple goals into one notification if applicable: "Your streaks on 'Morning run' and 'Meditate' are safe! 🛡️ You've used your grace days for this week."

### 11.7 Premium Gating Consideration

Grace days are a natural premium conversion trigger:
- Users only appreciate them *after* building a streak they care about losing
- The first broken streak is a pain point — "if only I'd had a grace day" drives upgrades
- Free users could see "Your 14-day streak broke. With Premium, grace days protect your streaks." as a post-break upsell

**Option A:** Grace days are premium-only. Strong conversion trigger, but may feel punishing.

**Option B:** All users get 1 grace day/week. Premium gets 2. Softer, but weaker conversion signal.

**Option C (recommended):** All users get grace days. They're a retention feature, not a monetisation feature. A broken streak due to a bad day is the #1 reason people abandon habit trackers. Preventing that abandonment is worth more in long-term retention than the marginal premium conversions. Monetise other things.

### 11.8 When to Build

**Ship after active days, and only if needed.** Signals that grace days are needed:
- Users complain about streaks breaking on "one bad day"
- Retention data shows streak-break events correlate with churn
- Users create weekly numeric goals (e.g., "exercise 6x/week") as a workaround when they clearly want daily tracking with flexibility

**Estimated effort: 2–3 days** (mostly streak logic changes and the grace notification)

---

## 12. Other Future Enhancements

- **Quick-set from goal edit:** "Set to weekdays only" shortcut for existing goals
- **Group-level default active days:** A group-wide default that new goals inherit (saves repeated selection)
- **Analytics:** Track which active day patterns are most popular and have highest completion rates
- **Smart suggestions:** "Most groups with exercise goals use weekday-only scheduling" — suggest active days based on goal title/category
- **Bi-weekly / every-other-day patterns:** More complex scheduling (significant additional complexity, low demand expected)

---

**End of Specification**
