import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';
import { createNotification } from './notification.service.js';

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

function daysInclusive(start: string, end: string): number {
  const s = new Date(`${start}T12:00:00Z`).getTime();
  const e = new Date(`${end}T12:00:00Z`).getTime();
  if (e < s) return 0;
  return Math.floor((e - s) / ONE_DAY_MS) + 1;
}

function cadencePeriodCount(cadence: string, start: string, end: string): number {
  const dayCount = daysInclusive(start, end);
  if (dayCount <= 0) return 0;

  if (cadence === 'daily') return dayCount;
  if (cadence === 'weekly') return Math.floor((dayCount - 1) / 7) + 1;
  if (cadence === 'monthly') {
    const [sy, sm] = start.split('-').map(Number);
    const [ey, em] = end.split('-').map(Number);
    return (ey - sy) * 12 + (em - sm) + 1;
  }
  if (cadence === 'yearly') {
    const sy = Number(start.slice(0, 4));
    const ey = Number(end.slice(0, 4));
    return (ey - sy) + 1;
  }
  return dayCount;
}

function maxDateStr(a: string, b: string): string {
  return a > b ? a : b;
}

function buildChallengeCompletionCardData(
  challengeName: string,
  completionRate: number,
  iconEmoji: string | null
): Record<string, unknown> {
  const percent = Math.round(completionRate * 100);
  return {
    milestone_type: 'challenge_completed',
    title: 'Challenge complete!',
    subtitle: challengeName,
    stat_value: String(percent),
    stat_label: 'completion rate',
    quote: 'You finished what you started',
    goal_icon_emoji: iconEmoji ?? '\u{1F3C6}',
    background_gradient: ['#1976D2', '#1565C0'],
    generated_at: new Date().toISOString(),
  };
}

export async function getChallengeCompletionRateForUser(
  groupId: string,
  userId: string,
  challengeStart: string,
  challengeEnd: string
): Promise<number> {
  const membership = await db
    .selectFrom('group_memberships')
    .select('joined_at')
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .where('status', '=', 'active')
    .executeTakeFirst();

  if (!membership) return 0;

  const joinedAtDate = new Date(membership.joined_at).toISOString().slice(0, 10);
  const effectiveStart = maxDateStr(challengeStart, joinedAtDate);
  const effectiveEnd = challengeEnd;

  if (effectiveStart > effectiveEnd) return 0;

  const goals = await db
    .selectFrom('goals')
    .select(['id', 'cadence', 'metric_type', 'target_value'])
    .where('group_id', '=', groupId)
    .where('deleted_at', 'is', null)
    .execute();

  if (goals.length === 0) return 0;

  const goalIds = goals.map((g) => g.id);
  const entries = await db
    .selectFrom('progress_entries')
    .select(['goal_id', 'period_start', 'value'])
    .where('user_id', '=', userId)
    .where('goal_id', 'in', goalIds)
    .where('period_start', '>=', effectiveStart)
    .where('period_start', '<=', effectiveEnd)
    .execute();

  const sumsByGoalPeriod = new Map<string, number>();
  const seenByGoalPeriod = new Set<string>();

  for (const entry of entries) {
    const period = typeof entry.period_start === 'string'
      ? entry.period_start
      : new Date(entry.period_start).toISOString().slice(0, 10);
    const key = `${entry.goal_id}:${period}`;
    const numericValue = typeof entry.value === 'string' ? Number(entry.value) : Number(entry.value ?? 0);
    sumsByGoalPeriod.set(key, (sumsByGoalPeriod.get(key) ?? 0) + numericValue);
    seenByGoalPeriod.add(key);
  }

  let totalExpected = 0;
  let totalCompleted = 0;

  for (const goal of goals) {
    const expectedForGoal = cadencePeriodCount(goal.cadence, effectiveStart, effectiveEnd);
    totalExpected += expectedForGoal;

    const periodKeys = [...seenByGoalPeriod].filter((k) => k.startsWith(`${goal.id}:`));
    if (goal.metric_type === 'binary') {
      totalCompleted += periodKeys.length;
    } else {
      const target = goal.target_value == null
        ? null
        : (typeof goal.target_value === 'string' ? Number(goal.target_value) : Number(goal.target_value));

      for (const key of periodKeys) {
        const sum = sumsByGoalPeriod.get(key) ?? 0;
        if (target == null || sum >= target) {
          totalCompleted += 1;
        }
      }
    }
  }

  if (totalExpected <= 0) return 0;
  return Math.max(0, Math.min(1, totalCompleted / totalExpected));
}

async function sendChallengeCompletionNotifications(groupId: string): Promise<number> {
  const challenge = await db
    .selectFrom('groups')
    .select(['id', 'name', 'icon_emoji', 'challenge_start_date', 'challenge_end_date'])
    .where('id', '=', groupId)
    .executeTakeFirst();

  if (!challenge || !challenge.challenge_start_date || !challenge.challenge_end_date) {
    return 0;
  }

  const members = await db
    .selectFrom('group_memberships')
    .select('user_id')
    .where('group_id', '=', groupId)
    .where('status', '=', 'active')
    .execute();

  if (members.length === 0) return 0;

  let created = 0;
  for (const member of members) {
    const completionRate = await getChallengeCompletionRateForUser(
      groupId,
      member.user_id,
      challenge.challenge_start_date,
      challenge.challenge_end_date
    );

    const cardData = buildChallengeCompletionCardData(challenge.name, completionRate, challenge.icon_emoji);
    const notificationId = await createNotification({
      user_id: member.user_id,
      type: 'milestone_achieved',
      actor_user_id: null,
      group_id: groupId,
      goal_id: null,
      metadata: {
        milestone_type: 'challenge_completed',
        challenge_name: challenge.name,
        completion_rate: completionRate,
      },
      shareable_card_data: cardData,
    });

    if (notificationId) {
      created += 1;
    }
  }

  return created;
}

export interface ChallengeStatusUpdateResult {
  activated: number;
  completed: number;
  completion_notifications: number;
}

export async function updateChallengeStatuses(): Promise<ChallengeStatusUpdateResult> {
  const today = new Date().toISOString().slice(0, 10);

  const activatedRows = await db
    .updateTable('groups')
    .set({ challenge_status: 'active', updated_at: new Date().toISOString() })
    .where('is_challenge', '=', true)
    .where('challenge_status', '=', 'upcoming')
    .where('challenge_start_date', '<=', today)
    .executeTakeFirst();
  const activated = Number(activatedRows.numUpdatedRows ?? 0);

  const completedGroups = await db
    .updateTable('groups')
    .set({ challenge_status: 'completed', updated_at: new Date().toISOString() })
    .where('is_challenge', '=', true)
    .where('challenge_status', '=', 'active')
    .where('challenge_end_date', '<', today)
    .returning(['id'])
    .execute();

  let completionNotifications = 0;
  for (const group of completedGroups) {
    await db
      .insertInto('group_activities')
      .values({
        group_id: group.id,
        user_id: null,
        activity_type: 'challenge_completed',
        metadata: null,
      })
      .execute();

    completionNotifications += await sendChallengeCompletionNotifications(group.id);
  }

  const result: ChallengeStatusUpdateResult = {
    activated,
    completed: completedGroups.length,
    completion_notifications: completionNotifications,
  };

  logger.info('updateChallengeStatuses completed', result);
  return result;
}
