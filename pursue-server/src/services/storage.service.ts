import sharp from 'sharp';
import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';

/**
 * Process and upload group icon to PostgreSQL BYTEA.
 * Resizes to 256x256, converts to WebP.
 */
export async function uploadGroupIcon(
  groupId: string,
  buffer: Buffer
): Promise<void> {
  // Process image: resize to 256x256, crop to center, convert to WebP
  const processedBuffer = await sharp(buffer)
    .resize(256, 256, { fit: 'cover', position: 'center' })
    .webp({ quality: 90 })
    .toBuffer();

  // Store directly in database
  await db
    .updateTable('groups')
    .set({
      icon_data: processedBuffer,
      icon_mime_type: 'image/webp',
      icon_emoji: null,
      icon_color: null,
    })
    .where('id', '=', groupId)
    .execute();
}

/**
 * Delete group icon from database.
 */
export async function deleteGroupIcon(groupId: string): Promise<void> {
  await db
    .updateTable('groups')
    .set({
      icon_data: null,
      icon_mime_type: null,
    })
    .where('id', '=', groupId)
    .execute();
}

/**
 * Process and upload user avatar to PostgreSQL BYTEA.
 * Resizes to 256x256, converts to WebP.
 */
export async function uploadUserAvatar(
  userId: string,
  buffer: Buffer
): Promise<void> {
  const DEBUG_AVATAR = process.env.DEBUG_AVATAR === 'true';

  // Process image: resize to 256x256, crop to center, convert to WebP
  const processedBuffer = await sharp(buffer)
    .resize(256, 256, { fit: 'cover', position: 'center' })
    .webp({ quality: 90 })
    .toBuffer();

  if (DEBUG_AVATAR) {
    const sizeReduction = ((1 - processedBuffer.length / buffer.length) * 100).toFixed(1);
    logger.debug('Avatar processing complete', {
      processedSize: processedBuffer.length,
      originalSize: buffer.length,
      sizeReduction: `${sizeReduction}%`,
    });
  }

  // Store directly in database
  await db
    .updateTable('users')
    .set({
      avatar_data: processedBuffer,
      avatar_mime_type: 'image/webp',
    })
    .where('id', '=', userId)
    .execute();
}

/**
 * Delete user avatar from database.
 */
export async function deleteUserAvatar(userId: string): Promise<void> {
  await db
    .updateTable('users')
    .set({
      avatar_data: null,
      avatar_mime_type: null,
    })
    .where('id', '=', userId)
    .execute();
}
