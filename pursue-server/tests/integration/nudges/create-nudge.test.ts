import request from 'supertest';
import { format } from 'date-fns';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createTestNudge,
  randomEmail,
} from '../../helpers';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: 'progress_logs' | 'group_events') => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
}));

function todayStr(): string {
  return format(new Date(), 'yyyy-MM-dd');
}

describe('POST /api/nudges', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should create a nudge successfully', async () => {
    const { accessToken: creatorToken, userId: senderId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Sender');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);
    const { memberAccessToken: recipientToken, memberUserId: recipientId } = await addMemberToGroup(creatorToken, groupId);

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({
        recipient_user_id: recipientId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(201);
    expect(response.body.nudge).toMatchObject({
      recipient_user_id: recipientId,
      group_id: groupId,
      goal_id: goalId,
    });
    expect(response.body.nudge).toHaveProperty('id');
    expect(response.body.nudge).toHaveProperty('sent_at');

    const nudge = await testDb
      .selectFrom('nudges')
      .selectAll()
      .where('id', '=', response.body.nudge.id)
      .executeTakeFirst();

    expect(nudge).toBeDefined();
    expect(nudge?.sender_user_id).toBe(senderId);
    expect(nudge?.recipient_user_id).toBe(recipientId);
  });

  it('should create group activity entry on success', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Alice');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);
    const { memberUserId: recipientId } = await addMemberToGroup(creatorToken, groupId);

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({
        recipient_user_id: recipientId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(201);

    const activity = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'nudge_sent')
      .executeTakeFirst();

    expect(activity).toBeDefined();
    expect(activity?.metadata).toMatchObject({
      sender_user_id: expect.any(String),
      sender_display_name: 'Alice',
      recipient_user_id: recipientId,
      goal_id: goalId,
      goal_title: '30 min run',
    });
  });

  it('should return 400 CANNOT_NUDGE_SELF when sender equals recipient', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_user_id: userId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CANNOT_NUDGE_SELF');
  });

  it('should return 401 when unauthenticated', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .post('/api/nudges')
      .send({
        recipient_user_id: userId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(401);
  });

  it('should return 403 NOT_A_MEMBER when sender not in group', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);
    const { memberUserId: recipientId } = await addMemberToGroup(creatorToken, groupId);

    const { accessToken: outsiderToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Outsider');

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${outsiderToken}`)
      .send({
        recipient_user_id: recipientId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 403 RECIPIENT_NOT_IN_GROUP when recipient not in group', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);

    const { accessToken: outsiderToken, userId: outsiderId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Outsider');

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({
        recipient_user_id: outsiderId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('RECIPIENT_NOT_IN_GROUP');
  });

  it('should return 409 ALREADY_NUDGED_TODAY on duplicate same day', async () => {
    const { accessToken: creatorToken, userId: senderId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Sender');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);
    const { memberUserId: recipientId } = await addMemberToGroup(creatorToken, groupId);

    await createTestNudge(senderId, recipientId, groupId, todayStr(), goalId);

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({
        recipient_user_id: recipientId,
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('ALREADY_NUDGED_TODAY');
  });

  it('should return 429 DAILY_SEND_LIMIT after 20 nudges', async () => {
    const { accessToken: creatorToken, userId: senderId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Sender');
    const { groupId, goalId } = await createGroupWithGoal(creatorToken);

    const memberIds: string[] = [];
    for (let i = 0; i < 21; i++) {
      const { memberUserId } = await addMemberToGroup(creatorToken, groupId);
      memberIds.push(memberUserId);
    }

    for (let i = 0; i < 20; i++) {
      await createTestNudge(senderId, memberIds[i], groupId, todayStr(), goalId);
    }

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({
        recipient_user_id: memberIds[20],
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(429);
    expect(response.body.error.code).toBe('DAILY_SEND_LIMIT');
  });

  it('should reject validation error for missing sender_local_date', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);
    const { memberUserId: recipientId } = await addMemberToGroup(accessToken, groupId);

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_user_id: recipientId,
        group_id: groupId,
        goal_id: goalId,
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject validation error for invalid UUID', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .post('/api/nudges')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        recipient_user_id: 'invalid-uuid',
        group_id: groupId,
        goal_id: goalId,
        sender_local_date: todayStr(),
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });
});
