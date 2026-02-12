import { Storage } from '@google-cloud/storage';
import { logger } from '../utils/logger.js';

// GCS bucket name (default for production, can be overridden via env)
const BUCKET_NAME = process.env.GCS_PHOTO_BUCKET || 'pursue-photo-journal-australia';

// Signed URL expiry (1 hour)
const SIGNED_URL_EXPIRY_MS = 60 * 60 * 1000;

// Lazy-initialized Storage client (uses ADC in production, service account in dev)
let storageClient: Storage | null = null;

function getStorage(): Storage {
  if (!storageClient) {
    storageClient = new Storage();
  }
  return storageClient;
}

/**
 * Build the GCS object path for a progress photo.
 * Format: {userId}/{year}/{month}/{progressEntryId}.jpg
 *
 * This structure enables:
 * - Efficient prefix deletion on account deletion
 * - Logical organization by user and time
 */
export function buildObjectPath(userId: string, progressEntryId: string): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  return `${userId}/${year}/${month}/${progressEntryId}.jpg`;
}

/**
 * Upload a photo to GCS.
 * The buffer should already be processed (EXIF stripped, re-encoded as JPEG).
 *
 * @param objectPath - The full object path (from buildObjectPath)
 * @param buffer - The processed image buffer
 * @param contentType - MIME type (default: image/jpeg)
 */
export async function uploadPhoto(
  objectPath: string,
  buffer: Buffer,
  contentType: string = 'image/jpeg'
): Promise<void> {
  const storage = getStorage();
  const bucket = storage.bucket(BUCKET_NAME);
  const file = bucket.file(objectPath);

  await file.save(buffer, {
    contentType,
    metadata: {
      cacheControl: 'private, max-age=3600',
    },
    resumable: false, // Small files, no need for resumable upload
  });

  logger.debug('Photo uploaded to GCS', { objectPath, size: buffer.length });
}

/**
 * Generate a signed URL for reading a photo.
 * URL expires in 1 hour.
 *
 * @param objectPath - The full object path
 * @returns Signed URL string
 */
export async function getSignedUrl(objectPath: string): Promise<string> {
  const storage = getStorage();
  const bucket = storage.bucket(BUCKET_NAME);
  const file = bucket.file(objectPath);

  const [url] = await file.getSignedUrl({
    version: 'v4',
    action: 'read',
    expires: Date.now() + SIGNED_URL_EXPIRY_MS,
  });

  return url;
}

/**
 * Delete all photos for a user (for account deletion).
 * Uses prefix deletion to remove all objects under {userId}/.
 *
 * @param userId - The user ID whose photos should be deleted
 */
export async function deleteUserPhotos(userId: string): Promise<void> {
  const storage = getStorage();
  const bucket = storage.bucket(BUCKET_NAME);

  const [files] = await bucket.getFiles({
    prefix: `${userId}/`,
  });

  if (files.length === 0) {
    logger.debug('No photos to delete for user', { userId });
    return;
  }

  // Delete files in batches
  await Promise.all(files.map((file) => file.delete().catch((err) => {
    logger.warn('Failed to delete photo file', { userId, file: file.name, error: err.message });
  })));

  logger.info('Deleted user photos from GCS', { userId, count: files.length });
}

/**
 * Delete a single photo from GCS.
 *
 * @param objectPath - The full object path to delete
 */
export async function deletePhoto(objectPath: string): Promise<void> {
  const storage = getStorage();
  const bucket = storage.bucket(BUCKET_NAME);
  const file = bucket.file(objectPath);

  try {
    await file.delete();
    logger.debug('Photo deleted from GCS', { objectPath });
  } catch (err) {
    // If file doesn't exist, that's fine - it may have been deleted by lifecycle
    const error = err as { code?: number };
    if (error.code === 404) {
      logger.debug('Photo already deleted from GCS', { objectPath });
      return;
    }
    throw err;
  }
}

/**
 * Check if a photo exists in GCS.
 *
 * @param objectPath - The full object path to check
 * @returns true if the file exists
 */
export async function photoExists(objectPath: string): Promise<boolean> {
  const storage = getStorage();
  const bucket = storage.bucket(BUCKET_NAME);
  const file = bucket.file(objectPath);

  const [exists] = await file.exists();
  return exists;
}

// Export for testing
export function _resetStorageClient(): void {
  storageClient = null;
}

export function _setStorageClient(client: Storage): void {
  storageClient = client;
}
