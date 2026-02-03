import { OAuth2Client } from 'google-auth-library';
import { ApplicationError } from '../middleware/errorHandler.js';

/**
 * Get allowed Google Client IDs for token verification
 * Supports both Android and Web Application Client IDs
 */
function getAllowedClientIds(): string[] {
  const clientIds: string[] = [];
  
  // Web Application Client ID (for web clients)
  if (process.env.GOOGLE_CLIENT_ID) {
    clientIds.push(process.env.GOOGLE_CLIENT_ID);
  }
  
  // Android Client ID (for Android app)
  if (process.env.GOOGLE_ANDROID_CLIENT_ID) {
    clientIds.push(process.env.GOOGLE_ANDROID_CLIENT_ID);
  }
  
  return clientIds;
}

// Initialize OAuth2Client - will use first client ID for initialization
// Actual verification will check against all allowed client IDs
const allowedClientIds = getAllowedClientIds();
const client = new OAuth2Client(allowedClientIds[0] || process.env.GOOGLE_CLIENT_ID);

export interface GoogleUserInfo {
  sub: string; // Google user ID
  email: string;
  name: string;
  picture?: string;
}

/**
 * Verify Google ID token and extract user information
 * 
 * Supports multiple client IDs:
 * - GOOGLE_CLIENT_ID: Web Application Client ID (for web clients)
 * - GOOGLE_ANDROID_CLIENT_ID: Android Client ID (for Android app)
 * 
 * Validates:
 * - Token signature (cryptographic verification)
 * - Audience (aud) - must match one of the configured client IDs
 * - Issuer (iss) - must be Google accounts
 * - Expiration (exp) - token must not be expired
 * - Required claims (sub, email, name)
 * 
 * @param idToken - Google ID token (JWT) from client
 * @returns GoogleUserInfo with user details
 * @throws ApplicationError with specific error codes for different failure types
 */
export async function verifyGoogleIdToken(idToken: string): Promise<GoogleUserInfo> {
  const allowedClientIds = getAllowedClientIds();
  
  if (allowedClientIds.length === 0) {
    throw new ApplicationError(
      'Google Client ID not configured. Set GOOGLE_CLIENT_ID or GOOGLE_ANDROID_CLIENT_ID',
      500,
      'GOOGLE_CONFIG_ERROR'
    );
  }

  // Try verifying with each allowed client ID
  // This supports tokens from both Android and Web clients
  let ticket;
  
  for (const clientId of allowedClientIds) {
    try {
      const testClient = new OAuth2Client(clientId);
      ticket = await testClient.verifyIdToken({
        idToken,
        audience: clientId,
      });
      break; // Success, exit loop
    } catch (error) {
      // Continue to next client ID if this one fails
      // We'll throw an error if all fail
    }
  }

  if (!ticket) {
    // All client IDs failed - throw audience mismatch error
    throw new ApplicationError(
      `Google ID token audience mismatch. Token must be issued for one of: ${allowedClientIds.join(', ')}`,
      401,
      'GOOGLE_TOKEN_AUDIENCE_MISMATCH'
    );
  }

  try {

    const payload = ticket.getPayload();

    if (!payload) {
      throw new ApplicationError(
        'Invalid Google ID token: no payload',
        401,
        'INVALID_GOOGLE_TOKEN'
      );
    }

    // Validate issuer - must be Google accounts
    const validIssuers = [
      'https://accounts.google.com',
      'accounts.google.com',
    ];
    if (!payload.iss || !validIssuers.includes(payload.iss)) {
      throw new ApplicationError(
        'Invalid Google ID token: wrong issuer',
        401,
        'GOOGLE_TOKEN_ISSUER_MISMATCH'
      );
    }

    // Validate expiration
    if (payload.exp && payload.exp * 1000 < Date.now()) {
      throw new ApplicationError(
        'Google ID token has expired',
        401,
        'GOOGLE_TOKEN_EXPIRED'
      );
    }

    // Validate required claims
    if (!payload.sub) {
      throw new ApplicationError(
        'Invalid Google ID token: missing subject (sub)',
        401,
        'GOOGLE_TOKEN_INVALID'
      );
    }

    if (!payload.email) {
      throw new ApplicationError(
        'Invalid Google ID token: missing email',
        401,
        'GOOGLE_TOKEN_INVALID'
      );
    }

    if (!payload.name) {
      throw new ApplicationError(
        'Invalid Google ID token: missing name',
        401,
        'GOOGLE_TOKEN_INVALID'
      );
    }

    return {
      sub: payload.sub,
      email: payload.email,
      name: payload.name,
      picture: payload.picture, // Optional - may be undefined
    };
  } catch (error) {
    // If it's already an ApplicationError, re-throw it
    if (error instanceof ApplicationError) {
      throw error;
    }

    // Handle google-auth-library specific errors
    const errorMessage = error instanceof Error ? error.message : String(error);

    // Check for common error patterns
    if (errorMessage.includes('audience') || errorMessage.includes('aud')) {
      throw new ApplicationError(
        'Google ID token audience mismatch',
        401,
        'GOOGLE_TOKEN_AUDIENCE_MISMATCH'
      );
    }

    if (errorMessage.includes('expired') || errorMessage.includes('exp')) {
      throw new ApplicationError(
        'Google ID token has expired',
        401,
        'GOOGLE_TOKEN_EXPIRED'
      );
    }

    if (errorMessage.includes('signature') || errorMessage.includes('verify')) {
      throw new ApplicationError(
        'Invalid Google ID token signature',
        401,
        'INVALID_GOOGLE_TOKEN'
      );
    }

    // Generic error for other cases
    throw new ApplicationError(
      `Google ID token verification failed: ${errorMessage}`,
      401,
      'INVALID_GOOGLE_TOKEN'
    );
  }
}
