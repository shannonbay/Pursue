import request from 'supertest';
import crypto from 'crypto';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createProgressEntry,
  addMemberToGroup,
  randomEmail,
} from '../../helpers';
import {
  format,
  startOfWeek,
  startOfMonth,
  startOfYear,
  subDays,
} from 'date-fns';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: 'progress_logs' | 'group_events') => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
}));

describe('Pending member restrictions', () => {
  it('pending member cannot log progress', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser();
    const { accessToken: pendingToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Pending');

    const { groupId, goalId } = await createGroupWithGoal(creatorToken);

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${pendingToken}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const userDate = format(subDays(new Date(), 1), 'yyyy-MM-dd');
    const response = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${pendingToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/New_York',
      });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('PENDING_APPROVAL');
    expect(response.body.error?.message).toContain('pending approval');
  });
});

describe('POST /api/progress', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('happy paths', () => {
    it('should log progress for binary goal with value 1 and optional note', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken);
      const userDate = format(subDays(new Date(), 1), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          note: 'Great run in the park!',
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);
      expect(response.body).toMatchObject({
        goal_id: goalId,
        user_id: userId,
        value: 1,
        note: 'Great run in the park!',
        period_start: userDate,
      });
      expect(response.body).toHaveProperty('id');
      expect(response.body).toHaveProperty('logged_at');

      const row = await testDb
        .selectFrom('progress_entries')
        .selectAll()
        .where('goal_id', '=', goalId!)
        .where('user_id', '=', userId)
        .executeTakeFirst();
      expect(row).toBeDefined();
      expect(row?.value).toBe('1.00');
      expect(format(new Date(row!.period_start as string | Date), 'yyyy-MM-dd')).toBe(userDate);
    });

    it('should log progress for numeric goal', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Read 50 pages',
          cadence: 'daily',
          metric_type: 'numeric',
          target_value: 50,
          unit: 'pages',
        },
      });
      const userDate = format(new Date(), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 55,
          note: 'Read 55 pages of The Stand',
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);
      expect(response.body).toMatchObject({
        goal_id: goalId,
        user_id: userId,
        value: 55,
        note: 'Read 55 pages of The Stand',
        period_start: userDate,
      });
    });

    it('should log progress for duration goal (integer seconds)', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Meditate 30 min',
          cadence: 'daily',
          metric_type: 'duration',
          target_value: 1800,
        },
      });
      const userDate = format(new Date(), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1800,
          note: '30 min session',
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);
      expect(response.body).toMatchObject({
        goal_id: goalId,
        user_id: userId,
        value: 1800,
        period_start: userDate,
      });
    });

    it('should set period_start to Monday for weekly goal', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Run 3x per week',
          cadence: 'weekly',
          metric_type: 'binary',
          target_value: 3,
        },
      });
      const someDayInWeek = new Date(2026, 0, 22);
      const userDate = format(someDayInWeek, 'yyyy-MM-dd');
      const expectedPeriodStart = format(
        startOfWeek(someDayInWeek, { weekStartsOn: 1 }),
        'yyyy-MM-dd'
      );

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);
      expect(response.body.period_start).toBe(expectedPeriodStart);
    });

    it('should set period_start to first of month for monthly goal', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Run 10x per month',
          cadence: 'monthly',
          metric_type: 'binary',
          target_value: 10,
        },
      });
      const someDayInMonth = new Date(2026, 0, 15);
      const userDate = format(someDayInMonth, 'yyyy-MM-dd');
      const expectedPeriodStart = format(startOfMonth(someDayInMonth), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);
      expect(response.body.period_start).toBe(expectedPeriodStart);
    });

    it('should set period_start to January 1 for yearly goal', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Run 100x per year',
          cadence: 'yearly',
          metric_type: 'binary',
          target_value: 100,
        },
      });
      const someDayInYear = new Date(2025, 5, 15);
      const userDate = format(someDayInYear, 'yyyy-MM-dd');
      const expectedPeriodStart = format(startOfYear(someDayInYear), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);
      expect(response.body.period_start).toBe(expectedPeriodStart);
    });
  });

  describe('validation', () => {
    it('should reject invalid goal_id (non-UUID)', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: 'not-a-uuid',
          value: 1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect([400, 500]).toContain(response.status);
      if (response.status === 400) {
        expect(response.body.error.code).toBe('VALIDATION_ERROR');
      }
    });

    it('should reject goal not found (valid UUID not in DB)', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const fakeGoalId = crypto.randomUUID();

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: fakeGoalId,
          value: 1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
      expect(response.body.error.details).toBeDefined();
    });

    it('should reject binary goal value other than 0 or 1', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 2,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject negative value for numeric goal', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken, {
        goal: { metric_type: 'numeric', target_value: 50, unit: 'pages' },
      });

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: -1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject numeric value over 999999.99', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken, {
        goal: { metric_type: 'numeric', target_value: 999999.99, unit: 'pages' },
      });

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1000000,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject negative value for duration goal', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken, {
        goal: { metric_type: 'duration', target_value: 1800 },
      });

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: -100,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject non-integer value for duration goal', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken, {
        goal: { metric_type: 'duration', target_value: 1800 },
      });

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1800.5,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject user_date wrong format', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: '2026/01/17',
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject user_date in the future', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const futureDate = format(
        new Date(Date.now() + 7 * 24 * 60 * 60 * 1000),
        'yyyy-MM-dd'
      );

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: futureDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject empty user_timezone', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: '',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject note over 500 characters', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          note: 'a'.repeat(501),
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject note exceeding max length (>500 chars)', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          note: 'a'.repeat(700),
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });

    it('should reject missing user_date', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('VALIDATION_ERROR');
    });
  });

  describe('auth and authorization', () => {
    it('should reject request without Authorization header', async () => {
      const { goalId } = await createGroupWithGoal(
        (await createAuthenticatedUser()).accessToken
      );

      const response = await request(app)
        .post('/api/progress')
        .send({
          goal_id: goalId,
          value: 1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('UNAUTHORIZED');
    });

    it('should reject invalid or malformed token', async () => {
      const { goalId } = await createGroupWithGoal(
        (await createAuthenticatedUser()).accessToken
      );

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', 'Bearer invalid-token')
        .send({
          goal_id: goalId,
          value: 1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('INVALID_TOKEN');
    });

    it('should reject when user is not member of goal group', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(creator.accessToken);
      const otherUser = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${otherUser.accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: format(new Date(), 'yyyy-MM-dd'),
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('FORBIDDEN');
    });
  });

  describe('duplicate entry', () => {
    it('should reject duplicate progress for same period', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { goalId } = await createGroupWithGoal(accessToken);
      const userDate = format(new Date(), 'yyyy-MM-dd');

      await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('DUPLICATE_ENTRY');
      expect(response.body.error.message).toContain('Duplicate entry');
    });
  });

  describe('database and activity', () => {
    it('should create progress_entries row and group_activity on success', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken);
      const userDate = format(new Date(), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          note: 'Done',
          user_date: userDate,
          user_timezone: 'America/New_York',
        });

      expect(response.status).toBe(201);

      const entry = await testDb
        .selectFrom('progress_entries')
        .selectAll()
        .where('id', '=', response.body.id)
        .executeTakeFirst();
      expect(entry).toBeDefined();
      expect(entry?.goal_id).toBe(goalId);
      expect(entry?.user_id).toBe(userId);
      expect(entry?.value).toBe('1.00');
      expect(format(new Date(entry!.period_start as string | Date), 'yyyy-MM-dd')).toBe(userDate);

      const activity = await testDb
        .selectFrom('group_activities')
        .selectAll()
        .where('group_id', '=', groupId)
        .where('activity_type', '=', 'progress_logged')
        .executeTakeFirst();
      expect(activity).toBeDefined();
      expect(activity?.user_id).toBe(userId);
    });
  });
});

describe('GET /api/progress/:entry_id', () => {
  it('should return entry when owner requests', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);
    const userDate = format(new Date(), 'yyyy-MM-dd');

    const postResponse = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        note: 'Morning run',
        user_date: userDate,
        user_timezone: 'America/New_York',
      });
    expect(postResponse.status).toBe(201);
    const entryId = postResponse.body.id;

    const response = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: entryId,
      goal_id: goalId,
      user_id: userId,
      value: 1,
      note: 'Morning run',
      period_start: userDate,
    });
    expect(response.body).toHaveProperty('logged_at');
  });

  it('should return entry when group member (not owner) requests', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);
    const userDate = format(new Date(), 'yyyy-MM-dd');

    const postResponse = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/New_York',
      });
    expect(postResponse.status).toBe(201);
    const entryId = postResponse.body.id;

    const response = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.id).toBe(entryId);
    expect(response.body.goal_id).toBe(goalId);
  });

  it('should return 401 without auth', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    const userDate = format(new Date(), 'yyyy-MM-dd');
    const postResponse = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/New_York',
      });
    const entryId = postResponse.body.id;

    const response = await request(app).get(`/api/progress/${entryId}`);
    expect(response.status).toBe(401);
  });

  it('should return 401 with invalid token', async () => {
    const response = await request(app)
      .get(`/api/progress/${crypto.randomUUID()}`)
      .set('Authorization', 'Bearer invalid-token');
    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_TOKEN');
  });

  it('should return 404 for non-existent entry_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeEntryId = crypto.randomUUID();

    const response = await request(app)
      .get(`/api/progress/${fakeEntryId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });

  it('should return 403 when user is neither owner nor group member', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    await createProgressEntry(
      goalId!,
      creator.userId,
      1,
      format(new Date(), 'yyyy-MM-dd')
    );
    const entry = await testDb
      .selectFrom('progress_entries')
      .select('id')
      .where('goal_id', '=', goalId!)
      .where('user_id', '=', creator.userId)
      .executeTakeFirstOrThrow();
    const otherUser = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .get(`/api/progress/${entry.id}`)
      .set('Authorization', `Bearer ${otherUser.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });
});

describe('DELETE /api/progress/:entry_id', () => {
  it('should delete own entry and return 204', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);
    const userDate = format(new Date(), 'yyyy-MM-dd');

    const postResponse = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/New_York',
      });
    expect(postResponse.status).toBe(201);
    const entryId = postResponse.body.id;

    const response = await request(app)
      .delete(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(204);

    const entry = await testDb
      .selectFrom('progress_entries')
      .select('id')
      .where('id', '=', entryId)
      .executeTakeFirst();
    expect(entry).toBeUndefined();
  });

  it('should return 401 without auth', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    const userDate = format(new Date(), 'yyyy-MM-dd');
    const postResponse = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/New_York',
      });
    const entryId = postResponse.body.id;

    const response = await request(app).delete(`/api/progress/${entryId}`);
    expect(response.status).toBe(401);
  });

  it('should return 404 for non-existent entry_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeEntryId = crypto.randomUUID();

    const response = await request(app)
      .delete(`/api/progress/${fakeEntryId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });

  it('should return 403 when group member tries to delete another user entry', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);
    const userDate = format(new Date(), 'yyyy-MM-dd');

    const postResponse = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/New_York',
      });
    expect(postResponse.status).toBe(201);
    const entryId = postResponse.body.id;

    const response = await request(app)
      .delete(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
    expect(response.body.error.message).toContain('own progress');

    const entry = await testDb
      .selectFrom('progress_entries')
      .select('id')
      .where('id', '=', entryId)
      .executeTakeFirst();
    expect(entry).toBeDefined();
  });
});
