# Pursue Smart Reminders System - Technical Specification

**Version:** 2.0  
**Status:** Implementation Ready  
**Author:** Shannon Thompson  
**Last Updated:** February 17, 2026  
**Reviewed:** v2.0 — Major revision addressing architectural, timezone, scalability, and correctness issues from v1.0

---

## Revision Summary (v1.0 → v2.0)

This revision addresses the following critical issues identified during review:

1. **Cloud Run + node-cron incompatibility** — Cloud Run scales to zero and has no persistent process; node-cron cannot run reliably. Replaced with Google Cloud Scheduler → Cloud Run HTTP endpoints.
2. **Timezone handling gaps** — The candidate query used `CURRENT_DATE` (server UTC), which is wrong for user-local "has logged today" checks. All time-sensitive logic now uses the user's stored timezone.
3. **`day_of_week` NULL in composite primary key** — PostgreSQL allows only one NULL combo per composite PK when a column is nullable; changed to use `day_of_week = -1` sentinel for general patterns.
4. **Circular hour arithmetic bug** — Pattern calculation using simple median/IQR breaks for users who log near midnight (e.g., 23:00–01:00). Added circular statistics handling.
5. **N+1 query in processReminders** — The original loop issued per-user DB queries for patterns, preferences, and history. Replaced with batch queries.
6. **Social context query per reminder** — `buildSocialContext` made multiple DB calls per candidate. Now pre-fetched in bulk.
7. **`percentComplete` comparison bug** — `context.percentComplete === context.totalMembers - 1` compared a percentage to a count. Fixed to use `loggedToday`.
8. **Missing user_timezone in reminder_history insert** — The column existed in schema but was never populated.
9. **Default reminder gap** — Default strategy skipped Tier 2 (supportive), jumping from noon gentle to 8 PM last chance with no middle step.
10. **Effectiveness window too tight** — 2-hour effectiveness window for last_chance reminders sent at 9 PM means the window extends past midnight into the next day, which crosses the period boundary. Adjusted to use "logged same day after reminder."
11. **`this.getGoalId()` phantom method** — Called in `evaluateSmartModeReminder` but doesn't exist on the class. Fixed to thread goalId through properly.
12. **Missing global daily cap** — No limit on total reminders per user per day across all goals. Added configurable cap (default: 6).
13. **No backoff for ineffective reminders** — If a user never responds to reminders, the system sends the same volume forever. Added adaptive suppression.
14. **Premium tier consideration** — Clarified that smart reminders are available to all users (free + premium) as a core engagement feature, not a premium gate.
15. **`evaluateDefaultReminder` uses `await` in non-async method** — Fixed async signature.
16. **Missing index on reminder_history for "today's reminders" lookup** — Added targeted index.
17. **Appendix timeline inconsistency** — The 9:30 PM supportive reminder appeared after the 9:00 PM last chance. Fixed ordering.

---

## 1. Overview

### 1.1 Purpose

The Smart Reminders system provides intelligent, non-intrusive notifications that help users complete their daily goals without feeling nagged. Unlike traditional habit trackers that send static reminders at fixed times, Pursue's smart reminders:

- **Learn individual patterns** — Detect when each user typically logs each goal
- **Respect context** — Different timing for weekdays vs weekends
- **Leverage social pressure** — Include group progress to motivate action
- **Escalate gracefully** — Multi-tier reminder strategy from gentle to urgent
- **Give control** — Users can customize aggressiveness and quiet hours
- **Back off intelligently** — Reduce frequency for users who don't respond

### 1.2 Key Differentiators

| Traditional Reminders | Pursue Smart Reminders |
|----------------------|------------------------|
| Static time (9:00 AM daily) | Learned pattern (after work: 5–7 PM) |
| Same for all goals | Goal-specific patterns |
| No context | Day-of-week aware |
| Annoying nag | Supportive nudge that backs off |
| Solo focus | Social accountability context |

### 1.3 User Experience Goals

**For users on a streak:**
- Gentle, supportive reminders
- Emphasise maintaining momentum
- Celebrate consistency

**For users who miss occasionally:**
- Encouraging tone
- Show group progress to motivate
- Offer flexibility without judgment

**For new goals (< 5 data points):**
- Conservative default reminders (noon + 8 PM)
- Educational tone (habit formation tips)
- Wider reminder window until pattern emerges

**For users who ignore reminders:**
- Adaptive suppression — reduce frequency over time
- Don't punish; re-engage gently after a break
- Respect that some users don't want reminders even if they haven't explicitly disabled them

### 1.4 Feature Tier

Smart reminders are a **core feature available to all users** (free and premium). They drive engagement and retention, which benefits the entire ecosystem. Gating them behind premium would undermine the accountability value proposition.

---

## 2. Architecture

### 2.1 System Components

```
┌──────────────────────────────────────────────────────────┐
│            Google Cloud Scheduler (cron)                  │
│                                                          │
│  • POST /jobs/process-reminders     (*/15 * * * *)       │
│  • POST /jobs/recalculate-patterns  (0 3 * * 0)          │
│  • POST /jobs/update-effectiveness  (0 2 * * *)          │
└──────────────────────┬───────────────────────────────────┘
                       │ HTTPS (with OIDC auth)
                       ▼
┌──────────────────────────────────────────────────────────┐
│              Smart Reminder Service                      │
│  • Pattern Calculator                                    │
│  • Reminder Decision Engine                              │
│  • Social Context Builder                                │
│  • FCM Notification Sender                               │
└──────────────────────┬───────────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
     ┌────────┐  ┌─────────┐  ┌─────────┐
     │Patterns│  │Reminder │  │ Device  │
     │ Cache  │  │ History │  │ Tokens  │
     │ (DB)   │  │  (DB)   │  │  (DB)   │
     └────────┘  └─────────┘  └─────────┘
```

**Why Cloud Scheduler instead of node-cron:**

Pursue's backend runs on Google Cloud Run, which is serverless and scales to zero. An in-process cron library like `node-cron` requires a persistent, always-on process — it simply won't fire if no instance is running. Cloud Scheduler sends HTTP requests to Cloud Run endpoints, which spins up an instance on demand. This is reliable, observable (logs show each invocation), and costs effectively nothing at our scale.

### 2.2 Database Schema

```sql
-- ============================================================
-- User logging patterns (cached for performance)
-- ============================================================
CREATE TABLE user_logging_patterns (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  -- -1 for general pattern, 0 (Sun) - 6 (Sat) for day-specific
  day_of_week INTEGER NOT NULL DEFAULT -1,
  typical_hour_start INTEGER NOT NULL, -- 0-23 (user's local time)
  typical_hour_end INTEGER NOT NULL,   -- 0-23 (user's local time)
  confidence_score DECIMAL(3,2) NOT NULL, -- 0.00 - 1.00
  sample_size INTEGER NOT NULL,
  last_calculated_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id, day_of_week)
);

CREATE INDEX idx_patterns_recalc 
  ON user_logging_patterns(last_calculated_at) 
  WHERE confidence_score > 0.3;

COMMENT ON COLUMN user_logging_patterns.day_of_week 
  IS '-1 for general pattern, 0-6 for day-specific. Using -1 instead of NULL because NULL cannot participate in composite primary keys reliably.';

COMMENT ON COLUMN user_logging_patterns.confidence_score 
  IS 'Based on sample size and consistency. <0.3 = unreliable, use defaults.';

-- ============================================================
-- Reminder history (prevent spam, track effectiveness)
-- ============================================================
CREATE TABLE reminder_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  reminder_tier VARCHAR(20) NOT NULL, -- 'gentle', 'supportive', 'last_chance'
  sent_at TIMESTAMPTZ DEFAULT NOW(),
  sent_at_local_date DATE NOT NULL, -- User's local date when sent (for "today" checks)
  was_effective BOOLEAN, -- Did user log the same day after this reminder?
  social_context JSONB,
  user_timezone VARCHAR(50) NOT NULL,
  PRIMARY KEY (id)
);

CREATE INDEX idx_reminder_history_user_goal_date
  ON reminder_history(user_id, goal_id, sent_at_local_date DESC);

CREATE INDEX idx_reminder_history_user_date
  ON reminder_history(user_id, sent_at_local_date)
  WHERE was_effective IS NULL;

CREATE INDEX idx_reminder_history_effectiveness 
  ON reminder_history(reminder_tier, was_effective) 
  WHERE was_effective IS NOT NULL;

COMMENT ON COLUMN reminder_history.was_effective 
  IS 'TRUE if user logged the goal on the same local date after this reminder was sent. NULL if not yet evaluated.';

COMMENT ON COLUMN reminder_history.sent_at_local_date
  IS 'The user local date when the reminder was sent. Critical for correct "already reminded today" checks across timezones.';

-- ============================================================
-- User reminder preferences (per-goal control)
-- ============================================================
CREATE TABLE user_reminder_preferences (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  enabled BOOLEAN DEFAULT TRUE,
  mode VARCHAR(20) DEFAULT 'smart', -- 'smart', 'fixed', 'disabled'
  fixed_hour INTEGER, -- 0-23 (user local time), used if mode='fixed'
  aggressiveness VARCHAR(20) DEFAULT 'balanced', -- 'gentle', 'balanced', 'persistent'
  quiet_hours_start INTEGER, -- 0-23, NULL if no quiet hours
  quiet_hours_end INTEGER,   -- 0-23, NULL if no quiet hours
  last_modified_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id)
);

CREATE INDEX idx_preferences_enabled 
  ON user_reminder_preferences(user_id) 
  WHERE enabled = TRUE;

COMMENT ON COLUMN user_reminder_preferences.mode 
  IS 'smart = pattern-based, fixed = same time daily, disabled = never remind';

COMMENT ON COLUMN user_reminder_preferences.aggressiveness 
  IS 'gentle = last_chance only, balanced = all tiers, persistent = shorter delays between tiers';
```

### 2.3 Node.js Implementation Structure

```typescript
// src/services/smartReminders/
│
├── index.ts                    // Service orchestrator
├── patternCalculator.ts        // Calculates logging patterns
├── reminderEngine.ts           // Decides when to send reminders
├── socialContextBuilder.ts     // Builds motivational group stats
├── notificationSender.ts       // FCM integration
└── types.ts                    // Shared types

// Job endpoints (invoked by Cloud Scheduler)
// src/routes/jobs.ts
```

---

## 3. Pattern Detection Algorithm

### 3.1 Data Collection

**Minimum Requirements:**
- At least 5 logged entries for a goal
- Logs must span at least 7 days
- If insufficient data, fall back to default reminder strategy

**Historical Window:**
- Last 30 days of progress entries
- Ignore entries older than 30 days (patterns change over time)
- Weight recent logs more heavily (exponential decay, λ = 0.1)

### 3.2 Calculation Logic

```typescript
interface LoggingPattern {
  userId: string;
  goalId: string;
  dayOfWeek: number; // -1 for general pattern, 0-6 for day-specific
  typicalHourStart: number; // 0-23 (user's local time)
  typicalHourEnd: number;   // 0-23 (user's local time)
  confidenceScore: number;  // 0.0 - 1.0
  sampleSize: number;
  lastCalculatedAt: Date;
}

class PatternCalculator {
  
  async calculatePattern(
    userId: string, 
    goalId: string,
    userTimezone: string,
    dayOfWeek?: number
  ): Promise<LoggingPattern | null> {
    
    // Fetch last 30 days of logs
    const logs = await this.getRecentLogs(userId, goalId, 30);
    
    // Convert all log timestamps to user's local time
    const localLogs = logs.map(log => ({
      ...log,
      localHour: this.getLocalHour(log.loggedAt, userTimezone),
      localDayOfWeek: this.getLocalDayOfWeek(log.loggedAt, userTimezone)
    }));
    
    // Filter by day of week if specified
    const relevantLogs = dayOfWeek !== undefined
      ? localLogs.filter(log => log.localDayOfWeek === dayOfWeek)
      : localLogs;
    
    if (relevantLogs.length < 5) {
      return null; // Insufficient data
    }
    
    // Extract hour of day for each log (in user's local timezone)
    const hours = relevantLogs.map(log => log.localHour);
    
    // Use circular statistics for hours (handles midnight wrapping)
    const { median, iqr, stdDev } = this.calculateCircularStats(hours);
    
    // Define typical window (median ± 1 hour, or IQR-based if more spread)
    const windowSize = Math.min(2, Math.ceil(iqr / 2));
    const hourStart = this.wrapHour(Math.round(median) - windowSize);
    const hourEnd = this.wrapHour(Math.round(median) + windowSize);
    
    // Calculate confidence score
    const confidence = this.calculateConfidence(
      relevantLogs.length,
      stdDev,
      iqr
    );
    
    return {
      userId,
      goalId,
      dayOfWeek: dayOfWeek ?? -1,
      typicalHourStart: hourStart,
      typicalHourEnd: hourEnd,
      confidenceScore: confidence,
      sampleSize: relevantLogs.length,
      lastCalculatedAt: new Date()
    };
  }
  
  /**
   * Circular statistics handle the midnight-wrapping problem.
   * 
   * Example: A user who logs at 23:00 and 01:00 should have a median
   * of ~midnight, not ~noon (which naive averaging would produce).
   * 
   * We convert hours to angles (0-24 → 0-2π), compute circular mean,
   * then convert back.
   */
  private calculateCircularStats(hours: number[]): {
    median: number;
    iqr: number;
    stdDev: number;
  } {
    const TWO_PI = 2 * Math.PI;
    
    // Convert hours to radians
    const angles = hours.map(h => (h / 24) * TWO_PI);
    
    // Circular mean
    const sinSum = angles.reduce((sum, a) => sum + Math.sin(a), 0);
    const cosSum = angles.reduce((sum, a) => sum + Math.cos(a), 0);
    const meanAngle = Math.atan2(sinSum / hours.length, cosSum / hours.length);
    const circularMean = ((meanAngle / TWO_PI) * 24 + 24) % 24;
    
    // Circular deviation (R = mean resultant length)
    const R = Math.sqrt(
      Math.pow(sinSum / hours.length, 2) + 
      Math.pow(cosSum / hours.length, 2)
    );
    // Circular std dev (in hours): sqrt(-2 * ln(R)) * (24 / 2π)
    const circularStdDev = R > 0 
      ? Math.sqrt(-2 * Math.log(R)) * (24 / TWO_PI)
      : 12; // Maximum uncertainty
    
    // For IQR: unwrap hours relative to circular mean, then use linear IQR
    const unwrapped = hours.map(h => {
      let diff = h - circularMean;
      if (diff > 12) diff -= 24;
      if (diff < -12) diff += 24;
      return diff;
    });
    const sorted = [...unwrapped].sort((a, b) => a - b);
    const q1 = sorted[Math.floor(sorted.length * 0.25)];
    const q3 = sorted[Math.floor(sorted.length * 0.75)];
    const iqr = q3 - q1;
    
    return {
      median: circularMean,
      iqr: Math.abs(iqr),
      stdDev: circularStdDev
    };
  }
  
  private calculateConfidence(
    sampleSize: number,
    stdDev: number,
    iqr: number
  ): number {
    // Confidence based on:
    // 1. Sample size (more logs = higher confidence), capped at 30
    // 2. Consistency (lower stdDev = higher confidence)
    // 3. Tightness (lower IQR = higher confidence)
    
    const sampleFactor = Math.min(1.0, sampleSize / 30);
    const consistencyFactor = Math.max(0, 1 - (stdDev / 12));
    const tightnessFactor = Math.max(0, 1 - (iqr / 8));
    
    return (
      sampleFactor * 0.4 +
      consistencyFactor * 0.3 +
      tightnessFactor * 0.3
    );
  }
  
  private wrapHour(h: number): number {
    return ((h % 24) + 24) % 24;
  }
  
  private getLocalHour(timestamp: Date, timezone: string): number {
    return parseInt(
      timestamp.toLocaleString('en-US', { 
        timeZone: timezone, 
        hour: 'numeric', 
        hour12: false 
      })
    );
  }
  
  private getLocalDayOfWeek(timestamp: Date, timezone: string): number {
    const dayStr = timestamp.toLocaleString('en-US', { 
      timeZone: timezone, 
      weekday: 'short' 
    });
    const dayMap: Record<string, number> = { 
      Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 
    };
    return dayMap[dayStr];
  }
}
```

### 3.3 Pattern Caching Strategy

**Weekly Recalculation:**

Triggered by Google Cloud Scheduler every Sunday at 3:00 AM UTC.

**Rationale for Sunday 3 AM UTC:**
- Saturday 7 PM PST (US West Coast — late evening)
- Sunday 4 PM NZDT (NZ — afternoon, low usage)
- Low server load from both primary markets

```typescript
// POST /jobs/recalculate-patterns
// Called by Cloud Scheduler: 0 3 * * 0
router.post('/jobs/recalculate-patterns', authenticateScheduler, async (req, res) => {
  const startTime = Date.now();
  logger.info('Starting weekly pattern recalculation...');
  
  try {
    const calculator = new PatternCalculator();
    const result = await calculator.recalculateAllPatterns();
    
    const duration = Date.now() - startTime;
    logger.info('Pattern recalculation completed', {
      duration_ms: duration,
      patterns_updated: result.updated,
      patterns_created: result.created,
      patterns_removed: result.removed
    });
    
    res.json({ status: 'ok', ...result, duration_ms: duration });
  } catch (error) {
    logger.error('Pattern recalculation failed', { error: error.message });
    res.status(500).json({ status: 'error', message: error.message });
  }
});
```

**On-Demand Recalculation:**
- When user logs 5th, 10th, 20th, 30th entry (milestones)
- When user manually requests recalibration in settings
- After 30-day period if pattern hasn't been updated

---

## 4. Reminder Decision Engine

### 4.1 Multi-Tier Reminder Strategy

**Tier 1: Gentle Nudge**
- **When:** User's typical window has passed + 30 min grace period
- **Tone:** "Hey, just a friendly reminder..."
- **Frequency:** Once per goal per day maximum
- **Condition:** confidence_score > 0.5 (reliable pattern)

**Tier 2: Supportive Reminder**
- **When:** 2 hours after Tier 1 (if still not logged)
- **Tone:** "You've got this! 3 of 5 teammates already checked in..."
- **Frequency:** Once per goal per day maximum
- **Condition:** aggressiveness ∈ {'balanced', 'persistent'}

**Tier 3: Last Chance**
- **When:** 9 PM local time (if still not logged)
- **Tone:** "Don't break the streak! Log before midnight..."
- **Frequency:** Once per goal per day maximum
- **Condition:** aggressiveness ∈ {'balanced', 'persistent'}

**Global Daily Cap:**
- Maximum 6 reminders per user per day across ALL goals
- Prioritise goals by streak length (longest streak = highest priority)
- If cap reached, skip lower-priority goals

### 4.2 Adaptive Suppression

If a user has not responded to reminders for a goal in the last 7 days (i.e., 7 consecutive days of reminders sent with `was_effective = FALSE`), reduce reminder frequency:

- **Days 1–7:** Normal schedule (all tiers per aggressiveness)
- **Days 8–14:** Only last_chance tier
- **Days 15+:** Suppress entirely; resume if user logs organically

This prevents the "notification fatigue → disable all notifications" death spiral.

### 4.3 Decision Logic

```typescript
interface ReminderDecision {
  shouldSend: boolean;
  tier: 'gentle' | 'supportive' | 'last_chance' | null;
  reason: string;
}

class ReminderEngine {
  
  // Configuration
  private static readonly MAX_REMINDERS_PER_USER_PER_DAY = 6;
  private static readonly SUPPRESSION_THRESHOLD_DAYS = 7;
  
  async shouldSendReminder(
    userId: string,
    goalId: string,
    userTimezone: string,
    currentTimeUtc: Date,
    // Pre-fetched data (to avoid N+1 queries)
    prefs: UserReminderPreferences | null,
    pattern: LoggingPattern | null,
    todaysRemindersForGoal: ReminderHistory[],
    todaysReminderCountAllGoals: number,
    recentEffectiveness: { consecutiveIneffectiveDays: number }
  ): Promise<ReminderDecision> {
    
    // 1. Check global daily cap
    if (todaysReminderCountAllGoals >= ReminderEngine.MAX_REMINDERS_PER_USER_PER_DAY) {
      return { shouldSend: false, tier: null, reason: 'Global daily cap reached' };
    }
    
    // 2. Check if user has preferences set
    const effectivePrefs = prefs ?? {
      enabled: true,
      mode: 'smart' as const,
      aggressiveness: 'balanced' as const,
      quietHoursStart: null,
      quietHoursEnd: null,
      fixedHour: null
    };
    
    if (!effectivePrefs.enabled || effectivePrefs.mode === 'disabled') {
      return { shouldSend: false, tier: null, reason: 'User disabled reminders' };
    }
    
    // 3. Get current local time for user
    const localTime = this.getLocalTime(currentTimeUtc, userTimezone);
    const currentHour = localTime.hour;
    const currentMinutes = localTime.minute;
    const currentTimeInMinutes = currentHour * 60 + currentMinutes;
    
    // 4. Check quiet hours
    if (this.isQuietHours(currentHour, effectivePrefs)) {
      return { shouldSend: false, tier: null, reason: 'Quiet hours' };
    }
    
    // 5. Check adaptive suppression
    if (recentEffectiveness.consecutiveIneffectiveDays >= 15) {
      return { shouldSend: false, tier: null, reason: 'Suppressed (15+ days ineffective)' };
    }
    
    const suppressToLastChanceOnly = 
      recentEffectiveness.consecutiveIneffectiveDays >= ReminderEngine.SUPPRESSION_THRESHOLD_DAYS;
    
    // 6. Determine tier and timing based on mode
    if (effectivePrefs.mode === 'fixed') {
      return this.evaluateFixedModeReminder(
        effectivePrefs, currentHour, todaysRemindersForGoal
      );
    }
    
    // Smart mode
    return this.evaluateSmartModeReminder(
      pattern,
      currentTimeInMinutes,
      todaysRemindersForGoal,
      effectivePrefs,
      suppressToLastChanceOnly
    );
  }
  
  private evaluateSmartModeReminder(
    pattern: LoggingPattern | null,
    currentTimeInMinutes: number,
    todaysReminders: ReminderHistory[],
    prefs: UserReminderPreferences,
    suppressToLastChanceOnly: boolean
  ): ReminderDecision {
    
    // No pattern or low confidence — use default strategy
    if (!pattern || pattern.confidenceScore < 0.3) {
      return this.evaluateDefaultReminder(
        currentTimeInMinutes, todaysReminders, prefs, suppressToLastChanceOnly
      );
    }
    
    // Calculate when reminders should fire (all in minutes from midnight)
    const gentleTime = pattern.typicalHourEnd * 60 + 30; // End of window + 30 min
    const supportiveTime = gentleTime + 120; // 2 hours after gentle
    const lastChanceTime = 21 * 60; // 9 PM local time
    
    // For 'persistent' aggressiveness, compress the schedule
    const effectiveSupportiveTime = prefs.aggressiveness === 'persistent'
      ? gentleTime + 60  // 1 hour after gentle instead of 2
      : supportiveTime;
    
    // Tier 1: Gentle
    if (
      !suppressToLastChanceOnly &&
      currentTimeInMinutes >= gentleTime &&
      currentTimeInMinutes < effectiveSupportiveTime &&
      !this.hasSentTier(todaysReminders, 'gentle') &&
      prefs.aggressiveness !== 'gentle' // 'gentle' mode skips to last_chance only
    ) {
      return {
        shouldSend: true,
        tier: 'gentle',
        reason: 'Past typical logging window'
      };
    }
    
    // Tier 2: Supportive
    if (
      !suppressToLastChanceOnly &&
      currentTimeInMinutes >= effectiveSupportiveTime &&
      currentTimeInMinutes < lastChanceTime &&
      !this.hasSentTier(todaysReminders, 'supportive') &&
      prefs.aggressiveness !== 'gentle'
    ) {
      return {
        shouldSend: true,
        tier: 'supportive',
        reason: 'Still not logged 2+ hours after window'
      };
    }
    
    // Tier 3: Last Chance
    if (
      currentTimeInMinutes >= lastChanceTime &&
      currentTimeInMinutes < 23 * 60 && // Don't send after 11 PM
      !this.hasSentTier(todaysReminders, 'last_chance')
    ) {
      return {
        shouldSend: true,
        tier: 'last_chance',
        reason: 'Last chance before day ends'
      };
    }
    
    return { shouldSend: false, tier: null, reason: 'Not time yet' };
  }
  
  private evaluateDefaultReminder(
    currentTimeInMinutes: number,
    todaysReminders: ReminderHistory[],
    prefs: UserReminderPreferences,
    suppressToLastChanceOnly: boolean
  ): ReminderDecision {
    
    // Default strategy when no pattern exists:
    // Gentle at noon, Supportive at 5 PM, Last Chance at 9 PM
    
    const NOON = 12 * 60;
    const FIVE_PM = 17 * 60;
    const NINE_PM = 21 * 60;
    const ELEVEN_PM = 23 * 60;
    
    // Tier 1: Gentle at noon
    if (
      !suppressToLastChanceOnly &&
      currentTimeInMinutes >= NOON &&
      currentTimeInMinutes < FIVE_PM &&
      !this.hasSentTier(todaysReminders, 'gentle') &&
      prefs.aggressiveness !== 'gentle'
    ) {
      return {
        shouldSend: true,
        tier: 'gentle',
        reason: 'Default midday reminder (no pattern)'
      };
    }
    
    // Tier 2: Supportive at 5 PM
    if (
      !suppressToLastChanceOnly &&
      currentTimeInMinutes >= FIVE_PM &&
      currentTimeInMinutes < NINE_PM &&
      !this.hasSentTier(todaysReminders, 'supportive') &&
      prefs.aggressiveness !== 'gentle'
    ) {
      return {
        shouldSend: true,
        tier: 'supportive',
        reason: 'Default afternoon reminder (no pattern)'
      };
    }
    
    // Tier 3: Last Chance at 9 PM
    if (
      currentTimeInMinutes >= NINE_PM &&
      currentTimeInMinutes < ELEVEN_PM &&
      !this.hasSentTier(todaysReminders, 'last_chance')
    ) {
      return {
        shouldSend: true,
        tier: 'last_chance',
        reason: 'Default evening reminder (no pattern)'
      };
    }
    
    return { shouldSend: false, tier: null, reason: 'Not default reminder time' };
  }
  
  private evaluateFixedModeReminder(
    prefs: UserReminderPreferences,
    currentHour: number,
    todaysReminders: ReminderHistory[]
  ): ReminderDecision {
    
    if (
      prefs.fixedHour !== null &&
      currentHour === prefs.fixedHour &&
      todaysReminders.length === 0
    ) {
      return {
        shouldSend: true,
        tier: 'gentle',
        reason: 'Fixed time reminder'
      };
    }
    
    return { shouldSend: false, tier: null, reason: 'Not fixed reminder time' };
  }
  
  private hasSentTier(reminders: ReminderHistory[], tier: string): boolean {
    return reminders.some(r => r.reminderTier === tier);
  }
  
  private isQuietHours(
    currentHour: number,
    prefs: { quietHoursStart: number | null; quietHoursEnd: number | null }
  ): boolean {
    if (prefs.quietHoursStart === null || prefs.quietHoursEnd === null) {
      return false;
    }
    
    // Handle overnight quiet hours (e.g., 22:00 – 07:00)
    if (prefs.quietHoursStart > prefs.quietHoursEnd) {
      return currentHour >= prefs.quietHoursStart || currentHour < prefs.quietHoursEnd;
    }
    
    return currentHour >= prefs.quietHoursStart && currentHour < prefs.quietHoursEnd;
  }
  
  private getLocalTime(utcDate: Date, timezone: string): { hour: number; minute: number } {
    const parts = utcDate.toLocaleString('en-US', {
      timeZone: timezone,
      hour: 'numeric',
      minute: 'numeric',
      hour12: false
    }).split(':');
    return {
      hour: parseInt(parts[0]),
      minute: parseInt(parts[1])
    };
  }
}
```

---

## 5. Social Accountability Integration

### 5.1 Group Progress Context

```typescript
interface SocialContext {
  groupName: string;
  totalMembers: number;
  loggedToday: number;
  percentComplete: number;
  topPerformer?: {
    name: string;
    currentStreak: number;
  };
  userStreak: number;
  groupStreak?: number; // Days everyone has logged
}

class SocialContextBuilder {
  
  /**
   * Bulk-build social context for multiple goals at once.
   * This avoids the N+1 problem of building context per-reminder.
   */
  async buildBulkSocialContext(
    goalIds: string[],
    userStreakMap: Map<string, Map<string, number>> // goalId -> userId -> streak
  ): Promise<Map<string, SocialContext>> {
    
    if (goalIds.length === 0) return new Map();
    
    // Single query: get today's progress counts per goal
    const progressCounts = await db.query(`
      SELECT 
        g.id as goal_id,
        g.group_id,
        gr.name as group_name,
        COUNT(DISTINCT gm.user_id) as total_members,
        COUNT(DISTINCT pe.user_id) as logged_today
      FROM goals g
      INNER JOIN groups gr ON g.group_id = gr.id
      INNER JOIN group_memberships gm ON g.group_id = gm.group_id
      LEFT JOIN progress_entries pe 
        ON pe.goal_id = g.id 
        AND pe.user_id = gm.user_id
        AND pe.period_start = CURRENT_DATE
      WHERE g.id = ANY($1)
      GROUP BY g.id, g.group_id, gr.name
    `, [goalIds]);
    
    const contextMap = new Map<string, SocialContext>();
    
    for (const row of progressCounts.rows) {
      const goalStreaks = userStreakMap.get(row.goal_id);
      let topPerformer: { name: string; currentStreak: number } | undefined;
      
      if (goalStreaks) {
        // Find top performer from pre-computed streaks
        let maxStreak = 0;
        let topUserId = '';
        for (const [userId, streak] of goalStreaks) {
          if (streak > maxStreak) {
            maxStreak = streak;
            topUserId = userId;
          }
        }
        if (topUserId && maxStreak > 0) {
          const user = await this.getUser(topUserId); // Consider caching
          topPerformer = { name: user.displayName, currentStreak: maxStreak };
        }
      }
      
      contextMap.set(row.goal_id, {
        groupName: row.group_name,
        totalMembers: parseInt(row.total_members),
        loggedToday: parseInt(row.logged_today),
        percentComplete: Math.round(
          (parseInt(row.logged_today) / parseInt(row.total_members)) * 100
        ),
        topPerformer,
        userStreak: 0, // Filled per-user at send time
        groupStreak: 0  // TODO: compute if needed (expensive)
      });
    }
    
    return contextMap;
  }
}
```

### 5.2 Notification Message Templates

```typescript
interface NotificationTemplate {
  title: string;
  body: string;
  data: Record<string, string>;
}

class NotificationMessageBuilder {
  
  buildNotification(
    tier: 'gentle' | 'supportive' | 'last_chance',
    goal: Goal,
    group: Group,
    socialContext: SocialContext
  ): NotificationTemplate {
    
    switch (tier) {
      case 'gentle':
        return this.buildGentleNotification(goal, group, socialContext);
      case 'supportive':
        return this.buildSupportiveNotification(goal, group, socialContext);
      case 'last_chance':
        return this.buildLastChanceNotification(goal, group, socialContext);
    }
  }
  
  private buildGentleNotification(
    goal: Goal,
    group: Group,
    context: SocialContext
  ): NotificationTemplate {
    
    const messages = [
      `Time to log: ${goal.title}`,
      `Don't forget to log: ${goal.title}`,
      `Ready to log ${goal.title}?`,
      `${goal.title} — time to check in`
    ];
    
    return {
      title: group.name,
      body: this.randomChoice(messages),
      data: {
        type: 'smart_reminder',
        tier: 'gentle',
        goal_id: goal.id,
        group_id: group.id
      }
    };
  }
  
  private buildSupportiveNotification(
    goal: Goal,
    group: Group,
    context: SocialContext
  ): NotificationTemplate {
    
    let body: string;
    
    if (context.loggedToday > context.totalMembers / 2) {
      body = `${context.loggedToday} of ${context.totalMembers} teammates completed their goal. Join them!`;
    } else if (context.userStreak > 0) {
      body = `Keep your ${context.userStreak}-day streak alive! Log ${goal.title}.`;
    } else {
      body = `You've got this! Don't forget to log: ${goal.title}`;
    }
    
    return {
      title: group.name,
      body,
      data: {
        type: 'smart_reminder',
        tier: 'supportive',
        goal_id: goal.id,
        group_id: group.id,
        social_stats: JSON.stringify({
          logged: context.loggedToday,
          total: context.totalMembers
        })
      }
    };
  }
  
  private buildLastChanceNotification(
    goal: Goal,
    group: Group,
    context: SocialContext
  ): NotificationTemplate {
    
    let body: string;
    
    if (context.userStreak >= 7) {
      body = `Don't break your ${context.userStreak}-day streak! Log '${goal.title}' before midnight.`;
    } else if (context.groupStreak && context.groupStreak > 0) {
      body = `Keep the group's ${context.groupStreak}-day streak alive! Log '${goal.title}' now.`;
    } else if (context.loggedToday === context.totalMembers - 1) {
      // User is the last one — compare counts, not percentages
      body = `You're the last one! Everyone else logged their goal. Don't leave them hanging!`;
    } else {
      body = `Last chance to log '${goal.title}' before midnight!`;
    }
    
    return {
      title: group.name,
      body,
      data: {
        type: 'smart_reminder',
        tier: 'last_chance',
        goal_id: goal.id,
        group_id: group.id,
        streak: context.userStreak.toString(),
        group_streak: (context.groupStreak || 0).toString()
      }
    };
  }
  
  private randomChoice<T>(array: T[]): T {
    return array[Math.floor(Math.random() * array.length)];
  }
}
```

---

## 6. Scheduled Job Implementation

### 6.1 Cloud Scheduler Setup

```bash
# Job 1: Process reminders every 15 minutes
gcloud scheduler jobs create http process-reminders \
  --location=us-central1 \
  --schedule="*/15 * * * *" \
  --uri="https://pursue-backend-HASH.run.app/jobs/process-reminders" \
  --http-method=POST \
  --oidc-service-account-email=scheduler@PROJECT_ID.iam.gserviceaccount.com \
  --oidc-token-audience="https://pursue-backend-HASH.run.app" \
  --time-zone="UTC"

# Job 2: Recalculate patterns weekly (Sunday 3 AM UTC)
gcloud scheduler jobs create http recalculate-patterns \
  --location=us-central1 \
  --schedule="0 3 * * 0" \
  --uri="https://pursue-backend-HASH.run.app/jobs/recalculate-patterns" \
  --http-method=POST \
  --oidc-service-account-email=scheduler@PROJECT_ID.iam.gserviceaccount.com \
  --oidc-token-audience="https://pursue-backend-HASH.run.app" \
  --time-zone="UTC"

# Job 3: Update effectiveness metrics daily (2 AM UTC)
gcloud scheduler jobs create http update-effectiveness \
  --location=us-central1 \
  --schedule="0 2 * * *" \
  --uri="https://pursue-backend-HASH.run.app/jobs/update-effectiveness" \
  --http-method=POST \
  --oidc-service-account-email=scheduler@PROJECT_ID.iam.gserviceaccount.com \
  --oidc-token-audience="https://pursue-backend-HASH.run.app" \
  --time-zone="UTC"
```

**Authentication:** Cloud Scheduler uses OIDC tokens. The job endpoints verify the token:

```typescript
import { OAuth2Client } from 'google-auth-library';

const authClient = new OAuth2Client();

async function authenticateScheduler(req: Request, res: Response, next: NextFunction) {
  const authHeader = req.headers.authorization;
  if (!authHeader?.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Missing authorization' });
  }
  
  try {
    const token = authHeader.split(' ')[1];
    const ticket = await authClient.verifyIdToken({
      idToken: token,
      audience: process.env.CLOUD_RUN_URL
    });
    
    const payload = ticket.getPayload();
    if (payload?.email !== process.env.SCHEDULER_SERVICE_ACCOUNT) {
      return res.status(403).json({ error: 'Unauthorized service account' });
    }
    
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Invalid token' });
  }
}
```

### 6.2 Main Reminder Processing Logic

```typescript
// POST /jobs/process-reminders
// Called by Cloud Scheduler every 15 minutes

router.post('/jobs/process-reminders', authenticateScheduler, async (req, res) => {
  const startTime = Date.now();
  logger.info('Starting reminder processing job...');
  
  try {
    const service = new SmartReminderService();
    const result = await service.processReminders();
    
    const duration = Date.now() - startTime;
    logger.info('Reminder job completed', {
      duration_ms: duration,
      reminders_sent: result.sent,
      skipped: result.skipped,
      errors: result.errors
    });
    
    res.json({ status: 'ok', ...result, duration_ms: duration });
  } catch (error) {
    logger.error('Reminder job failed', { error: error.message, stack: error.stack });
    res.status(500).json({ status: 'error', message: error.message });
  }
});
```

```typescript
// src/services/smartReminders/index.ts

interface ProcessResult {
  sent: number;
  skipped: number;
  errors: number;
}

export class SmartReminderService {
  
  private engine: ReminderEngine;
  private sender: NotificationSender;
  private contextBuilder: SocialContextBuilder;
  
  constructor() {
    this.engine = new ReminderEngine();
    this.sender = new NotificationSender();
    this.contextBuilder = new SocialContextBuilder();
  }
  
  async processReminders(): Promise<ProcessResult> {
    const result: ProcessResult = { sent: 0, skipped: 0, errors: 0 };
    
    // ── Step 1: Get all candidate user-goal pairs (batch query) ──
    const candidates = await this.getCandidateGoals();
    logger.info(`Processing ${candidates.length} candidate goals`);
    
    if (candidates.length === 0) {
      return result;
    }
    
    // ── Step 2: Batch-fetch all needed data ──
    const userIds = [...new Set(candidates.map(c => c.userId))];
    const goalIds = [...new Set(candidates.map(c => c.goalId))];
    
    // Fetch all patterns in one query
    const patterns = await this.batchGetPatterns(userIds, goalIds);
    
    // Fetch all preferences in one query
    const preferences = await this.batchGetPreferences(userIds, goalIds);
    
    // Fetch today's reminder history in one query (per user-goal, using user's local date)
    const todaysReminders = await this.batchGetTodaysReminders(userIds, goalIds);
    
    // Fetch today's reminder count per user (for global cap)
    const reminderCounts = await this.batchGetReminderCounts(userIds);
    
    // Fetch recent effectiveness data for adaptive suppression
    const effectivenessData = await this.batchGetRecentEffectiveness(userIds, goalIds);
    
    // Pre-fetch social context for all goals
    const socialContexts = await this.contextBuilder.buildBulkSocialContext(
      goalIds, new Map() // Streak map computed separately if needed
    );
    
    // ── Step 3: Evaluate and send ──
    for (const candidate of candidates) {
      try {
        const key = `${candidate.userId}:${candidate.goalId}`;
        
        const decision = await this.engine.shouldSendReminder(
          candidate.userId,
          candidate.goalId,
          candidate.userTimezone,
          new Date(),
          preferences.get(key) ?? null,
          patterns.get(key) ?? null,
          todaysReminders.get(key) ?? [],
          reminderCounts.get(candidate.userId) ?? 0,
          effectivenessData.get(key) ?? { consecutiveIneffectiveDays: 0 }
        );
        
        if (!decision.shouldSend) {
          result.skipped++;
          continue;
        }
        
        // Get or build social context
        const context = socialContexts.get(candidate.goalId);
        if (!context) {
          result.skipped++;
          continue;
        }
        
        await this.sendReminder(
          candidate.userId,
          candidate.goalId,
          candidate.userTimezone,
          decision.tier!,
          context
        );
        
        // Update in-memory count for global cap enforcement within this batch
        reminderCounts.set(
          candidate.userId,
          (reminderCounts.get(candidate.userId) ?? 0) + 1
        );
        
        result.sent++;
        
      } catch (error) {
        logger.error('Failed to process reminder', {
          user_id: candidate.userId,
          goal_id: candidate.goalId,
          error: error.message
        });
        result.errors++;
      }
    }
    
    return result;
  }
  
  /**
   * Find all candidate user-goal pairs that might need reminders.
   * 
   * Critical: The "has not logged today" check must use the user's local date,
   * not server UTC date. We join on users.timezone to compute this.
   */
  private async getCandidateGoals(): Promise<Array<{
    userId: string;
    goalId: string;
    groupId: string;
    userTimezone: string;
  }>> {
    
    const query = `
      SELECT DISTINCT
        gm.user_id,
        g.id as goal_id,
        g.group_id,
        u.timezone as user_timezone
      FROM goals g
      INNER JOIN group_memberships gm ON g.group_id = gm.group_id
      INNER JOIN users u ON gm.user_id = u.id
      LEFT JOIN user_reminder_preferences urp 
        ON gm.user_id = urp.user_id AND g.id = urp.goal_id
      WHERE g.cadence = 'daily'
        AND g.deleted_at IS NULL
        AND u.deleted_at IS NULL
        AND COALESCE(urp.enabled, TRUE) = TRUE
        AND COALESCE(urp.mode, 'smart') != 'disabled'
        AND NOT EXISTS (
          SELECT 1 FROM progress_entries pe
          WHERE pe.goal_id = g.id
            AND pe.user_id = gm.user_id
            AND pe.period_start = (
              CURRENT_TIMESTAMP AT TIME ZONE COALESCE(u.timezone, 'UTC')
            )::date
        )
    `;
    
    const result = await db.query(query);
    return result.rows.map(row => ({
      userId: row.user_id,
      goalId: row.goal_id,
      groupId: row.group_id,
      userTimezone: row.user_timezone || 'UTC'
    }));
  }
  
  private async sendReminder(
    userId: string,
    goalId: string,
    userTimezone: string,
    tier: 'gentle' | 'supportive' | 'last_chance',
    socialContext: SocialContext
  ): Promise<void> {
    
    const goal = await this.getGoal(goalId);
    const group = await this.getGroup(goal.groupId);
    
    const messageBuilder = new NotificationMessageBuilder();
    const notification = messageBuilder.buildNotification(tier, goal, group, socialContext);
    
    await this.sender.sendToUser(userId, notification);
    
    // Calculate user's local date for the history record
    const now = new Date();
    const localDateStr = now.toLocaleDateString('en-CA', { timeZone: userTimezone }); // YYYY-MM-DD
    
    await this.recordReminderHistory(userId, goalId, tier, socialContext, userTimezone, localDateStr);
    
    logger.info('Reminder sent', {
      user_id: userId,
      goal_id: goalId,
      tier,
      user_timezone: userTimezone,
      local_date: localDateStr,
      group_progress: `${socialContext.loggedToday}/${socialContext.totalMembers}`
    });
  }
  
  private async recordReminderHistory(
    userId: string,
    goalId: string,
    tier: string,
    socialContext: SocialContext,
    userTimezone: string,
    localDate: string
  ): Promise<void> {
    
    await db.query(`
      INSERT INTO reminder_history (
        user_id, goal_id, reminder_tier, social_context, 
        user_timezone, sent_at_local_date
      ) VALUES ($1, $2, $3, $4, $5, $6::date)
    `, [
      userId, goalId, tier, JSON.stringify(socialContext),
      userTimezone, localDate
    ]);
  }
  
  /**
   * Update effectiveness for recent reminders.
   * 
   * A reminder is "effective" if the user logged the goal on the same local date
   * after the reminder was sent. This avoids the problem of a 2-hour window
   * crossing midnight for late-night last_chance reminders.
   */
  async updateEffectivenessMetrics(): Promise<{ updated: number }> {
    
    const query = `
      UPDATE reminder_history rh
      SET was_effective = EXISTS (
        SELECT 1 FROM progress_entries pe
        WHERE pe.user_id = rh.user_id
          AND pe.goal_id = rh.goal_id
          AND pe.period_start = rh.sent_at_local_date
          AND pe.created_at > rh.sent_at
      )
      WHERE rh.sent_at >= NOW() - INTERVAL '2 days'
        AND rh.was_effective IS NULL
    `;
    
    const result = await db.query(query);
    logger.info(`Updated effectiveness for ${result.rowCount} reminders`);
    return { updated: result.rowCount };
  }
}
```

---

## 7. User Control & Preferences

### 7.1 API Endpoints

```typescript
// ============================================
// GET /api/users/me/reminder-preferences
// ============================================
router.get('/users/me/reminder-preferences', authenticate, async (req, res) => {
  const userId = req.user!.userId;
  
  const preferences = await db.query(`
    SELECT
      urp.goal_id,
      g.title as goal_title,
      urp.enabled,
      urp.mode,
      urp.fixed_hour,
      urp.aggressiveness,
      urp.quiet_hours_start,
      urp.quiet_hours_end,
      urp.last_modified_at,
      ulp.typical_hour_start,
      ulp.typical_hour_end,
      ulp.confidence_score,
      ulp.sample_size
    FROM goals g
    INNER JOIN group_memberships gm ON g.group_id = gm.group_id AND gm.user_id = $1
    LEFT JOIN user_reminder_preferences urp ON urp.goal_id = g.id AND urp.user_id = $1
    LEFT JOIN user_logging_patterns ulp 
      ON ulp.goal_id = g.id AND ulp.user_id = $1 AND ulp.day_of_week = -1
    WHERE g.deleted_at IS NULL
      AND g.cadence = 'daily'
    ORDER BY g.created_at DESC
  `, [userId]);
  
  res.json({ preferences: preferences.rows });
});

// ============================================
// PUT /api/goals/:goalId/reminder-preferences
// ============================================
router.put('/goals/:goalId/reminder-preferences', authenticate, async (req, res) => {
  const userId = req.user!.userId;
  const { goalId } = req.params;
  
  const schema = z.object({
    enabled: z.boolean().optional(),
    mode: z.enum(['smart', 'fixed', 'disabled']).optional(),
    fixed_hour: z.number().int().min(0).max(23).nullable().optional(),
    aggressiveness: z.enum(['gentle', 'balanced', 'persistent']).optional(),
    quiet_hours_start: z.number().int().min(0).max(23).nullable().optional(),
    quiet_hours_end: z.number().int().min(0).max(23).nullable().optional()
  });
  
  const validated = schema.parse(req.body);
  
  const isMember = await verifyGoalMembership(userId, goalId);
  if (!isMember) {
    return res.status(403).json({ error: 'Not a member of this goal\'s group' });
  }
  
  await db.query(`
    INSERT INTO user_reminder_preferences (
      user_id, goal_id, enabled, mode, fixed_hour, 
      aggressiveness, quiet_hours_start, quiet_hours_end
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
    ON CONFLICT (user_id, goal_id) DO UPDATE SET
      enabled = COALESCE($3, user_reminder_preferences.enabled),
      mode = COALESCE($4, user_reminder_preferences.mode),
      fixed_hour = COALESCE($5, user_reminder_preferences.fixed_hour),
      aggressiveness = COALESCE($6, user_reminder_preferences.aggressiveness),
      quiet_hours_start = COALESCE($7, user_reminder_preferences.quiet_hours_start),
      quiet_hours_end = COALESCE($8, user_reminder_preferences.quiet_hours_end),
      last_modified_at = NOW()
  `, [
    userId, goalId,
    validated.enabled,
    validated.mode,
    validated.fixed_hour,
    validated.aggressiveness,
    validated.quiet_hours_start,
    validated.quiet_hours_end
  ]);
  
  res.json({ message: 'Preferences updated' });
});

// ============================================
// POST /api/goals/:goalId/recalculate-pattern
// ============================================
router.post('/goals/:goalId/recalculate-pattern', authenticate, async (req, res) => {
  const userId = req.user!.userId;
  const { goalId } = req.params;
  
  const isMember = await verifyGoalMembership(userId, goalId);
  if (!isMember) {
    return res.status(403).json({ error: 'Not a member of this goal\'s group' });
  }
  
  const user = await getUser(userId);
  const calculator = new PatternCalculator();
  const pattern = await calculator.calculateAndStorePattern(
    userId, goalId, user.timezone
  );
  
  if (!pattern) {
    return res.json({
      message: 'Insufficient data to calculate pattern (need at least 5 logs)',
      pattern: null
    });
  }
  
  res.json({
    message: 'Pattern recalculated',
    pattern: {
      typical_hour_start: pattern.typicalHourStart,
      typical_hour_end: pattern.typicalHourEnd,
      confidence_score: pattern.confidenceScore,
      sample_size: pattern.sampleSize
    }
  });
});
```

### 7.2 Mobile UI Mockup

```
┌─────────────────────────────────────────┐
│  ← Reminder Settings                    │
├─────────────────────────────────────────┤
│                                         │
│  Goal: 30 min run                       │
│  Group: Morning Runners                 │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ ✓ Enable reminders          [⊙] │    │
│  └─────────────────────────────────┘    │
│                                         │
│  Reminder Mode                          │
│  ● Smart (learns your pattern)          │
│  ○ Fixed time                           │
│  ○ Off                                  │
│                                         │
│  [Smart Mode Details]                   │
│  Your pattern: 5:00 PM – 7:00 PM       │
│  Based on 23 logs (High confidence)     │
│  [Recalculate Pattern]                  │
│                                         │
│  Reminder Level                         │
│  ○ Gentle (last chance only)            │
│  ● Balanced (all reminders)             │
│  ○ Persistent (shorter delays)          │
│                                         │
│  Quiet Hours                            │
│  From: [22:00] To: [07:00]              │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │        Save Changes              │    │
│  └─────────────────────────────────┘    │
│                                         │
└─────────────────────────────────────────┘
```

---

## 8. Analytics & Monitoring

### 8.1 Key Metrics to Track

```sql
-- Reminder effectiveness by tier (last 30 days)
SELECT
  reminder_tier,
  COUNT(*) as total_sent,
  SUM(CASE WHEN was_effective THEN 1 ELSE 0 END) as effective,
  ROUND(
    AVG(CASE WHEN was_effective THEN 1.0 ELSE 0.0 END) * 100, 1
  ) as effectiveness_pct
FROM reminder_history
WHERE sent_at > NOW() - INTERVAL '30 days'
  AND was_effective IS NOT NULL
GROUP BY reminder_tier;

-- Average pattern confidence by sample size
SELECT
  CASE
    WHEN sample_size < 10 THEN '< 10 logs'
    WHEN sample_size < 20 THEN '10-19 logs'
    WHEN sample_size < 30 THEN '20-29 logs'
    ELSE '30+ logs'
  END as sample_bucket,
  COUNT(*) as patterns,
  ROUND(AVG(confidence_score), 2) as avg_confidence
FROM user_logging_patterns
WHERE day_of_week = -1  -- General patterns only
GROUP BY sample_bucket
ORDER BY MIN(sample_size);

-- Reminder volume per day (last 7 days)
SELECT
  sent_at_local_date,
  COUNT(*) as total_sent,
  COUNT(DISTINCT user_id) as unique_users
FROM reminder_history
WHERE sent_at > NOW() - INTERVAL '7 days'
GROUP BY sent_at_local_date
ORDER BY sent_at_local_date DESC;

-- Adaptive suppression stats
SELECT
  COUNT(DISTINCT user_id || ':' || goal_id) as suppressed_pairs,
  COUNT(DISTINCT user_id) as suppressed_users
FROM (
  SELECT user_id, goal_id
  FROM reminder_history
  WHERE sent_at > NOW() - INTERVAL '15 days'
    AND was_effective = FALSE
  GROUP BY user_id, goal_id
  HAVING COUNT(*) >= 15
) suppressed;
```

### 8.2 Logging & Alerting

```typescript
// Alert if reminder job takes too long
const MAX_JOB_DURATION_MS = 5 * 60 * 1000; // 5 minutes
if (duration > MAX_JOB_DURATION_MS) {
  logger.error('Reminder job exceeded time limit', {
    duration_ms: duration,
    limit_ms: MAX_JOB_DURATION_MS,
    candidates_processed: candidates.length
  });
}

// Alert if effectiveness drops below threshold
const LOW_EFFECTIVENESS_THRESHOLD = 0.15;
if (effectivenessRate < LOW_EFFECTIVENESS_THRESHOLD) {
  logger.warn('Low reminder effectiveness detected', {
    tier: reminderTier,
    effectiveness_rate: effectivenessRate,
    threshold: LOW_EFFECTIVENESS_THRESHOLD,
    sample_size: sampleSize
  });
}

// Monitor FCM delivery failures
const FCM_FAILURE_THRESHOLD = 0.10; // 10% failure rate
if (fcmFailureRate > FCM_FAILURE_THRESHOLD) {
  logger.error('High FCM failure rate', {
    failure_rate: fcmFailureRate,
    total_attempted: totalAttempted,
    total_failed: totalFailed
  });
}
```

### 8.3 Admin Metrics Endpoint

```typescript
router.get('/admin/reminder-metrics', authenticateAdmin, async (req, res) => {
  const metrics = {
    effectiveness: await calculateOverallEffectiveness(),
    patterns: await getPatternStats(),
    reminders_sent_today: await getRemindersSentToday(),
    opt_out_rate: await getOptOutRate(),
    suppression_rate: await getSuppressionRate(),
    job_performance: await getJobPerformanceMetrics()
  };
  
  res.json(metrics);
});
```

---

## 9. Testing Strategy

### 9.1 Unit Tests

```typescript
describe('PatternCalculator', () => {
  
  it('should calculate pattern with sufficient data', async () => {
    const logs = generateMockLogs(20, { hourStart: 18, hourEnd: 19 });
    const pattern = await calculator.calculatePattern(userId, goalId, 'Pacific/Auckland');
    
    expect(pattern).toBeDefined();
    expect(pattern!.typicalHourStart).toBeGreaterThanOrEqual(17);
    expect(pattern!.typicalHourEnd).toBeLessThanOrEqual(20);
    expect(pattern!.confidenceScore).toBeGreaterThan(0.7);
  });
  
  it('should return null with insufficient data', async () => {
    const logs = generateMockLogs(3);
    const pattern = await calculator.calculatePattern(userId, goalId, 'UTC');
    expect(pattern).toBeNull();
  });
  
  it('should handle midnight-wrapping patterns correctly', async () => {
    // User logs between 11 PM and 1 AM
    const logs = generateMockLogs(15, { hourStart: 23, hourEnd: 1, wrapMidnight: true });
    const pattern = await calculator.calculatePattern(userId, goalId, 'UTC');
    
    expect(pattern).toBeDefined();
    // Median should be near midnight, not noon
    const median = (pattern!.typicalHourStart + pattern!.typicalHourEnd) / 2;
    expect(median).toBeLessThan(3);  // Near midnight
  });
  
  it('should handle weekday vs weekend patterns', async () => {
    const weekdayLogs = generateMockLogs(10, { hourStart: 18, daysOfWeek: [1,2,3,4,5] });
    const weekendLogs = generateMockLogs(10, { hourStart: 10, daysOfWeek: [0,6] });
    
    const weekdayPattern = await calculator.calculatePattern(userId, goalId, 'UTC', 1);
    const weekendPattern = await calculator.calculatePattern(userId, goalId, 'UTC', 0);
    
    expect(weekdayPattern!.typicalHourStart).toBeGreaterThan(15);
    expect(weekendPattern!.typicalHourStart).toBeLessThan(12);
  });
});

describe('ReminderEngine', () => {
  
  it('should send gentle reminder after pattern window', async () => {
    const pattern = { typicalHourEnd: 19, confidenceScore: 0.8 } as LoggingPattern;
    // 7:45 PM local time
    const decision = await engine.shouldSendReminder(
      userId, goalId, 'Pacific/Auckland',
      new Date('2026-02-17T06:45:00Z'), // 7:45 PM NZDT
      null, pattern, [], 0,
      { consecutiveIneffectiveDays: 0 }
    );
    
    expect(decision.shouldSend).toBe(true);
    expect(decision.tier).toBe('gentle');
  });
  
  it('should respect quiet hours', async () => {
    const prefs = { 
      enabled: true, mode: 'smart' as const,
      aggressiveness: 'balanced' as const,
      quietHoursStart: 22, quietHoursEnd: 7, 
      fixedHour: null 
    };
    
    const decision = await engine.shouldSendReminder(
      userId, goalId, 'UTC',
      new Date('2026-02-17T23:00:00Z'),
      prefs, null, [], 0,
      { consecutiveIneffectiveDays: 0 }
    );
    
    expect(decision.shouldSend).toBe(false);
    expect(decision.reason).toContain('Quiet hours');
  });
  
  it('should enforce global daily cap', async () => {
    const decision = await engine.shouldSendReminder(
      userId, goalId, 'UTC', new Date(),
      null, null, [], 6, // Already 6 reminders today
      { consecutiveIneffectiveDays: 0 }
    );
    
    expect(decision.shouldSend).toBe(false);
    expect(decision.reason).toContain('Global daily cap');
  });
  
  it('should suppress after 15 consecutive ineffective days', async () => {
    const decision = await engine.shouldSendReminder(
      userId, goalId, 'UTC', new Date(),
      null, null, [], 0,
      { consecutiveIneffectiveDays: 16 }
    );
    
    expect(decision.shouldSend).toBe(false);
    expect(decision.reason).toContain('Suppressed');
  });
  
  it('should reduce to last_chance only after 7 ineffective days', async () => {
    // At noon with default strategy, this would normally be 'gentle'
    const noonUtc = new Date('2026-02-17T12:00:00Z');
    
    const decision = await engine.shouldSendReminder(
      userId, goalId, 'UTC', noonUtc,
      null, null, [], 0,
      { consecutiveIneffectiveDays: 10 }
    );
    
    // Should NOT send gentle because suppressed to last_chance only
    expect(decision.shouldSend).toBe(false);
  });
  
  it('should not send duplicate reminders', async () => {
    const todaysReminders = [{ reminderTier: 'gentle', sentAt: new Date() }] as ReminderHistory[];
    
    const decision = await engine.shouldSendReminder(
      userId, goalId, 'UTC', new Date(),
      null, null, todaysReminders, 1,
      { consecutiveIneffectiveDays: 0 }
    );
    
    // Should not send gentle again; may send supportive/last_chance depending on time
    if (decision.shouldSend) {
      expect(decision.tier).not.toBe('gentle');
    }
  });
});
```

### 9.2 Integration Tests

```typescript
describe('Smart Reminders Integration', () => {
  
  beforeEach(async () => {
    await resetDatabase();
    await seedTestData();
  });
  
  it('should send reminder with social context', async () => {
    await createTestGroup('Morning Runners', 5);
    await logProgress(member1, goal, today);
    await logProgress(member2, goal, today);
    await logProgress(member3, goal, today);
    
    jest.setSystemTime(new Date('2026-02-17T20:00:00'));
    
    await reminderService.processReminders();
    
    const notifications = await getNotificationsSent();
    expect(notifications).toHaveLength(2);
    expect(notifications[0].body).toContain('3 of 5 teammates');
  });
  
  it('should handle timezone differences correctly', async () => {
    const nzUser = await createUser({ timezone: 'Pacific/Auckland' });
    const pattern = await calculatePattern(nzUser.id, goal.id, 'Pacific/Auckland');
    
    // User typically logs at 6 PM NZDT (5 AM UTC)
    expect(pattern!.typicalHourStart).toBe(17); // 5 PM local
    expect(pattern!.typicalHourEnd).toBe(19); // 7 PM local
  });
  
  it('should respect global daily cap across multiple goals', async () => {
    // User has 4 goals, cap is 6 reminders
    const goals = await createGoals(4);
    
    // Set all patterns to have already passed
    for (const goal of goals) {
      await setPattern(user, goal, { typicalHourEnd: 10, confidence: 0.8 });
    }
    
    // Set time to 9 PM (all tiers eligible)
    jest.setSystemTime(new Date('2026-02-17T21:00:00Z'));
    
    await reminderService.processReminders();
    
    const sent = await getRemindersSentToday(user.id);
    expect(sent.length).toBeLessThanOrEqual(6);
  });
});
```

### 9.3 Load Testing

```bash
# Test pattern calculation performance with 10,000 users
npm run test:load -- --scenario=pattern-calculation --users=10000

# Test reminder processing with 50,000 active goals
npm run test:load -- --scenario=reminder-processing --goals=50000
```

**Performance Targets:**
- Pattern calculation: < 500ms per user
- Reminder processing (15 min job): < 2 minutes total for 50K goals
- Database queries: < 100ms p95
- FCM batch send: < 500ms per 500 messages

---

## 10. Migration & Rollout

### 10.1 Database Migration

```sql
-- Migration: 001_smart_reminders.sql

BEGIN;

CREATE TABLE user_logging_patterns (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  day_of_week INTEGER NOT NULL DEFAULT -1,
  typical_hour_start INTEGER NOT NULL,
  typical_hour_end INTEGER NOT NULL,
  confidence_score DECIMAL(3,2) NOT NULL,
  sample_size INTEGER NOT NULL,
  last_calculated_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id, day_of_week)
);

CREATE INDEX idx_patterns_recalc 
  ON user_logging_patterns(last_calculated_at) 
  WHERE confidence_score > 0.3;

CREATE TABLE reminder_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  reminder_tier VARCHAR(20) NOT NULL,
  sent_at TIMESTAMPTZ DEFAULT NOW(),
  sent_at_local_date DATE NOT NULL,
  was_effective BOOLEAN,
  social_context JSONB,
  user_timezone VARCHAR(50) NOT NULL
);

CREATE INDEX idx_reminder_history_user_goal_date
  ON reminder_history(user_id, goal_id, sent_at_local_date DESC);

CREATE INDEX idx_reminder_history_user_date
  ON reminder_history(user_id, sent_at_local_date)
  WHERE was_effective IS NULL;

CREATE INDEX idx_reminder_history_effectiveness 
  ON reminder_history(reminder_tier, was_effective) 
  WHERE was_effective IS NOT NULL;

CREATE TABLE user_reminder_preferences (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  enabled BOOLEAN DEFAULT TRUE,
  mode VARCHAR(20) DEFAULT 'smart',
  fixed_hour INTEGER,
  aggressiveness VARCHAR(20) DEFAULT 'balanced',
  quiet_hours_start INTEGER,
  quiet_hours_end INTEGER,
  last_modified_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id)
);

CREATE INDEX idx_preferences_enabled 
  ON user_reminder_preferences(user_id) 
  WHERE enabled = TRUE;

-- Backfill default preferences for existing daily goals
INSERT INTO user_reminder_preferences (user_id, goal_id, enabled, mode, aggressiveness)
SELECT DISTINCT
  gm.user_id,
  g.id as goal_id,
  TRUE as enabled,
  'smart' as mode,
  'balanced' as aggressiveness
FROM goals g
INNER JOIN group_memberships gm ON g.group_id = gm.group_id
WHERE g.cadence = 'daily' AND g.deleted_at IS NULL
ON CONFLICT DO NOTHING;

COMMIT;
```

### 10.2 Rollout Plan

**Phase 1: Internal Testing (Week 1)**
- Enable for Shannon's account only
- Monitor logs, test all reminder tiers manually
- Validate pattern calculation with real data
- Confirm Cloud Scheduler invocations are reliable

**Phase 2: Opt-In Beta (Week 2–3)**
- Add "Try Smart Reminders (Beta)" toggle in settings
- Enable for early adopter users who opt in
- Gather feedback, measure initial effectiveness
- Watch for excessive notification complaints

**Phase 3: Gradual Rollout (Week 4–6)**
- Week 4: Enable for 25% of users (default on, can disable)
- Week 5: 50% of users
- Week 6: 100% of users
- Monitor opt-out rate, engagement metrics, app store reviews

**Phase 4: Optimisation (Ongoing)**
- Tune pattern detection algorithm based on effectiveness data
- Experiment with message templates (A/B test copy)
- Adjust adaptive suppression thresholds
- Add weekly/monthly goal support

### 10.3 Feature Flags

```typescript
const SMART_REMINDERS_ENABLED = process.env.SMART_REMINDERS_ENABLED === 'true';

const SMART_REMINDERS_ROLLOUT_PCT = parseInt(
  process.env.SMART_REMINDERS_ROLLOUT_PCT || '0'
);

function isSmartRemindersEnabled(userId: string): boolean {
  if (!SMART_REMINDERS_ENABLED) return false;
  
  // Deterministic per-user rollout based on user ID hash
  const userHash = hashUserId(userId);
  const bucket = userHash % 100;
  return bucket < SMART_REMINDERS_ROLLOUT_PCT;
}

function hashUserId(userId: string): number {
  // Simple deterministic hash for rollout bucketing
  let hash = 0;
  for (let i = 0; i < userId.length; i++) {
    hash = ((hash << 5) - hash) + userId.charCodeAt(i);
    hash = hash & hash; // Convert to 32-bit integer
  }
  return Math.abs(hash);
}
```

---

## 11. Success Metrics

### 11.1 Primary Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Reminder Effectiveness | > 30% | % of reminders leading to a log the same day |
| User Satisfaction | > 4.0/5.0 | In-app survey: "How helpful are reminders?" |
| Opt-Out Rate | < 15% | % of users who disable all reminders |
| Pattern Confidence | > 0.6 avg | Average confidence_score across all patterns |
| Notification Fatigue | < 5% | % of users who disable OS-level notifications |

### 11.2 Secondary Metrics

- **Logging Frequency**: Does smart reminders increase daily completion rate?
- **Retention**: Do users with reminders enabled have higher 30-day retention?
- **Engagement**: Does social context in reminders drive more group interaction?
- **Suppression Rate**: What % of user-goal pairs are adaptively suppressed?
- **Performance**: Job duration, database load, FCM success rate

---

## 12. Decisions on Open Questions (from v1.0)

| Question | Decision | Rationale |
|----------|----------|-----------|
| Weekly/monthly goal reminders? | **Daily only for v1.** Weekly/monthly in Phase 4. | Keep scope manageable; daily goals are the core use case. |
| Max reminders per user per day? | **6 across all goals.** | Prevents notification overload. Prioritise by streak length. |
| Should last_chance be opt-in? | **Opt-out (on by default).** | Highest-value reminder; users can set aggressiveness to 'gentle' for last_chance only, or disable entirely. |
| Timezone auto-detection? | **Use stored timezone from user profile.** Client sends timezone on registration and login; users can update in settings. | Already in the backend spec. Server never guesses. |
| Can admins disable reminders for group? | **Not in v1.** | Low demand; individual user control is sufficient. Revisit if requested. |
| What if pattern changes dramatically? | **30-day rolling window handles this naturally.** Old data ages out. On-demand recalculation available. | Exponential decay weighting further smooths transitions. |

---

## 13. Future Enhancements

### 13.1 Advanced Pattern Detection
- **Multi-goal correlation**: Detect if user logs multiple goals at same time → bundle reminders
- **Event-based patterns**: "After I log workout, I log water intake"
- **Weather awareness**: Outdoor goals delayed on rainy days (via weather API)

### 13.2 Personalised Message Tones
- **Voice & Personality**: Let users choose reminder tone (motivating, playful, strict)
- **Adaptive Tone**: Learn which messages get best response per user
- **Dynamic Content**: Include relevant tips, quotes, or habit science

### 13.3 Weekly/Monthly Goal Support
- Weekly: "You usually log on Tuesday evenings"
- Monthly: "Last month you logged on the 15th"

### 13.4 Gamification
- **Beating the Clock**: Award badge if logged before gentle reminder fires
- **Group Challenges**: "Can your group go a week without needing reminders?"

---

## 14. Appendix

### 14.1 Example Scenario Walkthrough

**User:** Sarah  
**Goal:** "30 min run" (daily, binary)  
**Group:** "Morning Runners" (5 members)  
**Historical Pattern:** Logs between 5:00–7:00 PM on weekdays (confidence: 0.85)  
**Timezone:** Pacific/Auckland (NZDT, UTC+13)

**Timeline on Tuesday, Feb 17, 2026 (all times NZDT):**

| Time (NZDT) | Event |
|-------------|-------|
| 7:00 AM | Sarah wakes up. No reminder — too early. |
| 5:00 PM | Sarah's typical logging window starts. |
| 7:00 PM | Sarah's typical window ends. |
| 7:30 PM | Reminder job runs (Cloud Scheduler). **Tier 1: Gentle** sent — "Time to log: 30 min run" |
| 9:30 PM | Reminder job runs. **Tier 2: Supportive** sent — "4 of 5 teammates completed their goal. Join them!" |
| ~9:00 PM+ | *(Note: if Tier 2 hadn't fired, Tier 3 would fire at 9 PM)* |
| 9:45 PM | Sarah logs her run. ✅ |
| Next day, 3 PM NZDT | Effectiveness job runs. Marks the 9:30 PM supportive reminder as **effective** (Sarah logged same day after it was sent). |

**Result:** Sarah maintained her streak thanks to the supportive reminder with social context. The system learns that supportive reminders are effective for Sarah on this goal.

---

**End of Specification**

**Version:** 2.0  
**Status:** Ready for Implementation  
**Estimated Development Time:** 3–4 weeks  
**Dependencies:** FCM integration, PostgreSQL, Google Cloud Scheduler, OIDC auth  
**Priority:** High (user-requested feature)
