import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser } from '../../helpers';
import * as googleAuth from '../../../src/services/googleAuth';

// Mock the Google auth service
jest.mock('../../../src/services/googleAuth');
const mockedGoogleAuth = googleAuth as jest.Mocked<typeof googleAuth>;

describe('POST /api/auth/link/google', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should link Google account to existing user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-link-123',
      email: 'linked@gmail.com',
      name: 'Linked User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/link/google')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);
    expect(response.body.provider).toBe('google');
    expect(response.body.provider_email).toBe('linked@gmail.com');

    // Verify provider was added
    const provider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', userId)
      .where('provider', '=', 'google')
      .executeTakeFirst();

    expect(provider).toBeDefined();
    expect(provider?.provider_user_id).toBe('google-link-123');
  });

  it('should reject if Google account already linked to same user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // First, link the Google account
    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: userId,
        provider: 'google',
        provider_user_id: 'google-already-linked',
        provider_email: 'already@gmail.com',
      })
      .execute();

    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-already-linked',
      email: 'already@gmail.com',
      name: 'Already Linked',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/link/google')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('ALREADY_LINKED');
  });

  it('should reject if Google account linked to another user', async () => {
    const { accessToken } = await createAuthenticatedUser('user1@example.com');

    // Create another user with Google linked
    const otherUser = await testDb
      .insertInto('users')
      .values({
        email: 'other@example.com',
        display_name: 'Other User',
        password_hash: null,
      })
      .returning(['id'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: otherUser.id,
        provider: 'google',
        provider_user_id: 'google-other-user',
        provider_email: 'other@gmail.com',
      })
      .execute();

    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-other-user', // Same Google ID as other user
      email: 'other@gmail.com',
      name: 'Other User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/link/google')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('GOOGLE_ACCOUNT_IN_USE');
  });

  it('should reject request without access token', async () => {
    const response = await request(app)
      .post('/api/auth/link/google')
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(401);
  });

  it('should reject request with invalid access token', async () => {
    const response = await request(app)
      .post('/api/auth/link/google')
      .set('Authorization', 'Bearer invalid-token')
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(401);
  });

  it('should reject missing id_token', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/auth/link/google')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(response.status).toBe(400);
  });

  it('should reject invalid Google token', async () => {
    const { accessToken } = await createAuthenticatedUser();

    mockedGoogleAuth.verifyGoogleIdToken.mockRejectedValue(
      new Error('Invalid token')
    );

    const response = await request(app)
      .post('/api/auth/link/google')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        id_token: 'invalid-google-token'
      });

    expect(response.status).toBe(500);
  });
});
