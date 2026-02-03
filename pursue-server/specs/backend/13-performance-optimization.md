## 13. Performance Optimization

### 13.1 Input Validation (Zod)

**Critical:** Validate all user input to prevent injection, type errors, and invalid data.

```typescript
import { z } from 'zod';

// Progress entry validation
export const ProgressEntrySchema = z.object({
  goal_id: z.string().uuid(),
  value: z.number(),
  note: z.string().max(500).optional(),
  user_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), // YYYY-MM-DD
  user_timezone: z.string().min(1).max(50) // IANA timezone
}).strict();

// Validation with metric type checking
export async function validateProgressEntry(data: unknown, goalId: string) {
  // Parse basic structure
  const parsed = ProgressEntrySchema.parse(data);
  
  // Fetch goal to check metric_type
  const goal = await db
    .selectFrom('goals')
    .select(['metric_type', 'target_value'])
    .where('id', '=', goalId)
    .executeTakeFirst();
    
  if (!goal) {
    throw new Error('Goal not found');
  }
  
  // Validate value against metric_type
  if (goal.metric_type === 'binary') {
    if (parsed.value !== 0 && parsed.value !== 1) {
      throw new Error('Binary goal value must be 0 or 1');
    }
  } else if (goal.metric_type === 'numeric') {
    if (parsed.value < 0) {
      throw new Error('Numeric goal value cannot be negative');
    }
    if (parsed.value > 999999.99) {
      throw new Error('Numeric goal value too large');
    }
  } else if (goal.metric_type === 'duration') {
    if (parsed.value < 0) {
      throw new Error('Duration cannot be negative');
    }
    if (!Number.isInteger(parsed.value)) {
      throw new Error('Duration must be integer (seconds)');
    }
  }
  
  return parsed;
}

// Goal creation validation
export const CreateGoalSchema = z.object({
  title: z.string().min(1).max(200),
  description: z.string().max(1000).optional(),
  cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
  metric_type: z.enum(['binary', 'numeric', 'duration']),
  target_value: z.number().positive().max(999999.99).optional(),
  unit: z.string().max(50).optional()
}).strict().refine(
  (data) => {
    // Numeric goals should have target_value
    if (data.metric_type === 'numeric' && !data.target_value) {
      return false;
    }
    return true;
  },
  { message: 'Numeric goals must have target_value' }
);

// User registration validation
export const RegisterSchema = z.object({
  email: z.string().email().max(255),
  password: z.string().min(8).max(100),
  display_name: z.string().min(1).max(100)
}).strict();
```

**Usage in Routes:**
```typescript
app.post('/api/progress', authenticate, async (req, res, next) => {
  try {
    const validated = await validateProgressEntry(req.body, req.body.goal_id);
    // Proceed with validated data
  } catch (error) {
    if (error instanceof z.ZodError) {
      return res.status(400).json({
        error: {
          message: 'Validation error',
          code: 'VALIDATION_ERROR',
          details: error.errors
        }
      });
    }
    next(error);
  }
});
```

### 13.2 Database Connection Pooling

```typescript
import { Pool } from 'pg';
import { Kysely, PostgresDialect } from 'kysely';

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 20, // Maximum connections
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 2000
});

export const db = new Kysely({
  dialect: new PostgresDialect({ pool })
});
```

### 13.2 Query Optimization

```typescript
// ✅ Good: Use indexes, limit results
const recentProgress = await db
  .selectFrom('progress_entries')
  .selectAll()
  .where('user_id', '=', userId)
  .orderBy('logged_at', 'desc')
  .limit(50)
  .execute();

// ✅ Good: Select only needed columns
const users = await db
  .selectFrom('users')
  .select([
    'id', 
    'display_name',
    db.raw('(avatar_data IS NOT NULL) as has_avatar').as('has_avatar')
  ])
  .where('id', 'in', userIds)
  .execute();
```

**Note:** Don't select `avatar_data` in list queries - it's large binary data. Use `has_avatar` boolean to show if avatar exists, then fetch via GET endpoint if needed.

### 13.3 Query Optimization

```typescript
// ✅ Good: Use indexes, limit results
const recentProgress = await db
  .selectFrom('progress_entries')
  .selectAll()
  .where('user_id', '=', userId)
  .orderBy('logged_at', 'desc')
  .limit(50)
  .execute();

// ✅ Good: Select only needed columns (don't select BYTEA in lists)
const users = await db
  .selectFrom('users')
  .select([
    'id', 
    'display_name',
    db.raw('(avatar_data IS NOT NULL) as has_avatar').as('has_avatar')
  ])
  .where('id', 'in', userIds)
  .execute();
```

### 13.4 Preventing N+1 Queries

**Problem:** The N+1 query problem occurs when you fetch a list of items, then loop through them making additional queries for related data.

**Example - BAD (N+1):**
```typescript
// ❌ BAD: This makes 1 + N queries
app.get('/api/groups/:id', async (req, res) => {
  // 1 query to get group
  const group = await db
    .selectFrom('groups')
    .selectAll()
    .where('id', '=', req.params.id)
    .executeTakeFirst();
    
  // N queries (one per goal)
  const goals = await db
    .selectFrom('goals')
    .selectAll()
    .where('group_id', '=', group.id)
    .execute();
    
  // For each goal, fetch recent progress (N more queries!)
  for (const goal of goals) {
    goal.recentProgress = await db
      .selectFrom('progress_entries')
      .selectAll()
      .where('goal_id', '=', goal.id)
      .limit(5)
      .execute();
  }
  
  return res.json({ group, goals });
});
```

**Solution - GOOD (Single Optimized Query):**
```typescript
// ✅ GOOD: This makes 1 query using JOINs
app.get('/api/groups/:id', async (req, res) => {
  const result = await db
    .selectFrom('groups as g')
    .leftJoin('goals as go', 'go.group_id', 'g.id')
    .leftJoin('progress_entries as p', 'p.goal_id', 'go.id')
    .select([
      'g.id as group_id',
      'g.name as group_name',
      'g.description',
      'go.id as goal_id',
      'go.title as goal_title',
      'go.cadence',
      'p.id as progress_id',
      'p.value',
      'p.user_id',
      'p.logged_at'
    ])
    .where('g.id', '=', req.params.id)
    .where('go.deleted_at', 'is', null) // Only active goals
    .orderBy('p.logged_at', 'desc')
    .execute();
    
  // Transform flat result into nested structure
  const group = {
    id: result[0]?.group_id,
    name: result[0]?.group_name,
    description: result[0]?.description,
    goals: []
  };
  
  // Group by goal_id
  const goalsMap = new Map();
  for (const row of result) {
    if (!goalsMap.has(row.goal_id)) {
      goalsMap.set(row.goal_id, {
        id: row.goal_id,
        title: row.goal_title,
        cadence: row.cadence,
        recentProgress: []
      });
    }
    
    if (row.progress_id) {
      goalsMap.get(row.goal_id).recentProgress.push({
        id: row.progress_id,
        value: row.value,
        user_id: row.user_id,
        logged_at: row.logged_at
      });
    }
  }
  
  group.goals = Array.from(goalsMap.values());
  
  return res.json(group);
});
```

**Alternative: Use Kysely's `selectFrom().selectAll()` with aggregation:**
```typescript
// ✅ ALSO GOOD: Fetch separately but efficiently
const [group, goals, recentProgress] = await Promise.all([
  // Query 1: Get group
  db.selectFrom('groups')
    .selectAll()
    .where('id', '=', groupId)
    .executeTakeFirst(),
    
  // Query 2: Get all goals for this group
  db.selectFrom('goals')
    .selectAll()
    .where('group_id', '=', groupId)
    .where('deleted_at', 'is', null)
    .execute(),
    
  // Query 3: Get recent progress for ALL goals in one query
  db.selectFrom('progress_entries')
    .innerJoin('goals', 'goals.id', 'progress_entries.goal_id')
    .selectAll('progress_entries')
    .where('goals.group_id', '=', groupId)
    .orderBy('progress_entries.logged_at', 'desc')
    .limit(100) // Reasonable limit across all goals
    .execute()
]);

// Group progress by goal_id in memory
const progressByGoal = new Map();
for (const entry of recentProgress) {
  if (!progressByGoal.has(entry.goal_id)) {
    progressByGoal.set(entry.goal_id, []);
  }
  progressByGoal.get(entry.goal_id).push(entry);
}

// Attach progress to goals
const goalsWithProgress = goals.map(goal => ({
  ...goal,
  recentProgress: progressByGoal.get(goal.id) || []
}));

return res.json({ group, goals: goalsWithProgress });
```

**Key Principles:**
1. **Fetch related data in bulk**, not in loops
2. **Use JOINs** for small related datasets
3. **Use separate queries + in-memory grouping** for complex relationships
4. **Always use WHERE clauses** to filter server-side
5. **Add LIMIT** to prevent massive result sets

### 13.5 Caching (Future Enhancement)

```typescript
import Redis from 'ioredis';

const redis = new Redis(process.env.REDIS_URL);

// Cache group data
export async function getCachedGroup(groupId: string) {
  const cached = await redis.get(`group:${groupId}`);
  if (cached) {
    return JSON.parse(cached);
  }
  
  const group = await db
    .selectFrom('groups')
    .selectAll()
    .where('id', '=', groupId)
    .executeTakeFirst();
    
  if (group) {
    await redis.setex(`group:${groupId}`, 300, JSON.stringify(group));
  }
  
  return group;
}
```

---

