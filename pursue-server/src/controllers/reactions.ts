import type { Response, NextFunction } from 'express';
import { sql } from 'kysely';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { AddReactionSchema } from '../validations/reactions.js';
import { requireActiveGroupMember } from '../services/authorization.js';
import { sendNotificationToUser } from '../services/fcm.service.js';
import { logger } from '../utils/logger.js';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

async function getActivityWithGroup(activityId: string): Promise<{ id: string; group_id: string; user_id: string | null } | null> {
  if (!UUID_REGEX.test(activityId)) {
    return null;
  }
  const activity = await db
    .selectFrom('group_activities')
    .select(['id', 'group_id', 'user_id'])
    .where('id', '=', activityId)
    .executeTakeFirst();
  return activity ?? null;
}

/**
 * PUT /api/activities/:activity_id/reactions
 * Add or replace a reaction on an activity.
 */
export async function addOrReplaceReaction(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const activity_id = String(req.params.activity_id);
    const activity = await getActivityWithGroup(activity_id);
    if (!activity) {
      throw new ApplicationError('Activity not found', 404, 'NOT_FOUND');
    }

    await requireActiveGroupMember(req.user.id, activity.group_id);

    const data = AddReactionSchema.parse(req.body);

    // Check for existing reaction to set `replaced` flag
    const existing = await db
      .selectFrom('activity_reactions')
      .select(['emoji'])
      .where('activity_id', '=', activity_id)
      .where('user_id', '=', req.user.id)
      .executeTakeFirst();

    const replaced = !!existing && existing.emoji !== data.emoji;

    await sql`
      INSERT INTO activity_reactions (activity_id, user_id, emoji)
      VALUES (${activity_id}, ${req.user.id}, ${data.emoji})
      ON CONFLICT (activity_id, user_id)
      DO UPDATE SET emoji = ${data.emoji}, created_at = NOW()
    `.execute(db);

    const reaction = await db
      .selectFrom('activity_reactions')
      .select(['activity_id', 'user_id', 'emoji', 'created_at'])
      .where('activity_id', '=', activity_id)
      .where('user_id', '=', req.user.id)
      .executeTakeFirstOrThrow();

    // Send FCM notification to activity owner (skip if self-reaction)
    const activityOwnerId = activity.user_id;
    if (activityOwnerId && activityOwnerId !== req.user.id) {
      const reactor = await db
        .selectFrom('users')
        .select('display_name')
        .where('id', '=', req.user.id)
        .executeTakeFirst();
      const reactorDisplayName = reactor?.display_name ?? 'Someone';
      sendNotificationToUser(
        activityOwnerId,
        { title: 'New Reaction', body: `${reactorDisplayName} reacted ${data.emoji} to your activity` },
        { type: 'activity_reaction', activity_id: activity_id, group_id: activity.group_id, emoji: data.emoji }
      ).catch((error) => {
        logger.error('Failed to send reaction notification', { error, activity_id, activityOwnerId });
      });
    }

    res.status(200).json({
      reaction: {
        activity_id: reaction.activity_id,
        user_id: reaction.user_id,
        emoji: reaction.emoji,
        created_at: reaction.created_at,
      },
      replaced,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * DELETE /api/activities/:activity_id/reactions
 * Remove the current user's reaction from an activity.
 */
export async function removeReaction(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const activity_id = String(req.params.activity_id);
    const activity = await getActivityWithGroup(activity_id);
    if (!activity) {
      throw new ApplicationError('Activity not found', 404, 'NOT_FOUND');
    }

    await requireActiveGroupMember(req.user.id, activity.group_id);

    const result = await db
      .deleteFrom('activity_reactions')
      .where('activity_id', '=', activity_id)
      .where('user_id', '=', req.user.id)
      .executeTakeFirst();

    if (Number(result.numDeletedRows) === 0) {
      throw new ApplicationError('Reaction not found', 404, 'NOT_FOUND');
    }

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/activities/:activity_id/reactions
 * Get full reactor list for bottom sheet (user info + emoji per reaction).
 */
export async function getReactions(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const activity_id = String(req.params.activity_id);
    const activity = await getActivityWithGroup(activity_id);
    if (!activity) {
      throw new ApplicationError('Activity not found', 404, 'NOT_FOUND');
    }

    await requireActiveGroupMember(req.user.id, activity.group_id);

    const reactions = await db
      .selectFrom('activity_reactions')
      .innerJoin('users', 'activity_reactions.user_id', 'users.id')
      .select([
        'activity_reactions.emoji',
        'activity_reactions.created_at',
        'users.id as user_id',
        'users.display_name as user_display_name',
        sql<boolean>`users.avatar_data IS NOT NULL`.as('has_avatar'),
      ])
      .where('activity_reactions.activity_id', '=', activity_id)
      .orderBy('activity_reactions.created_at', 'desc')
      .execute();

    res.status(200).json({
      activity_id,
      reactions: reactions.map((r) => ({
        emoji: r.emoji,
        user: {
          id: r.user_id,
          display_name: r.user_display_name,
          avatar_url: r.has_avatar ? `/api/users/${r.user_id}/avatar` : null,
        },
        created_at: r.created_at,
      })),
      total: reactions.length,
    });
  } catch (error) {
    next(error);
  }
}
