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
import { getSignedUrl } from '../services/gcs.service.js';
import { daysToBitmask, bitmaskToDays, serializeActiveDays } from '../utils/activeDays.js';
import { validateGoalName, assertValidName } from '../moderation/validate-name.js';
import { checkTextContent } from '../services/openai-moderation.service.js';

function formatPeriodStart(ps: string | Date): string {
  if (typeof ps === 'string') return ps.slice(0, 10);
  const d = ps as Date;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// Helper: Get period bounds from a date string (YYYY-MM-DD) without timezone conversion
function getPeriodBoundsForDateStr(cadence: string, dateStr: string): { start: string; end: string } {
  const [year, month, day] = dateStr.split('-').map(Number);
  const date = new Date(year, month - 1, day);
  const bounds = getPeriodBounds(cadence, date);
  return {
    start: formatPeriodStart(bounds.start),
    end: formatPeriodStart(bounds.end),
  };
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
  if (goal.metric_type === 'binary' || goal.metric_type === 'journal') {
    // For binary/journal: count number of entries (days completed)
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
function calculateTotal(goal: Goal, _period?: { start: string; end: string }): number {
  if (goal.metric_type === 'binary' || goal.metric_type === 'journal') {
    // For binary/journal: target is the goal's target_value (e.g., 3x per week), default 1
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
    log_title_prompt: string | null;
    active_days: number[] | null;
    active_days_label: string;
    active_days_count: number;
    created_by_user_id: string | null;
    created_at: Date;
    archived_at: Date | null;
  }>,
  currentUserId: string,
  groupId: string,
  userTimezone?: string
): Promise<Array<{
  id: string;
  group_id: string;
  title: string;
  description: string | null;
  cadence: string;
  metric_type: string;
  target_value: number | null;
  unit: string | null;
  log_title_prompt: string | null;
  active_days: number[] | null;
  active_days_label: string;
  active_days_count: number;
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

  // Calculate today's date in user's timezone (or server time if not provided)
  let todayStr: string;
  if (userTimezone) {
    const formatter = new Intl.DateTimeFormat('en-CA', {
      timeZone: userTimezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    });
    todayStr = formatter.format(now);
  } else {
    todayStr = formatPeriodStart(now);
  }

  // Calculate period bounds for each goal using date string (for progress filtering)
  // Also calculate Date-based bounds for API response (backwards compatible)
  const periodBounds = goals.map((goal) => {
    const strBounds = getPeriodBoundsForDateStr(goal.cadence, todayStr);
    // Parse the date strings back to Date objects for API response
    const [startYear, startMonth, startDay] = strBounds.start.split('-').map(Number);
    const [endYear, endMonth, endDay] = strBounds.end.split('-').map(Number);
    return {
      goal_id: goal.id,
      start: strBounds.start,
      end: strBounds.end,
      // Date objects for API response (local time interpretation)
      startDate: new Date(startYear, startMonth - 1, startDay, 0, 0, 0, 0),
      endDate: new Date(endYear, endMonth - 1, endDay, 23, 59, 59, 999),
    };
  });

  // Find the earliest start date across all periods
  const earliestStart = periodBounds.reduce((min, p) => (p.start < min ? p.start : min), periodBounds[0].start);

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
    .where('progress_entries.period_start', '>=', earliestStart)
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
    const currentPeriodEntries = goalProgressEntries.filter((entry) => {
      const entryDateStr = formatPeriodStart(entry.period_start);
      return entryDateStr >= periodBound.start && entryDateStr <= periodBound.end;
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
        start_date: periodBound.startDate.toISOString(),
        end_date: periodBound.endDate.toISOString(),
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

    const groupMeta = await db
      .selectFrom('groups')
      .select(['is_challenge', 'challenge_status'])
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    if (groupMeta.is_challenge && groupMeta.challenge_status === 'active') {
      throw new ApplicationError(
        'Goals cannot be modified while a challenge is active',
        403,
        'CHALLENGE_GOALS_LOCKED'
      );
    }

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

    // Validate goal title against profanity and OpenAI moderation
    assertValidName(validateGoalName(data.title));
    await checkTextContent(data.title);

    const activeDaysBitmask = data.active_days ? daysToBitmask(data.active_days) : null;

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
        active_days: activeDaysBitmask,
        log_title_prompt: data.log_title_prompt ?? null,
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
        'active_days',
        'log_title_prompt',
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
      log_title_prompt: goal.log_title_prompt,
      ...serializeActiveDays(goal.active_days),
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
      user_timezone: req.query.user_timezone,
      language: req.query.language,
    });
    const cadence = parsed.success ? parsed.data.cadence : undefined;
    const archived = parsed.success ? parsed.data.archived : undefined;
    const includeProgress = parsed.success ? parsed.data.include_progress : undefined;
    const userTimezone = parsed.success ? parsed.data.user_timezone : undefined;
    const language = parsed.success ? parsed.data.language : undefined;
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
        'active_days',
        'log_title_prompt',
        'template_goal_id',
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

    let goals = rows.map((r) => ({
      id: r.id,
      group_id: r.group_id,
      title: r.title,
      description: r.description,
      cadence: r.cadence,
      metric_type: r.metric_type,
      target_value: r.target_value,
      unit: r.unit,
      log_title_prompt: r.log_title_prompt,
      ...serializeActiveDays(r.active_days),
      created_by_user_id: r.created_by_user_id,
      created_at: r.created_at,
      archived_at: r.deleted_at ?? null,
      template_goal_id: r.template_goal_id ?? null,
    }));

    // Translation overlay for non-English locales
    if (language && language !== 'en') {
      const templateGoalIds = goals
        .map(g => g.template_goal_id)
        .filter((id): id is string => id != null);

      if (templateGoalIds.length > 0) {
        const translations = await db
          .selectFrom('group_template_goal_translations')
          .select(['goal_id', 'title', 'description', 'log_title_prompt'])
          .where('goal_id', 'in', templateGoalIds)
          .where('language', '=', language)
          .execute();

        const tMap = new Map(translations.map(t => [t.goal_id, t]));
        goals = goals.map(g => {
          const t = g.template_goal_id ? tMap.get(g.template_goal_id) : undefined;
          if (!t) return g;
          return {
            ...g,
            title: t.title,
            description: t.description ?? g.description,
            log_title_prompt: t.log_title_prompt ?? g.log_title_prompt,
          };
        });
      }
    }

    // Strip internal template_goal_id before sending response
    const goalsForResponse = goals.map(({ template_goal_id: _tgid, ...rest }) => rest);

    // If include_progress requested, attach progress data
    if (includeProgress === 'true' && goalsForResponse.length > 0) {
      const goalsWithProgress = await attachProgressToGoals(goalsForResponse, req.user.id, group_id, userTimezone);

      res.status(200).json({
        goals: goalsWithProgress,
        total: goalsWithProgress.length,
      });
      return;
    }

    // Default: return goals without progress
    res.status(200).json({ goals: goalsForResponse, total: goalsForResponse.length });
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
      ...serializeActiveDays(goal.active_days),
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

    // Validate goal title against profanity and OpenAI moderation
    if (data.title !== undefined) {
      assertValidName(validateGoalName(data.title));
      await checkTextContent(data.title);
    }

    const updates: Record<string, string | number | null> = {};
    if (data.title !== undefined) updates.title = data.title;
    if (data.description !== undefined) updates.description = data.description ?? null;

    // Handle active_days update
    if (data.active_days !== undefined) {
      if (data.active_days !== null && goal.cadence !== 'daily') {
        throw new ApplicationError(
          'Active days can only be set for daily goals',
          400,
          'VALIDATION_ERROR'
        );
      }
      const newBitmask = data.active_days !== null ? daysToBitmask(data.active_days) : null;
      updates.active_days = newBitmask;
    }

    if (Object.keys(updates).length === 0) {
      res.status(200).json({
        id: goal.id,
        title: goal.title,
        description: goal.description,
        ...serializeActiveDays(goal.active_days),
      });
      return;
    }

    const updated = await db
      .updateTable('goals')
      .set(updates)
      .where('id', '=', goal.id)
      .returning(['id', 'title', 'description', 'active_days'])
      .executeTakeFirstOrThrow();

    // Log activity if active_days changed
    if (data.active_days !== undefined) {
      const oldDays = serializeActiveDays(goal.active_days);
      const newDays = serializeActiveDays(updated.active_days);
      await createGroupActivity(goal.group_id, ACTIVITY_TYPES.GOAL_UPDATED, req.user.id, {
        goal_id: goal.id,
        field: 'active_days',
        old_value: oldDays.active_days,
        new_value: newDays.active_days,
      });
    }

    res.status(200).json({
      id: updated.id,
      title: updated.title,
      description: updated.description,
      ...serializeActiveDays(updated.active_days),
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
        'progress_entries.log_title',
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

    const entryIds = rows.map((r) => r.id);

    // Batch fetch photos for all progress entries
    const now = new Date();
    const photosMap = new Map<string, {
      id: string;
      gcs_object_path: string;
      width_px: number;
      height_px: number;
      expires_at: Date;
    }>();

    if (entryIds.length > 0) {
      const photos = await db
        .selectFrom('progress_photos')
        .select(['id', 'progress_entry_id', 'gcs_object_path', 'width_px', 'height_px', 'expires_at', 'gcs_deleted_at'])
        .where('progress_entry_id', 'in', entryIds)
        .execute();

      for (const photo of photos) {
        const expiresAt = new Date(photo.expires_at);
        if (expiresAt > now && !photo.gcs_deleted_at) {
          photosMap.set(photo.progress_entry_id, {
            id: photo.id,
            gcs_object_path: photo.gcs_object_path,
            width_px: photo.width_px,
            height_px: photo.height_px,
            expires_at: expiresAt,
          });
        }
      }
    }

    // Generate signed URLs for all photos
    const signedUrls = new Map<string, string>();
    const urlPromises = Array.from(photosMap.entries()).map(async ([entryId, photo]) => {
      try {
        const url = await getSignedUrl(photo.gcs_object_path);
        signedUrls.set(entryId, url);
      } catch (err) {
        // Log but don't fail - entry will have photo: null
      }
    });
    await Promise.all(urlPromises);

    const byUser = new Map<string, { user_id: string; display_name: string; entries: Array<{ id: string; value: number; note: string | null; period_start: string; logged_at: Date; photo: { id: string; url: string; width: number; height: number; expires_at: Date } | null }> }>();
    for (const r of rows) {
      const photoMeta = photosMap.get(r.id);
      const signedUrl = signedUrls.get(r.id);
      const photo = photoMeta && signedUrl
        ? { id: photoMeta.id, url: signedUrl, width: photoMeta.width_px, height: photoMeta.height_px, expires_at: photoMeta.expires_at }
        : null;

      const ent = {
        id: r.id,
        value: Number(r.value),
        note: r.note,
        log_title: r.log_title,
        period_start: formatPeriodStart(r.period_start),
        logged_at: r.logged_at,
        photo,
      };
      const existing = byUser.get(r.user_id);
      if (existing) {
        existing.entries.push(ent);
      } else {
        byUser.set(r.user_id, { user_id: r.user_id, display_name: r.display_name, entries: [ent] });
      }
    }

    res.status(200).json({
      goal: { id: goal.id, title: goal.title, cadence: goal.cadence, ...serializeActiveDays(goal.active_days) },
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
      .select(['id', 'value', 'note', 'log_title', 'period_start', 'logged_at'])
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
      ...serializeActiveDays(goal.active_days),
      entries: rows.map((r) => ({
        id: r.id,
        value: Number(r.value),
        note: r.note,
        log_title: r.log_title,
        period_start: formatPeriodStart(r.period_start),
        logged_at: r.logged_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}
