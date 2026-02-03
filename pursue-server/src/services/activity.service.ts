import { db } from '../database/index.js';

export const ACTIVITY_TYPES = {
  GROUP_CREATED: 'group_created',
  MEMBER_JOINED: 'member_joined',
  MEMBER_LEFT: 'member_left',
  MEMBER_PROMOTED: 'member_promoted',
  MEMBER_REMOVED: 'member_removed',
  GOAL_ADDED: 'goal_added',
  GOAL_ARCHIVED: 'goal_archived',
  GROUP_RENAMED: 'group_renamed',
  PROGRESS_LOGGED: 'progress_logged',
  JOIN_REQUEST: 'join_request',
  MEMBER_APPROVED: 'member_approved',
  MEMBER_DECLINED: 'member_declined',
  INVITE_CODE_REGENERATED: 'invite_code_regenerated'
} as const;

/**
 * Create a group_activity entry.
 */
export async function createGroupActivity(
  groupId: string,
  activityType: string,
  userId: string | null,
  metadata?: Record<string, unknown>
): Promise<void> {
  await db
    .insertInto('group_activities')
    .values({
      group_id: groupId,
      user_id: userId,
      activity_type: activityType,
      metadata: metadata ?? null
    })
    .execute();
}
