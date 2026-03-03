package app.getpursue.ui.fragments.sessions

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.BuildConfig
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.FocusParticipant
import app.getpursue.data.websocket.SignalingClient
import app.getpursue.data.webrtc.WebRtcManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import java.time.Instant

/**
 * Three-phase focus session screen:
 *  Lobby → Focus Block → Chit-Chat
 *
 * Integrates SignalingClient (WebSocket) and WebRtcManager (audio + video peer connections).
 */
class FocusSessionFragment : Fragment(), SignalingClient.SignalingListener {

    companion object {
        private const val TAG = "FocusSession"
        private const val ARG_SESSION_ID = "session_id"
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_GROUP_NAME = "group_name"
        private const val ARG_IS_HOST = "is_host"

        private const val PHASE_LOBBY = "lobby"
        private const val PHASE_FOCUS = "focus"
        private const val PHASE_CHIT_CHAT = "chit-chat"

        private const val CHIT_CHAT_DURATION_SECS = 10 * 60L

        fun newInstance(
            sessionId: String,
            groupId: String,
            groupName: String,
            isHost: Boolean
        ) = FocusSessionFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SESSION_ID, sessionId)
                putString(ARG_GROUP_ID, groupId)
                putString(ARG_GROUP_NAME, groupName)
                putBoolean(ARG_IS_HOST, isHost)
            }
        }
    }

    // --- Args ---
    private lateinit var sessionId: String
    private lateinit var groupId: String
    private lateinit var groupName: String
    private var isHost = false

    // --- State ---
    private var currentPhase = PHASE_LOBBY
    private var focusDurationSeconds = 45 * 60L
    private var focusEndsAt: Instant? = null
    private val participants = mutableListOf<FocusParticipant>()
    private val chatMessages = mutableListOf<String>()
    private var timerJob: Job? = null
    private var sessionActive = false

    // --- Signaling / WebRTC ---
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcManager: WebRtcManager
    private var accessToken: String? = null
    @Volatile private var myUserId: String = ""

    // --- Video state ---
    private var localRenderer: SurfaceViewRenderer? = null
    private val remoteRenderers = mutableMapOf<String, SurfaceViewRenderer>()
    private var isCameraOn = true
    private var isMicOn = false  // starts muted in lobby
    private var isSpeakerOn = false  // starts on earpiece
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    // --- Views: Video ---
    private lateinit var videoGrid: GridLayout
    private lateinit var btnToggleCamera: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnToggleMic: ImageButton
    private lateinit var btnToggleSpeaker: ImageButton

    // --- Views: Lobby ---
    private lateinit var lobbyControls: View
    private lateinit var lobbyGroupName: TextView
    private lateinit var lobbyPeopleCount: TextView
    private lateinit var lobbyDurationChips: ChipGroup
    private lateinit var btnStartFocus: MaterialButton

    // --- Views: Focus ---
    private lateinit var focusOverlay: View
    private lateinit var focusTimer: TextView
    private lateinit var btnEndEarly: MaterialButton

    // --- Views: Chit-Chat ---
    private lateinit var chitChatOverlay: View
    private lateinit var chitChatTimer: TextView
    private lateinit var chitChatMessages: RecyclerView
    private lateinit var chitChatMessageInput: TextInputEditText
    private lateinit var btnSendMessage: ImageButton

    // --- Chat adapter (simple string list) ---
    private val chatAdapter by lazy {
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = chatMessages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    setPadding(8, 4, 8, 4)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                (holder.itemView as TextView).text = chatMessages[position]
            }
        }
    }

    // --- Permission launcher ---
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        if (audioGranted) {
            setupAudioManager()
            if (cameraGranted) {
                webRtcManager.initLocalVideo(requireContext())
                setupLocalVideoPreview()
            }
            connectSignaling()
        } else {
            // Audio is mandatory; exit
            Toast.makeText(
                requireContext(),
                getString(R.string.focus_session_audio_permission_title),
                Toast.LENGTH_LONG
            ).show()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = arguments?.getString(ARG_SESSION_ID) ?: ""
        groupId = arguments?.getString(ARG_GROUP_ID) ?: ""
        groupName = arguments?.getString(ARG_GROUP_NAME) ?: ""
        isHost = arguments?.getBoolean(ARG_IS_HOST, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_focus_session, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupClickListeners()

        // Init WebRTC
        webRtcManager = WebRtcManager(requireContext())
        setupWebRtcCallbacks()

        // Init signaling client
        signalingClient = SignalingClient(this)

        // Request mic + camera permissions then connect
        requestPermissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        )
    }

    private fun bindViews(view: View) {
        // Video grid + controls
        videoGrid = view.findViewById(R.id.video_grid)
        btnToggleCamera = view.findViewById(R.id.btn_toggle_camera)
        btnSwitchCamera = view.findViewById(R.id.btn_switch_camera)
        btnToggleMic = view.findViewById(R.id.btn_toggle_mic)
        btnToggleSpeaker = view.findViewById(R.id.btn_toggle_speaker)

        // Lobby
        lobbyControls = view.findViewById(R.id.lobby_controls)
        lobbyGroupName = view.findViewById(R.id.lobby_group_name)
        lobbyPeopleCount = view.findViewById(R.id.lobby_people_count)
        lobbyDurationChips = view.findViewById(R.id.lobby_duration_chips)
        btnStartFocus = view.findViewById(R.id.btn_start_focus)

        // Focus
        focusOverlay = view.findViewById(R.id.focus_overlay)
        focusTimer = view.findViewById(R.id.focus_timer)
        btnEndEarly = view.findViewById(R.id.btn_end_early)

        // Chit-chat
        chitChatOverlay = view.findViewById(R.id.chit_chat_overlay)
        chitChatTimer = view.findViewById(R.id.chit_chat_timer)
        chitChatMessages = view.findViewById(R.id.chit_chat_messages)
        chitChatMessageInput = view.findViewById(R.id.chit_chat_message_input)
        btnSendMessage = view.findViewById(R.id.btn_send_message)

        lobbyGroupName.text = groupName
        btnStartFocus.visibility = if (isHost) View.VISIBLE else View.GONE
        btnEndEarly.visibility = if (isHost) View.VISIBLE else View.GONE

        chitChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        chitChatMessages.adapter = chatAdapter

        // Mic starts muted
        updateMicIcon()
    }

    private fun setupClickListeners() {
        btnStartFocus.setOnClickListener { hostStartFocus() }
        btnEndEarly.setOnClickListener { hostEndSession() }
        btnSendMessage.setOnClickListener { sendChatMessage() }
        chitChatMessageInput.setOnEditorActionListener { _, _, _ ->
            sendChatMessage()
            true
        }

        lobbyDurationChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            focusDurationSeconds = when (checkedIds.first()) {
                R.id.chip_25 -> 25 * 60L
                R.id.chip_45 -> 45 * 60L
                R.id.chip_60 -> 60 * 60L
                R.id.chip_90 -> 90 * 60L
                else -> 45 * 60L
            }
        }

        btnToggleCamera.setOnClickListener {
            isCameraOn = !isCameraOn
            webRtcManager.setVideoEnabled(isCameraOn)
            btnToggleCamera.setImageResource(
                if (isCameraOn) R.drawable.ic_videocam else R.drawable.ic_videocam_off
            )
        }

        btnSwitchCamera.setOnClickListener {
            webRtcManager.switchCamera()
        }

        btnToggleMic.setOnClickListener {
            if (currentPhase == PHASE_FOCUS) return@setOnClickListener  // mic forced muted during focus
            isMicOn = !isMicOn
            webRtcManager.muteLocalAudio(!isMicOn)
            updateMicIcon()
        }

        btnToggleSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            audioManager?.isSpeakerphoneOn = isSpeakerOn
            updateSpeakerIcon()
        }
    }

    private fun updateMicIcon() {
        btnToggleMic.setImageResource(
            if (isMicOn) R.drawable.ic_mic else R.drawable.ic_mic_off
        )
    }

    private fun setupAudioManager() {
        val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = isSpeakerOn
        updateSpeakerIcon()
    }

    private fun updateSpeakerIcon() {
        btnToggleSpeaker.setImageResource(
            if (isSpeakerOn) R.drawable.ic_volume_up else R.drawable.ic_hearing
        )
    }

    // --- Local video preview ---

    private fun setupLocalVideoPreview() {
        val renderer = SurfaceViewRenderer(requireContext()).apply {
            init(webRtcManager.getEglBaseContext(), null)
            setMirror(true)  // front camera mirror
            setEnableHardwareScaler(true)
        }
        localRenderer = renderer
        webRtcManager.getLocalVideoTrack()?.addSink(renderer)
        addVideoTileToGrid(renderer, getString(R.string.focus_session_you))
    }

    private fun addVideoTileToGrid(renderer: SurfaceViewRenderer, label: String): FrameLayout {
        val tile = FrameLayout(requireContext()).apply {
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                setMargins(4, 4, 4, 4)
            }
        }
        tile.addView(renderer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Name label overlay
        val nameLabel = TextView(requireContext()).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            setPadding(8, 4, 8, 4)
            textSize = 12f
        }
        tile.addView(nameLabel, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        videoGrid.addView(tile)
        updateGridColumns()
        return tile
    }

    private fun updateGridColumns() {
        val count = videoGrid.childCount
        videoGrid.columnCount = if (count <= 1) 1 else 2
    }

    private fun removeVideoTile(peerId: String) {
        val renderer = remoteRenderers.remove(peerId) ?: return
        for (i in 0 until videoGrid.childCount) {
            val tile = videoGrid.getChildAt(i) as? FrameLayout ?: continue
            if (tile.getChildAt(0) == renderer) {
                videoGrid.removeViewAt(i)
                break
            }
        }
        renderer.release()
        updateGridColumns()
    }

    private fun connectSignaling() {
        Log.d(TAG, "connectSignaling() called")
        lifecycleScope.launch {
            val ctx = context ?: run {
                Log.w(TAG, "connectSignaling: context is null, aborting")
                return@launch
            }
            val token = withContext(Dispatchers.IO) {
                SecureTokenManager.getInstance(ctx).getAccessToken()
            }
            if (token == null) {
                Log.w(TAG, "connectSignaling: no access token, aborting")
                return@launch
            }
            accessToken = token

            // Join session via REST
            Log.d(TAG, "connectSignaling: joining session=$sessionId group=$groupId")
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.joinSession(token, groupId, sessionId)
                }
                Log.d(TAG, "connectSignaling: joinSession succeeded")
            } catch (e: ApiException) {
                Log.d(TAG, "connectSignaling: joinSession error (may be already joined): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "connectSignaling: joinSession unexpected error: ${e.message}")
            }

            // Open SSE signaling stream — SSE goes direct to Cloud Run (bypasses Cloudflare buffering)
            val sseBaseUrl = BuildConfig.SIGNAL_BASE_URL
            val apiBaseUrl = ApiClient.getBaseUrl()
            Log.d(TAG, "connectSignaling: opening SSE to $sseBaseUrl/signal/$sessionId/stream")
            signalingClient.connect(sseBaseUrl, apiBaseUrl, sessionId) {
                // Token provider: returns fresh token on each call (handles token refresh)
                SecureTokenManager.getInstance(ctx).getAccessToken() ?: ""
            }
            sessionActive = true
            Log.d(TAG, "connectSignaling: SSE connect initiated")
        }
    }

    // --- SignalingListener ---

    override fun onConnected(userId: String, isReconnect: Boolean) {
        Log.d(TAG, "onConnected myUserId=$userId isReconnect=$isReconnect")
        myUserId = userId
        requireActivity().runOnUiThread {
            if (isReconnect) {
                Log.d(TAG, "Reconnected — tearing down stale WebRTC state")
                // Close all stale peer connections (server will re-send peer-joined events)
                webRtcManager.closeAllPeerConnections()
                // Remove all remote video tiles
                for ((peerId, renderer) in remoteRenderers) {
                    for (i in 0 until videoGrid.childCount) {
                        val tile = videoGrid.getChildAt(i) as? FrameLayout ?: continue
                        if (tile.getChildAt(0) == renderer) {
                            videoGrid.removeViewAt(i)
                            break
                        }
                    }
                    renderer.release()
                }
                remoteRenderers.clear()
                participants.clear()
                updateGridColumns()
            }
            // Always update count (shows "1 people here" on initial connect)
            updateParticipantCountUI()
        }
    }

    override fun onPeerJoined(userId: String, displayName: String) {
        val localId = myUserId  // capture volatile read
        Log.d(TAG, "onPeerJoined peerId=$userId myUserId=$localId willOffer=${localId.isNotEmpty() && localId < userId}")

        // Ignore self-notifications (server may echo our own userId on reconnect)
        if (userId == localId) {
            Log.d(TAG, "onPeerJoined: ignoring self-notification")
            return
        }

        requireActivity().runOnUiThread {
            // Add synthetic participant entry to local list for UI
            val existing = participants.any { it.user_id == userId }
            if (!existing) {
                participants.add(FocusParticipant(
                    id = userId,
                    user_id = userId,
                    display_name = displayName,
                    joined_at = java.time.Instant.now().toString(),
                    left_at = null
                ))
                updateParticipantCountUI()
            }
            // Glare prevention: only the peer with the lexicographically smaller
            // userId sends the offer. The other peer waits to receive an offer.
            if (localId.isNotEmpty() && localId < userId) {
                webRtcManager.createPeerConnection(userId)
            }
        }
    }

    override fun onPeerLeft(userId: String) {
        requireActivity().runOnUiThread {
            participants.removeAll { it.user_id == userId }
            removeVideoTile(userId)
            updateParticipantCountUI()
        }
    }

    override fun onPhaseChanged(newPhase: String, endsAt: String?) {
        requireActivity().runOnUiThread {
            when (newPhase) {
                PHASE_FOCUS -> {
                    currentPhase = PHASE_FOCUS
                    focusEndsAt = if (endsAt != null) {
                        try { Instant.parse(endsAt) } catch (_: Exception) { Instant.now().plusSeconds(focusDurationSeconds) }
                    } else {
                        Instant.now().plusSeconds(focusDurationSeconds)
                    }
                    renderPhase(PHASE_FOCUS)
                    // Enforce mic mute during focus
                    isMicOn = false
                    webRtcManager.muteLocalAudio(true)
                    updateMicIcon()
                    btnToggleMic.alpha = 0.5f  // dimmed to indicate disabled
                    startFocusCountdown()
                }
                PHASE_CHIT_CHAT -> {
                    currentPhase = PHASE_CHIT_CHAT
                    renderPhase(PHASE_CHIT_CHAT)
                    // Unmute for chit-chat
                    isMicOn = true
                    webRtcManager.muteLocalAudio(false)
                    updateMicIcon()
                    btnToggleMic.alpha = 1.0f
                    startChitChatCountdown(endsAt)
                }
                else -> {}
            }
        }
    }

    override fun onSessionEnded() {
        Log.d(TAG, "onSessionEnded received from server")
        requireActivity().runOnUiThread { finishSession() }
    }

    override fun onOffer(fromUserId: String, sdp: String) {
        Log.d(TAG, "onOffer from=$fromUserId sdpLen=${sdp.length}")
        webRtcManager.handleOffer(fromUserId, sdp)
    }

    override fun onAnswer(fromUserId: String, sdp: String) {
        Log.d(TAG, "onAnswer from=$fromUserId sdpLen=${sdp.length}")
        webRtcManager.handleAnswer(fromUserId, sdp)
    }

    override fun onIceCandidate(fromUserId: String, candidateJson: String) {
        Log.d(TAG, "onIceCandidate from=$fromUserId json=${candidateJson.take(120)}")
        try {
            val obj = JSONObject(candidateJson)
            val sdp = obj.optString("candidate")
            val mid = obj.optString("sdpMid", "")
            val idx = obj.optInt("sdpMLineIndex", 0)
            Log.d(TAG, "onIceCandidate parsed: sdp=${sdp.take(80)} mid=$mid idx=$idx")
            webRtcManager.addIceCandidate(fromUserId, sdp, mid, idx)
        } catch (e: Exception) {
            Log.e(TAG, "onIceCandidate parse error: ${e.message}, raw=$candidateJson")
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError: $message")
        requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Phase transitions ---

    private fun renderPhase(phase: String) {
        lobbyControls.visibility = if (phase == PHASE_LOBBY) View.VISIBLE else View.GONE
        focusOverlay.visibility = if (phase == PHASE_FOCUS) View.VISIBLE else View.GONE
        chitChatOverlay.visibility = if (phase == PHASE_CHIT_CHAT) View.VISIBLE else View.GONE
        // Video grid + camera controls stay visible in all phases
    }

    private fun startFocusCountdown() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            val deadline = focusEndsAt ?: Instant.now().plusSeconds(focusDurationSeconds)
            while (isActive) {
                val remaining = (deadline.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)
                val mm = remaining / 60
                val ss = remaining % 60
                focusTimer.text = String.format("%02d:%02d", mm, ss)
                if (remaining == 0L) {
                    // Focus done; server will push phase-changed → chit-chat
                    break
                }
                delay(1000)
            }
        }
    }

    private fun startChitChatCountdown(endsAt: String? = null) {
        timerJob?.cancel()
        val deadline = endsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        var remaining = if (deadline != null)
            (deadline.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)
        else
            CHIT_CHAT_DURATION_SECS
        timerJob = lifecycleScope.launch {
            while (isActive && remaining >= 0) {
                val mm = remaining / 60
                val ss = remaining % 60
                chitChatTimer.text = String.format("%02d:%02d", mm, ss)
                delay(1000)
                remaining--
            }
            if (isActive) finishSession()
        }
    }

    // --- Host actions ---

    private fun hostStartFocus() {
        lifecycleScope.launch {
            val token = accessToken ?: return@launch
            try {
                withContext(Dispatchers.IO) { ApiClient.startSession(token, groupId, sessionId) }
                // Broadcast phase change to remote peers via signaling
                signalingClient.send("phase-change", mapOf("phase" to "focus"))
                // Transition host UI immediately (don't wait for SSE round-trip)
                currentPhase = PHASE_FOCUS
                focusEndsAt = Instant.now().plusSeconds(focusDurationSeconds)
                renderPhase(PHASE_FOCUS)
                // Enforce mic mute during focus
                isMicOn = false
                webRtcManager.muteLocalAudio(true)
                updateMicIcon()
                btnToggleMic.alpha = 0.5f
                startFocusCountdown()
            } catch (e: ApiException) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hostEndSession() {
        lifecycleScope.launch {
            val token = accessToken ?: return@launch
            try {
                withContext(Dispatchers.IO) { ApiClient.endSession(token, groupId, sessionId) }
                // Notify all peers before leaving
                signalingClient.send("phase-change", mapOf("phase" to "end"))
                finishSession()
            } catch (e: ApiException) {
                Toast.makeText(requireContext(), e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Leave session (called from Activity back-press dialog) ---

    fun leaveSession() {
        lifecycleScope.launch {
            val token = accessToken ?: return@launch
            try {
                withContext(Dispatchers.IO) { ApiClient.leaveSession(token, groupId, sessionId) }
            } catch (_: ApiException) { /* ignore */ }
            finishSession()
        }
    }

    fun isSessionActive() = sessionActive && currentPhase != "ended"

    private fun finishSession() {
        Log.d(TAG, "finishSession() called", Exception("stack trace"))
        sessionActive = false
        timerJob?.cancel()
        requireActivity().finish()
    }

    private fun sendChatMessage() {
        val text = chitChatMessageInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        chatMessages.add(text)
        chatAdapter.notifyItemInserted(chatMessages.size - 1)
        chitChatMessages.scrollToPosition(chatMessages.size - 1)
        chitChatMessageInput.text?.clear()
    }

    private fun updateParticipantCountUI() {
        val count = participants.size + 1 // +1 for self
        lobbyPeopleCount.text = getString(R.string.focus_session_people_here, count)
    }

    // --- WebRTC signaling relay setup ---

    private fun setupWebRtcCallbacks() {
        webRtcManager.onOfferCreated = { peerId, sdp ->
            signalingClient.send("offer", mapOf("to" to peerId, "sdp" to sdp))
        }
        webRtcManager.onAnswerCreated = { peerId, sdp ->
            signalingClient.send("answer", mapOf("to" to peerId, "sdp" to sdp))
        }
        webRtcManager.onIceCandidateReady = { peerId, candidate ->
            val candidateObj = JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            signalingClient.send(
                "ice-candidate",
                mapOf("to" to peerId, "candidate" to candidateObj)
            )
        }

        webRtcManager.onRemoteVideoTrack = { peerId, track ->
            requireActivity().runOnUiThread {
                try {
                    // Guard: both onAddStream and onAddTrack may fire; skip if already added
                    if (remoteRenderers.containsKey(peerId)) return@runOnUiThread
                    if (!isAdded) return@runOnUiThread
                    Log.d(TAG, "Adding remote video tile for $peerId")
                    val renderer = SurfaceViewRenderer(requireContext()).apply {
                        init(webRtcManager.getEglBaseContext(), null)
                        setEnableHardwareScaler(true)
                    }
                    remoteRenderers[peerId] = renderer
                    val name = participants.find { it.user_id == peerId }?.display_name ?: "Peer"
                    addVideoTileToGrid(renderer, name)
                    // Add sink AFTER renderer is in the view hierarchy and laid out
                    renderer.post { track.addSink(renderer) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding remote video tile for $peerId: ${e.message}", e)
                }
            }
        }

        webRtcManager.onRemoteVideoTrackRemoved = { peerId ->
            requireActivity().runOnUiThread {
                removeVideoTile(peerId)
            }
        }

        webRtcManager.onPeerConnectionFailed = { peerId ->
            Log.w(TAG, "ICE connection failed for $peerId, attempting recovery")
            removeVideoTile(peerId)
            webRtcManager.closePeerConnection(peerId)
            // Re-create the connection using the same glare-prevention rule as onPeerJoined
            val localId = myUserId
            if (localId.isNotEmpty() && localId < peerId) {
                webRtcManager.createPeerConnection(peerId)
            }
        }
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView() called")
        super.onDestroyView()
        timerJob?.cancel()
        signalingClient.disconnect()

        // Restore audio mode
        audioManager?.let {
            it.isSpeakerphoneOn = false
            it.mode = previousAudioMode
        }

        // Release renderers before WebRTC teardown
        localRenderer?.let { renderer ->
            webRtcManager.getLocalVideoTrack()?.removeSink(renderer)
            renderer.release()
        }
        localRenderer = null
        remoteRenderers.values.forEach { it.release() }
        remoteRenderers.clear()

        webRtcManager.releaseAll()
    }
}
