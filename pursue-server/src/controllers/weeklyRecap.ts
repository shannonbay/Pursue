import type { Response, NextFunction, Request } from 'express';
import { ApplicationError } from '../middleware/errorHandler.js';
import { processWeeklyRecaps } from '../services/weeklyRecap.service.js';
import { logger } from '../utils/logger.js';

/**
 * POST /api/internal/jobs/weekly-recap
 * Internal endpoint for scheduled job to generate and send weekly recaps.
 * Secured by INTERNAL_JOB_KEY header.
 * 
 * This job should be triggered every 30 minutes on Sundays by Cloud Scheduler.
 * It processes groups whose members are in the timezone where it's currently 7 PM.
 */
export async function weeklyRecapJob(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    // Verify internal job key
    const jobKey = req.headers['x-internal-job-key'];
    const expectedKey = process.env.INTERNAL_JOB_KEY;

    if (!expectedKey) {
      logger.error('INTERNAL_JOB_KEY environment variable not set');
      throw new ApplicationError('Internal server error', 500, 'INTERNAL_ERROR');
    }

    if (jobKey !== expectedKey) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    logger.info('Starting weekly recap job');

    // In test mode, allow force overrides to bypass timezone/day-of-week filtering
    const forceGroupId = process.env.NODE_ENV === 'test' ? (req.body?.forceGroupId as string | undefined) : undefined;
    const forceWeekEnd = process.env.NODE_ENV === 'test' ? (req.body?.forceWeekEnd as string | undefined) : undefined;

    // Process weekly recaps
    // The service will internally check which groups have members experiencing Sunday 7 PM
    const result = await processWeeklyRecaps({ forceGroupId, forceWeekEnd });

    logger.info('Weekly recap job completed', result);

    res.status(200).json({
      success: true,
      groups_processed: result.processed,
      errors: result.errors,
      skipped: result.skipped,
    });
  } catch (error) {
    next(error);
  }
}
