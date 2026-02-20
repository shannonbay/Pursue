Great question! Let me check what we specified for timezone handling:Perfect! Here's the **Timezone Handling Strategy** for Pursue:

## **Timezone Strategy Summary**

### **Core Principle: Store User's Local Date, Not UTC**

The key insight is that **goals are calendar-based, not clock-based**. A "daily goal" means "once per calendar day in the user's timezone", not "once per 24-hour UTC period."

---

## **The Problem We're Solving**

**Bad Example (UTC-based):**
```
User in New York (EST, UTC-5):
- Completes workout Friday 11:30 PM EST
- Server converts to UTC: Saturday 4:30 AM UTC
- ❌ Shows as Saturday workout (wrong!)
- ❌ Breaks their Friday streak
```

**Good Example (Local date-based):**
```
User in New York (EST):
- Completes workout Friday 11:30 PM EST
- App sends: { user_date: "2026-01-17", user_timezone: "America/New_York" }
- Server stores: period_start = '2026-01-17' (DATE, not TIMESTAMP)
- ✅ Shows as Friday workout (correct!)
- ✅ Maintains Friday streak
```

---

## **Implementation Details**

### **1. Database Schema**

```sql
CREATE TABLE progress_entries (
  id UUID PRIMARY KEY,
  goal_id UUID NOT NULL,
  user_id UUID NOT NULL,
  value DECIMAL(10,2) NOT NULL,
  note TEXT,
  
  -- CRITICAL: Two different timestamps
  logged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),  -- UTC timestamp (for sorting/feed)
  period_start DATE NOT NULL,                        -- User's local date (for tracking)
  user_timezone VARCHAR(50),                         -- Reference (e.g., "America/New_York")
  
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**Why two timestamps?**
- `logged_at` (TIMESTAMP WITH TIME ZONE): For activity feed sorting ("2 hours ago")
- `period_start` (DATE): For goal tracking ("Friday's workout")

### **2. API Request (POST /api/progress)**

**Android app sends:**
```json
{
  "goal_id": "goal-uuid",
  "value": 1,
  "note": "Great run!",
  "user_date": "2026-01-17",                    // ← User's local date (not converted)
  "user_timezone": "America/New_York"           // ← IANA timezone (optional, for reference)
}
```

**Android code:**
```kotlin
// Get user's local date
val localDate = LocalDate.now()  // Uses device timezone automatically
val dateString = localDate.toString()  // "2026-01-17"

// Get user's timezone
val timeZone = TimeZone.getDefault().id  // "America/New_York"

// Send to API
val request = LogProgressRequest(
    goal_id = goalId,
    value = value,
    note = note,
    user_date = dateString,        // Send local date as-is
    user_timezone = timeZone        // For reference
)
```

### **3. Backend Processing**

```typescript
app.post('/api/progress', authenticateJWT, async (req, res) => {
  const { goal_id, value, note, user_date, user_timezone } = req.body;
  const userId = req.user.user_id;
  
  // CRITICAL: Do NOT convert user_date to UTC
  // Store exactly what the user sends
  const periodStart = user_date;  // "2026-01-17" (their local date)
  
  // Check for duplicate (same goal, user, period)
  const existing = await db
    .selectFrom('progress_entries')
    .select('id')
    .where('goal_id', '=', goal_id)
    .where('user_id', '=', userId)
    .where('period_start', '=', periodStart)  // Same calendar day
    .executeTakeFirst();
  
  if (existing) {
    return res.status(400).json({ error: 'Already logged for this period' });
  }
  
  // Create entry
  const entry = await db
    .insertInto('progress_entries')
    .values({
      goal_id,
      user_id: userId,
      value,
      note,
      period_start: periodStart,       // Store user's local date
      user_timezone: user_timezone,    // Store for reference
      logged_at: new Date()             // UTC timestamp (auto)
    })
    .returningAll()
    .executeTakeFirst();
  
  res.status(201).json(entry);
});
```

---

## **4. Period Calculation (Weekly/Monthly/Yearly)**

For non-daily goals, calculate the period in the **user's timezone**:

```typescript
function calculatePeriodStart(userDate: string, cadence: string, timezone: string): string {
  const moment = require('moment-timezone');
  const date = moment.tz(userDate, timezone);
  
  switch (cadence) {
    case 'daily':
      return userDate;  // Same day
      
    case 'weekly':
      // Get Monday of that week (in user's timezone)
      return date.startOf('isoWeek').format('YYYY-MM-DD');
      
    case 'monthly':
      // Get first day of month (in user's timezone)
      return date.startOf('month').format('YYYY-MM-DD');
      
    case 'yearly':
      // Get January 1st (in user's timezone)
      return date.startOf('year').format('YYYY-MM-DD');
  }
}
```

**Example (Weekly Goal):**
```
User in Tokyo (JST, UTC+9):
- Logs on Saturday Jan 18, 2026
- period_start = Monday Jan 13, 2026 (start of that week in JST)

User in New York (EST, UTC-5):
- Logs on Saturday Jan 18, 2026
- period_start = Monday Jan 13, 2026 (start of that week in EST)

✅ Both get the same "week" even though they're 14 hours apart in UTC
```

---

## **5. Querying Progress**

**Get today's progress (user's local date):**
```typescript
app.get('/api/goals/:goal_id/progress/today', authenticateJWT, async (req, res) => {
  const { goal_id } = req.params;
  const { user_date } = req.query;  // App sends "2026-01-17"
  const userId = req.user.user_id;
  
  // Query by user's local date (no timezone conversion)
  const progress = await db
    .selectFrom('progress_entries')
    .selectAll()
    .where('goal_id', '=', goal_id)
    .where('user_id', '=', userId)
    .where('period_start', '=', user_date)  // Exact date match
    .executeTakeFirst();
  
  res.json({ progress });
});
```

**Get this week's progress:**
```typescript
app.get('/api/goals/:goal_id/progress/week', authenticateJWT, async (req, res) => {
  const { goal_id } = req.params;
  const { week_start } = req.query;  // App sends "2026-01-13" (Monday)
  const userId = req.user.user_id;
  
  // Query all entries for this week
  const progress = await db
    .selectFrom('progress_entries')
    .selectAll()
    .where('goal_id', '=', goal_id)
    .where('user_id', '=', userId)
    .where('period_start', '>=', week_start)           // Monday
    .where('period_start', '<', addDays(week_start, 7))  // Next Monday
    .execute();
  
  res.json({ progress });
});
```

---

## **6. Edge Cases**

### **User Travels Across Timezones**

**Scenario:**
```
User flies from New York (EST) to Tokyo (JST)
- Arrives Saturday morning Tokyo time
- Completes workout Saturday 10 AM JST
- App sends: { user_date: "2026-01-18", user_timezone: "Asia/Tokyo" }
- ✅ Counts for Saturday in their current timezone
```

**Historical data preserves original timezone:**
```sql
SELECT * FROM progress_entries WHERE user_id = 'uuid';

-- Results show where user was when they logged:
period_start    user_timezone        logged_at
2026-01-17      America/New_York     2026-01-17T23:30:00Z
2026-01-18      Asia/Tokyo           2026-01-18T01:00:00Z
```

### **Daylight Saving Time**

**No issues!** Because we store DATE (not TIMESTAMP), DST transitions don't affect anything:

```
User in New York:
- Logs workout Sunday morning (DST begins overnight)
- App sends: { user_date: "2026-03-08" }
- ✅ Stored as '2026-03-08' (DATE)
- No UTC conversion, no DST confusion
```

### **Leap Years / Leap Seconds**

**Handled automatically** by using DATE type and user's local calendar.

---

## **7. Why This Works**

### **Benefits:**

✅ **Intuitive**: "Friday's workout" means Friday in user's timezone  
✅ **Simple**: No complex timezone math  
✅ **Portable**: Works across timezones (traveling users)  
✅ **Accurate**: Respects user's local calendar  
✅ **Reliable**: No DST bugs  
✅ **Scalable**: Efficient queries (indexed on DATE)  

### **Trade-offs:**

⚠️ **Multiple timezones in one group**: Users see their own local calendar  
- Example: New York user logs Friday 11 PM → Shows as Friday
- Example: Tokyo user sees it "Saturday" (because it's Saturday in Tokyo)
- **Solution**: This is actually correct! Each user sees progress in their own local time.

⚠️ **Historical timezone data**: If user doesn't send timezone, we can't reconstruct it  
- **Solution**: Make `user_timezone` required in API (fail if missing)

---

## **8. Summary Table**

| Field | Type | Purpose | Example |
|-------|------|---------|---------|
| `logged_at` | TIMESTAMP WITH TIME ZONE | Activity feed sorting | `2026-01-17T23:30:00Z` (UTC) |
| `period_start` | DATE | Goal tracking | `2026-01-17` (user's local date) |
| `user_timezone` | VARCHAR(50) | Reference/debugging | `America/New_York` (IANA) |

---

## **9. Android Implementation**

```kotlin
// Get user's local date and timezone
fun getCurrentLocalDate(): String {
    return LocalDate.now().toString()  // "2026-01-17"
}

fun getCurrentTimezone(): String {
    return TimeZone.getDefault().id  // "America/New_York"
}

// Log progress
suspend fun logProgress(goalId: String, value: Double, note: String?) {
    val request = LogProgressRequest(
        goal_id = goalId,
        value = value,
        note = note,
        user_date = getCurrentLocalDate(),     // User's local date
        user_timezone = getCurrentTimezone()   // IANA timezone
    )
    
    api.logProgress(request)
}

// Check if logged today
suspend fun hasLoggedToday(goalId: String): Boolean {
    val today = getCurrentLocalDate()
    val response = api.getTodayProgress(goalId, today)
    return response.progress != null
}
```

---

## **Key Takeaway**

**Store the user's local DATE, not UTC timestamps, for goal tracking.**

This simple strategy avoids all the classic timezone bugs and matches user expectations. Goals are calendar-based, not clock-based! 🎯