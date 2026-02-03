import type { Response, NextFunction } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { RegisterDeviceSchema } from '../validations/devices.js';

// POST /api/devices/register
export async function registerDevice(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = RegisterDeviceSchema.parse(req.body);

    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Check if device with this FCM token already exists
    const existingDevice = await db
      .selectFrom('devices')
      .select(['id', 'user_id'])
      .where('fcm_token', '=', data.fcm_token)
      .executeTakeFirst();

    let device;

    if (existingDevice) {
      // Update existing device (upsert behavior)
      device = await db
        .updateTable('devices')
        .set({
          user_id: req.user.id,
          device_name: data.device_name || null,
          platform: data.platform || null,
          last_active: new Date().toISOString(),
        })
        .where('id', '=', existingDevice.id)
        .returning(['id', 'device_name', 'platform'])
        .executeTakeFirstOrThrow();
    } else {
      // Create new device
      device = await db
        .insertInto('devices')
        .values({
          user_id: req.user.id,
          fcm_token: data.fcm_token,
          device_name: data.device_name || null,
          platform: data.platform || null,
        })
        .returning(['id', 'device_name', 'platform'])
        .executeTakeFirstOrThrow();
    }

    res.status(200).json({
      id: device.id,
      device_name: device.device_name,
      platform: device.platform,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/devices
export async function listDevices(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const devices = await db
      .selectFrom('devices')
      .select(['id', 'device_name', 'platform', 'last_active', 'created_at'])
      .where('user_id', '=', req.user.id)
      .orderBy('last_active', 'desc')
      .execute();

    res.status(200).json({
      devices: devices.map((d) => ({
        id: d.id,
        device_name: d.device_name,
        platform: d.platform,
        last_active: d.last_active,
        created_at: d.created_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/devices/:device_id
export async function unregisterDevice(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const { device_id } = req.params;

    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    // Check if device exists and belongs to user
    const device = await db
      .selectFrom('devices')
      .select(['id', 'user_id'])
      .where('id', '=', device_id)
      .executeTakeFirst();

    if (!device) {
      throw new ApplicationError('Device not found', 404, 'DEVICE_NOT_FOUND');
    }

    if (device.user_id !== req.user.id) {
      throw new ApplicationError('Device not found', 404, 'DEVICE_NOT_FOUND');
    }

    // Delete the device
    await db
      .deleteFrom('devices')
      .where('id', '=', device_id)
      .execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}
