import type { Response, NextFunction } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import {
  createOrRenewPremiumSubscription,
  cancelSubscription,
  selectGroupOnDowngrade,
} from '../services/subscription.service.js';
import { UpgradeSubscriptionSchema, VerifySubscriptionSchema, DowngradeSelectGroupSchema } from '../validations/subscriptions.js';

/**
 * POST /api/subscriptions/upgrade
 * Initiate premium upgrade (after client has purchase token from store).
 */
export async function upgrade(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }
    const data = UpgradeSubscriptionSchema.parse(req.body);
    if (data.product_id !== 'pursue_premium_annual') {
      throw new ApplicationError('Invalid product_id', 400, 'INVALID_PRODUCT');
    }
    // Mock token only accepted when NODE_ENV=test and not running under Jest (E2E dev server)
    const isE2EMode = process.env.NODE_ENV === 'test' && !process.env.JEST_WORKER_ID;
    if (data.purchase_token === 'mock-token-e2e' && !isE2EMode) {
      throw new ApplicationError('Invalid purchase token', 400, 'INVALID_TOKEN');
    }
    const result = await createOrRenewPremiumSubscription(req.user.id, {
      platform: data.platform,
      purchase_token: data.purchase_token,
      product_id: data.product_id,
    });
    res.status(200).json({
      subscription_id: result.subscription_id,
      tier: result.tier,
      status: result.status,
      expires_at: result.expires_at.toISOString(),
      group_limit: result.group_limit,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/subscriptions/verify
 * Verify purchase with platform (internal/webhook). If user_id not provided, requires auth.
 */
export async function verify(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const data = VerifySubscriptionSchema.parse(req.body);
    const userId = data.user_id ?? req.user?.id;
    if (!userId) {
      throw new ApplicationError('user_id required or authenticate', 401, 'UNAUTHORIZED');
    }
    if (data.product_id !== 'pursue_premium_annual') {
      throw new ApplicationError('Invalid product_id', 400, 'INVALID_PRODUCT');
    }
    const { verifyPurchase } = await import('../services/subscription.service.js');
    const result = await verifyPurchase(userId, {
      platform: data.platform,
      purchase_token: data.purchase_token,
      product_id: data.product_id,
    });
    res.status(200).json({
      subscription_id: result.subscription_id,
      tier: result.tier,
      status: result.status,
      expires_at: result.expires_at.toISOString(),
      group_limit: result.group_limit,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/subscriptions/cancel
 * Cancel current premium subscription (access until expires_at).
 */
export async function cancel(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }
    const result = await cancelSubscription(req.user.id);
    res.status(200).json({
      status: result.status,
      access_until: result.access_until.toISOString(),
      auto_renew: result.auto_renew,
      message: result.message,
    });
  } catch (error) {
    if (error instanceof Error && error.message === 'No active premium subscription found') {
      next(new ApplicationError('No active premium subscription found', 404, 'SUBSCRIPTION_NOT_FOUND'));
      return;
    }
    next(error);
  }
}

/**
 * POST /api/subscriptions/downgrade/select-group
 * User in over_limit state selects one group to keep.
 */
export async function downgradeSelectGroup(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }
    const data = DowngradeSelectGroupSchema.parse(req.body);
    const result = await selectGroupOnDowngrade(req.user.id, data.keep_group_id);
    res.status(200).json({
      status: result.status,
      kept_group: result.kept_group,
      removed_groups: result.removed_groups,
      read_only_access_until: result.read_only_access_until.toISOString(),
    });
  } catch (error) {
    if (error instanceof Error) {
      if (error.message === 'User not found') {
        next(new ApplicationError('User not found', 404, 'NOT_FOUND'));
        return;
      }
      if (error.message === 'User is not in over_limit state') {
        next(new ApplicationError('User is not in over_limit state', 400, 'INVALID_STATE'));
        return;
      }
      if (error.message === 'User is not a member of the selected group') {
        next(new ApplicationError('User is not a member of the selected group', 400, 'INVALID_GROUP'));
        return;
      }
    }
    next(error);
  }
}
