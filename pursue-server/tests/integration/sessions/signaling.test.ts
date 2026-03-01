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
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
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
});
