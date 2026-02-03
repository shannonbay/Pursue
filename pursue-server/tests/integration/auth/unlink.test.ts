import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';

describe('DELETE /api/auth/unlink/:provider', () => {
  it('should unlink Google provider when user has email provider', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Add Google provider to user
    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: userId,
        provider: 'google',
        provider_user_id: 'google-to-unlink',
        provider_email: 'google@gmail.com',
      })
      .execute();

    const response = await request(app)
      .delete('/api/auth/unlink/google')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);

    // Verify Google provider was removed
    const googleProvider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', userId)
      .where('provider', '=', 'google')
      .executeTakeFirst();

    expect(googleProvider).toBeUndefined();

    // Verify email provider still exists
    const emailProvider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', userId)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(emailProvider).toBeDefined();
  });

  it('should unlink email provider when user has Google provider', async () => {
    // Create user with both providers
    const user = await testDb
      .insertInto('users')
      .values({
        email: 'both@example.com',
        display_name: 'Both Providers User',
        password_hash: 'some-hash',
      })
      .returning(['id', 'email'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values([
        {
          user_id: user.id,
          provider: 'email',
          provider_user_id: user.email,
          provider_email: user.email,
        },
        {
          user_id: user.id,
          provider: 'google',
          provider_user_id: 'google-123',
          provider_email: user.email,
        },
      ])
      .execute();

    // Login to get access token
    const loginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'both@example.com',
        password: 'some-hash' // Won't work, need to use proper password
      });

    // Use register to get a valid token
    const { accessToken, userId } = await createAuthenticatedUser('test-unlink@example.com');

    // Add Google provider
    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: userId,
        provider: 'google',
        provider_user_id: 'google-unlink-email',
        provider_email: 'test-unlink@example.com',
      })
      .execute();

    const response = await request(app)
      .delete('/api/auth/unlink/email')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);

    // Verify email provider was removed
    const emailProvider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', userId)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(emailProvider).toBeUndefined();

    // Verify password was cleared
    const userRecord = await testDb
      .selectFrom('users')
      .select('password_hash')
      .where('id', '=', userId)
      .executeTakeFirst();

    expect(userRecord?.password_hash).toBeNull();
  });

  it('should reject unlinking last provider', async () => {
    const { accessToken } = await createAuthenticatedUser();

    // User only has email provider from registration
    const response = await request(app)
      .delete('/api/auth/unlink/email')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CANNOT_UNLINK_LAST_PROVIDER');
  });

  it('should reject unlinking provider user does not have (returns last provider error)', async () => {
    const { accessToken } = await createAuthenticatedUser();

    // User only has email provider, trying to unlink google
    // But since user has only 1 provider, they get CANNOT_UNLINK_LAST_PROVIDER first
    const response = await request(app)
      .delete('/api/auth/unlink/google')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CANNOT_UNLINK_LAST_PROVIDER');
  });

  it('should reject invalid provider name', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .delete('/api/auth/unlink/facebook')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_PROVIDER');
  });

  it('should reject request without access token', async () => {
    const response = await request(app)
      .delete('/api/auth/unlink/google');

    expect(response.status).toBe(401);
  });

  it('should reject request with invalid access token', async () => {
    const response = await request(app)
      .delete('/api/auth/unlink/google')
      .set('Authorization', 'Bearer invalid-token');

    expect(response.status).toBe(401);
  });

  it('should return PROVIDER_NOT_FOUND when user has multiple providers but not the requested one', async () => {
    const { accessToken, userId } = await createAuthenticatedUser('multi-provider@example.com');

    // Add a second provider (not google) so user has 2 providers
    // We insert 'apple' to simulate a future provider type
    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: userId,
        provider: 'apple',
        provider_user_id: 'apple-user-123',
        provider_email: 'multi-provider@example.com',
      })
      .execute();

    // User now has [email, apple], try to unlink google
    const response = await request(app)
      .delete('/api/auth/unlink/google')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('PROVIDER_NOT_FOUND');
  });
});
