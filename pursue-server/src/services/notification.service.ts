import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';
import { sendNotificationToUser } from './fcm.service.js';
import { getOrCreateReferralToken } from './referral.service.js';

const SHARE_BASE_URL = process.env.PURSUE_SHARE_BASE_URL ?? 'https://getpursue.app';
const MILESTONE_ASSET_BASE_URL = process.env.PURSUE_MILESTONE_ASSET_BASE_URL ?? 'https://storage.googleapis.com/pursue-assets';
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

export const SHAREABLE_MILESTONE_CARDS_PREMIUM_REQUIRED = false;

export type MilestoneKey =
  | 'first_log'
  | 'streak_7'
  | 'streak_14'
  | 'streak_30'
  | 'streak_365'
  | 'total_logs_100'
  | 'total_logs_500';

interface MilestoneConfig {
  key: MilestoneKey;
  condition: boolean;
  count: number;
  cooldownDays: number | null;
  goalScoped: boolean;
  metadata: Record<string, unknown>;
}

export interface ShareableCardData {
  milestone_type: 'first_log' | 'streak' | 'total_logs';
  milestone_key: MilestoneKey;
  title: string;
  subtitle: string;
  stat_value: string;
  stat_label: string;
  quote: string;
  goal_icon_emoji: string;
  background_gradient: [string, string];
  referral_token: string;
  share_url: string;
  qr_url: string;
  generated_at: string;
}

function getMilestoneTypeFromKey(milestoneKey: MilestoneKey): ShareableCardData['milestone_type'] {
  if (milestoneKey === 'first_log') return 'first_log';
  if (milestoneKey.startsWith('streak_')) return 'streak';
  return 'total_logs';
}

function buildShareUrl(token: string, milestoneKey: MilestoneKey): string {
  const campaign = encodeURIComponent(milestoneKey);
  const ref = encodeURIComponent(token);
  return `${SHARE_BASE_URL}?utm_source=share&utm_medium=milestone_card&utm_campaign=${campaign}&ref=${ref}`;
}

function buildQrUrl(token: string, milestoneKey: MilestoneKey): string {
  const campaign = encodeURIComponent(milestoneKey);
  const ref = encodeURIComponent(token);
  return `${SHARE_BASE_URL}?ref=${ref}&utm_source=qr&utm_medium=milestone_card&utm_campaign=${campaign}`;
}

function getMilestonePreviewImageUrl(milestoneKey: MilestoneKey): string {
  const previewImages: Record<MilestoneKey, string> = {
    first_log: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-first-log.png`,
    streak_7: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-streak-7.png`,
    streak_14: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-streak-14.png`,
    streak_30: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-streak-30.png`,
    streak_365: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-streak-365.png`,
    total_logs_100: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-total-100.png`,
    total_logs_500: `${MILESTONE_ASSET_BASE_URL}/milestone-preview-total-500.png`,
  };

  return previewImages[milestoneKey];
}

function getMilestoneKeyFromMetadata(metadata: Record<string, unknown> | null | undefined): MilestoneKey | null {
  const explicit = metadata?.milestone_key;
  if (typeof explicit === 'string') {
    const knownKeys: MilestoneKey[] = [
      'first_log',
      'streak_7',
      'streak_14',
      'streak_30',
      'streak_365',
      'total_logs_100',
      'total_logs_500',
    ];
    if (knownKeys.includes(explicit as MilestoneKey)) {
      return explicit as MilestoneKey;
    }
  }

  const milestoneType = metadata?.milestone_type;
  const streakCount = metadata?.streak_count;
  const count = metadata?.count;

  if (milestoneType === 'first_log') return 'first_log';
  if (milestoneType === 'streak' && typeof streakCount === 'number') {
    if (streakCount === 7) return 'streak_7';
    if (streakCount === 14) return 'streak_14';
    if (streakCount === 30) return 'streak_30';
    if (streakCount === 365) return 'streak_365';
  }
  if (milestoneType === 'total_logs' && typeof count === 'number') {
    if (count === 100) return 'total_logs_100';
    if (count === 500) return 'total_logs_500';
  }
  return null;
}

export function generateCardData(
  milestoneKey: MilestoneKey,
  goalTitle: string | null,
  count: number,
  referralToken: string
): ShareableCardData {
  const milestoneType = getMilestoneTypeFromKey(milestoneKey);
  const templates = {
    first_log: {
      title: 'First step taken',
      quote: 'Every journey begins somewhere',
      stat_label: 'progress logged',
    },
    streak_7: {
      title: '7-day streak!',
      quote: 'Consistency is everything',
      stat_label: 'days in a row',
    },
    streak_14: {
      title: 'Two weeks strong!',
      quote: 'Habits are forming',
      stat_label: 'days in a row',
    },
    streak_30: {
      title: '30-day streak!',
      quote: 'A month of dedication',
      stat_label: 'days in a row',
    },
    streak_365: {
      title: 'One year. Every day.',
      quote: 'This is who you are now',
      stat_label: 'days in a row',
    },
    total_logs_100: {
      title: '100 logs milestone',
      quote: 'Proof that showing up works',
      stat_label: 'total logs',
    },
    total_logs_500: {
      title: '500 logs milestone',
      quote: 'Dedication has a number',
      stat_label: 'total logs',
    },
  } as const;
  const gradients: Record<MilestoneKey, [string, string]> = {
    first_log: ['#1E88E5', '#1565C0'],
    streak_7: ['#F57C00', '#E65100'],
    streak_14: ['#00897B', '#00695C'],
    streak_30: ['#F57C00', '#E65100'],
    streak_365: ['#FF6F00', '#E65100'],
    total_logs_100: ['#7B1FA2', '#4A148C'],
    total_logs_500: ['#6A1B9A', '#4A148C'],
  };

  const subtitle = milestoneType === 'total_logs' ? 'Pursue Goals' : (goalTitle ?? 'Pursue Goals');

  return {
    milestone_type: milestoneType,
    milestone_key: milestoneKey,
    title: templates[milestoneKey].title,
    subtitle,
    stat_value: String(count),
    stat_label: templates[milestoneKey].stat_label,
    quote: templates[milestoneKey].quote,
    goal_icon_emoji: '\u{1F3AF}',
    background_gradient: gradients[milestoneKey],
    referral_token: referralToken,
    share_url: buildShareUrl(referralToken, milestoneKey),
    qr_url: buildQrUrl(referralToken, milestoneKey),
    generated_at: new Date().toISOString(),
  };
}

async function canAwardMilestone(
  userId: string,
  milestoneKey: string,
  cooldownDays: number | null
): Promise<boolean> {
  const existing = await db
    .selectFrom('user_milestone_grants')
    .select('granted_at')
    .where('user_id', '=', userId)
    .where('milestone_key', '=', milestoneKey)
    .executeTakeFirst();

  if (!existing) return true;
  if (cooldownDays === null) return false;

  const now = Date.now();
  const grantedAt = new Date(existing.granted_at).getTime();
  const daysSinceGrant = Math.floor((now - grantedAt) / ONE_DAY_MS);
  return daysSinceGrant >= cooldownDays;
}

async function upsertMilestoneGrant(userId: string, milestoneKey: string, goalId: string | null): Promise<void> {
  await db
    .insertInto('user_milestone_grants')
    .values({
      user_id: userId,
      milestone_key: milestoneKey,
      goal_id: goalId,
      granted_at: new Date().toISOString(),
    })
    .onConflict((oc) =>
      oc.columns(['user_id', 'milestone_key']).doUpdateSet({
        granted_at: new Date().toISOString(),
        goal_id: goalId,
      })
    )
    .execute();
}

export type NotificationType =
  | 'reaction_received'
  | 'nudge_received'
  | 'membership_approved'
  | 'membership_rejected'
  | 'promoted_to_admin'
  | 'removed_from_group'
  | 'milestone_achieved'
  | 'join_request_received'
  | 'challenge_suggestion'
  | 'challenge_starts_tomorrow'
  | 'challenge_started'
  | 'challenge_countdown';

export interface CreateNotificationParams {
  user_id: string;
  type: NotificationType;
  actor_user_id?: string | null;
  group_id?: string | null;
  goal_id?: string | null;
  progress_entry_id?: string | null;
  metadata?: Record<string, unknown> | null;
  shareable_card_data?: Record<string, unknown> | null;
}

/**
 * Create a notification and send FCM push.
 * Failures are logged but do not throw (per spec: do not block primary action).
 */
export async function createNotification(params: CreateNotificationParams): Promise<string | null> {
  try {
    logger.debug('createNotification', { userId: params.user_id, type: params.type });

    const row = await db
      .insertInto('user_notifications')
      .values({
        user_id: params.user_id,
        type: params.type,
        actor_user_id: params.actor_user_id ?? null,
        group_id: params.group_id ?? null,
        goal_id: params.goal_id ?? null,
        progress_entry_id: params.progress_entry_id ?? null,
        metadata: params.metadata ?? null,
        shareable_card_data: params.shareable_card_data ?? null,
      })
      .returning(['id', 'is_read'])
      .executeTakeFirst();

    if (!row) return null;

    logger.debug('createNotification complete', { notificationId: row.id, isRead: row.is_read });

    // Send FCM in background - do not block
    sendFcmForNotification(row.id, params).catch((err) => {
      logger.error('Failed to send FCM for notification', {
        notificationId: row.id,
        type: params.type,
        error: err instanceof Error ? err.message : String(err),
      });
    });

    return row.id;
  } catch (err) {
    logger.error('Failed to create notification', {
      type: params.type,
      userId: params.user_id,
      error: err instanceof Error ? err.message : String(err),
    });
    return null;
  }
}

async function sendFcmForNotification(
  notificationId: string,
  params: CreateNotificationParams
): Promise<void> {
  const { user_id, type, actor_user_id, group_id, goal_id, metadata } = params;

  const [group, goal, actor] = await Promise.all([
    group_id
      ? db.selectFrom('groups').select('name').where('id', '=', group_id).executeTakeFirst()
      : Promise.resolve(null),
    goal_id
      ? db.selectFrom('goals').select('title').where('id', '=', goal_id).executeTakeFirst()
      : Promise.resolve(null),
    actor_user_id
      ? db.selectFrom('users').select('display_name').where('id', '=', actor_user_id).executeTakeFirst()
      : Promise.resolve(null),
  ]);

  const groupName = group?.name ?? 'Your group';
  const goalTitle = goal?.title ?? 'your goal';
  const actorName = actor?.display_name ?? 'Someone';

  let notification: { title: string; body: string; image?: string };
  const data: Record<string, string> = {
    type,
    notification_id: notificationId,
  };

  switch (type) {
    case 'reaction_received': {
      const emoji = (metadata?.emoji as string) ?? '\u{1F44D}';
      notification = {
        title: groupName,
        body: `${actorName} reacted ${emoji} to your ${goalTitle} log`,
      };
      if (group_id) data.group_id = group_id;
      break;
    }
    case 'nudge_received':
      notification = {
        title: `${actorName} is rooting for you!`,
        body: `Don't forget your ${goalTitle} in ${groupName}`,
      };
      if (goal_id) data.goal_id = goal_id;
      break;
    case 'membership_approved':
      notification = {
        title: 'Request Approved',
        body: `You've been approved to join ${groupName}`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'membership_rejected':
      notification = {
        title: 'Request Not Approved',
        body: `Your request to join ${groupName} was not approved`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'promoted_to_admin':
      notification = {
        title: groupName,
        body: `${actorName} made you an admin`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'removed_from_group':
      notification = {
        title: 'Removed from Group',
        body: `You were removed from ${groupName}`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'milestone_achieved': {
      const milestoneType = (metadata?.milestone_type as string) ?? 'milestone';
      const streakCount = metadata?.streak_count as number | undefined;
      const count = metadata?.count as number | undefined;
      const milestoneKey = getMilestoneKeyFromMetadata(metadata);
      const challengeName = (metadata?.challenge_name as string | undefined) ?? groupName;
      const completionRate = metadata?.completion_rate as number | undefined;

      if (milestoneType === 'first_log') {
        notification = {
          title: '\u{1F389} First log!',
          body: "You've logged your first activity. Tap to share!",
        };
      } else if (milestoneType === 'streak' && streakCount) {
        notification = {
          title: `\u{1F389} ${streakCount}-day streak!`,
          body: `You've logged ${goalTitle} ${streakCount} days in a row. Tap to share!`,
        };
      } else if (milestoneType === 'total_logs' && count) {
        notification = {
          title: `\u{1F389} ${count} total logs!`,
          body: `You've reached ${count} total logs. Tap to share!`,
        };
      } else if (milestoneType === 'challenge_completed') {
        const pct = completionRate != null ? Math.round(completionRate * 100) : null;
        notification = {
          title: '\u{1F389} Challenge Complete!',
          body: pct == null
            ? `You finished ${challengeName}. Tap to share!`
            : `You finished ${challengeName} with a ${pct}% completion rate. Tap to share!`,
        };
      } else {
        notification = {
          title: '\u{1F389} Milestone achieved!',
          body: `Great progress on ${goalTitle}!`,
        };
      }

      if (milestoneKey) {
        data.milestone_key = milestoneKey;
        notification.image = getMilestonePreviewImageUrl(milestoneKey);
      }
      if (group_id) data.group_id = group_id;
      data.shareable = 'true';
      break;
    }
    case 'join_request_received':
      notification = {
        title: 'New Join Request',
        body: `${actorName} wants to join ${groupName}`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'challenge_suggestion':
      notification = {
        title: 'Ready for a challenge? \u{1F3C6}',
        body: 'Start a 30-day challenge with your friends. Pick from dozens of templates!',
      };
      break;
    case 'challenge_starts_tomorrow':
      notification = {
        title: '\u{1F3C1} Challenge starts tomorrow!',
        body: `${groupName} kicks off tomorrow. Make sure your group is ready!`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'challenge_started':
      notification = {
        title: '\u{1F680} Day 1 \u2014 let\'s go!',
        body: `Your ${groupName} challenge starts today. Log your first entry!`,
      };
      if (group_id) data.group_id = group_id;
      break;
    case 'challenge_countdown': {
      const milestoneType = metadata?.countdown_type as string;
      if (milestoneType === 'halfway') {
        notification = {
          title: '\u{1F525} Halfway there!',
          body: `You're halfway through ${groupName}! Keep pushing.`,
        };
      } else if (milestoneType === 'three_days_left') {
        notification = {
          title: '\u23F0 3 days left!',
          body: `Just 3 more days in your ${groupName} challenge. Don't stop now!`,
        };
      } else if (milestoneType === 'final_day') {
        notification = {
          title: '\u{1F3C6} Last day!',
          body: `It's the final day of ${groupName}. Finish strong!`,
        };
      } else {
        notification = {
          title: 'Challenge Update',
          body: `Keep up the great work in ${groupName}!`,
        };
      }
      if (group_id) data.group_id = group_id;
      break;
    }
    default:
      return;
  }

  await sendNotificationToUser(user_id, notification, data);
}

export type HeatMilestoneType = 'heat_tier_up' | 'heat_supernova_reached' | 'heat_streak_milestone';

export interface HeatMilestoneMetadata {
  tier_name?: string;
  streak_days?: number;
}

/**
 * Send FCM push for a group heat milestone to all active members.
 * Does NOT create user_notifications (inbox) entries - heat is a group-level ambient event.
 * Fire-and-forget; failures are logged and do not throw.
 */
export async function sendHeatMilestonePush(
  groupId: string,
  type: HeatMilestoneType,
  metadata: HeatMilestoneMetadata
): Promise<void> {
  try {
    const [group, members] = await Promise.all([
      db.selectFrom('groups').select('name').where('id', '=', groupId).executeTakeFirst(),
      db
        .selectFrom('group_memberships')
        .select('user_id')
        .where('group_id', '=', groupId)
        .where('status', '=', 'active')
        .execute(),
    ]);

    const groupName = group?.name ?? 'Your group';
    const memberIds = members.map((m) => m.user_id);
    if (memberIds.length === 0) return;

    let title: string;
    let body: string;
    switch (type) {
      case 'heat_tier_up':
        title = groupName;
        body = `Group heat is rising! Now at ${metadata.tier_name ?? 'higher'}.`;
        break;
      case 'heat_supernova_reached':
        title = groupName;
        body = 'SUPERNOVA! The group is burning blue-hot!';
        break;
      case 'heat_streak_milestone':
        title = groupName;
        body = `${metadata.streak_days ?? 0}-day heat streak! Keep the momentum!`;
        break;
      default:
        return;
    }

    const data: Record<string, string> = { type, group_id: groupId };

    await Promise.all(
      memberIds.map((user_id) =>
        sendNotificationToUser(user_id, { title, body }, data).catch((err) => {
          logger.error('Failed to send heat milestone FCM', {
            groupId,
            type,
            userId: user_id,
            error: err instanceof Error ? err.message : String(err),
          });
        })
      )
    );
  } catch (err) {
    logger.error('sendHeatMilestonePush failed', {
      groupId,
      type,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

/**
 * Check milestone conditions after a progress entry is saved.
 * Awards notifications for first log, streak tiers, and total log tiers.
 */
export async function checkMilestones(
  userId: string,
  goalId: string,
  groupId: string
): Promise<void> {
  try {
    const [totalLogsResult, streakResult, goalRow, referralToken] = await Promise.all([
      db
        .selectFrom('progress_entries')
        .select(db.fn.count('id').as('count'))
        .where('user_id', '=', userId)
        .executeTakeFirst(),
      getCurrentStreak(userId, goalId),
      db.selectFrom('goals').select('title').where('id', '=', goalId).executeTakeFirst(),
      getOrCreateReferralToken(userId),
    ]);

    const totalLogs = Number(totalLogsResult?.count ?? 0);
    const goalTitle = goalRow?.title ?? null;

    const milestones: MilestoneConfig[] = [
      {
        key: 'first_log',
        condition: totalLogs === 1,
        count: 1,
        cooldownDays: null,
        goalScoped: false,
        metadata: { milestone_type: 'first_log', milestone_key: 'first_log' },
      },
      {
        key: 'streak_7',
        condition: streakResult === 7,
        count: 7,
        cooldownDays: 14,
        goalScoped: true,
        metadata: { milestone_type: 'streak', streak_count: 7, milestone_key: 'streak_7' },
      },
      {
        key: 'streak_14',
        condition: streakResult === 14,
        count: 14,
        cooldownDays: 30,
        goalScoped: true,
        metadata: { milestone_type: 'streak', streak_count: 14, milestone_key: 'streak_14' },
      },
      {
        key: 'streak_30',
        condition: streakResult === 30,
        count: 30,
        cooldownDays: 60,
        goalScoped: true,
        metadata: { milestone_type: 'streak', streak_count: 30, milestone_key: 'streak_30' },
      },
      {
        key: 'streak_365',
        condition: streakResult === 365,
        count: 365,
        cooldownDays: 365,
        goalScoped: true,
        metadata: { milestone_type: 'streak', streak_count: 365, milestone_key: 'streak_365' },
      },
      {
        key: 'total_logs_100',
        condition: totalLogs === 100,
        count: 100,
        cooldownDays: null,
        goalScoped: false,
        metadata: { milestone_type: 'total_logs', count: 100, milestone_key: 'total_logs_100' },
      },
      {
        key: 'total_logs_500',
        condition: totalLogs === 500,
        count: 500,
        cooldownDays: null,
        goalScoped: false,
        metadata: { milestone_type: 'total_logs', count: 500, milestone_key: 'total_logs_500' },
      },
    ];

    for (const m of milestones) {
      if (!m.condition) continue;

      const dedupeKey = m.goalScoped ? `${m.key}:${goalId}` : m.key;
      const allowed = await canAwardMilestone(userId, dedupeKey, m.cooldownDays);
      if (!allowed) continue;

      await upsertMilestoneGrant(userId, dedupeKey, m.goalScoped ? goalId : null);

      const cardData = generateCardData(m.key, goalTitle, m.count, referralToken);
      await createNotification({
        user_id: userId,
        type: 'milestone_achieved',
        actor_user_id: null,
        group_id: groupId,
        goal_id: goalId,
        metadata: m.metadata,
        shareable_card_data: cardData as unknown as Record<string, unknown>,
      });
    }
  } catch (err) {
    logger.error('checkMilestones failed', {
      userId,
      goalId,
      error: err instanceof Error ? err.message : String(err),
    });
  }
}

/**
 * Get current streak: consecutive days (going backward from today) with a progress entry.
 * Streak is broken if there's a gap of more than 1 day.
 */
async function getCurrentStreak(userId: string, goalId: string): Promise<number> {
  const entries = await db
    .selectFrom('progress_entries')
    .select('period_start')
    .where('user_id', '=', userId)
    .where('goal_id', '=', goalId)
    .orderBy('period_start', 'desc')
    .limit(400)
    .execute();

  const dates = new Set(entries.map((e) => e.period_start));
  if (dates.size === 0) return 0;

  const sortedDates = [...dates].sort().reverse();
  const mostRecent = sortedDates[0];
  const today = new Date().toISOString().slice(0, 10);

  const mostRecentDate = new Date(mostRecent + 'T12:00:00Z').getTime();
  const todayDate = new Date(today + 'T12:00:00Z').getTime();

  if (mostRecentDate < todayDate - ONE_DAY_MS) {
    return 0;
  }

  let streak = 0;
  let checkDate = new Date(mostRecent + 'T12:00:00Z');

  while (true) {
    const dateStr = checkDate.toISOString().slice(0, 10);
    if (!dates.has(dateStr)) break;
    streak++;
    checkDate.setTime(checkDate.getTime() - ONE_DAY_MS);
  }

  return streak;
}
