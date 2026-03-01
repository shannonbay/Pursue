import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  setUserPremium,
  randomEmail,
  createFocusSlot,
  createSlotRsvp,
} from '../../helpers';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: string) => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
}));

const { sendGroupNotification, sendNotificationToUser } = jest.requireMock('../../../src/services/fcm.service');

function futureDate(offsetMs: number = 60 * 60 * 1000): Date {
  return new Date(Date.now() + offsetMs);
}

describe('Slot REST endpoints', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  // ---------------------------------------------------------------------------
  // POST /api/groups/:groupId/slots
  // ---------------------------------------------------------------------------
  describe('POST /api/groups/:groupId/slots', () => {
    it('creates a slot and returns 201', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const scheduledStart = futureDate();

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          scheduled_start: scheduledStart.toISOString(),
          focus_duration_minutes: 25,
        });

      expect(res.status).toBe(201);
      expect(res.body.slot).toMatchObject({
        group_id: groupId,
        created_by: userId,
        focus_duration_minutes: 25,
      });
    });

    it('sends FCM notification on slot creation', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      await request(app)
        .post(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ scheduled_start: futureDate().toISOString(), focus_duration_minutes: 25 });

      expect(sendGroupNotification).toHaveBeenCalledTimes(1);
    });

    it('returns 422 for past scheduled_start', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const pastDate = new Date(Date.now() - 60000);

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ scheduled_start: pastDate.toISOString(), focus_duration_minutes: 25 });

      expect(res.status).toBe(422);
    });

    it('returns 403 PREMIUM_REQUIRED for 90-min slot as free user', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ scheduled_start: futureDate().toISOString(), focus_duration_minutes: 90 });

      expect(res.status).toBe(403);
      expect(res.body.error.code).toBe('PREMIUM_REQUIRED');
    });

    it('allows 90-min slot for premium user', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      await setUserPremium(userId);
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ scheduled_start: futureDate().toISOString(), focus_duration_minutes: 90 });

      expect(res.status).toBe(201);
    });

    it('stores optional note', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ scheduled_start: futureDate().toISOString(), focus_duration_minutes: 45, note: 'Writing sprint' });

      expect(res.status).toBe(201);
      expect(res.body.slot.note).toBe('Writing sprint');
    });
  });

  // ---------------------------------------------------------------------------
  // GET /api/groups/:groupId/slots
  // ---------------------------------------------------------------------------
  describe('GET /api/groups/:groupId/slots', () => {
    it('returns upcoming slots with RSVP count and user_rsvped flag', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, userId, futureDate());
      await createSlotRsvp(slotId, userId);

      const res = await request(app)
        .get(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.slots).toHaveLength(1);
      expect(res.body.slots[0]).toMatchObject({ id: slotId, rsvp_count: 1, user_rsvped: true });
    });

    it('does not return past or cancelled slots', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      // Past slot (scheduled_start in past)
      const pastSlotId = await createFocusSlot(groupId, userId, new Date(Date.now() - 60000));
      // Cancelled upcoming slot
      const cancelledSlotId = await createFocusSlot(groupId, userId, futureDate());
      await testDb
        .updateTable('focus_slots')
        .set({ cancelled_at: new Date().toISOString() })
        .where('id', '=', cancelledSlotId)
        .execute();

      const res = await request(app)
        .get(`/api/groups/${groupId}/slots`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.slots).toHaveLength(0);
      void pastSlotId;
    });
  });

  // ---------------------------------------------------------------------------
  // DELETE /api/groups/:groupId/slots/:id
  // ---------------------------------------------------------------------------
  describe('DELETE /api/groups/:groupId/slots/:id', () => {
    it('creator can cancel slot — returns 204', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, userId, futureDate());

      const res = await request(app)
        .delete(`/api/groups/${groupId}/slots/${slotId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(204);

      const slot = await testDb
        .selectFrom('focus_slots')
        .select('cancelled_at')
        .where('id', '=', slotId)
        .executeTakeFirst();
      expect(slot?.cancelled_at).not.toBeNull();
    });

    it('non-creator cannot cancel slot — returns 403', async () => {
      const { accessToken: creatorToken, userId: creatorId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creatorToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, creatorId, futureDate());
      const { memberAccessToken: memberToken } = await addMemberToGroup(creatorToken, groupId);

      const res = await request(app)
        .delete(`/api/groups/${groupId}/slots/${slotId}`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(403);
    });
  });

  // ---------------------------------------------------------------------------
  // POST /api/groups/:groupId/slots/:id/rsvp
  // ---------------------------------------------------------------------------
  describe('POST /api/groups/:groupId/slots/:id/rsvp', () => {
    it('RSVP returns 201', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, hostId, futureDate());
      const { memberAccessToken: memberToken } = await addMemberToGroup(hostToken, groupId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots/${slotId}/rsvp`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(res.status).toBe(201);
    });

    it('duplicate RSVP returns 409 ALREADY_RSVPED', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, userId, futureDate());
      await createSlotRsvp(slotId, userId);

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots/${slotId}/rsvp`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(409);
      expect(res.body.error.code).toBe('ALREADY_RSVPED');
    });

    it('RSVP to cancelled slot returns 422', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, userId, futureDate());
      await testDb
        .updateTable('focus_slots')
        .set({ cancelled_at: new Date().toISOString() })
        .where('id', '=', slotId)
        .execute();

      const res = await request(app)
        .post(`/api/groups/${groupId}/slots/${slotId}/rsvp`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(422);
    });

    it('3rd RSVP triggers FCM milestone notification to slot creator', async () => {
      const { accessToken: creatorToken, userId: creatorId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creatorToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, creatorId, futureDate());

      // Pre-fill 2 RSVPs via helper
      const user2 = await addMemberToGroup(creatorToken, groupId);
      const user3 = await addMemberToGroup(creatorToken, groupId);
      await createSlotRsvp(slotId, user2.memberUserId);
      await createSlotRsvp(slotId, user3.memberUserId);

      jest.clearAllMocks();

      // 3rd RSVP (creator RSVPs)
      await request(app)
        .post(`/api/groups/${groupId}/slots/${slotId}/rsvp`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(sendNotificationToUser).toHaveBeenCalledTimes(1);
      expect(sendNotificationToUser).toHaveBeenCalledWith(
        creatorId,
        expect.objectContaining({ title: expect.stringContaining('Focus Session') }),
        expect.objectContaining({ type: 'rsvp_milestone' })
      );
    });
  });

  // ---------------------------------------------------------------------------
  // DELETE /api/groups/:groupId/slots/:id/rsvp
  // ---------------------------------------------------------------------------
  describe('DELETE /api/groups/:groupId/slots/:id/rsvp', () => {
    it('un-RSVP returns 204', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, userId, futureDate());
      await createSlotRsvp(slotId, userId);

      const res = await request(app)
        .delete(`/api/groups/${groupId}/slots/${slotId}/rsvp`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(204);

      const rsvp = await testDb
        .selectFrom('focus_slot_rsvps')
        .select('id')
        .where('slot_id', '=', slotId)
        .where('user_id', '=', userId)
        .executeTakeFirst();
      expect(rsvp).toBeUndefined();
    });

    it('un-RSVP when not RSVPed is still 204 (idempotent)', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const slotId = await createFocusSlot(groupId, userId, futureDate());

      const res = await request(app)
        .delete(`/api/groups/${groupId}/slots/${slotId}/rsvp`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(204);
    });
  });

  // ---------------------------------------------------------------------------
  // GET /api/me/slots
  // ---------------------------------------------------------------------------
  describe('GET /api/me/slots', () => {
    it('returns upcoming slots across all groups', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'SlotUser');
      await setUserPremium(userId);
      const { groupId: groupId1 } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const { groupId: groupId2 } = await createGroupWithGoal(accessToken, { includeGoal: false, groupName: 'Second Group' });

      await createFocusSlot(groupId1, userId, futureDate(60 * 60 * 1000));
      await createFocusSlot(groupId2, userId, futureDate(2 * 60 * 60 * 1000));

      const res = await request(app)
        .get('/api/me/slots')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.slots).toHaveLength(2);
    });

    it('does not return slots from groups user is not a member of', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      const { accessToken: otherToken, userId: otherId } = await createAuthenticatedUser(randomEmail());
      const { groupId: otherGroupId } = await createGroupWithGoal(otherToken, { includeGoal: false });
      await createFocusSlot(otherGroupId, otherId, futureDate());

      const res = await request(app)
        .get('/api/me/slots')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.slots).toHaveLength(0);
    });

    it('returns empty array when user has no groups', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .get('/api/me/slots')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.slots).toHaveLength(0);
    });
  });
});
