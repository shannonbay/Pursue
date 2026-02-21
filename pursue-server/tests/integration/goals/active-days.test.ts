import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  randomEmail,
} from '../../helpers';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: string) => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('../../../src/services/gcs.service.js', () => ({
  buildObjectPath: jest.fn((userId: string, entryId: string) => `${userId}/2026/02/${entryId}.jpg`),
  uploadPhoto: jest.fn().mockResolvedValue(undefined),
  getSignedUrl: jest.fn().mockResolvedValue('https://storage.googleapis.com/signed-url-mock'),
  deleteUserPhotos: jest.fn().mockResolvedValue(undefined),
  deletePhoto: jest.fn().mockResolvedValue(undefined),
}));

describe('Active Days', () => {
  describe('POST /api/groups/:group_id/goals — create with active_days', () => {
    it('should create a daily goal with weekday active_days', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Morning run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5], // Mon-Fri
        });

      expect(res.status).toBe(201);
      expect(res.body.active_days).toEqual([1, 2, 3, 4, 5]);
      expect(res.body.active_days_label).toBe('Weekdays only');
      expect(res.body.active_days_count).toBe(5);
    });

    it('should create a daily goal with weekend active_days', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekend yoga',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [0, 6], // Sun, Sat
        });

      expect(res.status).toBe(201);
      expect(res.body.active_days).toEqual([0, 6]);
      expect(res.body.active_days_label).toBe('Weekends only');
      expect(res.body.active_days_count).toBe(2);
    });

    it('should create a daily goal with MWF active_days', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Gym',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 3, 5], // Mon, Wed, Fri
        });

      expect(res.status).toBe(201);
      expect(res.body.active_days).toEqual([1, 3, 5]);
      expect(res.body.active_days_label).toBe('Mon, Wed, Fri');
      expect(res.body.active_days_count).toBe(3);
    });

    it('should create a daily goal without active_days (null = every day)', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Meditate',
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect(res.status).toBe(201);
      expect(res.body.active_days).toBeNull();
      expect(res.body.active_days_label).toBe('Every day');
      expect(res.body.active_days_count).toBe(7);
    });

    it('should store correct bitmask in database', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekday run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5], // Mon-Fri = 62
        });

      expect(res.status).toBe(201);

      const dbGoal = await testDb
        .selectFrom('goals')
        .select('active_days')
        .where('id', '=', res.body.id)
        .executeTakeFirstOrThrow();

      expect(dbGoal.active_days).toBe(62); // WEEKDAYS bitmask
    });
  });

  describe('Validation errors', () => {
    it('should reject active_days on a weekly goal', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'numeric',
          target_value: 3,
          active_days: [1, 2, 3],
        });

      expect(res.status).toBe(400);
    });

    it('should reject empty active_days array', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Bad goal',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [],
        });

      expect(res.status).toBe(400);
    });

    it('should reject active_days values > 6', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Bad goal',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [0, 7],
        });

      expect(res.status).toBe(400);
    });

    it('should reject duplicate values in active_days', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Bad goal',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 1, 2],
        });

      expect(res.status).toBe(400);
    });
  });

  describe('PATCH /api/goals/:goal_id — update active_days', () => {
    it('should update active_days on a daily goal', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      // Create goal with weekdays
      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5],
        });

      const goalId = createRes.body.id;

      // Update to MWF
      const res = await request(app)
        .patch(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ active_days: [1, 3, 5] });

      expect(res.status).toBe(200);
      expect(res.body.active_days).toEqual([1, 3, 5]);
      expect(res.body.active_days_label).toBe('Mon, Wed, Fri');
      expect(res.body.active_days_count).toBe(3);
    });

    it('should reset active_days to null (every day)', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5],
        });

      const goalId = createRes.body.id;

      const res = await request(app)
        .patch(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ active_days: null });

      expect(res.status).toBe(200);
      expect(res.body.active_days).toBeNull();
      expect(res.body.active_days_label).toBe('Every day');
      expect(res.body.active_days_count).toBe(7);
    });

    it('should create activity entry when active_days changes', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5],
        });

      const goalId = createRes.body.id;

      await request(app)
        .patch(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ active_days: [1, 3, 5] });

      const activities = await testDb
        .selectFrom('group_activities')
        .selectAll()
        .where('group_id', '=', groupId)
        .where('activity_type', '=', 'goal_updated')
        .execute();

      expect(activities.length).toBe(1);
      const metadata = activities[0].metadata as Record<string, unknown>;
      expect(metadata.field).toBe('active_days');
      expect(metadata.old_value).toEqual([1, 2, 3, 4, 5]);
      expect(metadata.new_value).toEqual([1, 3, 5]);
    });

    it('should reject active_days on a non-daily goal', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, {
        includeGoal: false,
      });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekly run',
          cadence: 'weekly',
          metric_type: 'numeric',
          target_value: 3,
        });

      const goalId = createRes.body.id;

      const res = await request(app)
        .patch(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ active_days: [1, 2, 3] });

      expect(res.status).toBe(400);
    });
  });

  describe('GET /api/groups/:group_id/goals — list goals includes active_days', () => {
    it('should return active_days fields in goal list', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekday run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5],
        });

      await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Every day meditate',
          cadence: 'daily',
          metric_type: 'binary',
        });

      const res = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.goals.length).toBe(2);

      const weekdayGoal = res.body.goals.find((g: any) => g.title === 'Weekday run');
      const everydayGoal = res.body.goals.find((g: any) => g.title === 'Every day meditate');

      expect(weekdayGoal.active_days).toEqual([1, 2, 3, 4, 5]);
      expect(weekdayGoal.active_days_label).toBe('Weekdays only');
      expect(weekdayGoal.active_days_count).toBe(5);

      expect(everydayGoal.active_days).toBeNull();
      expect(everydayGoal.active_days_label).toBe('Every day');
      expect(everydayGoal.active_days_count).toBe(7);
    });
  });

  describe('GET /api/goals/:goal_id — get goal includes active_days', () => {
    it('should return active_days fields', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekday run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [0, 6], // weekends
        });

      const goalId = createRes.body.id;

      const res = await request(app)
        .get(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.active_days).toEqual([0, 6]);
      expect(res.body.active_days_label).toBe('Weekends only');
      expect(res.body.active_days_count).toBe(2);
    });
  });

  describe('Progress endpoints include active_days', () => {
    it('GET /api/goals/:goal_id/progress should include active_days in goal object', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekday run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5],
        });

      const goalId = createRes.body.id;

      const res = await request(app)
        .get(`/api/goals/${goalId}/progress`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.goal.active_days).toEqual([1, 2, 3, 4, 5]);
      expect(res.body.goal.active_days_label).toBe('Weekdays only');
      expect(res.body.goal.active_days_count).toBe(5);
    });

    it('GET /api/goals/:goal_id/progress/me should include active_days', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekday run',
          cadence: 'daily',
          metric_type: 'binary',
          active_days: [1, 2, 3, 4, 5],
        });

      const goalId = createRes.body.id;

      const res = await request(app)
        .get(`/api/goals/${goalId}/progress/me`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.active_days).toEqual([1, 2, 3, 4, 5]);
      expect(res.body.active_days_label).toBe('Weekdays only');
      expect(res.body.active_days_count).toBe(5);
    });
  });
});
