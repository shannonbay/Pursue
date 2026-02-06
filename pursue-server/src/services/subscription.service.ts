import { sql } from 'kysely';
import { db } from '../database/index.js';

const FREE_GROUP_LIMIT = 1;
const PREMIUM_GROUP_LIMIT = 10;
const FREE_EXPORT_DAYS = 30;
const PREMIUM_EXPORT_DAYS = 365;
const ABSOLUTE_MAX_EXPORT_DAYS = 730; // 24 months

export type Tier = 'free' | 'premium';
export type SubscriptionStatus = 'active' | 'cancelled' | 'expired' | 'grace_period' | 'over_limit';

export interface UserSubscriptionState {
  userId: string;
  current_subscription_tier: Tier;
  subscription_status: SubscriptionStatus;
  group_limit: number;
  current_group_count: number;
}

export interface SubscriptionEligibility {
  can_create_group: boolean;
  can_join_group: boolean;
  reason?: 'at_group_limit' | 'over_limit' | string;
  current_count: number;
  limit: number;
  upgrade_required: boolean;
}

export interface ExportRangeValidation {
  valid: boolean;
  max_days_allowed: number;
  requested_days: number;
  subscription_tier: Tier;
  error?: string;
  message?: string;
}

export interface CanUserWriteInGroupResult {
  allowed: boolean;
  reason?: 'group_selection_required' | 'read_only';
  read_only_until?: Date;
}

/**
 * Count active group memberships for a user (status = 'active' only).
 * Pending/declined do not count per spec.
 */
export async function getActiveGroupCount(userId: string): Promise<number> {
  const row = await db
    .selectFrom('group_memberships')
    .where('user_id', '=', userId)
    .where('status', '=', 'active')
    .select(db.fn.count('id').as('count'))
    .executeTakeFirst();
  return Number(row?.count ?? 0);
}

/**
 * Get user's subscription state. Uses stored group_limit/current_subscription_tier
 * and recomputes current_group_count from active memberships.
 */
export async function getUserSubscriptionState(userId: string): Promise<UserSubscriptionState | null> {
  const user = await db
    .selectFrom('users')
    .select([
      'id',
      'current_subscription_tier',
      'subscription_status',
      'group_limit',
      'current_group_count',
    ])
    .where('id', '=', userId)
    .where('deleted_at', 'is', null)
    .executeTakeFirst();

  if (!user) return null;

  const current_group_count = await getActiveGroupCount(userId);
  const tier = (user.current_subscription_tier as Tier) ?? 'free';
  let status = (user.subscription_status as SubscriptionStatus) ?? 'active';
  const group_limit = Number(user.group_limit ?? FREE_GROUP_LIMIT);

  // Sync over_limit: free user with more groups than limit should be over_limit,
  // unless they have already resolved it by selecting a group to keep
  // AND they are still an active member of that kept group
  if (tier === 'free' && current_group_count > group_limit && status !== 'over_limit') {
    const handledDowngrade = await db
      .selectFrom('subscription_downgrade_history')
      .select(['id', 'kept_group_id'])
      .where('user_id', '=', userId)
      .where('kept_group_id', 'is not', null)
      .orderBy('downgrade_date', 'desc')
      .limit(1)
      .executeTakeFirst();

    let needsNewSelection = !handledDowngrade;

    // If there was a previous selection, verify user is still a member of that group
    if (handledDowngrade?.kept_group_id) {
      const stillMember = await db
        .selectFrom('group_memberships')
        .select('id')
        .where('user_id', '=', userId)
        .where('group_id', '=', handledDowngrade.kept_group_id)
        .where('status', '=', 'active')
        .executeTakeFirst();

      if (!stillMember) {
        needsNewSelection = true;
      }
    }

    if (needsNewSelection) {
      await updateSubscriptionStatus(userId);
      status = 'over_limit';
    }
  }

  return {
    userId: user.id,
    current_subscription_tier: tier,
    subscription_status: status,
    group_limit,
    current_group_count,
  };
}

/**
 * Sync user row subscription fields from current subscription record and active group count.
 */
export async function updateSubscriptionStatus(userId: string): Promise<void> {
  const activeCount = await getActiveGroupCount(userId);
  const sub = await db
    .selectFrom('user_subscriptions')
    .select(['id', 'tier', 'status', 'expires_at'])
    .where('user_id', '=', userId)
    .orderBy('started_at', 'desc')
    .executeTakeFirst();

  if (!sub || sub.tier === 'free' || sub.status === 'expired' || sub.status === 'cancelled') {
    const expiresAt = sub?.expires_at ? new Date(sub.expires_at) : null;
    const now = new Date();
    const isExpired = expiresAt && now > expiresAt;
    const tier: Tier = 'free';
    const status: SubscriptionStatus = activeCount > FREE_GROUP_LIMIT ? 'over_limit' : (isExpired ? 'expired' : 'active');
    await db
      .updateTable('users')
      .set({
        current_subscription_tier: tier,
        subscription_status: status,
        group_limit: FREE_GROUP_LIMIT,
        current_group_count: activeCount,
        updated_at: sql`NOW()`,
      })
      .where('id', '=', userId)
      .execute();
    if (sub && (sub.status === 'active' || sub.status === 'grace_period') && expiresAt && now > expiresAt) {
      await db
        .updateTable('user_subscriptions')
        .set({ status: 'expired', updated_at: sql`NOW()` })
        .where('id', '=', sub.id)
        .execute();
    }
    return;
  }

  const expiresAt = sub.expires_at ? new Date(sub.expires_at) : null;
  const now = new Date();
  if (expiresAt && now > expiresAt) {
    await db
      .updateTable('user_subscriptions')
      .set({ status: 'expired', updated_at: sql`NOW()` })
      .where('id', '=', sub.id)
      .execute();
    await db
      .updateTable('users')
      .set({
        current_subscription_tier: 'free',
        subscription_status: activeCount > FREE_GROUP_LIMIT ? 'over_limit' : 'expired',
        group_limit: FREE_GROUP_LIMIT,
        current_group_count: activeCount,
        updated_at: sql`NOW()`,
      })
      .where('id', '=', userId)
      .execute();
    return;
  }

  await db
    .updateTable('users')
    .set({
      current_subscription_tier: 'premium',
      subscription_status: 'active',
      group_limit: PREMIUM_GROUP_LIMIT,
      current_group_count: activeCount,
      updated_at: sql`NOW()`,
    })
    .where('id', '=', userId)
    .execute();
}

/**
 * Check if user can create or join a group (under group limit).
 */
export async function canUserJoinOrCreateGroup(userId: string): Promise<{ allowed: boolean; reason?: string }> {
  const state = await getUserSubscriptionState(userId);
  if (!state) return { allowed: false, reason: 'user_not_found' };
  if (state.current_group_count >= state.group_limit) {
    return {
      allowed: false,
      reason: state.current_subscription_tier === 'free' ? 'free_tier_limit_reached' : 'premium_tier_limit_reached',
    };
  }
  return { allowed: true };
}

/**
 * Get eligibility for creating/joining groups (for GET /users/me/subscription/eligibility).
 */
export async function getSubscriptionEligibility(userId: string): Promise<SubscriptionEligibility | null> {
  const state = await getUserSubscriptionState(userId);
  if (!state) return null;
  const atLimit = state.current_group_count >= state.group_limit;
  const reason = state.subscription_status === 'over_limit' ? 'over_limit' : (atLimit ? 'at_group_limit' : undefined);
  return {
    can_create_group: !atLimit,
    can_join_group: !atLimit,
    reason,
    current_count: state.current_group_count,
    limit: state.group_limit,
    upgrade_required: atLimit && state.current_subscription_tier === 'free',
  };
}

const READ_ONLY_DAYS_AFTER_DOWNGRADE = 30;

/**
 * Check if user can perform write actions in a group (progress, goals, etc.).
 * Over_limit users: no writes until they select a group; after selection only the kept group is writable.
 */
export async function canUserWriteInGroup(
  userId: string,
  groupId: string
): Promise<CanUserWriteInGroupResult> {
  const state = await getUserSubscriptionState(userId);
  if (!state) return { allowed: false };

  // If user is in over_limit state and hasn't selected a group yet, block all writes
  if (state.subscription_status === 'over_limit') {
    const latest = await db
      .selectFrom('subscription_downgrade_history')
      .select(['kept_group_id'])
      .where('user_id', '=', userId)
      .where('kept_group_id', 'is not', null)
      .orderBy('downgrade_date', 'desc')
      .limit(1)
      .executeTakeFirst();

    if (!latest) {
      return { allowed: false, reason: 'group_selection_required' };
    }
  }

  // For free-tier users with more groups than their limit, enforce read-only
  // on non-kept groups after downgrade selection
  if (state.current_subscription_tier !== 'free' || state.current_group_count <= state.group_limit) {
    return { allowed: true };
  }

  const latest = await db
    .selectFrom('subscription_downgrade_history')
    .select(['kept_group_id', 'downgrade_date'])
    .where('user_id', '=', userId)
    .orderBy('downgrade_date', 'desc')
    .executeTakeFirst();

  if (!latest || latest.kept_group_id === null) {
    return { allowed: true };
  }
  if (latest.kept_group_id === groupId) return { allowed: true };

  const downgradeDate = latest.downgrade_date instanceof Date
    ? latest.downgrade_date
    : new Date(latest.downgrade_date);
  const read_only_until = new Date(downgradeDate);
  read_only_until.setDate(read_only_until.getDate() + READ_ONLY_DAYS_AFTER_DOWNGRADE);
  return { allowed: false, reason: 'read_only', read_only_until };
}

/**
 * Validate export date range by tier (free: 30 days, premium: 365 days, absolute max 730).
 */
export async function validateExportDateRange(
  userId: string,
  startDate: string,
  endDate: string
): Promise<ExportRangeValidation | null> {
  const state = await getUserSubscriptionState(userId);
  if (!state) return null;

  const start = new Date(startDate);
  const end = new Date(endDate);
  const requestedDays = Math.floor((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)) + 1;
  const tier = state.current_subscription_tier;
  const maxDaysAllowed = tier === 'premium' ? PREMIUM_EXPORT_DAYS : FREE_EXPORT_DAYS;

  if (requestedDays > ABSOLUTE_MAX_EXPORT_DAYS) {
    return {
      valid: false,
      max_days_allowed: ABSOLUTE_MAX_EXPORT_DAYS,
      requested_days: requestedDays,
      subscription_tier: tier,
      error: 'absolute_maximum_exceeded',
      message: 'Export range cannot exceed 24 months.',
    };
  }
  if (requestedDays > maxDaysAllowed) {
    return {
      valid: false,
      max_days_allowed: maxDaysAllowed,
      requested_days: requestedDays,
      subscription_tier: tier,
      error: 'date_range_exceeds_tier_limit',
      message:
        tier === 'free'
          ? 'Free tier users can export up to 30 days of data. Upgrade to Premium for up to 12 months.'
          : 'Export range exceeds 12 months.',
    };
  }
  return {
    valid: true,
    max_days_allowed: maxDaysAllowed,
    requested_days: requestedDays,
    subscription_tier: tier,
  };
}

/**
 * Get current active premium subscription for user (not expired, not cancelled).
 */
async function getCurrentActiveSubscription(userId: string) {
  const now = new Date();
  return db
    .selectFrom('user_subscriptions')
    .selectAll()
    .where('user_id', '=', userId)
    .where('tier', '=', 'premium')
    .where('status', 'in', ['active', 'grace_period'])
    .where((eb) => eb.or([eb('expires_at', 'is', null), eb('expires_at', '>', now)]))
    .orderBy('started_at', 'desc')
    .executeTakeFirst();
}

/**
 * Create or update premium subscription (e.g. after successful purchase verification).
 * productId expected: pursue_premium_annual. Sets expires_at to 1 year from now.
 */
export async function createOrRenewPremiumSubscription(
  userId: string,
  params: {
    platform: 'google_play' | 'app_store';
    purchase_token: string;
    product_id: string;
    platform_subscription_id?: string;
  }
): Promise<{ subscription_id: string; tier: Tier; status: string; expires_at: Date; group_limit: number }> {
  const now = new Date();
  const expiresAt = new Date(now);
  expiresAt.setFullYear(expiresAt.getFullYear() + 1);

  const existing = await getCurrentActiveSubscription(userId);
  if (existing) {
    await db
      .updateTable('user_subscriptions')
      .set({
        platform_purchase_token: params.purchase_token,
        platform_subscription_id: params.platform_subscription_id ?? existing.platform_subscription_id,
        expires_at: expiresAt,
        status: 'active',
        auto_renew: true,
        updated_at: sql`NOW()`,
      })
      .where('id', '=', existing.id)
      .execute();
    await updateSubscriptionStatus(userId);
    return {
      subscription_id: existing.id,
      tier: 'premium',
      status: 'active',
      expires_at: expiresAt,
      group_limit: PREMIUM_GROUP_LIMIT,
    };
  }

  const [inserted] = await db
    .insertInto('user_subscriptions')
    .values({
      user_id: userId,
      tier: 'premium',
      status: 'active',
      started_at: sql`NOW()`,
      expires_at: expiresAt,
      platform: params.platform,
      platform_subscription_id: params.platform_subscription_id ?? null,
      platform_purchase_token: params.purchase_token,
      auto_renew: true,
    })
    .returning(['id', 'expires_at'])
    .execute();

  await updateSubscriptionStatus(userId);
  return {
    subscription_id: inserted.id,
    tier: 'premium',
    status: 'active',
    expires_at: inserted.expires_at as Date,
    group_limit: PREMIUM_GROUP_LIMIT,
  };
}

/**
 * Verify purchase (e.g. with Google Play API). This implementation records the subscription
 * without external API call for testing; production should call Google Play and then create/renew.
 */
export async function verifyPurchase(
  userId: string,
  params: { platform: 'google_play' | 'app_store'; purchase_token: string; product_id: string }
): Promise<{ subscription_id: string; tier: Tier; status: string; expires_at: Date; group_limit: number }> {
  if (params.product_id !== 'pursue_premium_annual') {
    throw new Error('Invalid product_id');
  }
  return createOrRenewPremiumSubscription(userId, {
    platform: params.platform,
    purchase_token: params.purchase_token,
    product_id: params.product_id,
  });
}

/**
 * Cancel subscription (access until expires_at).
 */
export async function cancelSubscription(userId: string): Promise<{
  status: string;
  access_until: Date;
  auto_renew: boolean;
  message: string;
}> {
  const sub = await getCurrentActiveSubscription(userId);
  if (!sub) {
    throw new Error('No active premium subscription found');
  }
  const now = new Date();
  await db
    .updateTable('user_subscriptions')
    .set({
      status: 'cancelled',
      cancelled_at: now,
      auto_renew: false,
      updated_at: sql`NOW()`,
    })
    .where('id', '=', sub.id)
    .execute();
  const expiresAt = sub.expires_at as Date;
  const msg = `Your subscription will remain active until ${expiresAt.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}`;
  return {
    status: 'cancelled',
    access_until: expiresAt,
    auto_renew: false,
    message: msg,
  };
}

/**
 * Downgrade: user selects one group to keep; record history and update membership for others.
 * Other groups: membership remains but can be treated as read-only until 30 days (enforced elsewhere).
 */
export async function selectGroupOnDowngrade(
  userId: string,
  keepGroupId: string
): Promise<{
  status: string;
  kept_group: { id: string; name: string };
  removed_groups: Array<{ id: string; name: string }>;
  read_only_access_until: Date;
}> {
  const state = await getUserSubscriptionState(userId);
  if (!state) throw new Error('User not found');
  if (state.subscription_status !== 'over_limit') {
    throw new Error('User is not in over_limit state');
  }

  const activeMemberships = await db
    .selectFrom('group_memberships')
    .innerJoin('groups', 'groups.id', 'group_memberships.group_id')
    .select(['group_memberships.group_id', 'groups.name'])
    .where('group_memberships.user_id', '=', userId)
    .where('group_memberships.status', '=', 'active')
    .execute();

  const kept = activeMemberships.find((m) => m.group_id === keepGroupId);
  if (!kept) throw new Error('User is not a member of the selected group');

  const removed = activeMemberships.filter((m) => m.group_id !== keepGroupId);
  const removedIds = removed.map((m) => m.group_id);

  const readOnlyUntil = new Date();
  readOnlyUntil.setDate(readOnlyUntil.getDate() + 30);

  await db.transaction().execute(async (trx) => {
    await trx
      .insertInto('subscription_downgrade_history')
      .values({
        user_id: userId,
        previous_tier: state.current_subscription_tier,
        groups_before_downgrade: state.current_group_count,
        kept_group_id: keepGroupId,
        removed_group_ids: removedIds,
      })
      .execute();

    await trx
      .updateTable('users')
      .set({
        subscription_status: 'active',
        current_group_count: 1,
        group_limit: FREE_GROUP_LIMIT,
        updated_at: sql`NOW()`,
      })
      .where('id', '=', userId)
      .execute();
  });

  return {
    status: 'success',
    kept_group: { id: keepGroupId, name: kept.name },
    removed_groups: removed.map((m) => ({ id: m.group_id, name: m.name })),
    read_only_access_until: readOnlyUntil,
  };
}
