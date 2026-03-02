package com.github.shannonbay.pursue.e2e.sessions

import app.getpursue.data.websocket.SignalingClient
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.config.LocalServerConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Test
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * E2E tests for the WebSocket signaling server.
 *
 * Exercises [SignalingClient] against a live backend to verify:
 *   - Connection authentication (valid/invalid JWT)
 *   - peer-joined / peer-left message format (flat JSON, hyphenated type names)
 *   - phase-changed message format (phase + timer.endsAt as ISO-8601)
 *   - WebRTC signaling relay (offer → target peer, correct `from` field)
 *
 * These tests would have caught the original protocol mismatch between
 * SignalingClient and the server (underscore vs hyphen type names, nested vs
 * flat JSON, startedAt vs endsAt).
 *
 * All tests use fresh users + fresh groups for session state isolation.
 */
class SignalingE2ETest : E2ETest() {

    // -------------------------------------------------------------------------
    // Event container — thread-safe queues for async WebSocket callbacks.
    // OkHttp delivers callbacks on its own threads, so we use blocking queues
    // and await them from the IO dispatcher inside runTest.
    // -------------------------------------------------------------------------

    private data class SignalingEvents(
        val connected: CountDownLatch = CountDownLatch(1),
        val peerJoined: ArrayBlockingQueue<Pair<String, String>> = ArrayBlockingQueue(10), // userId, displayName
        val peerLeft: ArrayBlockingQueue<String> = ArrayBlockingQueue(10),                 // userId
        val phaseChanged: ArrayBlockingQueue<Pair<String, String?>> = ArrayBlockingQueue(5), // phase, endsAt
        val sessionEnded: CountDownLatch = CountDownLatch(1),
        val offer: ArrayBlockingQueue<Pair<String, String>> = ArrayBlockingQueue(5),       // fromId, sdp
        val answer: ArrayBlockingQueue<Pair<String, String>> = ArrayBlockingQueue(5),      // fromId, sdp
        val errors: ArrayBlockingQueue<String> = ArrayBlockingQueue(10)
    )

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun connectSignaling(sessionId: String, token: String): Pair<SignalingClient, SignalingEvents> {
        val events = SignalingEvents()
        val client = SignalingClient(object : SignalingClient.SignalingListener {
            override fun onConnected() { events.connected.countDown() }
            override fun onPeerJoined(userId: String, displayName: String) {
                events.peerJoined.offer(userId to displayName)
            }
            override fun onPeerLeft(userId: String) { events.peerLeft.offer(userId) }
            override fun onPhaseChanged(newPhase: String, endsAt: String?) {
                events.phaseChanged.offer(newPhase to endsAt)
            }
            override fun onSessionEnded() { events.sessionEnded.countDown() }
            override fun onOffer(fromUserId: String, sdp: String) { events.offer.offer(fromUserId to sdp) }
            override fun onAnswer(fromUserId: String, sdp: String) { events.answer.offer(fromUserId to sdp) }
            override fun onIceCandidate(fromUserId: String, candidateJson: String) { /* not tested here */ }
            override fun onError(message: String) { events.errors.offer(message) }
        })
        client.connect(LocalServerConfig.API_BASE_URL, sessionId) { token }
        return client to events
    }

    /**
     * Block on the IO dispatcher (real time) until the latch fires.
     * Returns false if the timeout elapses first.
     */
    private suspend fun awaitLatch(latch: CountDownLatch, timeoutSecs: Long = 5): Boolean =
        withContext(Dispatchers.IO) { latch.await(timeoutSecs, TimeUnit.SECONDS) }

    /**
     * Block on the IO dispatcher (real time) until an item arrives in the queue.
     * Throws if the timeout elapses — gives a clear failure message.
     */
    private suspend fun <T> awaitEvent(
        queue: ArrayBlockingQueue<T>,
        label: String = "event",
        timeoutSecs: Long = 5
    ): T = withContext(Dispatchers.IO) {
        queue.poll(timeoutSecs, TimeUnit.SECONDS)
            ?: error("Timeout (${timeoutSecs}s) waiting for $label")
    }

    /** Invite a new user into a group and approve their membership. Returns (accessToken, userId). */
    private suspend fun addMemberToGroup(creatorToken: String, groupId: String): Pair<String, String> {
        val invite = api.getGroupInviteCode(creatorToken, groupId)
        val member = testDataHelper.createTestUser(api, displayName = "Signaling Member")
        trackUser(member.user!!.id)
        api.joinGroup(member.access_token, invite.invite_code)
        api.approveMember(creatorToken, groupId, member.user!!.id)
        return member.access_token to member.user!!.id
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `signaling connects with valid token and fires onConnected`() = runTest {
        val host = testDataHelper.createTestUser(api, displayName = "Sig Connect Host")
        trackUser(host.user!!.id)
        val group = testDataHelper.createTestGroup(api, host.access_token)
        trackGroup(group.id)
        val sessionId = api.createFocusSession(host.access_token, group.id, 25).session.id

        val (client, events) = connectSignaling(sessionId, host.access_token)
        try {
            assertThat(awaitLatch(events.connected)).isTrue()
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `signaling rejects invalid token with WebSocket close code 4001`() = runTest {
        // The HTTP 101 upgrade always succeeds before the server runs auth logic,
        // so onConnected fires even for invalid tokens. The server then closes the
        // WebSocket with code 4001. We use raw OkHttp here to capture that close
        // code directly — it is the precise thing SignalingClient cannot expose.
        val host = testDataHelper.createTestUser(api, displayName = "Sig Auth Host")
        trackUser(host.user!!.id)
        val group = testDataHelper.createTestGroup(api, host.access_token)
        trackGroup(group.id)
        val sessionId = api.createFocusSession(host.access_token, group.id, 25).session.id

        val closedCode = arrayOf(-1)
        val closeLatch = CountDownLatch(1)

        val rawClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val ws = rawClient.newWebSocket(
            Request.Builder()
                .url("ws://localhost:3000/ws/sessions/$sessionId?token=invalid-token")
                .build(),
            object : WebSocketListener() {
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    closedCode[0] = code
                    ws.close(1000, null)
                    closeLatch.countDown()
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    closeLatch.countDown()
                }
            }
        )

        try {
            val closed = withContext(Dispatchers.IO) { closeLatch.await(5, TimeUnit.SECONDS) }
            assertThat(closed).isTrue()
            assertThat(closedCode[0]).isEqualTo(4001)
        } finally {
            ws.close(1000, null)
            rawClient.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `two clients connect — each receives peer-joined with correct peerId`() = runTest {
        val host = testDataHelper.createTestUser(api, displayName = "Sig Peer Host")
        trackUser(host.user!!.id)
        val group = testDataHelper.createTestGroup(api, host.access_token)
        trackGroup(group.id)
        val sessionId = api.createFocusSession(host.access_token, group.id, 25).session.id

        val (memberToken, memberId) = addMemberToGroup(host.access_token, group.id)

        val (hostClient, hostEvents) = connectSignaling(sessionId, host.access_token)
        val (memberClient, memberEvents) = connectSignaling(sessionId, memberToken)
        try {
            assertThat(awaitLatch(hostEvents.connected)).isTrue()
            assertThat(awaitLatch(memberEvents.connected)).isTrue()

            // Host must receive peer-joined for the member
            val (joinedId, _) = awaitEvent(hostEvents.peerJoined, "host:peer-joined for member")
            assertThat(joinedId).isEqualTo(memberId)

            // Member must receive peer-joined for the host (sent when member joined the room)
            val (hostJoinedId, _) = awaitEvent(memberEvents.peerJoined, "member:peer-joined for host")
            assertThat(hostJoinedId).isEqualTo(host.user!!.id)
        } finally {
            hostClient.disconnect()
            memberClient.disconnect()
        }
    }

    @Test
    fun `peer-left fires with correct userId when a client disconnects`() = runTest {
        val host = testDataHelper.createTestUser(api, displayName = "Sig Leave Host")
        trackUser(host.user!!.id)
        val group = testDataHelper.createTestGroup(api, host.access_token)
        trackGroup(group.id)
        val sessionId = api.createFocusSession(host.access_token, group.id, 25).session.id

        val (memberToken, memberId) = addMemberToGroup(host.access_token, group.id)

        val (hostClient, hostEvents) = connectSignaling(sessionId, host.access_token)
        val (memberClient, _) = connectSignaling(sessionId, memberToken)
        try {
            assertThat(awaitLatch(hostEvents.connected)).isTrue()
            // Drain peer-joined so queue is clean before we disconnect
            awaitEvent(hostEvents.peerJoined, "peer-joined drain")

            // Member disconnects — host should receive peer-left
            memberClient.disconnect()

            val leftId = awaitEvent(hostEvents.peerLeft, "peer-left")
            assertThat(leftId).isEqualTo(memberId)
        } finally {
            hostClient.disconnect()
        }
    }

    @Test
    fun `host sends phase-change — all clients receive phase-changed with future endsAt`() = runTest {
        val host = testDataHelper.createTestUser(api, displayName = "Sig Phase Host")
        trackUser(host.user!!.id)
        val group = testDataHelper.createTestGroup(api, host.access_token)
        trackGroup(group.id)
        val sessionId = api.createFocusSession(host.access_token, group.id, 25).session.id

        val (memberToken, _) = addMemberToGroup(host.access_token, group.id)

        val (hostClient, hostEvents) = connectSignaling(sessionId, host.access_token)
        val (memberClient, memberEvents) = connectSignaling(sessionId, memberToken)
        try {
            assertThat(awaitLatch(hostEvents.connected)).isTrue()
            assertThat(awaitLatch(memberEvents.connected)).isTrue()
            awaitEvent(hostEvents.peerJoined, "peer-joined drain") // drain before phase-change

            // Host triggers focus phase via WebSocket (no REST /start needed)
            hostClient.send("phase-change", mapOf("phase" to "focus"))

            // Both host and member must receive phase-changed
            val (hostPhase, hostEndsAt) = awaitEvent(hostEvents.phaseChanged, "host:phase-changed")
            val (memberPhase, memberEndsAt) = awaitEvent(memberEvents.phaseChanged, "member:phase-changed")

            assertThat(hostPhase).isEqualTo("focus")
            assertThat(memberPhase).isEqualTo("focus")

            // endsAt must be a valid ISO-8601 instant in the future (server sets it to now + 25 min)
            assertThat(hostEndsAt).isNotNull()
            assertThat(memberEndsAt).isNotNull()
            assertThat(Instant.parse(hostEndsAt).isAfter(Instant.now())).isTrue()
            assertThat(Instant.parse(memberEndsAt).isAfter(Instant.now())).isTrue()
        } finally {
            hostClient.disconnect()
            memberClient.disconnect()
        }
    }

    @Test
    fun `offer is relayed to the target peer with correct from and sdp fields`() = runTest {
        val host = testDataHelper.createTestUser(api, displayName = "Sig Offer Host")
        trackUser(host.user!!.id)
        val group = testDataHelper.createTestGroup(api, host.access_token)
        trackGroup(group.id)
        val sessionId = api.createFocusSession(host.access_token, group.id, 25).session.id

        val (memberToken, memberId) = addMemberToGroup(host.access_token, group.id)

        val (hostClient, hostEvents) = connectSignaling(sessionId, host.access_token)
        val (memberClient, memberEvents) = connectSignaling(sessionId, memberToken)
        try {
            assertThat(awaitLatch(hostEvents.connected)).isTrue()
            assertThat(awaitLatch(memberEvents.connected)).isTrue()
            awaitEvent(hostEvents.peerJoined, "peer-joined drain") // drain before sending offer

            // Host sends offer to member
            hostClient.send("offer", mapOf("to" to memberId, "sdp" to "test-sdp-payload"))

            // Member receives it — `from` must be host's userId, `sdp` must be intact
            val (fromId, sdp) = awaitEvent(memberEvents.offer, "member:offer")
            assertThat(fromId).isEqualTo(host.user!!.id)
            assertThat(sdp).isEqualTo("test-sdp-payload")
        } finally {
            hostClient.disconnect()
            memberClient.disconnect()
        }
    }
}
