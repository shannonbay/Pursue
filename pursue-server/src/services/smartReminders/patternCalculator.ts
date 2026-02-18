/**
 * Smart Reminders - Pattern Calculator
 * 
 * Calculates user logging patterns from progress entries.
 * Uses circular statistics to handle midnight-wrapping patterns.
 */

import { sql } from 'kysely';
import { db } from '../../database/index.js';
import { logger } from '../../utils/logger.js';
import type { LoggingPattern, PatternRecalculationResult } from './types.js';
import { REMINDER_CONFIG } from './types.js';

const TWO_PI = 2 * Math.PI;

/**
 * Calculate circular statistics for hours (handles midnight wrapping)
 * 
 * Example: A user who logs at 23:00 and 01:00 should have a median
 * of ~midnight, not ~noon (which naive averaging would produce).
 */
function calculateCircularStats(hours: number[]): {
  median: number;
  iqr: number;
  stdDev: number;
} {
  if (hours.length === 0) {
    return { median: 12, iqr: 24, stdDev: 12 };
  }

  // Convert hours to radians (0-24 → 0-2π)
  const angles = hours.map((h) => (h / 24) * TWO_PI);

  // Circular mean
  const sinSum = angles.reduce((sum, a) => sum + Math.sin(a), 0);
  const cosSum = angles.reduce((sum, a) => sum + Math.cos(a), 0);
  const meanAngle = Math.atan2(sinSum / hours.length, cosSum / hours.length);
  const circularMean = ((meanAngle / TWO_PI) * 24 + 24) % 24;

  // Circular deviation (R = mean resultant length)
  const R = Math.sqrt(
    Math.pow(sinSum / hours.length, 2) + Math.pow(cosSum / hours.length, 2)
  );

  // Circular std dev (in hours): sqrt(-2 * ln(R)) * (24 / 2π)
  // Clamp R to [0, 1] before log: floating-point can yield R slightly > 1
  // (e.g. sin²(x)+cos²(x) = 1.0000000000000002 for some angles), which makes
  // log(R) > 0 → negative argument to sqrt → NaN.
  const circularStdDev =
    R > 0 ? Math.sqrt(-2 * Math.log(Math.min(R, 1))) * (24 / TWO_PI) : 12;

  // For IQR: unwrap hours relative to circular mean, then use linear IQR
  const unwrapped = hours.map((h) => {
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
    stdDev: circularStdDev,
  };
}

/**
 * Calculate confidence score based on sample size and consistency
 */
function calculateConfidence(
  sampleSize: number,
  stdDev: number,
  iqr: number
): number {
  // Confidence based on:
  // 1. Sample size (more logs = higher confidence), capped at 30
  // 2. Consistency (lower stdDev = higher confidence)
  // 3. Tightness (lower IQR = higher confidence)
  const sampleFactor = Math.min(1.0, sampleSize / 30);
  const consistencyFactor = Math.max(0, 1 - stdDev / 12);
  const tightnessFactor = Math.max(0, 1 - iqr / 8);

  return sampleFactor * 0.4 + consistencyFactor * 0.3 + tightnessFactor * 0.3;
}

/**
 * Wrap hour to 0-23 range
 */
function wrapHour(h: number): number {
  return ((h % 24) + 24) % 24;
}

/**
 * Get local hour from a timestamp in a specific timezone
 */
function getLocalHour(timestamp: Date, timezone: string): number {
  try {
    const hourStr = timestamp.toLocaleString('en-US', {
      timeZone: timezone,
      hour: 'numeric',
      hour12: false,
    });
    return parseInt(hourStr, 10);
  } catch {
    // Invalid timezone, default to UTC
    return timestamp.getUTCHours();
  }
}

/**
 * Get local day of week from a timestamp in a specific timezone
 */
function getLocalDayOfWeek(timestamp: Date, timezone: string): number {
  try {
    const dayStr = timestamp.toLocaleString('en-US', {
      timeZone: timezone,
      weekday: 'short',
    });
    const dayMap: Record<string, number> = {
      Sun: 0,
      Mon: 1,
      Tue: 2,
      Wed: 3,
      Thu: 4,
      Fri: 5,
      Sat: 6,
    };
    return dayMap[dayStr] ?? 0;
  } catch {
    return timestamp.getUTCDay();
  }
}

/**
 * Calculate logging pattern for a user-goal combination
 */
export async function calculatePattern(
  userId: string,
  goalId: string,
  userTimezone: string,
  dayOfWeek?: number
): Promise<LoggingPattern | null> {
  // Fetch last 30 days of logs
  const cutoffDate = new Date();
  cutoffDate.setDate(cutoffDate.getDate() - REMINDER_CONFIG.PATTERN_HISTORY_DAYS);

  const logs = await db
    .selectFrom('progress_entries')
    .select(['logged_at'])
    .where('user_id', '=', userId)
    .where('goal_id', '=', goalId)
    .where('logged_at', '>=', cutoffDate)
    .execute();

  // Convert all log timestamps to user's local time
  const localLogs = logs.map((log) => ({
    localHour: getLocalHour(log.logged_at, userTimezone),
    localDayOfWeek: getLocalDayOfWeek(log.logged_at, userTimezone),
  }));

  // Filter by day of week if specified
  const relevantLogs =
    dayOfWeek !== undefined
      ? localLogs.filter((log) => log.localDayOfWeek === dayOfWeek)
      : localLogs;

  if (relevantLogs.length < REMINDER_CONFIG.MIN_LOGS_FOR_PATTERN) {
    return null; // Insufficient data
  }

  // Extract hour of day for each log (in user's local timezone)
  const hours = relevantLogs.map((log) => log.localHour);

  // Use circular statistics for hours (handles midnight wrapping)
  const { median, iqr, stdDev } = calculateCircularStats(hours);

  // Define typical window (median ± 1 hour, or IQR-based if more spread)
  const windowSize = Math.min(2, Math.ceil(iqr / 2));
  const hourStart = wrapHour(Math.round(median) - windowSize);
  const hourEnd = wrapHour(Math.round(median) + windowSize);

  // Calculate confidence score
  const confidence = calculateConfidence(relevantLogs.length, stdDev, iqr);

  return {
    userId,
    goalId,
    dayOfWeek: dayOfWeek ?? -1,
    typicalHourStart: hourStart,
    typicalHourEnd: hourEnd,
    confidenceScore: confidence,
    sampleSize: relevantLogs.length,
    lastCalculatedAt: new Date(),
  };
}

/**
 * Calculate and store a pattern for a user-goal combination
 */
export async function calculateAndStorePattern(
  userId: string,
  goalId: string,
  userTimezone: string
): Promise<LoggingPattern | null> {
  const pattern = await calculatePattern(userId, goalId, userTimezone);

  if (!pattern) {
    // Remove any existing pattern if we no longer have enough data
    await db
      .deleteFrom('user_logging_patterns')
      .where('user_id', '=', userId)
      .where('goal_id', '=', goalId)
      .where('day_of_week', '=', -1)
      .execute();
    return null;
  }

  // Upsert the pattern
  await db
    .insertInto('user_logging_patterns')
    .values({
      user_id: pattern.userId,
      goal_id: pattern.goalId,
      day_of_week: pattern.dayOfWeek,
      typical_hour_start: pattern.typicalHourStart,
      typical_hour_end: pattern.typicalHourEnd,
      confidence_score: pattern.confidenceScore,
      sample_size: pattern.sampleSize,
    })
    .onConflict((oc) =>
      oc.columns(['user_id', 'goal_id', 'day_of_week']).doUpdateSet({
        typical_hour_start: pattern.typicalHourStart,
        typical_hour_end: pattern.typicalHourEnd,
        confidence_score: pattern.confidenceScore,
        sample_size: pattern.sampleSize,
        last_calculated_at: new Date().toISOString(),
      })
    )
    .execute();

  return pattern;
}

/**
 * Recalculate patterns for all active user-goal combinations
 * Called by the weekly scheduled job
 */
export async function recalculateAllPatterns(): Promise<PatternRecalculationResult> {
  const result: PatternRecalculationResult = {
    updated: 0,
    created: 0,
    removed: 0,
  };

  try {
    // Get all unique user-goal combinations with daily goals where user has logged at least once
    const userGoals = await db
      .selectFrom('progress_entries as pe')
      .innerJoin('goals as g', 'pe.goal_id', 'g.id')
      .innerJoin('users as u', 'pe.user_id', 'u.id')
      .select([
        'pe.user_id',
        'pe.goal_id',
        sql<string>`COALESCE(u.timezone, 'UTC')`.as('user_timezone'),
      ])
      .distinct()
      .where('g.deleted_at', 'is', null)
      .where('u.deleted_at', 'is', null)
      .where('g.cadence', '=', 'daily')
      .execute();

    logger.info(`Recalculating patterns for ${userGoals.length} user-goal pairs`);

    // Track which patterns we've updated
    const updatedPairs = new Set<string>();

    for (const ug of userGoals) {
      const key = `${ug.user_id}:${ug.goal_id}`;
      try {
        // Check if pattern already exists
        const existingPattern = await db
          .selectFrom('user_logging_patterns')
          .select('user_id')
          .where('user_id', '=', ug.user_id)
          .where('goal_id', '=', ug.goal_id)
          .where('day_of_week', '=', -1)
          .executeTakeFirst();

        const pattern = await calculateAndStorePattern(
          ug.user_id,
          ug.goal_id,
          ug.user_timezone
        );

        if (pattern) {
          updatedPairs.add(key);
          if (existingPattern) {
            result.updated++;
          } else {
            result.created++;
          }
        } else if (existingPattern) {
          // Pattern was removed due to insufficient data
          result.removed++;
        }
      } catch (error) {
        logger.error('Error calculating pattern', {
          user_id: ug.user_id,
          goal_id: ug.goal_id,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    }

    // Clean up orphaned patterns (goals deleted, users deleted, etc.)
    const cleanupResult = await db
      .deleteFrom('user_logging_patterns')
      .where((eb) =>
        eb.or([
          // Goal no longer exists or is deleted
          eb.not(
            eb.exists(
              eb
                .selectFrom('goals')
                .select('id')
                .whereRef('goals.id', '=', 'user_logging_patterns.goal_id')
                .where('goals.deleted_at', 'is', null)
            )
          ),
          // User no longer exists or is deleted
          eb.not(
            eb.exists(
              eb
                .selectFrom('users')
                .select('id')
                .whereRef('users.id', '=', 'user_logging_patterns.user_id')
                .where('users.deleted_at', 'is', null)
            )
          ),
        ])
      )
      .executeTakeFirst();

    if (cleanupResult.numDeletedRows) {
      result.removed += Number(cleanupResult.numDeletedRows);
    }

    logger.info('Pattern recalculation completed', result);
    return result;
  } catch (error) {
    logger.error('Failed to recalculate patterns', {
      error: error instanceof Error ? error.message : String(error),
    });
    throw error;
  }
}

/**
 * Get existing pattern for a user-goal combination
 */
export async function getPattern(
  userId: string,
  goalId: string,
  dayOfWeek: number = -1
): Promise<LoggingPattern | null> {
  const row = await db
    .selectFrom('user_logging_patterns')
    .selectAll()
    .where('user_id', '=', userId)
    .where('goal_id', '=', goalId)
    .where('day_of_week', '=', dayOfWeek)
    .executeTakeFirst();

  if (!row) {
    return null;
  }

  return {
    userId: row.user_id,
    goalId: row.goal_id,
    dayOfWeek: row.day_of_week,
    typicalHourStart: row.typical_hour_start,
    typicalHourEnd: row.typical_hour_end,
    confidenceScore: Number(row.confidence_score),
    sampleSize: row.sample_size,
    lastCalculatedAt: row.last_calculated_at,
  };
}

/**
 * Batch get patterns for multiple user-goal pairs
 */
export async function batchGetPatterns(
  userGoalPairs: Array<{ userId: string; goalId: string }>
): Promise<Map<string, LoggingPattern>> {
  if (userGoalPairs.length === 0) {
    return new Map();
  }

  // Build the condition for all pairs
  const rows = await db
    .selectFrom('user_logging_patterns')
    .selectAll()
    .where('day_of_week', '=', -1) // Only general patterns
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

  const result = new Map<string, LoggingPattern>();
  for (const row of rows) {
    const key = `${row.user_id}:${row.goal_id}`;
    result.set(key, {
      userId: row.user_id,
      goalId: row.goal_id,
      dayOfWeek: row.day_of_week,
      typicalHourStart: row.typical_hour_start,
      typicalHourEnd: row.typical_hour_end,
      confidenceScore: Number(row.confidence_score),
      sampleSize: row.sample_size,
      lastCalculatedAt: row.last_calculated_at,
    });
  }

  return result;
}
