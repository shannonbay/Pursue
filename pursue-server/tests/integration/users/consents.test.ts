import request from 'supertest';
import crypto from 'crypto';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

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

  describe('POST /api/users/consent-hash', () => {
    it('should return 200 with correct hash for a given email', async () => {
      const email = 'lookup@example.com';
      const response = await request(app)
        .post('/api/users/consent-hash')
        .send({ email });

      expect(response.status).toBe(200);
      expect(response.body.email_hash).toBeDefined();
      expect(typeof response.body.email_hash).toBe('string');
      expect(response.body.email_hash).toHaveLength(64);

      // Verify it matches the expected hash
      const expectedHash = crypto
        .createHash('sha256')
        .update(email.toLowerCase() + process.env.CONSENT_HASH_SALT!)
        .digest('hex');
      expect(response.body.email_hash).toBe(expectedHash);
    });

    it('should produce a hash matching the one stored after account deletion', async () => {
      const email = randomEmail();
      const { accessToken: deleteToken } = await createAuthenticatedUser(email);

      // Delete the user — ghost link hashes the email into consent records
      await request(app)
        .delete('/api/users/me')
        .set('Authorization', `Bearer ${deleteToken}`)
        .send({ confirmation: 'delete' });

      // Look up the hash via the consent-hash endpoint (no auth needed)
      const response = await request(app)
        .post('/api/users/consent-hash')
        .send({ email });

      expect(response.status).toBe(200);

      // Verify the ghost-linked consent record matches
      const consents = await testDb
        .selectFrom('user_consents')
        .select(['email_hash'])
        .where('email_hash', '=', response.body.email_hash)
        .where('user_id', 'is', null)
        .execute();

      expect(consents.length).toBeGreaterThanOrEqual(1);
    });

    it('should return 404 when NODE_ENV is not test', async () => {
      const originalEnv = process.env.NODE_ENV;
      process.env.NODE_ENV = 'production';

      try {
        const response = await request(app)
          .post('/api/users/consent-hash')
          .send({ email: 'test@example.com' });

        expect(response.status).toBe(404);
      } finally {
        process.env.NODE_ENV = originalEnv;
      }
    });

    it('should return 400 for invalid email', async () => {
      const response = await request(app)
        .post('/api/users/consent-hash')
        .send({ email: 'not-an-email' });

      expect(response.status).toBe(400);
    });
  });
});
