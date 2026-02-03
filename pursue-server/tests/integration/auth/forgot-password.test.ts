import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createTestUser } from '../../helpers';

describe('POST /api/auth/forgot-password', () => {
  beforeEach(async () => {
    await createTestUser('test@example.com', 'Test123!@#', 'Test User');
  });

  it('should return success message for existing email', async () => {
    const response = await request(app)
      .post('/api/auth/forgot-password')
      .send({
        email: 'test@example.com'
      });

    expect(response.status).toBe(200);
    expect(response.body.message).toContain('password reset link');
  });

  it('should create password reset token in database', async () => {
    const user = await testDb
      .selectFrom('users')
      .select('id')
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    await request(app)
      .post('/api/auth/forgot-password')
      .send({
        email: 'test@example.com'
      });

    const resetToken = await testDb
      .selectFrom('password_reset_tokens')
      .selectAll()
      .where('user_id', '=', user!.id)
      .executeTakeFirst();

    expect(resetToken).toBeDefined();
    expect(resetToken?.used_at).toBeNull();
    expect(new Date(resetToken!.expires_at).getTime()).toBeGreaterThan(Date.now());
  });

  it('should return same success message for non-existent email (prevent enumeration)', async () => {
    const response = await request(app)
      .post('/api/auth/forgot-password')
      .send({
        email: 'nonexistent@example.com'
      });

    expect(response.status).toBe(200);
    expect(response.body.message).toContain('password reset link');
  });

  it('should not create token for non-existent email', async () => {
    await request(app)
      .post('/api/auth/forgot-password')
      .send({
        email: 'nonexistent@example.com'
      });

    const tokens = await testDb
      .selectFrom('password_reset_tokens')
      .selectAll()
      .execute();

    expect(tokens.length).toBe(0);
  });

  it('should handle email case-insensitively', async () => {
    const user = await testDb
      .selectFrom('users')
      .select('id')
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    await request(app)
      .post('/api/auth/forgot-password')
      .send({
        email: 'TEST@EXAMPLE.COM'
      });

    const resetToken = await testDb
      .selectFrom('password_reset_tokens')
      .selectAll()
      .where('user_id', '=', user!.id)
      .executeTakeFirst();

    expect(resetToken).toBeDefined();
  });

  it('should reject invalid email format', async () => {
    const response = await request(app)
      .post('/api/auth/forgot-password')
      .send({
        email: 'not-an-email'
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing email', async () => {
    const response = await request(app)
      .post('/api/auth/forgot-password')
      .send({});

    expect(response.status).toBe(400);
  });

  it('should allow multiple reset tokens for same user', async () => {
    const user = await testDb
      .selectFrom('users')
      .select('id')
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    // Request first reset
    await request(app)
      .post('/api/auth/forgot-password')
      .send({ email: 'test@example.com' });

    // Request second reset
    await request(app)
      .post('/api/auth/forgot-password')
      .send({ email: 'test@example.com' });

    const tokens = await testDb
      .selectFrom('password_reset_tokens')
      .selectAll()
      .where('user_id', '=', user!.id)
      .execute();

    expect(tokens.length).toBe(2);
  });
});
