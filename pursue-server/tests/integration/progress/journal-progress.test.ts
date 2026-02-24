import request from 'supertest';
import { app } from '../../../src/app';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  randomEmail,
} from '../../helpers';

const TEST_TIMEZONE = 'America/New_York';

function todayInTz(tz: string = TEST_TIMEZONE): string {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  return formatter.format(new Date());
}

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: string) => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
}));

describe('Journal metric type', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('POST /api/groups/:id/goals with metric_type=journal', () => {
    it('creates a journal goal and returns log_title_prompt', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily Discovery',
          description: 'Share what you learned today',
          cadence: 'daily',
          metric_type: 'journal',
          log_title_prompt: 'What did you learn today?',
        });

      expect(res.status).toBe(201);
      expect(res.body.metric_type).toBe('journal');
      expect(res.body.log_title_prompt).toBe('What did you learn today?');
      expect(res.body.target_value).toBeNull();
    });

    it('rejects journal goal with target_value', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily Discovery',
          cadence: 'daily',
          metric_type: 'journal',
          target_value: 5,
        });

      expect(res.status).toBe(400);
      expect(res.body.error?.code).toBe('VALIDATION_ERROR');
    });
  });

  describe('POST /api/progress for journal goal', () => {
    async function createJournalGoal(accessToken: string, groupId: string): Promise<string> {
      const res = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily Discovery',
          cadence: 'daily',
          metric_type: 'journal',
          log_title_prompt: 'What did you learn today?',
        });
      if (res.status !== 201) {
        throw new Error(`Failed to create journal goal: ${JSON.stringify(res.body)}`);
      }
      return res.body.id;
    }

    it('logs progress for journal goal with log_title and returns it in response', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const goalId = await createJournalGoal(accessToken, groupId);

      const res = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          log_title: 'I learned about Kysely query builder today',
          user_date: todayInTz(),
          user_timezone: TEST_TIMEZONE,
        });

      expect(res.status).toBe(201);
      expect(res.body.log_title).toBe('I learned about Kysely query builder today');
      expect(res.body.value).toBe(1);
    });

    it('rejects journal progress without log_title', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const goalId = await createJournalGoal(accessToken, groupId);

      const res = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: todayInTz(),
          user_timezone: TEST_TIMEZONE,
        });

      expect(res.status).toBe(400);
      expect(res.body.error?.code).toBe('VALIDATION_ERROR');
      // Verify that the issue is about log_title
      const details = res.body.error?.details ?? [];
      expect(details.some((d: { path: string[] }) => d.path.includes('log_title'))).toBe(true);
    });

    it('rejects journal progress with invalid value (not 0 or 1)', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const goalId = await createJournalGoal(accessToken, groupId);

      const res = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 2,
          log_title: 'Something I learned',
          user_date: todayInTz(),
          user_timezone: TEST_TIMEZONE,
        });

      expect(res.status).toBe(400);
      expect(res.body.error?.code).toBe('VALIDATION_ERROR');
      // Verify the issue is about value
      const details = res.body.error?.details ?? [];
      expect(details.some((d: { path: string[] }) => d.path.includes('value'))).toBe(true);
    });
  });

  describe('GET /api/groups/:id/goals', () => {
    it('returns log_title_prompt on journal goals', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily Discovery',
          cadence: 'daily',
          metric_type: 'journal',
          log_title_prompt: 'What did you learn today?',
        });

      const res = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      const journalGoal = res.body.goals.find((g: { metric_type: string }) => g.metric_type === 'journal');
      expect(journalGoal).toBeDefined();
      expect(journalGoal.log_title_prompt).toBe('What did you learn today?');
    });
  });

  describe('GET /api/goals/:id/progress', () => {
    it('returns log_title on journal progress entries', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const goalRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily Discovery',
          cadence: 'daily',
          metric_type: 'journal',
          log_title_prompt: 'What did you learn today?',
        });
      const goalId = goalRes.body.id;

      await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          log_title: 'Learned about PostgreSQL partial indexes',
          user_date: todayInTz(),
          user_timezone: TEST_TIMEZONE,
        });

      const res = await request(app)
        .get(`/api/goals/${goalId}/progress`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.progress).toBeDefined();
      const entry = res.body.progress[0]?.entries[0];
      expect(entry).toBeDefined();
      expect(entry.log_title).toBe('Learned about PostgreSQL partial indexes');
    });
  });

  describe('Non-journal goal with log_title', () => {
    it('stores and returns log_title even for binary goals (field is optional for all types)', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: { title: 'Run', cadence: 'daily', metric_type: 'binary' },
      });

      const res = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          goal_id: goalId!,
          value: 1,
          log_title: 'Morning run completed',
          user_date: todayInTz(),
          user_timezone: TEST_TIMEZONE,
        });

      expect(res.status).toBe(201);
      expect(res.body.log_title).toBe('Morning run completed');
    });
  });
});
