/**
 * Tests for group heat milestone FCM push notifications (section 7.2).
 * Heat milestones send FCM only — no user_notifications (inbox) entries.
 */
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  randomEmail,
} from '../../helpers';
import { sendHeatMilestonePush } from '../../../src/services/notification.service';
import * as fcmService from '../../../src/services/fcm.service';

jest.mock('../../../src/services/fcm.service', () => ({
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: jest.fn((g: string, t: string) => `${g}_${t}`),
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
}));

describe('Heat push notifications', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should send FCM to each active member for heat_tier_up and not create inbox entries', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Heat Push Test Group',
    });
    const { memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    const beforeCount = await testDb
      .selectFrom('user_notifications')
      .select(testDb.fn.count('id').as('count'))
      .executeTakeFirst();

    (fcmService.sendNotificationToUser as jest.Mock).mockClear();
    await sendHeatMilestonePush(groupId, 'heat_tier_up', { tier_name: 'Blaze' });

    const calls = (fcmService.sendNotificationToUser as jest.Mock).mock.calls;
    const heatTierUpCalls = calls.filter(
      (c: unknown[]) => (c as [string, unknown, Record<string, string>])[2]?.type === 'heat_tier_up'
    );
    expect(heatTierUpCalls).toHaveLength(2);
    const userIds = heatTierUpCalls.map((c: unknown[]) => (c as [string])[0]).sort();
    expect(userIds).toEqual([creator.userId, memberUserId].sort());

    expect(heatTierUpCalls[0][1]).toEqual({
      title: 'Heat Push Test Group',
      body: 'Group heat is rising! Now at Blaze.',
    });
    expect(heatTierUpCalls[0][2]).toEqual({ type: 'heat_tier_up', group_id: groupId });

    const afterCount = await testDb
      .selectFrom('user_notifications')
      .select(testDb.fn.count('id').as('count'))
      .executeTakeFirst();
    expect(Number(afterCount?.count)).toBe(Number(beforeCount?.count));
  });

  it('should send FCM for heat_supernova_reached with correct body', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Supernova Group',
    });

    await sendHeatMilestonePush(groupId, 'heat_supernova_reached', {});

    expect(fcmService.sendNotificationToUser).toHaveBeenCalledTimes(1);
    const [userId, notification, data] = (fcmService.sendNotificationToUser as jest.Mock).mock
      .calls[0];
    expect(userId).toBe(creator.userId);
    expect(notification.body).toBe('SUPERNOVA! The group is burning blue-hot!');
    expect(data.type).toBe('heat_supernova_reached');
    expect(data.group_id).toBe(groupId);
  });

  it('should send FCM for heat_streak_milestone with streak_days in body', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Streak Group',
    });

    await sendHeatMilestonePush(groupId, 'heat_streak_milestone', { streak_days: 7 });

    expect(fcmService.sendNotificationToUser).toHaveBeenCalledTimes(1);
    const [, notification, data] = (fcmService.sendNotificationToUser as jest.Mock).mock.calls[0];
    expect(notification.body).toBe('7-day heat streak! Keep the momentum!');
    expect(data.type).toBe('heat_streak_milestone');
  });

  it('should not create user_notifications rows for any heat type', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'No Inbox Group',
    });

    const beforeCount = await testDb
      .selectFrom('user_notifications')
      .select(testDb.fn.count('id').as('count'))
      .executeTakeFirst();

    await sendHeatMilestonePush(groupId, 'heat_tier_up', { tier_name: 'Spark' });
    await sendHeatMilestonePush(groupId, 'heat_supernova_reached', {});
    await sendHeatMilestonePush(groupId, 'heat_streak_milestone', { streak_days: 14 });

    const afterCount = await testDb
      .selectFrom('user_notifications')
      .select(testDb.fn.count('id').as('count'))
      .executeTakeFirst();
    expect(Number(afterCount?.count)).toBe(Number(beforeCount?.count));
  });
});
