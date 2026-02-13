import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createTestNotification,
  randomEmail,
} from '../../helpers';

describe('Notification Endpoints', () => {
  describe('GET /api/notifications', () => {
    it('should return user notifications', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('notif-list@example.com');
      const notifId = await createTestNotification(userId, 'reaction_received', {
        actorUserId: userId,
        metadata: { emoji: '🔥' },
      });

      const response = await request(app)
        .get('/api/notifications')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('notifications');
      expect(response.body).toHaveProperty('unread_count');
      expect(response.body).toHaveProperty('has_more');
      expect(Array.isArray(response.body.notifications)).toBe(true);
      expect(response.body.notifications.length).toBeGreaterThanOrEqual(1);
      const notif = response.body.notifications.find((n: { id: string }) => n.id === notifId);
      expect(notif).toBeDefined();
      expect(notif.type).toBe('reaction_received');
      expect(notif.metadata).toEqual({ emoji: '🔥' });
    });

    it('should return empty array when no notifications', async () => {
      const { accessToken } = await createAuthenticatedUser('no-notifs@example.com');

      const response = await request(app)
        .get('/api/notifications')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.notifications).toHaveLength(0);
      expect(response.body.unread_count).toBe(0);
      expect(response.body.has_more).toBe(false);
    });

    it('should support pagination with before_id', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('pagination@example.com');
      const ids: string[] = [];
      for (let i = 0; i < 5; i++) {
        ids.push(await createTestNotification(userId, 'nudge_received', { isRead: false }));
      }

      const first = await request(app)
        .get('/api/notifications?limit=2')
        .set('Authorization', `Bearer ${accessToken}`);
      expect(first.status).toBe(200);
      expect(first.body.notifications).toHaveLength(2);
      expect(first.body.has_more).toBe(true);

      const lastId = first.body.notifications[1].id;
      const second = await request(app)
        .get(`/api/notifications?limit=5&before_id=${lastId}`)
        .set('Authorization', `Bearer ${accessToken}`);
      expect(second.status).toBe(200);
      expect(second.body.notifications.length).toBeGreaterThanOrEqual(1);
    });

    it('should reject request without authentication', async () => {
      const response = await request(app).get('/api/notifications');
      expect(response.status).toBe(401);
    });

    it('should not return other users notifications', async () => {
      const { accessToken: user1Token } = await createAuthenticatedUser('user1-notifs@example.com');
      const { userId: user2Id } = await createAuthenticatedUser('user2-notifs@example.com');
      await createTestNotification(user2Id, 'nudge_received');

      const response = await request(app)
        .get('/api/notifications')
        .set('Authorization', `Bearer ${user1Token}`);

      expect(response.status).toBe(200);
      expect(response.body.notifications).toHaveLength(0);
    });
  });

  describe('GET /api/notifications/unread-count', () => {
    it('should return unread count', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('unread-count@example.com');
      await createTestNotification(userId, 'reaction_received', { isRead: false });
      await createTestNotification(userId, 'nudge_received', { isRead: false });
      await createTestNotification(userId, 'milestone_achieved', { isRead: true });

      const response = await request(app)
        .get('/api/notifications/unread-count')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.unread_count).toBe(2);
    });

    it('should return 0 when no unread', async () => {
      const { accessToken } = await createAuthenticatedUser('all-read@example.com');

      const response = await request(app)
        .get('/api/notifications/unread-count')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.unread_count).toBe(0);
    });

    it('should reject request without authentication', async () => {
      const response = await request(app).get('/api/notifications/unread-count');
      expect(response.status).toBe(401);
    });
  });

  describe('POST /api/notifications/mark-all-read', () => {
    it('should mark all notifications as read', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('mark-all@example.com');
      await createTestNotification(userId, 'reaction_received', { isRead: false });
      await createTestNotification(userId, 'nudge_received', { isRead: false });

      const response = await request(app)
        .post('/api/notifications/mark-all-read')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.marked_read).toBe(2);

      const unread = await request(app)
        .get('/api/notifications/unread-count')
        .set('Authorization', `Bearer ${accessToken}`);
      expect(unread.body.unread_count).toBe(0);
    });

    it('should return 0 when all already read', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('already-read@example.com');
      await createTestNotification(userId, 'reaction_received', { isRead: true });

      const response = await request(app)
        .post('/api/notifications/mark-all-read')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.marked_read).toBe(0);
    });

    it('should reject request without authentication', async () => {
      const response = await request(app).post('/api/notifications/mark-all-read');
      expect(response.status).toBe(401);
    });
  });

  describe('PATCH /api/notifications/:notification_id/read', () => {
    it('should mark single notification as read', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('mark-one@example.com');
      const notifId = await createTestNotification(userId, 'nudge_received', { isRead: false });

      const response = await request(app)
        .patch(`/api/notifications/${notifId}/read`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.id).toBe(notifId);
      expect(response.body.is_read).toBe(true);

      const row = await testDb
        .selectFrom('user_notifications')
        .select('is_read')
        .where('id', '=', notifId)
        .executeTakeFirst();
      expect(row?.is_read).toBe(true);
    });

    it('should return 404 for non-existent notification', async () => {
      const { accessToken } = await createAuthenticatedUser('not-found-read@example.com');

      const response = await request(app)
        .patch('/api/notifications/00000000-0000-0000-0000-000000000000/read')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('NOT_FOUND');
    });

    it('should not allow marking another users notification', async () => {
      const { accessToken: user1Token } = await createAuthenticatedUser('attacker-read@example.com');
      const { userId: user2Id } = await createAuthenticatedUser('victim-read@example.com');
      const notifId = await createTestNotification(user2Id, 'nudge_received');

      const response = await request(app)
        .patch(`/api/notifications/${notifId}/read`)
        .set('Authorization', `Bearer ${user1Token}`);

      expect(response.status).toBe(404);
    });

    it('should reject request without authentication', async () => {
      const response = await request(app)
        .patch('/api/notifications/00000000-0000-0000-0000-000000000000/read');
      expect(response.status).toBe(401);
    });
  });

  describe('DELETE /api/notifications/:notification_id', () => {
    it('should delete notification', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('delete-notif@example.com');
      const notifId = await createTestNotification(userId, 'reaction_received');

      const response = await request(app)
        .delete(`/api/notifications/${notifId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(204);

      const row = await testDb
        .selectFrom('user_notifications')
        .select('id')
        .where('id', '=', notifId)
        .executeTakeFirst();
      expect(row).toBeUndefined();
    });

    it('should return 404 for non-existent notification', async () => {
      const { accessToken } = await createAuthenticatedUser('delete-missing@example.com');

      const response = await request(app)
        .delete('/api/notifications/00000000-0000-0000-0000-000000000000')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('NOT_FOUND');
    });

    it('should not allow deleting another users notification', async () => {
      const { accessToken: user1Token } = await createAuthenticatedUser('attacker-del@example.com');
      const { userId: user2Id } = await createAuthenticatedUser('victim-del@example.com');
      const notifId = await createTestNotification(user2Id, 'nudge_received');

      const response = await request(app)
        .delete(`/api/notifications/${notifId}`)
        .set('Authorization', `Bearer ${user1Token}`);

      expect(response.status).toBe(404);

      const row = await testDb
        .selectFrom('user_notifications')
        .select('id')
        .where('id', '=', notifId)
        .executeTakeFirst();
      expect(row).toBeDefined();
    });

    it('should reject request without authentication', async () => {
      const response = await request(app)
        .delete('/api/notifications/00000000-0000-0000-0000-000000000000');
      expect(response.status).toBe(401);
    });
  });
});
