import { sql } from 'kysely';
import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';
import { isCompleted, tierName, TIER_NAMES } from './heat.service.js';
import { sendGroupNotification } from './fcm.service.js';

// TypeScript interfaces for recap data
export interface WeeklyRecapData {
  group: {
    id: string;
    name: string;
    iconUrl: string | null;
  };
  period: {
    startDate: string;
    endDate: string;
  };
  completionRate: {
    current: number;
    previous: number;
    delta: number;
  };
  highlights: RecapHighlight[];
  goalBreakdown: GoalWeeklyStats[];
  heat: {
    score: number;
    tier: number;
    tierName: string;
    streakDays: number;
    tierDelta: number;
  } | null;
  memberCount: number;
}

export interface RecapHighlight {
  type: string;
  priority: number;
  emoji: string;
  text: string;
  userId?: string;
  displayName?: string;
}

export interface GoalWeeklyStats {
  goalId: string;
  title: string;
  completionRate: number;
  metricType: string;
}

interface MemberStats {
  userId: string;
  displayName: string;
  rate: number;
  previousRate?: number;
}

interface StreakData {
  userId: string;
  displayName: string;
  goalId: string;
  streakDays: number;
  milestonedThisWeek: boolean;
}

// Streak milestones from spec
const STREAK_MILESTONES = [7, 14, 21, 30, 50, 100];

/**
 * Subtract days from a date string (YYYY-MM-DD)
 */
function subtractDays(dateStr: string, days: number): string {
  const date = new Date(dateStr + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}

/**
 * Add days to a date string (YYYY-MM-DD)
 */
function addDays(dateStr: string, days: number): string {
  const date = new Date(dateStr + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

/**
 * Calculate number of days between two dates (inclusive)
 */
function daysBetween(startDate: string, endDate: string): number {
  const start = new Date(startDate + 'T00:00:00Z');
  const end = new Date(endDate + 'T00:00:00Z');
  const diffTime = Math.abs(end.getTime() - start.getTime());
  const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
  return diffDays;
}

/**
 * Get user's current local date in their timezone
 */
function getUserLocalDate(timezone: string): string {
  try {
    return new Date().toLocaleDateString('en-CA', { timeZone: timezone });
  } catch {
    return new Date().toISOString().slice(0, 10);
  }
}

/**
 * Get user's current local hour (0-23) in their timezone
 */
function getUserLocalHour(timezone: string): number {
  try {
    const hour = new Date().toLocaleString('en-US', {
      timeZone: timezone,
      hour: '2-digit',
      hour12: false,
    });
    return parseInt(hour);
  } catch {
    return new Date().getUTCHours();
  }
}

/**
 * Get user's current local day of week (0 = Sunday, 6 = Saturday)
 */
function getUserLocalDayOfWeek(timezone: string): number {
  try {
    const dateStr = new Date().toLocaleDateString('en-US', {
      timeZone: timezone,
      weekday: 'short',
    });
    const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    return days.indexOf(dateStr);
  } catch {
    return new Date().getUTCDay();
  }
}

/**
 * Get the previous Sunday from a date in user's timezone
 * Returns the Sunday that just ended (or current Sunday if it's early Sunday)
 */
export function getPreviousSunday(timezone: string): string {
  const localDate = getUserLocalDate(timezone);
  const localDayOfWeek = getUserLocalDayOfWeek(timezone);
  
  // Calculate days to subtract to get to last Sunday
  // If it's Sunday (0), go back 7 days to get previous Sunday
  // If it's Monday (1), go back 1 day to get yesterday's Sunday
  // etc.
  const daysToSubtract = localDayOfWeek === 0 ? 7 : localDayOfWeek;
  
  return subtractDays(localDate, daysToSubtract);
}

/**
 * Get the Monday of the week for a given Sunday
 */
function getMondayForSunday(sunday: string): string {
  return subtractDays(sunday, 6);
}

/**
 * Build weekly recap data for a group
 */
export async function buildWeeklyRecap(
  groupId: string,
  weekStartDate: string, // Monday
  weekEndDate: string     // Sunday
): Promise<WeeklyRecapData> {
  // 1. Group completion rate from existing group_daily_gcr
  const gcrRows = await db
    .selectFrom('group_daily_gcr')
    .select(['date', 'gcr', 'total_possible', 'total_completed'])
    .where('group_id', '=', groupId)
    .where('date', '>=', weekStartDate)
    .where('date', '<=', weekEndDate)
    .execute();

  const currentRate = gcrRows.length > 0
    ? Math.round((gcrRows.reduce((sum, r) => sum + Number(r.gcr), 0) / gcrRows.length) * 100)
    : 0;

  // 2. Prior week for comparison
  const priorStart = subtractDays(weekStartDate, 7);
  const priorEnd = subtractDays(weekEndDate, 7);

  const priorGcrRows = await db
    .selectFrom('group_daily_gcr')
    .select(['gcr'])
    .where('group_id', '=', groupId)
    .where('date', '>=', priorStart)
    .where('date', '<=', priorEnd)
    .execute();

  const previousRate = priorGcrRows.length > 0
    ? Math.round((priorGcrRows.reduce((sum, r) => sum + Number(r.gcr), 0) / priorGcrRows.length) * 100)
    : 0;

  // 3. Per-goal breakdown
  const goalStats = await calculatePerGoalCompletion(groupId, weekStartDate, weekEndDate);

  // 4. Per-member completion rates (for highlights)
  const memberStats = await calculatePerMemberCompletion(groupId, weekStartDate, weekEndDate, priorStart, priorEnd);

  // 5. Streak data (current streaks for all members across all goals)
  const streakData = await getGroupStreaks(groupId, weekStartDate, weekEndDate);

  // 6. Photo count
  const photoResult = await db
    .selectFrom('progress_entries')
    .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
    .innerJoin('progress_photos', 'progress_entries.id', 'progress_photos.progress_entry_id')
    .where('goals.group_id', '=', groupId)
    .where('progress_entries.period_start', '>=', weekStartDate)
    .where('progress_entries.period_start', '<=', weekEndDate)
    .select(db.fn.countAll<string>().as('count'))
    .executeTakeFirst();
  const photoCount = photoResult ? parseInt(photoResult.count) : 0;

  // 7. Reaction count
  const weekStartTimestamp = new Date(`${weekStartDate}T00:00:00Z`);
  const weekEndTimestamp = new Date(`${weekEndDate}T23:59:59Z`);

  const reactionResult = await db
    .selectFrom('activity_reactions')
    .innerJoin('group_activities', 'activity_reactions.activity_id', 'group_activities.id')
    .where('group_activities.group_id', '=', groupId)
    .where('activity_reactions.created_at', '>=', weekStartTimestamp)
    .where('activity_reactions.created_at', '<=', weekEndTimestamp)
    .select(db.fn.countAll<string>().as('count'))
    .executeTakeFirst();
  const reactionCount = reactionResult ? parseInt(reactionResult.count) : 0;

  // 8. Nudge count
  const nudgeResult = await db
    .selectFrom('nudges')
    .where('group_id', '=', groupId)
    .where('sent_at', '>=', weekStartTimestamp)
    .where('sent_at', '<=', weekEndTimestamp)
    .select(db.fn.countAll<string>().as('count'))
    .executeTakeFirst();
  const nudgeCount = nudgeResult ? parseInt(nudgeResult.count) : 0;

  // 9. New members
  const newMembers = await db
    .selectFrom('group_memberships')
    .innerJoin('users', 'group_memberships.user_id', 'users.id')
    .where('group_memberships.group_id', '=', groupId)
    .where('group_memberships.status', '=', 'active')
    .where('group_memberships.joined_at', '>=', weekStartTimestamp)
    .where('group_memberships.joined_at', '<=', weekEndTimestamp)
    .select(['users.id', 'users.display_name'])
    .execute();

  // 10. New goals added this week
  const newGoals = await db
    .selectFrom('goals')
    .where('group_id', '=', groupId)
    .where('deleted_at', 'is', null)
    .where('created_at', '>=', weekStartTimestamp)
    .where('created_at', '<=', weekEndTimestamp)
    .select(['id', 'title'])
    .execute();

  // 11. Group heat (current and start of week for tier delta)
  const heat = await db
    .selectFrom('group_heat')
    .selectAll()
    .where('group_id', '=', groupId)
    .executeTakeFirst();

  // Get heat tier at start of week for comparison
  let tierDelta = 0;
  if (heat) {
    const weekStartHeat = await db
      .selectFrom('group_daily_gcr')
      .select(['date'])
      .where('group_id', '=', groupId)
      .where('date', '=', subtractDays(weekStartDate, 1)) // Day before week started
      .executeTakeFirst();
    
    // For simplicity, calculate tier delta by comparing current tier to what it might have been
    // In a full implementation, we'd store historical tier changes
    tierDelta = 0; // Simplified - would need historical tracking
  }

  // Build highlights from all the above
  const highlights = buildHighlights(
    memberStats,
    streakData,
    photoCount,
    reactionCount,
    nudgeCount,
    newMembers.map(m => ({ userId: m.id, displayName: m.display_name })),
    newGoals.map(g => ({ goalId: g.id, title: g.title })),
    heat ? { heat_tier: heat.heat_tier } : null,
    currentRate,
    previousRate
  );

  // Get group basic info
  const group = await db
    .selectFrom('groups')
    .select(['id', 'name', 'icon_data', 'icon_mime_type'])
    .where('id', '=', groupId)
    .executeTakeFirstOrThrow();

  // Generate icon URL if icon data exists
  const iconUrl = group.icon_data 
    ? `data:${group.icon_mime_type};base64,${group.icon_data.toString('base64')}`
    : null;

  return {
    group: {
      id: group.id,
      name: group.name,
      iconUrl,
    },
    period: { startDate: weekStartDate, endDate: weekEndDate },
    completionRate: {
      current: currentRate,
      previous: previousRate,
      delta: currentRate - previousRate,
    },
    highlights,
    goalBreakdown: goalStats.slice(0, 5),
    heat: heat ? {
      score: Number(heat.heat_score),
      tier: heat.heat_tier,
      tierName: tierName(heat.heat_tier),
      streakDays: heat.streak_days,
      tierDelta,
    } : null,
    memberCount: memberStats.length,
  };
}

/**
 * Calculate per-member completion rates for the week (and optionally prior week)
 */
async function calculatePerMemberCompletion(
  groupId: string,
  startDate: string,
  endDate: string,
  priorStart?: string,
  priorEnd?: string
): Promise<MemberStats[]> {
  const members = await db
    .selectFrom('group_memberships')
    .innerJoin('users', 'group_memberships.user_id', 'users.id')
    .where('group_memberships.group_id', '=', groupId)
    .where('group_memberships.status', '=', 'active')
    .select(['users.id as userId', 'users.display_name as displayName', 'group_memberships.joined_at'])
    .execute();

  const goals = await db
    .selectFrom('goals')
    .select(['id', 'metric_type', 'target_value'])
    .where('group_id', '=', groupId)
    .where('cadence', '=', 'daily')
    .where('deleted_at', 'is', null)
    .execute();

  const dayCount = daysBetween(startDate, endDate) + 1;
  const totalPossiblePerMember = goals.length * dayCount;

  if (totalPossiblePerMember === 0) return [];

  const entries = await db
    .selectFrom('progress_entries')
    .select(['user_id', 'goal_id', 'value', 'period_start'])
    .where('goal_id', 'in', goals.map(g => g.id))
    .where('period_start', '>=', startDate)
    .where('period_start', '<=', endDate)
    .execute();

  // Optionally get prior week entries for delta calculation
  let priorEntries: typeof entries = [];
  if (priorStart && priorEnd) {
    priorEntries = await db
      .selectFrom('progress_entries')
      .select(['user_id', 'goal_id', 'value', 'period_start'])
      .where('goal_id', 'in', goals.map(g => g.id))
      .where('period_start', '>=', priorStart)
      .where('period_start', '<=', priorEnd)
      .execute();
  }

  return members.map(member => {
    const memberEntries = entries.filter(e => e.user_id === member.userId);
    let completed = 0;

    for (const goal of goals) {
      for (let d = 0; d < dayCount; d++) {
        const date = addDays(startDate, d);
        
        // Skip days before member joined
        const joinedDate = new Date(member.joined_at).toISOString().slice(0, 10);
        if (date < joinedDate) continue;

        const entry = memberEntries.find(
          e => e.goal_id === goal.id && e.period_start === date
        );
        if (entry && isCompleted(Number(entry.value), goal.metric_type, goal.target_value)) {
          completed++;
        }
      }
    }

    const rate = Math.round((completed / totalPossiblePerMember) * 100);

    // Calculate previous rate if prior data available
    let previousRate: number | undefined;
    if (priorStart && priorEnd) {
      const memberPriorEntries = priorEntries.filter(e => e.user_id === member.userId);
      let priorCompleted = 0;
      const priorDayCount = daysBetween(priorStart, priorEnd) + 1;

      for (const goal of goals) {
        for (let d = 0; d < priorDayCount; d++) {
          const date = addDays(priorStart, d);
          const entry = memberPriorEntries.find(
            e => e.goal_id === goal.id && e.period_start === date
          );
          if (entry && isCompleted(Number(entry.value), goal.metric_type, goal.target_value)) {
            priorCompleted++;
          }
        }
      }
      previousRate = Math.round((priorCompleted / totalPossiblePerMember) * 100);
    }

    return {
      userId: member.userId,
      displayName: member.displayName,
      rate,
      previousRate,
    };
  });
}

/**
 * Calculate per-goal completion rates for the week
 */
async function calculatePerGoalCompletion(
  groupId: string,
  startDate: string,
  endDate: string
): Promise<GoalWeeklyStats[]> {
  const members = await db
    .selectFrom('group_memberships')
    .where('group_id', '=', groupId)
    .where('status', '=', 'active')
    .select(['user_id'])
    .execute();

  const goals = await db
    .selectFrom('goals')
    .select(['id', 'title', 'metric_type', 'target_value'])
    .where('group_id', '=', groupId)
    .where('cadence', '=', 'daily')
    .where('deleted_at', 'is', null)
    .execute();

  const dayCount = daysBetween(startDate, endDate) + 1;
  const totalPossiblePerGoal = members.length * dayCount;

  if (totalPossiblePerGoal === 0) return [];

  const entries = await db
    .selectFrom('progress_entries')
    .select(['user_id', 'goal_id', 'value', 'period_start'])
    .where('goal_id', 'in', goals.map(g => g.id))
    .where('period_start', '>=', startDate)
    .where('period_start', '<=', endDate)
    .execute();

  const stats: GoalWeeklyStats[] = goals.map(goal => {
    const goalEntries = entries.filter(e => e.goal_id === goal.id);
    let completed = 0;

    for (const member of members) {
      for (let d = 0; d < dayCount; d++) {
        const date = addDays(startDate, d);
        const entry = goalEntries.find(
          e => e.user_id === member.user_id && e.period_start === date
        );
        if (entry && isCompleted(Number(entry.value), goal.metric_type, goal.target_value)) {
          completed++;
        }
      }
    }

    return {
      goalId: goal.id,
      title: goal.title,
      completionRate: Math.round((completed / totalPossiblePerGoal) * 100),
      metricType: goal.metric_type,
    };
  });

  // Sort by completion rate (highest first)
  return stats.sort((a, b) => b.completionRate - a.completionRate);
}

/**
 * Get streak data for all members in a group
 */
async function getGroupStreaks(
  groupId: string,
  weekStartDate: string,
  weekEndDate: string
): Promise<StreakData[]> {
  const members = await db
    .selectFrom('group_memberships')
    .innerJoin('users', 'group_memberships.user_id', 'users.id')
    .where('group_memberships.group_id', '=', groupId)
    .where('group_memberships.status', '=', 'active')
    .select(['users.id as userId', 'users.display_name as displayName'])
    .execute();

  const goals = await db
    .selectFrom('goals')
    .select(['id', 'metric_type', 'target_value'])
    .where('group_id', '=', groupId)
    .where('cadence', '=', 'daily')
    .where('deleted_at', 'is', null)
    .execute();

  const streaks: StreakData[] = [];

  for (const member of members) {
    for (const goal of goals) {
      // Calculate current streak by going back from today
      let streakDays = 0;
      let currentDate = new Date().toISOString().slice(0, 10);

      while (true) {
        const entry = await db
          .selectFrom('progress_entries')
          .select(['value'])
          .where('goal_id', '=', goal.id)
          .where('user_id', '=', member.userId)
          .where('period_start', '=', currentDate)
          .executeTakeFirst();

        if (entry && isCompleted(Number(entry.value), goal.metric_type, goal.target_value)) {
          streakDays++;
          currentDate = subtractDays(currentDate, 1);
        } else {
          break;
        }

        // Safety limit
        if (streakDays > 365) break;
      }

      if (streakDays > 0) {
        // Check if milestone was reached this week
        const milestonedThisWeek = STREAK_MILESTONES.includes(streakDays) &&
          currentDate >= weekStartDate && currentDate <= weekEndDate;

        streaks.push({
          userId: member.userId,
          displayName: member.displayName,
          goalId: goal.id,
          streakDays,
          milestonedThisWeek,
        });
      }
    }
  }

  return streaks;
}

/**
 * Build highlights from week data using priority scoring
 */
export function buildHighlights(
  memberStats: MemberStats[],
  streakData: StreakData[],
  photoCount: number,
  reactionCount: number,
  nudgeCount: number,
  newMembers: Array<{ userId: string; displayName: string }>,
  newGoals: Array<{ goalId: string; title: string }>,
  heat: { heat_tier: number } | null,
  currentRate: number,
  previousRate: number
): RecapHighlight[] {
  const candidates: RecapHighlight[] = [];

  // Priority 10: Streak milestones reached this week
  const milestones = streakData.filter(s => s.milestonedThisWeek);
  for (const milestone of milestones) {
    candidates.push({
      type: 'streak_milestone',
      priority: 10,
      emoji: '🔥',
      text: `${milestone.displayName} — ${milestone.streakDays}-day streak!`,
      userId: milestone.userId,
      displayName: milestone.displayName,
    });
  }

  // Priority 9: Members with 100% completion
  const perfectMembers = memberStats.filter(m => m.rate === 100);
  for (const member of perfectMembers) {
    candidates.push({
      type: 'perfect_week',
      priority: 9,
      emoji: '⭐',
      text: `${member.displayName} — 100% completion this week`,
      userId: member.userId,
      displayName: member.displayName,
    });
  }

  // Priority 8: Longest streak in group
  if (streakData.length > 0) {
    const longestStreak = streakData.reduce((max, s) => s.streakDays > max.streakDays ? s : max);
    if (longestStreak.streakDays >= 7) {
      candidates.push({
        type: 'longest_streak',
        priority: 8,
        emoji: '🔥',
        text: `${longestStreak.displayName} — ${longestStreak.streakDays}-day streak (longest!)`,
        userId: longestStreak.userId,
        displayName: longestStreak.displayName,
      });
    }
  }

  // Priority 7: Most improved member
  const improved = memberStats
    .filter(m => m.previousRate !== undefined && m.rate > m.previousRate!)
    .sort((a, b) => (b.rate - b.previousRate!) - (a.rate - a.previousRate!));
  
  if (improved.length > 0) {
    const mostImproved = improved[0];
    const delta = mostImproved.rate - mostImproved.previousRate!;
    if (delta >= 10) {
      candidates.push({
        type: 'most_improved',
        priority: 7,
        emoji: '📈',
        text: `${mostImproved.displayName} — up ${delta}% this week`,
        userId: mostImproved.userId,
        displayName: mostImproved.displayName,
      });
    }
  }

  // Priority 6: Photo count (if >= 3)
  if (photoCount >= 3) {
    candidates.push({
      type: 'photos',
      priority: 6,
      emoji: '📸',
      text: `${photoCount} photo check-ins this week`,
    });
  }

  // Priority 5: Reaction count (if >= 5)
  if (reactionCount >= 5) {
    candidates.push({
      type: 'reactions',
      priority: 5,
      emoji: '💬',
      text: `${reactionCount} reactions shared`,
    });
  }

  // Priority 4: Nudge count (if >= 3)
  if (nudgeCount >= 3) {
    candidates.push({
      type: 'nudges',
      priority: 4,
      emoji: '👋',
      text: `${nudgeCount} nudges sent — the group's got each other's backs`,
    });
  }

  // Priority 3: New members
  for (const member of newMembers) {
    candidates.push({
      type: 'new_member',
      priority: 3,
      emoji: '👋',
      text: `Welcome ${member.displayName} to the group!`,
      userId: member.userId,
      displayName: member.displayName,
    });
  }

  // Priority 2: Heat tier increase (would need historical data - simplified)
  // Skipped for now as we don't track tier at start of week

  // Priority 1: New goals
  for (const goal of newGoals) {
    candidates.push({
      type: 'new_goal',
      priority: 1,
      emoji: '🎯',
      text: `New goal added: ${goal.title}`,
    });
  }

  // Sort by priority (highest first)
  candidates.sort((a, b) => b.priority - a.priority);

  // Apply rules: max 4 highlights, max 2 per member
  const selected: RecapHighlight[] = [];
  const memberAppearances = new Map<string, number>();

  for (const candidate of candidates) {
    if (selected.length >= 4) break;

    // Check member appearance limit
    if (candidate.userId) {
      const appearances = memberAppearances.get(candidate.userId) || 0;
      if (appearances >= 2) continue;
      memberAppearances.set(candidate.userId, appearances + 1);
    }

    selected.push(candidate);
  }

  // Fallback if fewer than 2 highlights
  if (selected.length < 2) {
    selected.push({
      type: 'fallback',
      priority: 0,
      emoji: '💪',
      text: 'Keep logging — consistency compounds!',
    });
  }

  return selected;
}

/**
 * Select primary headline for push notification using priority waterfall
 */
export function selectPrimaryHeadline(recapData: WeeklyRecapData): string {
  const { group, completionRate, highlights } = recapData;
  const rate = completionRate.current;
  const delta = completionRate.delta;

  // Priority 1: GCR >= 90%
  if (rate >= 90) {
    return `${group.name} hit ${rate}% this week! 💪`;
  }

  // Priority 2: Streak milestone
  const streakMilestone = highlights.find(h => h.type === 'streak_milestone');
  if (streakMilestone && streakMilestone.displayName) {
    const match = streakMilestone.text.match(/(\d+)-day streak/);
    const streakDays = match ? match[1] : '';
    return `${streakMilestone.displayName} hit a ${streakDays}-day streak! 🔥 Your group finished at ${rate}%.`;
  }

  // Priority 3: Group Heat tier increased (simplified - check if tier name is present and high)
  if (recapData.heat && recapData.heat.tier >= 5) {
    const tierIncreased = delta > 5; // Simplified check
    if (tierIncreased) {
      return `${group.name} is heating up — now at ${recapData.heat.tierName}! 🔥`;
    }
  }

  // Priority 4: Completion rate improved
  if (delta > 0) {
    return `${group.name} improved ${Math.abs(delta)}% this week — keep climbing! 📈`;
  }

  // Priority 5: Default
  return `${group.name} finished the week at ${rate}% — here's your recap.`;
}

/**
 * Send recap to all opted-in members of a group
 */
export async function sendRecapToGroup(
  groupId: string,
  recapData: WeeklyRecapData
): Promise<{ sent: number; skipped: number }> {
  // Get all active members who are opted in
  const members = await db
    .selectFrom('group_memberships')
    .select(['user_id', 'weekly_recap_enabled'])
    .where('group_id', '=', groupId)
    .where('status', '=', 'active')
    .execute();

  const headline = selectPrimaryHeadline(recapData);
  let sent = 0;
  let skipped = 0;

  for (const member of members) {
    if (!member.weekly_recap_enabled) {
      skipped++;
      continue;
    }

    // Insert user notification with full recap metadata
    await db
      .insertInto('user_notifications')
      .values({
        user_id: member.user_id,
        type: 'weekly_recap',
        actor_user_id: null,
        group_id: groupId,
        goal_id: null,
        progress_entry_id: null,
        metadata: {
          week_start: recapData.period.startDate,
          week_end: recapData.period.endDate,
          completion_rate: recapData.completionRate.current,
          completion_delta: recapData.completionRate.delta,
          highlights: recapData.highlights,
          goal_breakdown: recapData.goalBreakdown,
          heat: recapData.heat,
        },
        is_read: false,
      })
      .execute();

    sent++;
  }

  // Send FCM push notification
  try {
    await sendGroupNotification(
      groupId,
      {
        title: `📊 ${recapData.group.name} — Week in Review`,
        body: headline,
      },
      {
        type: 'weekly_recap',
        group_id: groupId,
        week_end: recapData.period.endDate,
      },
      { membershipStatus: 'active' } // Only to active members
    );
  } catch (error) {
    logger.error('Failed to send weekly recap FCM notification', {
      groupId,
      error: error instanceof Error ? error.message : 'Unknown error',
    });
    // Continue - don't fail if FCM fails
  }

  // Insert deduplication row
  await db
    .insertInto('weekly_recaps_sent')
    .values({
      group_id: groupId,
      week_end: recapData.period.endDate,
    })
    .execute();

  return { sent, skipped };
}

/**
 * Check if recap should be skipped for a group
 * Note: This checks if ANY week_end has been recorded for this group in the past 7 days
 * to prevent duplicate sends across different timezone calculations
 */
export async function shouldSkipGroup(
  groupId: string,
  weekEndDate: string
): Promise<{ skip: boolean; reason?: string }> {
  // Check if already sent for this specific week_end
  const alreadySent = await db
    .selectFrom('weekly_recaps_sent')
    .where('group_id', '=', groupId)
    .where('week_end', '=', weekEndDate)
    .executeTakeFirst();

  if (alreadySent) {
    return { skip: true, reason: 'already_sent' };
  }
  
  // Also check if sent in the past 6 days (to prevent multiple sends in same week)
  const sixDaysAgo = subtractDays(weekEndDate, 6);
  const recentSend = await db
    .selectFrom('weekly_recaps_sent')
    .where('group_id', '=', groupId)
    .where('week_end', '>=', sixDaysAgo)
    .where('week_end', '<=', weekEndDate)
    .executeTakeFirst();

  if (recentSend) {
    return { skip: true, reason: 'already_sent_this_week' };
  }

  // Check member count (need >= 2)
  const memberCount = await db
    .selectFrom('group_memberships')
    .where('group_id', '=', groupId)
    .where('status', '=', 'active')
    .select(db.fn.countAll<string>().as('count'))
    .executeTakeFirst();

  const members = memberCount ? parseInt(memberCount.count) : 0;
  if (members < 2) {
    return { skip: true, reason: 'insufficient_members' };
  }

  // Check daily goal count (need >= 1)
  const goalCount = await db
    .selectFrom('goals')
    .where('group_id', '=', groupId)
    .where('cadence', '=', 'daily')
    .where('deleted_at', 'is', null)
    .select(db.fn.countAll<string>().as('count'))
    .executeTakeFirst();

  const goals = goalCount ? parseInt(goalCount.count) : 0;
  if (goals === 0) {
    return { skip: true, reason: 'no_daily_goals' };
  }

  // Check if group was created this week
  const group = await db
    .selectFrom('groups')
    .select(['created_at'])
    .where('id', '=', groupId)
    .executeTakeFirst();

  if (group) {
    const weekStart = getMondayForSunday(weekEndDate);
    const createdDate = new Date(group.created_at).toISOString().slice(0, 10);
    if (createdDate >= weekStart) {
      return { skip: true, reason: 'group_too_new' };
    }
  }

  return { skip: false };
}

/**
 * Process weekly recaps for all eligible groups
 * This runs every 30 minutes on Sundays and sends recaps to groups
 * where members are experiencing Sunday 7 PM in their local timezone.
 *
 * In test mode, pass forceGroupId to bypass timezone filtering and process
 * a specific group directly. Pass forceWeekEnd (YYYY-MM-DD) to override the
 * week-end date used for the recap window.
 */
export async function processWeeklyRecaps(options?: {
  forceGroupId?: string;
  forceWeekEnd?: string;
}): Promise<{ processed: number; errors: number; skipped: number }> {
  // Test-only: force a specific group regardless of timezone window
  if (options?.forceGroupId) {
    const forceGroupId = options.forceGroupId;
    const weekEndDate = options.forceWeekEnd ?? (() => {
      // Default to previous Sunday in UTC
      const today = new Date();
      const dayOfWeek = today.getUTCDay();
      const daysBack = dayOfWeek === 0 ? 7 : dayOfWeek;
      const sunday = new Date(today);
      sunday.setUTCDate(today.getUTCDate() - daysBack);
      return sunday.toISOString().slice(0, 10);
    })();
    const weekStartDate = subtractDays(weekEndDate, 6);

    logger.info('Weekly recap: force processing group', { forceGroupId, weekStartDate, weekEndDate });

    try {
      // Still check deduplication to prevent double-sends
      const alreadySent = await db
        .selectFrom('weekly_recaps_sent')
        .where('group_id', '=', forceGroupId)
        .where('week_end', '=', weekEndDate)
        .executeTakeFirst();

      if (alreadySent) {
        logger.debug('Skipping force recap — already sent', { forceGroupId, weekEndDate });
        return { processed: 0, errors: 0, skipped: 1 };
      }

      const recapData = await buildWeeklyRecap(forceGroupId, weekStartDate, weekEndDate);
      const result = await sendRecapToGroup(forceGroupId, recapData);
      if (result.sent === 0) {
        return { processed: 0, errors: 0, skipped: 1 };
      }
      return { processed: 1, errors: 0, skipped: 0 };
    } catch (error) {
      logger.error('Failed to force-process weekly recap for group', {
        forceGroupId,
        error: error instanceof Error ? error.message : 'Unknown error',
      });
      return { processed: 0, errors: 1, skipped: 0 };
    }
  }
  logger.info('Starting weekly recap processing');

  // Get all groups with their member timezones
  const groupsWithTimezones = await db
    .selectFrom('groups as g')
    .innerJoin('group_memberships as gm', 'g.id', 'gm.group_id')
    .innerJoin('users as u', 'gm.user_id', 'u.id')
    .select([
      'g.id as group_id',
      db.fn.countAll<string>().as('member_count'),
      sql<string>`array_agg(DISTINCT COALESCE(u.timezone, 'UTC'))`.as('timezones'),
    ])
    .where('g.deleted_at', 'is', null)
    .where('u.deleted_at', 'is', null)
    .where('gm.status', '=', 'active')
    .groupBy('g.id')
    .execute();

  let processed = 0;
  let errors = 0;
  let skipped = 0;

  for (const group of groupsWithTimezones) {
    try {
      // Parse timezones array (PostgreSQL returns as string)
      const timezones: string[] = Array.isArray(group.timezones)
        ? group.timezones
        : (group.timezones as string).replace(/[{}]/g, '').split(',');

      // Check if any member is currently experiencing Sunday 7 PM (±30 min window)
      let shouldProcess = false;
      let selectedTimezone: string | null = null;

      for (const tz of timezones) {
        const localHour = getUserLocalHour(tz);
        const localDay = getUserLocalDayOfWeek(tz);

        // Process if it's Sunday (day 0) and between 7:00 PM and 7:29 PM
        if (localDay === 0 && localHour === 19) {
          shouldProcess = true;
          selectedTimezone = tz;
          break;
        }
      }

      if (!shouldProcess) {
        // Not the right time for this group
        continue;
      }

      // Use the first member's timezone that triggered processing
      const timezone = selectedTimezone || timezones[0];
      const weekEndDate = getPreviousSunday(timezone);
      const weekStartDate = getMondayForSunday(weekEndDate);

      logger.debug('Evaluating group for weekly recap', {
        groupId: group.group_id,
        timezone,
        weekStartDate,
        weekEndDate,
      });

      // Check skip conditions
      const skipCheck = await shouldSkipGroup(group.group_id, weekEndDate);
      if (skipCheck.skip) {
        skipped++;
        logger.debug('Skipping group for weekly recap', {
          groupId: group.group_id,
          reason: skipCheck.reason,
        });
        continue;
      }

      // Build and send recap
      const recapData = await buildWeeklyRecap(group.group_id, weekStartDate, weekEndDate);
      const result = await sendRecapToGroup(group.group_id, recapData);

      logger.info('Weekly recap sent', {
        groupId: group.group_id,
        timezone,
        weekEndDate,
        sent: result.sent,
        skipped: result.skipped,
      });

      processed++;
    } catch (error) {
      errors++;
      logger.error('Failed to process weekly recap for group', {
        groupId: group.group_id,
        error: error instanceof Error ? error.message : 'Unknown error',
      });
    }
  }

  logger.info('Weekly recap processing completed', {
    processed,
    errors,
    skipped,
    total: groupsWithTimezones.length,
  });

  return { processed, errors, skipped };
}
