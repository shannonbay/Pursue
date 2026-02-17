/**
 * Smart Reminders Service - Main Orchestrator
 * 
 * Coordinates pattern calculation, reminder decisions, and notifications.
 */

import { sql } from 'kysely';
import { db } from '../../database/index.js';
import { logger } from '../../utils/logger.js';
import type {
  ProcessResult,
  ReminderCandidate,
  UserPreferences,
  ReminderHistoryEntry,
  EffectivenessData,
  LoggingPattern,
  SocialContext,
  ReminderTier,
} from './types.js';
import { REMINDER_CONFIG } from './types.js';
import { batchGetPatterns } from './patternCalculator.js';
import { shouldSendReminder } from './reminderEngine.js';
import { buildSocialContext } from './socialContextBuilder.js';
import { sendReminder, recordReminderHistory } from './notificationSender.js';

export {
  calculatePattern,
  calculateAndStorePattern,
  recalculateAllPatterns,
  getPattern,
  batchGetPatterns,
} from './patternCalculator.js';

export { shouldSendReminder, getDefaultPreferences } from './reminderEngine.js';
export { buildSocialContext, buildBulkSocialContext } from './socialContextBuilder.js';
export { buildNotification, sendReminder, recordReminderHistory } from './notificationSender.js';
export * from './types.js';

/**
 * Get user's local date string from current UTC time
 */
function getUserLocalDate(timezone: string): string {
  try {
    return new Date().toLocaleDateString('en-CA', { timeZone: timezone });
  } catch {
    return new Date().toISOString().slice(0, 10);
  }
}

/**
 * Find all candidate user-goal pairs that might need reminders
 * 
 * Critical: The "has not logged today" check uses the user's timezone
 */
async function getCandidateGoals(): Promise<ReminderCandidate[]> {
  // Find users with daily goals who haven't logged today (in their timezone)
  const candidates = await db
    .selectFrom('goals as g')
    .innerJoin('group_memberships as gm', 'g.group_id', 'gm.group_id')
    .innerJoin('users as u', 'gm.user_id', 'u.id')
    .innerJoin('groups as gr', 'g.group_id', 'gr.id')
    .leftJoin('user_reminder_preferences as urp', (join) =>
      join
        .onRef('gm.user_id', '=', 'urp.user_id')
        .onRef('g.id', '=', 'urp.goal_id')
    )
    .select([
      'gm.user_id',
      'g.id as goal_id',
      'g.group_id',
      'g.title as goal_title',
      sql<string>`COALESCE(u.timezone, 'UTC')`.as('user_timezone'),
    ])
    .distinct()
    .where('g.cadence', '=', 'daily')
    .where('g.deleted_at', 'is', null)
    .where('u.deleted_at', 'is', null)
    .where('gr.deleted_at', 'is', null)
    .where('gm.status', '=', 'active')
    .where((eb) =>
      eb.or([eb('urp.enabled', 'is', null), eb('urp.enabled', '=', true)])
    )
    .where((eb) =>
      eb.or([
        eb('urp.mode', 'is', null),
        eb('urp.mode', '!=', 'disabled'),
      ])
    )
    .execute();

  // Filter out users who have already logged today (need to check per-user timezone)
  const filteredCandidates: ReminderCandidate[] = [];

  for (const candidate of candidates) {
    const userLocalDate = getUserLocalDate(candidate.user_timezone);

    // Check if user has logged today
    const hasLogged = await db
      .selectFrom('progress_entries')
      .select('id')
      .where('user_id', '=', candidate.user_id)
      .where('goal_id', '=', candidate.goal_id)
      .where('period_start', '=', userLocalDate)
      .executeTakeFirst();

    if (!hasLogged) {
      filteredCandidates.push({
        userId: candidate.user_id,
        goalId: candidate.goal_id,
        groupId: candidate.group_id,
        userTimezone: candidate.user_timezone,
        goalTitle: candidate.goal_title,
      });
    }
  }

  return filteredCandidates;
}

/**
 * Batch get user preferences for multiple user-goal pairs
 */
async function batchGetPreferences(
  userGoalPairs: Array<{ userId: string; goalId: string }>
): Promise<Map<string, UserPreferences>> {
  if (userGoalPairs.length === 0) {
    return new Map();
  }

  const rows = await db
    .selectFrom('user_reminder_preferences')
    .selectAll()
    .where((eb) =>
      eb.or(
        userGoalPairs.map((pair) =>
          eb.and([
            eb('user_id', '=', pair.userId),
            eb('goal_id', '=', pair.goalId),
          ])
        )
      )
    )
    .execute();

  const result = new Map<string, UserPreferences>();
  for (const row of rows) {
    const key = `${row.user_id}:${row.goal_id}`;
    result.set(key, {
      enabled: row.enabled,
      mode: row.mode as UserPreferences['mode'],
      fixedHour: row.fixed_hour,
      aggressiveness: row.aggressiveness as UserPreferences['aggressiveness'],
      quietHoursStart: row.quiet_hours_start,
      quietHoursEnd: row.quiet_hours_end,
    });
  }

  return result;
}

/**
 * Batch get today's reminders for multiple user-goal pairs
 */
async function batchGetTodaysReminders(
  userGoalPairs: Array<{ userId: string; goalId: string; userTimezone: string }>
): Promise<Map<string, ReminderHistoryEntry[]>> {
  if (userGoalPairs.length === 0) {
    return new Map();
  }

  // Get all reminders for today (need to check per-user local date)
  const result = new Map<string, ReminderHistoryEntry[]>();

  // Group by local date to minimize queries
  const dateGroups = new Map<string, typeof userGoalPairs>();
  for (const pair of userGoalPairs) {
    const localDate = getUserLocalDate(pair.userTimezone);
    const key = localDate;
    if (!dateGroups.has(key)) {
      dateGroups.set(key, []);
    }
    dateGroups.get(key)!.push(pair);
  }

  for (const [localDate, pairs] of dateGroups) {
    const rows = await db
      .selectFrom('reminder_history')
      .selectAll()
      .where('sent_at_local_date', '=', localDate)
      .where((eb) =>
        eb.or(
          pairs.map((pair) =>
            eb.and([
              eb('user_id', '=', pair.userId),
              eb('goal_id', '=', pair.goalId),
            ])
          )
        )
      )
      .execute();

    for (const row of rows) {
      const key = `${row.user_id}:${row.goal_id}`;
      if (!result.has(key)) {
        result.set(key, []);
      }
      result.get(key)!.push({
        id: row.id,
        userId: row.user_id,
        goalId: row.goal_id,
        reminderTier: row.reminder_tier as ReminderTier,
        sentAt: row.sent_at,
        sentAtLocalDate: row.sent_at_local_date,
        wasEffective: row.was_effective,
        userTimezone: row.user_timezone,
      });
    }
  }

  return result;
}

/**
 * Batch get today's reminder counts per user (for global cap)
 */
async function batchGetReminderCounts(
  userIds: string[],
  userTimezones: Map<string, string>
): Promise<Map<string, number>> {
  if (userIds.length === 0) {
    return new Map();
  }

  const result = new Map<string, number>();

  // Group users by their local date
  const dateGroups = new Map<string, string[]>();
  for (const userId of userIds) {
    const timezone = userTimezones.get(userId) ?? 'UTC';
    const localDate = getUserLocalDate(timezone);
    if (!dateGroups.has(localDate)) {
      dateGroups.set(localDate, []);
    }
    dateGroups.get(localDate)!.push(userId);
  }

  for (const [localDate, users] of dateGroups) {
    const counts = await db
      .selectFrom('reminder_history')
      .select(['user_id', sql<number>`COUNT(*)`.as('count')])
      .where('sent_at_local_date', '=', localDate)
      .where('user_id', 'in', users)
      .groupBy('user_id')
      .execute();

    for (const row of counts) {
      result.set(row.user_id, Number(row.count));
    }
  }

  // Initialize users without reminders to 0
  for (const userId of userIds) {
    if (!result.has(userId)) {
      result.set(userId, 0);
    }
  }

  return result;
}

/**
 * Batch get recent effectiveness data for adaptive suppression
 */
async function batchGetRecentEffectiveness(
  userGoalPairs: Array<{ userId: string; goalId: string }>
): Promise<Map<string, EffectivenessData>> {
  if (userGoalPairs.length === 0) {
    return new Map();
  }

  const result = new Map<string, EffectivenessData>();

  // For each pair, count consecutive days of ineffective reminders
  for (const pair of userGoalPairs) {
    const key = `${pair.userId}:${pair.goalId}`;

    // Get recent reminders ordered by date
    const recentReminders = await db
      .selectFrom('reminder_history')
      .select(['sent_at_local_date', 'was_effective'])
      .where('user_id', '=', pair.userId)
      .where('goal_id', '=', pair.goalId)
      .where('was_effective', 'is not', null)
      .orderBy('sent_at_local_date', 'desc')
      .limit(30)
      .execute();

    // Count consecutive ineffective days
    let consecutiveIneffectiveDays = 0;
    let lastDate: string | null = null;

    for (const reminder of recentReminders) {
      if (reminder.was_effective) {
        break; // Found an effective day, stop counting
      }

      // Only count once per day
      if (reminder.sent_at_local_date !== lastDate) {
        consecutiveIneffectiveDays++;
        lastDate = reminder.sent_at_local_date;
      }
    }

    result.set(key, { consecutiveIneffectiveDays });
  }

  return result;
}

/**
 * Process all pending reminders
 * Called by the scheduled job every 15 minutes
 */
export async function processReminders(): Promise<ProcessResult> {
  const result: ProcessResult = { sent: 0, skipped: 0, errors: 0 };

  try {
    // Step 1: Get all candidate user-goal pairs
    const candidates = await getCandidateGoals();
    logger.info(`Processing ${candidates.length} candidate goals for reminders`);

    if (candidates.length === 0) {
      return result;
    }

    // Step 2: Batch-fetch all needed data
    const userGoalPairs = candidates.map((c) => ({
      userId: c.userId,
      goalId: c.goalId,
      userTimezone: c.userTimezone,
    }));
    const userIds = [...new Set(candidates.map((c) => c.userId))];
    const userTimezones = new Map(candidates.map((c) => [c.userId, c.userTimezone]));

    const [patterns, preferences, todaysReminders, reminderCounts, effectivenessData] =
      await Promise.all([
        batchGetPatterns(userGoalPairs),
        batchGetPreferences(userGoalPairs),
        batchGetTodaysReminders(userGoalPairs),
        batchGetReminderCounts(userIds, userTimezones),
        batchGetRecentEffectiveness(userGoalPairs),
      ]);

    // Step 3: Evaluate and send reminders
    const now = new Date();

    for (const candidate of candidates) {
      try {
        const key = `${candidate.userId}:${candidate.goalId}`;
        const userLocalDate = getUserLocalDate(candidate.userTimezone);

        const decision = shouldSendReminder(
          candidate.userId,
          candidate.goalId,
          candidate.userTimezone,
          now,
          preferences.get(key) ?? null,
          patterns.get(key) ?? null,
          todaysReminders.get(key) ?? [],
          reminderCounts.get(candidate.userId) ?? 0,
          effectivenessData.get(key) ?? { consecutiveIneffectiveDays: 0 }
        );

        if (!decision.shouldSend || !decision.tier) {
          result.skipped++;
          continue;
        }

        // Build social context
        const socialContext = await buildSocialContext(
          candidate.goalId,
          candidate.userId,
          userLocalDate
        );

        if (!socialContext) {
          result.skipped++;
          continue;
        }

        // Send the reminder
        await sendReminder(
          candidate.userId,
          candidate.goalId,
          candidate.goalTitle,
          socialContext.groupName,
          candidate.groupId,
          decision.tier,
          socialContext
        );

        // Record in history
        await recordReminderHistory(
          candidate.userId,
          candidate.goalId,
          decision.tier,
          socialContext,
          candidate.userTimezone,
          userLocalDate
        );

        // Update in-memory count for global cap enforcement
        reminderCounts.set(
          candidate.userId,
          (reminderCounts.get(candidate.userId) ?? 0) + 1
        );

        result.sent++;
      } catch (error) {
        logger.error('Failed to process reminder', {
          user_id: candidate.userId,
          goal_id: candidate.goalId,
          error: error instanceof Error ? error.message : String(error),
        });
        result.errors++;
      }
    }

    return result;
  } catch (error) {
    logger.error('Failed to process reminders', {
      error: error instanceof Error ? error.message : String(error),
    });
    throw error;
  }
}

/**
 * Update effectiveness metrics for recent reminders
 * Called by the daily scheduled job
 * 
 * A reminder is "effective" if the user logged the goal on the same local date
 * after the reminder was sent.
 */
export async function updateEffectivenessMetrics(): Promise<{ updated: number }> {
  // Update reminders from the past 2 days where effectiveness hasn't been evaluated yet
  const twoDaysAgo = new Date();
  twoDaysAgo.setDate(twoDaysAgo.getDate() - 2);

  const pendingReminders = await db
    .selectFrom('reminder_history')
    .select(['id', 'user_id', 'goal_id', 'sent_at', 'sent_at_local_date'])
    .where('sent_at', '>=', twoDaysAgo)
    .where('was_effective', 'is', null)
    .execute();

  let updated = 0;

  for (const reminder of pendingReminders) {
    // Check if user logged the goal on the same local date after the reminder
    const loggedAfter = await db
      .selectFrom('progress_entries')
      .select('id')
      .where('user_id', '=', reminder.user_id)
      .where('goal_id', '=', reminder.goal_id)
      .where('period_start', '=', reminder.sent_at_local_date)
      .where('created_at', '>', reminder.sent_at)
      .executeTakeFirst();

    const wasEffective = !!loggedAfter;

    await db
      .updateTable('reminder_history')
      .set({ was_effective: wasEffective })
      .where('id', '=', reminder.id)
      .execute();

    updated++;
  }

  logger.info(`Updated effectiveness for ${updated} reminders`);
  return { updated };
}
