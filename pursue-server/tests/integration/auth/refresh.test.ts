import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';

describe('POST /api/auth/refresh', () => {
  it('should return new access token and new refresh token with valid refresh token', async () => {
    const { refreshToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/auth/refresh')
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(typeof response.body.access_token).toBe('string');
    expect(typeof response.body.refresh_token).toBe('string');
    // New refresh token should be different from old one
    expect(response.body.refresh_token).not.toBe(refreshToken);
  });

  it('should reject invalid refresh token', async () => {
    const response = await request(app)
      .post('/api/auth/refresh')
      .send({
        refresh_token: 'invalid-token'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_REFRESH_TOKEN');
  });

  it('should reject missing refresh token', async () => {
    const response = await request(app)
      .post('/api/auth/refresh')
      .send({});

    expect(response.status).toBe(400);
  });

  it('should reject revoked refresh token', async () => {
    const { refreshToken, userId } = await createAuthenticatedUser();

    // Revoke all refresh tokens for this user
    await testDb
      .updateTable('refresh_tokens')
      .set({ revoked_at: new Date() })
      .where('user_id', '=', userId)
      .execute();

    const response = await request(app)
      .post('/api/auth/refresh')
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('TOKEN_REVOKED');
  });

  it('should reject expired refresh token', async () => {
    const { refreshToken, userId } = await createAuthenticatedUser();

    // Set expiry to past
    await testDb
      .updateTable('refresh_tokens')
      .set({ expires_at: new Date('2020-01-01') })
      .where('user_id', '=', userId)
      .execute();

    const response = await request(app)
      .post('/api/auth/refresh')
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('TOKEN_EXPIRED');
  });

  it('should implement single-use rotation (revoke old token on refresh)', async () => {
    const { refreshToken, userId } = await createAuthenticatedUser();

    // First refresh - should succeed and return new token
    const response1 = await request(app)
      .post('/api/auth/refresh')
      .send({ refresh_token: refreshToken });

    expect(response1.status).toBe(200);
    expect(response1.body).toHaveProperty('refresh_token');
    const newRefreshToken = response1.body.refresh_token;

    // Verify old token is now revoked in database
    const oldTokenRecord = await testDb
      .selectFrom('refresh_tokens')
      .select(['revoked_at'])
      .where('id', '=', (
        await testDb
          .selectFrom('refresh_tokens')
          .select('id')
          .where('user_id', '=', userId)
          .orderBy('created_at', 'asc')
          .limit(1)
      ))
      .executeTakeFirst();

    // Second refresh with old token should fail (revoked)
    const response2 = await request(app)
      .post('/api/auth/refresh')
      .send({ refresh_token: refreshToken });

    expect(response2.status).toBe(401);
    expect(response2.body.error.code).toBe('TOKEN_REVOKED');

    // Third refresh with new token should succeed
    const response3 = await request(app)
      .post('/api/auth/refresh')
      .send({ refresh_token: newRefreshToken });

    expect(response3.status).toBe(200);
    expect(response3.body).toHaveProperty('refresh_token');
    expect(response3.body.refresh_token).not.toBe(newRefreshToken);
  });

  it('should reject refresh token that was deleted from database', async () => {
    const { refreshToken, userId } = await createAuthenticatedUser('deleted-token@example.com');

    // Delete the token from database (simulates token cleanup or manual deletion)
    await testDb
      .deleteFrom('refresh_tokens')
      .where('user_id', '=', userId)
      .execute();

    // Token is valid JWT but not in database
    const response = await request(app)
      .post('/api/auth/refresh')
      .send({ refresh_token: refreshToken });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_REFRESH_TOKEN');
  });
});
