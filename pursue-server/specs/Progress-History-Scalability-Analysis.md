# Progress History Scalability Analysis - Pursue Backend

**Date:** January 22, 2026  
**Status:** Analysis Complete  
**Severity:** ⚠️ MEDIUM - Needs optimization before launch

---

## Executive Summary

**Current State:**
- `progress_entries` table stores unlimited history (no retention policy)
- Indexes exist but queries lack proper date range filtering
- UI only displays 30 days, but backend may fetch much more
- No pagination on progress endpoints
- Potential for unbounded query growth over time

**Is This a Problem?**
- **Short term (0-6 months):** ✅ No issues - small datasets
- **Medium term (6-18 months):** ⚠️ Performance degradation - needs optimization
- **Long term (18+ months):** ❌ Major issues - requires immediate fixes

**Recommended Actions:**
1. Add date range filters to all progress queries (Quick win)
2. Add pagination to progress endpoints (Medium priority)
3. Implement data retention policy (Long term)
4. Add query monitoring and slow query alerts (Ongoing)

---

## Table Schema Analysis

### Current Schema

```sql
CREATE TABLE progress_entries (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  goal_id UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  value DECIMAL(10,2) NOT NULL,
  note TEXT,
  logged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  period_start DATE NOT NULL,
  user_timezone VARCHAR(50),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes (GOOD - these exist)
CREATE INDEX idx_progress_goal_user ON progress_entries(goal_id, user_id, period_start);
CREATE INDEX idx_progress_user_recent ON progress_entries(user_id, logged_at DESC);
CREATE INDEX idx_progress_period ON progress_entries(period_start);
```

**Indexes:** ✅ Good composite index for common queries
**Problem:** No constraints on data retention or query limits

---

## Growth Projection

### Assumptions

**Active user with moderate usage:**
- 3 groups
- 5 goals per group = 15 total goals
- Logs progress 10 times/day (2/3 of goals)
- Uses app 350 days/year (95% uptime)

**Database Growth:**

| Time Period | Entries per User | 1,000 Users | 10,000 Users | 100,000 Users |
|-------------|-----------------|-------------|--------------|---------------|
| 1 month     | ~300            | 300K        | 3M           | 30M           |
| 6 months    | ~1,800          | 1.8M        | 18M          | 180M          |
| 1 year      | ~3,500          | 3.5M        | 35M          | 350M          |
| 2 years     | ~7,000          | 7M          | 70M          | 700M          |
| 5 years     | ~17,500         | 17.5M       | 175M         | 1.75B         |

**Storage Size Estimates:**

```
Single row size: ~150 bytes (UUID + refs + value + timestamps + note)
1M rows = ~150 MB
10M rows = ~1.5 GB
100M rows = ~15 GB
1B rows = ~150 GB
```

**Index Size:** ~30-40% of table size

**Total for 100K users after 2 years:**
- Table: ~100 GB
- Indexes: ~30-40 GB
- **Total: ~130-140 GB**

✅ **Storage is NOT the problem** - PostgreSQL handles this fine.

❌ **Query performance IS the problem** - Scanning millions of rows is slow.

---

## Problem Areas

### 1. Unbounded Progress Queries (Critical)

**Current Endpoint:** `GET /api/goals/:goal_id/progress`

```typescript
// ❌ CURRENT - No date limits!
const progress = await db
  .selectFrom('progress_entries')
  .selectAll()
  .where('goal_id', '=', goalId)
  .execute();  // Could return 10,000+ rows!
```

**Problem:**
- If goal has existed for 2 years, this returns ~700 entries per user
- With 10 users in group = 7,000 rows returned
- Response size: ~1 MB JSON
- Query time: 500ms - 2 seconds (depending on index)

**Impact:**
- Slow API responses
- High bandwidth usage
- Android app memory issues (parsing huge JSON)
- Database CPU spikes

---

### 2. Heatmap Query (30 Days) - MODERATE

**UI Spec:**
- Displays 30-day heatmap
- Shows completion percentage per day

**Likely Query:**

```typescript
// Current (probably implemented this way)
const thirtyDaysAgo = new Date();
thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

const progress = await db
  .selectFrom('progress_entries')
  .select(['period_start', 'value', 'goal_id'])
  .where('user_id', '=', userId)
  .where('period_start', '>=', thirtyDaysAgo)  // ✅ GOOD - has date filter
  .execute();
```

**Analysis:**
- 30 days × 10 entries/day = ~300 rows
- Query time: 10-50ms ✅ Acceptable
- Index `idx_progress_user_recent` helps

**Verdict:** ✅ This query is fine (if date filter exists)

---

### 3. Streak Calculation - CRITICAL

**Backend spec mentions streak calculation but no implementation shown.**

**Likely Implementation (Naive):**

```typescript
// ❌ BAD - Fetches ALL history
async function calculateStreak(userId: string): Promise<number> {
  const allProgress = await db
    .selectFrom('progress_entries')
    .select(['period_start', 'value'])
    .where('user_id', '=', userId)
    .orderBy('period_start', 'desc')
    .execute();  // ALL TIME - could be thousands of rows
    
  let streak = 0;
  let currentDate = new Date();
  
  for (const entry of allProgress) {
    // Check if consecutive day
    // ...
  }
  
  return streak;
}
```

**Problem:**
- Fetches entire user history (potentially years)
- Inefficient loop through all rows
- Query time: 1-5 seconds for active users

**Better Implementation:**

```typescript
// ✅ GOOD - Only fetch recent 100 days
async function calculateStreak(userId: string): Promise<number> {
  const hundredDaysAgo = new Date();
  hundredDaysAgo.setDate(hundredDaysAgo.getDate() - 100);
  
  const recentProgress = await db
    .selectFrom('progress_entries')
    .select(['period_start', 'value'])
    .where('user_id', '=', userId)
    .where('period_start', '>=', hundredDaysAgo)  // LIMIT to 100 days
    .orderBy('period_start', 'desc')
    .execute();
    
  // Calculate streak (max possible is 100 days anyway)
  // ...
}
```

**Why 100 days?**
- Realistically, no one has >100 day streak to protect
- If they do, cap streak at 100 (or add special "Century Club" badge)
- Keeps query fast and bounded

---

### 4. Goal Breakdown (30 Days) - GOOD

**UI displays:**
- "30 min run: 80% (24/30)" - completed 24 out of last 30 days

**Query:**

```typescript
const thirtyDaysAgo = new Date();
thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

const progress = await db
  .selectFrom('progress_entries')
  .select(['goal_id', 'value'])
  .where('user_id', '=', userId)
  .where('period_start', '>=', thirtyDaysAgo)  // ✅ Date filter
  .where('period_start', '<=', new Date())
  .execute();

// Count completed per goal
const breakdown = progress.reduce((acc, entry) => {
  if (!acc[entry.goal_id]) acc[entry.goal_id] = 0;
  if (entry.value > 0) acc[entry.goal_id]++;
  return acc;
}, {});
```

**Analysis:**
- 30 days × 10 entries = ~300 rows ✅
- Query time: 10-50ms ✅
- Acceptable performance

**Verdict:** ✅ This is fine

---

### 5. Activity Feed - CRITICAL

**Current spec has no pagination!**

**Likely Implementation:**

```typescript
// GET /api/groups/:group_id/activity
const activities = await db
  .selectFrom('group_activities')
  .selectAll()
  .where('group_id', '=', groupId)
  .orderBy('created_at', 'desc')
  .execute();  // ❌ NO LIMIT!
```

**Problem:**
- Active group = 100+ activities/day
- After 1 month = 3,000 activities
- After 1 year = 36,500 activities
- Query returns everything!

**Solution:**

```typescript
// ✅ GOOD - Add pagination
const activities = await db
  .selectFrom('group_activities')
  .selectAll()
  .where('group_id', '=', groupId)
  .orderBy('created_at', 'desc')
  .limit(50)  // Limit to 50 most recent
  .offset(page * 50)  // Pagination
  .execute();
```

---

## Missing from Backend Spec

### 1. No LIMIT Clauses

**Problem:** Most endpoints don't specify query limits.

**Example:**
```typescript
// GET /api/goals/:goal_id/progress
// Current spec doesn't mention LIMIT or pagination
```

**Fix:** Add `limit` and `offset` query parameters to all list endpoints.

---

### 2. No Date Range Filters

**Endpoints missing date filters:**

```typescript
// GET /api/goals/:goal_id/progress
// ❌ Should require start_date and end_date

// GET /api/users/me/progress
// ❌ Should require date range or default to last 30 days
```

**Current spec shows:**
```json
// Query Parameters:
- start_date: ISO date (e.g., '2026-01-01')
- end_date: ISO date (e.g., '2026-01-31')
```

**But these are OPTIONAL!** If not provided, query fetches ALL history.

---

### 3. No Data Retention Policy

**Questions:**
- Do we keep progress history forever?
- Delete after 2 years?
- Archive old data to cheaper storage?

**Recommendation:** Keep 2 years, archive older data.

---

## Recommended Fixes

### Priority 1: Add Query Limits (Immediate)

**Update all progress endpoints to require date ranges:**

```typescript
// GET /api/goals/:goal_id/progress
app.get('/api/goals/:goal_id/progress', authenticate, async (req, res) => {
  const { start_date, end_date } = req.query;
  
  // ✅ REQUIRE date range (default to last 30 days if not provided)
  const startDate = start_date 
    ? new Date(start_date) 
    : new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
    
  const endDate = end_date 
    ? new Date(end_date) 
    : new Date();
    
  // ✅ Enforce max range (e.g., 90 days)
  const diffDays = (endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24);
  if (diffDays > 90) {
    return res.status(400).json({
      error: {
        message: 'Date range too large. Maximum 90 days.',
        code: 'DATE_RANGE_TOO_LARGE'
      }
    });
  }
  
  const progress = await db
    .selectFrom('progress_entries')
    .selectAll()
    .where('goal_id', '=', req.params.goal_id)
    .where('period_start', '>=', startDate)
    .where('period_start', '<=', endDate)
    .limit(1000)  // Hard limit as safety net
    .execute();
    
  res.json({ progress });
});
```

**Benefits:**
- Prevents unbounded queries
- Fast response times (<100ms)
- Predictable database load

---

### Priority 2: Add Pagination (Medium)

**Update all list endpoints:**

```typescript
// GET /api/groups/:group_id/activity
app.get('/api/groups/:group_id/activity', authenticate, async (req, res) => {
  const page = parseInt(req.query.page as string) || 0;
  const limit = Math.min(parseInt(req.query.limit as string) || 50, 100);  // Max 100
  
  const activities = await db
    .selectFrom('group_activities')
    .selectAll()
    .where('group_id', '=', req.params.group_id)
    .orderBy('created_at', 'desc')
    .limit(limit)
    .offset(page * limit)
    .execute();
    
  const total = await db
    .selectFrom('group_activities')
    .where('group_id', '=', req.params.group_id)
    .select(db.fn.count('id').as('count'))
    .executeTakeFirst();
    
  res.json({
    activities,
    pagination: {
      page,
      limit,
      total: parseInt(total?.count as string || '0'),
      has_more: activities.length === limit
    }
  });
});
```

---

### Priority 3: Optimize Streak Calculation (High)

```typescript
// ✅ Optimized streak calculation
async function calculateStreak(userId: string): Promise<number> {
  // Only fetch last 100 days (no streak longer than this anyway)
  const cutoffDate = new Date();
  cutoffDate.setDate(cutoffDate.getDate() - 100);
  
  const progress = await db
    .selectFrom('progress_entries')
    .select(['period_start', 'goal_id'])
    .where('user_id', '=', userId)
    .where('period_start', '>=', cutoffDate)
    .where('value', '>', 0)  // Only completed entries
    .orderBy('period_start', 'desc')
    .execute();
    
  // Group by date to get daily completion percentage
  const dailyCompletion = new Map<string, number>();
  
  for (const entry of progress) {
    const dateKey = entry.period_start.toISOString().split('T')[0];
    dailyCompletion.set(dateKey, (dailyCompletion.get(dateKey) || 0) + 1);
  }
  
  // Count consecutive days with >50% completion
  let streak = 0;
  let currentDate = new Date();
  
  while (streak < 100) {  // Cap at 100 days
    const dateKey = currentDate.toISOString().split('T')[0];
    const completed = dailyCompletion.get(dateKey) || 0;
    const totalGoals = 15;  // Get this from user's active goals
    
    if (completed / totalGoals >= 0.5) {
      streak++;
      currentDate.setDate(currentDate.getDate() - 1);
    } else {
      break;
    }
  }
  
  return streak;
}
```

**Benefits:**
- Query limited to 100 days max
- Fast (10-50ms)
- Predictable memory usage

---

### Priority 4: Add Data Retention Policy (Long-term)

**Option A: Soft Delete (Recommended)**

```sql
-- Add deleted_at column
ALTER TABLE progress_entries ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;

-- Create index
CREATE INDEX idx_progress_not_deleted ON progress_entries(period_start) 
  WHERE deleted_at IS NULL;
```

```typescript
// Scheduled job (run daily)
async function archiveOldProgress() {
  const twoYearsAgo = new Date();
  twoYearsAgo.setFullYear(twoYearsAgo.getFullYear() - 2);
  
  // Soft delete entries older than 2 years
  await db
    .updateTable('progress_entries')
    .set({ deleted_at: new Date() })
    .where('period_start', '<', twoYearsAgo)
    .where('deleted_at', 'is', null)
    .execute();
    
  console.log('Archived progress older than 2 years');
}
```

**Option B: Archive to Separate Table**

```sql
-- Create archive table (same schema)
CREATE TABLE progress_entries_archive (
  LIKE progress_entries INCLUDING ALL
);

-- Move old data
INSERT INTO progress_entries_archive
SELECT * FROM progress_entries
WHERE period_start < (CURRENT_DATE - INTERVAL '2 years');

DELETE FROM progress_entries
WHERE period_start < (CURRENT_DATE - INTERVAL '2 years');
```

**Recommendation:** Soft delete first, archive later if needed.

---

### Priority 5: Add Query Monitoring

**PostgreSQL Slow Query Log:**

```sql
-- postgresql.conf
log_min_duration_statement = 1000  -- Log queries >1 second
log_statement = 'all'  -- Log all statements (dev only)
```

**Application Logging:**

```typescript
// Middleware to log slow queries
import { performance } from 'perf_hooks';

app.use(async (req, res, next) => {
  const start = performance.now();
  
  res.on('finish', () => {
    const duration = performance.now() - start;
    
    if (duration > 500) {  // Log queries >500ms
      console.warn({
        message: 'Slow query detected',
        method: req.method,
        path: req.path,
        duration_ms: duration,
        query: req.query
      });
    }
  });
  
  next();
});
```

**Cloud Monitoring:**

```typescript
// Send metrics to Google Cloud Monitoring
import { MetricServiceClient } from '@google-cloud/monitoring';

async function recordQueryDuration(endpoint: string, duration: number) {
  const client = new MetricServiceClient();
  
  await client.createTimeSeries({
    name: client.projectPath(projectId),
    timeSeries: [{
      metric: {
        type: 'custom.googleapis.com/api/query_duration',
        labels: { endpoint }
      },
      points: [{
        interval: {
          endTime: { seconds: Date.now() / 1000 }
        },
        value: { doubleValue: duration }
      }]
    }]
  });
}
```

---

## Testing Strategy

### Load Testing

**Use k6 to simulate realistic usage:**

```javascript
// load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '2m', target: 100 },   // Ramp up to 100 users
    { duration: '5m', target: 100 },   // Stay at 100 for 5 minutes
    { duration: '2m', target: 0 },     // Ramp down
  ],
};

export default function () {
  const userId = 'test-user-' + Math.floor(Math.random() * 1000);
  
  // Login
  const loginRes = http.post('http://localhost:3000/api/auth/login', {
    email: `${userId}@test.com`,
    password: 'test123'
  });
  
  const token = loginRes.json('access_token');
  
  // Fetch 30-day progress (realistic query)
  const progressRes = http.get(
    'http://localhost:3000/api/users/me/progress?start_date=2026-01-01&end_date=2026-01-30',
    { headers: { Authorization: `Bearer ${token}` } }
  );
  
  check(progressRes, {
    'status is 200': (r) => r.status === 200,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });
  
  sleep(1);
}
```

**Run test:**
```bash
k6 run load-test.js
```

---

### Database Seeding

**Create test data with realistic history:**

```typescript
// scripts/seed-progress-history.ts
async function seedProgressHistory() {
  const userId = 'test-user-uuid';
  const goalId = 'test-goal-uuid';
  
  // Create 2 years of history
  const twoYearsAgo = new Date();
  twoYearsAgo.setFullYear(twoYearsAgo.getFullYear() - 2);
  
  const entries = [];
  
  for (let i = 0; i < 730; i++) {  // 730 days = 2 years
    const date = new Date(twoYearsAgo);
    date.setDate(date.getDate() + i);
    
    // 70% completion rate
    if (Math.random() < 0.7) {
      entries.push({
        id: uuid(),
        goal_id: goalId,
        user_id: userId,
        value: 1,
        period_start: date.toISOString().split('T')[0],
        logged_at: date,
        created_at: date
      });
    }
  }
  
  // Bulk insert
  await db.insertInto('progress_entries').values(entries).execute();
  
  console.log(`Created ${entries.length} progress entries over 2 years`);
}
```

**Test queries against this data:**
```bash
# Should be fast (<100ms)
curl "http://localhost:3000/api/users/me/progress?start_date=2026-01-01&end_date=2026-01-30"

# Should be rejected (>90 day range)
curl "http://localhost:3000/api/users/me/progress?start_date=2024-01-01&end_date=2026-01-30"
```

---

## Cost Analysis

### Database Costs (Google Cloud SQL)

**PostgreSQL instance for 100K users after 2 years:**

| Resource | Requirement | Cost/Month |
|----------|-------------|------------|
| CPU | 4 vCPU | $150 |
| RAM | 16 GB | $100 |
| Storage (SSD) | 150 GB | $25 |
| Backups | 150 GB | $8 |
| **Total** | | **~$283/month** |

**With optimizations (date filters + archiving):**

| Resource | Requirement | Cost/Month |
|----------|-------------|------------|
| CPU | 2 vCPU | $75 |
| RAM | 8 GB | $50 |
| Storage (SSD) | 50 GB | $8 |
| Backups | 50 GB | $3 |
| **Total** | | **~$136/month** |

**Savings:** ~$147/month (~52% reduction)

---

## Summary & Recommendations

### Current Status

| Area | Status | Risk |
|------|--------|------|
| Table schema | ✅ Good indexes | Low |
| Storage growth | ✅ Manageable | Low |
| Query limits | ❌ Missing | **HIGH** |
| Date filters | ⚠️ Optional | **MEDIUM** |
| Pagination | ❌ Missing | **MEDIUM** |
| Retention policy | ❌ Missing | Low (short-term) |
| Monitoring | ❌ Missing | **MEDIUM** |

### Action Items

**Before Launch (Critical):**
1. ✅ Make date ranges REQUIRED on all progress endpoints
2. ✅ Add default 30-day window if not specified
3. ✅ Add max 90-day range validation
4. ✅ Add LIMIT clauses as safety nets (1000 rows max)
5. ✅ Optimize streak calculation (100-day window)

**First Month (High Priority):**
6. ✅ Add pagination to activity feed
7. ✅ Add slow query logging
8. ✅ Set up query monitoring dashboards
9. ✅ Load test with realistic data (2+ years of history)

**First Quarter (Medium Priority):**
10. ✅ Implement soft delete for old progress (2+ years)
11. ✅ Add cron job to archive old data monthly
12. ✅ Set up alerting for slow queries (>500ms)

**First Year (Low Priority):**
13. ✅ Move archived data to cheaper storage (Cloud Storage)
14. ✅ Add data export feature for users
15. ✅ Consider partitioning table by period_start (PostgreSQL 17 feature)

---

## Conclusion

**Is long progress history a problem?** 

✅ **Not immediately** - With proper query limits and date filters
❌ **Yes, eventually** - Without optimizations, performance will degrade significantly

**The spec is missing critical safeguards:**
- No required date range filters
- No pagination
- No query limits
- No retention policy

**These are easy fixes that should be implemented before launch.**

The good news: The index structure is already solid, so adding these optimizations won't require schema changes - just API updates.

**Estimated effort:** 2-3 days to implement all Priority 1 & 2 fixes.

---

**Next Steps:** Update backend spec to include these query limits and pagination patterns.
