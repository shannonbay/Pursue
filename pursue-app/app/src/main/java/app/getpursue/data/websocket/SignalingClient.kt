package app.getpursue.data.websocket

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * SSE + POST signaling client for body-teaming focus sessions.
 *
 * Uses Server-Sent Events (SSE) for receiving signaling messages and HTTP POST
 * for sending them. This works through Cloud Run's proxy which strips WebSocket
 * upgrade headers.
 *
 * Connects to:
 *   GET  <baseUrl>/signal/:sessionId/stream?token=<jwt>   (SSE stream)
 *   POST <baseUrl>/signal/:sessionId/send                  (send messages)
 *
 * Server -> client messages (via SSE, flat JSON in `data:` lines):
 *   connected     { type, userId }
 *   peer-joined   { type, peerId, peerName, peerAvatar }
 *   peer-left     { type, peerId }
 *   phase-changed { type, phase, timer: { endsAt } }
 *   session-ended { type }
 *   offer         { type, from, sdp }
 *   answer        { type, from, sdp }
 *   ice-candidate { type, from, candidate }
 *
 * Client -> server messages (via POST, flat JSON body):
 *   offer         { type:"offer",         to, sdp }
 *   answer        { type:"answer",        to, sdp }
 *   ice-candidate { type:"ice-candidate", to, candidate }
 *   phase-change  { type:"phase-change",  phase }
 *   leave         { type:"leave" }
 *
 * Auto-reconnects up to 3 times with exponential back-off on unexpected closure.
 */
class SignalingClient(private val listener: SignalingListener) {

    interface SignalingListener {
        fun onConnected()
        fun onPeerJoined(userId: String, displayName: String)
        fun onPeerLeft(userId: String)
        /** newPhase is "focus" or "chit-chat". endsAt is ISO-8601. */
        fun onPhaseChanged(newPhase: String, endsAt: String?)
        fun onSessionEnded()
        fun onOffer(fromUserId: String, sdp: String)
        fun onAnswer(fromUserId: String, sdp: String)
        fun onIceCandidate(fromUserId: String, candidateJson: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SignalingClient"
        private const val MAX_RETRIES = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var sseJob: Job? = null
    private var retryCount = 0
    private var isIntentionallyClosed = false

    // Connection state
    private var currentBaseUrl: String? = null
    private var currentSessionId: String? = null
    private var tokenProvider: (() -> String)? = null

    // Force HTTP/1.1 — Cloud Run's proxy negotiates HTTP/2 via ALPN by default,
    // but HTTP/2 prohibits hop-by-hop headers (Connection: Upgrade) so the
    // WebSocket handshake fails with 502. Pinning HTTP/1.1 is also safer for SSE.
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no read timeout for persistent SSE
        .build()

    /**
     * Connect to the session signaling server via SSE.
     *
     * @param apiBaseUrl     The REST base URL (e.g. "https://api.getpursue.app/api").
     * @param sessionId      The focus session ID.
     * @param tokenProvider  Lambda returning the user's current JWT access token.
     *                       Called on each connection attempt so reconnects use a fresh token.
     */
    fun connect(apiBaseUrl: String, sessionId: String, tokenProvider: () -> String) {
        isIntentionallyClosed = false
        retryCount = 0
        currentBaseUrl = apiBaseUrl
        currentSessionId = sessionId
        this.tokenProvider = tokenProvider
        openSseStream()
    }

    private fun openSseStream() {
        val baseUrl = currentBaseUrl ?: return
        val sessionId = currentSessionId ?: return
        val token = tokenProvider?.invoke() ?: return

        val streamUrl = "$baseUrl/signal/$sessionId/stream?token=$token"
        val request = Request.Builder()
            .url(streamUrl)
            .header("Accept", "text/event-stream")
            .build()

        sseJob = scope.launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    Log.w(TAG, "SSE connection failed: $code")
                    listener.onError("Connection failed: $code")
                    if (!isIntentionallyClosed) scheduleReconnect()
                    return@launch
                }

                // Read SSE stream line by line
                val inputStream = response.body?.byteStream()
                if (inputStream == null) {
                    response.close()
                    if (!isIntentionallyClosed) scheduleReconnect()
                    return@launch
                }

                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    if (isIntentionallyClosed) break

                    val l = line ?: continue
                    if (l.startsWith("data: ")) {
                        val jsonStr = l.substring(6)
                        handleMessage(jsonStr)
                    }
                    // Skip empty lines and comment lines (start with ":")
                }

                reader.close()
                response.close()

                // Stream ended
                Log.d(TAG, "SSE stream ended")
                if (!isIntentionallyClosed) {
                    scheduleReconnect()
                }
            } catch (e: Exception) {
                if (!isIntentionallyClosed) {
                    Log.w(TAG, "SSE stream error: ${e.message}")
                    listener.onError(e.message ?: "Connection failed")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (retryCount >= MAX_RETRIES) {
            Log.w(TAG, "Max retries reached, giving up reconnect")
            return
        }
        val delayMs = (1000L * (1 shl retryCount)).coerceAtMost(8000L)
        retryCount++
        Log.d(TAG, "Reconnecting in ${delayMs}ms (attempt $retryCount/$MAX_RETRIES)")
        scope.launch {
            delay(delayMs)
            if (!isIntentionallyClosed) openSseStream()
        }
    }

    /**
     * Parse flat server messages and dispatch to listener.
     * Server sends flat JSON — no nested "data" object.
     */
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {
                "connected" -> {
                    Log.d(TAG, "SSE connected")
                    retryCount = 0
                    listener.onConnected()
                }
                "peer-joined" -> listener.onPeerJoined(
                    json.optString("peerId"),
                    json.optString("peerName", "Unknown")
                )
                "peer-left" -> listener.onPeerLeft(json.optString("peerId"))
                "phase-changed" -> {
                    val phase = json.optString("phase")
                    val endsAt = json.optJSONObject("timer")?.optString("endsAt")
                    listener.onPhaseChanged(phase, endsAt)
                }
                "session-ended" -> listener.onSessionEnded()
                "offer" -> listener.onOffer(
                    json.optString("from"),
                    json.optString("sdp")
                )
                "answer" -> listener.onAnswer(
                    json.optString("from"),
                    json.optString("sdp")
                )
                "ice-candidate" -> listener.onIceCandidate(
                    json.optString("from"),
                    json.opt("candidate")?.toString() ?: ""
                )
                else -> Log.d(TAG, "Unknown message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing signaling message", e)
        }
    }

    /**
     * Send a flat message to the signaling server via HTTP POST.
     * @param type   The message type (e.g. "offer", "ice-candidate", "leave").
     * @param fields Optional key-value pairs merged into the top-level JSON object.
     */
    fun send(type: String, fields: Map<String, Any?> = emptyMap()) {
        val baseUrl = currentBaseUrl ?: return
        val sessionId = currentSessionId ?: return
        val token = tokenProvider?.invoke() ?: return

        val json = JSONObject().apply {
            put("type", type)
            fields.forEach { (k, v) -> put(k, v) }
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/signal/$sessionId/send")
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        scope.launch {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to send message: $type (${response.code})")
                }
                response.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error sending message: $type", e)
            }
        }
    }

    /** Gracefully close the connection. */
    fun disconnect() {
        isIntentionallyClosed = true
        // Send leave message to clean up server-side state
        send("leave")
        // Cancel SSE stream
        sseJob?.cancel()
        sseJob = null
    }
}
