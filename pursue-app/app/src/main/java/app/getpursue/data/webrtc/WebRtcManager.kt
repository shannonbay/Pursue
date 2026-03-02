package app.getpursue.data.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Manages WebRTC peer connections for body-teaming focus sessions.
 *
 * Uses a full-mesh topology: one PeerConnection per remote participant.
 * Supports audio and video (low-res 480x360 @ 15fps for body doubling presence).
 */
class WebRtcManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcManager"
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
        private const val AUDIO_TRACK_ID = "pursue_audio_0"
        private const val VIDEO_TRACK_ID = "pursue_video_0"
        private const val LOCAL_STREAM_ID = "pursue_stream_0"

        private const val VIDEO_WIDTH = 480
        private const val VIDEO_HEIGHT = 360
        private const val VIDEO_FPS = 15

        @Volatile
        private var initialized = false

        fun init(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            initialized = true
        }
    }

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val localAudioSource: AudioSource
    private val localAudioTrack: AudioTrack
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    // ICE candidate buffer: holds candidates that arrive before remote description is set.
    // Flushed in handleAnswer()/handleOffer() once setRemoteDescription succeeds.
    private val pendingIceCandidates = mutableMapOf<String, MutableList<IceCandidate>>()

    // Video members
    private var cameraCapturer: CameraVideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isVideoEnabled = true

    /** Callback dispatched to the session fragment for signaling relay. */
    var onOfferCreated: ((peerId: String, sdp: String) -> Unit)? = null
    var onAnswerCreated: ((peerId: String, sdp: String) -> Unit)? = null
    var onIceCandidateReady: ((peerId: String, candidate: IceCandidate) -> Unit)? = null

    /** Callback when a remote peer's video track is received. */
    var onRemoteVideoTrack: ((peerId: String, track: VideoTrack) -> Unit)? = null
    var onRemoteVideoTrackRemoved: ((peerId: String) -> Unit)? = null

    /** Callback when a peer connection's ICE state transitions to FAILED. */
    var onPeerConnectionFailed: ((peerId: String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        init(context)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            // Pass null to avoid EGL texture-mode decoding — uses byte-buffer mode instead.
            // Sharing the same EglBase.Context between encoder and decoder causes SIGABRT
            // on the DecodingQueue thread on some devices.
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, localAudioSource)
        localAudioTrack.setEnabled(true)
    }

    /**
     * Initialize local video capture using the front-facing camera.
     * Call after construction and after camera permission is granted.
     */
    fun initLocalVideo(context: Context) {
        val enumerator = Camera2Enumerator(context)
        val frontCamera = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run {
                Log.w(TAG, "No camera found")
                return
            }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        localVideoSource = factory.createVideoSource(false)
        cameraCapturer = Camera2Capturer(context, frontCamera, null)
        cameraCapturer!!.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
        cameraCapturer!!.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource)
        localVideoTrack?.setEnabled(true)
    }

    fun getEglBaseContext(): EglBase.Context = eglBase.eglBaseContext

    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack

    fun setVideoEnabled(enabled: Boolean) {
        isVideoEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        cameraCapturer?.switchCamera(null)
    }

    private fun rtcConfig() = PeerConnection.RTCConfiguration(
        listOf(PeerConnection.IceServer.builder(STUN_SERVER).createIceServer())
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    /**
     * Create (or retrieve) a PeerConnection for a remote peer.
     * Adds local audio + video tracks and creates an offer.
     */
    fun createPeerConnection(peerId: String) {
        if (peerConnections.containsKey(peerId)) return
        Log.d(TAG, "createPeerConnection (active) for $peerId")

        val pc = factory.createPeerConnection(rtcConfig(), createPeerObserver(peerId)) ?: run {
            Log.e(TAG, "Failed to create PeerConnection for $peerId")
            return
        }

        pc.addTrack(localAudioTrack, listOf(LOCAL_STREAM_ID))
        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID)) }

        peerConnections[peerId] = pc

        // Create offer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Offer created for $peerId, setting local description")
                pc.setLocalDescription(SdpObserverAdapter(), sdp)
                onOfferCreated?.invoke(peerId, sdp.description)
            }
        }, constraints)
    }

    /**
     * Handle a remote SDP offer — set as remote description, then create and send answer.
     */
    fun handleOffer(peerId: String, sdp: String) {
        Log.d(TAG, "handleOffer from $peerId")
        val pc = getOrCreatePassivePeerConnection(peerId)
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote offer set for $peerId, flushing buffered candidates and creating answer")
                flushIceCandidates(peerId, pc)
                val constraints = MediaConstraints()
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        Log.d(TAG, "Answer created for $peerId, setting local description")
                        pc.setLocalDescription(SdpObserverAdapter(), answer)
                        onAnswerCreated?.invoke(peerId, answer.description)
                    }
                }, constraints)
            }
        }, offer)
    }

    /**
     * Handle a remote SDP answer.
     */
    fun handleAnswer(peerId: String, sdp: String) {
        Log.d(TAG, "handleAnswer from $peerId, pc exists=${peerConnections.containsKey(peerId)}")
        val pc = peerConnections[peerId] ?: return
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set for $peerId, flushing buffered candidates")
                flushIceCandidates(peerId, pc)
            }
        }, answer)
    }

    /**
     * Add a remote ICE candidate.
     */
    fun addIceCandidate(peerId: String, candidateSdp: String, sdpMid: String, sdpMLineIndex: Int) {
        val pc = peerConnections[peerId]
        if (pc == null) {
            Log.w(TAG, "addIceCandidate: no PC for $peerId, buffering candidate (PC not created yet)")
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
            pendingIceCandidates.getOrPut(peerId) { mutableListOf() }.add(candidate)
            return
        }
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)

        // Buffer if remote description not yet set (HAVE_LOCAL_OFFER means we sent offer, awaiting answer)
        val sigState = pc.signalingState()
        if (sigState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER ||
            sigState == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
            Log.d(TAG, "addIceCandidate for $peerId: buffering (signalingState=$sigState)")
            pendingIceCandidates.getOrPut(peerId) { mutableListOf() }.add(candidate)
            return
        }

        Log.d(TAG, "addIceCandidate for $peerId: mid=$sdpMid idx=$sdpMLineIndex sdp=${candidateSdp.take(80)}")
        val added = pc.addIceCandidate(candidate)
        Log.d(TAG, "addIceCandidate result=$added for $peerId")
    }

    /**
     * Mute or unmute the local audio track.
     */
    fun muteLocalAudio(muted: Boolean) {
        localAudioTrack.setEnabled(!muted)
    }

    /**
     * Close all peer connections but keep local audio/video tracks alive.
     * Used on SSE reconnect to rebuild WebRTC from a clean state.
     */
    fun closeAllPeerConnections() {
        Log.d(TAG, "closeAllPeerConnections: closing ${peerConnections.size} connections")
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        pendingIceCandidates.clear()
    }

    /**
     * Close a single peer connection and remove from tracking maps.
     */
    fun closePeerConnection(peerId: String) {
        Log.d(TAG, "closePeerConnection: $peerId")
        peerConnections.remove(peerId)?.close()
        pendingIceCandidates.remove(peerId)
    }

    /**
     * Release all peer connections, video capture, and the factory.
     */
    fun releaseAll() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        pendingIceCandidates.clear()

        try { cameraCapturer?.stopCapture() } catch (_: Exception) {}
        cameraCapturer?.dispose()
        cameraCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        localVideoSource?.dispose()
        localVideoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        localAudioTrack.dispose()
        localAudioSource.dispose()
        factory.dispose()
        eglBase.release()
    }

    private fun flushIceCandidates(peerId: String, pc: PeerConnection) {
        val buffered = pendingIceCandidates.remove(peerId) ?: return
        Log.d(TAG, "Flushing ${buffered.size} buffered ICE candidates for $peerId")
        for (candidate in buffered) {
            val added = pc.addIceCandidate(candidate)
            Log.d(TAG, "Flushed ICE candidate for $peerId: mid=${candidate.sdpMid} result=$added")
        }
    }

    private fun createPeerObserver(peerId: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Log.d(TAG, "[$peerId] signalingState=$newState")
        }
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "[$peerId] iceConnectionState=$newState")
            if (newState == PeerConnection.IceConnectionState.FAILED) {
                mainHandler.post { onPeerConnectionFailed?.invoke(peerId) }
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "[$peerId] iceGatheringState=$newState")
        }
        override fun onIceCandidate(candidate: IceCandidate) {
            onIceCandidateReady?.invoke(peerId, candidate)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {
            Log.d(TAG, "[$peerId] onAddStream videoTracks=${stream?.videoTracks?.size} audioTracks=${stream?.audioTracks?.size}")
            // Fallback: some WebRTC versions deliver tracks via onAddStream
            // instead of (or in addition to) onAddTrack when addStream was used.
            stream?.videoTracks?.firstOrNull()?.let { track ->
                mainHandler.post {
                    onRemoteVideoTrack?.invoke(peerId, track)
                }
            }
        }
        override fun onRemoveStream(stream: MediaStream?) {
            mainHandler.post {
                onRemoteVideoTrackRemoved?.invoke(peerId)
            }
        }
        override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track()
            Log.d(TAG, "[$peerId] onAddTrack kind=${track?.kind()} id=${track?.id()}")
            if (track is VideoTrack) {
                mainHandler.post {
                    onRemoteVideoTrack?.invoke(peerId, track)
                }
            }
        }
    }

    private fun getOrCreatePassivePeerConnection(peerId: String): PeerConnection {
        peerConnections[peerId]?.let { return it }
        Log.d(TAG, "getOrCreatePassivePeerConnection (new) for $peerId")

        val pc = factory.createPeerConnection(rtcConfig(), createPeerObserver(peerId))!!

        pc.addTrack(localAudioTrack, listOf(LOCAL_STREAM_ID + peerId))
        localVideoTrack?.let { pc.addTrack(it, listOf(LOCAL_STREAM_ID + peerId)) }
        peerConnections[peerId] = pc
        return pc
    }

    /** No-op SdpObserver base class to reduce boilerplate. */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e("WebRtcManager", "SDP createFailure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e("WebRtcManager", "SDP setFailure: $error")
        }
    }
}
