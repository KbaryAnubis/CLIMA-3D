package com.example.ui.components

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import android.os.DeadObjectException
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.diagnostics.GraphicsSurfaceCoordinator
import java.util.concurrent.atomic.AtomicBoolean

internal data class ExoSurfaceSnapshot(
    val surfaceViewClass: String? = null,
    val textureAvailable: Boolean? = null,
    val hasSurfaceTexture: Boolean? = null,
) {
    fun formatted(): String =
        "surfaceView=${surfaceViewClass ?: "none"}, textureAvailable=${textureAvailable ?: "unknown"}, hasSurfaceTexture=${hasSurfaceTexture ?: "unknown"}"
}

internal data class WeatherVideoCodecErrorDiagnostic(
    val errorCodeName: String,
    val errorClass: String,
    val isDeadObjectOrReclaimed: Boolean,
    val positionMsAtError: Long,
    val playbackSpeedAtError: Float,
    val message: String?,
)

internal interface WeatherVideoPlayerBridge {
    val sessionId: String
    val identityHash: Int
    val isReleased: Boolean
    val isIdle: Boolean
    val currentPositionMs: Long
        get() = 0L
    val currentPlaybackSpeed: Float
        get() = 1.0f

    fun setVideoResIdHint(@RawRes resId: Int) {}
    fun setMediaItem(uri: Uri)
    fun setVolume(volume: Float)
    fun setRepeatMode(repeatMode: Int)
    fun setPlayWhenReady(playWhenReady: Boolean)
    fun setPlaybackSpeed(speed: Float)
    fun seekTo(positionMs: Long) {}
    fun prepare()
    fun stop()
    fun play()
    fun pause()
    fun clearVideoSurface()
    fun setFirstFrameListener(listener: (() -> Unit)?)
    fun setCodecErrorListener(listener: ((WeatherVideoCodecErrorDiagnostic) -> Unit)?) {}
    fun attachToPlayerView(playerView: PlayerView)
    fun isAttachedToPlayerView(playerView: PlayerView): Boolean
    fun releaseOnce(): Boolean
}

internal interface WeatherVideoSurfaceBridge {
    val view: View
    val viewIdentity: Int
    val viewClassName: String
        get() = view::class.java.simpleName.ifEmpty { "View" }

    fun attachPlayer(player: WeatherVideoPlayerBridge)
    fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean
    fun detachPlayer()

    fun inspectSurfaceSnapshot(): ExoSurfaceSnapshot = ExoSurfaceSnapshot()

    fun attachWindowObserver(
        onAttachStateChanged: (attached: Boolean, snapshot: ExoSurfaceSnapshot) -> Unit,
    ): () -> Unit = {}
}

internal fun isMediaCodecDeadObjectOrReclaimed(throwable: Throwable?): Boolean {
    var cursor: Throwable? = throwable
    var depth = 0
    while (cursor != null && depth < 12) {
        if (cursor is DeadObjectException) return true
        if (cursor is MediaCodec.CodecException) {
            val diag = cursor.diagnosticInfo.orEmpty()
            val msg = cursor.message.orEmpty()
            if (diag.contains("DEAD_OBJECT", ignoreCase = true) ||
                msg.contains("DEAD_OBJECT", ignoreCase = true) ||
                msg.contains("Released by resource manager", ignoreCase = true) ||
                cursor.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED
            ) {
                return true
            }
        }
        val msg = cursor.message.orEmpty()
        if (msg.contains("DEAD_OBJECT", ignoreCase = true) ||
            msg.contains("Released by resource manager", ignoreCase = true) ||
            msg.contains("ERROR_RECLAIMED", ignoreCase = true)
        ) {
            return true
        }
        cursor = cursor.cause
        depth++
    }
    return false
}

private class RealExoPlayerBridge(
    private val exoPlayer: ExoPlayer,
    override val sessionId: String,
    initialVideoResId: Int,
) : WeatherVideoPlayerBridge {
    private val releasedFlag = AtomicBoolean(false)
    private var firstFrameCallback: (() -> Unit)? = null
    private var codecErrorCallback: ((WeatherVideoCodecErrorDiagnostic) -> Unit)? = null
    @Volatile
    private var currentVideoResId: Int = initialVideoResId
    @Volatile
    private var lastKnownGoodPositionMs: Long = 0L
    private var firstFrameLoggedForCurrentItem = false
    private var lastPlaybackSignature: Pair<Int, Boolean>? = null
    private var lastSurfaceSize: Pair<Int, Int>? = null

    override val identityHash: Int
        get() = System.identityHashCode(exoPlayer)

    override val isReleased: Boolean
        get() = releasedFlag.get()

    override val isIdle: Boolean
        get() = !releasedFlag.get() && exoPlayer.playbackState == Player.STATE_IDLE

    override val currentPositionMs: Long
        get() =
            if (releasedFlag.get()) {
                0L
            } else {
                val livePos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                if (livePos > 0L) {
                    lastKnownGoodPositionMs = livePos
                    livePos
                } else {
                    lastKnownGoodPositionMs
                }
            }

    override val currentPlaybackSpeed: Float
        get() = if (releasedFlag.get()) 1.0f else runCatching { exoPlayer.playbackParameters.speed }.getOrDefault(1.0f)

    private fun currentPlayerStateLabel(): String =
        if (releasedFlag.get()) {
            "RELEASED"
        } else {
            runCatching { playbackStateName(exoPlayer.playbackState) }.getOrDefault("UNKNOWN")
        }

    private val lifecycleDiagnosticListener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (releasedFlag.get()) return
                val isPlayingNow = exoPlayer.isPlaying
                val posNow = currentPositionMs
                val signature = playbackState to isPlayingNow
                if (lastPlaybackSignature != signature) {
                    lastPlaybackSignature = signature
                    ClimaGraphicsLifecycleDiagnostics.logExoPlaybackMilestone(
                        sessionId = sessionId,
                        playerIdentity = identityHash,
                        playbackState = playbackStateName(playbackState),
                        isPlaying = isPlayingNow,
                        videoResId = currentVideoResId,
                        positionMs = posNow,
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (releasedFlag.get()) return
                val stateNow = exoPlayer.playbackState
                val posNow = currentPositionMs
                ClimaGraphicsLifecycleDiagnostics.logExoIsPlayingChanged(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    isPlaying = isPlaying,
                    playerState = playbackStateName(stateNow),
                    videoResId = currentVideoResId,
                    positionMs = posNow,
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (releasedFlag.get()) return
                val reasonName =
                    when (reason) {
                        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                        else -> "REASON($reason)"
                    }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    ClimaGraphicsLifecycleDiagnostics.logExoMediaItemTransition(
                        sessionId = sessionId,
                        playerIdentity = identityHash,
                        transitionReason = reasonName,
                        playerState = currentPlayerStateLabel(),
                        videoResId = currentVideoResId,
                        positionMs = currentPositionMs,
                    )
                }
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                if (releasedFlag.get()) return
                val nextSize = width to height
                if (lastSurfaceSize != nextSize) {
                    lastSurfaceSize = nextSize
                    ClimaGraphicsLifecycleDiagnostics.logExoSurfaceSizeChanged(
                        sessionId = sessionId,
                        playerIdentity = identityHash,
                        width = width,
                        height = height,
                    )
                }
            }

            override fun onRenderedFirstFrame() {
                if (releasedFlag.get()) return
                val posNow = currentPositionMs
                if (!firstFrameLoggedForCurrentItem) {
                    firstFrameLoggedForCurrentItem = true
                    ClimaGraphicsLifecycleDiagnostics.logExoFirstFrame(
                        sessionId = sessionId,
                        playerIdentity = identityHash,
                        videoResId = currentVideoResId,
                        positionMs = posNow,
                        playerState = currentPlayerStateLabel(),
                    )
                }
                firstFrameCallback?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (releasedFlag.get()) return
                val posAtError = currentPositionMs
                val speedAtError = currentPlaybackSpeed
                val causeClass = (error.cause ?: error)::class.java.simpleName
                val deadObject = isMediaCodecDeadObjectOrReclaimed(error)
                firstFrameLoggedForCurrentItem = false
                ClimaGraphicsLifecycleDiagnostics.logExoPlayerError(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    errorCodeName = error.errorCodeName,
                    errorClass = causeClass,
                    isDeadObject = deadObject,
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = posAtError,
                    message = error.localizedMessage,
                )
                codecErrorCallback?.invoke(
                    WeatherVideoCodecErrorDiagnostic(
                        errorCodeName = error.errorCodeName,
                        errorClass = causeClass,
                        isDeadObjectOrReclaimed = deadObject,
                        positionMsAtError = posAtError,
                        playbackSpeedAtError = speedAtError,
                        message = error.localizedMessage,
                    ),
                )
            }
        }

    private val decoderAnalyticsListener =
        object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (releasedFlag.get()) return
                ClimaGraphicsLifecycleDiagnostics.logExoDecoderInitialized(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "VIDEO",
                    decoderName = decoderName,
                    initDurationMs = initializationDurationMs,
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                )
            }

            override fun onVideoDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
            ) {
                if (releasedFlag.get()) return
                firstFrameLoggedForCurrentItem = false
                ClimaGraphicsLifecycleDiagnostics.logExoDecoderReleased(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "VIDEO",
                    decoderName = decoderName,
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                )
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception,
            ) {
                if (releasedFlag.get()) return
                firstFrameLoggedForCurrentItem = false
                ClimaGraphicsLifecycleDiagnostics.logExoCodecError(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "VIDEO",
                    errorClass = videoCodecError::class.java.simpleName,
                    isDeadObject = isMediaCodecDeadObjectOrReclaimed(videoCodecError),
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                    message = videoCodecError.localizedMessage,
                )
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                if (releasedFlag.get()) return
                ClimaGraphicsLifecycleDiagnostics.logExoDecoderInitialized(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "AUDIO",
                    decoderName = decoderName,
                    initDurationMs = initializationDurationMs,
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                )
            }

            override fun onAudioDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
            ) {
                if (releasedFlag.get()) return
                ClimaGraphicsLifecycleDiagnostics.logExoDecoderReleased(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "AUDIO",
                    decoderName = decoderName,
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                )
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception,
            ) {
                if (releasedFlag.get()) return
                ClimaGraphicsLifecycleDiagnostics.logExoCodecError(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "AUDIO",
                    errorClass = audioCodecError::class.java.simpleName,
                    isDeadObject = isMediaCodecDeadObjectOrReclaimed(audioCodecError),
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                    message = audioCodecError.localizedMessage,
                )
            }
        }

    init {
        exoPlayer.addListener(lifecycleDiagnosticListener)
        exoPlayer.addAnalyticsListener(decoderAnalyticsListener)
    }

    override fun setVideoResIdHint(resId: Int) {
        currentVideoResId = resId
    }

    override fun setMediaItem(uri: Uri) {
        if (releasedFlag.get()) return
        firstFrameLoggedForCurrentItem = false
        lastKnownGoodPositionMs = 0L
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
    }

    override fun setVolume(volume: Float) {
        if (releasedFlag.get()) return
        exoPlayer.volume = volume
    }

    override fun setRepeatMode(repeatMode: Int) {
        if (releasedFlag.get()) return
        exoPlayer.repeatMode = repeatMode
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (releasedFlag.get()) return
        exoPlayer.playWhenReady = playWhenReady
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (releasedFlag.get()) return
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    override fun seekTo(positionMs: Long) {
        if (releasedFlag.get()) return
        val clampedPos = positionMs.coerceAtLeast(0L)
        lastKnownGoodPositionMs = clampedPos
        runCatching { exoPlayer.seekTo(clampedPos) }
    }

    override fun prepare() {
        if (releasedFlag.get()) return
        exoPlayer.prepare()
    }

    override fun stop() {
        if (releasedFlag.get()) return
        exoPlayer.stop()
    }

    override fun play() {
        if (releasedFlag.get()) return
        exoPlayer.play()
    }

    override fun pause() {
        if (releasedFlag.get()) return
        val livePos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        if (livePos > 0L) {
            lastKnownGoodPositionMs = livePos
        }
        exoPlayer.pause()
    }

    override fun clearVideoSurface() {
        if (releasedFlag.get()) return
        val livePos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        if (livePos > 0L) {
            lastKnownGoodPositionMs = livePos
        }
        runCatching { exoPlayer.clearVideoSurface() }
    }

    override fun setFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameCallback = listener
    }

    override fun setCodecErrorListener(listener: ((WeatherVideoCodecErrorDiagnostic) -> Unit)?) {
        codecErrorCallback = listener
    }

    override fun attachToPlayerView(playerView: PlayerView) {
        if (releasedFlag.get()) return
        playerView.player = exoPlayer
    }

    override fun isAttachedToPlayerView(playerView: PlayerView): Boolean =
        playerView.player === exoPlayer

    override fun releaseOnce(): Boolean {
        if (!releasedFlag.compareAndSet(false, true)) return false
        firstFrameCallback = null
        codecErrorCallback = null
        runCatching { exoPlayer.removeAnalyticsListener(decoderAnalyticsListener) }
        runCatching { exoPlayer.removeListener(lifecycleDiagnosticListener) }
        exoPlayer.release()
        return true
    }

    private fun playbackStateName(state: Int): String =
        when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
}

private class RealPlayerViewSurfaceBridge(
    private val playerView: PlayerView,
) : WeatherVideoSurfaceBridge {
    override val view: View
        get() = playerView

    override val viewIdentity: Int
        get() = System.identityHashCode(playerView)

    override val viewClassName: String
        get() = playerView::class.java.simpleName.ifEmpty { "PlayerView" }

    override fun attachPlayer(player: WeatherVideoPlayerBridge) {
        player.attachToPlayerView(playerView)
    }

    override fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean =
        player.isAttachedToPlayerView(playerView)

    override fun detachPlayer() {
        playerView.player = null
    }

    override fun inspectSurfaceSnapshot(): ExoSurfaceSnapshot {
        val surface = runCatching { playerView.videoSurfaceView }.getOrNull()
        val textureView = surface as? TextureView
        return ExoSurfaceSnapshot(
            surfaceViewClass = surface?.javaClass?.simpleName,
            textureAvailable = textureView?.isAvailable,
            hasSurfaceTexture = textureView?.let { it.surfaceTexture != null },
        )
    }

    override fun attachWindowObserver(
        onAttachStateChanged: (attached: Boolean, snapshot: ExoSurfaceSnapshot) -> Unit,
    ): () -> Unit {
        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    onAttachStateChanged(true, inspectSurfaceSnapshot())
                }

                override fun onViewDetachedFromWindow(v: View) {
                    onAttachStateChanged(false, inspectSurfaceSnapshot())
                }
            }
        playerView.addOnAttachStateChangeListener(listener)
        if (playerView.isAttachedToWindow) {
            onAttachStateChanged(true, inspectSurfaceSnapshot())
        }
        return {
            playerView.removeOnAttachStateChangeListener(listener)
        }
    }
}

/**
 * Media3 ExoPlayer looping background video component:
 * - Infinite repeat (`REPEAT_MODE_ONE`)
 * - Muted (`volume = 0f`)
 * - TextureView surface (`view_weather_video_texture.xml`) so translucent Compose glass cards
 *   composite cleanly over the video with zero SurfaceView Z-order artifacts.
 * - Supports V53 playback speed adjustments (e.g., 0.85f for `chuva_fraca`) without resetting
 *   media items or recreating the player.
 * - Enforces surface exclusivity with MapLibre (`RADAR` / `CITIES`): pauses playback, detaches
 *   `PlayerView.player = null`, and clears `ExoPlayer`'s video output surface while any MapLibre
 *   OpenGL TextureView session is active or disposing, then reattaches the same in-memory
 *   `ExoPlayer` instance once MapLibre teardown completes.
 * - Lifecycle-aware pause/resume and ordered surface-detachment before idempotent `release()`.
 */
@Composable
fun WeatherVideoBackground(
    @RawRes videoResId: Int,
    playbackSpeed: Float = 1.0f,
    isPlaying: Boolean = true,
    onFirstFrameRendered: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    WeatherVideoBackgroundImpl(
        videoResId = videoResId,
        playbackSpeed = playbackSpeed,
        isPlaying = isPlaying,
        onFirstFrameRendered = onFirstFrameRendered,
        modifier = modifier,
    )
}

@Composable
internal fun WeatherVideoBackgroundImpl(
    @RawRes videoResId: Int,
    playbackSpeed: Float = 1.0f,
    isPlaying: Boolean = true,
    onFirstFrameRendered: () -> Unit = {},
    modifier: Modifier = Modifier,
    allowVideoSurfaceOutput: Boolean = true,
    enforceMapSurfaceExclusivity: Boolean = !allowVideoSurfaceOutput,
    playerBridgeFactory: ((Context) -> WeatherVideoPlayerBridge)? = null,
    surfaceBridgeFactory: ((Context) -> WeatherVideoSurfaceBridge)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameRendered)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val coordinationSnapshot by GraphicsSurfaceCoordinator.state.collectAsState()

    val playerHandle =
        remember(context) {
            val sessionId = ClimaGraphicsLifecycleDiagnostics.nextSessionId("exo")
            val created =
                playerBridgeFactory?.invoke(context)
                    ?: RealExoPlayerBridge(
                        exoPlayer =
                            ExoPlayer.Builder(context).build().apply {
                                repeatMode = Player.REPEAT_MODE_ONE
                                volume = 0f
                                playWhenReady = isPlaying && allowVideoSurfaceOutput
                            },
                        sessionId = sessionId,
                        initialVideoResId = videoResId,
                    )
            ClimaGraphicsLifecycleDiagnostics.logExoPlayerCreated(
                sessionId = created.sessionId,
                playerIdentity = created.identityHash,
                videoResId = videoResId,
            )
            created
        }

    var surfaceBridgeRef by remember { mutableStateOf<WeatherVideoSurfaceBridge?>(null) }
    var detachWindowObserverRef by remember { mutableStateOf<(() -> Unit)?>(null) }
    var appliedVideoResId by remember(playerHandle) { mutableStateOf<Int?>(null) }
    var appliedSpeed by remember(playerHandle) { mutableStateOf<Float?>(null) }
    var lastAppliedPlayState by remember(playerHandle) { mutableStateOf<Boolean?>(null) }
    var videoSurfaceClearedForMap by remember(playerHandle) { mutableStateOf(false) }
    var pendingReattachRequestLogged by remember(playerHandle) { mutableStateOf(false) }
    var needsCodecRecovery by remember(playerHandle) { mutableStateOf(false) }
    var recoverySavedPositionMs by remember(playerHandle) { mutableLongStateOf(0L) }
    var recoverySavedSpeed by remember(playerHandle) { mutableStateOf<Float?>(null) }
    var lastCodecErrorCode by remember(playerHandle) { mutableStateOf("NONE") }
    var codecRecoveryEpoch by remember(playerHandle) { mutableIntStateOf(0) }
    var consecutiveUnrenderedRecoveryCount by remember(playerHandle) { mutableIntStateOf(0) }

    // Effective exclusivity: when mounted via WeatherBackgroundLayer (or when allowVideoSurfaceOutput
    // is false / map exclusivity is active), video output only attaches when allowed AND no MapView
    // session is active or disposing.
    val trackMapExclusivity = remember(playerHandle) { mutableStateOf(enforceMapSurfaceExclusivity) }
    if (!allowVideoSurfaceOutput || enforceMapSurfaceExclusivity) {
        trackMapExclusivity.value = true
    }
    val canAttachVideoSurfaceNow =
        allowVideoSurfaceOutput &&
            (!trackMapExclusivity.value || coordinationSnapshot.canAttachVideoOutput)
    val latestCanAttachVideoSurfaceNow by rememberUpdatedState(canAttachVideoSurfaceNow)

    fun isLifecycleStarted(): Boolean =
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun pausePlayerIdempotently() {
        if (playerHandle.isReleased) return
        if (lastAppliedPlayState != false) {
            lastAppliedPlayState = false
            playerHandle.setPlayWhenReady(false)
            playerHandle.pause()
        }
    }

    fun resumePlayerIfEligible(requireStartedLifecycle: Boolean = true) {
        if (playerHandle.isReleased) return
        val canPlayInCurrentLifecycle = !requireStartedLifecycle || isLifecycleStarted()
        if (!latestIsPlaying || !latestCanAttachVideoSurfaceNow) {
            pausePlayerIdempotently()
            return
        }
        if (!canPlayInCurrentLifecycle) {
            return
        }
        val bridge = surfaceBridgeRef
        if (bridge != null && bridge.isPlayerAttached(playerHandle)) {
            if (needsCodecRecovery) {
                val targetSpeed = (recoverySavedSpeed ?: appliedSpeed ?: playbackSpeed).coerceIn(0.25f, 2.0f)
                val targetPosMs = maxOf(recoverySavedPositionMs, playerHandle.currentPositionMs)
                if (appliedSpeed != targetSpeed || playerHandle.currentPlaybackSpeed != targetSpeed) {
                    playerHandle.setPlaybackSpeed(targetSpeed)
                    appliedSpeed = targetSpeed
                }
                if (targetPosMs > 0L && playerHandle.currentPositionMs != targetPosMs) {
                    playerHandle.seekTo(targetPosMs)
                }
                needsCodecRecovery = false
                lastAppliedPlayState = true
                ClimaGraphicsLifecycleDiagnostics.logExoRecoveryAction(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    action = "RECOVER_PREPARE",
                    reason = "codec_error_$lastCodecErrorCode",
                    videoResId = appliedVideoResId ?: videoResId,
                    positionMs = targetPosMs,
                    playbackSpeed = targetSpeed,
                )
                playerHandle.setPlayWhenReady(true)
                playerHandle.prepare()
                return
            }
            if (lastAppliedPlayState != true) {
                lastAppliedPlayState = true
                playerHandle.setPlayWhenReady(true)
                if (playerHandle.isIdle) {
                    playerHandle.prepare()
                } else {
                    playerHandle.play()
                }
            }
        }
    }

    fun detachVideoOutputForMapExclusivity(bridge: WeatherVideoSurfaceBridge, reason: String) {
        pausePlayerIdempotently()
        val wasAttachedToBridge = bridge.isPlayerAttached(playerHandle)
        if (!wasAttachedToBridge && videoSurfaceClearedForMap) {
            return
        }
        GraphicsSurfaceCoordinator.onVideoOutputDetachForMap(
            sessionId = playerHandle.sessionId,
            playerIdentity = playerHandle.identityHash,
            viewIdentity = bridge.viewIdentity,
            reason = reason,
        )
        if (wasAttachedToBridge) {
            bridge.detachPlayer()
        }
        if (!videoSurfaceClearedForMap && !playerHandle.isReleased) {
            playerHandle.clearVideoSurface()
            videoSurfaceClearedForMap = true
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                sessionId = playerHandle.sessionId,
                playerIdentity = playerHandle.identityHash,
                viewIdentity = bridge.viewIdentity,
                reason = reason,
            )
        }
        pendingReattachRequestLogged = false
        GraphicsSurfaceCoordinator.onVideoOutputDetached(
            sessionId = playerHandle.sessionId,
            playerIdentity = playerHandle.identityHash,
            viewIdentity = bridge.viewIdentity,
            reason = reason,
        )
    }

    fun attachVideoOutputIfAllowed(bridge: WeatherVideoSurfaceBridge, reason: String) {
        if (playerHandle.isReleased) return
        if (!allowVideoSurfaceOutput) {
            detachVideoOutputForMapExclusivity(bridge, reason = "map_tab_active")
            return
        }
        if (trackMapExclusivity.value && !coordinationSnapshot.canAttachVideoOutput) {
            if (bridge.isPlayerAttached(playerHandle) || !videoSurfaceClearedForMap) {
                detachVideoOutputForMapExclusivity(bridge, reason = "map_surface_busy")
            }
            if (!pendingReattachRequestLogged) {
                pendingReattachRequestLogged = true
                GraphicsSurfaceCoordinator.onVideoOutputReattachRequest(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "${reason}_awaiting_map_teardown",
                )
            }
            return
        }

        if (!bridge.isPlayerAttached(playerHandle)) {
            if (!pendingReattachRequestLogged && videoSurfaceClearedForMap) {
                GraphicsSurfaceCoordinator.onVideoOutputReattachRequest(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = reason,
                )
            }
            pendingReattachRequestLogged = false
            videoSurfaceClearedForMap = false
            bridge.attachPlayer(playerHandle)
            val surfaceFormatted = bridge.inspectSurfaceSnapshot().formatted()
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                sessionId = playerHandle.sessionId,
                playerIdentity = playerHandle.identityHash,
                viewIdentity = bridge.viewIdentity,
                viewClass = bridge.viewClassName,
                surfaceInfo = surfaceFormatted,
            )
            GraphicsSurfaceCoordinator.onVideoOutputReattached(
                sessionId = playerHandle.sessionId,
                playerIdentity = playerHandle.identityHash,
                viewIdentity = bridge.viewIdentity,
                surfaceInfo = surfaceFormatted,
            )
        }
        resumePlayerIfEligible(requireStartedLifecycle = true)
    }

    LaunchedEffect(playerHandle, videoResId) {
        if (playerHandle.isReleased || appliedVideoResId == videoResId) return@LaunchedEffect
        val uri = Uri.parse("android.resource://${context.packageName}/$videoResId")
        if (appliedVideoResId != null) {
            playerHandle.stop()
        }
        val playNow = latestIsPlaying && latestCanAttachVideoSurfaceNow
        needsCodecRecovery = false
        recoverySavedPositionMs = 0L
        consecutiveUnrenderedRecoveryCount = 0
        playerHandle.setVideoResIdHint(videoResId)
        playerHandle.setMediaItem(uri)
        playerHandle.setVolume(0f)
        playerHandle.setRepeatMode(Player.REPEAT_MODE_ONE)
        playerHandle.setPlayWhenReady(playNow)
        playerHandle.prepare()
        appliedVideoResId = videoResId
        lastAppliedPlayState = playNow
    }

    LaunchedEffect(playerHandle, playbackSpeed) {
        if (playerHandle.isReleased) return@LaunchedEffect
        val clamped = playbackSpeed.coerceIn(0.25f, 2.0f)
        if (appliedSpeed != clamped) {
            playerHandle.setPlaybackSpeed(clamped)
            appliedSpeed = clamped
            recoverySavedSpeed = clamped
        }
    }

    LaunchedEffect(
        playerHandle,
        surfaceBridgeRef,
        allowVideoSurfaceOutput,
        coordinationSnapshot.mapState,
        coordinationSnapshot.canAttachVideoOutput,
    ) {
        val bridge = surfaceBridgeRef ?: return@LaunchedEffect
        if (!allowVideoSurfaceOutput) {
            detachVideoOutputForMapExclusivity(bridge, reason = "map_tab_active")
        } else {
            attachVideoOutputIfAllowed(bridge, reason = "return_from_map")
        }
    }

    LaunchedEffect(playerHandle, isPlaying, canAttachVideoSurfaceNow, codecRecoveryEpoch) {
        if (playerHandle.isReleased) return@LaunchedEffect
        resumePlayerIfEligible()
    }

    DisposableEffect(lifecycleOwner, playerHandle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (playerHandle.isReleased) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME -> {
                        val bridge = surfaceBridgeRef
                        if (bridge != null && latestCanAttachVideoSurfaceNow && !bridge.isPlayerAttached(playerHandle)) {
                            attachVideoOutputIfAllowed(bridge, reason = "lifecycle_${event.name.lowercase()}")
                        } else {
                            resumePlayerIfEligible()
                        }
                    }

                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP -> {
                        pausePlayerIdempotently()
                    }

                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(playerHandle) {
        playerHandle.setFirstFrameListener {
            consecutiveUnrenderedRecoveryCount = 0
            latestOnFirstFrame()
        }
        playerHandle.setCodecErrorListener { diagnostic ->
            if (playerHandle.isReleased) return@setCodecErrorListener
            val posToSave = maxOf(diagnostic.positionMsAtError, playerHandle.currentPositionMs)
            val speedToSave =
                (appliedSpeed ?: diagnostic.playbackSpeedAtError.takeIf { it > 0f } ?: playbackSpeed)
                    .coerceIn(0.25f, 2.0f)
            recoverySavedPositionMs = posToSave
            recoverySavedSpeed = speedToSave
            lastCodecErrorCode = diagnostic.errorCodeName
            lastAppliedPlayState = false
            if (!latestCanAttachVideoSurfaceNow || !latestIsPlaying || !isLifecycleStarted()) {
                needsCodecRecovery = true
                consecutiveUnrenderedRecoveryCount = 0
                val deferReason =
                    when {
                        !latestCanAttachVideoSurfaceNow -> "map_surface_active_or_disposing"
                        !isLifecycleStarted() -> "activity_not_started"
                        else -> "playback_paused"
                    }
                ClimaGraphicsLifecycleDiagnostics.logExoRecoveryAction(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    action = "DEFERRED",
                    reason = deferReason,
                    videoResId = appliedVideoResId ?: videoResId,
                    positionMs = posToSave,
                    playbackSpeed = speedToSave,
                )
            } else if (consecutiveUnrenderedRecoveryCount < 2) {
                consecutiveUnrenderedRecoveryCount++
                needsCodecRecovery = true
                codecRecoveryEpoch++
            }
        }
        onDispose {
            playerHandle.setFirstFrameListener(null)
            playerHandle.setCodecErrorListener(null)
            detachWindowObserverRef?.invoke()
            detachWindowObserverRef = null
            surfaceBridgeRef?.let { bridge ->
                val wasAttached = bridge.isPlayerAttached(playerHandle)
                if (wasAttached) {
                    bridge.detachPlayer()
                }
                if (!videoSurfaceClearedForMap) {
                    playerHandle.clearVideoSurface()
                    videoSurfaceClearedForMap = true
                    ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                        sessionId = playerHandle.sessionId,
                        playerIdentity = playerHandle.identityHash,
                        viewIdentity = bridge.viewIdentity,
                        reason = "onDispose",
                    )
                }
                GraphicsSurfaceCoordinator.onVideoOutputDetached(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "onDispose",
                )
            } ?: run {
                if (!videoSurfaceClearedForMap) {
                    playerHandle.clearVideoSurface()
                    videoSurfaceClearedForMap = true
                }
            }
            surfaceBridgeRef = null
            if (playerHandle.releaseOnce()) {
                ClimaGraphicsLifecycleDiagnostics.logExoPlayerReleased(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                )
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val bridge =
                surfaceBridgeFactory?.invoke(viewContext)
                    ?: RealPlayerViewSurfaceBridge(
                        LayoutInflater.from(viewContext)
                            .inflate(R.layout.view_weather_video_texture, null, false) as PlayerView,
                    )
            surfaceBridgeRef = bridge
            detachWindowObserverRef?.invoke()
            detachWindowObserverRef =
                bridge.attachWindowObserver { attached, snapshot ->
                    ClimaGraphicsLifecycleDiagnostics.logExoViewAttachState(
                        sessionId = playerHandle.sessionId,
                        playerIdentity = playerHandle.identityHash,
                        viewClass = bridge.viewClassName,
                        viewIdentity = bridge.viewIdentity,
                        attached = attached,
                        surfaceViewClass = snapshot.surfaceViewClass,
                        textureAvailable = snapshot.textureAvailable,
                        hasSurfaceTexture = snapshot.hasSurfaceTexture,
                    )
                }
            if (canAttachVideoSurfaceNow) {
                bridge.attachPlayer(playerHandle)
                videoSurfaceClearedForMap = false
                val surfaceFormatted = bridge.inspectSurfaceSnapshot().formatted()
                ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    viewClass = bridge.viewClassName,
                    surfaceInfo = surfaceFormatted,
                )
                GraphicsSurfaceCoordinator.onVideoOutputReattached(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    surfaceInfo = surfaceFormatted,
                )
            } else {
                videoSurfaceClearedForMap = true
                GraphicsSurfaceCoordinator.onVideoOutputDetached(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "factory_map_active",
                )
            }
            bridge.view
        },
        update = {
            val bridge = surfaceBridgeRef ?: return@AndroidView
            if (!allowVideoSurfaceOutput) {
                detachVideoOutputForMapExclusivity(bridge, reason = "map_tab_active")
            } else if (!playerHandle.isReleased && canAttachVideoSurfaceNow && !bridge.isPlayerAttached(playerHandle)) {
                attachVideoOutputIfAllowed(bridge, reason = "android_view_update")
            }
        },
        onRelease = {
            detachWindowObserverRef?.invoke()
            detachWindowObserverRef = null
            val bridge = surfaceBridgeRef
            if (bridge != null) {
                if (bridge.isPlayerAttached(playerHandle)) {
                    bridge.detachPlayer()
                }
                if (!playerHandle.isReleased && !videoSurfaceClearedForMap) {
                    playerHandle.clearVideoSurface()
                    videoSurfaceClearedForMap = true
                    ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                        sessionId = playerHandle.sessionId,
                        playerIdentity = playerHandle.identityHash,
                        viewIdentity = bridge.viewIdentity,
                        reason = "onRelease",
                    )
                }
                GraphicsSurfaceCoordinator.onVideoOutputDetached(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "onRelease",
                )
                surfaceBridgeRef = null
            }
        },
    )
}

