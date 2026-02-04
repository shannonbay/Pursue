import type { Response, NextFunction, Request } from 'express';
import multer, { type FileFilterCallback } from 'multer';
import { sql } from 'kysely';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import {
  CreateGroupSchema,
  UpdateGroupSchema,
  UpdateMemberRoleSchema,
  JoinGroupSchema,
  ExportProgressQuerySchema,
} from '../validations/groups.js';
import {
  ensureGroupExists,
  getGroupMembership,
  requireGroupMember,
  requireGroupAdmin,
  requireGroupCreator,
  requireActiveGroupMember,
} from '../services/authorization.js';
import { createGroupActivity, ACTIVITY_TYPES } from '../services/activity.service.js';
import { sendGroupNotification, sendNotificationToUser, sendPushNotification, sendToTopic, buildTopicName } from '../services/fcm.service.js';
import { uploadGroupIcon, deleteGroupIcon } from '../services/storage.service.js';
import {
  sanitizeSheetName,
  sanitizeFilename,
  addLetterhead,
  generateSummarySection,
  generateCalendarSection,
  type ExportGoal,
  type ExportProgressEntry,
} from '../services/exportProgress.service.js';
import { logger } from '../utils/logger.js';
import ExcelJS from 'exceljs';
import path from 'node:path';
import fs from 'node:fs/promises';

// Configure multer for icon uploads
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 }, // 5 MB
  fileFilter: (_req: Request, file: Express.Multer.File, cb: FileFilterCallback) => {
    const allowedTypes = ['image/png', 'image/jpeg', 'image/webp'];
    if (allowedTypes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new ApplicationError('Invalid file type. Only PNG, JPG, WebP allowed', 400, 'INVALID_FILE_TYPE'));
    }
  },
});

/**
 * Generate unique invite code: PURSUE-XXXXXX-XXXXXX (no 0,O,1,I to avoid confusion)
 */
function generateInviteCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const randomPart = () => {
    let result = '';
    for (let i = 0; i < 6; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
  };
  return `PURSUE-${randomPart()}-${randomPart()}`;
}

// POST /api/groups
export async function createGroup(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = CreateGroupSchema.parse(req.body);
    const userId = req.user!.id;

    const result = await db.transaction().execute(async (trx) => {
      // 1. Create group
      const group = await trx
        .insertInto('groups')
        .values({
          name: data.name,
          description: data.description ?? null,
          icon_emoji: data.icon_emoji ?? null,
          icon_color: data.icon_color ?? null,
          creator_user_id: userId,
        })
        .returning([
          'id',
          'name',
          'description',
          'icon_emoji',
          'icon_color',
          'creator_user_id',
          'created_at',
        ])
        .executeTakeFirstOrThrow();

      // 2. Add creator as member with role 'creator'
      await trx
        .insertInto('group_memberships')
        .values({
          group_id: group.id,
          user_id: userId,
          role: 'creator',
        })
        .execute();

      // 3. Generate unique invite code (one active code per group)
      let inviteCode: string;
      let attempts = 0;
      do {
        inviteCode = generateInviteCode();
        const existing = await trx
          .selectFrom('invite_codes')
          .select('id')
          .where('code', '=', inviteCode)
          .executeTakeFirst();
        if (!existing) break;
        attempts++;
        if (attempts > 10) {
          throw new ApplicationError('Failed to generate unique invite code', 500, 'CODE_GENERATION_FAILED');
        }
      } while (true);

      await trx
        .insertInto('invite_codes')
        .values({
          group_id: group.id,
          code: inviteCode,
          created_by_user_id: userId,
          revoked_at: null,
        })
        .execute();

      // 4. Create initial goals if provided
      if (data.initial_goals && data.initial_goals.length > 0) {
        await trx
          .insertInto('goals')
          .values(
            data.initial_goals.map((goal) => ({
              group_id: group.id,
              title: goal.title,
              description: goal.description ?? null,
              cadence: goal.cadence,
              metric_type: goal.metric_type,
              target_value: goal.target_value ?? null,
              unit: goal.unit ?? null,
              created_by_user_id: userId,
            }))
          )
          .execute();
      }

      // 5. Create activity entry (with invite_code in metadata)
      await trx
        .insertInto('group_activities')
        .values({
          group_id: group.id,
          user_id: userId,
          activity_type: ACTIVITY_TYPES.GROUP_CREATED,
          metadata: { group_name: group.name, invite_code: inviteCode },
        })
        .execute();

      return { group, inviteCode };
    });

    const memberCount = await db
      .selectFrom('group_memberships')
      .select(db.fn.count('id').as('count'))
      .where('group_id', '=', result.group.id)
      .executeTakeFirstOrThrow();

    res.status(201).json({
      id: result.group.id,
      name: result.group.name,
      description: result.group.description,
      icon_emoji: result.group.icon_emoji,
      icon_color: result.group.icon_color,
      has_icon: false,
      creator_user_id: result.group.creator_user_id,
      member_count: Number(memberCount.count),
      created_at: result.group.created_at,
      invite_code: result.inviteCode,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id
export async function getGroup(
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

    // Check membership
    const membership = await requireGroupMember(req.user.id, group_id);

    // Get group with member count (active members only)
    const result = await db
      .selectFrom('groups')
      .leftJoin('group_memberships', (join) =>
        join
          .onRef('groups.id', '=', 'group_memberships.group_id')
          .on('group_memberships.status', '=', 'active')
      )
      .select([
        'groups.id',
        'groups.name',
        'groups.description',
        'groups.icon_emoji',
        'groups.icon_color',
        'groups.creator_user_id',
        'groups.created_at',
        db.fn.count('group_memberships.id').as('member_count'),
        sql<boolean>`groups.icon_data IS NOT NULL`.as('has_icon'),
      ])
      .where('groups.id', '=', group_id)
      .groupBy('groups.id')
      .executeTakeFirstOrThrow();

    res.status(200).json({
      id: result.id,
      name: result.name,
      description: result.description,
      icon_emoji: result.icon_emoji,
      icon_color: result.icon_color,
      has_icon: Boolean(result.has_icon),
      creator_user_id: result.creator_user_id,
      member_count: Number(result.member_count),
      created_at: result.created_at,
      user_role: membership.role,
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/groups/:group_id
export async function updateGroup(
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
    await requireGroupAdmin(req.user.id, group_id);

    const data = UpdateGroupSchema.parse(req.body);

    // Build update object (only include provided fields)
    const updates: Record<string, unknown> = {};
    if (data.name !== undefined) updates.name = data.name;
    if (data.description !== undefined) updates.description = data.description ?? null;
    if (data.icon_emoji !== undefined) updates.icon_emoji = data.icon_emoji ?? null;
    if (data.icon_color !== undefined) updates.icon_color = data.icon_color ?? null;

    if (Object.keys(updates).length === 0) {
      // No updates, return current group
      const group = await db
        .selectFrom('groups')
        .select([
          'id',
          'name',
          'description',
          'icon_emoji',
          'icon_color',
          'updated_at',
          sql<boolean>`icon_data IS NOT NULL`.as('has_icon'),
        ])
        .where('id', '=', group_id)
        .executeTakeFirstOrThrow();
      res.status(200).json({
        id: group.id,
        name: group.name,
        description: group.description,
        icon_emoji: group.icon_emoji,
        icon_color: group.icon_color,
        has_icon: Boolean(group.has_icon),
        updated_at: group.updated_at,
      });
      return;
    }

    // Get old name before update if name is changing
    let oldName: string | undefined;
    if (data.name !== undefined) {
      const oldGroup = await db
        .selectFrom('groups')
        .select('name')
        .where('id', '=', group_id)
        .executeTakeFirstOrThrow();
      oldName = oldGroup.name;
    }

    const group = await db
      .updateTable('groups')
      .set(updates)
      .where('id', '=', group_id)
      .returning([
        'id',
        'name',
        'description',
        'icon_emoji',
        'icon_color',
        'updated_at',
      ])
      .executeTakeFirstOrThrow();

    // Get has_icon separately
    const hasIconResult = await db
      .selectFrom('groups')
      .select(sql<boolean>`icon_data IS NOT NULL`.as('has_icon'))
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    // Create activity if name changed
    if (data.name !== undefined && oldName !== undefined) {
      await createGroupActivity(group_id, ACTIVITY_TYPES.GROUP_RENAMED, req.user.id, {
        old_name: oldName,
        new_name: data.name,
      });
    }

    res.status(200).json({
      id: group.id,
      name: group.name,
      description: group.description,
      icon_emoji: group.icon_emoji,
      icon_color: group.icon_color,
      has_icon: Boolean(hasIconResult.has_icon),
      updated_at: group.updated_at,
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/groups/:group_id/icon
export const uploadIcon = [
  upload.single('icon'),
  async (req: AuthRequest, res: Response, next: NextFunction): Promise<void> => {
    try {
      if (!req.user) {
        throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
      }

      const group_id = String(req.params.group_id);
      await ensureGroupExists(group_id);
      await requireGroupAdmin(req.user.id, group_id);

      const file = (req as Request & { file?: Express.Multer.File }).file;
      if (!file) {
        throw new ApplicationError('Icon file is required', 400, 'MISSING_FILE');
      }

      // Upload new icon (stores in icon_data, clears icon_emoji and icon_color)
      await uploadGroupIcon(group_id, file.buffer);

      // Get updated group
      const group = await db
        .selectFrom('groups')
        .select([
          'id',
          'icon_emoji',
          'icon_color',
          'updated_at',
          sql<boolean>`icon_data IS NOT NULL`.as('has_icon'),
        ])
        .where('id', '=', group_id)
        .executeTakeFirstOrThrow();

      res.status(200).json({
        id: group.id,
        has_icon: Boolean(group.has_icon),
        icon_emoji: group.icon_emoji,
        icon_color: group.icon_color,
        updated_at: group.updated_at,
      });
    } catch (error) {
      next(error);
    }
  },
];

// GET /api/groups/:group_id/icon
export async function getGroupIcon(
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

    const group = await db
      .selectFrom('groups')
      .select(['icon_data', 'icon_mime_type', 'updated_at'])
      .where('id', '=', group_id)
      .executeTakeFirst();

    if (!group?.icon_data) {
      res.status(404).json({ error: { message: 'No icon', code: 'NOT_FOUND' } });
      return;
    }

    res.set('Content-Type', group.icon_mime_type || 'image/webp');
    res.set('Cache-Control', 'public, max-age=86400');
    const etag = `"icon-${group_id}-${group.updated_at.getTime()}"`;
    res.set('ETag', etag);

    if (req.get('If-None-Match') === etag) {
      res.status(304).end();
      return;
    }

    res.send(group.icon_data);
  } catch (error) {
    next(error);
  }
}

// DELETE /api/groups/:group_id/icon
export async function deleteIcon(
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
    await requireGroupAdmin(req.user.id, group_id);

    // Delete icon from database
    await deleteGroupIcon(group_id);

      // Get updated group
      const group = await db
        .selectFrom('groups')
        .select([
          'id',
          'icon_emoji',
          'icon_color',
          'updated_at',
          sql<boolean>`icon_data IS NOT NULL`.as('has_icon'),
        ])
        .where('id', '=', group_id)
        .executeTakeFirstOrThrow();

      res.status(200).json({
        id: group.id,
        has_icon: Boolean(group.has_icon),
        icon_emoji: group.icon_emoji,
        icon_color: group.icon_color,
        updated_at: group.updated_at,
      });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/groups/:group_id
export async function deleteGroup(
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
    await requireGroupCreator(req.user.id, group_id);

    // Delete group (CASCADE handles related data, including icon_data)
    await db.deleteFrom('groups').where('id', '=', group_id).execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/members/pending
export async function listPendingMembers(
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
    await requireGroupAdmin(req.user.id, group_id);

    const members = await db
      .selectFrom('group_memberships')
      .innerJoin('users', 'group_memberships.user_id', 'users.id')
      .select([
        'group_memberships.user_id',
        'users.display_name',
        'group_memberships.joined_at',
        sql<boolean>`users.avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .where('group_memberships.group_id', '=', group_id)
      .where('group_memberships.status', '=', 'pending')
      .orderBy('group_memberships.joined_at', 'asc')
      .execute();

    res.status(200).json({
      pending_members: members.map((m) => ({
        user_id: m.user_id,
        display_name: m.display_name,
        has_avatar: Boolean(m.has_avatar),
        requested_at: m.joined_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:group_id/members/:user_id/approve
export async function approveMember(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const user_id = String(req.params.user_id);
    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    // Check membership exists and is pending
    const membership = await db
      .selectFrom('group_memberships')
      .select(['status'])
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .executeTakeFirst();

    if (!membership) {
      throw new ApplicationError('Member not found', 404, 'NOT_FOUND');
    }

    if (membership.status !== 'pending') {
      throw new ApplicationError('No pending request found', 404, 'NOT_FOUND');
    }

    // Update status to active
    await db
      .updateTable('group_memberships')
      .set({ status: 'active' })
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .execute();

    const group = await db
      .selectFrom('groups')
      .select(['name'])
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    const approvedUser = await db
      .selectFrom('users')
      .select(['display_name'])
      .where('id', '=', user_id)
      .executeTakeFirstOrThrow();

    // Create activity (user = admin who approved; metadata = approved user for feed display)
    await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_APPROVED, req.user.id, {
      approved_user_id: user_id,
      approved_user_display_name: approvedUser.display_name,
    });

    // Send FCM to approved user
    const approvedUserDevices = await db
      .selectFrom('devices')
      .select(['fcm_token'])
      .where('user_id', '=', user_id)
      .execute();

    if (approvedUserDevices.length > 0) {
      for (const device of approvedUserDevices) {
        sendPushNotification(device.fcm_token, 'Request Approved', `You've been approved to join ${group.name}`, {
          type: 'member_approved',
          group_id,
        }).catch((error) => {
          logger.error('Failed to send approval notification', { error, user_id });
        });
      }
    }

    // Send FCM to group_events topic subscribers
    await sendToTopic(
      buildTopicName(group_id, 'group_events'),
      {
        title: group.name,
        body: `${approvedUser.display_name} joined the group`,
      },
      {
        type: 'member_approved',
        group_id,
        user_id,
      }
    );

    res.status(200).json({
      success: true,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:group_id/members/:user_id/decline
export async function declineMember(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const user_id = String(req.params.user_id);
    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    // Check membership exists and is pending
    const membership = await db
      .selectFrom('group_memberships')
      .select(['status'])
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .executeTakeFirst();

    if (!membership) {
      throw new ApplicationError('Member not found', 404, 'NOT_FOUND');
    }

    if (membership.status !== 'pending') {
      throw new ApplicationError('No pending request found', 404, 'NOT_FOUND');
    }

    // Update status to declined
    await db
      .updateTable('group_memberships')
      .set({ status: 'declined' })
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .execute();

    const declinedUser = await db
      .selectFrom('users')
      .select(['display_name'])
      .where('id', '=', user_id)
      .executeTakeFirstOrThrow();

    const group = await db
      .selectFrom('groups')
      .select(['name'])
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    // Create activity (user = admin who declined; metadata = declined user for feed display)
    await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_DECLINED, req.user.id, {
      declined_user_id: user_id,
      declined_user_display_name: declinedUser.display_name,
    });

    // Send FCM to declined user
    const declinedUserDevices = await db
      .selectFrom('devices')
      .select(['fcm_token'])
      .where('user_id', '=', user_id)
      .execute();

    if (declinedUserDevices.length > 0) {
      for (const device of declinedUserDevices) {
        sendPushNotification(device.fcm_token, 'Request Declined', `Your request to join ${group.name} was declined`, {
          type: 'member_declined',
          group_id,
        }).catch((error) => {
          logger.error('Failed to send decline notification', { error, user_id });
        });
      }
    }

    res.status(200).json({
      success: true,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/members
export async function listMembers(
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
    await requireActiveGroupMember(req.user.id, group_id);

    const members = await db
      .selectFrom('group_memberships')
      .innerJoin('users', 'group_memberships.user_id', 'users.id')
      .select([
        'group_memberships.user_id',
        'users.display_name',
        'group_memberships.role',
        'group_memberships.joined_at',
        sql<boolean>`users.avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .where('group_memberships.group_id', '=', group_id)
      .where('group_memberships.status', '=', 'active')
      .orderBy(sql`CASE group_memberships.role WHEN 'creator' THEN 1 WHEN 'admin' THEN 2 ELSE 3 END`, 'asc')
      .orderBy('group_memberships.joined_at', 'asc')
      .execute();

    res.status(200).json({
      members: members.map((m) => ({
        user_id: m.user_id,
        display_name: m.display_name,
        has_avatar: Boolean(m.has_avatar),
        role: m.role,
        joined_at: m.joined_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/groups/:group_id/members/:user_id
export async function updateMemberRole(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const user_id = String(req.params.user_id);
    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    const data = UpdateMemberRoleSchema.parse(req.body);

    // Cannot change own role
    if (user_id === req.user.id) {
      throw new ApplicationError('Cannot change own role', 400, 'CANNOT_CHANGE_OWN_ROLE');
    }

    // Check target user is a member
    const targetMembership = await getGroupMembership(user_id, group_id);
    if (!targetMembership) {
      throw new ApplicationError('User is not a member of this group', 404, 'NOT_FOUND');
    }

    // Cannot demote creator
    if (targetMembership.role === 'creator') {
      throw new ApplicationError('Cannot change creator role', 400, 'CANNOT_DEMOTE_CREATOR');
    }

    // Update role
    await db
      .updateTable('group_memberships')
      .set({ role: data.role })
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .execute();

    // Create activity
    await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_PROMOTED, req.user.id, {
      target_user_id: user_id,
      new_role: data.role,
    });

    res.status(200).json({ success: true });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/groups/:group_id/members/:user_id
export async function removeMember(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const user_id = String(req.params.user_id);
    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    // Check target user is a member
    const targetMembership = await getGroupMembership(user_id, group_id);
    if (!targetMembership) {
      throw new ApplicationError('User is not a member of this group', 404, 'NOT_FOUND');
    }

    // Cannot remove creator
    if (targetMembership.role === 'creator') {
      throw new ApplicationError('Cannot remove creator', 400, 'CANNOT_REMOVE_CREATOR');
    }

    // Get user display name for notification
    const user = await db
      .selectFrom('users')
      .select('display_name')
      .where('id', '=', user_id)
      .executeTakeFirst();

    // Delete membership
    await db
      .deleteFrom('group_memberships')
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .execute();

    // Create activity
    await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_REMOVED, req.user.id, {
      removed_user_id: user_id,
    });

    // Send FCM notification to group_events topic
    const group = await db
      .selectFrom('groups')
      .select('name')
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    await sendToTopic(
      buildTopicName(group_id, 'group_events'),
      {
        title: group.name,
        body: `${user?.display_name || 'A member'} was removed from the group`,
      },
      {
        type: 'member_removed',
        group_id,
        user_id,
      }
    );

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

const FORTY_EIGHT_HOURS_MS = 48 * 60 * 60 * 1000;

/**
 * Select one active member to promote when the last admin/creator leaves.
 * Returns user_id of the member to promote, or null if no candidates.
 * Selection: last activity (max of group_activities, progress in group, devices.last_active; fallback joined_at);
 * within 48h of top, tie-break by joined_at ascending (earliest joined).
 */
async function selectMemberToPromote(
  group_id: string,
  leaverId: string
): Promise<string | null> {
  const rows = await db
    .selectFrom('group_memberships as gm')
    .select([
      'gm.user_id',
      'gm.joined_at',
      sql<Date>`COALESCE(
        GREATEST(
          (SELECT MAX(ga.created_at) FROM group_activities ga WHERE ga.group_id = gm.group_id AND ga.user_id = gm.user_id),
          (SELECT MAX(pe.logged_at) FROM progress_entries pe INNER JOIN goals g ON pe.goal_id = g.id WHERE g.group_id = gm.group_id AND pe.user_id = gm.user_id),
          (SELECT MAX(d.last_active) FROM devices d WHERE d.user_id = gm.user_id)
        ),
        gm.joined_at
      )`.as('last_activity'),
    ])
    .where('gm.group_id', '=', group_id)
    .where('gm.status', '=', 'active')
    .where('gm.user_id', '!=', leaverId)
    .execute();

  if (rows.length === 0) return null;

  const sorted = [...rows].sort(
    (a, b) => new Date(b.last_activity).getTime() - new Date(a.last_activity).getTime()
  );
  const maxActivity = new Date(sorted[0].last_activity).getTime();
  const cutoff = maxActivity - FORTY_EIGHT_HOURS_MS;
  const within48h = sorted.filter((r) => new Date(r.last_activity).getTime() >= cutoff);
  const byJoined = [...within48h].sort(
    (a, b) => new Date(a.joined_at).getTime() - new Date(b.joined_at).getTime()
  );
  return byJoined[0].user_id;
}

// DELETE /api/groups/:group_id/members/me
export async function leaveGroup(
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

    const membership = await getGroupMembership(req.user.id, group_id);
    if (!membership) {
      throw new ApplicationError('Not a member of this group', 403, 'FORBIDDEN');
    }

    const isAdminOrCreator =
      membership.role === 'creator' || membership.role === 'admin';

    const [memberCountResult, adminCreatorCountResult] = await Promise.all([
      db
        .selectFrom('group_memberships')
        .select(db.fn.count('id').as('count'))
        .where('group_id', '=', group_id)
        .where('status', '=', 'active')
        .executeTakeFirstOrThrow(),
      db
        .selectFrom('group_memberships')
        .select(db.fn.count('id').as('count'))
        .where('group_id', '=', group_id)
        .where('status', '=', 'active')
        .where('role', 'in', ['creator', 'admin'])
        .executeTakeFirstOrThrow(),
    ]);

    const totalActive = Number(memberCountResult.count);
    const adminCreatorCount = Number(adminCreatorCountResult.count);

    if (
      isAdminOrCreator &&
      adminCreatorCount === 1 &&
      totalActive >= 2
    ) {
      const promotedUserId = await selectMemberToPromote(group_id, req.user.id);
      if (promotedUserId) {
        const newRole =
          membership.role === 'creator' ? 'creator' : 'admin';
        if (membership.role === 'creator') {
          await db
            .updateTable('groups')
            .set({ creator_user_id: promotedUserId })
            .where('id', '=', group_id)
            .execute();
        }
        await db
          .updateTable('group_memberships')
          .set({ role: newRole })
          .where('group_id', '=', group_id)
          .where('user_id', '=', promotedUserId)
          .execute();

        await createGroupActivity(
          group_id,
          ACTIVITY_TYPES.MEMBER_PROMOTED,
          promotedUserId,
          {
            promoted_user_id: promotedUserId,
            new_role: newRole,
            reason: 'auto_last_admin_left',
          }
        );

        const [groupRow, promotedUser] = await Promise.all([
          db
            .selectFrom('groups')
            .select('name')
            .where('id', '=', group_id)
            .executeTakeFirstOrThrow(),
          db
            .selectFrom('users')
            .select('display_name')
            .where('id', '=', promotedUserId)
            .executeTakeFirst(),
        ]);

        const groupName = groupRow.name;
        const displayName = promotedUser?.display_name ?? 'A member';
        const bodyToGroup =
          newRole === 'creator'
            ? `${displayName} is now the group creator`
            : `${displayName} is now an admin`;

        await sendToTopic(
          buildTopicName(group_id, 'group_events'),
          { title: groupName, body: bodyToGroup },
          {
            type: 'member_promoted',
            group_id,
            promoted_user_id: promotedUserId,
            new_role: newRole,
          }
        );

        const titleToUser =
          newRole === 'creator'
            ? "You're now the group creator"
            : "You're now an admin";
        const bodyToUser =
          newRole === 'creator'
            ? `You're now the creator of ${groupName}`
            : `You're now an admin of ${groupName}`;
        await sendNotificationToUser(
          promotedUserId,
          { title: titleToUser, body: bodyToUser },
          { type: 'member_promoted', group_id, new_role: newRole }
        );
      }
    }

    await db
      .deleteFrom('group_memberships')
      .where('group_id', '=', group_id)
      .where('user_id', '=', req.user.id)
      .execute();

    const remainingCount = await db
      .selectFrom('group_memberships')
      .select(db.fn.count('id').as('count'))
      .where('group_id', '=', group_id)
      .executeTakeFirstOrThrow();

    if (Number(remainingCount.count) === 0) {
      await db.deleteFrom('groups').where('id', '=', group_id).execute();
      res.status(204).send();
      return;
    }

    const group = await db
      .selectFrom('groups')
      .select('name')
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    await createGroupActivity(
      group_id,
      ACTIVITY_TYPES.MEMBER_LEFT,
      req.user.id
    );
    await sendToTopic(
      buildTopicName(group_id, 'group_events'),
      {
        title: group.name,
        body: `${req.user.email} left the group`,
      },
      {
        type: 'member_left',
        group_id,
        user_id: req.user.id,
      }
    );

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/invite
export async function getGroupInvite(
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

    const invite = await db
      .selectFrom('invite_codes')
      .select(['code', 'created_at'])
      .where('group_id', '=', group_id)
      .where('revoked_at', 'is', null)
      .executeTakeFirst();

    if (!invite) {
      throw new ApplicationError('No active invite code found', 404, 'NOT_FOUND');
    }

    res.status(200).json({
      invite_code: invite.code,
      share_url: `https://getpursue.app/join/${invite.code}`,
      created_at: invite.created_at,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:group_id/invite/regenerate
export async function regenerateInviteCode(
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
    await requireGroupAdmin(req.user.id, group_id);
    const userId = req.user!.id;

    const result = await db.transaction().execute(async (trx) => {
      const now = new Date();

      // 1. Revoke old active code
      const oldCode = await trx
        .updateTable('invite_codes')
        .set({ revoked_at: now })
        .where('group_id', '=', group_id)
        .where('revoked_at', 'is', null)
        .returning('code')
        .executeTakeFirst();

      // 2. Generate new unique code
      let newCode: string;
      let attempts = 0;
      do {
        newCode = generateInviteCode();
        const existing = await trx
          .selectFrom('invite_codes')
          .select('id')
          .where('code', '=', newCode)
          .executeTakeFirst();
        if (!existing) break;
        attempts++;
        if (attempts > 10) {
          throw new ApplicationError('Failed to generate unique invite code', 500, 'CODE_GENERATION_FAILED');
        }
      } while (true);

      // 3. Insert new code
      const invite = await trx
        .insertInto('invite_codes')
        .values({
          group_id,
          code: newCode,
          created_by_user_id: userId,
          revoked_at: null,
        })
        .returning(['code', 'created_at'])
        .executeTakeFirstOrThrow();

      // 4. Create activity log
      await trx
        .insertInto('group_activities')
        .values({
          group_id,
          user_id: userId,
          activity_type: ACTIVITY_TYPES.INVITE_CODE_REGENERATED,
          metadata: { old_code: oldCode?.code ?? null, new_code: invite.code },
        })
        .execute();

      return {
        invite_code: invite.code,
        created_at: invite.created_at,
        previous_code_revoked: oldCode?.code ?? null,
      };
    });

    res.status(200).json({
      invite_code: result.invite_code,
      share_url: `https://getpursue.app/join/${result.invite_code}`,
      created_at: result.created_at,
      previous_code_revoked: result.previous_code_revoked,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/join
export async function joinGroup(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = JoinGroupSchema.parse(req.body);

    // Find active invite code (not revoked)
    const invite = await db
      .selectFrom('invite_codes')
      .select(['group_id', 'id'])
      .where('code', '=', String(data.invite_code))
      .where('revoked_at', 'is', null)
      .executeTakeFirst();

    if (!invite) {
      throw new ApplicationError('Invalid or revoked invite code', 404, 'INVALID_INVITE_CODE');
    }

    // Check already a member
    const existingMembership = await getGroupMembership(req.user.id, invite.group_id);
    if (existingMembership) {
      throw new ApplicationError('Already a member of this group', 409, 'ALREADY_MEMBER');
    }

    // Check resource limit (max 100 active groups per user)
    const userGroupCount = await db
      .selectFrom('group_memberships')
      .where('user_id', '=', req.user.id)
      .where('status', '=', 'active')
      .select(db.fn.count('id').as('count'))
      .executeTakeFirst();
    if (Number(userGroupCount?.count ?? 0) >= 100) {
      throw new ApplicationError('Maximum groups limit reached (100)', 429, 'RESOURCE_LIMIT');
    }

    // Add user to group with status='pending' (approval required)
    await db
      .insertInto('group_memberships')
      .values({
        group_id: invite.group_id,
        user_id: req.user.id,
        role: 'member',
        status: 'pending',
      })
      .execute();

    // Create activity for join request
    await createGroupActivity(invite.group_id, ACTIVITY_TYPES.JOIN_REQUEST, req.user.id);

    // Get group and active member count for response
    const group = await db
      .selectFrom('groups')
      .select(['id', 'name'])
      .where('id', '=', invite.group_id)
      .executeTakeFirstOrThrow();

    const memberCountResult = await db
      .selectFrom('group_memberships')
      .select(db.fn.count('id').as('count'))
      .where('group_id', '=', invite.group_id)
      .where('status', '=', 'active')
      .executeTakeFirstOrThrow();

    const requestingUser = await db
      .selectFrom('users')
      .select(['display_name'])
      .where('id', '=', req.user.id)
      .executeTakeFirst();

    // Send FCM notification to admins/creators only (fire and forget)
    const displayName = requestingUser?.display_name ?? req.user.email;
    const userId = req.user.id;
    db.selectFrom('devices')
      .innerJoin('group_memberships', 'devices.user_id', 'group_memberships.user_id')
      .select(['devices.fcm_token'])
      .where('group_memberships.group_id', '=', invite.group_id)
      .where((eb) =>
        eb.or([
          eb('group_memberships.role', '=', 'admin'),
          eb('group_memberships.role', '=', 'creator'),
        ])
      )
      .execute()
      .then((adminDevices) => {
        if (adminDevices.length > 0) {
          for (const device of adminDevices) {
            sendPushNotification(
              device.fcm_token,
              'New Join Request',
              `${displayName} wants to join ${group.name}`,
              {
                type: 'join_request',
                group_id: invite.group_id,
                user_id: userId,
              }
            ).catch((error) => {
              logger.error('Failed to send join request notification', { error });
            });
          }
        }
      })
      .catch((error) => {
        logger.error('Failed to query admin devices', { error });
      });

    res.status(200).json({
      status: 'pending',
      message: 'Join request sent to group admins for approval',
      group: {
        id: group.id,
        name: group.name,
        member_count: Number(memberCountResult.count),
      },
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/activity
export async function getActivity(
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
    await requireActiveGroupMember(req.user.id, group_id);

    const limit = Math.min(parseInt(String(req.query.limit || '50')) || 50, 100);
    const offset = parseInt(String(req.query.offset || '0')) || 0;

    // Get activities with user info (LEFT JOIN because user may be deleted)
    const activities = await db
      .selectFrom('group_activities')
      .leftJoin('users', 'group_activities.user_id', 'users.id')
      .select([
        'group_activities.id',
        'group_activities.activity_type',
        'group_activities.metadata',
        'group_activities.created_at',
        'users.id as user_id',
        'users.display_name as user_display_name',
      ])
      .where('group_activities.group_id', '=', group_id)
      .orderBy('group_activities.created_at', 'desc')
      .limit(limit)
      .offset(offset)
      .execute();

    // Get total count
    const totalResult = await db
      .selectFrom('group_activities')
      .select(db.fn.count('id').as('count'))
      .where('group_id', '=', group_id)
      .executeTakeFirstOrThrow();

    res.status(200).json({
      activities: activities.map((a) => ({
        id: a.id,
        activity_type: a.activity_type,
        user: a.user_id
          ? {
              id: a.user_id,
              display_name: a.user_display_name,
            }
          : null,
        metadata: a.metadata,
        created_at: a.created_at,
      })),
      total: Number(totalResult.count),
    });
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/groups/:group_id/export-progress
 * Generate Excel workbook with progress overview for all group members.
 */
export async function exportGroupProgress(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const userId = req.user.id;

    const query = ExportProgressQuerySchema.parse(req.query);
    const { start_date, end_date, user_timezone } = query;

    await ensureGroupExists(group_id);
    await requireActiveGroupMember(userId, group_id);

    const groupDataRaw = await db
      .selectFrom('groups')
      .innerJoin('group_memberships', 'groups.id', 'group_memberships.group_id')
      .innerJoin('users', 'group_memberships.user_id', 'users.id')
      .leftJoin('goals', (join) =>
        join
          .onRef('goals.group_id', '=', 'groups.id')
          .on('goals.deleted_at', 'is', null)
      )
      .select([
        'groups.id as group_id',
        'groups.name as group_name',
        'users.id as user_id',
        'users.display_name',
        'users.email',
        'goals.id as goal_id',
        'goals.title as goal_title',
        'goals.cadence',
        'goals.metric_type',
        'goals.target_value',
        'goals.unit',
        'goals.created_at as goal_created_at',
      ])
      .where('groups.id', '=', group_id)
      .where('group_memberships.status', '=', 'active')
      .orderBy('users.display_name')
      .orderBy('goals.created_at')
      .execute();

    if (groupDataRaw.length === 0) {
      throw new ApplicationError('Group not found', 404, 'NOT_FOUND');
    }

    const progressData: ExportProgressEntry[] = await db
      .selectFrom('progress_entries')
      .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
      .select([
        'progress_entries.goal_id',
        'progress_entries.user_id',
        'progress_entries.value',
        'progress_entries.period_start',
        'goals.cadence',
        'goals.metric_type',
        'goals.target_value',
      ])
      .where('goals.group_id', '=', group_id)
      .where('progress_entries.period_start', '>=', start_date)
      .where('progress_entries.period_start', '<=', end_date)
      .execute()
      .then((rows) =>
        rows.map((r) => ({
          goal_id: r.goal_id,
          user_id: r.user_id,
          value: Number(r.value),
          period_start: r.period_start,
          cadence: r.cadence,
          metric_type: r.metric_type,
          target_value: r.target_value != null ? Number(r.target_value) : null,
        }))
      );

    const groupName = groupDataRaw[0].group_name as string;
    const memberMap = new Map<
      string,
      { id: string; display_name: string; email: string; goals: ExportGoal[] }
    >();

    for (const row of groupDataRaw) {
      if (!memberMap.has(row.user_id)) {
        memberMap.set(row.user_id, {
          id: row.user_id,
          display_name: row.display_name,
          email: row.email,
          goals: [],
        });
      }
      if (row.goal_id) {
        const member = memberMap.get(row.user_id)!;
        if (!member.goals.some((g) => g.id === row.goal_id)) {
          member.goals.push({
            id: row.goal_id,
            title: row.goal_title ?? '',
            cadence: row.cadence as ExportGoal['cadence'],
            metric_type: row.metric_type as ExportGoal['metric_type'],
            target_value:
              row.target_value != null ? Number(row.target_value) : null,
            unit: row.unit,
            user_id: row.user_id,
          });
        }
      }
    }

    const workbook = new ExcelJS.Workbook();
    workbook.creator = 'Pursue';
    workbook.created = new Date();
    workbook.modified = new Date();
    (workbook as { properties?: { title?: string; subject?: string; keywords?: string } }).properties = {
      title: `${groupName} Progress Report`,
      subject: `Progress from ${start_date} to ${end_date}`,
      keywords: 'pursue, goals, progress, accountability',
    };

    const members = Array.from(memberMap.values()).sort((a, b) =>
      a.display_name.localeCompare(b.display_name)
    );

    let letterheadBuffer: Buffer | null = null;
    const letterheadPath = path.join(process.cwd(), 'assets', 'letterhead.png');
    try {
      letterheadBuffer = await fs.readFile(letterheadPath);
    } catch {
      logger.warn('Letterhead not found at %s, progress export will omit letterhead', letterheadPath);
    }

    for (const member of members) {
      const sheetName = sanitizeSheetName(member.display_name);
      const worksheet = workbook.addWorksheet(sheetName);

      const startRow = letterheadBuffer ? addLetterhead(workbook, worksheet, letterheadBuffer) : 1;
      let currentRow = generateSummarySection(
        worksheet,
        startRow,
        member,
        groupName,
        member.goals,
        progressData,
        start_date,
        end_date
      );

      currentRow += 2;
      generateCalendarSection(
        worksheet,
        currentRow,
        member.goals,
        progressData.filter((p) => p.user_id === member.id),
        start_date,
        end_date,
        user_timezone
      );
    }

    const filename = `${sanitizeFilename(groupName)}_Progress_${start_date}_to_${end_date}.xlsx`;

    res.setHeader(
      'Content-Type',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    );
    res.setHeader('Content-Disposition', `attachment; filename="${filename}"`);

    await workbook.xlsx.write(res);
    res.end();

    await createGroupActivity(
      group_id,
      ACTIVITY_TYPES.EXPORT_PROGRESS,
      userId,
      { start_date, end_date }
    );
  } catch (error) {
    next(error);
  }
}
