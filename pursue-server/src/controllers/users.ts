import type { Response, NextFunction, Request } from 'express';
import crypto from 'crypto';
import multer, { type FileFilterCallback } from 'multer';
import { sql } from 'kysely';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { uploadUserAvatar, deleteUserAvatar } from '../services/storage.service.js';
import { hashPassword, verifyPassword } from '../utils/password.js';
import { logger } from '../utils/logger.js';
import {
  UpdateUserSchema,
  ChangePasswordSchema,
  DeleteUserSchema,
  GetGroupsQuerySchema,
  RecordConsentsSchema,
  ConsentHashLookupSchema,
} from '../validations/users.js';
import {
  getUserSubscriptionState,
  getSubscriptionEligibility,
} from '../services/subscription.service.js';
import { deleteUserPhotos } from '../services/gcs.service.js';
import { tierName } from '../services/heat.service.js';

// Temporary debug logging for avatar endpoints
const DEBUG_AVATAR = process.env.DEBUG_AVATAR === 'true';

// Configure multer for avatar uploads
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 }, // 5 MB
  fileFilter: (_req: Request, file: Express.Multer.File, cb: FileFilterCallback) => {
    if (DEBUG_AVATAR) {
      logger.debug('fileFilter called', { mimetype: file.mimetype });
    }
    const allowedTypes = ['image/png', 'image/jpeg', 'image/jpg', 'image/webp'];
    if (allowedTypes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      if (DEBUG_AVATAR) {
        logger.debug('fileFilter rejected - invalid type', { mimetype: file.mimetype });
      }
      cb(new ApplicationError('Invalid file type. Only PNG, JPG, WebP allowed', 400, 'INVALID_FILE_TYPE'));
    }
  },
});

// Wrapper for multer middleware to catch and log errors
const multerMiddleware = (req: AuthRequest, res: Response, next: NextFunction): void => {
  if (DEBUG_AVATAR) {
    logger.debug('Multer middleware starting', {
      contentType: req.get('Content-Type'),
      contentLength: req.get('Content-Length'),
    });
  }
  
  upload.single('avatar')(req, res, (err) => {
    if (err) {
      if (DEBUG_AVATAR) {
        logger.error('Multer error', {
          errorName: err.name,
          errorMessage: err.message,
          errorCode: 'code' in err ? (err as any).code : undefined,
        });
      }
      return next(err);
    }
    if (DEBUG_AVATAR) {
      logger.debug('Multer middleware completed successfully');
    }
    next();
  });
};

// POST /api/users/me/avatar
export const uploadAvatar = [
  multerMiddleware,
  async (req: AuthRequest, res: Response, next: NextFunction): Promise<void> => {
    try {
      if (DEBUG_AVATAR) {
        const file = (req as Request & { file?: Express.Multer.File }).file;
        logger.debug('Avatar upload - Request received', {
          userId: req.user?.id,
          fileReceived: !!file,
          fileFieldName: file?.fieldname,
          fileOriginalName: file?.originalname,
          fileMimetype: file?.mimetype,
          fileSize: file?.size,
          contentType: req.get('Content-Type'),
        });
      }

      if (!req.user) {
        throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
      }

      // Check if user exists and is not soft-deleted
      const user = await db
        .selectFrom('users')
        .select(['id'])
        .where('id', '=', req.user.id)
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (!user) {
        throw new ApplicationError('User not found', 404, 'NOT_FOUND');
      }

      const file = (req as Request & { file?: Express.Multer.File }).file;
      if (!file) {
        if (DEBUG_AVATAR) {
          console.log('   ❌ ERROR: No file in request');
        }
        throw new ApplicationError('Avatar file is required', 400, 'MISSING_FILE');
      }

      if (DEBUG_AVATAR) {
        console.log('2. Processing image...');
        console.log('   - Original image size:', file.size, 'bytes');
        console.log('3. Storing in database...');
      }

      try {
        await uploadUserAvatar(req.user.id, file.buffer);
      } catch (uploadErr) {
        const err = uploadErr instanceof Error ? uploadErr : new Error(String(uploadErr));
        const msg = err.message ?? '';
        const isImageError =
          /input|format|vips|sharp|corrupt|unsupported|expected|decode|bad/.test(msg.toLowerCase());
        if (isImageError) {
          if (DEBUG_AVATAR) {
            logger.debug('Avatar upload - Error during processing', {
              error: err.message,
              stack: err.stack,
            });
          }
          throw new ApplicationError('Invalid or unsupported image format', 400, 'INVALID_IMAGE');
        }
        throw uploadErr; // Rethrow DB etc. so error handler returns 500
      }

      if (DEBUG_AVATAR) {
        logger.debug('Avatar upload - Database update successful');
      }

      res.status(200).json({ success: true, has_avatar: true });
    } catch (error) {
      if (DEBUG_AVATAR && error instanceof Error) {
        logger.debug('Avatar upload - Error during processing', {
          error: error.message,
          stack: error.stack,
        });
      }
      next(error);
    }
  },
];

// GET /api/users/:user_id/avatar
export async function getUserAvatar(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const user_id = String(req.params.user_id);

    if (DEBUG_AVATAR) {
      logger.debug('Avatar get - Request received', {
        userId: user_id,
        hasAuthorization: !!req.get('Authorization'),
      });
    }

    // Validate UUID format (basic check)
    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (!uuidRegex.test(user_id)) {
      res.status(404).json({ error: { message: 'User has no avatar', code: 'NO_AVATAR' } });
      return;
    }

    const user = await db
      .selectFrom('users')
      .select(['avatar_data', 'avatar_mime_type', 'updated_at'])
      .where('id', '=', user_id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (DEBUG_AVATAR) {
      logger.debug('Avatar get - Database query result', {
        userFound: !!user,
        avatarDataExists: !!user?.avatar_data,
        avatarDataSize: user?.avatar_data?.length || 0,
        mimeType: user?.avatar_mime_type || null,
      });
    }

    if (!user?.avatar_data) {
      if (DEBUG_AVATAR) {
        logger.debug('Avatar get - No avatar data in database');
      }
      res.status(404).json({ error: { message: 'User has no avatar', code: 'NO_AVATAR' } });
      return;
    }

    res.set('Content-Type', user.avatar_mime_type || 'image/webp');
    res.set('Cache-Control', 'public, max-age=86400');
    const updatedAt = user.updated_at instanceof Date ? user.updated_at : new Date(user.updated_at as string);
    const etag = `"avatar-${user_id}-${updatedAt.getTime()}"`;
    res.set('ETag', etag);

    if (req.get('If-None-Match') === etag) {
      res.status(304).end();
      return;
    }

    if (DEBUG_AVATAR) {
      console.log('3. Sending binary response');
      console.log('   - Content-Type:', user.avatar_mime_type || 'image/webp');
      console.log('   - Body size:', user.avatar_data.length, 'bytes');
    }

    res.send(user.avatar_data);
  } catch (error) {
    next(error);
  }
}

// DELETE /api/users/me/avatar
export async function deleteAvatar(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (DEBUG_AVATAR) {
      logger.debug('Avatar delete - Request received', {
        userId: req.user?.id,
      });
    }

    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Check if user exists and is not soft-deleted
    const user = await db
      .selectFrom('users')
      .select(['id'])
      .where('id', '=', req.user.id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!user) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }

    // Check current state before deletion
    if (DEBUG_AVATAR) {
      const beforeUser = await db
        .selectFrom('users')
        .select(['avatar_data', 'avatar_mime_type'])
        .where('id', '=', req.user.id)
        .executeTakeFirst();
      console.log('2. Before deletion:');
      console.log('   - Avatar exists:', beforeUser?.avatar_data ? 'YES' : 'NO');
    }

    if (DEBUG_AVATAR) {
      console.log('3. Deleting avatar...');
    }

    await deleteUserAvatar(req.user.id);

    // Verify deletion
    if (DEBUG_AVATAR) {
      const afterUser = await db
        .selectFrom('users')
        .select(['avatar_data', 'avatar_mime_type'])
        .where('id', '=', req.user.id)
        .executeTakeFirst();
      console.log('4. After deletion:');
      console.log('   - Avatar exists:', afterUser?.avatar_data ? 'YES' : 'NO');
      const response = { success: true, has_avatar: false };
      console.log('5. Sending response:', JSON.stringify(response));
    }

    res.status(200).json({ success: true, has_avatar: false });
  } catch (error) {
    if (DEBUG_AVATAR && error instanceof Error) {
      logger.debug('Avatar delete - Error during delete', {
        error: error.message,
        stack: error.stack,
      });
    }
    next(error);
  }
}

// GET /api/users/me
export async function getCurrentUser(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const user = await db
      .selectFrom('users')
      .select([
        'id',
        'email',
        'display_name',
        'created_at',
        sql<boolean>`avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .where('id', '=', req.user.id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!user) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }

    res.status(200).json({
      id: user.id,
      email: user.email,
      display_name: user.display_name,
      has_avatar: Boolean(user.has_avatar),
      created_at: user.created_at,
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/users/me
export async function updateCurrentUser(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = UpdateUserSchema.parse(req.body);

    // Build update object (only include provided fields)
    const updates: Record<string, unknown> = {};
    if (data.display_name !== undefined) {
      updates.display_name = data.display_name;
    }

    if (Object.keys(updates).length === 0) {
      // No updates, return current user
      return getCurrentUser(req, res, next);
    }

    // Update user
    const updatedUser = await db
      .updateTable('users')
      .set(updates)
      .where('id', '=', req.user.id)
      .where('deleted_at', 'is', null)
      .returning([
        'id',
        'email',
        'display_name',
        'created_at',
        sql<boolean>`avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .executeTakeFirst();

    if (!updatedUser) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }

    res.status(200).json({
      id: updatedUser.id,
      email: updatedUser.email,
      display_name: updatedUser.display_name,
      has_avatar: Boolean(updatedUser.has_avatar),
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/users/me/password
export async function changePassword(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = ChangePasswordSchema.parse(req.body);

    // Get user to check current password
    const user = await db
      .selectFrom('users')
      .select(['password_hash'])
      .where('id', '=', req.user.id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!user) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }

    // If current_password provided, verify it
    if (data.current_password !== null && data.current_password !== undefined) {
      if (!user.password_hash) {
        throw new ApplicationError('User has no password set', 400, 'NO_PASSWORD');
      }
      const validPassword = await verifyPassword(data.current_password, user.password_hash);
      if (!validPassword) {
        throw new ApplicationError('Invalid current password', 400, 'INVALID_PASSWORD');
      }
    } else {
      // current_password is null - verify user has no password (Google-only)
      if (user.password_hash) {
        throw new ApplicationError('Current password is required', 400, 'PASSWORD_REQUIRED');
      }
    }

    // Hash new password
    const newPasswordHash = await hashPassword(data.new_password);

    // Update password_hash
    await db
      .updateTable('users')
      .set({ password_hash: newPasswordHash })
      .where('id', '=', req.user.id)
      .execute();

    // Ensure auth_providers entry exists for 'email' provider
    const emailProvider = await db
      .selectFrom('auth_providers')
      .select('id')
      .where('user_id', '=', req.user.id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    if (!emailProvider) {
      // Get user email for provider_user_id
      const userEmail = await db
        .selectFrom('users')
        .select('email')
        .where('id', '=', req.user.id)
        .executeTakeFirst();

      if (userEmail) {
        await db
          .insertInto('auth_providers')
          .values({
            user_id: req.user.id,
            provider: 'email',
            provider_user_id: userEmail.email,
            provider_email: userEmail.email,
          })
          .execute();
      }
    }

    res.status(200).json({ success: true });
  } catch (error) {
    next(error);
  }
}

// GET /api/users/me/providers
export async function getAuthProviders(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Query auth_providers
    const providers = await db
      .selectFrom('auth_providers')
      .select(['provider', 'provider_email', 'linked_at'])
      .where('user_id', '=', req.user.id)
      .execute();

    // Get user password_hash to check has_password for email provider
    const user = await db
      .selectFrom('users')
      .select(['password_hash'])
      .where('id', '=', req.user.id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!user) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }

    // Map providers with has_password for email provider
    const providersWithPassword = providers.map((p) => {
      if (p.provider === 'email') {
        return {
          provider: p.provider,
          has_password: user.password_hash !== null,
          linked_at: p.linked_at,
        };
      } else {
        return {
          provider: p.provider,
          provider_email: p.provider_email,
          linked_at: p.linked_at,
        };
      }
    });

    res.status(200).json({ providers: providersWithPassword });
  } catch (error) {
    next(error);
  }
}

// GET /api/users/me/subscription
export async function getSubscription(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }
    const state = await getUserSubscriptionState(req.user.id);
    if (!state) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }
    const groupsRemaining = Math.max(0, state.group_limit - state.current_group_count);
    const canCreate = state.current_group_count < state.group_limit;
    const canJoin = state.current_group_count < state.group_limit;

    let subscription_expires_at: string | null = null;
    let auto_renew: boolean | null = null;
    if (state.current_subscription_tier === 'premium') {
      const sub = await db
        .selectFrom('user_subscriptions')
        .select(['expires_at', 'auto_renew'])
        .where('user_id', '=', req.user.id)
        .where('tier', '=', 'premium')
        .where('status', 'in', ['active', 'grace_period'])
        .orderBy('started_at', 'desc')
        .executeTakeFirst();
      if (sub?.expires_at) subscription_expires_at = new Date(sub.expires_at as Date).toISOString();
      if (sub) auto_renew = sub.auto_renew ?? null;
    }

    res.status(200).json({
      tier: state.current_subscription_tier,
      status: state.subscription_status,
      group_limit: state.group_limit,
      current_group_count: state.current_group_count,
      groups_remaining: groupsRemaining,
      is_over_limit: state.subscription_status === 'over_limit',
      subscription_expires_at,
      auto_renew,
      can_create_group: canCreate,
      can_join_group: canJoin,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/users/me/subscription/eligibility
export async function getSubscriptionEligibilityHandler(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }
    const eligibility = await getSubscriptionEligibility(req.user.id);
    if (!eligibility) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }
    res.status(200).json(eligibility);
  } catch (error) {
    next(error);
  }
}

// GET /api/users/me/groups
export async function getUserGroups(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Validate query params
    const query = GetGroupsQuerySchema.parse(req.query);
    const limit = Math.min(query.limit ?? 50, 100); // Clamp to max 100
    const offset = query.offset ?? 0;

    // Get user's groups with member count, role, and heat data
    // Use subquery to get member count for each group
    const groups = await db
      .selectFrom('group_memberships')
      .innerJoin('groups', 'groups.id', 'group_memberships.group_id')
      .leftJoin('group_heat', 'group_heat.group_id', 'groups.id')
      .select([
        'groups.id',
        'groups.name',
        'groups.description',
        'groups.icon_emoji',
        'groups.icon_color',
        'group_memberships.role',
        'group_memberships.joined_at',
        sql<boolean>`groups.icon_data IS NOT NULL`.as('has_icon'),
        // Subquery for member count
        sql<number>`
          (SELECT COUNT(*)::int 
           FROM group_memberships gm 
           WHERE gm.group_id = groups.id
             AND gm.status = 'active')
        `.as('member_count'),
        // Heat data
        'group_heat.heat_score',
        'group_heat.heat_tier',
        'group_heat.streak_days',
        'group_heat.peak_score',
        'group_heat.last_calculated_at',
      ])
      .where('group_memberships.user_id', '=', req.user.id)
      .orderBy('group_memberships.joined_at', 'desc')
      .limit(limit)
      .offset(offset)
      .execute();

    // Determine read-only groups for free users who downgraded (over group limit)
    let keptGroupId: string | null = null;
    const subState = await getUserSubscriptionState(req.user.id);
    if (
      subState &&
      subState.current_subscription_tier === 'free' &&
      subState.current_group_count > subState.group_limit
    ) {
      const latest = await db
        .selectFrom('subscription_downgrade_history')
        .select(['kept_group_id'])
        .where('user_id', '=', req.user.id)
        .where('kept_group_id', 'is not', null)
        .orderBy('downgrade_date', 'desc')
        .limit(1)
        .executeTakeFirst();
      keptGroupId = latest?.kept_group_id ?? null;
    }

    // Get total count
    const totalResult = await db
      .selectFrom('group_memberships')
      .select(db.fn.count('id').as('count'))
      .where('user_id', '=', req.user.id)
      .executeTakeFirst();

    const total = totalResult ? Number(totalResult.count) : 0;

    res.status(200).json({
      groups: groups.map((g) => ({
        id: g.id,
        name: g.name,
        description: g.description,
        icon_emoji: g.icon_emoji,
        icon_color: g.icon_color,
        has_icon: Boolean(g.has_icon),
        member_count: Number(g.member_count),
        role: g.role,
        joined_at: g.joined_at,
        is_read_only: keptGroupId !== null && g.id !== keptGroupId,
        heat: {
          score: g.heat_score != null ? Number(g.heat_score) : 0,
          tier: g.heat_tier ?? 0,
          tier_name: tierName(g.heat_tier ?? 0),
          streak_days: g.streak_days ?? 0,
          peak_score: g.peak_score != null ? Number(g.peak_score) : 0,
          last_calculated_at: g.last_calculated_at?.toISOString() ?? null,
        },
      })),
      total,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/users/me/consents
export async function getUserConsents(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const consents = await db
      .selectFrom('user_consents')
      .select(['consent_type', 'agreed_at'])
      .where('user_id', '=', req.user.id)
      .orderBy('agreed_at', 'desc')
      .execute();

    res.status(200).json({ consents });
  } catch (error) {
    next(error);
  }
}

// POST /api/users/me/consents
export async function recordConsents(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = RecordConsentsSchema.parse(req.body);

    await db.insertInto('user_consents').values(
      data.consent_types.map(ct => ({
        user_id: req.user!.id,
        consent_type: ct,
        ip_address: req.ip || null,
      }))
    ).execute();

    res.status(201).json({ success: true });
  } catch (error) {
    next(error);
  }
}

// POST /api/users/consent-hash (test-only)
export async function getConsentEmailHash(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (process.env.NODE_ENV !== 'test') {
      throw new ApplicationError('Not found', 404, 'NOT_FOUND');
    }

    const data = ConsentHashLookupSchema.parse(req.body);

    const emailHash = crypto
      .createHash('sha256')
      .update(data.email.toLowerCase() + process.env.CONSENT_HASH_SALT!)
      .digest('hex');

    res.status(200).json({ email_hash: emailHash });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/users/me
export async function deleteCurrentUser(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = DeleteUserSchema.parse(req.body);

    // Validate confirmation string
    if (data.confirmation.toLowerCase() !== 'delete') {
      throw new ApplicationError('Confirmation must be "delete"', 400, 'INVALID_CONFIRMATION');
    }

    const userId = req.user.id;

    // Delete user photos from GCS before database cleanup
    // If this fails, photos will expire via GCS lifecycle rules
    try {
      await deleteUserPhotos(userId);
    } catch (error) {
      logger.warn('Failed to delete user photos from GCS', { userId, error });
    }

    await db.transaction().execute(async (trx) => {
      // Check user exists
      const user = await trx
        .selectFrom('users')
        .select(['id'])
        .where('id', '=', userId)
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (!user) {
        throw new ApplicationError('User not found', 404, 'NOT_FOUND');
      }

      // 1. Find groups where user is creator → transfer or delete
      const createdGroups = await trx
        .selectFrom('groups')
        .select(['id'])
        .where('creator_user_id', '=', userId)
        .execute();

      for (const group of createdGroups) {
        const memberCount = await trx
          .selectFrom('group_memberships')
          .select(trx.fn.count('id').as('count'))
          .where('group_id', '=', group.id)
          .where('status', '=', 'active')
          .executeTakeFirst();

        const count = Number(memberCount?.count ?? 0);

        if (count <= 1) {
          // Sole member (or no members) — delete the group (CASCADE handles children)
          await trx.deleteFrom('groups').where('id', '=', group.id).execute();
        } else {
          // Other members exist — transfer creator to oldest admin, or oldest member
          const newCreator = await trx
            .selectFrom('group_memberships')
            .select(['user_id', 'role', 'joined_at'])
            .where('group_id', '=', group.id)
            .where('user_id', '!=', userId)
            .where('status', '=', 'active')
            .orderBy(sql`CASE WHEN role = 'admin' THEN 0 ELSE 1 END`, 'asc')
            .orderBy('joined_at', 'asc')
            .limit(1)
            .executeTakeFirst();

          if (newCreator) {
            await trx
              .updateTable('groups')
              .set({ creator_user_id: newCreator.user_id })
              .where('id', '=', group.id)
              .execute();

            // Promote to admin if not already
            if (newCreator.role !== 'admin' && newCreator.role !== 'creator') {
              await trx
                .updateTable('group_memberships')
                .set({ role: 'admin' })
                .where('group_id', '=', group.id)
                .where('user_id', '=', newCreator.user_id)
                .execute();
            }
          }
        }
      }

      // 2. Ghost-link: hash user email into consent records before deletion
      const emailHash = crypto
        .createHash('sha256')
        .update(req.user!.email + process.env.CONSENT_HASH_SALT!)
        .digest('hex');

      await trx
        .updateTable('user_consents')
        .set({ email_hash: emailHash })
        .where('user_id', '=', userId)
        .execute();

      // 3. Hard delete user via SQL function — FK constraints handle all cleanup:
      //    CASCADE: auth_providers, refresh_tokens, password_reset_tokens, devices,
      //             group_memberships, progress_entries, user_subscriptions,
      //             subscription_downgrade_history
      //    SET NULL: goals.created_by_user_id, goals.deleted_by_user_id,
      //              group_activities.user_id, invite_codes.created_by_user_id,
      //              user_consents.user_id
      await sql`SELECT delete_user_data(${userId}::uuid)`.execute(trx);
    });

    res.status(204).end();
  } catch (error) {
    next(error);
  }
}
