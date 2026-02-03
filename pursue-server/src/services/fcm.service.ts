import admin from 'firebase-admin';
import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';

// Initialize once at app startup when Firebase is configured
if (
  !admin.apps.length &&
  process.env.FIREBASE_PROJECT_ID &&
  process.env.FIREBASE_CLIENT_EMAIL &&
  process.env.FIREBASE_PRIVATE_KEY
) {
  admin.initializeApp({
    credential: admin.credential.cert({
      projectId: process.env.FIREBASE_PROJECT_ID,
      clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
      privateKey: process.env.FIREBASE_PRIVATE_KEY.replace(/\\n/g, '\n')
    })
  });
}

export async function sendPushNotification(
  deviceToken: string,
  title: string,
  body: string,
  data?: Record<string, string>
): Promise<void> {
  try {
    await admin.messaging().send({
      token: deviceToken,
      notification: { title, body },
      data,
      android: {
        priority: 'high',
        notification: {
          sound: 'default',
          channelId: 'pursue_notifications'
        }
      }
    });

    console.log('✅ Push notification sent:', { title, deviceToken });
  } catch (error) {
    console.error('❌ Failed to send notification:', error);
    throw error;
  }
}

export interface GroupNotificationPayload {
  title: string;
  body: string;
}

export interface SendGroupNotificationOptions {
  membershipStatus?: 'active';
}

/**
 * Send push notification to all devices of members in a group.
 * On FCM failures, invalid tokens are removed from the database.
 * Does not throw; logs errors so HTTP requests are not failed by FCM.
 * When options.membershipStatus is provided, only members with that status are notified.
 */
export async function sendGroupNotification(
  groupId: string,
  notification: GroupNotificationPayload,
  data: Record<string, string>,
  options?: SendGroupNotificationOptions
): Promise<void> {
  if (!admin.apps.length) return;
  try {
    let query = db
      .selectFrom('devices')
      .innerJoin('group_memberships', 'devices.user_id', 'group_memberships.user_id')
      .select(['devices.fcm_token'])
      .where('group_memberships.group_id', '=', groupId);

    if (options?.membershipStatus) {
      query = query.where('group_memberships.status', '=', options.membershipStatus);
    }

    const devices = await query.execute();

    const tokens = devices.map((d) => d.fcm_token);
    if (tokens.length === 0) return;

    const message = {
      notification,
      data: { ...data },
      tokens,
      android: {
        priority: 'high' as const,
        notification: {
          sound: 'default',
          channelId: 'pursue_notifications'
        }
      }
    };

    const response = await admin.messaging().sendEachForMulticast(message);

    if (response.failureCount > 0) {
      const failedTokens: string[] = [];
      response.responses.forEach((resp, idx) => {
        if (!resp.success) failedTokens.push(tokens[idx]);
      });
      if (failedTokens.length > 0) {
        await db
          .deleteFrom('devices')
          .where('fcm_token', 'in', failedTokens)
          .execute();
      }
    }
  } catch (error) {
    logger.error('sendGroupNotification failed', {
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined,
      groupId,
    });
  }
}

/**
 * Build a topic name for FCM topic-based messaging.
 * Format: {groupId}_{type}
 *
 * @param groupId The group ID
 * @param type Either 'progress_logs' or 'group_events'
 */
export function buildTopicName(groupId: string, type: 'progress_logs' | 'group_events'): string {
  return `${groupId}_${type}`;
}

/**
 * Send push notification to all subscribers of a topic.
 * Does not throw; logs errors so HTTP requests are not failed by FCM.
 *
 * @param topic The FCM topic name (e.g., "{groupId}_progress_logs")
 * @param notification The notification payload (title, body)
 * @param data Additional data payload
 */
export async function sendToTopic(
  topic: string,
  notification: GroupNotificationPayload,
  data: Record<string, string>
): Promise<void> {
  if (!admin.apps.length) return;
  try {
    const message = {
      topic,
      notification,
      data: { ...data },
      android: {
        priority: 'high' as const,
        notification: {
          sound: 'default',
          channelId: 'pursue_notifications'
        }
      }
    };

    await admin.messaging().send(message);
    logger.info('sendToTopic succeeded', { topic });
  } catch (error) {
    logger.error('sendToTopic failed', {
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined,
      topic,
    });
  }
}

/**
 * Send push notification to all devices of a single user.
 * On FCM failures, invalid tokens are removed from the database.
 * Does not throw; logs errors so HTTP requests are not failed by FCM.
 */
export async function sendNotificationToUser(
  userId: string,
  notification: GroupNotificationPayload,
  data: Record<string, string>
): Promise<void> {
  if (!admin.apps.length) return;
  try {
    const devices = await db
      .selectFrom('devices')
      .select('fcm_token')
      .where('user_id', '=', userId)
      .execute();

    const tokens = devices.map((d) => d.fcm_token);
    if (tokens.length === 0) return;

    const message = {
      notification,
      data: { ...data },
      tokens,
      android: {
        priority: 'high' as const,
        notification: {
          sound: 'default',
          channelId: 'pursue_notifications'
        }
      }
    };

    const response = await admin.messaging().sendEachForMulticast(message);

    if (response.failureCount > 0) {
      const failedTokens: string[] = [];
      response.responses.forEach((resp, idx) => {
        if (!resp.success) failedTokens.push(tokens[idx]);
      });
      if (failedTokens.length > 0) {
        await db
          .deleteFrom('devices')
          .where('fcm_token', 'in', failedTokens)
          .execute();
      }
    }
  } catch (error) {
    logger.error('sendNotificationToUser failed', {
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined,
      userId,
    });
  }
}