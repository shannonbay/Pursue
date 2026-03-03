import { createClient, type SupabaseClient, type RealtimeChannel } from '@supabase/supabase-js';
import crypto from 'node:crypto';
import { logger } from '../utils/logger.js';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface RemotePeer {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
}

interface PresenceState {
  peers: RemotePeer[];
  instanceId: string;
}

type OnRemoteMessageFn = (
  sessionId: string,
  msg: Record<string, unknown>
) => void;

type OnRemotePresenceChangeFn = (
  sessionId: string,
  peers: RemotePeer[]
) => void;

interface RelayCallbacks {
  onRemoteMessage: OnRemoteMessageFn;
  onRemotePresenceJoin: OnRemotePresenceChangeFn;
  onRemotePresenceLeave: OnRemotePresenceChangeFn;
}

// ---------------------------------------------------------------------------
// Singleton state
// ---------------------------------------------------------------------------

const INSTANCE_ID = crypto.randomUUID();

const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_SECRET_KEY;
const isEnabled = !!(supabaseUrl && supabaseKey);

let supabase: SupabaseClient | null = null;
if (isEnabled) {
  supabase = createClient(supabaseUrl!, supabaseKey!, {
    realtime: { params: { eventsPerSecond: 100 } },
  });
}

/** Active Realtime channels keyed by sessionId */
const channels = new Map<string, RealtimeChannel>();

/** Cached remote presence per session: instanceId -> RemotePeer[] */
const remotePresenceCache = new Map<string, Map<string, RemotePeer[]>>();
/** Latest local presence we want tracked for each session */
const localPresenceCache = new Map<string, RemotePeer[]>();

let callbacks: RelayCallbacks | null = null;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function channelName(sessionId: string): string {
  return `signal:${sessionId}`;
}

function trackLocalPresence(sessionId: string, channel: RealtimeChannel): void {
  const peers = localPresenceCache.get(sessionId) ?? [];
  channel.track({ peers, instanceId: INSTANCE_ID }).catch((err) => {
    const message = err instanceof Error ? err.message : String(err);
    // This commonly happens if track is called before SUBSCRIBED; subscribe callback retries.
    logger.warn('signaling relay: presence track failed (will retry on subscribe)', {
      sessionId,
      err: message,
    });
  });
}

/**
 * Diff cached presence against new snapshot from Supabase, invoking join/leave
 * callbacks with per-peer granularity.
 */
function diffPresence(sessionId: string, channel: RealtimeChannel): void {
  if (!callbacks) return;

  const rawState = channel.presenceState<PresenceState>();
  const oldCache = remotePresenceCache.get(sessionId) ?? new Map<string, RemotePeer[]>();
  const newCache = new Map<string, RemotePeer[]>();

  // Build new cache from presence state (skip own instance)
  for (const [key, presences] of Object.entries(rawState)) {
    // Each key is an instance's presence key; presences is an array of tracked objects
    for (const p of presences as PresenceState[]) {
      if (p.instanceId === INSTANCE_ID) continue;
      newCache.set(key, p.peers ?? []);
    }
  }

  // Compute joined peers (in new but not in old)
  const joinedPeers: RemotePeer[] = [];
  for (const [instId, peers] of newCache) {
    const oldPeers = oldCache.get(instId) ?? [];
    const oldUserIds = new Set(oldPeers.map((p) => p.userId));
    for (const peer of peers) {
      if (!oldUserIds.has(peer.userId)) {
        joinedPeers.push(peer);
      }
    }
  }

  // Compute left peers (in old but not in new)
  const leftPeers: RemotePeer[] = [];
  for (const [instId, oldPeers] of oldCache) {
    const newPeers = newCache.get(instId) ?? [];
    const newUserIds = new Set(newPeers.map((p) => p.userId));
    for (const peer of oldPeers) {
      if (!newUserIds.has(peer.userId)) {
        leftPeers.push(peer);
      }
    }
  }

  // Update cache
  remotePresenceCache.set(sessionId, newCache);

  // Fire callbacks
  if (joinedPeers.length > 0) {
    callbacks.onRemotePresenceJoin(sessionId, joinedPeers);
  }
  if (leftPeers.length > 0) {
    callbacks.onRemotePresenceLeave(sessionId, leftPeers);
  }
}

// ---------------------------------------------------------------------------
// Channel lifecycle
// ---------------------------------------------------------------------------

function getOrCreateChannel(sessionId: string): RealtimeChannel {
  const existing = channels.get(sessionId);
  if (existing) return existing;

  const channel = supabase!.channel(channelName(sessionId), {
    config: {
      broadcast: { self: false },
      presence: { key: INSTANCE_ID },
    },
  });

  // Listen for broadcast relay messages
  channel.on('broadcast', { event: 'relay' }, (payload) => {
    if (!callbacks) return;

    const msg = payload.payload as Record<string, unknown>;
    if (!msg) return;

    // Safety: skip messages from own instance (broadcast.self is false, but belt-and-suspenders)
    if (msg._instanceId === INSTANCE_ID) return;

    callbacks.onRemoteMessage(sessionId, msg);
  });

  // Listen for presence sync
  channel.on('presence', { event: 'sync' }, () => {
    diffPresence(sessionId, channel);
  });

  channel.subscribe((status) => {
    if (status === 'SUBSCRIBED') {
      logger.info('signaling relay: channel subscribed', {
        sessionId,
        channelName: channelName(sessionId),
        instanceId: INSTANCE_ID,
      });
      // Ensure latest local state is present even if an earlier track call raced subscribe.
      trackLocalPresence(sessionId, channel);
    } else if (status === 'CHANNEL_ERROR') {
      logger.error('signaling relay: channel error', { sessionId });
    }
  });

  channels.set(sessionId, channel);
  return channel;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export const signalingRelay = {
  /** Whether the relay is enabled (Supabase env vars are set) */
  get enabled(): boolean {
    return isEnabled;
  },

  /** Unique identifier for this Cloud Run instance */
  get instanceId(): string {
    return INSTANCE_ID;
  },

  /** Register callbacks from signaling.service.ts (call once at startup) */
  setCallbacks(cbs: RelayCallbacks): void {
    callbacks = cbs;
  },

  /**
   * Sync this instance's local peers for a session into Supabase Presence.
   * Call after any local peer join/leave.
   */
  syncPresence(sessionId: string, localPeers: RemotePeer[]): void {
    if (!isEnabled) return;

    localPresenceCache.set(sessionId, localPeers);
    const channel = getOrCreateChannel(sessionId);
    trackLocalPresence(sessionId, channel);
  },

  /**
   * Send a targeted message to a specific user on another instance.
   */
  sendToRemotePeer(
    sessionId: string,
    targetUserId: string,
    payload: Record<string, unknown>
  ): void {
    if (!isEnabled) return;

    const channel = getOrCreateChannel(sessionId);
    channel
      .send({
        type: 'broadcast',
        event: 'relay',
        payload: { ...payload, targetUserId, _instanceId: INSTANCE_ID },
      })
      .catch((err) => {
        logger.error('signaling relay: sendToRemotePeer failed', {
          err,
          sessionId,
          targetUserId,
        });
      });
  },

  /**
   * Broadcast a message to all remote instances (room-wide events like phase-changed).
   */
  broadcastToRemotePeers(
    sessionId: string,
    payload: Record<string, unknown>,
    excludeUserId?: string
  ): void {
    if (!isEnabled) return;

    const channel = getOrCreateChannel(sessionId);
    channel
      .send({
        type: 'broadcast',
        event: 'relay',
        payload: { ...payload, excludeUserId, _instanceId: INSTANCE_ID },
      })
      .catch((err) => {
        logger.error('signaling relay: broadcastToRemotePeers failed', {
          err,
          sessionId,
        });
      });
  },

  /**
   * Get all remote peers for a session (from cached presence state).
   */
  getRemotePeers(sessionId: string): RemotePeer[] {
    const cache = remotePresenceCache.get(sessionId);
    if (!cache) return [];

    const peers: RemotePeer[] = [];
    for (const instancePeers of cache.values()) {
      peers.push(...instancePeers);
    }
    return peers;
  },

  /**
   * Remove channel for a session (when last local peer leaves).
   */
  removeChannel(sessionId: string): void {
    if (!isEnabled) return;

    const channel = channels.get(sessionId);
    if (!channel) return;

    channel.untrack().catch((err) => {
      logger.error('signaling relay: untrack failed', { err, sessionId });
    });

    supabase!.removeChannel(channel).catch((err) => {
      logger.error('signaling relay: removeChannel failed', { err, sessionId });
    });

    channels.delete(sessionId);
    remotePresenceCache.delete(sessionId);
    localPresenceCache.delete(sessionId);

    logger.info('signaling relay: channel removed', { sessionId, instanceId: INSTANCE_ID });
  },
};
