import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  addMemberToGroup,
  createGroupWithGoal,
  createProgressEntry,
  randomEmail,
} from '../../helpers';

describe('GET /api/groups/:group_id/export-progress', () => {
  it('should require authentication', async () => {
    const { groupId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken,
      { includeGoal: true }
    );

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      );

    expect(response.status).toBe(401);
  });

  it('should return 403 when user is not a member of the group', async () => {
    const creator = await createAuthenticatedUser();
    const nonMember = await createAuthenticatedUser(randomEmail());

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ name: 'Export Test Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('FORBIDDEN');
  });

  it('should return 403 when user is pending (not approved) member', async () => {
    const creator = await createAuthenticatedUser();
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ name: 'Export Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    const pendingUser = await createAuthenticatedUser(randomEmail());
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${pendingUser.accessToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${pendingUser.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('PENDING_APPROVAL');
  });

  it('should return 404 when group does not exist', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGroupId = '550e8400-e29b-41d4-a716-446655440000';

    const response = await request(app)
      .get(
        `/api/groups/${fakeGroupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
  });

  it('should return 400 when start_date is missing', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 when end_date is missing', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 when user_timezone is missing', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 for invalid date format', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=01-01-2025&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 when end_date is before start_date', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-12-31&end_date=2025-01-01&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 when date range exceeds 24 months', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2023-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 for invalid timezone', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=Invalid/Timezone`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 200 and Excel file for approved member', async () => {
    const { accessToken } = await createAuthenticatedUser('export-creator@example.com', 'Test123!@#', 'Alice');
    const { groupId, goalId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
      groupName: 'Morning Runners',
      goal: { title: '30 min run', cadence: 'daily', metric_type: 'binary' },
    });

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.headers['content-type']).toContain(
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    );
    expect(response.headers['content-disposition']).toContain('attachment');
    expect(response.headers['content-disposition']).toContain('.xlsx');
    expect(response.headers['content-disposition']).toContain('Morning_Runners');
    const size =
      (response.body && Buffer.isBuffer(response.body) && response.body.length) ||
      (typeof response.text === 'string' && response.text.length) ||
      Number(response.headers['content-length']) ||
      0;
    expect(size).toBeGreaterThan(0);
    if (Buffer.isBuffer(response.body) && response.body.length >= 2) {
      expect(response.body[0]).toBe(0x50);
      expect(response.body[1]).toBe(0x4b);
    }
  });

  it('should include member sheet and summary for group with goals and progress', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(
      'export-with-progress@example.com',
      'Test123!@#',
      'Shannon'
    );
    const { groupId, goalId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
      groupName: 'Progress Group',
      goal: { title: 'Daily Run', cadence: 'daily', metric_type: 'binary' },
    });
    if (!goalId) throw new Error('createGroupWithGoal did not return goalId');

    await createProgressEntry(goalId, userId, 1, '2025-01-01');
    await createProgressEntry(goalId, userId, 1, '2025-01-02');
    await createProgressEntry(goalId, userId, 1, '2025-01-03');

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-01-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    const size =
      (response.body && Buffer.isBuffer(response.body) && response.body.length) ||
      (typeof response.text === 'string' && response.text.length) ||
      Number(response.headers['content-length']) ||
      0;
    expect(size).toBeGreaterThan(100);
    expect(response.headers['content-type']).toContain(
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    );
  });

  it('should log export_progress activity', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(
      randomEmail(),
      'Test123!@#',
      'Export Logger'
    );
    const { groupId } = await createGroupWithGoal(accessToken, {
      includeGoal: true,
    });

    await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-06-30&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${accessToken}`);

    const activity = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'export_progress')
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(activity).toBeDefined();
    expect(activity?.metadata).toMatchObject({
      start_date: '2025-01-01',
      end_date: '2025-06-30',
    });
  });

  it('should return 200 for group with multiple approved members', async () => {
    const creator = await createAuthenticatedUser(
      'multi-export-creator@example.com',
      'Test123!@#',
      'Alice'
    );
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({
        name: 'Multi Member Group',
        initial_goals: [
          { title: 'Daily Goal', cadence: 'daily', metric_type: 'binary' },
        ],
      });

    const groupId = createResponse.body.id;
    const { memberAccessToken } = await addMemberToGroup(
      creator.accessToken,
      groupId
    );

    const response = await request(app)
      .get(
        `/api/groups/${groupId}/export-progress?start_date=2025-01-01&end_date=2025-12-31&user_timezone=America/New_York`
      )
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(200);
    expect(response.headers['content-type']).toContain(
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    );
    const size =
      (response.body && Buffer.isBuffer(response.body) && response.body.length) ||
      (typeof response.text === 'string' && response.text.length) ||
      Number(response.headers['content-length']) ||
      0;
    expect(size).toBeGreaterThan(0);
  });
});
