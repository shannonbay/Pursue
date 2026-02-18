import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import * as googleAuth from '../../../src/services/googleAuth';

// Mock the Google auth service
jest.mock('../../../src/services/googleAuth');
const mockedGoogleAuth = googleAuth as jest.Mocked<typeof googleAuth>;

describe('POST /api/auth/google', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('should create new user for first-time Google sign-in', async () => {
    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-user-123',
      email: 'newuser@gmail.com',
      name: 'New Google User',
      picture: 'https://lh3.googleusercontent.com/photo.jpg'
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token',
        consent_agreed: true
      });

    expect(response.status).toBe(201);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.is_new_user).toBe(true);
    expect(response.body.user).toMatchObject({
      email: 'newuser@gmail.com',
      display_name: 'New Google User',
      has_avatar: false, // Avatar download fails in tests since mock URL is not real
    });
  });

  it('should sign in existing Google user', async () => {
    // Create existing Google user
    const existingUser = await testDb
      .insertInto('users')
      .values({
        email: 'existing@gmail.com',
        display_name: 'Existing User',
        password_hash: null,
      })
      .returning(['id'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: existingUser.id,
        provider: 'google',
        provider_user_id: 'google-user-456',
        provider_email: 'existing@gmail.com',
      })
      .execute();

    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-user-456',
      email: 'existing@gmail.com',
      name: 'Existing User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(200);
    expect(response.body.is_new_user).toBe(false);
    expect(response.body.user.id).toBe(existingUser.id);
  });

  it('should link Google to existing email user', async () => {
    // Create existing email user
    const existingUser = await testDb
      .insertInto('users')
      .values({
        email: 'user@example.com',
        display_name: 'Email User',
        password_hash: 'some-hash',
      })
      .returning(['id'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: existingUser.id,
        provider: 'email',
        provider_user_id: 'user@example.com',
        provider_email: 'user@example.com',
      })
      .execute();

    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-user-789',
      email: 'user@example.com', // Same email as existing user
      name: 'Google Name',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(200);
    expect(response.body.user.id).toBe(existingUser.id);

    // Verify Google provider was added
    const googleProvider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', existingUser.id)
      .where('provider', '=', 'google')
      .executeTakeFirst();

    expect(googleProvider).toBeDefined();
    expect(googleProvider?.provider_user_id).toBe('google-user-789');
  });

  it('should create auth provider for new Google user', async () => {
    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-new-user',
      email: 'brand.new@gmail.com',
      name: 'Brand New User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token',
        consent_agreed: true
      });

    const provider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .where('provider', '=', 'google')
      .executeTakeFirst();

    expect(provider).toBeDefined();
    expect(provider?.provider_user_id).toBe('google-new-user');
    expect(provider?.provider_email).toBe('brand.new@gmail.com');
  });

  it('should reject invalid Google ID token', async () => {
    const { ApplicationError } = await import('../../../src/middleware/errorHandler');
    mockedGoogleAuth.verifyGoogleIdToken.mockRejectedValue(
      new ApplicationError('Invalid Google ID token', 401, 'INVALID_GOOGLE_TOKEN')
    );

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'invalid-token'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_GOOGLE_TOKEN');
  });

  it('should reject Google ID token with wrong audience', async () => {
    const { ApplicationError } = await import('../../../src/middleware/errorHandler');
    mockedGoogleAuth.verifyGoogleIdToken.mockRejectedValue(
      new ApplicationError('Google ID token audience mismatch', 401, 'GOOGLE_TOKEN_AUDIENCE_MISMATCH')
    );

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'token-with-wrong-audience'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('GOOGLE_TOKEN_AUDIENCE_MISMATCH');
  });

  it('should reject expired Google ID token', async () => {
    const { ApplicationError } = await import('../../../src/middleware/errorHandler');
    mockedGoogleAuth.verifyGoogleIdToken.mockRejectedValue(
      new ApplicationError('Google ID token has expired', 401, 'GOOGLE_TOKEN_EXPIRED')
    );

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'expired-token'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('GOOGLE_TOKEN_EXPIRED');
  });

  it('should reject missing id_token', async () => {
    const response = await request(app)
      .post('/api/auth/google')
      .send({});

    expect(response.status).toBe(400);
  });

  it('should reject empty id_token', async () => {
    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: ''
      });

    expect(response.status).toBe(400);
  });

  it('should store refresh token for Google user', async () => {
    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-refresh-test',
      email: 'refresh@gmail.com',
      name: 'Refresh Test User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token',
        consent_agreed: true
      });

    const refreshToken = await testDb
      .selectFrom('refresh_tokens')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .executeTakeFirst();

    expect(refreshToken).toBeDefined();
    expect(refreshToken?.revoked_at).toBeNull();
  });

  it('should normalize email to lowercase', async () => {
    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-uppercase',
      email: 'UPPERCASE@GMAIL.COM',
      name: 'Uppercase User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token',
        consent_agreed: true
      });

    expect(response.body.user.email).toBe('uppercase@gmail.com');
  });

  it('should return 422 CONSENT_REQUIRED for new Google user without consent', async () => {
    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-no-consent',
      email: 'noconsent@gmail.com',
      name: 'No Consent User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(422);
    expect(response.body.error.code).toBe('CONSENT_REQUIRED');
  });

  it('should create two versioned consent entries for new Google user from server config', async () => {
    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-consent-test',
      email: 'consent@gmail.com',
      name: 'Consent Test User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token',
        consent_agreed: true
      });

    expect(response.status).toBe(201);

    const consents = await testDb
      .selectFrom('user_consents')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .execute();

    expect(consents).toHaveLength(2);
    const types = consents.map(c => c.consent_type).sort();
    expect(types).toEqual(['privacy policy Feb 17, 2026', 'terms Feb 11, 2026']);
  });

  it('should not require consent for returning Google user', async () => {
    // Create existing Google user
    const existingUser = await testDb
      .insertInto('users')
      .values({
        email: 'returning@gmail.com',
        display_name: 'Returning User',
        password_hash: null,
      })
      .returning(['id'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: existingUser.id,
        provider: 'google',
        provider_user_id: 'google-returning',
        provider_email: 'returning@gmail.com',
      })
      .execute();

    mockedGoogleAuth.verifyGoogleIdToken.mockResolvedValue({
      sub: 'google-returning',
      email: 'returning@gmail.com',
      name: 'Returning User',
      picture: undefined
    });

    const response = await request(app)
      .post('/api/auth/google')
      .send({
        id_token: 'valid-google-id-token'
      });

    expect(response.status).toBe(200);
    expect(response.body.is_new_user).toBe(false);
  });
});
