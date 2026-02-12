import type { Response, NextFunction } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { CreateNudgeSchema, GetSentTodaySchema } from '../validations/nudges.js';
import { requireActiveGroupMember } from '../services/authorization.js';
import { createGroupActivity, ACTIVITY_TYPES } from '../services/activity.service.js';
import { sendNotificationToUser } from '../services/fcm.service.js';

const DAILY_SEND_LIMIT = 20;

function buildNudgeBody(senderName: string, goalTitle: string | null): string {
  if (goalTitle) {
    return `${senderName} is rooting for you! Don't forget your ${goalTitle} goal. 💪`;
  }
  return `${senderName} is rooting for you! Keep going today. 💪`;
}

// POST /api/nudges
export async function createNudge(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = CreateNudgeSchema.parse(req.body);
    const senderId = req.user.id;

    if (senderId === data.recipient_user_id) {
      throw new ApplicationError('Cannot nudge yourself', 400, 'CANNOT_NUDGE_SELF');
    }

    await requireActiveGroupMember(senderId, data.group_id);

    const recipientMembership = await db
      .selectFrom('group_memberships')
      .select('user_id')
      .where('group_id', '=', data.group_id)
      .where('user_id', '=', data.recipient_user_id)
      .where('status', '=', 'active')
      .executeTakeFirst();

    if (!recipientMembership) {
      throw new ApplicationError('Recipient is not a member of this group', 403, 'RECIPIENT_NOT_IN_GROUP');
    }

    const sentTodayCount = await db
      .selectFrom('nudges')
      .select(db.fn.count('id').as('count'))
      .where('sender_user_id', '=', senderId)
      .where('sender_local_date', '=', data.sender_local_date)
      .executeTakeFirst();

    const count = Number(sentTodayCount?.count ?? 0);
    if (count >= DAILY_SEND_LIMIT) {
      throw new ApplicationError(
        `You have reached the daily nudge limit (${DAILY_SEND_LIMIT})`,
        429,
        'DAILY_SEND_LIMIT'
      );
    }

    let nudge;
    try {
      nudge = await db
        .insertInto('nudges')
        .values({
          sender_user_id: senderId,
          recipient_user_id: data.recipient_user_id,
          group_id: data.group_id,
          goal_id: data.goal_id ?? null,
          sender_local_date: data.sender_local_date,
        })
        .returning(['id', 'recipient_user_id', 'group_id', 'goal_id', 'sent_at'])
        .executeTakeFirstOrThrow();
    } catch (err: unknown) {
      const pgErr = err as { code?: string };
      if (pgErr.code === '23505') {
        throw new ApplicationError('You already nudged this person today', 409, 'ALREADY_NUDGED_TODAY');
      }
      throw err;
    }

    const [group, sender, recipient, goal] = await Promise.all([
      db
        .selectFrom('groups')
        .select('name')
        .where('id', '=', data.group_id)
        .executeTakeFirst(),
      db
        .selectFrom('users')
        .select('display_name')
        .where('id', '=', senderId)
        .executeTakeFirst(),
      db
        .selectFrom('users')
        .select('display_name')
        .where('id', '=', data.recipient_user_id)
        .executeTakeFirst(),
      data.goal_id
        ? db
            .selectFrom('goals')
            .select('title')
            .where('id', '=', data.goal_id)
            .where('deleted_at', 'is', null)
            .executeTakeFirst()
        : Promise.resolve(null),
    ]);

    const senderName = sender?.display_name ?? 'Someone';
    const recipientName = recipient?.display_name ?? 'Someone';
    const goalTitle = goal?.title ?? null;
    const groupName = group?.name ?? 'Your group';

    await sendNotificationToUser(
      data.recipient_user_id,
      {
        title: groupName,
        body: buildNudgeBody(senderName, goalTitle),
      },
      {
        type: 'nudge_received',
        group_id: data.group_id,
        goal_id: data.goal_id ?? '',
        sender_user_id: senderId,
        sender_display_name: senderName,
      }
    );

    await createGroupActivity(
      data.group_id,
      ACTIVITY_TYPES.NUDGE_SENT,
      senderId,
      {
        sender_user_id: senderId,
        sender_display_name: senderName,
        recipient_user_id: data.recipient_user_id,
        recipient_display_name: recipientName,
        goal_id: data.goal_id ?? null,
        goal_title: goalTitle,
      }
    );

    res.status(201).json({
      nudge: {
        id: nudge.id,
        recipient_user_id: nudge.recipient_user_id,
        group_id: nudge.group_id,
        goal_id: nudge.goal_id,
        sent_at: nudge.sent_at,
      },
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/nudges/sent-today
export async function getSentToday(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const query = GetSentTodaySchema.parse({ sender_local_date: req.query.sender_local_date });

    await requireActiveGroupMember(req.user.id, group_id);

    const nudges = await db
      .selectFrom('nudges')
      .select('recipient_user_id')
      .where('sender_user_id', '=', req.user.id)
      .where('group_id', '=', group_id)
      .where('sender_local_date', '=', query.sender_local_date)
      .execute();

    const nudgedUserIds = [...new Set(nudges.map((n) => n.recipient_user_id))];

    res.status(200).json({ nudged_user_ids: nudgedUserIds });
  } catch (error) {
    next(error);
  }
}
