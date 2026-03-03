/**
 * WebSocket Signaling Server Integration Tests
 *
 * These tests spin up a real HTTP server (not just the Express app) so that
 * the WebSocketServer can attach to it on the /ws path.
 */
import http from 'node:http';
import { WebSocket } from 'ws';
import { app } from '../../../src/app';
import { attachSignalingServer } from '../../../src/services/signaling.service';
import { generateAccessToken } from '../../../src/utils/jwt';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  randomEmail,
  createFocusSession,
} from '../../helpers';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  sendSilentGroupMessage: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: string) => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
}));

// ---------------------------------------------------------------------------
// Server lifecycle
// ---------------------------------------------------------------------------
let server: http.Server;
let port: number;

beforeAll((done) => {
  server = http.createServer(app);
  attachSignalingServer(server);
  server.listen(0, '127.0.0.1', () => {
    const addr = server.address();
    port = typeof addr === 'object' && addr ? addr.port : 0;
    done();
  });
});

afterAll((done) => {
  server.close(done);
});

// ---------------------------------------------------------------------------
// Helper: open a WebSocket connection and wait for it to open/close/error
// ---------------------------------------------------------------------------
function connectWs(sessionId: string, token: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/sessions/${sessionId}?token=${token}`);
    const onError = (err: Error) => reject(err);
    ws.once('open', () => {
      ws.removeListener('error', onError);
      resolve(ws);
    });
    ws.once('error', onError);
    ws.once('unexpected-response', (_req, res) => {
      reject(new Error(`Unexpected response: ${res.statusCode}`));
    });
  });
}

function waitForClose(ws: WebSocket, timeoutMs = 5000): Promise<{ code: number; reason: string }> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timeout waiting for close')), timeoutMs);
    ws.once('close', (code, reason) => {
      clearTimeout(timer);
      resolve({ code, reason: reason.toString() });
    });
    ws.once('error', (err) => {
      clearTimeout(timer);
      // If the connection was refused outright, treat as close code 0
      resolve({ code: 0, reason: err.message });
    });
    ws.once('unexpected-response', (_req, res) => {
      clearTimeout(timer);
      resolve({ code: res.statusCode ?? 0, reason: 'unexpected-response' });
    });
  });
}

function waitForMessage(ws: WebSocket, type: string, timeoutMs = 2000): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timeout waiting for message type: ${type}`)), timeoutMs);
    const handler = (data: Buffer | string) => {
      try {
        const msg = JSON.parse(data.toString()) as Record<string, unknown>;
        if (msg.type === type) {
          clearTimeout(timer);
          ws.off('message', handler);
          resolve(msg);
        }
      } catch {
        // ignore parse errors
      }
    };
    ws.on('message', handler);
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe('WebSocket Signaling Server', () => {
  describe('Connection authentication', () => {
    it('rejects connection with invalid token (close code 4001)', async () => {
      // We need a real session in the DB for the path to matter — but with invalid token
      // the server closes before checking the session, so a fake ID is fine
      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/sessions/00000000-0000-0000-0000-000000000000?token=invalid`);
      const { code } = await waitForClose(ws);
      expect(code).toBe(4001);
    });

    it('rejects connection with missing token (close code 4001)', async () => {
      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/sessions/00000000-0000-0000-0000-000000000000`);
      const { code } = await waitForClose(ws);
      expect(code).toBe(4001);
    });

    it('rejects connection to non-existent session (close code 4004)', async () => {
      const { userId } = await createAuthenticatedUser(randomEmail());
      const fakeToken = generateAccessToken(userId, 'test@example.com');
      const fakeSessionId = '00000000-0000-0000-0000-000000000099';

      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/sessions/${fakeSessionId}?token=${fakeToken}`);
      const { code } = await waitForClose(ws);
      expect(code).toBe(4004);
    });

    it('rejects connection to ended session (close code 4004)', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, userId, { status: 'ended' });

      const fakeToken = generateAccessToken(userId, 'host@example.com');
      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/sessions/${sessionId}?token=${fakeToken}`);
      const { code } = await waitForClose(ws);
      expect(code).toBe(4004);
    });

    it('rejects non-group-member (close code 4003)', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);

      // Create an outsider user
      const { userId: outsiderId } = await createAuthenticatedUser(randomEmail());
      const outsiderToken = generateAccessToken(outsiderId, 'outsider@example.com');

      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/sessions/${sessionId}?token=${outsiderToken}`);
      const { code } = await waitForClose(ws);
      expect(code).toBe(4003);
    });

    it('rejects unknown URL path (close code 4004)', async () => {
      const { userId } = await createAuthenticatedUser(randomEmail());
      const token = generateAccessToken(userId, 'test@example.com');

      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/unknown-path?token=${token}`);
      const { code } = await waitForClose(ws);
      expect(code).toBe(4004);
    });
  });

  describe('Peer presence', () => {
    it('two peers connect — each receives peer-joined event', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const memberToken = generateAccessToken(memberId, 'member@example.com');

      // First peer connects
      const ws1 = await connectWs(sessionId, hostToken2);

      // Collect messages from peer1 in parallel while peer2 connects
      const peerJoinedPromise = waitForMessage(ws1, 'peer-joined');

      // Second peer connects
      const ws2 = await connectWs(sessionId, memberToken);

      const peerJoinedMsg = await peerJoinedPromise;
      expect(peerJoinedMsg.type).toBe('peer-joined');
      expect(peerJoinedMsg.peerId).toBe(memberId);

      ws1.close();
      ws2.close();
      await Promise.all([waitForClose(ws1), waitForClose(ws2)]).catch(() => {});
    });

    it('peer-left sent when a client disconnects', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const memberToken = generateAccessToken(memberId, 'member@example.com');

      const ws1 = await connectWs(sessionId, hostToken2);
      const ws2 = await connectWs(sessionId, memberToken);

      // Wait for peer-joined so both peers are in the room
      await waitForMessage(ws1, 'peer-joined').catch(() => {});

      const peerLeftPromise = waitForMessage(ws1, 'peer-left');

      // Disconnect peer2
      ws2.close();

      const peerLeftMsg = await peerLeftPromise;
      expect(peerLeftMsg.peerId).toBe(memberId);

      ws1.close();
      await waitForClose(ws1).catch(() => {});
    });
  });

  describe('Message relay', () => {
    it('offer message is relayed to target peer', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const memberToken = generateAccessToken(memberId, 'member@example.com');

      const ws1 = await connectWs(sessionId, hostToken2);
      const ws2 = await connectWs(sessionId, memberToken);

      // Wait for both peers to be in the room
      await waitForMessage(ws1, 'peer-joined').catch(() => {});

      const offerPromise = waitForMessage(ws2, 'offer');

      ws1.send(JSON.stringify({ type: 'offer', to: memberId, sdp: 'test-sdp' }));

      const offerMsg = await offerPromise;
      expect(offerMsg.type).toBe('offer');
      expect(offerMsg.sdp).toBe('test-sdp');
      expect(offerMsg.from).toBe(hostId);

      ws1.close();
      ws2.close();
      await Promise.all([waitForClose(ws1), waitForClose(ws2)]).catch(() => {});
    });
  });

  describe('Phase changes', () => {
    it('host can broadcast phase-changed to room', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const memberToken = generateAccessToken(memberId, 'member@example.com');

      const ws1 = await connectWs(sessionId, hostToken2);
      const ws2 = await connectWs(sessionId, memberToken);

      // Wait for peer-joined
      await waitForMessage(ws1, 'peer-joined').catch(() => {});

      const phaseChangedOnMember = waitForMessage(ws2, 'phase-changed');
      const phaseChangedOnHost = waitForMessage(ws1, 'phase-changed');

      ws1.send(JSON.stringify({ type: 'phase-change', phase: 'focus' }));

      const [memberMsg, hostMsg] = await Promise.all([phaseChangedOnMember, phaseChangedOnHost]);
      expect(memberMsg.phase).toBe('focus');
      expect(hostMsg.phase).toBe('focus');
      expect(memberMsg.timer).toBeDefined();

      // Verify DB updated
      const session = await testDb
        .selectFrom('focus_sessions')
        .select('status')
        .where('id', '=', sessionId)
        .executeTakeFirst();
      expect(session?.status).toBe('focus');

      ws1.close();
      ws2.close();
      await Promise.all([waitForClose(ws1), waitForClose(ws2)]).catch(() => {});
    });

    it('relay does not forward arbitrary extra fields (Issue #3)', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const memberToken = generateAccessToken(memberId, 'member@example.com');

      const ws1 = await connectWs(sessionId, hostToken2);
      const ws2 = await connectWs(sessionId, memberToken);

      await waitForMessage(ws1, 'peer-joined').catch(() => {});

      const offerPromise = waitForMessage(ws2, 'offer');

      // Send offer with extra malicious fields
      ws1.send(JSON.stringify({
        type: 'offer',
        to: memberId,
        sdp: 'test-sdp',
        malicious: 'injected-data',
        __proto__: 'attack',
      }));

      const offerMsg = await offerPromise;
      expect(offerMsg.type).toBe('offer');
      expect(offerMsg.sdp).toBe('test-sdp');
      expect(offerMsg.from).toBe(hostId);
      // Extra fields should NOT be present
      expect(offerMsg).not.toHaveProperty('malicious');
      // Only expected fields should be present
      expect(Object.keys(offerMsg).sort()).toEqual(['from', 'sdp', 'to', 'type']);

      ws1.close();
      ws2.close();
      await Promise.all([waitForClose(ws1), waitForClose(ws2)]).catch(() => {});
    });

    it('non-host phase-change is rejected with error message', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const memberToken = generateAccessToken(memberId, 'member@example.com');

      const ws1 = await connectWs(sessionId, hostToken2);
      const ws2 = await connectWs(sessionId, memberToken);

      // Wait for peer-joined
      await waitForMessage(ws1, 'peer-joined').catch(() => {});

      const errorPromise = waitForMessage(ws2, 'error');

      // Member tries to change phase
      ws2.send(JSON.stringify({ type: 'phase-change', phase: 'focus' }));

      const errorMsg = await errorPromise;
      expect(errorMsg.code).toBe(4003);

      ws1.close();
      ws2.close();
      await Promise.all([waitForClose(ws1), waitForClose(ws2)]).catch(() => {});
    });
  });

  describe('Reconnection resilience', () => {
    it('stale close handler does not evict a reconnected user', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);
      const { memberUserId: memberId } = await addMemberToGroup(hostToken, groupId);

      const hostJwt = generateAccessToken(hostId, 'host@example.com');
      const memberJwt = generateAccessToken(memberId, 'member@example.com');

      // Host connects
      const wsHost = await connectWs(sessionId, hostJwt);

      // Member opens first connection (ws1)
      const peerJoinedOnHost1 = waitForMessage(wsHost, 'peer-joined');
      const ws1 = await connectWs(sessionId, memberJwt);
      const joined1 = await peerJoinedOnHost1;
      expect(joined1.peerId).toBe(memberId);

      // Host should see peer-left (old connection replaced) then peer-joined (new connection)
      const peerLeftOnHost = waitForMessage(wsHost, 'peer-left');
      const peerJoinedOnHost2 = waitForMessage(wsHost, 'peer-joined');

      // Member opens second connection (ws2) — simulates reconnect
      const ws2 = await connectWs(sessionId, memberJwt);

      // Server replaces ws1 with ws2: host sees peer-left then peer-joined
      const left = await peerLeftOnHost;
      expect(left.peerId).toBe(memberId);
      const joined2 = await peerJoinedOnHost2;
      expect(joined2.peerId).toBe(memberId);

      // Now close the stale ws1 — this should NOT trigger another peer-left
      // because the server should recognise the stale connectionId
      ws1.close();
      // Give the stale close handler time to fire
      await new Promise((r) => setTimeout(r, 500));

      // Verify ws2 can still relay messages through the room
      const offerPromise = waitForMessage(ws2, 'offer');
      wsHost.send(JSON.stringify({ type: 'offer', to: memberId, sdp: 'reconnect-test' }));
      const offerMsg = await offerPromise;
      expect(offerMsg.sdp).toBe('reconnect-test');
      expect(offerMsg.from).toBe(hostId);

      // Verify member also sees the host as a peer (ws2 got peer-joined for host)
      // Host sends to member, member responds — proves both directions work
      const answerPromise = waitForMessage(wsHost, 'answer');
      ws2.send(JSON.stringify({ type: 'answer', to: hostId, sdp: 'reconnect-answer' }));
      const answerMsg = await answerPromise;
      expect(answerMsg.sdp).toBe('reconnect-answer');
      expect(answerMsg.from).toBe(memberId);

      wsHost.close();
      ws2.close();
      await Promise.all([waitForClose(wsHost), waitForClose(ws2)]).catch(() => {});
    });
  });

  describe('Rate limiting and message size (Issues #7, #8)', () => {
    it('client exceeding message rate limit gets disconnected with code 4029', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const ws1 = await connectWs(sessionId, hostToken2);

      // Wait for server to finish async setup (DB queries, event handler registration)
      await new Promise((r) => setTimeout(r, 200));

      const closePromise = waitForClose(ws1, 10000);

      // Send messages exceeding the rate limit (30 per 10s window)
      const msg = JSON.stringify({ type: 'offer', to: 'fake-user', sdp: 'test' });
      for (let i = 0; i < 35; i++) {
        if (ws1.readyState !== WebSocket.OPEN) break;
        ws1.send(msg);
      }

      const { code } = await closePromise;
      expect(code).toBe(4029);
    }, 15000);

    it('oversized WebSocket message closes connection with code 1009', async () => {
      const { accessToken: hostToken, userId: hostId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Host');
      const { groupId } = await createGroupWithGoal(hostToken, { includeGoal: false });
      const sessionId = await createFocusSession(groupId, hostId);

      const hostToken2 = generateAccessToken(hostId, 'host@example.com');
      const ws1 = await connectWs(sessionId, hostToken2);

      // Wait for server to finish async setup
      await new Promise((r) => setTimeout(r, 200));

      // Suppress the error the ws library emits on the client side
      ws1.on('error', () => {});

      // Send a message larger than 4KB maxPayload
      const largePayload = JSON.stringify({ type: 'offer', sdp: 'x'.repeat(5000) });
      ws1.send(largePayload);

      const { code } = await waitForClose(ws1);
      expect(code).toBe(1009);
    });
  });
});
