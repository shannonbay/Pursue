import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';

describe('User Consents API', () => {
  describe('GET /api/users/me/consents', () => {
    it('should return user consent entries', async () => {
      const { userId, accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .get('/api/users/me/consents')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.consents).toBeDefined();
      expect(Array.isArray(response.body.consents)).toBe(true);
      // Registration creates at least one consent entry
      expect(response.body.consents.length).toBeGreaterThanOrEqual(1);
      expect(response.body.consents[0]).toHaveProperty('consent_type');
      expect(response.body.consents[0]).toHaveProperty('agreed_at');
    });

    it('should return 401 without auth', async () => {
      const response = await request(app)
        .get('/api/users/me/consents');

      expect(response.status).toBe(401);
    });

    it('should return consents ordered by agreed_at desc', async () => {
      const { userId, accessToken } = await createAuthenticatedUser();

      // Add another consent entry
      await testDb.insertInto('user_consents').values({
        user_id: userId,
        consent_type: 'terms Feb 11, 2026',
        ip_address: null,
      }).execute();

      const response = await request(app)
        .get('/api/users/me/consents')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.consents.length).toBeGreaterThanOrEqual(2);
      // Most recent first
      const dates = response.body.consents.map((c: any) => new Date(c.agreed_at).getTime());
      for (let i = 0; i < dates.length - 1; i++) {
        expect(dates[i]).toBeGreaterThanOrEqual(dates[i + 1]);
      }
    });
  });

  describe('POST /api/users/me/consents', () => {
    it('should record new consent entries', async () => {
      const { userId, accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .post('/api/users/me/consents')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          consent_types: ['terms Feb 11, 2026', 'privacy policy Feb 11, 2026'],
        });

      expect(response.status).toBe(201);
      expect(response.body.success).toBe(true);

      // Verify entries were created
      const consents = await testDb
        .selectFrom('user_consents')
        .selectAll()
        .where('user_id', '=', userId)
        .execute();

      const termsEntry = consents.find(c => c.consent_type === 'terms Feb 11, 2026');
      const privacyEntry = consents.find(c => c.consent_type === 'privacy policy Feb 11, 2026');
      expect(termsEntry).toBeDefined();
      expect(privacyEntry).toBeDefined();
    });

    it('should reject empty consent_types array', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .post('/api/users/me/consents')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          consent_types: [],
        });

      expect(response.status).toBe(400);
    });

    it('should return 401 without auth', async () => {
      const response = await request(app)
        .post('/api/users/me/consents')
        .send({
          consent_types: ['terms Feb 11, 2026'],
        });

      expect(response.status).toBe(401);
    });

    it('should reject extra fields (strict mode)', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .post('/api/users/me/consents')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          consent_types: ['terms Feb 11, 2026'],
          extra_field: 'should fail',
        });

      expect(response.status).toBe(400);
    });
  });
});
