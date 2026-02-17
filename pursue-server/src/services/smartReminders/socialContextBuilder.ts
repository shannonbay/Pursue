/**
 * Smart Reminders - Social Context Builder
 * 
 * Builds motivational social context for reminder messages.
 */

import { sql } from 'kysely';
import { db } from '../../database/index.js';
import type { SocialContext } from './types.js';

/**
 * Calculate user's streak for a specific goal
 */
async function calculateUserStreak(
  userId: string,
  goalId: string
): Promise<number> {
  // Get the most recent consecutive days where user logged progress
  const result = await db
    .selectFrom('progress_entries')
    .select(['period_start'])
    .where('user_id', '=', userId)
    .where('goal_id', '=', goalId)
    .orderBy('period_start', 'desc')
    .limit(60) // Check last 60 days max
    .execute();

  if (result.length === 0) {
    return 0;
  }

  // Count consecutive days from today/yesterday backwards
  let streak = 0;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  
  // Convert to YYYY-MM-DD strings
  const todayStr = today.toISOString().slice(0, 10);
  const yesterdayStr = yesterday.toISOString().slice(0, 10);

  // Check if the streak starts from today or yesterday
  const firstEntry = result[0].period_start;
  if (firstEntry !== todayStr && firstEntry !== yesterdayStr) {
    return 0; // Streak is broken
  }

  // Count consecutive days
  let expectedDate = new Date(firstEntry);
  for (const entry of result) {
    const entryDate = entry.period_start;
    const expectedStr = expectedDate.toISOString().slice(0, 10);
    
    if (entryDate === expectedStr) {
      streak++;
      expectedDate.setDate(expectedDate.getDate() - 1);
    } else if (entryDate < expectedStr) {
      // Skip duplicate entries for same date, or break if gap found
      const entryDateObj = new Date(entryDate);
      if (entryDateObj < expectedDate) {
        break; // Gap in streak
      }
    }
  }

  return streak;
}

/**
 * Build social context for a single goal
 */
export async function buildSocialContext(
  goalId: string,
  userId: string,
  userLocalDate: string
): Promise<SocialContext | null> {
  // Get goal and group info
  const goalInfo = await db
    .selectFrom('goals')
    .innerJoin('groups', 'goals.group_id', 'groups.id')
    .select([
      'goals.id as goal_id',
      'goals.group_id',
      'groups.name as group_name',
    ])
    .where('goals.id', '=', goalId)
    .where('goals.deleted_at', 'is', null)
    .where('groups.deleted_at', 'is', null)
    .executeTakeFirst();

  if (!goalInfo) {
    return null;
  }

  // Get member count and today's progress in one query
  const stats = await db
    .selectFrom('group_memberships')
    .leftJoin('progress_entries', (join) =>
      join
        .onRef('progress_entries.user_id', '=', 'group_memberships.user_id')
        .on('progress_entries.goal_id', '=', goalId)
        .on('progress_entries.period_start', '=', userLocalDate)
    )
    .select([
      sql<number>`COUNT(DISTINCT group_memberships.user_id)`.as('total_members'),
      sql<number>`COUNT(DISTINCT progress_entries.user_id)`.as('logged_today'),
    ])
    .where('group_memberships.group_id', '=', goalInfo.group_id)
    .where('group_memberships.status', '=', 'active')
    .executeTakeFirst();

  const totalMembers = Number(stats?.total_members ?? 0);
  const loggedToday = Number(stats?.logged_today ?? 0);

  // Calculate user's streak
  const userStreak = await calculateUserStreak(userId, goalId);

  // Find top performer (highest streak in the group for this goal)
  const topPerformerData = await db
    .selectFrom('group_memberships as gm')
    .innerJoin('users as u', 'gm.user_id', 'u.id')
    .select(['gm.user_id', 'u.display_name'])
    .where('gm.group_id', '=', goalInfo.group_id)
    .where('gm.status', '=', 'active')
    .where('u.deleted_at', 'is', null)
    .execute();

  let topPerformer: { name: string; currentStreak: number } | undefined;
  let maxStreak = 0;

  for (const member of topPerformerData) {
    if (member.user_id === userId) continue; // Skip current user
    const memberStreak = await calculateUserStreak(member.user_id, goalId);
    if (memberStreak > maxStreak) {
      maxStreak = memberStreak;
      topPerformer = {
        name: member.display_name,
        currentStreak: memberStreak,
      };
    }
  }

  return {
    groupId: goalInfo.group_id,
    groupName: goalInfo.group_name,
    totalMembers,
    loggedToday,
    percentComplete: totalMembers > 0 ? Math.round((loggedToday / totalMembers) * 100) : 0,
    userStreak,
    topPerformer: maxStreak > 0 ? topPerformer : undefined,
  };
}

/**
 * Build social context for multiple goals in bulk (optimized for batch processing)
 */
export async function buildBulkSocialContext(
  goalIds: string[],
  userLocalDates: Map<string, string> // goalId -> user's local date
): Promise<Map<string, SocialContext>> {
  if (goalIds.length === 0) {
    return new Map();
  }

  // Get goal and group info for all goals
  const goalInfos = await db
    .selectFrom('goals')
    .innerJoin('groups', 'goals.group_id', 'groups.id')
    .select([
      'goals.id as goal_id',
      'goals.group_id',
      'groups.name as group_name',
    ])
    .where('goals.id', 'in', goalIds)
    .where('goals.deleted_at', 'is', null)
    .where('groups.deleted_at', 'is', null)
    .execute();

  const result = new Map<string, SocialContext>();

  // Process each goal individually for now
  // (Could be optimized further with more complex queries if needed)
  for (const goalInfo of goalInfos) {
    const localDate = userLocalDates.get(goalInfo.goal_id) ?? new Date().toISOString().slice(0, 10);

    // Get member count and today's progress
    const stats = await db
      .selectFrom('group_memberships')
      .leftJoin('progress_entries', (join) =>
        join
          .onRef('progress_entries.user_id', '=', 'group_memberships.user_id')
          .on('progress_entries.goal_id', '=', goalInfo.goal_id)
          .on('progress_entries.period_start', '=', localDate)
      )
      .select([
        sql<number>`COUNT(DISTINCT group_memberships.user_id)`.as('total_members'),
        sql<number>`COUNT(DISTINCT progress_entries.user_id)`.as('logged_today'),
      ])
      .where('group_memberships.group_id', '=', goalInfo.group_id)
      .where('group_memberships.status', '=', 'active')
      .executeTakeFirst();

    const totalMembers = Number(stats?.total_members ?? 0);
    const loggedToday = Number(stats?.logged_today ?? 0);

    result.set(goalInfo.goal_id, {
      groupId: goalInfo.group_id,
      groupName: goalInfo.group_name,
      totalMembers,
      loggedToday,
      percentComplete: totalMembers > 0 ? Math.round((loggedToday / totalMembers) * 100) : 0,
      userStreak: 0, // Will be filled per-user at send time
    });
  }

  return result;
}
