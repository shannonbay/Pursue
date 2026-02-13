import request from 'supertest';
import { format } from 'date-fns';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createTestGroupActivityWithId,
  addMemberToGroup,
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

function todayStr(): string {
  return format(new Date(), 'yyyy-MM-dd');
}

describe('Notification Triggers', () => {
  describe('Reaction creates notification', () => {
    it('should create notification when another user reacts to your activity', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
      if (!goalId) throw new Error('createGroupWithGoal should return goalId');
      const progressEntryId = await createProgressEntry(goalId, creator.userId, 1, todayStr());
      const activityId = await createTestGroupActivityWithId(
        groupId,
        creator.userId,
        'progress_logged',
        { goal_id: goalId, goal_title: 'Run', value: 1, progress_entry_id: progressEntryId }
      );

      const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

      await request(app)
        .put(`/api/activities/${activityId}/reactions`)
        .set('Authorization', `Bearer ${memberAccessToken}`)
        .send({ emoji: '🔥' });

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', creator.userId)
        .where('type', '=', 'reaction_received')
        .execute();

      expect(notifs).toHaveLength(1);
      expect(notifs[0].actor_user_id).toBeDefined();
      expect(notifs[0].metadata).toEqual({ emoji: '🔥' });
    });

    it('should not create notification when user reacts to own activity', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(accessToken);
      const activityId = await createTestGroupActivityWithId(
        groupId,
        userId,
        'progress_logged',
        { goal_id: goalId }
      );

      await request(app)
        .put(`/api/activities/${activityId}/reactions`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ emoji: '🔥' });

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', userId)
        .where('type', '=', 'reaction_received')
        .execute();

      expect(notifs).toHaveLength(0);
    });
  });

  describe('Nudge creates notification', () => {
    it('should create notification for recipient when nudge is sent', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Sender');
      const { groupId, goalId } = await createGroupWithGoal(creatorToken);
      const { memberUserId: recipientId } = await addMemberToGroup(creatorToken, groupId);

      await request(app)
        .post('/api/nudges')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({
          recipient_user_id: recipientId,
          group_id: groupId,
          goal_id: goalId,
          sender_local_date: todayStr(),
        });

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', recipientId)
        .where('type', '=', 'nudge_received')
        .execute();

      expect(notifs).toHaveLength(1);
      expect(notifs[0].actor_user_id).toBeDefined();
    });
  });

  describe('Membership approved/rejected creates notification', () => {
    it('should create notification when membership is approved', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Admin');
      const { groupId } = await createGroupWithGoal(creator.accessToken);

      const invRes = await request(app)
        .get(`/api/groups/${groupId}/invite`)
        .set('Authorization', `Bearer ${creator.accessToken}`);
      const code = invRes.body.invite_code;

      const applicant = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Applicant');
      await request(app)
        .post('/api/groups/join')
        .set('Authorization', `Bearer ${applicant.accessToken}`)
        .send({ invite_code: code });

      await request(app)
        .post(`/api/groups/${groupId}/members/${applicant.userId}/approve`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', applicant.userId)
        .where('type', '=', 'membership_approved')
        .execute();

      expect(notifs).toHaveLength(1);
      expect(notifs[0].actor_user_id).toBe(creator.userId);
    });

    it('should create notification when membership is rejected', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Admin');
      const { groupId } = await createGroupWithGoal(creator.accessToken);

      const invRes = await request(app)
        .get(`/api/groups/${groupId}/invite`)
        .set('Authorization', `Bearer ${creator.accessToken}`);
      const code = invRes.body.invite_code;

      const applicant = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Applicant');
      await request(app)
        .post('/api/groups/join')
        .set('Authorization', `Bearer ${applicant.accessToken}`)
        .send({ invite_code: code });

      await request(app)
        .post(`/api/groups/${groupId}/members/${applicant.userId}/decline`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', applicant.userId)
        .where('type', '=', 'membership_rejected')
        .execute();

      expect(notifs).toHaveLength(1);
    });
  });

  describe('Promoted to admin creates notification', () => {
    it('should create notification when member is promoted to admin', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      const { memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

      await request(app)
        .patch(`/api/groups/${groupId}/members/${memberUserId}`)
        .set('Authorization', `Bearer ${creator.accessToken}`)
        .send({ role: 'admin' });

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', memberUserId)
        .where('type', '=', 'promoted_to_admin')
        .execute();

      expect(notifs).toHaveLength(1);
      expect(notifs[0].actor_user_id).toBe(creator.userId);
    });
  });

  describe('Removed from group creates notification', () => {
    it('should create notification when member is removed', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      const { memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

      await request(app)
        .delete(`/api/groups/${groupId}/members/${memberUserId}`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      const notifs = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('user_id', '=', memberUserId)
        .where('type', '=', 'removed_from_group')
        .execute();

      expect(notifs).toHaveLength(1);
    });
  });
});
