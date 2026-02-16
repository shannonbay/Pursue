import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';
import { createGroupActivity, ACTIVITY_TYPES } from './activity.service.js';

// Constants from spec
export const SENSITIVITY = 50;
export const DECAY_FACTOR = 0.98;

export const TIER_NAMES = [
  'Cold',      // 0: 0-5
  'Spark',     // 1: 6-18
  'Ember',     // 2: 19-32
  'Flicker',   // 3: 33-46
  'Steady',    // 4: 47-60
  'Blaze',     // 5: 61-74
  'Inferno',   // 6: 75-88
  'Supernova', // 7: 89-100
] as const;

// Streak milestones that trigger activity events
const STREAK_MILESTONES = [7, 14, 30];

/**
 * Convert a heat score (0-100) to a tier (0-7)
 */
export function scoreToTier(score: number): number {
  if (score <= 5) return 0;
  if (score <= 18) return 1;
  if (score <= 32) return 2;
  if (score <= 46) return 3;
  if (score <= 60) return 4;
  if (score <= 74) return 5;
  if (score <= 88) return 6;
  return 7;
}

/**
 * Get the tier name for a given tier number
 */
export function tierName(tier: number): string {
  return TIER_NAMES[tier] ?? 'Cold';
}

/**
 * Check if a (member, goal) pair is completed for a given progress entry value
 */
export function isCompleted(
  value: number,
  metricType: string,
  targetValue: number | null
): boolean {
  switch (metricType) {
    case 'binary':
      return value === 1;
    case 'numeric':
    case 'duration':
      return targetValue !== null && value >= targetValue;
    default:
      return false;
  }
}

/**
 * Subtract days from a date string (YYYY-MM-DD)
 */
function subtractDays(dateStr: string, days: number): string {
  const date = new Date(dateStr + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}

/**
 * Get yesterday's date in YYYY-MM-DD format (UTC)
 */
export function getYesterdayDate(): string {
  const now = new Date();
  now.setUTCDate(now.getUTCDate() - 1);
  return now.toISOString().slice(0, 10);
}

/**
 * Compute and store the GCR for a group on a specific date
 */
export async function computeAndStoreGcr(
  groupId: string,
  date: string
): Promise<number> {
  // Get active members (status = active)
  // Note: For more accurate historical GCR, we could filter by joined_at <= date
  // but for simplicity we use current active members
  const members = await db
    .selectFrom('group_memberships')
    .select(['user_id'])
    .where('group_id', '=', groupId)
    .where('status', '=', 'active')
    .execute();

  // Get active daily goals (not deleted)
  // Note: For more accurate historical GCR, we could filter by created_at <= date
  // but for simplicity we use current active goals
  const goals = await db
    .selectFrom('goals')
    .select(['id', 'metric_type', 'target_value'])
    .where('group_id', '=', groupId)
    .where('cadence', '=', 'daily')
    .where('deleted_at', 'is', null)
    .execute();

  const memberCount = members.length;
  const goalCount = goals.length;
  const totalPossible = memberCount * goalCount;

  if (totalPossible === 0) {
    // Store GCR as 0 with 0 possible — no impact on heat
    await storeGcrRow(groupId, date, 0, 0, 0, memberCount, goalCount);
    return 0;
  }

  // Get all progress entries for this group's daily goals on this date
  const goalIds = goals.map((g) => g.id);
  const memberIds = members.map((m) => m.user_id);

  const entries = await db
    .selectFrom('progress_entries')
    .select(['goal_id', 'user_id', 'value'])
    .where('goal_id', 'in', goalIds)
    .where('user_id', 'in', memberIds)
    .where('period_start', '=', date)
    .execute();

  // Count completed pairs
  let completedCount = 0;
  for (const goal of goals) {
    for (const member of members) {
      const entry = entries.find(
        (e) => e.goal_id === goal.id && e.user_id === member.user_id
      );
      if (
        entry &&
        isCompleted(Number(entry.value), goal.metric_type, goal.target_value)
      ) {
        completedCount++;
      }
    }
  }

  const gcr = completedCount / totalPossible;

  await storeGcrRow(
    groupId,
    date,
    totalPossible,
    completedCount,
    gcr,
    memberCount,
    goalCount
  );

  return gcr;
}

/**
 * Store or update a GCR row for a group and date
 */
async function storeGcrRow(
  groupId: string,
  date: string,
  totalPossible: number,
  totalCompleted: number,
  gcr: number,
  memberCount: number,
  goalCount: number
): Promise<void> {
  const roundedGcr = Math.round(gcr * 10000) / 10000;

  await db
    .insertInto('group_daily_gcr')
    .values({
      group_id: groupId,
      date,
      total_possible: totalPossible,
      total_completed: totalCompleted,
      gcr: roundedGcr,
      member_count: memberCount,
      goal_count: goalCount,
    })
    .onConflict((oc) =>
      oc.columns(['group_id', 'date']).doUpdateSet({
        total_possible: totalPossible,
        total_completed: totalCompleted,
        gcr: roundedGcr,
        member_count: memberCount,
        goal_count: goalCount,
      })
    )
    .execute();
}

/**
 * Calculate and update the heat score for a single group
 */
export async function calculateGroupHeat(
  groupId: string,
  date: string
): Promise<void> {
  // 1. Calculate yesterday's GCR (if not already computed)
  const existingGcr = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .where('date', '=', date)
    .executeTakeFirst();

  let yesterdayGcr: number;

  if (existingGcr) {
    yesterdayGcr = Number(existingGcr.gcr);
  } else {
    yesterdayGcr = await computeAndStoreGcr(groupId, date);
  }

  // 2. Get baseline: trailing 7-day average (D-8 through D-2)
  const baselineStart = subtractDays(date, 7); // D-8 relative to yesterday
  const baselineEnd = subtractDays(date, 1); // D-2 relative to yesterday

  const baselineRows = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .where('date', '>=', baselineStart)
    .where('date', '<=', baselineEnd)
    .execute();

  // If no baseline data (new group), use yesterday's GCR as baseline
  // This means delta = 0, and the score just decays slightly
  const baseline =
    baselineRows.length > 0
      ? baselineRows.reduce((sum, r) => sum + Number(r.gcr), 0) /
        baselineRows.length
      : yesterdayGcr;

  // 3. Get current heat state
  const currentHeat = await db
    .selectFrom('group_heat')
    .select(['heat_score', 'heat_tier', 'streak_days', 'peak_score', 'peak_date'])
    .where('group_id', '=', groupId)
    .executeTakeFirst();

  const currentScore = currentHeat ? Number(currentHeat.heat_score) : 0;
  const currentTier = currentHeat ? currentHeat.heat_tier : 0;
  const currentStreak = currentHeat ? currentHeat.streak_days : 0;
  const currentPeak = currentHeat ? Number(currentHeat.peak_score) : 0;

  // 4. Calculate delta and update heat score
  const delta = yesterdayGcr - baseline;
  const rawNewScore = currentScore + delta * SENSITIVITY;
  const clampedScore = Math.max(0, Math.min(100, rawNewScore));
  const decayedScore = clampedScore * DECAY_FACTOR;
  const finalScore = Math.round(decayedScore * 100) / 100; // 2 decimal places

  // 5. Determine new tier
  const newTier = scoreToTier(finalScore);

  // 6. Update streak and peak
  const scoreIncreased = finalScore > currentScore;
  const newStreak = scoreIncreased ? currentStreak + 1 : 0;
  const newPeak = finalScore > currentPeak ? finalScore : currentPeak;
  const peakDate = finalScore > currentPeak ? date : currentHeat?.peak_date;

  // 7. Upsert group_heat
  await db
    .insertInto('group_heat')
    .values({
      group_id: groupId,
      heat_score: finalScore,
      heat_tier: newTier,
      last_calculated_at: new Date(),
      streak_days: newStreak,
      peak_score: newPeak,
      peak_date: peakDate,
    })
    .onConflict((oc) =>
      oc.column('group_id').doUpdateSet({
        heat_score: finalScore,
        heat_tier: newTier,
        last_calculated_at: new Date(),
        streak_days: newStreak,
        peak_score: newPeak,
        peak_date: peakDate,
      })
    )
    .execute();

  // 8. Create activity events for tier changes and milestones
  await createHeatActivities(
    groupId,
    currentTier,
    newTier,
    currentStreak,
    newStreak,
    finalScore
  );
}

/**
 * Create activity events for heat tier changes and streak milestones
 */
async function createHeatActivities(
  groupId: string,
  oldTier: number,
  newTier: number,
  oldStreak: number,
  newStreak: number,
  score: number
): Promise<void> {
  // Tier increased
  if (newTier > oldTier) {
    await createGroupActivity(groupId, ACTIVITY_TYPES.HEAT_TIER_UP, null, {
      old_tier: oldTier,
      new_tier: newTier,
      tier_name: tierName(newTier),
      score,
    });

    // First time reaching Supernova
    if (newTier === 7 && oldTier < 7) {
      await createGroupActivity(
        groupId,
        ACTIVITY_TYPES.HEAT_SUPERNOVA_REACHED,
        null,
        { score }
      );
    }
  }

  // Tier decreased
  if (newTier < oldTier) {
    await createGroupActivity(groupId, ACTIVITY_TYPES.HEAT_TIER_DOWN, null, {
      old_tier: oldTier,
      new_tier: newTier,
      tier_name: tierName(newTier),
      score,
    });
  }

  // Streak milestones (7, 14, 30)
  for (const milestone of STREAK_MILESTONES) {
    if (newStreak === milestone && oldStreak < milestone) {
      await createGroupActivity(
        groupId,
        ACTIVITY_TYPES.HEAT_STREAK_MILESTONE,
        null,
        { streak_days: milestone, score }
      );
    }
  }
}

/**
 * Calculate heat for all active groups (called by daily job)
 */
export async function calculateDailyHeatForAllGroups(): Promise<{
  processed: number;
  errors: number;
}> {
  const yesterday = getYesterdayDate();

  // Get all active groups
  const groups = await db
    .selectFrom('groups')
    .select(['id'])
    .where('deleted_at', 'is', null)
    .execute();

  let processed = 0;
  let errors = 0;

  for (const group of groups) {
    try {
      await calculateGroupHeat(group.id, yesterday);
      processed++;
    } catch (error) {
      errors++;
      logger.error('Failed to calculate heat for group', {
        groupId: group.id,
        error: error instanceof Error ? error.message : 'Unknown error',
      });
    }
  }

  logger.info('Daily heat calculation completed', {
    processed,
    errors,
    total: groups.length,
    date: yesterday,
  });

  return { processed, errors };
}

/**
 * Get heat data for a group with optional extended info
 */
export async function getGroupHeat(
  groupId: string,
  includeGcrData = false
): Promise<{
  score: number;
  tier: number;
  tier_name: string;
  streak_days: number;
  peak_score: number;
  peak_date: string | null;
  last_calculated_at: string | null;
  yesterday_gcr?: number;
  baseline_gcr?: number;
} | null> {
  const heat = await db
    .selectFrom('group_heat')
    .select([
      'heat_score',
      'heat_tier',
      'streak_days',
      'peak_score',
      'peak_date',
      'last_calculated_at',
    ])
    .where('group_id', '=', groupId)
    .executeTakeFirst();

  if (!heat) {
    return null;
  }

  const result: ReturnType<typeof getGroupHeat> extends Promise<infer T>
    ? NonNullable<T>
    : never = {
    score: Number(heat.heat_score),
    tier: heat.heat_tier,
    tier_name: tierName(heat.heat_tier),
    streak_days: heat.streak_days,
    peak_score: Number(heat.peak_score),
    peak_date: heat.peak_date,
    last_calculated_at: heat.last_calculated_at?.toISOString() ?? null,
  };

  if (includeGcrData) {
    const yesterday = getYesterdayDate();
    const baselineStart = subtractDays(yesterday, 7);
    const baselineEnd = subtractDays(yesterday, 1);

    // Get yesterday's GCR
    const yesterdayGcr = await db
      .selectFrom('group_daily_gcr')
      .select(['gcr'])
      .where('group_id', '=', groupId)
      .where('date', '=', yesterday)
      .executeTakeFirst();

    // Get baseline average
    const baselineRows = await db
      .selectFrom('group_daily_gcr')
      .select(['gcr'])
      .where('group_id', '=', groupId)
      .where('date', '>=', baselineStart)
      .where('date', '<=', baselineEnd)
      .execute();

    result.yesterday_gcr = yesterdayGcr ? Number(yesterdayGcr.gcr) : undefined;
    result.baseline_gcr =
      baselineRows.length > 0
        ? baselineRows.reduce((sum, r) => sum + Number(r.gcr), 0) /
          baselineRows.length
        : undefined;
  }

  return result;
}

/**
 * Get heat history for a group (for premium users)
 */
export async function getGroupHeatHistory(
  groupId: string,
  days: number
): Promise<{
  date: string;
  score: number;
  tier: number;
  gcr: number;
}[]> {
  // Get GCR history for the specified number of days
  const history = await db
    .selectFrom('group_daily_gcr')
    .select(['date', 'gcr'])
    .where('group_id', '=', groupId)
    .orderBy('date', 'desc')
    .limit(days)
    .execute();

  // We need to calculate what the heat score would have been on each day
  // For simplicity, we return the GCR and compute the tier from GCR
  // In a full implementation, we'd store historical heat scores
  return history.map((row) => {
    const gcr = Number(row.gcr);
    // Approximate score from GCR (this is simplified)
    const approximateScore = Math.min(100, gcr * 100);
    return {
      date: row.date,
      score: Math.round(approximateScore * 100) / 100,
      tier: scoreToTier(approximateScore),
      gcr: Math.round(gcr * 10000) / 10000,
    };
  });
}

/**
 * Get heat statistics for a group (for premium users)
 */
export async function getGroupHeatStats(
  groupId: string,
  days: number
): Promise<{
  avg_score_30d: number;
  peak_score: number;
  peak_date: string | null;
  days_at_supernova: number;
  longest_increase_streak: number;
}> {
  const heat = await db
    .selectFrom('group_heat')
    .select(['peak_score', 'peak_date'])
    .where('group_id', '=', groupId)
    .executeTakeFirst();

  const gcrHistory = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .orderBy('date', 'desc')
    .limit(days)
    .execute();

  // Calculate average score (approximated from GCR)
  const avgGcr =
    gcrHistory.length > 0
      ? gcrHistory.reduce((sum, r) => sum + Number(r.gcr), 0) / gcrHistory.length
      : 0;

  // Count days at supernova (tier 7, score >= 89)
  const daysAtSupernova = gcrHistory.filter(
    (r) => scoreToTier(Number(r.gcr) * 100) === 7
  ).length;

  return {
    avg_score_30d: Math.round(avgGcr * 100 * 100) / 100,
    peak_score: heat ? Number(heat.peak_score) : 0,
    peak_date: heat?.peak_date ?? null,
    days_at_supernova: daysAtSupernova,
    longest_increase_streak: heat ? 0 : 0, // Would need historical tracking to calculate
  };
}

/**
 * Initialize heat record for a new group
 */
export async function initializeGroupHeat(groupId: string): Promise<void> {
  await db
    .insertInto('group_heat')
    .values({
      group_id: groupId,
      heat_score: 0,
      heat_tier: 0,
      streak_days: 0,
      peak_score: 0,
    })
    .onConflict((oc) => oc.column('group_id').doNothing())
    .execute();
}
