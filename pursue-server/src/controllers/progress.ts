import type { Response, NextFunction } from 'express';
import {
  startOfDay,
  startOfWeek,
  startOfMonth,
  startOfYear,
} from 'date-fns';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { CreateProgressSchema } from '../validations/progress.js';
import {
  ensureGoalExists,
  requireGroupMember,
  requireActiveGroupMember,
} from '../services/authorization.js';
import { canUserWriteInGroup } from '../services/subscription.service.js';
import { createGroupActivity, ACTIVITY_TYPES } from '../services/activity.service.js';
import { checkMilestones } from '../services/notification.service.js';
import { sendToTopic, buildTopicName } from '../services/fcm.service.js';
import { computeChallengeWindowStatus } from '../utils/timezone.js';
import { checkTextContent } from '../services/openai-moderation.service.js';

// Helper: Format date to YYYY-MM-DD string
function formatPeriodStart(date: Date | string): string {
  if (typeof date === 'string') {
    // Already a string, ensure it's YYYY-MM-DD format
    return date.slice(0, 10);
  }
  const d = date as Date;
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// Helper: Calculate period_start based on cadence and user_date
function calculatePeriodStart(cadence: string, userDateStr: string): string {
  // Parse user_date as local date (not UTC)
  const [year, month, day] = userDateStr.split('-').map(Number);
  const userDate = new Date(year, month - 1, day);

  let periodStart: Date;

  switch (cadence) {
    case 'daily':
      // Daily: use user_date as-is
      periodStart = startOfDay(userDate);
      break;
    case 'weekly':
      // Weekly: Monday of that week (week starts on Monday)
      periodStart = startOfWeek(userDate, { weekStartsOn: 1 });
      break;
    case 'monthly':
      // Monthly: First day of month
      periodStart = startOfMonth(userDate);
      break;
    case 'yearly':
      // Yearly: January 1st of that year
      periodStart = startOfYear(userDate);
      break;
    default:
      // Default to daily
      periodStart = startOfDay(userDate);
  }

  return formatPeriodStart(periodStart);
}

// POST /api/progress
export async function createProgress(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Validate input
    const data = await CreateProgressSchema.parseAsync(req.body);

    // Verify goal exists and is not deleted
    const goal = await ensureGoalExists(data.goal_id);

    // Verify user is active member of goal's group
    await requireActiveGroupMember(req.user.id, goal.group_id);

    const groupMeta = await db
      .selectFrom('groups')
      .select(['is_challenge', 'challenge_status', 'challenge_start_date', 'challenge_end_date'])
      .where('id', '=', goal.group_id)
      .executeTakeFirst();

    if (groupMeta?.is_challenge) {
      const windowStatus = computeChallengeWindowStatus(
        data.user_date,
        groupMeta.challenge_start_date,
        groupMeta.challenge_end_date,
        groupMeta.challenge_status
      );

      if (windowStatus !== 'active') {
        throw new ApplicationError(
          'Progress can only be logged for active challenges.',
          403,
          'CHALLENGE_NOT_ACTIVE'
        );
      }
    }

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

    // Check text content against OpenAI moderation
    if (data.log_title) await checkTextContent(data.log_title);
    if (data.note) await checkTextContent(data.note);

    // Calculate period_start based on cadence
    const periodStart = calculatePeriodStart(goal.cadence, data.user_date);

    // Check for duplicate entry (same goal_id, user_id, period_start)
    const existingEntry = await db
      .selectFrom('progress_entries')
      .select('id')
      .where('goal_id', '=', data.goal_id)
      .where('user_id', '=', req.user.id)
      .where('period_start', '=', periodStart)
      .executeTakeFirst();

    if (existingEntry) {
      throw new ApplicationError(
        'Duplicate entry: progress already logged for this period',
        400,
        'DUPLICATE_ENTRY'
      );
    }

    // Create progress entry
    const entry = await db
      .insertInto('progress_entries')
      .values({
        goal_id: data.goal_id,
        user_id: req.user.id,
        value: data.value,
        note: data.note ?? null,
        log_title: data.log_title ?? null,
        period_start: periodStart,
        user_timezone: data.user_timezone,
      })
      .returning([
        'id',
        'goal_id',
        'user_id',
        'value',
        'note',
        'log_title',
        'period_start',
        'logged_at',
      ])
      .executeTakeFirstOrThrow();

    // Sync user's cached timezone for smart reminders (if changed)
    if (data.user_timezone) {
      await db
        .updateTable('users')
        .set({ timezone: data.user_timezone })
        .where('id', '=', req.user.id)
        .where((eb) =>
          eb.or([
            eb('timezone', 'is', null),
            eb('timezone', '!=', data.user_timezone),
          ])
        )
        .execute();
    }

    // Create group activity entry
    await createGroupActivity(
      goal.group_id,
      ACTIVITY_TYPES.PROGRESS_LOGGED,
      req.user.id,
      {
        goal_id: goal.id,
        goal_title: goal.title,
        value: data.value,
        progress_entry_id: entry.id,
        log_title: data.log_title ?? null,
      }
    );

    // Get user display name for notification
    const user = await db
      .selectFrom('users')
      .select('display_name')
      .where('id', '=', req.user.id)
      .executeTakeFirstOrThrow();

    // Get group name for notification
    const group = await db
      .selectFrom('groups')
      .select('name')
      .where('id', '=', goal.group_id)
      .executeTakeFirstOrThrow();

    // Send FCM notification to topic subscribers (progress_logs)
    await sendToTopic(
      buildTopicName(goal.group_id, 'progress_logs'),
      {
        title: group.name,
        body: `${user.display_name} completed ${goal.title}`,
      },
      {
        type: 'progress_logged',
        goal_id: goal.id,
        group_id: goal.group_id,
      }
    );

    await checkMilestones(req.user.id, goal.id, goal.group_id);

    res.status(201).json({
      id: entry.id,
      goal_id: entry.goal_id,
      user_id: entry.user_id,
      value: Number(entry.value),
      note: entry.note,
      log_title: entry.log_title,
      period_start: formatPeriodStart(entry.period_start),
      logged_at: entry.logged_at,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/progress/:entry_id
export async function getProgress(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const entryId = String(req.params.entry_id);

    // Fetch entry with goal info
    const entry = await db
      .selectFrom('progress_entries')
      .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
      .select([
        'progress_entries.id',
        'progress_entries.goal_id',
        'progress_entries.user_id',
        'progress_entries.value',
        'progress_entries.note',
        'progress_entries.log_title',
        'progress_entries.period_start',
        'progress_entries.logged_at',
        'progress_entries.moderation_status',
        'goals.group_id',
      ])
      .where('progress_entries.id', '=', entryId)
      .where('goals.deleted_at', 'is', null) // Ensure goal is not deleted
      .executeTakeFirst();

    if (!entry) {
      throw new ApplicationError('Progress entry not found', 404, 'NOT_FOUND');
    }

    // Apply moderation visibility rules
    if (entry.moderation_status === 'removed') {
      throw new ApplicationError('Progress entry not found', 404, 'NOT_FOUND');
    }

    // Verify user owns entry OR is member of goal's group
    const isOwner = entry.user_id === req.user.id;
    if (!isOwner) {
      // Check if user is member of the group
      await requireGroupMember(req.user.id, entry.group_id);

      // Non-authors cannot see hidden entries
      if (entry.moderation_status === 'hidden') {
        throw new ApplicationError('Progress entry not found', 404, 'NOT_FOUND');
      }
    }

    res.status(200).json({
      id: entry.id,
      goal_id: entry.goal_id,
      user_id: entry.user_id,
      value: Number(entry.value),
      note: entry.note,
      log_title: entry.log_title,
      period_start: formatPeriodStart(entry.period_start),
      logged_at: entry.logged_at,
    });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/progress/:entry_id
export async function deleteProgress(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const entryId = String(req.params.entry_id);

    // Fetch entry with goal to get group_id for write check
    const entry = await db
      .selectFrom('progress_entries')
      .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
      .select([
        'progress_entries.id',
        'progress_entries.user_id',
        'goals.group_id as group_id',
      ])
      .where('progress_entries.id', '=', entryId)
      .where('goals.deleted_at', 'is', null)
      .executeTakeFirst();

    if (!entry) {
      throw new ApplicationError('Progress entry not found', 404, 'NOT_FOUND');
    }

    const writeCheck = await canUserWriteInGroup(req.user.id, entry.group_id);
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

    // Verify user owns the entry
    if (entry.user_id !== req.user.id) {
      throw new ApplicationError(
        'You can only delete your own progress entries',
        403,
        'FORBIDDEN'
      );
    }

    // Delete entry (use progress_entries.id only)
    await db
      .deleteFrom('progress_entries')
      .where('id', '=', entry.id)
      .execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}
