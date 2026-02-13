import type { Response, NextFunction } from 'express';
import { sql } from 'kysely';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import {
  GetNotificationsSchema,
  NotificationIdParamSchema,
} from '../validations/notifications.js';

// GET /api/notifications
export async function getNotifications(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const query = GetNotificationsSchema.parse(req.query);
    const userId = req.user.id;

    let baseQuery = db
      .selectFrom('user_notifications')
      .leftJoin('users as actor', 'user_notifications.actor_user_id', 'actor.id')
      .leftJoin('groups', 'user_notifications.group_id', 'groups.id')
      .leftJoin('goals', 'user_notifications.goal_id', 'goals.id')
      .select([
        'user_notifications.id',
        'user_notifications.type',
        'user_notifications.is_read',
        'user_notifications.created_at',
        'user_notifications.progress_entry_id',
        'user_notifications.metadata',
        'user_notifications.actor_user_id',
        'user_notifications.group_id',
        'user_notifications.goal_id',
        'actor.display_name as actor_display_name',
        sql<boolean>`actor.avatar_data IS NOT NULL`.as('actor_has_avatar'),
        'groups.name as group_name',
        'goals.title as goal_title',
      ])
      .where('user_notifications.user_id', '=', userId)
      .orderBy('user_notifications.created_at', 'desc')
      .limit(query.limit + 1); // Fetch one extra to determine has_more

    if (query.before_id) {
      const beforeNotif = await db
        .selectFrom('user_notifications')
        .select('created_at')
        .where('id', '=', query.before_id)
        .where('user_id', '=', userId)
        .executeTakeFirst();

      if (beforeNotif) {
        baseQuery = baseQuery.where(
          'user_notifications.created_at',
          '<',
          beforeNotif.created_at
        );
      }
    }

    const rows = await baseQuery.execute();

    const hasMore = rows.length > query.limit;
    const notifications = hasMore ? rows.slice(0, query.limit) : rows;

    const unreadCountResult = await db
      .selectFrom('user_notifications')
      .select(db.fn.count('id').as('count'))
      .where('user_id', '=', userId)
      .where('is_read', '=', false)
      .executeTakeFirst();

    const unreadCount = Number(unreadCountResult?.count ?? 0);

    res.status(200).json({
      notifications: notifications.map((n) => ({
        id: n.id,
        type: n.type,
        is_read: n.is_read,
        created_at: n.created_at,
        actor: n.actor_user_id
          ? {
              id: n.actor_user_id,
              display_name: n.actor_display_name ?? 'Someone',
              avatar_url: n.actor_has_avatar
                ? `/api/users/${n.actor_user_id}/avatar`
                : null,
            }
          : null,
        group: n.group_id
          ? { id: n.group_id, name: n.group_name ?? '' }
          : null,
        goal: n.goal_id
          ? { id: n.goal_id, title: n.goal_title ?? '' }
          : null,
        progress_entry_id: n.progress_entry_id,
        metadata: n.metadata ?? {},
      })),
      unread_count: unreadCount,
      has_more: hasMore,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/notifications/unread-count
export async function getUnreadCount(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const result = await db
      .selectFrom('user_notifications')
      .select(db.fn.count('id').as('count'))
      .where('user_id', '=', req.user.id)
      .where('is_read', '=', false)
      .executeTakeFirst();

    res.status(200).json({
      unread_count: Number(result?.count ?? 0),
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/notifications/mark-all-read
export async function markAllAsRead(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const result = await db
      .updateTable('user_notifications')
      .set({ is_read: true })
      .where('user_id', '=', req.user.id)
      .where('is_read', '=', false)
      .execute();

    const updateResult = Array.isArray(result) ? result[0] : result;
    const markedRead = Number((updateResult as { numUpdatedRows?: bigint })?.numUpdatedRows ?? 0);

    res.status(200).json({
      marked_read: markedRead,
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/notifications/:notification_id/read
export async function markAsRead(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const { notification_id } = NotificationIdParamSchema.parse(req.params);

    const result = await db
      .updateTable('user_notifications')
      .set({ is_read: true })
      .where('id', '=', notification_id)
      .where('user_id', '=', req.user.id)
      .returning(['id', 'is_read'])
      .executeTakeFirst();

    if (!result) {
      throw new ApplicationError('Notification not found', 404, 'NOT_FOUND');
    }

    res.status(200).json({
      id: result.id,
      is_read: result.is_read,
    });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/notifications/:notification_id
export async function deleteNotification(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const { notification_id } = NotificationIdParamSchema.parse(req.params);

    const result = await db
      .deleteFrom('user_notifications')
      .where('id', '=', notification_id)
      .where('user_id', '=', req.user.id)
      .executeTakeFirst();

    if (Number(result.numDeletedRows) === 0) {
      throw new ApplicationError('Notification not found', 404, 'NOT_FOUND');
    }

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}
