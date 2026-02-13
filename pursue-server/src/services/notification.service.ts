import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';
import { sendNotificationToUser } from './fcm.service.js';

export type NotificationType =
  | 'reaction_received'
  | 'nudge_received'
  | 'membership_approved'
  | 'membership_rejected'
  | 'promoted_to_admin'
  | 'removed_from_group'
  | 'milestone_achieved'
  | 'join_request_received';

export interface CreateNotificationParams {
  user_id: string;
  type: NotificationType;
  actor_user_id?: string | null;
  group_id?: string | null;
  goal_id?: string | null;
  progress_entry_id?: string | null;
  metadata?: Record<string, unknown> | null;
}

/**
 * Create a notification and send FCM push.
 * Failures are logged but do not throw (per spec: do not block primary action).
 */
export async function createNotification(params: CreateNotificationParams): Promise<string | null> {
  try {
    console.log(`[createNotification] Creating notification: user=${params.user_id} type=${params.type}`);

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
      })
      .returning(['id', 'is_read'])
      .executeTakeFirst();

    if (!row) return null;

    console.log(`[createNotification] Created notification: id=${row.id} is_read=${row.is_read}`);

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

  // Fetch context for FCM payload
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

  let notification: { title: string; body: string };
  let data: Record<string, string> = {
    type,
    notification_id: notificationId,
  };

  switch (type) {
    case 'reaction_received': {
      const emoji = (metadata?.emoji as string) ?? '👍';
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

      if (milestoneType === 'first_log') {
        notification = {
          title: '🎉 First log!',
          body: "You've logged your first activity. Keep it up!",
        };
      } else if (milestoneType === 'streak' && streakCount) {
        notification = {
          title: `🎉 ${streakCount}-day streak!`,
          body: `You've logged ${goalTitle} ${streakCount} days in a row. Keep it up!`,
        };
      } else if (milestoneType === 'total_logs' && count) {
        notification = {
          title: `🎉 ${count} total logs!`,
          body: `You've reached ${count} total logs. Amazing progress!`,
        };
      } else {
        notification = {
          title: '🎉 Milestone achieved!',
          body: `Great progress on ${goalTitle}!`,
        };
      }
      break;
    }
    case 'join_request_received':
      notification = {
        title: 'New Join Request',
        body: `${actorName} wants to join ${groupName}`,
      };
      if (group_id) data.group_id = group_id;
      break;
    default:
      return;
  }

  await sendNotificationToUser(user_id, notification, data);
}

/**
 * Check milestone conditions after a progress entry is saved.
 * Awards notifications for first log, 7-day streak, 30-day streak, 100 total logs.
 * Uses user_milestone_grants for deduplication.
 */
export async function checkMilestones(
  userId: string,
  goalId: string,
  groupId: string
): Promise<void> {
  try {
    const [totalLogsResult, streakResult] = await Promise.all([
      db
        .selectFrom('progress_entries')
        .select(db.fn.count('id').as('count'))
        .where('user_id', '=', userId)
        .executeTakeFirst(),
      getCurrentStreak(userId, goalId),
    ]);

    const totalLogs = Number(totalLogsResult?.count ?? 0);

    const milestones: Array<{
      key: string;
      condition: boolean;
      metadata: Record<string, unknown>;
    }> = [
      { key: 'first_log', condition: totalLogs === 1, metadata: { milestone_type: 'first_log' } },
      {
        key: 'streak_7',
        condition: streakResult === 7,
        metadata: { milestone_type: 'streak', streak_count: 7 },
      },
      {
        key: 'streak_30',
        condition: streakResult === 30,
        metadata: { milestone_type: 'streak', streak_count: 30 },
      },
      {
        key: 'total_logs_100',
        condition: totalLogs === 100,
        metadata: { milestone_type: 'total_logs', count: 100 },
      },
    ];

    for (const m of milestones) {
      if (!m.condition) continue;

      // Deduplicate: insert into user_milestone_grants, skip if already granted
      try {
        await db
          .insertInto('user_milestone_grants')
          .values({
            user_id: userId,
            milestone_key: m.key,
          })
          .execute();
      } catch (err: unknown) {
        const pgErr = err as { code?: string };
        if (pgErr.code === '23505') {
          // Unique violation - already granted
          continue;
        }
        throw err;
      }

      await createNotification({
        user_id: userId,
        type: 'milestone_achieved',
        actor_user_id: null,
        group_id: groupId,
        goal_id: goalId,
        metadata: m.metadata,
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
    .limit(120)
    .execute();

  const dates = new Set(entries.map((e) => e.period_start));
  if (dates.size === 0) return 0;

  const sortedDates = [...dates].sort().reverse();
  const mostRecent = sortedDates[0];
  const today = new Date().toISOString().slice(0, 10);

  // Streak only counts if most recent log is today or yesterday (consecutive)
  const oneDayMs = 24 * 60 * 60 * 1000;
  const mostRecentDate = new Date(mostRecent + 'T12:00:00Z').getTime();
  const todayDate = new Date(today + 'T12:00:00Z').getTime();

  if (mostRecentDate < todayDate - oneDayMs) {
    return 0;
  }

  let streak = 0;
  let checkDate = new Date(mostRecent + 'T12:00:00Z');

  while (true) {
    const dateStr = checkDate.toISOString().slice(0, 10);
    if (!dates.has(dateStr)) break;
    streak++;
    checkDate.setTime(checkDate.getTime() - oneDayMs);
  }

  return streak;
}
