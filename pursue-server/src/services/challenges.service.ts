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
  getDateInTimezone,
} from '../utils/timezone.js';
import { sql } from 'kysely';

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

/**
 * Send challenge countdown notifications (starts tomorrow, day 1, halfway, 3 days left, final day)
 */
export async function sendChallengeCountdownNotifications(now: Date = new Date()): Promise<number> {
  // Find all active or upcoming challenges and their members
  const challengesAndMembers = await db
    .selectFrom('groups as g')
    .innerJoin('group_memberships as gm', 'g.id', 'gm.group_id')
    .innerJoin('users as u', 'gm.user_id', 'u.id')
    .select([
      'g.id as group_id',
      'g.name',
      'g.challenge_start_date',
      'g.challenge_end_date',
      'gm.user_id',
      'u.timezone',
    ])
    .where('g.is_challenge', '=', true)
    .where('g.challenge_status', 'in', ['upcoming', 'active'])
    .where('gm.status', '=', 'active')
    .execute();

  let sent = 0;
  for (const row of challengesAndMembers) {
    if (!row.challenge_start_date || !row.challenge_end_date) continue;

    const timezone = row.timezone ?? 'UTC';
    const userLocalDate = getDateInTimezone(timezone, now);
    
    let type: 'challenge_starts_tomorrow' | 'challenge_started' | 'challenge_countdown' | null = null;
    let countdownType: string | null = null;

    const startDate = row.challenge_start_date;
    const endDate = row.challenge_end_date;
    
    if (userLocalDate === addDaysDateOnly(startDate, -1)) {
      type = 'challenge_starts_tomorrow';
    } else if (userLocalDate === startDate) {
      type = 'challenge_started';
    } else if (userLocalDate === endDate) {
      type = 'challenge_countdown';
      countdownType = 'final_day';
    } else if (userLocalDate === addDaysDateOnly(endDate, -2)) {
      type = 'challenge_countdown';
      countdownType = 'three_days_left';
    } else {
      const totalDays = daysInclusive(startDate, endDate);
      const halfwayDay = Math.floor(totalDays / 2);
      if (userLocalDate === addDaysDateOnly(startDate, halfwayDay)) {
        type = 'challenge_countdown';
        countdownType = 'halfway';
      }
    }

    if (type) {
      const metadata = countdownType ? { countdown_type: countdownType } : {};
      
      const existing = await db
        .selectFrom('user_notifications')
        .select('id')
        .where('user_id', '=', row.user_id)
        .where('group_id', '=', row.group_id)
        .where('type', '=', type)
        .where((eb) => {
          if (countdownType) {
            return eb('metadata', '@>', metadata);
          }
          // For types without countdownType, we check if they were sent "today" or "recently" 
          // Since it's once per day milestones, we can just check if any exists for this group/user/type.
          // In a real scenario, you might want to check the created_at date to allow repeating challenges.
          // But for a single challenge instance, group_id + user_id + type is enough.
          return eb.val(true);
        })
        .executeTakeFirst();

      if (!existing) {
        await createNotification({
          user_id: row.user_id,
          type,
          group_id: row.group_id,
          metadata,
        });
        sent++;
      }
    }
  }

  return sent;
}

export async function processChallengeSuggestions(now: Date = new Date()): Promise<number> {
  const thirtyDaysAgo = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
  const sevenDaysAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
  const sevenDaysAgoStr = sevenDaysAgo.toISOString().slice(0, 10);

  // 1. Identify users who:
  // - Have at least 1 active group membership
  // - Have never created or joined a challenge
  // - Have not had a suggestion in the last 30 days
  // - Are active (logged in 5 of last 7 days)
  
  // Note: we check for any challenge membership to stop suggestions forever once they join one
  const candidates = await db
    .selectFrom('users as u')
    .innerJoin('group_memberships as gm', 'u.id', 'gm.user_id')
    .select([
      'u.id',
      'u.display_name',
      'u.timezone',
    ])
    .distinct()
    .where('u.deleted_at', 'is', null)
    .where('gm.status', '=', 'active')
    // No challenge involvement
    .where(({ not, exists, selectFrom }) =>
      not(
        exists(
          selectFrom('group_memberships as cgm')
            .innerJoin('groups as cg', 'cg.id', 'cgm.group_id')
            .whereRef('cgm.user_id', '=', 'u.id')
            .where('cg.is_challenge', '=', true)
        )
      )
    )
    // No recent suggestion
    .where(({ not, exists, selectFrom }) =>
      not(
        exists(
          selectFrom('challenge_suggestion_log as csl')
            .whereRef('csl.user_id', '=', 'u.id')
            .where((eb) =>
              eb.or([
                eb('csl.sent_at', '>', thirtyDaysAgo),
                eb('csl.dismissed_at', '>', thirtyDaysAgo)
              ])
            )
        )
      )
    )
    // Logged in 5 of last 7 days
    .where(({ exists, selectFrom }) =>
      exists(
        selectFrom('progress_entries as pe')
          .select(sql`count(distinct period_start)`.as('days_logged'))
          .whereRef('pe.user_id', '=', 'u.id')
          .where('pe.period_start', '>=', sevenDaysAgoStr)
          .having(sql`count(distinct period_start)`, '>=', 5)
      )
    )
    .execute();

  let sent = 0;
  for (const user of candidates) {
    // Check if it's a good time to send (during their typical active window)
    const pattern = await db
      .selectFrom('user_logging_patterns')
      .select(['typical_hour_start', 'typical_hour_end'])
      .where('user_id', '=', user.id)
      .where('day_of_week', '=', -1) // general pattern
      .orderBy('confidence_score', 'desc')
      .executeTakeFirst();

    const timezone = user.timezone ?? 'UTC';
    let userHour: number;
    try {
      const userTime = new Intl.DateTimeFormat('en-US', {
        hour: 'numeric',
        hour12: false,
        timeZone: timezone
      }).format(now);
      userHour = parseInt(userTime, 10);
    } catch {
      userHour = now.getUTCHours();
    }

    const startHour = pattern?.typical_hour_start ?? 9;
    const endHour = pattern?.typical_hour_end ?? 21;

    // Send only if user is within their active window (or default 9-21)
    if (userHour >= startHour && userHour <= endHour) {
      const notificationId = await createNotification({
        user_id: user.id,
        type: 'challenge_suggestion',
        actor_user_id: null,
        group_id: null,
        goal_id: null,
        metadata: {},
      });

      if (notificationId) {
        await db
          .insertInto('challenge_suggestion_log')
          .values({
            user_id: user.id,
            sent_at: new Date().toISOString(),
          })
          .onConflict((oc) =>
            oc.column('user_id').doUpdateSet({
              sent_at: new Date().toISOString(),
              dismissed_at: null,
            })
          )
          .execute();
        
        sent++;
      }
    }
  }

  if (sent > 0) {
    logger.info(`Sent ${sent} challenge suggestions`);
  }
  return sent;
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
  countdown_notifications: number;
}

export async function updateChallengeStatuses(now: Date = new Date()): Promise<ChallengeStatusUpdateResult> {
  const activationBoundaryDate = getCurrentDateUtcPlus14(now);
  const completionBoundaryDate = getCurrentDateUtcMinus12(now);

  const countdown_notifications = await sendChallengeCountdownNotifications(now);

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
    countdown_notifications,
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
