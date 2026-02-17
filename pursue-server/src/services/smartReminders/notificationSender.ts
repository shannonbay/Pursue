/**
 * Smart Reminders - Notification Sender
 * 
 * Builds and sends reminder notifications via FCM.
 */

import { db } from '../../database/index.js';
import { sendNotificationToUser } from '../fcm.service.js';
import { logger } from '../../utils/logger.js';
import type { ReminderTier, SocialContext, NotificationTemplate } from './types.js';

/**
 * Pick a random element from an array
 */
function randomChoice<T>(array: T[]): T {
  return array[Math.floor(Math.random() * array.length)];
}

/**
 * Build gentle reminder notification
 */
function buildGentleNotification(
  goalTitle: string,
  groupName: string,
  goalId: string,
  groupId: string
): NotificationTemplate {
  const messages = [
    `Time to log: ${goalTitle}`,
    `Don't forget to log: ${goalTitle}`,
    `Ready to log ${goalTitle}?`,
    `${goalTitle} — time to check in`,
  ];

  return {
    title: groupName,
    body: randomChoice(messages),
    data: {
      type: 'smart_reminder',
      tier: 'gentle',
      goal_id: goalId,
      group_id: groupId,
    },
  };
}

/**
 * Build supportive reminder notification
 */
function buildSupportiveNotification(
  goalTitle: string,
  groupName: string,
  goalId: string,
  groupId: string,
  context: SocialContext
): NotificationTemplate {
  let body: string;

  if (context.loggedToday > context.totalMembers / 2) {
    body = `${context.loggedToday} of ${context.totalMembers} teammates completed their goal. Join them!`;
  } else if (context.userStreak > 0) {
    body = `Keep your ${context.userStreak}-day streak alive! Log ${goalTitle}.`;
  } else {
    body = `You've got this! Don't forget to log: ${goalTitle}`;
  }

  return {
    title: groupName,
    body,
    data: {
      type: 'smart_reminder',
      tier: 'supportive',
      goal_id: goalId,
      group_id: groupId,
      social_stats: JSON.stringify({
        logged: context.loggedToday,
        total: context.totalMembers,
      }),
    },
  };
}

/**
 * Build last chance reminder notification
 */
function buildLastChanceNotification(
  goalTitle: string,
  groupName: string,
  goalId: string,
  groupId: string,
  context: SocialContext
): NotificationTemplate {
  let body: string;

  if (context.userStreak >= 7) {
    body = `Don't break your ${context.userStreak}-day streak! Log '${goalTitle}' before midnight.`;
  } else if (context.groupStreak && context.groupStreak > 0) {
    body = `Keep the group's ${context.groupStreak}-day streak alive! Log '${goalTitle}' now.`;
  } else if (context.loggedToday === context.totalMembers - 1) {
    // User is the last one
    body = `You're the last one! Everyone else logged their goal. Don't leave them hanging!`;
  } else {
    body = `Last chance to log '${goalTitle}' before midnight!`;
  }

  return {
    title: groupName,
    body,
    data: {
      type: 'smart_reminder',
      tier: 'last_chance',
      goal_id: goalId,
      group_id: groupId,
      streak: context.userStreak.toString(),
      group_streak: (context.groupStreak || 0).toString(),
    },
  };
}

/**
 * Build notification based on tier and context
 */
export function buildNotification(
  tier: ReminderTier,
  goalTitle: string,
  groupName: string,
  goalId: string,
  groupId: string,
  socialContext: SocialContext
): NotificationTemplate {
  switch (tier) {
    case 'gentle':
      return buildGentleNotification(goalTitle, groupName, goalId, groupId);
    case 'supportive':
      return buildSupportiveNotification(
        goalTitle,
        groupName,
        goalId,
        groupId,
        socialContext
      );
    case 'last_chance':
      return buildLastChanceNotification(
        goalTitle,
        groupName,
        goalId,
        groupId,
        socialContext
      );
  }
}

/**
 * Send a reminder notification to a user
 */
export async function sendReminder(
  userId: string,
  goalId: string,
  goalTitle: string,
  groupName: string,
  groupId: string,
  tier: ReminderTier,
  socialContext: SocialContext
): Promise<void> {
  const notification = buildNotification(
    tier,
    goalTitle,
    groupName,
    goalId,
    groupId,
    socialContext
  );

  await sendNotificationToUser(userId, notification, notification.data);

  logger.info('Smart reminder sent', {
    user_id: userId,
    goal_id: goalId,
    tier,
    group_progress: `${socialContext.loggedToday}/${socialContext.totalMembers}`,
  });
}

/**
 * Record reminder in history
 */
export async function recordReminderHistory(
  userId: string,
  goalId: string,
  tier: ReminderTier,
  socialContext: SocialContext,
  userTimezone: string,
  localDate: string
): Promise<void> {
  await db
    .insertInto('reminder_history')
    .values({
      user_id: userId,
      goal_id: goalId,
      reminder_tier: tier,
      social_context: socialContext as unknown as Record<string, unknown>,
      user_timezone: userTimezone,
      sent_at_local_date: localDate,
    })
    .execute();
}
