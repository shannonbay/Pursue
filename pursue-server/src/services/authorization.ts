import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface GroupMembershipResult {
  role: string;
}

export interface GoalRow {
  id: string;
  group_id: string;
  title: string;
  description: string | null;
  cadence: string;
  metric_type: string;
  created_at: Date;
}

/**
 * Ensure goal exists and is not soft-deleted. Throws 404 if invalid UUID, not found, or deleted.
 */
export async function ensureGoalExists(goalId: string): Promise<GoalRow> {
  if (!UUID_REGEX.test(goalId)) {
    throw new ApplicationError('Goal not found', 404, 'NOT_FOUND');
  }
  const goal = await db
    .selectFrom('goals')
    .select(['id', 'group_id', 'title', 'description', 'cadence', 'metric_type', 'created_at'])
    .where('id', '=', goalId)
    .where('deleted_at', 'is', null)
    .executeTakeFirst();
  if (!goal) {
    throw new ApplicationError('Goal not found', 404, 'NOT_FOUND');
  }
  return goal;
}

/**
 * Ensure group exists. Throws 404 if not found.
 */
export async function ensureGroupExists(groupId: string): Promise<void> {
  const group = await db
    .selectFrom('groups')
    .select('id')
    .where('id', '=', groupId)
    .executeTakeFirst();
  if (!group) {
    throw new ApplicationError('Group not found', 404, 'NOT_FOUND');
  }
}

/**
 * Get user's membership in a group. Returns null if not a member.
 */
export async function getGroupMembership(
  userId: string,
  groupId: string
): Promise<GroupMembershipResult | null> {
  const membership = await db
    .selectFrom('group_memberships')
    .select(['role'])
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();

  return membership || null;
}

/**
 * Ensure user is a member of the group. Throws 403 if not.
 */
export async function requireGroupMember(
  userId: string,
  groupId: string
): Promise<GroupMembershipResult> {
  const membership = await getGroupMembership(userId, groupId);
  if (!membership) {
    throw new ApplicationError('Not a member of this group', 403, 'FORBIDDEN');
  }
  return membership;
}

/**
 * Ensure user is an active member of the group. Throws 403 if not a member or status is not active (e.g. pending).
 * When status is pending, throws with code PENDING_APPROVAL so the client can show appropriate messaging.
 */
export async function requireActiveGroupMember(
  userId: string,
  groupId: string
): Promise<GroupMembershipResult> {
  const membership = await db
    .selectFrom('group_memberships')
    .select(['role', 'status'])
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();

  if (!membership) {
    throw new ApplicationError('Not a member of this group', 403, 'FORBIDDEN');
  }
  if (membership.status === 'pending') {
    throw new ApplicationError('Membership is pending approval', 403, 'PENDING_APPROVAL');
  }
  if (membership.status !== 'active') {
    throw new ApplicationError('Not a member of this group', 403, 'FORBIDDEN');
  }
  return { role: membership.role };
}

/**
 * Ensure user is admin or creator. Throws 403 if not.
 */
export async function requireGroupAdmin(
  userId: string,
  groupId: string
): Promise<GroupMembershipResult> {
  const membership = await requireGroupMember(userId, groupId);
  if (membership.role !== 'admin' && membership.role !== 'creator') {
    throw new ApplicationError(
      'Admin or creator role required',
      403,
      'FORBIDDEN'
    );
  }
  return membership;
}

/**
 * Ensure user is the group creator. Throws 403 if not.
 */
export async function requireGroupCreator(
  userId: string,
  groupId: string
): Promise<GroupMembershipResult> {
  const membership = await requireGroupMember(userId, groupId);
  if (membership.role !== 'creator') {
    throw new ApplicationError(
      'Creator role required',
      403,
      'FORBIDDEN'
    );
  }
  return membership;
}
