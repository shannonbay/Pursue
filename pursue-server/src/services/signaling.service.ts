import type { IncomingMessage, Server } from 'node:http';
import { Router } from 'express';
import { WebSocketServer, WebSocket } from 'ws';
import rateLimit from 'express-rate-limit';
import { db } from '../database/index.js';
import { verifyAccessToken } from '../utils/jwt.js';
import { authenticate } from '../middleware/authenticate.js';
import type { AuthRequest } from '../types/express.js';
import { logger } from '../utils/logger.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface PeerInfo {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  send: (message: Record<string, unknown>) => void;
  close: () => void;
}

interface SignalingMessage {
  type: string;
  to?: string;
  from?: string;
  sdp?: string;
  candidate?: unknown;
  phase?: 'focus' | 'chit-chat' | 'end';
  sessionId?: string;
}

class SignalingError extends Error {
  constructor(public code: number, message: string) {
    super(message);
  }
}

// ---------------------------------------------------------------------------
// In-memory state: sessionId -> Map<userId, PeerInfo>
// ---------------------------------------------------------------------------
const sessions = new Map<string, Map<string, PeerInfo>>();

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function broadcastToRoom(
  sessionId: string,
  message: Record<string, unknown>,
  excludeUserId?: string
): void {
  const room = sessions.get(sessionId);
  if (!room) return;
  for (const [uid, peer] of room) {
    if (uid !== excludeUserId) {
      peer.send(message);
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
// Shared auth + validation
// ---------------------------------------------------------------------------

interface ValidatedPeer {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
}

async function authenticateAndValidateSession(
  sessionId: string,
  token: string
): Promise<ValidatedPeer> {
  // 1. Verify JWT
  if (!token) throw new SignalingError(4001, 'Unauthorized');
  let userId: string;
  try {
    const payload = verifyAccessToken(token);
    userId = payload.user_id;
  } catch {
    throw new SignalingError(4001, 'Unauthorized');
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
    throw new SignalingError(1011, 'Internal error');
  }

  if (!session) throw new SignalingError(4004, 'Session not found');
  if (session.status === 'ended') throw new SignalingError(4004, 'Session has ended');

  // 3. Verify group membership
  try {
    const membership = await db
      .selectFrom('group_memberships')
      .select('status')
      .where('group_id', '=', session.group_id)
      .where('user_id', '=', userId)
      .executeTakeFirst();

    if (!membership || membership.status !== 'active') {
      throw new SignalingError(4003, 'Not a group member');
    }
  } catch (err) {
    if (err instanceof SignalingError) throw err;
    logger.error('signaling: DB error checking membership', { err });
    throw new SignalingError(1011, 'Internal error');
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

  return { userId, displayName, avatarUrl };
}

// ---------------------------------------------------------------------------
// Shared room management
// ---------------------------------------------------------------------------

function addPeerToRoom(sessionId: string, peer: PeerInfo): void {
  if (!sessions.has(sessionId)) {
    sessions.set(sessionId, new Map());
  }
  const room = sessions.get(sessionId)!;

  // Notify existing peers about new peer, and tell new peer about existing peers
  for (const [existingUid, existingPeer] of room) {
    existingPeer.send({
      type: 'peer-joined',
      peerId: peer.userId,
      peerName: peer.displayName,
      peerAvatar: peer.avatarUrl,
    });
    peer.send({
      type: 'peer-joined',
      peerId: existingUid,
      peerName: existingPeer.displayName,
      peerAvatar: existingPeer.avatarUrl,
    });
  }

  room.set(peer.userId, peer);
  logger.info('signaling: peer joined room', {
    sessionId,
    userId: peer.userId,
    roomSize: room.size,
  });
}

async function sendCurrentPhaseToNewPeer(sessionId: string, peer: PeerInfo): Promise<void> {
  try {
    const session = await db
      .selectFrom('focus_sessions')
      .select(['status', 'started_at', 'focus_duration_minutes'])
      .where('id', '=', sessionId)
      .executeTakeFirst();

    if (!session || session.status === 'lobby' || session.status === 'ended') return;

    const startedAt = session.started_at ? new Date(session.started_at).getTime() : Date.now();
    const durationMs =
      session.status === 'focus'
        ? (session.focus_duration_minutes ?? 25) * 60 * 1000
        : 10 * 60 * 1000; // chit-chat always 10 min
    const endsAt = new Date(startedAt + durationMs).toISOString();

    peer.send({
      type: 'phase-changed',
      phase: session.status,       // "focus" or "chit-chat"
      timer: { endsAt },
    });
  } catch (err) {
    logger.error('signaling: failed to send current phase to new peer', { err, sessionId });
  }
}

// ---------------------------------------------------------------------------
// Shared message handler
// ---------------------------------------------------------------------------

async function processSignalingMessage(
  sessionId: string,
  userId: string,
  msg: SignalingMessage,
  senderSend: (msg: Record<string, unknown>) => void
): Promise<void> {
  const currentRoom = sessions.get(sessionId);
  if (!currentRoom) return;

  switch (msg.type) {
    case 'offer':
    case 'answer': {
      // Relay only known signaling fields — never spread raw client data
      const targetUserId = msg.to;
      if (!targetUserId) break;
      const targetPeer = currentRoom.get(targetUserId);
      if (targetPeer) {
        targetPeer.send({ type: msg.type, from: userId, to: msg.to, sdp: msg.sdp });
      }
      break;
    }
    case 'ice-candidate': {
      const targetUserId = msg.to;
      if (!targetUserId) break;
      const targetPeer = currentRoom.get(targetUserId);
      if (targetPeer) {
        targetPeer.send({
          type: 'ice-candidate',
          from: userId,
          to: msg.to,
          candidate: msg.candidate,
        });
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
          senderSend({
            type: 'error',
            code: 4003,
            message: 'Only the host can change phase',
          });
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
          const updates: Record<string, string | null> = { status: newStatus, started_at: nowIso };

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

          const minutes =
            newStatus === 'focus'
              ? (sessionRow?.focus_duration_minutes ?? 25)
              : 10; // chit-chat is always 10 min
          const endsAt = new Date(
            now.getTime() + minutes * 60 * 1000
          ).toISOString();

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
      const peer = currentRoom.get(userId);
      await handleLeave(sessionId, userId);
      peer?.close();
      break;
    }

    default:
      logger.debug('signaling: unknown message type', {
        type: msg.type,
        userId,
      });
  }
}

// ---------------------------------------------------------------------------
// WebSocket handler (for local development + integration tests)
// ---------------------------------------------------------------------------

export function attachSignalingServer(server: Server): void {
  // Canonical setup: let ws handle the HTTP upgrade internally.
  // The ws library registers its own server.on('upgrade') handler.
  const wss = new WebSocketServer({ server, maxPayload: 4 * 1024 });

  wss.on('connection', async (ws: WebSocket, req: IncomingMessage) => {
    logger.info('signaling: WebSocket connection established', {
      url: req.url,
      remoteAddress: req.socket.remoteAddress,
    });

    // Register error handler early to prevent unhandled errors during async setup
    ws.on('error', (err) => {
      logger.error('signaling: WebSocket error', { err: err.message });
    });

    // Parse URL: /ws/sessions/:sessionId?token=<jwt>
    const rawUrl = req.url ?? '';
    const url = new URL(rawUrl, 'ws://localhost');
    const pathMatch = url.pathname.match(/^\/ws\/sessions\/([^/]+)$/);

    if (!pathMatch) {
      ws.close(4004, 'Session not found');
      return;
    }

    const sessionId = pathMatch[1];
    const token = url.searchParams.get('token') ?? '';

    // Authenticate and validate
    let validated: ValidatedPeer;
    try {
      validated = await authenticateAndValidateSession(sessionId, token);
    } catch (err) {
      if (err instanceof SignalingError) {
        ws.close(err.code, err.message);
      } else {
        logger.error('signaling: unexpected auth error', { err });
        ws.close(1011, 'Internal error');
      }
      return;
    }

    const { userId, displayName, avatarUrl } = validated;

    // Create transport-agnostic peer
    const peer: PeerInfo = {
      userId,
      displayName,
      avatarUrl,
      send: (msg) => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(msg));
        }
      },
      close: () => {
        if (
          ws.readyState === WebSocket.OPEN ||
          ws.readyState === WebSocket.CONNECTING
        ) {
          ws.close(1000, 'left');
        }
      },
    };

    // Add to room
    addPeerToRoom(sessionId, peer);
    await sendCurrentPhaseToNewPeer(sessionId, peer);

    // Handle messages (with per-connection rate limiting)
    const MESSAGE_RATE_LIMIT = 30; // messages per window
    const MESSAGE_RATE_WINDOW_MS = 10_000; // 10 seconds
    let messageCount = 0;
    let windowStart = Date.now();

    ws.on('message', async (rawData) => {
      // Per-connection rate limiting
      const now = Date.now();
      if (now - windowStart > MESSAGE_RATE_WINDOW_MS) {
        messageCount = 0;
        windowStart = now;
      }
      messageCount++;
      if (messageCount > MESSAGE_RATE_LIMIT) {
        logger.debug('signaling: rate limit exceeded', { userId });
        ws.close(4029, 'Rate limit exceeded');
        return;
      }

      let msg: SignalingMessage;
      try {
        msg = JSON.parse(rawData.toString()) as SignalingMessage;
      } catch {
        logger.debug('signaling: invalid JSON from client', { userId });
        return;
      }

      await processSignalingMessage(sessionId, userId, msg, peer.send);
    });

    // Handle disconnect
    ws.on('close', async () => {
      await handleLeave(sessionId, userId);
      logger.debug('signaling: peer disconnected', { sessionId, userId });
    });
  });

  logger.info('Signaling server attached at /ws');
}

// ---------------------------------------------------------------------------
// SSE + POST router (for Cloud Run / HTTP-only environments)
// ---------------------------------------------------------------------------

const isTest = process.env.NODE_ENV === 'test';

const signalingPostLimiter = rateLimit({
  skip: () => isTest,
  windowMs: 10_000, // 10 seconds (matches WS rate limit window)
  max: 30, // 30 messages per 10s (matches WS rate limit)
  keyGenerator: (req) => {
    const authReq = req as AuthRequest;
    return `signal:${authReq.user?.id || 'unknown'}`;
  },
  message: {
    error: {
      message: 'Rate limit exceeded',
      code: 'SIGNALING_RATE_LIMIT_EXCEEDED',
    },
  },
  standardHeaders: true,
  legacyHeaders: false,
});

export function createSignalingRouter(): Router {
  const router = Router();

  // -------------------------------------------------------------------------
  // GET /:sessionId/stream?token=<jwt>  — SSE event stream
  //
  // Long-lived connection for receiving signaling events. Auth via query param
  // because SSE (EventSource) does not support custom headers.
  // -------------------------------------------------------------------------
  router.get('/:sessionId/stream', async (req, res) => {
    const sessionId = req.params.sessionId as string;
    const token = (req.query.token as string) ?? '';

    // Authenticate and validate
    let validated: ValidatedPeer;
    try {
      validated = await authenticateAndValidateSession(sessionId, token);
    } catch (err) {
      if (err instanceof SignalingError) {
        const httpStatus =
          err.code === 4001 ? 401 : err.code === 4003 ? 403 : 404;
        res
          .status(httpStatus)
          .json({ error: { message: err.message, code: `SIGNALING_${err.code}` } });
      } else {
        logger.error('signaling SSE: unexpected auth error', { err });
        res
          .status(500)
          .json({ error: { message: 'Internal error', code: 'INTERNAL_ERROR' } });
      }
      return;
    }

    const { userId, displayName, avatarUrl } = validated;

    logger.info('signaling SSE: stream opened', { sessionId, userId });

    // Set SSE headers
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no', // Disable nginx/proxy buffering
    });
    res.flushHeaders();

    // Send initial connected event
    res.write(`data: ${JSON.stringify({ type: 'connected', userId })}\n\n`);

    // Create transport-agnostic peer
    const peer: PeerInfo = {
      userId,
      displayName,
      avatarUrl,
      send: (msg) => {
        if (!res.writableEnded) {
          res.write(`data: ${JSON.stringify(msg)}\n\n`);
        }
      },
      close: () => {
        if (!res.writableEnded) {
          res.end();
        }
      },
    };

    // Add to room
    addPeerToRoom(sessionId, peer);
    await sendCurrentPhaseToNewPeer(sessionId, peer);

    // Heartbeat every 30 seconds to keep connection alive through proxies
    const heartbeatInterval = setInterval(() => {
      if (!res.writableEnded) {
        res.write(': heartbeat\n\n');
      } else {
        clearInterval(heartbeatInterval);
      }
    }, 30_000);

    // Handle client disconnect
    req.on('close', async () => {
      clearInterval(heartbeatInterval);
      await handleLeave(sessionId, userId);
      logger.debug('signaling SSE: client disconnected', { sessionId, userId });
    });
  });

  // -------------------------------------------------------------------------
  // POST /:sessionId/send  — send a signaling message
  //
  // Auth via standard Bearer token (authenticate middleware). The user must
  // already be connected to the session via SSE (present in the room).
  // -------------------------------------------------------------------------
  router.post(
    '/:sessionId/send',
    authenticate as any,
    signalingPostLimiter,
    async (req, res) => {
      const sessionId = req.params.sessionId as string;
      const userId = (req as AuthRequest).user!.id;

      // Verify user is in the room (connected via SSE)
      const room = sessions.get(sessionId);
      if (!room || !room.has(userId)) {
        res.status(403).json({
          error: {
            message: 'Not connected to this session',
            code: 'NOT_IN_SESSION',
          },
        });
        return;
      }

      const msg = req.body as SignalingMessage;
      if (!msg || !msg.type) {
        res.status(400).json({
          error: { message: 'Missing message type', code: 'INVALID_MESSAGE' },
        });
        return;
      }

      const peer = room.get(userId)!;
      await processSignalingMessage(sessionId, userId, msg, peer.send);

      res.status(200).json({ ok: true });
    }
  );

  return router;
}
