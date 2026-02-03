import { OAuth2Client } from 'google-auth-library';
import { ApplicationError } from '../../../src/middleware/errorHandler';
import { verifyGoogleIdToken } from '../../../src/services/googleAuth';

// Mock google-auth-library's OAuth2Client
jest.mock('google-auth-library');

describe('googleAuth service - verifyGoogleIdToken', () => {
  const originalEnv = process.env;
  let mockVerifyIdToken: jest.Mock;
  let mockOAuth2Client: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env = { ...originalEnv };

    mockVerifyIdToken = jest.fn();
    mockOAuth2Client = jest.fn().mockImplementation(() => ({
      verifyIdToken: mockVerifyIdToken,
    }));

    (OAuth2Client as jest.MockedClass<typeof OAuth2Client>).mockImplementation(
      mockOAuth2Client,
    );
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  describe('Configuration errors', () => {
    it('throws GOOGLE_CONFIG_ERROR when no client IDs are configured', async () => {
      delete process.env.GOOGLE_CLIENT_ID;
      delete process.env.GOOGLE_ANDROID_CLIENT_ID;

      await expect(verifyGoogleIdToken('some-token')).rejects.toThrow(
        ApplicationError,
      );
      await expect(verifyGoogleIdToken('some-token')).rejects.toMatchObject({
        statusCode: 500,
        code: 'GOOGLE_CONFIG_ERROR',
      });
    });
  });

  describe('Verification loop (audience mismatch)', () => {
    beforeEach(() => {
      process.env.GOOGLE_CLIENT_ID =
        'web-client-id.apps.googleusercontent.com';
      process.env.GOOGLE_ANDROID_CLIENT_ID =
        'android-client-id.apps.googleusercontent.com';
    });

    it('throws GOOGLE_TOKEN_AUDIENCE_MISMATCH when all client IDs fail verification', async () => {
      // Every verifyIdToken call rejects – loop exhausts all clientIds
      mockVerifyIdToken.mockRejectedValue(new Error('Invalid audience'));

      await expect(
        verifyGoogleIdToken('token-for-wrong-audience'),
      ).rejects.toThrow(ApplicationError);
      await expect(
        verifyGoogleIdToken('token-for-wrong-audience'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_AUDIENCE_MISMATCH',
      });

      // Should have tried at least both configured client IDs
      expect(mockVerifyIdToken.mock.calls.length).toBeGreaterThanOrEqual(2);
    });
  });

  describe('Payload validation errors', () => {
    beforeEach(() => {
      process.env.GOOGLE_CLIENT_ID =
        'web-client-id.apps.googleusercontent.com';
      process.env.GOOGLE_ANDROID_CLIENT_ID =
        'android-client-id.apps.googleusercontent.com';
    });

    function setupTicket(payload: any) {
      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);
      return mockTicket;
    }

    it('throws INVALID_GOOGLE_TOKEN when payload is null', async () => {
      setupTicket(null);

      await expect(
        verifyGoogleIdToken('token-with-null-payload'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'INVALID_GOOGLE_TOKEN',
      });
    });

    it('throws GOOGLE_TOKEN_ISSUER_MISMATCH when issuer is not Google', async () => {
      setupTicket({
        iss: 'https://evil.com',
        sub: 'user-123',
        email: 'user@example.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      });

      await expect(
        verifyGoogleIdToken('token-with-wrong-issuer'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_ISSUER_MISMATCH',
      });
    });

    it('throws GOOGLE_TOKEN_ISSUER_MISMATCH when issuer is missing', async () => {
      setupTicket({
        sub: 'user-123',
        email: 'user@example.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      });

      await expect(
        verifyGoogleIdToken('token-without-issuer'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_ISSUER_MISMATCH',
      });
    });

    it('throws GOOGLE_TOKEN_EXPIRED when token has expired', async () => {
      const expiredTime = Math.floor(Date.now() / 1000) - 3600;
      setupTicket({
        iss: 'https://accounts.google.com',
        sub: 'user-123',
        email: 'user@example.com',
        name: 'Test User',
        exp: expiredTime,
      });

      await expect(verifyGoogleIdToken('expired-token')).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_EXPIRED',
      });
    });

    it('throws GOOGLE_TOKEN_INVALID when sub is missing', async () => {
      setupTicket({
        iss: 'https://accounts.google.com',
        email: 'user@example.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      });

      await expect(
        verifyGoogleIdToken('token-without-sub'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_INVALID',
      });
    });

    it('throws GOOGLE_TOKEN_INVALID when email is missing', async () => {
      setupTicket({
        iss: 'https://accounts.google.com',
        sub: 'user-123',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      });

      await expect(
        verifyGoogleIdToken('token-without-email'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_INVALID',
      });
    });

    it('throws GOOGLE_TOKEN_INVALID when name is missing', async () => {
      setupTicket({
        iss: 'https://accounts.google.com',
        sub: 'user-123',
        email: 'user@example.com',
        exp: Math.floor(Date.now() / 1000) + 3600,
      });

      await expect(
        verifyGoogleIdToken('token-without-name'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_INVALID',
      });
    });
  });

  describe('Catch block error mapping', () => {
    beforeEach(() => {
      process.env.GOOGLE_CLIENT_ID =
        'web-client-id.apps.googleusercontent.com';
      delete process.env.GOOGLE_ANDROID_CLIENT_ID;
    });

    function setupTicketWithThrowingPayload(errorMessage: string) {
      const mockTicket = {
        getPayload: jest.fn().mockImplementation(() => {
          throw new Error(errorMessage);
        }),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);
      return mockTicket;
    }

    it('maps audience errors in payload to GOOGLE_TOKEN_AUDIENCE_MISMATCH', async () => {
      setupTicketWithThrowingPayload('audience mismatch');

      await expect(
        verifyGoogleIdToken('token-audience-error'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_AUDIENCE_MISMATCH',
      });
    });

    it('maps expired errors in payload to GOOGLE_TOKEN_EXPIRED', async () => {
      setupTicketWithThrowingPayload('token expired');

      await expect(
        verifyGoogleIdToken('token-expired-error'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'GOOGLE_TOKEN_EXPIRED',
      });
    });

    it('maps signature errors in payload to INVALID_GOOGLE_TOKEN', async () => {
      setupTicketWithThrowingPayload('invalid signature');

      await expect(
        verifyGoogleIdToken('token-signature-error'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'INVALID_GOOGLE_TOKEN',
      });
    });

    it('maps other errors in payload to INVALID_GOOGLE_TOKEN', async () => {
      setupTicketWithThrowingPayload('some other error');

      await expect(
        verifyGoogleIdToken('token-generic-error'),
      ).rejects.toMatchObject({
        statusCode: 401,
        code: 'INVALID_GOOGLE_TOKEN',
      });
    });
  });

  describe('Success scenarios', () => {
    beforeEach(() => {
      process.env.GOOGLE_CLIENT_ID =
        'web-client-id.apps.googleusercontent.com';
      process.env.GOOGLE_ANDROID_CLIENT_ID =
        'android-client-id.apps.googleusercontent.com';
    });

    it('successfully verifies token with Web Client ID', async () => {
      const payload = {
        iss: 'https://accounts.google.com',
        sub: 'google-user-123',
        email: 'user@gmail.com',
        name: 'Test User',
        picture: 'https://example.com/photo.jpg',
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);

      const result = await verifyGoogleIdToken('valid-web-token');

      expect(result).toEqual({
        sub: 'google-user-123',
        email: 'user@gmail.com',
        name: 'Test User',
        picture: 'https://example.com/photo.jpg',
      });
      expect(mockVerifyIdToken).toHaveBeenCalledWith({
        idToken: 'valid-web-token',
        audience: 'web-client-id.apps.googleusercontent.com',
      });
    });

    it('successfully verifies token with Android Client ID when Web fails', async () => {
      const payload = {
        iss: 'https://accounts.google.com',
        sub: 'google-user-456',
        email: 'android@gmail.com',
        name: 'Android User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };

      // First call (Web) fails, second call (Android) succeeds
      mockVerifyIdToken
        .mockRejectedValueOnce(new Error('Wrong audience'))
        .mockResolvedValueOnce(mockTicket);

      const result = await verifyGoogleIdToken('valid-android-token');

      expect(result).toEqual({
        sub: 'google-user-456',
        email: 'android@gmail.com',
        name: 'Android User',
        picture: undefined,
      });
      expect(mockVerifyIdToken).toHaveBeenCalledTimes(2);
      expect(mockVerifyIdToken).toHaveBeenNthCalledWith(1, {
        idToken: 'valid-android-token',
        audience: 'web-client-id.apps.googleusercontent.com',
      });
      expect(mockVerifyIdToken).toHaveBeenNthCalledWith(2, {
        idToken: 'valid-android-token',
        audience: 'android-client-id.apps.googleusercontent.com',
      });
    });

    it('successfully verifies token without picture field', async () => {
      const payload = {
        iss: 'https://accounts.google.com',
        sub: 'google-user-789',
        email: 'nopicture@gmail.com',
        name: 'No Picture User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);

      const result = await verifyGoogleIdToken('valid-token-no-picture');

      expect(result).toEqual({
        sub: 'google-user-789',
        email: 'nopicture@gmail.com',
        name: 'No Picture User',
        picture: undefined,
      });
    });

    it('accepts token with accounts.google.com issuer (without https)', async () => {
      const payload = {
        iss: 'accounts.google.com',
        sub: 'google-user-999',
        email: 'user@gmail.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);

      const result = await verifyGoogleIdToken('valid-token-alt-issuer');

      expect(result).toEqual({
        sub: 'google-user-999',
        email: 'user@gmail.com',
        name: 'Test User',
        picture: undefined,
      });
    });

    it('handles token without exp field', async () => {
      const payload = {
        iss: 'https://accounts.google.com',
        sub: 'google-user-exp',
        email: 'user@gmail.com',
        name: 'Test User',
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);

      const result = await verifyGoogleIdToken('valid-token-no-exp');

      expect(result).toEqual({
        sub: 'google-user-exp',
        email: 'user@gmail.com',
        name: 'Test User',
        picture: undefined,
      });
    });

    it('works with only Web Client ID configured', async () => {
      delete process.env.GOOGLE_ANDROID_CLIENT_ID;
      process.env.GOOGLE_CLIENT_ID = 'web-only.apps.googleusercontent.com';

      const payload = {
        iss: 'https://accounts.google.com',
        sub: 'user-123',
        email: 'user@gmail.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);

      const result = await verifyGoogleIdToken('valid-token');

      expect(result).toBeDefined();
      expect(mockVerifyIdToken).toHaveBeenCalledTimes(1);
      expect(mockVerifyIdToken).toHaveBeenCalledWith({
        idToken: 'valid-token',
        audience: 'web-only.apps.googleusercontent.com',
      });
    });

    it('works with only Android Client ID configured', async () => {
      delete process.env.GOOGLE_CLIENT_ID;
      process.env.GOOGLE_ANDROID_CLIENT_ID =
        'android-only.apps.googleusercontent.com';

      const payload = {
        iss: 'https://accounts.google.com',
        sub: 'user-456',
        email: 'user@gmail.com',
        name: 'Test User',
        exp: Math.floor(Date.now() / 1000) + 3600,
      };

      const mockTicket = {
        getPayload: jest.fn().mockReturnValue(payload),
      };
      mockVerifyIdToken.mockResolvedValue(mockTicket);

      const result = await verifyGoogleIdToken('valid-token');

      expect(result).toBeDefined();
      expect(mockVerifyIdToken).toHaveBeenCalledTimes(1);
      expect(mockVerifyIdToken).toHaveBeenCalledWith({
        idToken: 'valid-token',
        audience: 'android-only.apps.googleusercontent.com',
      });
    });
  });
});

