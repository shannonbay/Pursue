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
  MemberProgressQuerySchema,
} from '../validations/groups.js';
import { ValidateExportRangeQuerySchema } from '../validations/subscriptions.js';
import {
  ensureGroupExists,
  getGroupMembership,
  requireGroupMember,
  requireGroupAdmin,
  requireGroupCreator,
  requireActiveGroupMember,
} from '../services/authorization.js';
import { canUserCreateOrJoinChallenge, canUserJoinOrCreateGroup, validateExportDateRange } from '../services/subscription.service.js';
import { createGroupActivity, ACTIVITY_TYPES } from '../services/activity.service.js';
import { createNotification } from '../services/notification.service.js';
import { sendGroupNotification, sendNotificationToUser, sendPushNotification, sendToTopic, buildTopicName } from '../services/fcm.service.js';
import { uploadGroupIcon, deleteGroupIcon } from '../services/storage.service.js';
import { getSignedUrl } from '../services/gcs.service.js';
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
import { getGroupHeat, initializeGroupHeat } from '../services/heat.service.js';
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

/**
 * Format display name as "First L." for reaction summary (first name + last initial).
 */
function formatDisplayNameShort(displayName: string): string {
  const parts = displayName.trim().split(/\s+/);
  if (parts.length === 1) return parts[0] ?? '';
  const first = parts[0] ?? '';
  const last = parts[parts.length - 1] ?? '';
  const lastInitial = last.charAt(0).toUpperCase();
  return `${first} ${lastInitial}.`;
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

    const { allowed, reason } = await canUserJoinOrCreateGroup(userId);
    if (!allowed) {
      const message = reason === 'free_tier_limit_reached'
        ? 'Upgrade to Premium to create additional groups'
        : 'Maximum groups reached (10)';
      throw new ApplicationError(message, 403, 'GROUP_LIMIT_REACHED');
    }

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
          'is_challenge',
          'challenge_start_date',
          'challenge_end_date',
          'challenge_status',
          'challenge_template_id',
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

    // Initialize heat record for the new group
    await initializeGroupHeat(result.group.id);

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
      is_challenge: result.group.is_challenge,
      challenge_start_date: result.group.challenge_start_date,
      challenge_end_date: result.group.challenge_end_date,
      challenge_status: result.group.challenge_status,
      challenge_template_id: result.group.challenge_template_id,
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
        'groups.is_challenge',
        'groups.challenge_start_date',
        'groups.challenge_end_date',
        'groups.challenge_status',
        'groups.challenge_template_id',
        'groups.creator_user_id',
        'groups.created_at',
        db.fn.count('group_memberships.id').as('member_count'),
        sql<boolean>`groups.icon_data IS NOT NULL`.as('has_icon'),
      ])
      .where('groups.id', '=', group_id)
      .groupBy('groups.id')
      .executeTakeFirstOrThrow();

    // Get extended heat data including GCR values
    const heatData = await getGroupHeat(group_id, true);

    res.status(200).json({
      id: result.id,
      name: result.name,
      description: result.description,
      icon_emoji: result.icon_emoji,
      icon_color: result.icon_color,
      is_challenge: result.is_challenge,
      challenge_start_date: result.challenge_start_date,
      challenge_end_date: result.challenge_end_date,
      challenge_status: result.challenge_status,
      challenge_template_id: result.challenge_template_id,
      has_icon: Boolean(result.has_icon),
      creator_user_id: result.creator_user_id,
      member_count: Number(result.member_count),
      created_at: result.created_at,
      user_role: membership.role,
      heat: heatData ?? {
        score: 0,
        tier: 0,
        tier_name: 'Cold',
        streak_days: 0,
        peak_score: 0,
        peak_date: null,
        last_calculated_at: null,
        yesterday_gcr: undefined,
        baseline_gcr: undefined,
      },
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

    const groupInfo = await db
      .selectFrom('groups')
      .select(['is_challenge', 'challenge_status'])
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    const challengeLimitCheck = groupInfo.is_challenge
      ? await canUserCreateOrJoinChallenge(user_id)
      : null;

    const regularLimitCheck = groupInfo.is_challenge
      ? { allowed: true as const, reason: undefined as string | undefined }
      : await canUserJoinOrCreateGroup(user_id);

    const allowed = groupInfo.is_challenge
      ? Boolean(challengeLimitCheck?.allowed)
      : regularLimitCheck.allowed;
    const reason = groupInfo.is_challenge
      ? challengeLimitCheck?.reason
      : regularLimitCheck.reason;

    if (!allowed) {
      const message = groupInfo.is_challenge
        ? (reason === 'free_tier_limit_reached'
            ? 'User has reached free tier active challenge limit. Upgrade to Premium to approve.'
            : 'User has reached maximum active challenges (10).')
        : (reason === 'free_tier_limit_reached'
            ? 'User has reached free tier group limit. Upgrade to Premium to approve.'
            : 'User has reached maximum groups (10).');
      throw new ApplicationError(message, 403, 'GROUP_LIMIT_REACHED');
    }

    // Update status to active
    await db
      .updateTable('group_memberships')
      .set({ status: 'active' })
      .where('group_id', '=', group_id)
      .where('user_id', '=', user_id)
      .execute();

    // Delete join request notifications for all admins (request resolved)
    await db
      .deleteFrom('user_notifications')
      .where('type', '=', 'join_request_received')
      .where('group_id', '=', group_id)
      .where('actor_user_id', '=', user_id)
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

    await createNotification({
      user_id,
      type: 'membership_approved',
      actor_user_id: req.user.id,
      group_id,
      metadata: {},
    });

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

    // Delete join request notifications for all admins (request resolved)
    await db
      .deleteFrom('user_notifications')
      .where('type', '=', 'join_request_received')
      .where('group_id', '=', group_id)
      .where('actor_user_id', '=', user_id)
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

    await createNotification({
      user_id,
      type: 'membership_rejected',
      actor_user_id: req.user.id,
      group_id,
      metadata: {},
    });

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

    if (data.role === 'admin') {
      await createNotification({
        user_id,
        type: 'promoted_to_admin',
        actor_user_id: req.user.id,
        group_id,
        metadata: {},
      });
    }

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

    await createNotification({
      user_id,
      type: 'removed_from_group',
      actor_user_id: req.user.id,
      group_id,
      metadata: {},
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

        await createNotification({
          user_id: promotedUserId,
          type: 'promoted_to_admin',
          actor_user_id: null, // System-triggered when last admin left
          group_id,
          metadata: {},
        });
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

    const groupType = await db
      .selectFrom('groups')
      .select('is_challenge')
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

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
      share_url: groupType.is_challenge
        ? `https://getpursue.app/challenge/${invite.code}`
        : `https://getpursue.app/join/${invite.code}`,
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
    const groupType = await db
      .selectFrom('groups')
      .select('is_challenge')
      .where('id', '=', group_id)
      .executeTakeFirstOrThrow();

    res.status(200).json({
      invite_code: result.invite_code,
      share_url: groupType.is_challenge
        ? `https://getpursue.app/challenge/${result.invite_code}`
        : `https://getpursue.app/join/${result.invite_code}`,
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
      .innerJoin('groups', 'groups.id', 'invite_codes.group_id')
      .select([
        'invite_codes.group_id',
        'invite_codes.id',
        'groups.is_challenge',
        'groups.challenge_status',
      ])
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

    if (invite.is_challenge && ['completed', 'cancelled'].includes(invite.challenge_status ?? '')) {
      throw new ApplicationError('This challenge has ended', 409, 'CHALLENGE_ENDED');
    }

    const challengeLimitCheck = invite.is_challenge
      ? await canUserCreateOrJoinChallenge(req.user.id)
      : null;
    const regularLimitCheck = invite.is_challenge
      ? { allowed: true as const, reason: undefined as string | undefined }
      : await canUserJoinOrCreateGroup(req.user.id);
    const allowed = invite.is_challenge ? Boolean(challengeLimitCheck?.allowed) : regularLimitCheck.allowed;
    const reason = invite.is_challenge ? challengeLimitCheck?.reason : regularLimitCheck.reason;

    if (!allowed) {
      const message = invite.is_challenge
        ? (reason === 'free_tier_limit_reached'
            ? 'Upgrade to Premium to join more active challenges'
            : 'Maximum active challenges reached (10)')
        : (reason === 'free_tier_limit_reached'
            ? 'Upgrade to Premium to join more groups'
            : 'Maximum groups reached (10)');
      throw new ApplicationError(message, 403, 'GROUP_LIMIT_REACHED');
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

    // Get all admins/creators for notifications
    const userId = req.user.id;
    const admins = await db
      .selectFrom('group_memberships')
      .select(['user_id'])
      .where('group_id', '=', invite.group_id)
      .where('status', '=', 'active')
      .where((eb) =>
        eb.or([
          eb('role', '=', 'admin'),
          eb('role', '=', 'creator'),
        ])
      )
      .execute();

    // Create inbox notifications for all admins (fire and forget)
    for (const admin of admins) {
      createNotification({
        user_id: admin.user_id,
        type: 'join_request_received',
        actor_user_id: userId,
        group_id: invite.group_id,
      }).catch((error) => {
        logger.error('Failed to create join request notification', { error });
      });
    }

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

    // Collect progress_entry_ids from progress_logged activities
    const progressEntryIds: string[] = [];
    for (const activity of activities) {
      if (
        activity.activity_type === 'progress_logged' &&
        activity.metadata &&
        typeof activity.metadata === 'object' &&
        'progress_entry_id' in activity.metadata
      ) {
        progressEntryIds.push(activity.metadata.progress_entry_id as string);
      }
    }

    // Batch fetch photos for all progress entries
    const now = new Date();
    const photosMap = new Map<string, {
      id: string;
      gcs_object_path: string;
      width_px: number;
      height_px: number;
      expires_at: Date;
    }>();

    if (progressEntryIds.length > 0) {
      const photos = await db
        .selectFrom('progress_photos')
        .select(['id', 'progress_entry_id', 'gcs_object_path', 'width_px', 'height_px', 'expires_at', 'gcs_deleted_at'])
        .where('progress_entry_id', 'in', progressEntryIds)
        .execute();

      for (const photo of photos) {
        const expiresAt = new Date(photo.expires_at);
        // Only include non-expired, non-deleted photos
        if (expiresAt > now && !photo.gcs_deleted_at) {
          photosMap.set(photo.progress_entry_id, {
            id: photo.id,
            gcs_object_path: photo.gcs_object_path,
            width_px: photo.width_px,
            height_px: photo.height_px,
            expires_at: expiresAt,
          });
        }
      }
    }

    // Generate signed URLs for all photos (in parallel)
    const signedUrls = new Map<string, string>();
    const urlPromises = Array.from(photosMap.entries()).map(async ([entryId, photo]) => {
      try {
        const url = await getSignedUrl(photo.gcs_object_path);
        signedUrls.set(entryId, url);
      } catch (err) {
        logger.warn('Failed to generate signed URL for photo', { entryId, error: err });
      }
    });
    await Promise.all(urlPromises);

    // Get total count
    const totalResult = await db
      .selectFrom('group_activities')
      .select(db.fn.count('id').as('count'))
      .where('group_id', '=', group_id)
      .executeTakeFirstOrThrow();

    // Batch fetch reactions for all activities
    const activityIds = activities.map((a) => a.id);
    const currentUserId = req.user.id;

    type ReactionRow = { activity_id: string; emoji: string; user_id: string; created_at: Date; display_name: string };
    let reactionRows: ReactionRow[] = [];

    if (activityIds.length > 0) {
      reactionRows = await db
        .selectFrom('activity_reactions')
        .innerJoin('users', 'activity_reactions.user_id', 'users.id')
        .select([
          'activity_reactions.activity_id',
          'activity_reactions.emoji',
          'activity_reactions.user_id',
          'activity_reactions.created_at',
          'users.display_name',
        ])
        .where('activity_reactions.activity_id', 'in', activityIds)
        .orderBy('activity_reactions.activity_id')
        .orderBy('activity_reactions.created_at', 'desc')
        .execute() as ReactionRow[];
    }

    // Build reactions map: activity_id -> { byEmoji, topReactors }
    const reactionsByActivity = new Map<string, {
      byEmoji: Map<string, { count: number; reactorIds: string[]; currentUserReacted: boolean }>;
      topReactors: Array<{ user_id: string; display_name: string; created_at: Date }>;
    }>();

    for (const activityId of activityIds) {
      reactionsByActivity.set(activityId, {
        byEmoji: new Map(),
        topReactors: [],
      });
    }

    const seenForTop = new Map<string, Set<string>>();
    for (const r of reactionRows) {
      const entry = reactionsByActivity.get(r.activity_id)!;
      const emojiEntry = entry.byEmoji.get(r.emoji);
      if (!emojiEntry) {
        entry.byEmoji.set(r.emoji, {
          count: 1,
          reactorIds: [r.user_id],
          currentUserReacted: r.user_id === currentUserId,
        });
      } else {
        emojiEntry.count += 1;
        emojiEntry.reactorIds.push(r.user_id);
        emojiEntry.currentUserReacted = emojiEntry.currentUserReacted || r.user_id === currentUserId;
      }

      // Build top_reactors: current user first if reacted, then most recent others (unique), max 3
      const seen = seenForTop.get(r.activity_id) ?? new Set<string>();
      if (seen.size < 3 && !seen.has(r.user_id)) {
        seen.add(r.user_id);
        seenForTop.set(r.activity_id, seen);
        const formatted = formatDisplayNameShort(r.display_name);
        entry.topReactors.push({ user_id: r.user_id, display_name: formatted, created_at: r.created_at });
      }
    }

    // Reorder top_reactors: put current user first if they reacted
    for (const [, entry] of reactionsByActivity) {
      const currentUserIdx = entry.topReactors.findIndex((t) => t.user_id === currentUserId);
      if (currentUserIdx > 0) {
        const [curr] = entry.topReactors.splice(currentUserIdx, 1);
        entry.topReactors.unshift(curr);
      }
    }

    res.status(200).json({
      activities: activities.map((a) => {
        const progressEntryId =
          a.activity_type === 'progress_logged' &&
          a.metadata &&
          typeof a.metadata === 'object' &&
          'progress_entry_id' in a.metadata
            ? (a.metadata.progress_entry_id as string)
            : null;

        const photo = progressEntryId ? photosMap.get(progressEntryId) : null;
        const signedUrl = progressEntryId ? signedUrls.get(progressEntryId) : null;

        const reactionData = reactionsByActivity.get(a.id)!;
        const byEmoji = reactionData.byEmoji;
        const reactions = Array.from(byEmoji.entries())
          .sort(([, a], [, b]) => b.count - a.count)
          .map(([emoji, data]) => ({
            emoji,
            count: data.count,
            reactor_ids: data.reactorIds,
            current_user_reacted: data.currentUserReacted,
          }));
        const totalCount = Array.from(byEmoji.values()).reduce((sum, e) => sum + e.count, 0);

        return {
          id: a.id,
          activity_type: a.activity_type,
          user: a.user_id
            ? {
                id: a.user_id,
                display_name: a.user_display_name,
              }
            : null,
          metadata: a.metadata,
          photo: photo && signedUrl
            ? {
                id: photo.id,
                url: signedUrl,
                width: photo.width_px,
                height: photo.height_px,
                expires_at: photo.expires_at,
              }
            : null,
          created_at: a.created_at,
          reactions,
          reaction_summary: {
            total_count: totalCount,
            top_reactors: reactionData.topReactors.map((t) => ({ user_id: t.user_id, display_name: t.display_name })),
          },
        };
      }),
      total: Number(totalResult.count),
    });
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/groups/:group_id/export-progress/validate-range
 * Validate export date range against subscription tier (free: 30 days, premium: 12 months).
 */
export async function validateExportRange(
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

    const query = ValidateExportRangeQuerySchema.parse(req.query);
    const result = await validateExportDateRange(req.user.id, query.start_date, query.end_date);
    if (!result) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }
    if (result.valid) {
      res.status(200).json({
        valid: true,
        max_days_allowed: result.max_days_allowed,
        requested_days: result.requested_days,
        subscription_tier: result.subscription_tier,
      });
      return;
    }
    res.status(400).json({
      valid: false,
      max_days_allowed: result.max_days_allowed,
      requested_days: result.requested_days,
      subscription_tier: result.subscription_tier,
      error: result.error,
      message: result.message,
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

    await createGroupActivity(
      group_id,
      ACTIVITY_TYPES.EXPORT_PROGRESS,
      userId,
      { start_date, end_date }
    );

    await workbook.xlsx.write(res);
    res.end();
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/groups/:group_id/members/:user_id/progress
 * Get member progress overview and activity log for a specific user in a group.
 * Supports cursor-based pagination for the activity log.
 */
export async function getMemberProgress(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const target_user_id = String(req.params.user_id);

    // Validate query parameters
    const parseResult = MemberProgressQuerySchema.safeParse(req.query);
    if (!parseResult.success) {
      const firstError = parseResult.error.issues[0];
      // Check if it's a date range error
      if (firstError.path.includes('end_date') && firstError.message.includes('>=')) {
        throw new ApplicationError('end_date must be >= start_date', 400, 'INVALID_DATE_RANGE');
      }
      throw new ApplicationError(firstError.message, 400, 'VALIDATION_ERROR');
    }
    const { start_date, end_date, cursor, limit } = parseResult.data;

    // Ensure group exists
    await ensureGroupExists(group_id);

    // Authorization: requesting user must be an active member
    await requireActiveGroupMember(req.user.id, group_id);

    // Authorization: target user must be an active member
    const targetMembership = await db
      .selectFrom('group_memberships')
      .select(['user_id', 'role', 'status', 'joined_at'])
      .where('group_id', '=', group_id)
      .where('user_id', '=', target_user_id)
      .where('status', '=', 'active')
      .executeTakeFirst();

    if (!targetMembership) {
      throw new ApplicationError(
        'Target user is not an approved member of this group',
        403,
        'TARGET_NOT_A_MEMBER'
      );
    }

    // Check premium for date ranges > 30 days
    const startDateObj = new Date(start_date + 'T00:00:00Z');
    const endDateObj = new Date(end_date + 'T00:00:00Z');
    const daysDiff = Math.ceil((endDateObj.getTime() - startDateObj.getTime()) / (1000 * 60 * 60 * 24)) + 1;

    if (daysDiff > 30) {
      const user = await db
        .selectFrom('users')
        .select('current_subscription_tier')
        .where('id', '=', req.user.id)
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (!user || user.current_subscription_tier !== 'premium') {
        throw new ApplicationError(
          'Date range exceeds 30 days. Premium subscription required.',
          403,
          'SUBSCRIPTION_REQUIRED'
        );
      }
    }

    // Get member info
    const memberInfo = await db
      .selectFrom('users')
      .select(['id', 'display_name', 'avatar_data', 'avatar_mime_type'])
      .where('id', '=', target_user_id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!memberInfo) {
      throw new ApplicationError('User not found', 404, 'NOT_FOUND');
    }

    // Build avatar URL (null if no avatar data)
    const avatar_url = memberInfo.avatar_data
      ? `/api/users/${memberInfo.id}/avatar`
      : null;

    // Get all active goals for this group
    const goals = await db
      .selectFrom('goals')
      .select(['id', 'title', 'description', 'cadence', 'metric_type', 'target_value', 'unit'])
      .where('group_id', '=', group_id)
      .where('deleted_at', 'is', null)
      .orderBy('created_at', 'asc')
      .execute();

    // Get all progress entries for this user in the date range (for goal summaries)
    const goalIds = goals.map((g) => g.id);
    let progressEntries: Array<{
      id: string;
      goal_id: string;
      value: number | string;
      period_start: string;
    }> = [];

    if (goalIds.length > 0) {
      progressEntries = await db
        .selectFrom('progress_entries')
        .select(['id', 'goal_id', 'value', 'period_start'])
        .where('goal_id', 'in', goalIds)
        .where('user_id', '=', target_user_id)
        .where('period_start', '>=', start_date)
        .where('period_start', '<=', end_date)
        .execute();
    }

    // Calculate goal summaries
    const goal_summaries = goals.map((goal) => {
      const goalEntries = progressEntries.filter((e) => e.goal_id === goal.id);

      // Calculate completed
      let completed: number;
      if (goal.metric_type === 'binary') {
        completed = goalEntries.length;
      } else {
        completed = goalEntries.reduce((sum, entry) => {
          const val = typeof entry.value === 'string' ? parseFloat(entry.value) : Number(entry.value) || 0;
          return sum + val;
        }, 0);
      }

      // Calculate total based on cadence and date range for binary goals
      let total: number;
      if (goal.metric_type === 'binary') {
        // For binary goals, total is number of periods in the timeframe * target_value
        const periodsInRange = calculatePeriodsInRange(goal.cadence, start_date, end_date);
        const targetPerPeriod = goal.target_value != null
          ? (typeof goal.target_value === 'string' ? parseFloat(goal.target_value) : Number(goal.target_value))
          : 1;
        total = periodsInRange * targetPerPeriod;
      } else {
        // For numeric/duration goals, total is target_value per period * number of periods
        const periodsInRange = calculatePeriodsInRange(goal.cadence, start_date, end_date);
        const targetPerPeriod = goal.target_value != null
          ? (typeof goal.target_value === 'string' ? parseFloat(goal.target_value) : Number(goal.target_value))
          : 0;
        total = periodsInRange * targetPerPeriod;
      }

      const percentage = total > 0 ? Math.min(100, Math.round((completed / total) * 100)) : 0;

      return {
        goal_id: goal.id,
        title: goal.title,
        emoji: null, // Goals don't have emoji in the schema, this is in metadata if needed
        cadence: goal.cadence,
        metric_type: goal.metric_type,
        target_value: goal.target_value != null
          ? (typeof goal.target_value === 'string' ? parseFloat(goal.target_value) : Number(goal.target_value))
          : null,
        unit: goal.unit,
        completed: Math.round(completed * 100) / 100, // Round to 2 decimal places
        total: Math.round(total * 100) / 100,
        percentage,
      };
    });

    // Decode cursor if provided
    let cursorLoggedAt: Date | null = null;
    let cursorEntryId: string | null = null;
    if (cursor) {
      try {
        const decoded = JSON.parse(Buffer.from(cursor, 'base64').toString('utf-8'));
        if (!decoded.logged_at || !decoded.entry_id) {
          throw new Error('Invalid cursor structure');
        }
        cursorLoggedAt = new Date(decoded.logged_at);
        cursorEntryId = decoded.entry_id;
        if (isNaN(cursorLoggedAt.getTime())) {
          throw new Error('Invalid cursor date');
        }
      } catch {
        throw new ApplicationError('Invalid cursor', 400, 'INVALID_CURSOR');
      }
    }

    // Query activity log with keyset pagination
    // We need limit + 1 to determine if there's a next page
    let activityQuery = db
      .selectFrom('progress_entries as pe')
      .innerJoin('goals as g', 'pe.goal_id', 'g.id')
      .select([
        'pe.id as entry_id',
        'pe.goal_id',
        'g.title as goal_title',
        'g.metric_type',
        'g.unit',
        'pe.value',
        'pe.period_start as entry_date',
        'pe.logged_at',
        'pe.note',
        sql<number>`COUNT(*) OVER()`.as('total_count'),
      ])
      .where('g.group_id', '=', group_id)
      .where('g.deleted_at', 'is', null)
      .where('pe.user_id', '=', target_user_id)
      .where('pe.period_start', '>=', start_date)
      .where('pe.period_start', '<=', end_date);

    // Apply cursor filter if provided (keyset pagination)
    // (logged_at, id) < (cursor_logged_at, cursor_entry_id) is equivalent to:
    // logged_at < cursor_logged_at OR (logged_at = cursor_logged_at AND id < cursor_entry_id)
    if (cursorLoggedAt && cursorEntryId) {
      activityQuery = activityQuery.where(({ eb, or, and }) =>
        or([
          eb('pe.logged_at', '<', cursorLoggedAt),
          and([
            eb('pe.logged_at', '=', cursorLoggedAt),
            eb('pe.id', '<', cursorEntryId),
          ]),
        ])
      );
    }

    const activityRows = await activityQuery
      .orderBy('pe.logged_at', 'desc')
      .orderBy('pe.id', 'desc')
      .limit(limit + 1)
      .execute();

    // Determine pagination
    const hasMore = activityRows.length > limit;
    const activityResults = hasMore ? activityRows.slice(0, limit) : activityRows;
    const totalInTimeframe = activityRows.length > 0 ? Number(activityRows[0].total_count) : 0;

    // Build next cursor
    let nextCursor: string | null = null;
    if (hasMore && activityResults.length > 0) {
      const lastEntry = activityResults[activityResults.length - 1];
      const cursorData = {
        logged_at: lastEntry.logged_at instanceof Date
          ? lastEntry.logged_at.toISOString()
          : lastEntry.logged_at,
        entry_id: lastEntry.entry_id,
      };
      nextCursor = Buffer.from(JSON.stringify(cursorData)).toString('base64');
    }

    // Collect entry IDs for photo and reaction lookups
    const entryIds = activityResults.map((a) => a.entry_id);

    // Fetch photos for progress entries
    const now = new Date();
    const photosMap = new Map<string, {
      id: string;
      gcs_object_path: string;
      width_px: number;
      height_px: number;
      expires_at: Date;
    }>();

    if (entryIds.length > 0) {
      const photos = await db
        .selectFrom('progress_photos')
        .select(['id', 'progress_entry_id', 'gcs_object_path', 'width_px', 'height_px', 'expires_at', 'gcs_deleted_at'])
        .where('progress_entry_id', 'in', entryIds)
        .execute();

      for (const photo of photos) {
        const expiresAt = new Date(photo.expires_at);
        if (expiresAt > now && !photo.gcs_deleted_at) {
          photosMap.set(photo.progress_entry_id, {
            id: photo.id,
            gcs_object_path: photo.gcs_object_path,
            width_px: photo.width_px,
            height_px: photo.height_px,
            expires_at: expiresAt,
          });
        }
      }
    }

    // Generate signed URLs for photos
    const signedUrls = new Map<string, string>();
    const urlPromises = Array.from(photosMap.entries()).map(async ([entryId, photo]) => {
      try {
        const url = await getSignedUrl(photo.gcs_object_path);
        signedUrls.set(entryId, url);
      } catch (err) {
        logger.warn('Failed to generate signed URL for photo', { entryId, error: err });
      }
    });
    await Promise.all(urlPromises);

    // Fetch reactions: find group_activities that link to these progress entries
    const reactionsByEntry = new Map<string, Array<{ emoji: string; count: number }>>();

    if (entryIds.length > 0) {
      // Find group_activities with progress_logged type that reference these entries
      const activities = await db
        .selectFrom('group_activities')
        .select(['id', 'metadata'])
        .where('group_id', '=', group_id)
        .where('activity_type', '=', 'progress_logged')
        .execute();

      // Map activity_id -> progress_entry_id
      const activityToEntryMap = new Map<string, string>();
      const activityIds: string[] = [];
      for (const activity of activities) {
        if (
          activity.metadata &&
          typeof activity.metadata === 'object' &&
          'progress_entry_id' in activity.metadata
        ) {
          const progressEntryId = activity.metadata.progress_entry_id as string;
          if (entryIds.includes(progressEntryId)) {
            activityToEntryMap.set(activity.id, progressEntryId);
            activityIds.push(activity.id);
          }
        }
      }

      // Fetch reactions for these activities
      if (activityIds.length > 0) {
        const reactions = await db
          .selectFrom('activity_reactions')
          .select(['activity_id', 'emoji', sql<number>`COUNT(*)`.as('count')])
          .where('activity_id', 'in', activityIds)
          .groupBy(['activity_id', 'emoji'])
          .execute();

        // Group by progress entry
        for (const r of reactions) {
          const entryId = activityToEntryMap.get(r.activity_id);
          if (entryId) {
            if (!reactionsByEntry.has(entryId)) {
              reactionsByEntry.set(entryId, []);
            }
            reactionsByEntry.get(entryId)!.push({
              emoji: r.emoji,
              count: Number(r.count),
            });
          }
        }
      }
    }

    // Build activity log response
    const activity_log = activityResults.map((row) => {
      const photo = photosMap.get(row.entry_id);
      const signedUrl = signedUrls.get(row.entry_id);
      const reactions = reactionsByEntry.get(row.entry_id) || [];

      // Sort reactions by count descending
      reactions.sort((a, b) => b.count - a.count);

      return {
        entry_id: row.entry_id,
        goal_id: row.goal_id,
        goal_title: row.goal_title,
        goal_emoji: null, // Goals don't have emoji field
        value: typeof row.value === 'string' ? parseFloat(row.value) : Number(row.value),
        unit: row.unit,
        metric_type: row.metric_type,
        entry_date: row.entry_date,
        logged_at: row.logged_at,
        note: row.note,
        photo_url: photo && signedUrl ? signedUrl : null,
        reactions,
      };
    });

    res.status(200).json({
      member: {
        user_id: memberInfo.id,
        display_name: memberInfo.display_name,
        avatar_url,
        role: targetMembership.role,
        joined_at: targetMembership.joined_at,
      },
      timeframe: {
        start_date,
        end_date,
      },
      goal_summaries,
      activity_log,
      pagination: {
        next_cursor: nextCursor,
        has_more: hasMore,
        total_in_timeframe: totalInTimeframe,
      },
    });
  } catch (error) {
    next(error);
  }
}

/**
 * Calculate the number of periods (days/weeks/months/years) within a date range.
 */
function calculatePeriodsInRange(cadence: string, startDate: string, endDate: string): number {
  const start = new Date(startDate + 'T00:00:00Z');
  const end = new Date(endDate + 'T00:00:00Z');

  switch (cadence) {
    case 'daily': {
      const days = Math.ceil((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)) + 1;
      return days;
    }
    case 'weekly': {
      // Count number of weeks touched by the range
      // A week is Monday-Sunday
      const dayOfWeekStart = (start.getUTCDay() + 6) % 7; // Monday = 0
      const dayOfWeekEnd = (end.getUTCDay() + 6) % 7;

      const msPerDay = 1000 * 60 * 60 * 24;
      const totalDays = Math.ceil((end.getTime() - start.getTime()) / msPerDay) + 1;

      // Number of complete weeks + partial weeks
      const daysToNextMonday = (7 - dayOfWeekStart) % 7;
      if (totalDays <= daysToNextMonday + 1) {
        return 1; // All in one week
      }

      const daysAfterFirstWeek = totalDays - daysToNextMonday - 1;
      const completeWeeks = Math.floor(daysAfterFirstWeek / 7);
      const remainingDays = daysAfterFirstWeek % 7;

      return 1 + completeWeeks + (remainingDays > 0 || dayOfWeekEnd < 6 ? 1 : 0);
    }
    case 'monthly': {
      // Count number of months touched
      const startYear = start.getUTCFullYear();
      const startMonth = start.getUTCMonth();
      const endYear = end.getUTCFullYear();
      const endMonth = end.getUTCMonth();
      return (endYear - startYear) * 12 + (endMonth - startMonth) + 1;
    }
    case 'yearly': {
      // Count number of years touched
      return end.getUTCFullYear() - start.getUTCFullYear() + 1;
    }
    default:
      return 1;
  }
}
