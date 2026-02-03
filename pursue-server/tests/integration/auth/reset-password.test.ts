import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createTestUser, createPasswordResetToken, createExpiredPasswordResetToken } from '../../helpers';

describe('POST /api/auth/reset-password', () => {
  let userId: string;

  beforeEach(async () => {
    const user = await createTestUser('test@example.com', 'OldPassword123!', 'Test User');
    userId = user.id;
  });

  it('should reset password and return tokens', async () => {
    const resetToken = await createPasswordResetToken(userId);

    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: 'NewPassword456!'
      });

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
  });

  it('should allow login with new password after reset', async () => {
    const resetToken = await createPasswordResetToken(userId);
    const newPassword = 'NewPassword456!';

    await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: newPassword
      });

    const loginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: newPassword
      });

    expect(loginResponse.status).toBe(200);
  });

  it('should not allow login with old password after reset', async () => {
    const resetToken = await createPasswordResetToken(userId);

    await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: 'NewPassword456!'
      });

    const loginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'OldPassword123!'
      });

    expect(loginResponse.status).toBe(401);
  });

  it('should mark reset token as used', async () => {
    const resetToken = await createPasswordResetToken(userId);

    await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: 'NewPassword456!'
      });

    const storedToken = await testDb
      .selectFrom('password_reset_tokens')
      .selectAll()
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(storedToken?.used_at).not.toBeNull();
  });

  it('should reject already used token', async () => {
    const resetToken = await createPasswordResetToken(userId);

    // Use the token first time
    await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: 'NewPassword456!'
      });

    // Try to use same token again
    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: 'AnotherPassword789!'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('TOKEN_USED');
  });

  it('should reject expired token', async () => {
    const expiredToken = await createExpiredPasswordResetToken(userId);

    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: expiredToken,
        new_password: 'NewPassword456!'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('TOKEN_EXPIRED');
  });

  it('should reject invalid token', async () => {
    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: 'invalid-token',
        new_password: 'NewPassword456!'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_RESET_TOKEN');
  });

  it('should reject password shorter than 8 characters', async () => {
    const resetToken = await createPasswordResetToken(userId);

    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: '123'
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing token', async () => {
    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        new_password: 'NewPassword456!'
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing new_password', async () => {
    const resetToken = await createPasswordResetToken(userId);

    const response = await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken
      });

    expect(response.status).toBe(400);
  });

  it('should create email auth provider if user only had Google', async () => {
    // Create a Google-only user
    const googleUser = await testDb
      .insertInto('users')
      .values({
        email: 'google@example.com',
        display_name: 'Google User',
        password_hash: null,
      })
      .returning(['id'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: googleUser.id,
        provider: 'google',
        provider_user_id: 'google-123',
        provider_email: 'google@example.com',
      })
      .execute();

    const resetToken = await createPasswordResetToken(googleUser.id);

    await request(app)
      .post('/api/auth/reset-password')
      .send({
        token: resetToken,
        new_password: 'NewPassword456!'
      });

    const emailProvider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', googleUser.id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(emailProvider).toBeDefined();
  });
});
