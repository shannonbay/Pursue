import request from 'supertest';
import sharp from 'sharp';
import { app } from '../../../src/app';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

async function createTestImage(): Promise<Buffer> {
  return await sharp({
    create: {
      width: 10,
      height: 10,
      channels: 3,
      background: { r: 255, g: 0, b: 0 },
    },
  })
    .png()
    .toBuffer();
}

describe("Cross-user isolation: User A cannot read or update User B's data", () => {
  describe('GET /api/users/me', () => {
    it("with User A's token, returns only A's id and email, never B's", async () => {
      const A = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User A');
      const B = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User B');

      const res = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${A.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.id).toBe(A.userId);
      expect(res.body.email).toBe(A.user.email);
      expect(res.body.id).not.toBe(B.userId);
      expect(res.body.email).not.toBe(B.user.email);
    });
  });

  describe('PATCH /api/users/me', () => {
    it("User A's PATCH /me does not change User B's display_name", async () => {
      const A = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User A');
      const B = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User B');
      const bDisplayNameBefore = B.user.display_name;

      await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${A.accessToken}`)
        .send({ display_name: 'AttackerName' });

      const resB = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${B.accessToken}`);

      expect(resB.status).toBe(200);
      expect(resB.body.display_name).toBe(bDisplayNameBefore);
      expect(resB.body.display_name).not.toBe('AttackerName');
    });
  });

  describe('POST /api/users/me/avatar', () => {
    it("User A uploading an avatar does not overwrite User B's avatar", async () => {
      const A = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User A');
      const B = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User B');

      await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${B.accessToken}`)
        .attach('avatar', await createTestImage(), 'avatar.png');

      const getBBefore = await request(app).get(`/api/users/${B.userId}/avatar`);
      expect(getBBefore.status).toBe(200);
      const avatarBBefore = Buffer.from(getBBefore.body);

      await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${A.accessToken}`)
        .attach('avatar', await createTestImage(), 'avatar.png');

      const getBAfter = await request(app).get(`/api/users/${B.userId}/avatar`);
      expect(getBAfter.status).toBe(200);
      const avatarBAfter = Buffer.from(getBAfter.body);

      expect(Buffer.compare(avatarBAfter, avatarBBefore)).toBe(0);
    });
  });

  describe('DELETE /api/users/me/avatar', () => {
    it("User A deleting own avatar does not remove User B's avatar", async () => {
      const A = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User A');
      const B = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User B');

      await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${A.accessToken}`)
        .attach('avatar', await createTestImage(), 'avatar.png');
      await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${B.accessToken}`)
        .attach('avatar', await createTestImage(), 'avatar.png');

      await request(app)
        .delete('/api/users/me/avatar')
        .set('Authorization', `Bearer ${A.accessToken}`);

      const getB = await request(app).get(`/api/users/${B.userId}/avatar`);
      expect(getB.status).toBe(200);
      expect(Buffer.isBuffer(getB.body) && getB.body.length > 0).toBe(true);

      const meA = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${A.accessToken}`);
      expect(meA.body.has_avatar).toBe(false);

      const meB = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${B.accessToken}`);
      expect(meB.body.has_avatar).toBe(true);
    });
  });
});
