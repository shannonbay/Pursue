import type { Response, NextFunction } from 'express';
import sharp from 'sharp';
import { sql } from 'kysely';
import { db } from '../database/index.js';
import { hashPassword, verifyPassword } from '../utils/password.js';
import { logger } from '../utils/logger.js';
import {
  generateAccessToken,
  generateRefreshToken,
  verifyRefreshToken,
  hashToken,
  generateRandomToken,
  getRefreshTokenExpiryDate,
  getPasswordResetExpiryDate,
} from '../utils/jwt.js';
import { verifyGoogleIdToken } from '../services/googleAuth.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { getPolicyVersions } from '../utils/policyConfig.js';
import {
  RegisterSchema,
  LoginSchema,
  GoogleAuthSchema,
  RefreshTokenSchema,
  ForgotPasswordSchema,
  ResetPasswordSchema,
  LinkGoogleSchema,
} from '../validations/auth.js';

/**
 * Download and process Google avatar image.
 * Returns processed WebP buffer or null if download fails.
 */
async function downloadAndProcessGoogleAvatar(pictureUrl: string): Promise<Buffer | null> {
  try {
    const imageResponse = await fetch(pictureUrl);
    if (!imageResponse.ok) {
      logger.error('Failed to download Google avatar', {
        statusText: imageResponse.statusText,
        status: imageResponse.status,
      });
      return null;
    }
    const imageBuffer = await imageResponse.arrayBuffer();

    const processedImage = await sharp(Buffer.from(imageBuffer))
      .resize(256, 256, { fit: 'cover', position: 'center' })
      .webp({ quality: 90 })
      .toBuffer();

    return processedImage;
  } catch (error) {
    // Log but don't fail auth if avatar download fails
    console.error('Failed to download Google avatar:', error);
    return null;
  }
}

// POST /api/auth/register
export async function register(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = RegisterSchema.parse(req.body);

    // Check if email already registered
    const existingUser = await db
      .selectFrom('users')
      .select('id')
      .where('email', '=', data.email.toLowerCase())
      .executeTakeFirst();

    if (existingUser) {
      throw new ApplicationError('Email already registered', 409, 'EMAIL_EXISTS');
    }

    // Hash password
    const passwordHash = await hashPassword(data.password);

    // Create user, auth provider, consent, and refresh token atomically
    const result = await db.transaction().execute(async (trx) => {
      const user = await trx
        .insertInto('users')
        .values({
          email: data.email.toLowerCase(),
          display_name: data.display_name,
          password_hash: passwordHash,
        })
        .returning(['id', 'email', 'display_name', 'created_at'])
        .executeTakeFirstOrThrow();

      await trx
        .insertInto('auth_providers')
        .values({
          user_id: user.id,
          provider: 'email',
          provider_user_id: user.email,
          provider_email: user.email,
        })
        .execute();

      const { termsVersion, privacyVersion } = getPolicyVersions();
      await trx.insertInto('user_consents').values([
        { user_id: user.id, consent_type: `terms ${termsVersion}`, ip_address: req.ip || null },
        { user_id: user.id, consent_type: `privacy policy ${privacyVersion}`, ip_address: req.ip || null },
      ]).execute();

      const accessToken = generateAccessToken(user.id, user.email);
      const refreshTokenId = crypto.randomUUID();
      const refreshToken = generateRefreshToken(user.id, refreshTokenId);

      await trx
        .insertInto('refresh_tokens')
        .values({
          id: refreshTokenId,
          user_id: user.id,
          token_hash: hashToken(refreshToken),
          expires_at: getRefreshTokenExpiryDate(),
        })
        .execute();

      return { user, accessToken, refreshToken };
    });

    res.status(201).json({
      access_token: result.accessToken,
      refresh_token: result.refreshToken,
      user: {
        id: result.user.id,
        email: result.user.email,
        display_name: result.user.display_name,
        has_avatar: false, // New user, no avatar yet
        created_at: result.user.created_at,
      },
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/auth/login
export async function login(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = LoginSchema.parse(req.body);

    // Find user by email (excluding soft-deleted users)
    const user = await db
      .selectFrom('users')
      .select([
        'id',
        'email',
        'display_name',
        'password_hash',
        sql<boolean>`avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .where('email', '=', data.email.toLowerCase())
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!user || !user.password_hash) {
      throw new ApplicationError('Invalid email or password', 401, 'INVALID_CREDENTIALS');
    }

    // Verify password
    const validPassword = await verifyPassword(data.password, user.password_hash);
    if (!validPassword) {
      throw new ApplicationError('Invalid email or password', 401, 'INVALID_CREDENTIALS');
    }

    // Generate tokens
    const accessToken = generateAccessToken(user.id, user.email);
    const refreshTokenId = crypto.randomUUID();
    const refreshToken = generateRefreshToken(user.id, refreshTokenId);

    // Store refresh token hash
    await db
      .insertInto('refresh_tokens')
      .values({
        id: refreshTokenId,
        user_id: user.id,
        token_hash: hashToken(refreshToken),
        expires_at: getRefreshTokenExpiryDate(),
      })
      .execute();

    res.status(200).json({
      access_token: accessToken,
      refresh_token: refreshToken,
      user: {
        id: user.id,
        email: user.email,
        display_name: user.display_name,
        has_avatar: Boolean(user.has_avatar),
      },
    });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/auth/google
 * 
 * Sign in or register with Google OAuth.
 * 
 * Flow:
 * 1. Verify Google ID token (signature, audience, issuer, expiration)
 * 2. Check if Google account already linked to a user
 *    - If yes: Sign in existing user
 * 3. If not linked, check if user exists by email
 *    - If yes: Link Google account to existing user
 *    - If no: Create new user account
 * 4. Generate access and refresh tokens
 * 5. Return tokens, user data, and is_new_user flag
 * 
 * Request: { id_token: string }
 * Response: { access_token, refresh_token, is_new_user, user }
 * 
 * Errors:
 * - 400: Invalid request format (validation error)
 * - 401: Invalid/expired Google token or verification failure
 * - 500: Server error (database, token generation, etc.)
 */
export async function googleAuth(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    // Validate request body
    const data = GoogleAuthSchema.parse(req.body);

    // Verify Google ID token and extract user info
    // This will throw ApplicationError with specific error codes if verification fails
    const googleUser = await verifyGoogleIdToken(data.id_token);

    // Check if auth_provider exists for this Google account (by provider_user_id)
    // Exclude soft-deleted users to avoid returning tokens for deleted accounts
    const existingProvider = await db
      .selectFrom('auth_providers')
      .innerJoin('users', 'users.id', 'auth_providers.user_id')
      .select([
        'users.id',
        'users.email',
        'users.display_name',
        sql<boolean>`users.avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .where('auth_providers.provider', '=', 'google')
      .where('auth_providers.provider_user_id', '=', googleUser.sub)
      .where('users.deleted_at', 'is', null)
      .executeTakeFirst();

    let user: { id: string; email: string; display_name: string; has_avatar: boolean };
    let isNewUser = false;
    let accessToken: string;
    let refreshToken: string;

    if (existingProvider) {
      // Case 1: Google account already linked - sign in existing user
      user = {
        id: existingProvider.id,
        email: existingProvider.email,
        display_name: existingProvider.display_name,
        has_avatar: Boolean(existingProvider.has_avatar),
      };
    } else {
      // Case 2: Google account not linked - check if user exists by email
      // Exclude soft-deleted users
      const existingUserByEmail = await db
        .selectFrom('users')
        .select([
          'id',
          'email',
          'display_name',
          sql<boolean>`avatar_data IS NOT NULL`.as('has_avatar'),
        ])
        .where('email', '=', googleUser.email.toLowerCase())
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (existingUserByEmail) {
        // Case 2a: User exists with same email - link Google account
        await db
          .insertInto('auth_providers')
          .values({
            user_id: existingUserByEmail.id,
            provider: 'google',
            provider_user_id: googleUser.sub,
            provider_email: googleUser.email,
          })
          .execute();
        user = {
          id: existingUserByEmail.id,
          email: existingUserByEmail.email,
          display_name: existingUserByEmail.display_name,
          has_avatar: Boolean(existingUserByEmail.has_avatar),
        };
        
        // Download and update Google avatar if user doesn't have one
        if (!user.has_avatar && googleUser.picture) {
          const avatarData = await downloadAndProcessGoogleAvatar(googleUser.picture);
          if (avatarData) {
            await db
              .updateTable('users')
              .set({
                avatar_data: avatarData,
                avatar_mime_type: 'image/webp',
              })
              .where('id', '=', user.id)
              .execute();
            user.has_avatar = true;
          }
        }
      } else {
        // Case 2b: New user - create account and link Google
        // Require consent for new users
        if (!data.consent_agreed) {
          throw new ApplicationError(
            'You must agree to the Terms of Service and Privacy Policy',
            422,
            'CONSENT_REQUIRED'
          );
        }

        // Download and process Google avatar if available
        let avatarData: Buffer | null = null;
        if (googleUser.picture) {
          avatarData = await downloadAndProcessGoogleAvatar(googleUser.picture);
        }

        // Create user, auth provider, consent, and refresh token atomically
        const txResult = await db.transaction().execute(async (trx) => {
          const newUser = await trx
            .insertInto('users')
            .values({
              email: googleUser.email.toLowerCase(),
              display_name: googleUser.name,
              avatar_data: avatarData,
              avatar_mime_type: avatarData ? 'image/webp' : null,
              password_hash: null, // Google-authenticated users don't have passwords
            })
            .returning(['id', 'email', 'display_name'])
            .executeTakeFirstOrThrow();

          await trx
            .insertInto('auth_providers')
            .values({
              user_id: newUser.id,
              provider: 'google',
              provider_user_id: googleUser.sub,
              provider_email: googleUser.email,
            })
            .execute();

          const { termsVersion, privacyVersion } = getPolicyVersions();
          await trx.insertInto('user_consents').values([
            { user_id: newUser.id, consent_type: `terms ${termsVersion}`, ip_address: req.ip || null },
            { user_id: newUser.id, consent_type: `privacy policy ${privacyVersion}`, ip_address: req.ip || null },
          ]).execute();

          const accessToken = generateAccessToken(newUser.id, newUser.email);
          const refreshTokenId = crypto.randomUUID();
          const refreshToken = generateRefreshToken(newUser.id, refreshTokenId);

          await trx
            .insertInto('refresh_tokens')
            .values({
              id: refreshTokenId,
              user_id: newUser.id,
              token_hash: hashToken(refreshToken),
              expires_at: getRefreshTokenExpiryDate(),
            })
            .execute();

          return { newUser, accessToken, refreshToken };
        });

        user = {
          id: txResult.newUser.id,
          email: txResult.newUser.email,
          display_name: txResult.newUser.display_name,
          has_avatar: avatarData !== null,
        };
        isNewUser = true;
        accessToken = txResult.accessToken;
        refreshToken = txResult.refreshToken;
      }
    }

    if (!isNewUser) {
      // Generate JWT tokens for existing users (Cases 1 and 2a)
      accessToken = generateAccessToken(user.id, user.email);
      const refreshTokenId = crypto.randomUUID();
      refreshToken = generateRefreshToken(user.id, refreshTokenId);

      await db
        .insertInto('refresh_tokens')
        .values({
          id: refreshTokenId,
          user_id: user.id,
          token_hash: hashToken(refreshToken),
          expires_at: getRefreshTokenExpiryDate(),
        })
        .execute();
    }

    // Return success response
    // Use 201 Created for new users, 200 OK for existing users
    res.status(isNewUser ? 201 : 200).json({
      access_token: accessToken!,
      refresh_token: refreshToken!,
      is_new_user: isNewUser,
      user: {
        id: user.id,
        email: user.email,
        display_name: user.display_name,
        has_avatar: user.has_avatar,
      },
    });
  } catch (error) {
    // Pass error to error handler middleware
    // ApplicationError instances will be properly formatted
    next(error);
  }
}

// POST /api/auth/refresh
export async function refresh(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = RefreshTokenSchema.parse(req.body);

    // Verify refresh token
    let payload;
    try {
      payload = verifyRefreshToken(data.refresh_token);
    } catch {
      throw new ApplicationError('Invalid or expired refresh token', 401, 'INVALID_REFRESH_TOKEN');
    }

    // Check token in database
    const storedToken = await db
      .selectFrom('refresh_tokens')
      .innerJoin('users', 'users.id', 'refresh_tokens.user_id')
      .select([
        'refresh_tokens.id',
        'refresh_tokens.revoked_at',
        'refresh_tokens.expires_at',
        'users.id as user_id',
        'users.email',
      ])
      .where('refresh_tokens.id', '=', payload.token_id)
      .where('refresh_tokens.user_id', '=', payload.user_id)
      .executeTakeFirst();

    if (!storedToken) {
      throw new ApplicationError('Invalid refresh token', 401, 'INVALID_REFRESH_TOKEN');
    }

    if (storedToken.revoked_at) {
      throw new ApplicationError('Refresh token has been revoked', 401, 'TOKEN_REVOKED');
    }

    if (new Date(storedToken.expires_at) < new Date()) {
      throw new ApplicationError('Refresh token has expired', 401, 'TOKEN_EXPIRED');
    }

    // Generate new access token and refresh token (single-use rotation)
    const accessToken = generateAccessToken(storedToken.user_id, storedToken.email);
    const newRefreshTokenId = crypto.randomUUID();
    const newRefreshToken = generateRefreshToken(storedToken.user_id, newRefreshTokenId);

    // Revoke old token and insert new token in a transaction-like manner
    // First, revoke the old token
    await db
      .updateTable('refresh_tokens')
      .set({ revoked_at: new Date() })
      .where('id', '=', payload.token_id)
      .execute();

    // Then, store the new refresh token
    await db
      .insertInto('refresh_tokens')
      .values({
        id: newRefreshTokenId,
        user_id: storedToken.user_id,
        token_hash: hashToken(newRefreshToken),
        expires_at: getRefreshTokenExpiryDate(),
      })
      .execute();

    res.status(200).json({
      access_token: accessToken,
      refresh_token: newRefreshToken,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/auth/logout
export async function logout(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = RefreshTokenSchema.parse(req.body);

    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Verify and revoke refresh token
    let payload;
    try {
      payload = verifyRefreshToken(data.refresh_token);
    } catch {
      // Token invalid, but we still return success
      res.status(204).send();
      return;
    }

    // Revoke the token
    await db
      .updateTable('refresh_tokens')
      .set({ revoked_at: new Date() })
      .where('id', '=', payload.token_id)
      .where('user_id', '=', req.user.id)
      .execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// POST /api/auth/forgot-password
export async function forgotPassword(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = ForgotPasswordSchema.parse(req.body);

    // Always return success to prevent email enumeration
    const successResponse = {
      message: 'If an account exists with this email, a password reset link has been sent.',
    };

    // Find user by email
    const user = await db
      .selectFrom('users')
      .select(['id', 'email'])
      .where('email', '=', data.email.toLowerCase())
      .executeTakeFirst();

    if (!user) {
      res.status(200).json(successResponse);
      return;
    }

    // Generate reset token
    const plainToken = generateRandomToken();
    const tokenHash = hashToken(plainToken);

    // Store in password_reset_tokens
    await db
      .insertInto('password_reset_tokens')
      .values({
        user_id: user.id,
        token_hash: tokenHash,
        expires_at: getPasswordResetExpiryDate(),
      })
      .execute();

    // TODO: Send email with reset link
    // The reset link would be: `${process.env.FRONTEND_URL}/reset-password?token=${plainToken}`
    logger.info('Password reset token generated', {
      email: user.email,
      token: plainToken,
    });

    res.status(200).json(successResponse);
  } catch (error) {
    next(error);
  }
}

// POST /api/auth/reset-password
export async function resetPassword(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = ResetPasswordSchema.parse(req.body);

    // Hash the provided token
    const tokenHash = hashToken(data.token);

    // Find the token in database
    const resetToken = await db
      .selectFrom('password_reset_tokens')
      .innerJoin('users', 'users.id', 'password_reset_tokens.user_id')
      .select([
        'password_reset_tokens.id',
        'password_reset_tokens.expires_at',
        'password_reset_tokens.used_at',
        'users.id as user_id',
        'users.email',
      ])
      .where('password_reset_tokens.token_hash', '=', tokenHash)
      .executeTakeFirst();

    if (!resetToken) {
      throw new ApplicationError('Invalid or expired reset token', 400, 'INVALID_RESET_TOKEN');
    }

    if (resetToken.used_at) {
      throw new ApplicationError('Reset token has already been used', 400, 'TOKEN_USED');
    }

    if (new Date(resetToken.expires_at) < new Date()) {
      throw new ApplicationError('Reset token has expired', 400, 'TOKEN_EXPIRED');
    }

    // Hash new password
    const passwordHash = await hashPassword(data.new_password);

    // Update user's password
    await db
      .updateTable('users')
      .set({ password_hash: passwordHash })
      .where('id', '=', resetToken.user_id)
      .execute();

    // Mark token as used
    await db
      .updateTable('password_reset_tokens')
      .set({ used_at: new Date() })
      .where('id', '=', resetToken.id)
      .execute();

    // Ensure user has email provider
    const emailProvider = await db
      .selectFrom('auth_providers')
      .select('id')
      .where('user_id', '=', resetToken.user_id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    if (!emailProvider) {
      await db
        .insertInto('auth_providers')
        .values({
          user_id: resetToken.user_id,
          provider: 'email',
          provider_user_id: resetToken.email,
          provider_email: resetToken.email,
        })
        .execute();
    }

    // Generate new tokens
    const accessToken = generateAccessToken(resetToken.user_id, resetToken.email);
    const refreshTokenId = crypto.randomUUID();
    const refreshToken = generateRefreshToken(resetToken.user_id, refreshTokenId);

    // Store refresh token hash
    await db
      .insertInto('refresh_tokens')
      .values({
        id: refreshTokenId,
        user_id: resetToken.user_id,
        token_hash: hashToken(refreshToken),
        expires_at: getRefreshTokenExpiryDate(),
      })
      .execute();

    res.status(200).json({
      access_token: accessToken,
      refresh_token: refreshToken,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/auth/link/google
export async function linkGoogle(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = LinkGoogleSchema.parse(req.body);

    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Verify Google ID token
    const googleUser = await verifyGoogleIdToken(data.id_token);

    // Check if Google account already linked to another user
    const existingLink = await db
      .selectFrom('auth_providers')
      .select(['user_id'])
      .where('provider', '=', 'google')
      .where('provider_user_id', '=', googleUser.sub)
      .executeTakeFirst();

    if (existingLink) {
      if (existingLink.user_id === req.user.id) {
        throw new ApplicationError('Google account already linked to your account', 409, 'ALREADY_LINKED');
      }
      throw new ApplicationError('Google account already linked to another user', 409, 'GOOGLE_ACCOUNT_IN_USE');
    }

    // Link Google account
    await db
      .insertInto('auth_providers')
      .values({
        user_id: req.user.id,
        provider: 'google',
        provider_user_id: googleUser.sub,
        provider_email: googleUser.email,
      })
      .execute();

    res.status(200).json({
      success: true,
      provider: 'google',
      provider_email: googleUser.email,
    });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/auth/unlink/:provider
export async function unlinkProvider(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const { provider } = req.params;

    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    if (provider !== 'google' && provider !== 'email') {
      throw new ApplicationError('Invalid provider', 400, 'INVALID_PROVIDER');
    }

    // Count user's auth providers
    const providers = await db
      .selectFrom('auth_providers')
      .select(['id', 'provider'])
      .where('user_id', '=', req.user.id)
      .execute();

    if (providers.length <= 1) {
      throw new ApplicationError('Cannot unlink last authentication provider', 400, 'CANNOT_UNLINK_LAST_PROVIDER');
    }

    // Find the provider to unlink
    const providerToUnlink = providers.find((p) => p.provider === provider);
    if (!providerToUnlink) {
      throw new ApplicationError('Provider not linked to your account', 404, 'PROVIDER_NOT_FOUND');
    }

    // Delete the provider
    await db
      .deleteFrom('auth_providers')
      .where('id', '=', providerToUnlink.id)
      .execute();

    // If unlinking email provider, also clear password hash
    if (provider === 'email') {
      await db
        .updateTable('users')
        .set({ password_hash: null })
        .where('id', '=', req.user.id)
        .execute();
    }

    res.status(200).json({
      success: true,
    });
  } catch (error) {
    next(error);
  }
}
