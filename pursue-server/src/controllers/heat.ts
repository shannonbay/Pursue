import type { Response, NextFunction, Request } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { ensureGroupExists, requireGroupMember } from '../services/authorization.js';
import {
  calculateDailyHeatForAllGroups,
  getGroupHeat,
  getGroupHeatHistory,
  getGroupHeatStats,
  tierName,
} from '../services/heat.service.js';
import { HeatHistoryQuerySchema } from '../validations/heat.js';
import { logger } from '../utils/logger.js';

/**
 * POST /api/internal/jobs/calculate-heat
 * Internal endpoint for scheduled job to calculate daily heat for all groups.
 * Secured by INTERNAL_JOB_KEY header.
 */
export async function calculateHeatJob(
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

    logger.info('Starting daily heat calculation job');

    const result = await calculateDailyHeatForAllGroups();

    logger.info('Daily heat calculation job completed', result);

    res.status(200).json({
      success: true,
      processed: result.processed,
      errors: result.errors,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/groups/:group_id/heat/history
 * Get heat history for a group. Premium users get full history, free users get only current.
 */
export async function getHeatHistory(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    await ensureGroupExists(group_id);
    await requireGroupMember(req.user.id, group_id);

    // Validate query params
    const query = HeatHistoryQuerySchema.parse(req.query);
    const days = query.days ?? 30;

    // Get current heat data
    const currentHeat = await getGroupHeat(group_id, false);

    const current = currentHeat
      ? {
          score: currentHeat.score,
          tier: currentHeat.tier,
          tier_name: currentHeat.tier_name,
        }
      : {
          score: 0,
          tier: 0,
          tier_name: 'Cold',
        };

    // Check if user is premium
    const user = await db
      .selectFrom('users')
      .select('current_subscription_tier')
      .where('id', '=', req.user.id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    const isPremium = user?.current_subscription_tier === 'premium';

    if (!isPremium) {
      // Free users get only current heat
      res.status(200).json({
        group_id,
        current,
        history: null,
        stats: null,
        premium_required: true,
      });
      return;
    }

    // Premium users get full history and stats
    const [history, stats] = await Promise.all([
      getGroupHeatHistory(group_id, days),
      getGroupHeatStats(group_id, days),
    ]);

    res.status(200).json({
      group_id,
      current,
      history,
      stats,
      premium_required: false,
    });
  } catch (error) {
    next(error);
  }
}
