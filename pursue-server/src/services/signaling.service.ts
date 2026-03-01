import type { IncomingMessage, Server } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { db } from '../database/index.js';
import { verifyAccessToken } from '../utils/jwt.js';
import { logger } from '../utils/logger.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface PeerInfo {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  ws: WebSocket;
}

interface SignalingMessage {
  type: string;
  to?: string;
  from?: string;
  sdp?: string;
  candidate?: unknown;
  phase?: 'focus' | 'chit-chat' | 'end';
  sessionId?: string;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// In-memory state: sessionId → Map<userId, PeerInfo>
// ---------------------------------------------------------------------------
const sessions = new Map<string, Map<string, PeerInfo>>();

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function send(ws: WebSocket, message: Record<string, unknown>): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(message));
  }
}

function broadcastToRoom(
  sessionId: string,
  message: Record<string, unknown>,
  excludeUserId?: string
): void {
  const room = sessions.get(sessionId);
  if (!room) return;
  for (const [uid, peer] of room) {
    if (uid !== excludeUserId) {
      send(peer.ws, message);
    }
  }
}

async function handleLeave(sessionId: string, userId: string): Promise<void> {
  const room = sessions.get(sessionId);
  if (!room) return;

  room.delete(userId);

  if (room.size === 0) {
    sessions.delete(sessionId);
  }

  broadcastToRoom(sessionId, { type: 'peer-left', peerId: userId });

  // Sync DB: mark participant as left
  const now = new Date().toISOString();
  try {
    await db
      .updateTable('focus_session_participants')
      .set({ left_at: now })
      .where('session_id', '=', sessionId)
      .where('user_id', '=', userId)
      .where('left_at', 'is', null)
      .execute();

    // Host promotion / session auto-end
    const session = await db
      .selectFrom('focus_sessions')
      .select(['host_user_id', 'status'])
      .where('id', '=', sessionId)
      .executeTakeFirst();

    if (!session || session.status === 'ended') return;

    if (session.host_user_id === userId) {
      const remaining = await db
        .selectFrom('focus_session_participants')
        .select(['user_id', 'joined_at'])
        .where('session_id', '=', sessionId)
        .where('left_at', 'is', null)
        .orderBy('joined_at', 'asc')
        .execute();

      if (remaining.length > 0) {
        await db
          .updateTable('focus_sessions')
          .set({ host_user_id: remaining[0].user_id })
          .where('id', '=', sessionId)
          .execute();
      } else {
        await db
          .updateTable('focus_sessions')
          .set({ status: 'ended', ended_at: now })
          .where('id', '=', sessionId)
          .execute();
        sessions.delete(sessionId);
      }
    }
  } catch (err) {
    logger.error('signaling handleLeave DB error', { err, sessionId, userId });
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

export function attachSignalingServer(server: Server): void {
  // No path filter — validate path manually in the connection handler
  const wss = new WebSocketServer({ server });

  wss.on('connection', async (ws: WebSocket, req: IncomingMessage) => {
    // Parse URL: /ws/sessions/:sessionId?token=<jwt>
    const rawUrl = req.url ?? '';
    const url = new URL(rawUrl, 'ws://localhost');
    const pathMatch = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);

    if (!pathMatch) {
      ws.close(4004, 'Session not found');
      return;
    }

    const sessionId = pathMatch[1];
    const token = url.searchParams.get('token');

    // 1. Authenticate
    let userId: string;
    let userEmail: string;
    try {
      if (!token) throw new Error('missing token');
      const payload = verifyAccessToken(token);
      userId = payload.user_id;
      userEmail = payload.email;
    } catch {
      ws.close(4001, 'Unauthorized');
      return;
    }

    // 2. Verify session exists and is not ended
    let session;
    try {
      session = await db
        .selectFrom('focus_sessions')
        .select(['id', 'group_id', 'status', 'host_user_id'])
        .where('id', '=', sessionId)
        .executeTakeFirst();
    } catch (err) {
      logger.error('signaling: DB error fetching session', { err });
      ws.close(1011, 'Internal error');
      return;
    }

    if (!session) {
      ws.close(4004, 'Session not found');
      return;
    }
    if (session.status === 'ended') {
      ws.close(4004, 'Session has ended');
      return;
    }

    // 3. Verify group membership
    try {
      const membership = await db
        .selectFrom('group_memberships')
        .select('status')
        .where('group_id', '=', session.group_id)
        .where('user_id', '=', userId)
        .executeTakeFirst();

      if (!membership || membership.status !== 'active') {
        ws.close(4003, 'Not a group member');
        return;
      }
    } catch (err) {
      logger.error('signaling: DB error checking membership', { err });
      ws.close(1011, 'Internal error');
      return;
    }

    // 4. Load user info
    let displayName = 'Unknown';
    let avatarUrl: string | null = null;
    try {
      const user = await db
        .selectFrom('users')
        .select(['display_name'])
        .where('id', '=', userId)
        .executeTakeFirst();
      displayName = user?.display_name ?? 'Unknown';
    } catch {
      // Non-fatal
    }

    // 5. Add to room
    if (!sessions.has(sessionId)) {
      sessions.set(sessionId, new Map());
    }
    const room = sessions.get(sessionId)!;

    // Notify existing peers
    for (const [existingUid, existingPeer] of room) {
      // Tell existing peer that a new peer joined
      send(existingPeer.ws, {
        type: 'peer-joined',
        peerId: userId,
        peerName: displayName,
        peerAvatar: avatarUrl,
      });
      // Tell new peer about existing peers
      send(ws, {
        type: 'peer-joined',
        peerId: existingUid,
        peerName: existingPeer.displayName,
        peerAvatar: existingPeer.avatarUrl,
      });
    }

    room.set(userId, { userId, displayName, avatarUrl, ws });
    logger.debug('signaling: peer joined', { sessionId, userId });

    // 6. Handle messages
    ws.on('message', async (rawData) => {
      let msg: SignalingMessage;
      try {
        msg = JSON.parse(rawData.toString()) as SignalingMessage;
      } catch {
        logger.debug('signaling: invalid JSON from client', { userId });
        return;
      }

      const currentRoom = sessions.get(sessionId);
      if (!currentRoom) return;

      switch (msg.type) {
        case 'offer':
        case 'answer':
        case 'ice-candidate': {
          // Relay to the target peer
          const targetUserId = msg.to;
          if (!targetUserId) break;
          const targetPeer = currentRoom.get(targetUserId);
          if (targetPeer) {
            send(targetPeer.ws, { ...msg, from: userId });
          }
          break;
        }

        case 'phase-change': {
          // Only host may change phase
          try {
            const currentSession = await db
              .selectFrom('focus_sessions')
              .select(['host_user_id', 'status'])
              .where('id', '=', sessionId)
              .executeTakeFirst();

            if (!currentSession || currentSession.host_user_id !== userId) {
              send(ws, { type: 'error', code: 4003, message: 'Only the host can change phase' });
              break;
            }

            const phase = msg.phase;
            if (!phase || !['focus', 'chit-chat', 'end'].includes(phase)) break;

            const now = new Date();
            const nowIso = now.toISOString();

            if (phase === 'end') {
              await db
                .updateTable('focus_session_participants')
                .set({ left_at: nowIso })
                .where('session_id', '=', sessionId)
                .where('left_at', 'is', null)
                .execute();
              await db
                .updateTable('focus_sessions')
                .set({ status: 'ended', ended_at: nowIso })
                .where('id', '=', sessionId)
                .execute();
              broadcastToRoom(sessionId, { type: 'session-ended' });
              sessions.delete(sessionId);
            } else {
              const newStatus = phase === 'focus' ? 'focus' : 'chit-chat';
              const updates: Record<string, string | null> = { status: newStatus };
              if (phase === 'focus') updates.started_at = nowIso;

              await db
                .updateTable('focus_sessions')
                .set(updates)
                .where('id', '=', sessionId)
                .execute();

              // Calculate endsAt for focus or chit-chat
              const sessionRow = await db
                .selectFrom('focus_sessions')
                .select('focus_duration_minutes')
                .where('id', '=', sessionId)
                .executeTakeFirst();

              const minutes = newStatus === 'focus'
                ? (sessionRow?.focus_duration_minutes ?? 25)
                : 10; // chit-chat is always 10 min
              const endsAt = new Date(now.getTime() + minutes * 60 * 1000).toISOString();

              broadcastToRoom(sessionId, {
                type: 'phase-changed',
                phase: newStatus,
                timer: { endsAt },
              });
            }
          } catch (err) {
            logger.error('signaling: phase-change DB error', { err, sessionId });
          }
          break;
        }

        case 'leave': {
          await handleLeave(sessionId, userId);
          ws.close(1000, 'left');
          break;
        }

        default:
          logger.debug('signaling: unknown message type', { type: msg.type, userId });
      }
    });

    // 7. Handle disconnect
    ws.on('close', async () => {
      await handleLeave(sessionId, userId);
      logger.debug('signaling: peer disconnected', { sessionId, userId });
    });

    ws.on('error', (err) => {
      logger.error('signaling: WebSocket error', { err: err.message, sessionId, userId });
    });
  });

  logger.info('Signaling server attached at /ws');
}
