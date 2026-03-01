import type { Request, Response, NextFunction } from 'express';
import { ApplicationError } from './errorHandler.js';
import { logger } from '../utils/logger.js';

/**
 * Middleware to authenticate internal job endpoints.
 *
 * Authentication is via the X-Internal-Job-Key header, which must match
 * the INTERNAL_JOB_KEY environment variable. This key is configured in
 * Cloud Scheduler job definitions and kept secret.
 *
 * Note: IP allowlisting is not used because Cloud Scheduler sends requests
 * from dynamic Google egress IPs (e.g. 66.249.x.x), not from within the VPC.
 * The shared secret header provides equivalent authentication.
 */
export function internalJobAuth(
  req: Request,
  res: Response,
  next: NextFunction
): void {
  try {
    const jobKey = req.headers['x-internal-job-key'];
    const clientIp = req.ip || req.socket.remoteAddress;
    const userAgent = req.headers['user-agent'] || '';

    // Verify job key
    const expectedJobKey = process.env.INTERNAL_JOB_KEY;
    if (!jobKey || jobKey !== expectedJobKey) {
      logger.warn('Internal job endpoint called without valid key', {
        endpoint: req.path,
        clientIp,
        userAgent,
        hasKey: !!jobKey,
      });
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    logger.info('Internal job endpoint authorized', {
      endpoint: req.path,
      clientIp,
      userAgent,
    });

    next();
  } catch (error) {
    if (error instanceof ApplicationError) {
      throw error;
    }
    throw new ApplicationError('Internal Server Error', 500, 'INTERNAL_ERROR');
  }
}
