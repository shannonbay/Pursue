import type { Response, NextFunction } from 'express';
import { sql } from 'kysely';
import {
  startOfDay,
  endOfDay,
  startOfWeek,
  endOfWeek,
  startOfMonth,
  endOfMonth,
  startOfYear,
  endOfYear,
} from 'date-fns';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import type { Goal, ProgressEntry } from '../database/types.js';
import {
  CreateGoalSchema,
  UpdateGoalSchema,
  ProgressQuerySchema,
  ListGoalsQuerySchema,
} from '../validations/goals.js';
import {
  ensureGroupExists,
  ensureGoalExists,
  requireGroupMember,
  requireActiveGroupMember,
  requireGroupAdmin,
} from '../services/authorization.js';
import { canUserWriteInGroup } from '../services/subscription.service.js';
import { createGroupActivity, ACTIVITY_TYPES } from '../services/activity.service.js';
import { sendToTopic, buildTopicName } from '../services/fcm.service.js';

function formatPeriodStart(ps: string | Date): string {
  if (typeof ps === 'string') return ps.slice(0, 10);
  const d = ps as Date;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// Helper: Get period start/end based on cadence
function getPeriodBounds(cadence: string, date: Date): { start: Date; end: Date } {
  switch (cadence) {
    case 'daily':
      return {
        start: startOfDay(date),
        end: endOfDay(date),
      };
    case 'weekly':
      return {
        start: startOfWeek(date, { weekStartsOn: 1 }), // Monday
        end: endOfWeek(date, { weekStartsOn: 1 }),
      };
    case 'monthly':
      return {
        start: startOfMonth(date),
        end: endOfMonth(date),
      };
    case 'yearly':
      return {
        start: startOfYear(date),
        end: endOfYear(date),
      };
    default:
      return {
        start: startOfDay(date),
        end: endOfDay(date),
      };
  }
}

// Helper: Calculate completed count/value
function calculateCompleted(goal: Goal, entries: ProgressEntry[]): number {
  if (goal.metric_type === 'binary') {
    // For binary: count number of entries (days completed)
    return entries.length;
  }

  if (goal.metric_type === 'numeric' || goal.metric_type === 'duration') {
    // For numeric: sum of all values (convert to number since DECIMAL returns as string)
    return entries.reduce((sum, entry) => {
      const val = typeof entry.value === 'string' ? parseFloat(entry.value) : Number(entry.value) || 0;
      return sum + val;
    }, 0);
  }

  return 0;
}

// Helper: Calculate total target for period
function calculateTotal(goal: Goal, period: { start: Date; end: Date }): number {
  if (goal.metric_type === 'binary') {
    // For binary: target is the goal's target_value (e.g., 3x per week)
    // Convert to number since DECIMAL returns as string
    const target = goal.target_value;
    return target != null ? (typeof target === 'string' ? parseFloat(target) : Number(target)) : 1;
  }

  if (goal.metric_type === 'numeric' || goal.metric_type === 'duration') {
    // For numeric: target_value is the goal (e.g., 50 pages per week)
    // Convert to number since DECIMAL returns as string
    const target = goal.target_value;
    return target != null ? (typeof target === 'string' ? parseFloat(target) : Number(target)) : 0;
  }

  return 0;
}

// Helper function to attach progress in 2 additional queries (not N+1!)
async function attachProgressToGoals(
  goals: Array<{
    id: string;
    group_id: string;
    title: string;
    description: string | null;
    cadence: string;
    metric_type: string;
    target_value: number | null;
    unit: string | null;
    created_by_user_id: string | null;
    created_at: Date;
    archived_at: Date | null;
  }>,
  currentUserId: string,
  groupId: string
): Promise<Array<{
  id: string;
  group_id: string;
  title: string;
  description: string | null;
  cadence: string;
  metric_type: string;
  target_value: number | null;
  unit: string | null;
  created_by_user_id: string | null;
  created_at: Date;
  archived_at: Date | null;
  current_period_progress: {
    start_date: string;
    end_date: string;
    period_type: string;
    user_progress: {
      completed: number;
      total: number;
      percentage: number;
      entries: Array<{ date: string; value: number }>;
    };
    member_progress: Array<{
      user_id: string;
      display_name: string;
      avatar_url: string | null;
      completed: number;
      percentage: number;
    }>;
  };
}>> {
  if (goals.length === 0) return goals as any;

  const goalIds = goals.map((g) => g.id);
  const now = new Date();

  // Calculate period bounds for each goal
  const periodBounds = goals.map((goal) => {
    const bounds = getPeriodBounds(goal.cadence, now);
    return {
      goal_id: goal.id,
      start: bounds.start,
      end: bounds.end,
    };
  });

  // Find the earliest start date across all periods
  const earliestStart = new Date(Math.min(...periodBounds.map((p) => p.start.getTime())));

  // QUERY 2: Fetch all progress entries for all goals in ONE query
  // Using SQL IN clause - very efficient with proper index
  const allProgressEntries = await db
    .selectFrom('progress_entries')
    .select([
      'progress_entries.id',
      'progress_entries.goal_id',
      'progress_entries.user_id',
      'progress_entries.value',
      'progress_entries.period_start',
      'progress_entries.note',
      'progress_entries.created_at',
    ])
    .where('progress_entries.goal_id', 'in', goalIds)
    .where('progress_entries.period_start', '>=', formatPeriodStart(earliestStart))
    .orderBy('progress_entries.period_start', 'desc')
    .execute();

  // QUERY 3: Fetch all group members in ONE query
  const members = await db
    .selectFrom('group_memberships')
    .innerJoin('users', 'group_memberships.user_id', 'users.id')
    .select([
      'users.id',
      'users.display_name',
      sql<boolean>`users.avatar_data IS NOT NULL`.as('has_avatar'),
    ])
    .where('group_memberships.group_id', '=', groupId)
    .execute();

  // Group progress entries by goal_id (in-memory, O(n) - fast)
  const progressByGoal: Record<string, typeof allProgressEntries> = {};
  for (const entry of allProgressEntries) {
    if (!progressByGoal[entry.goal_id]) {
      progressByGoal[entry.goal_id] = [];
    }
    progressByGoal[entry.goal_id].push(entry);
  }

  // Attach progress to each goal
  return goals.map((goal) => {
    const periodBound = periodBounds.find((p) => p.goal_id === goal.id)!;
    const goalProgressEntries = progressByGoal[goal.id] || [];

    // Filter entries for this goal's current period
    // period_start is stored as DATE string (YYYY-MM-DD)
    const periodStartStr = formatPeriodStart(periodBound.start);
    const periodEndStr = formatPeriodStart(periodBound.end);
    const currentPeriodEntries = goalProgressEntries.filter((entry) => {
      const entryDateStr = formatPeriodStart(entry.period_start);
      return entryDateStr >= periodStartStr && entryDateStr <= periodEndStr;
    });

    // Calculate current user's progress
    const userEntries = currentPeriodEntries.filter((e) => e.user_id === currentUserId);

    const userCompleted = calculateCompleted(goal as unknown as Goal, userEntries as ProgressEntry[]);
    const total = calculateTotal(goal as unknown as Goal, periodBound);
    const userPercentage = total > 0 ? Math.round((userCompleted / total) * 100) : 0;

    // Calculate each member's progress
    const memberProgress = members.map((member) => {
      const memberEntries = currentPeriodEntries.filter((e) => e.user_id === member.id);
      const completed = calculateCompleted(goal as unknown as Goal, memberEntries as ProgressEntry[]);
      const percentage = total > 0 ? Math.round((completed / total) * 100) : 0;

      return {
        user_id: member.id,
        display_name: member.display_name,
        avatar_url: member.has_avatar ? `/api/users/${member.id}/avatar` : null,
        completed,
        percentage,
      };
    });

    return {
      ...goal,
      current_period_progress: {
        start_date: periodBound.start.toISOString(),
        end_date: periodBound.end.toISOString(),
        period_type: goal.cadence,
        user_progress: {
          completed: userCompleted,
          total,
          percentage: userPercentage,
          entries: userEntries.map((e) => ({
            date: formatPeriodStart(e.period_start),
            value: Number(e.value) || 1,
          })),
        },
        member_progress: memberProgress,
      },
    };
  });
}

// POST /api/groups/:group_id/goals
export async function createGoal(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    const writeCheck = await canUserWriteInGroup(req.user.id, group_id);
    if (!writeCheck.allowed) {
      if (writeCheck.reason === 'group_selection_required') {
        throw new ApplicationError(
          'Select which group to keep in your subscription settings.',
          403,
          'SUBSCRIPTION_GROUP_SELECTION_REQUIRED'
        );
      }
      const msg = writeCheck.read_only_until
        ? `This group is read-only until ${writeCheck.read_only_until.toISOString().slice(0, 10)}.`
        : 'This group is read-only.';
      throw new ApplicationError(msg, 403, 'GROUP_READ_ONLY');
    }

    const data = CreateGoalSchema.parse(req.body);

    const goal = await db
      .insertInto('goals')
      .values({
        group_id,
        title: data.title,
        description: data.description ?? null,
        cadence: data.cadence,
        metric_type: data.metric_type,
        target_value: data.target_value ?? null,
        unit: data.unit ?? null,
        created_by_user_id: req.user.id,
      })
      .returning([
        'id',
        'group_id',
        'title',
        'description',
        'cadence',
        'metric_type',
        'target_value',
        'unit',
        'created_by_user_id',
        'created_at',
      ])
      .executeTakeFirstOrThrow();

    await createGroupActivity(group_id, ACTIVITY_TYPES.GOAL_ADDED, req.user.id, {
      goal_id: goal.id,
    });

    const group = await db
      .selectFrom('groups')
      .select('name')
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    await sendToTopic(
      buildTopicName(group_id, 'group_events'),
      { title: group.name, body: `New goal: ${goal.title}` },
      { type: 'goal_added', goal_id: goal.id, group_id }
    );

    res.status(201).json({
      id: goal.id,
      group_id: goal.group_id,
      title: goal.title,
      description: goal.description,
      cadence: goal.cadence,
      metric_type: goal.metric_type,
      target_value: goal.target_value,
      unit: goal.unit,
      created_by_user_id: goal.created_by_user_id,
      created_at: goal.created_at,
      archived_at: null,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/goals
export async function listGoals(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    await ensureGroupExists(group_id);
    await requireActiveGroupMember(req.user.id, group_id);

    const parsed = ListGoalsQuerySchema.safeParse({
      cadence: req.query.cadence,
      archived: req.query.archived,
      include_progress: req.query.include_progress,
    });
    const cadence = parsed.success ? parsed.data.cadence : undefined;
    const archived = parsed.success ? parsed.data.archived : undefined;
    const includeProgress = parsed.success ? parsed.data.include_progress : undefined;
    const includeArchived = archived === 'true';

    let query = db
      .selectFrom('goals')
      .select([
        'id',
        'group_id',
        'title',
        'description',
        'cadence',
        'metric_type',
        'target_value',
        'unit',
        'created_by_user_id',
        'created_at',
        'deleted_at',
      ])
      .where('group_id', '=', group_id);

    if (cadence) {
      query = query.where('cadence', '=', cadence);
    }
    if (!includeArchived) {
      query = query.where('deleted_at', 'is', null);
    }

    const rows = await query
      .orderBy(sql`deleted_at ASC NULLS FIRST`)
      .orderBy('created_at', 'desc')
      .execute();

    const goals = rows.map((r) => ({
      id: r.id,
      group_id: r.group_id,
      title: r.title,
      description: r.description,
      cadence: r.cadence,
      metric_type: r.metric_type,
      target_value: r.target_value,
      unit: r.unit,
      created_by_user_id: r.created_by_user_id,
      created_at: r.created_at,
      archived_at: r.deleted_at ?? null,
    }));

    // If include_progress requested, attach progress data
    if (includeProgress === 'true' && goals.length > 0) {
      const goalsWithProgress = await attachProgressToGoals(goals, req.user.id, group_id);

      res.status(200).json({
        goals: goalsWithProgress,
        total: goalsWithProgress.length,
      });
      return;
    }

    // Default: return goals without progress
    res.status(200).json({ goals, total: goals.length });
  } catch (error) {
    next(error);
  }
}

// GET /api/goals/:goal_id
export async function getGoal(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goal = await ensureGoalExists(String(req.params.goal_id));
    await requireGroupMember(req.user.id, goal.group_id);

    res.status(200).json({
      id: goal.id,
      group_id: goal.group_id,
      title: goal.title,
      description: goal.description,
      cadence: goal.cadence,
      metric_type: goal.metric_type,
      created_at: goal.created_at,
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/goals/:goal_id
export async function updateGoal(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goal = await ensureGoalExists(String(req.params.goal_id));
    await requireGroupAdmin(req.user.id, goal.group_id);

    const writeCheck = await canUserWriteInGroup(req.user.id, goal.group_id);
    if (!writeCheck.allowed) {
      if (writeCheck.reason === 'group_selection_required') {
        throw new ApplicationError(
          'Select which group to keep in your subscription settings.',
          403,
          'SUBSCRIPTION_GROUP_SELECTION_REQUIRED'
        );
      }
      const msg = writeCheck.read_only_until
        ? `This group is read-only until ${writeCheck.read_only_until.toISOString().slice(0, 10)}.`
        : 'This group is read-only.';
      throw new ApplicationError(msg, 403, 'GROUP_READ_ONLY');
    }

    const data = UpdateGoalSchema.parse(req.body);

    const updates: Record<string, string | null> = {};
    if (data.title !== undefined) updates.title = data.title;
    if (data.description !== undefined) updates.description = data.description ?? null;

    if (Object.keys(updates).length === 0) {
      res.status(200).json({
        id: goal.id,
        title: goal.title,
        description: goal.description,
      });
      return;
    }

    const updated = await db
      .updateTable('goals')
      .set(updates)
      .where('id', '=', goal.id)
      .returning(['id', 'title', 'description'])
      .executeTakeFirstOrThrow();

    res.status(200).json({
      id: updated.id,
      title: updated.title,
      description: updated.description,
    });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/goals/:goal_id
export async function deleteGoal(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goal = await ensureGoalExists(String(req.params.goal_id));
    await requireGroupAdmin(req.user.id, goal.group_id);

    const writeCheck = await canUserWriteInGroup(req.user.id, goal.group_id);
    if (!writeCheck.allowed) {
      if (writeCheck.reason === 'group_selection_required') {
        throw new ApplicationError(
          'Select which group to keep in your subscription settings.',
          403,
          'SUBSCRIPTION_GROUP_SELECTION_REQUIRED'
        );
      }
      const msg = writeCheck.read_only_until
        ? `This group is read-only until ${writeCheck.read_only_until.toISOString().slice(0, 10)}.`
        : 'This group is read-only.';
      throw new ApplicationError(msg, 403, 'GROUP_READ_ONLY');
    }

    await db
      .updateTable('goals')
      .set({
        deleted_at: new Date(),
        deleted_by_user_id: req.user.id,
      })
      .where('id', '=', goal.id)
      .execute();

    await createGroupActivity(goal.group_id, ACTIVITY_TYPES.GOAL_ARCHIVED, req.user.id, {
      goal_id: goal.id,
    });

    const group = await db
      .selectFrom('groups')
      .select('name')
      .where('id', '=', goal.group_id)
      .executeTakeFirstOrThrow();

    await sendToTopic(
      buildTopicName(goal.group_id, 'group_events'),
      { title: group.name, body: 'Goal archived' },
      { type: 'goal_archived', goal_id: goal.id, group_id: goal.group_id }
    );

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// GET /api/goals/:goal_id/progress
export async function getProgress(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goal = await ensureGoalExists(String(req.params.goal_id));
    await requireGroupMember(req.user.id, goal.group_id);

    const parsed = ProgressQuerySchema.safeParse({
      start_date: req.query.start_date,
      end_date: req.query.end_date,
    });
    if (!parsed.success) {
      throw new ApplicationError('Invalid start_date or end_date', 400, 'VALIDATION_ERROR');
    }
    const { start_date, end_date } = parsed.data;

    let query = db
      .selectFrom('progress_entries')
      .innerJoin('users', 'progress_entries.user_id', 'users.id')
      .select([
        'progress_entries.id',
        'progress_entries.value',
        'progress_entries.note',
        'progress_entries.period_start',
        'progress_entries.logged_at',
        'progress_entries.user_id',
        'users.display_name',
      ])
      .where('progress_entries.goal_id', '=', goal.id);

    if (start_date) {
      query = query.where('progress_entries.period_start', '>=', start_date);
    }
    if (end_date) {
      query = query.where('progress_entries.period_start', '<=', end_date);
    }

    const rows = await query.orderBy('progress_entries.user_id').orderBy('progress_entries.period_start', 'asc').execute();

    const byUser = new Map<string, { user_id: string; display_name: string; entries: Array<{ id: string; value: number; note: string | null; period_start: string; logged_at: Date }> }>();
    for (const r of rows) {
      const ent = {
        id: r.id,
        value: Number(r.value),
        note: r.note,
        period_start: formatPeriodStart(r.period_start),
        logged_at: r.logged_at,
      };
      const existing = byUser.get(r.user_id);
      if (existing) {
        existing.entries.push(ent);
      } else {
        byUser.set(r.user_id, { user_id: r.user_id, display_name: r.display_name, entries: [ent] });
      }
    }

    res.status(200).json({
      goal: { id: goal.id, title: goal.title, cadence: goal.cadence },
      progress: Array.from(byUser.values()),
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/goals/:goal_id/progress/me
export async function getProgressMe(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goal = await ensureGoalExists(String(req.params.goal_id));
    await requireGroupMember(req.user.id, goal.group_id);

    const parsed = ProgressQuerySchema.safeParse({
      start_date: req.query.start_date,
      end_date: req.query.end_date,
    });
    if (!parsed.success) {
      throw new ApplicationError('Invalid start_date or end_date', 400, 'VALIDATION_ERROR');
    }
    const { start_date, end_date } = parsed.data;

    let query = db
      .selectFrom('progress_entries')
      .select(['id', 'value', 'note', 'period_start', 'logged_at'])
      .where('goal_id', '=', goal.id)
      .where('user_id', '=', req.user.id);

    if (start_date) {
      query = query.where('period_start', '>=', start_date);
    }
    if (end_date) {
      query = query.where('period_start', '<=', end_date);
    }

    const rows = await query.orderBy('period_start', 'asc').execute();

    res.status(200).json({
      goal_id: goal.id,
      entries: rows.map((r) => ({
        id: r.id,
        value: Number(r.value),
        note: r.note,
        period_start: formatPeriodStart(r.period_start),
        logged_at: r.logged_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}
