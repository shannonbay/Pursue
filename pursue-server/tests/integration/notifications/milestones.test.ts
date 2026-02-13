import request from 'supertest';
import { format, subDays } from 'date-fns';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
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
  });
});
