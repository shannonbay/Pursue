/**
 * Smart Reminders Controller
 * 
 * Handles:
 * - Internal job endpoints (scheduled by Cloud Scheduler)
 * - User preference endpoints
 */

import type { Response, NextFunction, Request } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import {
  UpdateReminderPreferencesSchema,
  RecalculatePatternSchema,
} from '../validations/reminders.js';
import { ensureGoalExists, requireGroupMember } from '../services/authorization.js';
import {
  processReminders,
  recalculateAllPatterns,
  updateEffectivenessMetrics,
  calculateAndStorePattern,
  getPattern,
  getDefaultPreferences,
} from '../services/smartReminders/index.js';
import { logger } from '../utils/logger.js';

// =============================================================================
// Internal Job Endpoints (secured by INTERNAL_JOB_KEY)
// =============================================================================

/**
 * Verify internal job key
 */
function verifyInternalJobKey(req: Request): void {
  const jobKey = req.headers['x-internal-job-key'];
  const expectedKey = process.env.INTERNAL_JOB_KEY;

  if (!expectedKey) {
    logger.error('INTERNAL_JOB_KEY environment variable not set');
    throw new ApplicationError('Internal server error', 500, 'INTERNAL_ERROR');
  }

  if (jobKey !== expectedKey) {
    throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
  }
}

/**
 * POST /api/internal/jobs/process-reminders
 * Process and send reminders for all eligible users.
 * Called by Cloud Scheduler every 15 minutes.
 */
export async function processRemindersJob(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    verifyInternalJobKey(req);

    logger.info('Starting process reminders job');

    const result = await processReminders();

    logger.info('Process reminders job completed', result);

    res.status(200).json({
      success: true,
      sent: result.sent,
      skipped: result.skipped,
      errors: result.errors,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/internal/jobs/recalculate-patterns
 * Recalculate logging patterns for all users.
 * Called by Cloud Scheduler weekly.
 */
export async function recalculatePatternsJob(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    verifyInternalJobKey(req);

    logger.info('Starting recalculate patterns job');

    const result = await recalculateAllPatterns();

    logger.info('Recalculate patterns job completed', result);

    res.status(200).json({
      success: true,
      created: result.created,
      updated: result.updated,
      removed: result.removed,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/internal/jobs/update-effectiveness
 * Update effectiveness metrics for recent reminders.
 * Called by Cloud Scheduler daily.
 */
export async function updateEffectivenessJob(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    verifyInternalJobKey(req);

    logger.info('Starting update effectiveness job');

    const result = await updateEffectivenessMetrics();

    logger.info('Update effectiveness job completed', result);

    res.status(200).json({
      success: true,
      updated: result.updated,
    });
  } catch (error) {
    next(error);
  }
}

// =============================================================================
// User-Facing Endpoints
// =============================================================================

/**
 * GET /api/users/me/reminder-preferences
 * Get all reminder preferences for the current user.
 */
export async function getAllReminderPreferences(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const preferences = await db
      .selectFrom('user_reminder_preferences as urp')
      .innerJoin('goals as g', 'urp.goal_id', 'g.id')
      .select([
        'urp.goal_id',
        'g.title as goal_title',
        'urp.enabled',
        'urp.mode',
        'urp.fixed_hour',
        'urp.aggressiveness',
        'urp.quiet_hours_start',
        'urp.quiet_hours_end',
        'urp.last_modified_at',
      ])
      .where('urp.user_id', '=', req.user.id)
      .where('g.deleted_at', 'is', null)
      .execute();

    res.status(200).json({
      preferences: preferences.map((p) => ({
        goal_id: p.goal_id,
        goal_title: p.goal_title,
        enabled: p.enabled,
        mode: p.mode,
        fixed_hour: p.fixed_hour,
        aggressiveness: p.aggressiveness,
        quiet_hours_start: p.quiet_hours_start,
        quiet_hours_end: p.quiet_hours_end,
        last_modified_at: p.last_modified_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/goals/:goal_id/reminder-preferences
 * Get reminder preferences for a specific goal.
 */
export async function getGoalReminderPreferences(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goalId = String(req.params.goal_id);

    // Verify goal exists and user is member
    const goal = await ensureGoalExists(goalId);
    await requireGroupMember(req.user.id, goal.group_id);

    // Get existing preferences or return defaults
    const existing = await db
      .selectFrom('user_reminder_preferences')
      .selectAll()
      .where('user_id', '=', req.user.id)
      .where('goal_id', '=', goalId)
      .executeTakeFirst();

    // Get pattern info
    const pattern = await getPattern(req.user.id, goalId);

    if (!existing) {
      // Return default preferences
      const defaults = getDefaultPreferences();
      res.status(200).json({
        goal_id: goalId,
        enabled: defaults.enabled,
        mode: defaults.mode,
        fixed_hour: defaults.fixedHour,
        aggressiveness: defaults.aggressiveness,
        quiet_hours_start: defaults.quietHoursStart,
        quiet_hours_end: defaults.quietHoursEnd,
        pattern: pattern
          ? {
              typical_hour_start: pattern.typicalHourStart,
              typical_hour_end: pattern.typicalHourEnd,
              confidence_score: pattern.confidenceScore,
              sample_size: pattern.sampleSize,
            }
          : null,
      });
      return;
    }

    res.status(200).json({
      goal_id: goalId,
      enabled: existing.enabled,
      mode: existing.mode,
      fixed_hour: existing.fixed_hour,
      aggressiveness: existing.aggressiveness,
      quiet_hours_start: existing.quiet_hours_start,
      quiet_hours_end: existing.quiet_hours_end,
      last_modified_at: existing.last_modified_at,
      pattern: pattern
        ? {
            typical_hour_start: pattern.typicalHourStart,
            typical_hour_end: pattern.typicalHourEnd,
            confidence_score: pattern.confidenceScore,
            sample_size: pattern.sampleSize,
          }
        : null,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * PUT /api/goals/:goal_id/reminder-preferences
 * Update reminder preferences for a specific goal.
 */
export async function updateGoalReminderPreferences(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goalId = String(req.params.goal_id);

    // Verify goal exists and user is member
    const goal = await ensureGoalExists(goalId);
    await requireGroupMember(req.user.id, goal.group_id);

    // Validate input
    const data = UpdateReminderPreferencesSchema.parse(req.body);

    // Build insert values with proper typing
    const insertValues = {
      user_id: req.user.id,
      goal_id: goalId,
      enabled: data.enabled,
      mode: data.mode,
      fixed_hour: data.fixed_hour,
      aggressiveness: data.aggressiveness,
      quiet_hours_start: data.quiet_hours_start,
      quiet_hours_end: data.quiet_hours_end,
      last_modified_at: new Date().toISOString(),
    };

    // Build update values (exclude PKs and undefined values)
    const updateValues: Record<string, unknown> = {
      last_modified_at: new Date().toISOString(),
    };
    if (data.enabled !== undefined) updateValues.enabled = data.enabled;
    if (data.mode !== undefined) updateValues.mode = data.mode;
    if (data.fixed_hour !== undefined) updateValues.fixed_hour = data.fixed_hour;
    if (data.aggressiveness !== undefined) updateValues.aggressiveness = data.aggressiveness;
    if (data.quiet_hours_start !== undefined) updateValues.quiet_hours_start = data.quiet_hours_start;
    if (data.quiet_hours_end !== undefined) updateValues.quiet_hours_end = data.quiet_hours_end;

    await db
      .insertInto('user_reminder_preferences')
      .values(insertValues)
      .onConflict((oc) =>
        oc.columns(['user_id', 'goal_id']).doUpdateSet(updateValues)
      )
      .execute();

    // Fetch updated record
    const updated = await db
      .selectFrom('user_reminder_preferences')
      .selectAll()
      .where('user_id', '=', req.user.id)
      .where('goal_id', '=', goalId)
      .executeTakeFirstOrThrow();

    res.status(200).json({
      goal_id: goalId,
      enabled: updated.enabled,
      mode: updated.mode,
      fixed_hour: updated.fixed_hour,
      aggressiveness: updated.aggressiveness,
      quiet_hours_start: updated.quiet_hours_start,
      quiet_hours_end: updated.quiet_hours_end,
      last_modified_at: updated.last_modified_at,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/goals/:goal_id/recalculate-pattern
 * Trigger immediate recalculation of the logging pattern for a goal.
 */
export async function recalculateGoalPattern(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const goalId = String(req.params.goal_id);

    // Verify goal exists and user is member
    const goal = await ensureGoalExists(goalId);
    await requireGroupMember(req.user.id, goal.group_id);

    // Validate input
    const data = RecalculatePatternSchema.parse(req.body);

    // Recalculate pattern
    const pattern = await calculateAndStorePattern(
      req.user.id,
      goalId,
      data.user_timezone
    );

    if (!pattern) {
      res.status(200).json({
        goal_id: goalId,
        pattern: null,
        message: 'Insufficient data to calculate pattern (need at least 5 logs)',
      });
      return;
    }

    res.status(200).json({
      goal_id: goalId,
      pattern: {
        typical_hour_start: pattern.typicalHourStart,
        typical_hour_end: pattern.typicalHourEnd,
        confidence_score: pattern.confidenceScore,
        sample_size: pattern.sampleSize,
        last_calculated_at: pattern.lastCalculatedAt,
      },
    });
  } catch (error) {
    next(error);
  }
}
