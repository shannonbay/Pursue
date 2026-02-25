import type { Response, NextFunction, Request } from 'express';
import multer, { type FileFilterCallback } from 'multer';
import sharp from 'sharp';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { PhotoUploadSchema } from '../validations/photos.js';
import { buildObjectPath, uploadPhoto, getSignedUrl } from '../services/gcs.service.js';
import { logger } from '../utils/logger.js';
import { checkPhotoWithContext } from '../services/openai-moderation.service.js';

// Constants
const MAX_FILE_SIZE = 500 * 1024; // 500 KB
const EDIT_WINDOW_MINUTES = 15;
const FREE_QUOTA_PER_WEEK = 3;
const PREMIUM_QUOTA_PER_WEEK = 70;

// Configure multer for photo uploads
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_FILE_SIZE },
  fileFilter: (_req: Request, file: Express.Multer.File, cb: FileFilterCallback) => {
    const allowedTypes = ['image/jpeg', 'image/jpg', 'image/webp'];
    if (allowedTypes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new ApplicationError('Invalid file type. Only JPEG and WebP allowed', 400, 'INVALID_FILE_TYPE'));
    }
  },
});

// Multer middleware wrapper
const multerMiddleware = (req: AuthRequest, res: Response, next: NextFunction): void => {
  upload.single('photo')(req, res, (err) => {
    if (err) {
      return next(err);
    }
    next();
  });
};

/**
 * Check if user has reached their weekly photo upload quota.
 * Returns { allowed: true } or { allowed: false, remaining, limit, upgrade_required }
 */
async function checkPhotoQuota(userId: string): Promise<{
  allowed: boolean;
  remaining: number;
  limit: number;
  upgrade_required: boolean;
}> {
  // Get user's subscription tier
  const user = await db
    .selectFrom('users')
    .select(['current_subscription_tier'])
    .where('id', '=', userId)
    .where('deleted_at', 'is', null)
    .executeTakeFirst();

  if (!user) {
    throw new ApplicationError('User not found', 404, 'NOT_FOUND');
  }

  const isPremium = user.current_subscription_tier === 'premium';
  const limit = isPremium ? PREMIUM_QUOTA_PER_WEEK : FREE_QUOTA_PER_WEEK;

  // Count uploads in the last 7 days
  const sevenDaysAgo = new Date();
  sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 7);

  const countResult = await db
    .selectFrom('photo_upload_log')
    .select(db.fn.count('id').as('count'))
    .where('user_id', '=', userId)
    .where('uploaded_at', '>', sevenDaysAgo)
    .executeTakeFirstOrThrow();

  const used = Number(countResult.count);
  const remaining = Math.max(0, limit - used);

  return {
    allowed: used < limit,
    remaining,
    limit,
    upgrade_required: !isPremium && used >= limit,
  };
}

/**
 * POST /api/progress/:progress_entry_id/photo
 * Upload a photo attachment to a progress entry.
 */
export const uploadProgressPhoto = [
  multerMiddleware,
  async (req: AuthRequest, res: Response, next: NextFunction): Promise<void> => {
    try {
      if (!req.user) {
        throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
      }

      const progressEntryId = String(req.params.progress_entry_id);
      const file = (req as Request & { file?: Express.Multer.File }).file;

      // Validate file exists
      if (!file) {
        throw new ApplicationError('Photo file is required', 400, 'MISSING_FILE');
      }

      // Validate dimensions from form data
      const metadata = PhotoUploadSchema.parse(req.body);

      // Fetch progress entry with goal info
      const entry = await db
        .selectFrom('progress_entries')
        .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
        .select([
          'progress_entries.id',
          'progress_entries.user_id',
          'progress_entries.logged_at',
          'progress_entries.log_title',
          'goals.group_id',
        ])
        .where('progress_entries.id', '=', progressEntryId)
        .where('goals.deleted_at', 'is', null)
        .executeTakeFirst();

      if (!entry) {
        throw new ApplicationError('Progress entry not found', 404, 'NOT_FOUND');
      }

      // Verify ownership
      if (entry.user_id !== req.user.id) {
        throw new ApplicationError('You can only attach photos to your own progress entries', 403, 'FORBIDDEN');
      }

      // Check edit window (15 minutes from logged_at)
      const loggedAt = new Date(entry.logged_at);
      const editWindowEnd = new Date(loggedAt.getTime() + EDIT_WINDOW_MINUTES * 60 * 1000);
      if (new Date() > editWindowEnd) {
        throw new ApplicationError(
          `Edit window expired. Photos can only be attached within ${EDIT_WINDOW_MINUTES} minutes of logging progress.`,
          403,
          'EDIT_WINDOW_EXPIRED'
        );
      }

      // Check if entry already has a photo
      const existingPhoto = await db
        .selectFrom('progress_photos')
        .select('id')
        .where('progress_entry_id', '=', progressEntryId)
        .executeTakeFirst();

      if (existingPhoto) {
        throw new ApplicationError('This progress entry already has a photo attached', 409, 'PHOTO_ALREADY_EXISTS');
      }

      // Check quota
      const quota = await checkPhotoQuota(req.user.id);
      if (!quota.allowed) {
        throw new ApplicationError(
          quota.upgrade_required
            ? 'Weekly photo limit reached. Upgrade to Premium for more uploads.'
            : 'Weekly photo limit reached.',
          422,
          'QUOTA_EXCEEDED',
          {
            upgrade_required: quota.upgrade_required,
            remaining: quota.remaining,
            limit: quota.limit,
          }
        );
      }

      // Process image with Sharp (strip EXIF, re-encode as JPEG)
      let processedBuffer: Buffer;
      try {
        processedBuffer = await sharp(file.buffer)
          .rotate() // Apply EXIF orientation
          .jpeg({ quality: 85 }) // Re-encode as JPEG, strip EXIF
          .toBuffer();
      } catch (err) {
        logger.error('Failed to process photo', { error: err });
        throw new ApplicationError('Failed to process image', 400, 'INVALID_IMAGE');
      }

      // Run OpenAI moderation on photo + log_title context before upload
      await checkPhotoWithContext(processedBuffer, entry.log_title ?? undefined);

      // Build GCS path and upload
      const objectPath = buildObjectPath(req.user.id, progressEntryId);
      await uploadPhoto(objectPath, processedBuffer);

      // Calculate expiry (7 days from now)
      const expiresAt = new Date();
      expiresAt.setDate(expiresAt.getDate() + 7);

      // Insert photo record and upload log in transaction
      const photo = await db.transaction().execute(async (trx) => {
        // Insert photo record
        const photoRecord = await trx
          .insertInto('progress_photos')
          .values({
            progress_entry_id: progressEntryId,
            user_id: req.user!.id,
            gcs_object_path: objectPath,
            width_px: metadata.width,
            height_px: metadata.height,
            expires_at: expiresAt.toISOString(),
          })
          .returning(['id', 'expires_at'])
          .executeTakeFirstOrThrow();

        // Insert upload log for quota tracking
        await trx
          .insertInto('photo_upload_log')
          .values({
            user_id: req.user!.id,
          })
          .execute();

        return photoRecord;
      });

      res.status(201).json({
        photo_id: photo.id,
        expires_at: photo.expires_at,
      });
    } catch (error) {
      next(error);
    }
  },
];

/**
 * GET /api/progress/:progress_entry_id/photo
 * Get the photo for a progress entry (returns signed URL).
 */
export async function getProgressPhoto(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const progressEntryId = String(req.params.progress_entry_id);

    // Fetch progress entry with goal to check group membership
    const entry = await db
      .selectFrom('progress_entries')
      .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
      .select([
        'progress_entries.id',
        'goals.group_id',
      ])
      .where('progress_entries.id', '=', progressEntryId)
      .where('goals.deleted_at', 'is', null)
      .executeTakeFirst();

    if (!entry) {
      throw new ApplicationError('Progress entry not found', 404, 'NOT_FOUND');
    }

    // Verify user is a member of the group
    const membership = await db
      .selectFrom('group_memberships')
      .select('id')
      .where('group_id', '=', entry.group_id)
      .where('user_id', '=', req.user.id)
      .where('status', '=', 'active')
      .executeTakeFirst();

    if (!membership) {
      throw new ApplicationError('You must be a member of this group to view photos', 403, 'FORBIDDEN');
    }

    // Fetch photo record
    const photo = await db
      .selectFrom('progress_photos')
      .select(['id', 'gcs_object_path', 'width_px', 'height_px', 'expires_at', 'gcs_deleted_at'])
      .where('progress_entry_id', '=', progressEntryId)
      .executeTakeFirst();

    if (!photo) {
      throw new ApplicationError('No photo attached to this progress entry', 404, 'NO_PHOTO');
    }

    // Check if photo is expired or deleted
    const now = new Date();
    const expiresAt = new Date(photo.expires_at);
    if (now > expiresAt || photo.gcs_deleted_at) {
      throw new ApplicationError('Photo has expired', 410, 'PHOTO_EXPIRED');
    }

    // Generate signed URL
    const url = await getSignedUrl(photo.gcs_object_path);

    res.status(200).json({
      photo_id: photo.id,
      url,
      width: photo.width_px,
      height: photo.height_px,
      expires_at: photo.expires_at,
    });
  } catch (error) {
    next(error);
  }
}
