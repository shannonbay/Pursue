import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  setUserPremium,
  randomEmail,
  createFocusSession,
} from '../../helpers';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  sendSilentGroupMessage: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: string) => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
}));

const { sendGroupNotification } = jest.requireMock('../../../src/services/fcm.service');

describe('Session REST endpoints', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  // ---------------------------------------------------------------------------
  // POST /api/groups/:groupId/sessions
  // ---------------------------------------------------------------------------
  describe('POST /api/groups/:groupId/sessions', () => {
    it('creates a session and returns 201 with participants', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ focus_duration_minutes: 25 });

      expect(res.status).toBe(201);
      expect(res.body.session).toMatchObject({ status: 'lobby', focus_duration_minutes: 25, group_id: groupId });
      expect(res.body.session.participants).toHaveLength(0);
    });

    it('sends FCM notification when host joins for the first time', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const createRes = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ focus_duration_minutes: 25 });

      // FCM should NOT fire on session creation
      expect(sendGroupNotification).not.toHaveBeenCalled();

      // FCM fires when host presses "Go Live" (calls join)
      await request(app)
        .post(`/api/groups/${groupId}/sessions/${createRes.body.session.id}/join`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(sendGroupNotification).toHaveBeenCalledTimes(1);
    });

    it('returns 403 PREMIUM_REQUIRED for 90-min session as free user', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ focus_duration_minutes: 90 });

      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('PREMIUM_REQUIRED');
    });

    it('allows 90-min session for premium user', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      await setUserPremium(userId);
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ focus_duration_minutes: 90 });

      expect(res.status).toBe(201);
      expect(res.body.session.focus_duration_minutes).toBe(90);
    });

    it('returns 403 FORBIDDEN for non-member', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creatorToken, { includeGoal: false });
      const { accessToken: outsiderToken } = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${outsiderToken}`)
        .send({ focus_duration_minutes: 25 });

      expect(res.status).toBe(403);
    });

    it('returns 400 VALIDATION_ERROR for invalid duration', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ focus_duration_minutes: 30 });

      expect(res.status).toBe(400);
    });

    it('returns 401 when unauthenticated', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .send({ focus_duration_minutes: 25 });

      expect(res.status).toBe(401);
    });
  });

  // ---------------------------------------------------------------------------
  // GET /api/groups/:groupId/sessions/active
  // ---------------------------------------------------------------------------
  describe('GET /api/groups/:groupId/sessions/active', () => {
    it('returns active sessions with participants', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      await createFocusSession(groupId, userId, { status: 'lobby' });

      const res = await request(app)
        .get(`/api/groups/${groupId}/sessions/active`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.sessions).toHaveLength(1);
      expect(res.body.sessions[0].participants).toHaveLength(1);
    });

    it('returns empty array when no active sessions', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      // Create an ended session — should NOT appear
      await createFocusSession(groupId, userId, { status: 'ended' });

      const res = await request(app)
        .get(`/api/groups/${groupId}/sessions/active`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.sessions).toHaveLength(0);
    });

    it('returns sessions in all active phases', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      await createFocusSession(groupId, userId, { status: 'lobby' });
      await createFocusSession(groupId, userId, { status: 'focus' });
      await createFocusSession(groupId, userId, { status: 'chit-chat' });
      await createFocusSession(groupId, userId, { status: 'ended' });

      const res = await request(app)
        .get(`/api/groups/${groupId}/sessions/active`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.sessions).toHaveLength(3); // lobby + focus + chit-chat
    });
  });

  // ---------------------------------------------------------------------------
  // POST /api/groups/:groupId/sessions/:id/join
  // ---------------------------------------------------------------------------
  describe('POST /api/groups/:groupId/sessions/:id/join', () => {
    it('adds participant and returns 200', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberAccessToken: memberToken } = await addMemberToGroup(hostToken, groupId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/join`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(200);
      expect(res.body.session.participants).toHaveLength(2);
    });

    it('spawns new session when existing session has 8 participants', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);

      // Fill session to 8 participants (host + 7 more)
      for (let i = 0; i < 7; i++) {
        const { memberUserId } = await addMemberToGroup(hostToken, groupId);
        await testDb
          .insertInto('focus_session_participants')
          .values({ session_id: sessionId, user_id: memberUserId })
          .execute();
      }

      // 9th person joins → should auto-spawn new session
      const { memberAccessToken: lateToken } = await addMemberToGroup(hostToken, groupId);
      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/join`)
        .set('Authorization', `Bearer ${lateToken}`);

      expect(res.status).toBe(201);
      expect(res.body.spawned).toBe(true);
      expect(res.body.session.id).not.toBe(sessionId);
    });

    it('returns 409 when session is ended', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId, { status: 'ended' });
      const { memberAccessToken: memberToken } = await addMemberToGroup(hostToken, groupId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/join`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(409);
    });
  });

  // ---------------------------------------------------------------------------
  // POST /api/groups/:groupId/sessions/:id/start
  // ---------------------------------------------------------------------------
  describe('POST /api/groups/:groupId/sessions/:id/start', () => {
    it('host starts session — returns 200 with status=focus', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, userId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/start`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.session.status).toBe('focus');
      expect(res.body.session.started_at).toBeTruthy();
    });

    it('non-host cannot start session — returns 403 FORBIDDEN', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberAccessToken: memberToken } = await addMemberToGroup(hostToken, groupId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/start`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('FORBIDDEN');
    });

    it('returns 409 when session is already in focus', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, userId, { status: 'focus' });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/start`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(409);
    });
  });

  // ---------------------------------------------------------------------------
  // POST /api/groups/:groupId/sessions/:id/end
  // ---------------------------------------------------------------------------
  describe('POST /api/groups/:groupId/sessions/:id/end', () => {
    it('host ends session — returns 200 with status=ended', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, userId, { status: 'focus' });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/end`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.session.status).toBe('ended');
      expect(res.body.session.ended_at).toBeTruthy();
    });

    it('non-host cannot end session — returns 403', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId, { status: 'lobby' });
      const { memberAccessToken: memberToken } = await addMemberToGroup(hostToken, groupId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/end`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(403);
    });

    it('returns 409 when session already ended', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, userId, { status: 'ended' });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/end`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(409);
    });
  });

  // ---------------------------------------------------------------------------
  // DELETE /api/groups/:groupId/sessions/:id/leave
  // ---------------------------------------------------------------------------
  describe('DELETE /api/groups/:groupId/sessions/:id/leave', () => {
    it('participant leaves — returns 204', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberAccessToken: memberToken, memberUserId } = await addMemberToGroup(hostToken, groupId);

      // Add member as participant
      await testDb
        .insertInto('focus_session_participants')
        .values({ session_id: sessionId, user_id: memberUserId })
        .execute();

      const res = await request(app)
        .delete(`/api/groups/${groupId}/sessions/${sessionId}/leave`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(204);

      // Verify member has left_at set
      const participant = await testDb
        .selectFrom('focus_session_participants')
        .select('left_at')
        .where('session_id', '=', sessionId)
        .where('user_id', '=', memberUserId)
        .executeTakeFirst();
      expect(participant?.left_at).not.toBeNull();
    });

    it('host leaving promotes next participant', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      await testDb
        .insertInto('focus_session_participants')
        .values({ session_id: sessionId, user_id: memberId })
        .execute();

      const res = await request(app)
        .delete(`/api/groups/${groupId}/sessions/${sessionId}/leave`)
        .set('Authorization', `Bearer ${hostToken}`);

      expect(res.status).toBe(204);

      const updatedSession = await testDb
        .selectFrom('focus_sessions')
        .select(['host_user_id', 'status'])
        .where('id', '=', sessionId)
        .executeTakeFirst();

      expect(updatedSession?.host_user_id).toBe(memberId);
      expect(updatedSession?.status).toBe('lobby'); // session continues
    });

    it('last participant leaving auto-ends session', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, userId);

      const res = await request(app)
        .delete(`/api/groups/${groupId}/sessions/${sessionId}/leave`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(204);

      const session = await testDb
        .selectFrom('focus_sessions')
        .select('status')
        .where('id', '=', sessionId)
        .executeTakeFirst();

      expect(session?.status).toBe('ended');
    });
  });

  // ---------------------------------------------------------------------------
  // UUID validation (Issue #4)
  // ---------------------------------------------------------------------------
  describe('UUID validation', () => {
    it('returns 404 for invalid groupId UUID on create session', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .post('/api/groups/not-a-uuid/sessions')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ focus_duration_minutes: 25 });

      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('NOT_FOUND');
    });

    it('returns 404 for invalid sessionId UUID on join', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/not-a-uuid/join`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe('NOT_FOUND');
    });
  });

  // ---------------------------------------------------------------------------
  // Premium bypass on overflow spawn (Issue #1)
  // ---------------------------------------------------------------------------
  describe('Premium bypass on overflow spawn', () => {
    it('returns 403 PREMIUM_REQUIRED when free user joins full 90-min session', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      await setUserPremium(hostId);
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });

      // Create a 90-min session
      const res1 = await request(app)
        .post(`/api/groups/${groupId}/sessions`)
        .set('Authorization', `Bearer ${hostToken}`)
        .send({ focus_duration_minutes: 90 });
      const sessionId = res1.body.session.id;

      // Fill session to 8 participants (host not auto-inserted, so 8 manual inserts)
      for (let i = 0; i < 8; i++) {
        const { memberUserId } = await addMemberToGroup(hostToken, groupId);
        await testDb
          .insertInto('focus_session_participants')
          .values({ session_id: sessionId, user_id: memberUserId })
          .execute();
      }

      // Free user joins full 90-min session → should get 403
      const { memberAccessToken: freeToken } = await addMemberToGroup(hostToken, groupId);
      const res = await request(app)
        .post(`/api/groups/${groupId}/sessions/${sessionId}/join`)
        .set('Authorization', `Bearer ${freeToken}`);

      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('PREMIUM_REQUIRED');
    });
  });
});
