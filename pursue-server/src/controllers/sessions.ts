import type { Response, NextFunction } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { CreateSessionSchema, CreateSlotSchema } from '../validations/sessions.js';
import { requireActiveGroupMember, UUID_REGEX } from '../services/authorization.js';
import {
  sendGroupNotification,
  sendSilentGroupMessage,
  sendNotificationToUser,
  buildTopicName,
} from '../services/fcm.service.js';
import { logger } from '../utils/logger.js';

const MAX_PARTICIPANTS = 8;
const RSVP_MILESTONE = 3;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function validateUuidParam(value: string, name: string): void {
  if (!UUID_REGEX.test(value)) {
    throw new ApplicationError(`${name} not found`, 404, 'NOT_FOUND');
  }
}


async function fetchActiveParticipants(sessionId: string) {
  return db
    .selectFrom('focus_session_participants as p')
    .innerJoin('users as u', 'p.user_id', 'u.id')
    .select(['p.id', 'p.user_id', 'u.display_name', 'p.joined_at', 'p.left_at'])
    .where('p.session_id', '=', sessionId)
    .where('p.left_at', 'is', null)
    .orderBy('p.joined_at', 'asc')
    .execute();
}

async function fetchSession(sessionId: string, groupId: string) {
  const session = await db
    .selectFrom('focus_sessions')
    .select(['id', 'group_id', 'host_user_id', 'status', 'focus_duration_minutes', 'started_at', 'ended_at', 'created_at'])
    .where('id', '=', sessionId)
    .executeTakeFirst();

  if (!session) {
    throw new ApplicationError('Session not found', 404, 'NOT_FOUND');
  }
  if (session.group_id !== groupId) {
    throw new ApplicationError('Session does not belong to this group', 404, 'NOT_FOUND');
  }
  return session;
}

async function createSessionWithHost(
  groupId: string,
  hostUserId: string,
  durationMinutes: number
) {
  const session = await db
    .insertInto('focus_sessions')
    .values({
      group_id: groupId,
      host_user_id: hostUserId,
      status: 'lobby',
      focus_duration_minutes: durationMinutes,
    })
    .returningAll()
    .executeTakeFirstOrThrow();

  await db
    .insertInto('focus_session_participants')
    .values({ session_id: session.id, user_id: hostUserId })
    .execute();

  return session;
}

async function getUserSubscriptionTier(userId: string): Promise<string> {
  const user = await db
    .selectFrom('users')
    .select('current_subscription_tier')
    .where('id', '=', userId)
    .executeTakeFirst();
  return user?.current_subscription_tier ?? 'free';
}

// ---------------------------------------------------------------------------
// Session endpoints
// ---------------------------------------------------------------------------

// POST /api/groups/:groupId/sessions
export async function createSession(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    validateUuidParam(groupId, 'Group');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    const data = CreateSessionSchema.parse(req.body);

    if (data.focus_duration_minutes === 90) {
      const tier = await getUserSubscriptionTier(userId);
      if (tier !== 'premium') {
        throw new ApplicationError(
          '90-minute focus blocks require a Premium subscription',
          403,
          'PREMIUM_REQUIRED'
        );
      }
    }

    const session = await createSessionWithHost(groupId, userId, data.focus_duration_minutes);
    const participants = await fetchActiveParticipants(session.id);

    // Notify all active group members except the host
    const user = await db.selectFrom('users').select('display_name').where('id', '=', userId).executeTakeFirst();
    const topicName = buildTopicName(groupId, 'group_events');
    sendGroupNotification(
      groupId,
      { title: 'Focus Session Started', body: `${user?.display_name ?? 'Someone'} started a focus session — join now!` },
      { type: 'session_started', session_id: session.id, group_id: groupId },
      { membershipStatus: 'active' }
    ).catch((err) => logger.error('FCM session_started failed', { err }));

    res.status(201).json({ session: { ...session, participants } });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:groupId/sessions/active
export async function getActiveSessions(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    validateUuidParam(groupId, 'Group');
    await requireActiveGroupMember(req.user.id, groupId);

    const sessions = await db
      .selectFrom('focus_sessions')
      .select(['id', 'group_id', 'host_user_id', 'status', 'focus_duration_minutes', 'started_at', 'ended_at', 'created_at'])
      .where('group_id', '=', groupId)
      .where('status', 'in', ['lobby', 'focus', 'chit-chat'])
      .orderBy('created_at', 'asc')
      .execute();

    // Batch-fetch all active participants for these sessions (avoids N+1)
    const sessionIds = sessions.map((s) => s.id);
    let allParticipants: Array<{ session_id: string; id: string; user_id: string; display_name: string; joined_at: Date; left_at: Date | null }> = [];
    if (sessionIds.length > 0) {
      allParticipants = await db
        .selectFrom('focus_session_participants as p')
        .innerJoin('users as u', 'p.user_id', 'u.id')
        .select(['p.session_id', 'p.id', 'p.user_id', 'u.display_name', 'p.joined_at', 'p.left_at'])
        .where('p.session_id', 'in', sessionIds)
        .where('p.left_at', 'is', null)
        .orderBy('p.joined_at', 'asc')
        .execute();
    }

    const participantsBySession = new Map<string, typeof allParticipants>();
    for (const p of allParticipants) {
      const list = participantsBySession.get(p.session_id) ?? [];
      list.push(p);
      participantsBySession.set(p.session_id, list);
    }

    const sessionsWithParticipants = sessions.map((s) => ({
      ...s,
      participants: participantsBySession.get(s.id) ?? [],
    }));

    res.status(200).json({ sessions: sessionsWithParticipants });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:groupId/sessions/:id/join
export async function joinSession(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const sessionId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(sessionId, 'Session');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    // Use transaction + forUpdate to prevent race conditions on participant count
    const result = await db.transaction().execute(async (trx) => {
      // Lock the session row to serialize concurrent joins
      const session = await trx
        .selectFrom('focus_sessions')
        .select(['id', 'group_id', 'host_user_id', 'status', 'focus_duration_minutes', 'started_at', 'ended_at', 'created_at'])
        .where('id', '=', sessionId)
        .forUpdate()
        .executeTakeFirst();

      if (!session) {
        throw new ApplicationError('Session not found', 404, 'NOT_FOUND');
      }
      if (session.group_id !== groupId) {
        throw new ApplicationError('Session does not belong to this group', 404, 'NOT_FOUND');
      }
      if (session.status === 'ended') {
        throw new ApplicationError('Session has already ended', 409, 'SESSION_ENDED');
      }

      const activeParticipants = await trx
        .selectFrom('focus_session_participants as p')
        .innerJoin('users as u', 'p.user_id', 'u.id')
        .select(['p.id', 'p.user_id', 'u.display_name', 'p.joined_at', 'p.left_at'])
        .where('p.session_id', '=', sessionId)
        .where('p.left_at', 'is', null)
        .orderBy('p.joined_at', 'asc')
        .execute();

      const alreadyIn = activeParticipants.some((p) => p.user_id === userId);

      if (!alreadyIn && activeParticipants.length >= MAX_PARTICIPANTS) {
        // Spawn outside transaction — it's a new session
        return { spawn: true, duration: session.focus_duration_minutes } as const;
      }

      // Upsert participant within the transaction
      const existing = await trx
        .selectFrom('focus_session_participants')
        .select(['id', 'left_at'])
        .where('session_id', '=', sessionId)
        .where('user_id', '=', userId)
        .executeTakeFirst();

      if (existing) {
        if (existing.left_at !== null) {
          await trx
            .updateTable('focus_session_participants')
            .set({ left_at: null })
            .where('id', '=', existing.id)
            .execute();
        }
      } else {
        await trx
          .insertInto('focus_session_participants')
          .values({ session_id: sessionId, user_id: userId })
          .execute();
      }

      return { spawn: false, session } as const;
    });

    if (result.spawn) {
      // Check premium gate for inherited duration
      if (result.duration === 90) {
        const tier = await getUserSubscriptionTier(userId);
        if (tier !== 'premium') {
          throw new ApplicationError(
            '90-minute focus blocks require a Premium subscription',
            403,
            'PREMIUM_REQUIRED'
          );
        }
      }
      const newSession = await createSessionWithHost(groupId, userId, result.duration);
      const newParticipants = await fetchActiveParticipants(newSession.id);
      return void res.status(201).json({ session: { ...newSession, participants: newParticipants }, spawned: true });
    }

    const updatedParticipants = await fetchActiveParticipants(sessionId);
    res.status(200).json({ session: { ...result.session, participants: updatedParticipants } });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:groupId/sessions/:id/start
export async function startSession(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const sessionId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(sessionId, 'Session');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);
    const session = await fetchSession(sessionId, groupId);

    if (session.status !== 'lobby') {
      throw new ApplicationError('Session is not in lobby phase', 409, 'INVALID_STATUS');
    }
    if (session.host_user_id !== userId) {
      throw new ApplicationError('Only the host can start the session', 403, 'FORBIDDEN');
    }

    const updated = await db
      .updateTable('focus_sessions')
      .set({ status: 'focus', started_at: new Date().toISOString() })
      .where('id', '=', sessionId)
      .returningAll()
      .executeTakeFirstOrThrow();

    res.status(200).json({ session: updated });
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:groupId/sessions/:id/end
export async function endSession(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const sessionId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(sessionId, 'Session');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);
    const session = await fetchSession(sessionId, groupId);

    if (session.status === 'ended') {
      throw new ApplicationError('Session has already ended', 409, 'SESSION_ENDED');
    }
    if (session.host_user_id !== userId) {
      throw new ApplicationError('Only the host can end the session', 403, 'FORBIDDEN');
    }

    const now = new Date().toISOString();

    await db
      .updateTable('focus_session_participants')
      .set({ left_at: now })
      .where('session_id', '=', sessionId)
      .where('left_at', 'is', null)
      .execute();

    const updated = await db
      .updateTable('focus_sessions')
      .set({ status: 'ended', ended_at: now })
      .where('id', '=', sessionId)
      .returningAll()
      .executeTakeFirstOrThrow();

    sendGroupNotification(
      groupId,
      { title: 'Focus Session Ended', body: 'The focus session has ended.' },
      { type: 'session_ended', session_id: sessionId, group_id: groupId },
      { membershipStatus: 'active' }
    ).catch((err) => logger.error('FCM session_ended failed', { err }));

    res.status(200).json({ session: updated });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/groups/:groupId/sessions/:id/leave
export async function leaveSession(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const sessionId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(sessionId, 'Session');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    // Use transaction + forUpdate to prevent race conditions on host promotion
    let sessionAutoEnded = false;
    await db.transaction().execute(async (trx) => {
      // Lock the session row
      const session = await trx
        .selectFrom('focus_sessions')
        .select(['id', 'host_user_id', 'status', 'group_id'])
        .where('id', '=', sessionId)
        .forUpdate()
        .executeTakeFirst();

      if (!session) {
        throw new ApplicationError('Session not found', 404, 'NOT_FOUND');
      }
      if (session.group_id !== groupId) {
        throw new ApplicationError('Session does not belong to this group', 404, 'NOT_FOUND');
      }
      if (session.status === 'ended') {
        throw new ApplicationError('Session has already ended', 409, 'SESSION_ENDED');
      }

      const now = new Date().toISOString();

      // Mark the user as left
      await trx
        .updateTable('focus_session_participants')
        .set({ left_at: now })
        .where('session_id', '=', sessionId)
        .where('user_id', '=', userId)
        .where('left_at', 'is', null)
        .execute();

      // If the leaver was the host, promote next or end session
      if (session.host_user_id === userId) {
        const remaining = await trx
          .selectFrom('focus_session_participants')
          .select(['user_id', 'joined_at'])
          .where('session_id', '=', sessionId)
          .where('left_at', 'is', null)
          .orderBy('joined_at', 'asc')
          .execute();

        if (remaining.length > 0) {
          await trx
            .updateTable('focus_sessions')
            .set({ host_user_id: remaining[0].user_id })
            .where('id', '=', sessionId)
            .execute();
        } else {
          await trx
            .updateTable('focus_sessions')
            .set({ status: 'ended', ended_at: now })
            .where('id', '=', sessionId)
            .execute();
          sessionAutoEnded = true;
        }
      }
    });

    // Silent FCM so group members' UI refreshes without a visible notification
    if (sessionAutoEnded) {
      sendSilentGroupMessage(groupId, {
        type: 'session_ended',
        session_id: sessionId,
        group_id: groupId,
      }).catch((err) => logger.error('Silent FCM session auto-end (leaveSession) failed', { err }));
    }

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// ---------------------------------------------------------------------------
// Slot endpoints
// ---------------------------------------------------------------------------

// POST /api/groups/:groupId/slots
export async function createSlot(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    validateUuidParam(groupId, 'Group');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    const data = CreateSlotSchema.parse(req.body);

    if (data.focus_duration_minutes === 90) {
      const tier = await getUserSubscriptionTier(userId);
      if (tier !== 'premium') {
        throw new ApplicationError(
          '90-minute focus blocks require a Premium subscription',
          403,
          'PREMIUM_REQUIRED'
        );
      }
    }

    const scheduledStart = new Date(data.scheduled_start);
    if (scheduledStart <= new Date()) {
      throw new ApplicationError('scheduled_start must be in the future', 422, 'VALIDATION_ERROR');
    }

    const slot = await db
      .insertInto('focus_slots')
      .values({
        group_id: groupId,
        created_by: userId,
        scheduled_start: scheduledStart.toISOString(),
        focus_duration_minutes: data.focus_duration_minutes,
        note: data.note ?? null,
      })
      .returningAll()
      .executeTakeFirstOrThrow();

    // Notify group members
    const user = await db.selectFrom('users').select('display_name').where('id', '=', userId).executeTakeFirst();
    const timeStr = scheduledStart.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
    sendGroupNotification(
      groupId,
      {
        title: 'Focus Session Scheduled',
        body: `${user?.display_name ?? 'Someone'} scheduled a focus session for ${timeStr}`,
      },
      { type: 'slot_posted', slot_id: slot.id, group_id: groupId },
      { membershipStatus: 'active' }
    ).catch((err) => logger.error('FCM slot_posted failed', { err }));

    res.status(201).json({ slot });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:groupId/slots
export async function listSlots(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    validateUuidParam(groupId, 'Group');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    const slots = await db
      .selectFrom('focus_slots as s')
      .select(['s.id', 's.group_id', 's.created_by', 's.scheduled_start', 's.focus_duration_minutes', 's.note', 's.session_id', 's.created_at', 's.cancelled_at'])
      .where('s.group_id', '=', groupId)
      .where('s.scheduled_start', '>=', new Date())
      .where('s.cancelled_at', 'is', null)
      .orderBy('s.scheduled_start', 'asc')
      .execute();

    // Batch-fetch RSVP counts and user RSVPs (avoids N+1)
    const slotIds = slots.map((s) => s.id);
    let rsvpCounts: Array<{ slot_id: string; count: string | number | bigint }> = [];
    let myRsvps: Array<{ slot_id: string }> = [];

    if (slotIds.length > 0) {
      [rsvpCounts, myRsvps] = await Promise.all([
        db.selectFrom('focus_slot_rsvps')
          .select(['slot_id', db.fn.count('id').as('count')])
          .where('slot_id', 'in', slotIds)
          .groupBy('slot_id')
          .execute(),
        db.selectFrom('focus_slot_rsvps')
          .select('slot_id')
          .where('slot_id', 'in', slotIds)
          .where('user_id', '=', userId)
          .execute(),
      ]);
    }

    const countMap = new Map(rsvpCounts.map((r) => [r.slot_id, Number(r.count)]));
    const myRsvpSet = new Set(myRsvps.map((r) => r.slot_id));

    const slotsWithMeta = slots.map((slot) => ({
      ...slot,
      rsvp_count: countMap.get(slot.id) ?? 0,
      user_rsvped: myRsvpSet.has(slot.id),
    }));

    res.status(200).json({ slots: slotsWithMeta });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/groups/:groupId/slots/:id
export async function cancelSlot(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const slotId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(slotId, 'Slot');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    const slot = await db
      .selectFrom('focus_slots')
      .select(['id', 'group_id', 'created_by', 'cancelled_at'])
      .where('id', '=', slotId)
      .executeTakeFirst();

    if (!slot || slot.group_id !== groupId) {
      throw new ApplicationError('Slot not found', 404, 'NOT_FOUND');
    }
    if (slot.cancelled_at !== null) {
      throw new ApplicationError('Slot is already cancelled', 409, 'ALREADY_CANCELLED');
    }
    if (slot.created_by !== userId) {
      throw new ApplicationError('Only the slot creator can cancel it', 403, 'FORBIDDEN');
    }

    await db
      .updateTable('focus_slots')
      .set({ cancelled_at: new Date().toISOString() })
      .where('id', '=', slotId)
      .execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:groupId/slots/:id/rsvp
export async function rsvpSlot(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const slotId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(slotId, 'Slot');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    const slot = await db
      .selectFrom('focus_slots')
      .select(['id', 'group_id', 'created_by', 'scheduled_start', 'cancelled_at'])
      .where('id', '=', slotId)
      .executeTakeFirst();

    if (!slot || slot.group_id !== groupId) {
      throw new ApplicationError('Slot not found', 404, 'NOT_FOUND');
    }
    if (slot.cancelled_at !== null) {
      throw new ApplicationError('Cannot RSVP to a cancelled slot', 422, 'SLOT_CANCELLED');
    }
    if (new Date(slot.scheduled_start) <= new Date()) {
      throw new ApplicationError('Cannot RSVP to a slot that has already started', 422, 'SLOT_IN_PAST');
    }

    let rsvp;
    try {
      rsvp = await db
        .insertInto('focus_slot_rsvps')
        .values({ slot_id: slotId, user_id: userId })
        .returningAll()
        .executeTakeFirstOrThrow();
    } catch (err: unknown) {
      const pgErr = err as { code?: string };
      if (pgErr.code === '23505') {
        throw new ApplicationError('Already RSVPed to this slot', 409, 'ALREADY_RSVPED');
      }
      throw err;
    }

    // Check for RSVP milestone
    const countRow = await db
      .selectFrom('focus_slot_rsvps')
      .select(db.fn.count('id').as('count'))
      .where('slot_id', '=', slotId)
      .executeTakeFirst();
    const rsvpCount = Number(countRow?.count ?? 0);

    if (rsvpCount === RSVP_MILESTONE) {
      // Notify the slot creator
      const slotCreatorId = slot.created_by;
      const scheduledStart = new Date(slot.scheduled_start);
      const timeStr = scheduledStart.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true });
      sendNotificationToUser(
        slotCreatorId,
        {
          title: 'Focus Session — People Are Joining!',
          body: `${rsvpCount} people are joining your session at ${timeStr} 🎉`,
        },
        { type: 'rsvp_milestone', slot_id: slotId, rsvp_count: String(rsvpCount) }
      ).catch((err) => logger.error('FCM rsvp_milestone failed', { err }));
    }

    res.status(201).json({ rsvp });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/groups/:groupId/slots/:id/rsvp
export async function unrsvpSlot(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const groupId = String(req.params.groupId);
    const slotId = String(req.params.id);
    validateUuidParam(groupId, 'Group');
    validateUuidParam(slotId, 'Slot');
    const userId = req.user.id;

    await requireActiveGroupMember(userId, groupId);

    // Verify slot belongs to group
    const slot = await db
      .selectFrom('focus_slots')
      .select(['id', 'group_id'])
      .where('id', '=', slotId)
      .executeTakeFirst();

    if (!slot || slot.group_id !== groupId) {
      throw new ApplicationError('Slot not found', 404, 'NOT_FOUND');
    }

    await db
      .deleteFrom('focus_slot_rsvps')
      .where('slot_id', '=', slotId)
      .where('user_id', '=', userId)
      .execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// GET /api/me/slots
export async function getMySlots(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');

    const userId = req.user.id;

    // Get all groups where user is an active member
    const memberships = await db
      .selectFrom('group_memberships')
      .select('group_id')
      .where('user_id', '=', userId)
      .where('status', '=', 'active')
      .execute();

    const groupIds = memberships.map((m) => m.group_id);
    if (groupIds.length === 0) {
      return void res.status(200).json({ slots: [] });
    }

    const slots = await db
      .selectFrom('focus_slots as s')
      .innerJoin('groups as g', 's.group_id', 'g.id')
      .select([
        's.id',
        's.group_id',
        's.created_by',
        's.scheduled_start',
        's.focus_duration_minutes',
        's.note',
        's.session_id',
        's.created_at',
        's.cancelled_at',
        'g.name as group_name',
        'g.icon_emoji as group_icon_emoji',
      ])
      .where('s.group_id', 'in', groupIds)
      .where('s.scheduled_start', '>=', new Date())
      .where('s.cancelled_at', 'is', null)
      .orderBy('s.scheduled_start', 'asc')
      .execute();

    // Batch-fetch RSVP counts and user RSVPs (avoids N+1)
    const slotIds = slots.map((s) => s.id);
    let rsvpCounts: Array<{ slot_id: string; count: string | number | bigint }> = [];
    let myRsvps: Array<{ slot_id: string }> = [];

    if (slotIds.length > 0) {
      [rsvpCounts, myRsvps] = await Promise.all([
        db.selectFrom('focus_slot_rsvps')
          .select(['slot_id', db.fn.count('id').as('count')])
          .where('slot_id', 'in', slotIds)
          .groupBy('slot_id')
          .execute(),
        db.selectFrom('focus_slot_rsvps')
          .select('slot_id')
          .where('slot_id', 'in', slotIds)
          .where('user_id', '=', userId)
          .execute(),
      ]);
    }

    const countMap = new Map(rsvpCounts.map((r) => [r.slot_id, Number(r.count)]));
    const myRsvpSet = new Set(myRsvps.map((r) => r.slot_id));

    const slotsWithMeta = slots.map((slot) => ({
      ...slot,
      rsvp_count: countMap.get(slot.id) ?? 0,
      user_rsvped: myRsvpSet.has(slot.id),
    }));

    res.status(200).json({ slots: slotsWithMeta });
  } catch (error) {
    next(error);
  }
}
