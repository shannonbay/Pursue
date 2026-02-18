import request from 'supertest';
import { format, subDays } from 'date-fns';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createProgressEntry,
  randomEmail,
} from '../../helpers';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: jest.fn((g: string, t: string) => `${g}_${t}`),
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
}));

const TEST_TIMEZONE = 'America/New_York';

function userDate(daysAgo: number): string {
  return format(subDays(new Date(), daysAgo), 'yyyy-MM-dd');
}

describe('Milestone Notifications', () => {
  it('should create first_log milestone on first ever progress entry', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate(1),
        user_timezone: TEST_TIMEZONE,
      });

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    expect(notifs).toHaveLength(1);
    expect(notifs[0].metadata).toMatchObject({ milestone_type: 'first_log' });
    expect(notifs[0].shareable_card_data).toMatchObject({
      milestone_type: 'first_log',
      milestone_key: 'first_log',
      title: 'First step taken',
      subtitle: '30 min run',
      stat_value: '1',
      stat_label: 'progress logged',
      background_gradient: ['#1E88E5', '#1565C0'],
    });

    const grant = await testDb
      .selectFrom('user_milestone_grants')
      .selectAll()
      .where('user_id', '=', userId)
      .where('milestone_key', '=', 'first_log')
      .executeTakeFirst();

    expect(grant).toBeDefined();
  });

  it('should not create same milestone twice (deduplication)', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate(1),
        user_timezone: TEST_TIMEZONE,
      });

    await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate(2),
        user_timezone: TEST_TIMEZONE,
      });

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    const firstLogNotifs = notifs.filter(
      (n) => n.metadata && (n.metadata as Record<string, unknown>).milestone_type === 'first_log'
    );
    expect(firstLogNotifs).toHaveLength(1);
  });

  it('should create streak milestone at 7 days', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    for (let i = 1; i <= 7; i++) {
      await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate(i),
          user_timezone: TEST_TIMEZONE,
        });
    }

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    const streak7 = notifs.find(
      (n) =>
        n.metadata &&
        typeof n.metadata === 'object' &&
        (n.metadata as Record<string, unknown>).milestone_type === 'streak' &&
        (n.metadata as Record<string, unknown>).streak_count === 7
    );
    expect(streak7).toBeDefined();
    expect(streak7?.shareable_card_data).toMatchObject({
      milestone_type: 'streak',
      milestone_key: 'streak_7',
      title: '7-day streak!',
      stat_value: '7',
      stat_label: 'days in a row',
      background_gradient: ['#F57C00', '#E65100'],
    });
  });

  it('should create streak milestone at 30 days with shareable card data', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    for (let i = 1; i <= 30; i++) {
      await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate(i),
          user_timezone: TEST_TIMEZONE,
        });
    }

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    const streak30 = notifs.find(
      (n) =>
        n.metadata &&
        typeof n.metadata === 'object' &&
        (n.metadata as Record<string, unknown>).milestone_type === 'streak' &&
        (n.metadata as Record<string, unknown>).streak_count === 30
    );
    expect(streak30).toBeDefined();
    expect(streak30?.shareable_card_data).toMatchObject({
      milestone_type: 'streak',
      milestone_key: 'streak_30',
      title: '30-day streak!',
      stat_value: '30',
      stat_label: 'days in a row',
      background_gradient: ['#F57C00', '#E65100'],
    });
  });

  it('should create streak milestone at 14 days', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(accessToken);

    for (let i = 1; i <= 14; i++) {
      await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate(i),
          user_timezone: TEST_TIMEZONE,
        });
    }

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    const streak14 = notifs.find(
      (n) =>
        n.metadata &&
        typeof n.metadata === 'object' &&
        (n.metadata as Record<string, unknown>).milestone_type === 'streak' &&
        (n.metadata as Record<string, unknown>).streak_count === 14
    );
    expect(streak14).toBeDefined();
    expect(streak14?.shareable_card_data).toMatchObject({
      milestone_type: 'streak',
      milestone_key: 'streak_14',
      title: 'Two weeks strong!',
      stat_value: '14',
      background_gradient: ['#00897B', '#00695C'],
    });
  });

  it('should allow repeat streak_7 milestone after cooldown for the same goal', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(accessToken);

    await testDb
      .insertInto('user_milestone_grants')
      .values({
        user_id: userId,
        milestone_key: `streak_7:${goalId}`,
        goal_id: goalId,
        granted_at: new Date(Date.now() - 15 * 24 * 60 * 60 * 1000).toISOString(),
      })
      .execute();

    for (let i = 1; i <= 7; i++) {
      await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate(i),
          user_timezone: TEST_TIMEZONE,
        });
    }

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    const streak7Notifs = notifs.filter(
      (n) =>
        n.metadata &&
        typeof n.metadata === 'object' &&
        (n.metadata as Record<string, unknown>).milestone_type === 'streak' &&
        (n.metadata as Record<string, unknown>).streak_count === 7
    );
    expect(streak7Notifs).toHaveLength(1);
  });

  it('should create total_logs milestone at 100 with shareable card data', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);
    if (!goalId) throw new Error('goalId is required');

    // Insert 99 entries directly to bypass checkMilestones (fast path)
    for (let i = 0; i < 99; i++) {
      await createProgressEntry(goalId, userId, 1, '2020-01-01');
    }

    // Post the 100th entry via API to trigger checkMilestones
    await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate(1),
        user_timezone: TEST_TIMEZONE,
      });

    const notifs = await testDb
      .selectFrom('user_notifications')
      .selectAll()
      .where('user_id', '=', userId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    const totalLogs100 = notifs.find(
      (n) =>
        n.metadata &&
        typeof n.metadata === 'object' &&
        (n.metadata as Record<string, unknown>).milestone_type === 'total_logs'
    );
    expect(totalLogs100).toBeDefined();
    expect(totalLogs100?.shareable_card_data).toMatchObject({
      milestone_type: 'total_logs',
      milestone_key: 'total_logs_100',
      title: '100 logs milestone',
      stat_value: '100',
      stat_label: 'total logs',
      background_gradient: ['#7B1FA2', '#4A148C'],
    });
    expect((totalLogs100?.shareable_card_data as Record<string, unknown>).share_url).toContain('ref=');
    expect((totalLogs100?.shareable_card_data as Record<string, unknown>).share_url).not.toContain(userId);
  });
});
