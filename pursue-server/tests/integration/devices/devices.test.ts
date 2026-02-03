import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';

describe('Device Endpoints', () => {
  describe('POST /api/devices/register', () => {
    it('should register a new device', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .post('/api/devices/register')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          fcm_token: 'test-fcm-token-12345',
          device_name: 'Pixel 8 Pro',
          platform: 'android',
        });

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('id');
      expect(response.body.device_name).toBe('Pixel 8 Pro');
      expect(response.body.platform).toBe('android');
    });

    it('should update existing device with same FCM token', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('update-device@example.com');
      const fcmToken = 'existing-fcm-token-67890';

      // Create initial device
      await testDb
        .insertInto('devices')
        .values({
          user_id: userId,
          fcm_token: fcmToken,
          device_name: 'Old Device',
          platform: 'ios',
        })
        .execute();

      // Update with same FCM token
      const response = await request(app)
        .post('/api/devices/register')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          fcm_token: fcmToken,
          device_name: 'New Device Name',
          platform: 'android',
        });

      expect(response.status).toBe(200);
      expect(response.body.device_name).toBe('New Device Name');
      expect(response.body.platform).toBe('android');

      // Verify only one device with this token exists
      const devices = await testDb
        .selectFrom('devices')
        .selectAll()
        .where('fcm_token', '=', fcmToken)
        .execute();

      expect(devices.length).toBe(1);
    });

    it('should register device without optional fields', async () => {
      const { accessToken } = await createAuthenticatedUser('minimal-device@example.com');

      const response = await request(app)
        .post('/api/devices/register')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          fcm_token: 'minimal-fcm-token',
        });

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('id');
      expect(response.body.device_name).toBeNull();
      expect(response.body.platform).toBeNull();
    });

    it('should reject request without authentication', async () => {
      const response = await request(app)
        .post('/api/devices/register')
        .send({
          fcm_token: 'unauthenticated-token',
        });

      expect(response.status).toBe(401);
    });

    it('should reject request with missing FCM token', async () => {
      const { accessToken } = await createAuthenticatedUser('missing-token@example.com');

      const response = await request(app)
        .post('/api/devices/register')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          device_name: 'Test Device',
        });

      expect(response.status).toBe(400);
    });

    it('should reject request with invalid platform', async () => {
      const { accessToken } = await createAuthenticatedUser('invalid-platform@example.com');

      const response = await request(app)
        .post('/api/devices/register')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          fcm_token: 'valid-token',
          platform: 'windows',
        });

      expect(response.status).toBe(400);
    });
  });

  describe('GET /api/devices', () => {
    it('should list user devices', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('list-devices@example.com');

      // Create multiple devices
      await testDb
        .insertInto('devices')
        .values([
          {
            user_id: userId,
            fcm_token: 'device-1-token',
            device_name: 'Phone',
            platform: 'android',
          },
          {
            user_id: userId,
            fcm_token: 'device-2-token',
            device_name: 'Tablet',
            platform: 'ios',
          },
        ])
        .execute();

      const response = await request(app)
        .get('/api/devices')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.devices).toHaveLength(2);
      expect(response.body.devices[0]).toHaveProperty('id');
      expect(response.body.devices[0]).toHaveProperty('device_name');
      expect(response.body.devices[0]).toHaveProperty('platform');
      expect(response.body.devices[0]).toHaveProperty('last_active');
      expect(response.body.devices[0]).toHaveProperty('created_at');
    });

    it('should return empty array when user has no devices', async () => {
      const { accessToken } = await createAuthenticatedUser('no-devices@example.com');

      const response = await request(app)
        .get('/api/devices')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.devices).toHaveLength(0);
    });

    it('should only list devices belonging to authenticated user', async () => {
      const { accessToken: user1Token, userId: user1Id } = await createAuthenticatedUser('user1-devices@example.com');
      const { userId: user2Id } = await createAuthenticatedUser('user2-devices@example.com');

      // Create devices for both users
      await testDb
        .insertInto('devices')
        .values([
          {
            user_id: user1Id,
            fcm_token: 'user1-device-token',
            device_name: 'User 1 Phone',
            platform: 'android',
          },
          {
            user_id: user2Id,
            fcm_token: 'user2-device-token',
            device_name: 'User 2 Phone',
            platform: 'ios',
          },
        ])
        .execute();

      const response = await request(app)
        .get('/api/devices')
        .set('Authorization', `Bearer ${user1Token}`);

      expect(response.status).toBe(200);
      expect(response.body.devices).toHaveLength(1);
      expect(response.body.devices[0].device_name).toBe('User 1 Phone');
    });

    it('should reject request without authentication', async () => {
      const response = await request(app).get('/api/devices');

      expect(response.status).toBe(401);
    });
  });

  describe('DELETE /api/devices/:device_id', () => {
    it('should delete user device', async () => {
      const { accessToken, userId } = await createAuthenticatedUser('delete-device@example.com');

      // Create a device
      const device = await testDb
        .insertInto('devices')
        .values({
          user_id: userId,
          fcm_token: 'delete-me-token',
          device_name: 'Delete Me',
          platform: 'android',
        })
        .returning(['id'])
        .executeTakeFirstOrThrow();

      const response = await request(app)
        .delete(`/api/devices/${device.id}`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(204);

      // Verify device was deleted
      const deletedDevice = await testDb
        .selectFrom('devices')
        .selectAll()
        .where('id', '=', device.id)
        .executeTakeFirst();

      expect(deletedDevice).toBeUndefined();
    });

    it('should return 404 for non-existent device', async () => {
      const { accessToken } = await createAuthenticatedUser('nonexistent-device@example.com');

      const response = await request(app)
        .delete('/api/devices/00000000-0000-0000-0000-000000000000')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('DEVICE_NOT_FOUND');
    });

    it('should not allow deleting another user device', async () => {
      const { accessToken: user1Token } = await createAuthenticatedUser('attacker@example.com');
      const { userId: user2Id } = await createAuthenticatedUser('victim@example.com');

      // Create device for user2
      const device = await testDb
        .insertInto('devices')
        .values({
          user_id: user2Id,
          fcm_token: 'victim-device-token',
          device_name: 'Victim Device',
          platform: 'android',
        })
        .returning(['id'])
        .executeTakeFirstOrThrow();

      // Try to delete with user1's token
      const response = await request(app)
        .delete(`/api/devices/${device.id}`)
        .set('Authorization', `Bearer ${user1Token}`);

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('DEVICE_NOT_FOUND');

      // Verify device still exists
      const existingDevice = await testDb
        .selectFrom('devices')
        .selectAll()
        .where('id', '=', device.id)
        .executeTakeFirst();

      expect(existingDevice).toBeDefined();
    });

    it('should reject request without authentication', async () => {
      const response = await request(app)
        .delete('/api/devices/some-device-id');

      expect(response.status).toBe(401);
    });
  });
});
