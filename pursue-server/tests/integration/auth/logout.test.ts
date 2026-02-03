import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';

describe('POST /api/auth/logout', () => {
  it('should revoke refresh token and return 204', async () => {
    const { accessToken, refreshToken, userId } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/auth/logout')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(204);

    // Verify refresh token is revoked in database
    const storedToken = await testDb
      .selectFrom('refresh_tokens')
      .selectAll()
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(storedToken?.revoked_at).not.toBeNull();
  });

  it('should reject request without access token', async () => {
    const { refreshToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/auth/logout')
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(401);
  });

  it('should reject request with invalid access token', async () => {
    const { refreshToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/auth/logout')
      .set('Authorization', 'Bearer invalid-token')
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(401);
  });

  it('should succeed even with invalid refresh token', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/auth/logout')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        refresh_token: 'invalid-refresh-token'
      });

    // Should still return 204 (logout is idempotent)
    expect(response.status).toBe(204);
  });

  it('should not allow using revoked refresh token after logout', async () => {
    const { accessToken, refreshToken } = await createAuthenticatedUser();

    // Logout
    await request(app)
      .post('/api/auth/logout')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        refresh_token: refreshToken
      });

    // Try to use the revoked refresh token
    const response = await request(app)
      .post('/api/auth/refresh')
      .send({
        refresh_token: refreshToken
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('TOKEN_REVOKED');
  });
});
