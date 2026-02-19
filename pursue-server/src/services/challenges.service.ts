import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';
import { createNotification } from './notification.service.js';
import { sendNotificationToUser } from './fcm.service.js';
import { getOrCreateReferralToken } from './referral.service.js';
import {
  addDaysDateOnly,
  computeDeferredSendAt,
  getCurrentDateUtcMinus12,
  getCurrentDateUtcPlus14,
} from '../utils/timezone.js';

const ONE_DAY_MS = 24 * 60 * 60 * 1000;
const SHARE_BASE_URL = process.env.PURSUE_SHARE_BASE_URL ?? 'https://getpursue.app';
const CARD_ASSET_BASE_URL = process.env.PURSUE_CARD_ASSET_BASE_URL ?? 'https://api.getpursue.app';
const CHALLENGE_COMPLETION_BACKGROUND_PATH = '/assets/challenge_completion_background.png';

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

function buildChallengeCompletionShareUrl(referralToken: string): string {
  return `${SHARE_BASE_URL}?utm_source=share&utm_medium=challenge_completion_card&utm_campaign=challenge_completed&ref=${encodeURIComponent(referralToken)}`;
}

function buildChallengeCompletionQrUrl(referralToken: string): string {
  return `${SHARE_BASE_URL}?utm_source=qr&utm_medium=challenge_completion_card&utm_campaign=challenge_completed&ref=${encodeURIComponent(referralToken)}`;
}

function buildChallengeCompletionBackgroundUrl(): string {
  const normalizedBase = CARD_ASSET_BASE_URL.replace(/\/+$/, '');
  return `${normalizedBase}${CHALLENGE_COMPLETION_BACKGROUND_PATH}`;
}

function buildChallengeCompletionCardData(
  challengeName: string,
  completionRate: number,
  iconEmoji: string | null,
  referralToken: string
): Record<string, unknown> {
  const percent = Math.round(completionRate * 100);
  return {
    card_type: 'challenge_completion',
    milestone_type: 'challenge_completed',
    title: 'Challenge complete!',
    subtitle: challengeName,
    stat_value: String(percent),
    stat_label: 'completion rate',
    quote: 'You finished what you started',
    goal_icon_emoji: iconEmoji ?? '\u{1F3C6}',
    background_gradient: ['#1976D2', '#1565C0'],
    background_image_url: buildChallengeCompletionBackgroundUrl(),
    referral_token: referralToken,
    share_url: buildChallengeCompletionShareUrl(referralToken),
    qr_url: buildChallengeCompletionQrUrl(referralToken),
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
    const referralToken = await getOrCreateReferralToken(member.user_id);

    const cardData = buildChallengeCompletionCardData(
      challenge.name,
      completionRate,
      challenge.icon_emoji,
      referralToken
    );
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

async function getPreferredReminderHour(userId: string): Promise<number> {
  const pattern = await db
    .selectFrom('user_logging_patterns')
    .select(['typical_hour_start'])
    .where('user_id', '=', userId)
    .where('day_of_week', '=', -1)
    .orderBy('confidence_score', 'desc')
    .executeTakeFirst();

  const hour = Number(pattern?.typical_hour_start ?? 9);
  if (Number.isNaN(hour)) return 9;
  return Math.min(23, Math.max(0, hour));
}

async function queueChallengeCompletionPushes(groupId: string): Promise<number> {
  const group = await db
    .selectFrom('groups')
    .select(['id', 'challenge_end_date'])
    .where('id', '=', groupId)
    .executeTakeFirst();

  if (!group?.challenge_end_date) {
    return 0;
  }

  const members = await db
    .selectFrom('group_memberships as gm')
    .innerJoin('users as u', 'u.id', 'gm.user_id')
    .select([
      'gm.user_id',
      'u.timezone',
    ])
    .where('gm.group_id', '=', groupId)
    .where('gm.status', '=', 'active')
    .execute();

  if (members.length === 0) return 0;

  let queued = 0;
  for (const member of members) {
    const timezone = member.timezone ?? 'UTC';
    const preferredHour = await getPreferredReminderHour(member.user_id);
    const baseLocalDate = addDaysDateOnly(group.challenge_end_date, 1);
    const sendAt = computeDeferredSendAt(timezone, baseLocalDate, preferredHour);

    await db
      .insertInto('challenge_completion_push_queue')
      .values({
        group_id: groupId,
        user_id: member.user_id,
        send_at: sendAt,
        status: 'pending',
        attempt_count: 0,
        last_error: null,
      })
      .onConflict((oc) =>
        oc.columns(['group_id', 'user_id']).doNothing()
      )
      .execute();

    queued += 1;
  }

  return queued;
}

export interface ChallengeStatusUpdateResult {
  activated: number;
  completed: number;
  completion_notifications: number;
  completion_pushes_queued: number;
}

export async function updateChallengeStatuses(now: Date = new Date()): Promise<ChallengeStatusUpdateResult> {
  const activationBoundaryDate = getCurrentDateUtcPlus14(now);
  const completionBoundaryDate = getCurrentDateUtcMinus12(now);

  const activatedRows = await db
    .updateTable('groups')
    .set({ challenge_status: 'active', updated_at: new Date().toISOString() })
    .where('is_challenge', '=', true)
    .where('challenge_status', '=', 'upcoming')
    .where('challenge_start_date', '<=', activationBoundaryDate)
    .executeTakeFirst();
  const activated = Number(activatedRows.numUpdatedRows ?? 0);

  const completedGroups = await db
    .updateTable('groups')
    .set({ challenge_status: 'completed', updated_at: new Date().toISOString() })
    .where('is_challenge', '=', true)
    .where('challenge_status', '=', 'active')
    .where('challenge_end_date', '<', completionBoundaryDate)
    .returning(['id'])
    .execute();

  let completionNotifications = 0;
  let completionPushesQueued = 0;
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
    completionPushesQueued += await queueChallengeCompletionPushes(group.id);
  }

  const result: ChallengeStatusUpdateResult = {
    activated,
    completed: completedGroups.length,
    completion_notifications: completionNotifications,
    completion_pushes_queued: completionPushesQueued,
  };

  logger.info('updateChallengeStatuses completed', result);
  return result;
}

export interface ProcessChallengeCompletionPushesResult {
  processed: number;
  sent: number;
  failed: number;
}

export async function processChallengeCompletionPushes(now: Date = new Date()): Promise<ProcessChallengeCompletionPushesResult> {
  const dueRows = await db
    .selectFrom('challenge_completion_push_queue as q')
    .innerJoin('groups as g', 'g.id', 'q.group_id')
    .select([
      'q.id',
      'q.user_id',
      'q.group_id',
      'q.attempt_count',
      'g.name as group_name',
    ])
    .where('q.status', '=', 'pending')
    .where('q.send_at', '<=', now)
    .orderBy('q.send_at', 'asc')
    .limit(200)
    .execute();

  let sent = 0;
  let failed = 0;

  for (const row of dueRows) {
    try {
      await sendNotificationToUser(
        row.user_id,
        {
          title: 'Challenge Complete!',
          body: `You finished ${row.group_name}. Share your result!`,
        },
        {
          type: 'challenge_completed',
          group_id: row.group_id,
        }
      );

      await db
        .updateTable('challenge_completion_push_queue')
        .set({
          status: 'sent',
          attempt_count: row.attempt_count + 1,
          last_error: null,
          updated_at: new Date().toISOString(),
        })
        .where('id', '=', row.id)
        .execute();
      sent += 1;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      await db
        .updateTable('challenge_completion_push_queue')
        .set({
          status: row.attempt_count + 1 >= 3 ? 'failed' : 'pending',
          attempt_count: row.attempt_count + 1,
          last_error: message.slice(0, 500),
          updated_at: new Date().toISOString(),
        })
        .where('id', '=', row.id)
        .execute();
      failed += 1;
    }
  }

  const result: ProcessChallengeCompletionPushesResult = {
    processed: dueRows.length,
    sent,
    failed,
  };

  logger.info('processChallengeCompletionPushes completed', result);
  return result;
}
