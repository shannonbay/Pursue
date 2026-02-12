import request from 'supertest';
import { format, subDays } from 'date-fns';
import { app } from '../../../src/app';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createTestNudge,
  randomEmail,
} from '../../helpers';

function todayStr(): string {
  return format(new Date(), 'yyyy-MM-dd');
}

describe('GET /api/groups/:group_id/nudges/sent-today', () => {
  it('should return empty array when no nudges sent', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .query({ sender_local_date: todayStr() })
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.nudged_user_ids).toEqual([]);
  });

  it('should return array of recipient IDs for nudges sent today', async () => {
    const { accessToken: creatorToken, userId: senderId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Sender');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);
    const { memberUserId: recipient1Id } = await addMemberToGroup(creatorToken, groupId);
    const { memberUserId: recipient2Id } = await addMemberToGroup(creatorToken, groupId);

    await createTestNudge(senderId, recipient1Id, groupId, todayStr(), goalId);
    await createTestNudge(senderId, recipient2Id, groupId, todayStr(), goalId);

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .query({ sender_local_date: todayStr() })
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(200);
    expect(response.body.nudged_user_ids).toHaveLength(2);
    expect(response.body.nudged_user_ids).toContain(recipient1Id);
    expect(response.body.nudged_user_ids).toContain(recipient2Id);
  });

  it('should exclude nudges sent on different dates', async () => {
    const { accessToken: creatorToken, userId: senderId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Sender');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);
    const { memberUserId: recipient1Id } = await addMemberToGroup(creatorToken, groupId);
    const { memberUserId: recipient2Id } = await addMemberToGroup(creatorToken, groupId);

    const yesterday = format(subDays(new Date(), 1), 'yyyy-MM-dd');
    await createTestNudge(senderId, recipient1Id, groupId, yesterday, goalId);
    await createTestNudge(senderId, recipient2Id, groupId, todayStr(), goalId);

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .query({ sender_local_date: todayStr() })
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(200);
    expect(response.body.nudged_user_ids).toHaveLength(1);
    expect(response.body.nudged_user_ids).toContain(recipient2Id);
  });

  it('should return 401 when unauthenticated', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .query({ sender_local_date: todayStr() });

    expect(response.status).toBe(401);
  });

  it('should return 403 when not a member of the group', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creatorToken);

    const { accessToken: outsiderToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Outsider');

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .query({ sender_local_date: todayStr() })
      .set('Authorization', `Bearer ${outsiderToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 400 when sender_local_date is missing', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 when sender_local_date format is invalid', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/groups/${groupId}/nudges/sent-today`)
      .query({ sender_local_date: '2026/02/13' })
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });
});
