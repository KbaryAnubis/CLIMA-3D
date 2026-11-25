package com.example.ui.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.net.Uri
import android.os.DeadObjectException
import android.os.SystemClock
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
import kotlinx.coroutines.delay

internal const val DEFAULT_FIRST_FRAME_TIMEOUT_MS: Long = 4_000L
internal const val MAX_EXISTING_PLAYER_RECOVERY_ATTEMPTS: Int = 2
internal const val MAX_PLAYER_REBUILD_ATTEMPTS: Int = 1

internal enum class WeatherVideoVisualState {
    VIDEO_NOT_READY,
    VIDEO_PREPARING,
    VIDEO_WAITING_FIRST_FRAME,
    VIDEO_FRAME_READY,
    VIDEO_RECOVERING,
    VIDEO_OUTPUT_DETACHED,
}

internal enum class WeatherVideoRecoveryState {
    IDLE,
    PREPARING,
    WAITING_FIRST_FRAME,
    PLAYING,
    DECODER_REVOKED,
    RECOVERING,
    REBUILDING_PLAYER,
    FAILED,
}

internal enum class WeatherVideoRecoveryTier {
    NONE,
    RECOVER_EXISTING,
    CONTROLLED_RETRY,
    REBUILD_PLAYER,
}

internal data class WeatherVideoVisualSnapshot(
    val visualState: WeatherVideoVisualState = WeatherVideoVisualState.VIDEO_NOT_READY,
    val sessionId: String = "",
    val playerIdentity: Int = 0,
    val videoResId: Int = 0,
    val playerState: String = "IDLE",
    val decoderGeneration: Int = 0,
    val frameReady: Boolean = false,
    val surfaceOutputAttached: Boolean = false,
    val hasPendingFatalError: Boolean = false,
    val recoveryExhausted: Boolean = false,
    val recoveryState: WeatherVideoRecoveryState = WeatherVideoRecoveryState.IDLE,
    val surfaceEpoch: Int = 0,
    val surfaceAvailable: Boolean = true,
    val recoveryAttempt: Int = 0,
    val rebuildCount: Int = 0,
    val playerGeneration: Int = 1,
) {
    /**
     * A video frame is visually eligible for display ONLY when:
     * - visualState is VIDEO_FRAME_READY and recoveryState is PLAYING
     * - frameReady is true (confirmed by onRenderedFirstFrame for the current player, MediaItem,
     *   active decoder generation, and surface epoch)
     * - surfaceOutputAttached and surfaceAvailable are both true
     * - no fatal decoder error or recovery is pending or exhausted
     */
    val isEligibleForVisibleVideo: Boolean
        get() =
            visualState == WeatherVideoVisualState.VIDEO_FRAME_READY &&
                recoveryState == WeatherVideoRecoveryState.PLAYING &&
                frameReady &&
                surfaceOutputAttached &&
                surfaceAvailable &&
                !hasPendingFatalError &&
                !recoveryExhausted
}

internal data class ExoSurfaceSnapshot(
    val surfaceViewClass: String? = null,
    val textureAvailable: Boolean? = null,
    val hasSurfaceTexture: Boolean? = null,
) {
    val isSurfaceValid: Boolean
        get() = textureAvailable != false && hasSurfaceTexture != false

    fun statusLabel(attachedToWindow: Boolean = true): String =
        when {
            !attachedToWindow -> "detached_from_window"
            textureAvailable == false -> "texture_unavailable"
            hasSurfaceTexture == false -> "no_surface_texture"
            else -> "valid"
        }

    fun formatted(): String =
        "surfaceView=${surfaceViewClass ?: "none"}, textureAvailable=${textureAvailable ?: "unknown"}, hasSurfaceTexture=${hasSurfaceTexture ?: "unknown"}"
}

internal data class WeatherVideoFrameToken(
    val sessionId: String,
    val playerIdentity: Int,
    val videoResId: Int,
    val decoderGeneration: Int,
    val surfaceEpoch: Int = 0,
    val surfaceViewIdentity: Int = 0,
    val decoderAlive: Boolean = true,
)

internal data class WeatherVideoCodecErrorDiagnostic(
    val errorCodeName: String,
    val errorClass: String,
    val isDeadObjectOrReclaimed: Boolean,
    val positionMsAtError: Long,
    val playbackSpeedAtError: Float,
    val decoderGenerationAtError: Int = 0,
    val message: String?,
)

internal interface WeatherVideoPlayerBridge {
    val sessionId: String
    val identityHash: Int
    val isReleased: Boolean
    val isIdle: Boolean
    val playerStateName: String
        get() = if (isReleased) "RELEASED" else if (isIdle) "IDLE" else "READY"
    val decoderGeneration: Int
        get() = 1
    val isDecoderAlive: Boolean
        get() = !isReleased && !isIdle && decoderGeneration > 0
    val surfaceEpoch: Int
        get() = 0
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
    fun setFirstFrameTokenListener(listener: ((WeatherVideoFrameToken) -> Unit)?) {}
    fun setDecoderLifecycleListener(
        listener: ((initialized: Boolean, decoderGeneration: Int, decoderName: String) -> Unit)?,
    ) {}
    fun setPlaybackStateListener(listener: ((playerStateName: String, isPlaying: Boolean) -> Unit)?) {}
    fun setSurfaceSizeListener(listener: ((width: Int, height: Int) -> Unit)?) {}
    fun setCodecErrorListener(listener: ((WeatherVideoCodecErrorDiagnostic) -> Unit)?) {}
    fun notifySurfaceReattachedForNewFrame() {}
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
                diag.contains("ERROR_RECLAIMED", ignoreCase = true) ||
                diag.contains("INSUFFICIENT_RESOURCE", ignoreCase = true) ||
                msg.contains("DEAD_OBJECT", ignoreCase = true) ||
                msg.contains("Released by resource manager", ignoreCase = true) ||
                msg.contains("ERROR_RECLAIMED", ignoreCase = true) ||
                msg.contains("ERROR_INSUFFICIENT_RESOURCE", ignoreCase = true) ||
                cursor.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED ||
                cursor.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE
            ) {
                return true
            }
        }
        val msg = cursor.message.orEmpty()
        if (msg.contains("DEAD_OBJECT", ignoreCase = true) ||
            msg.contains("Released by resource manager", ignoreCase = true) ||
            msg.contains("ERROR_RECLAIMED", ignoreCase = true) ||
            msg.contains("ERROR_INSUFFICIENT_RESOURCE", ignoreCase = true)
        ) {
            return true
        }
        cursor = cursor.cause
        depth++
    }
    return false
}

private fun monotonicElapsedMs(): Long =
    runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.currentTimeMillis() }

private class RealExoPlayerBridge(
    private val exoPlayer: ExoPlayer,
    override val sessionId: String,
    initialVideoResId: Int,
) : WeatherVideoPlayerBridge {
    private val releasedFlag = AtomicBoolean(false)
    private var firstFrameCallback: (() -> Unit)? = null
    private var firstFrameTokenCallback: ((WeatherVideoFrameToken) -> Unit)? = null
    private var decoderLifecycleCallback: ((Boolean, Int, String) -> Unit)? = null
    private var playbackStateCallback: ((String, Boolean) -> Unit)? = null
    private var surfaceSizeCallback: ((Int, Int) -> Unit)? = null
    private var codecErrorCallback: ((WeatherVideoCodecErrorDiagnostic) -> Unit)? = null
    @Volatile
    private var currentVideoResId: Int = initialVideoResId
    @Volatile
    private var lastKnownGoodPositionMs: Long = 0L
    @Volatile
    private var currentDecoderGen: Int = 0
    @Volatile
    private var currentDecoderAlive: Boolean = false
    @Volatile
    private var currentSurfaceEpoch: Int = 0
    @Volatile
    private var attachedSurfaceViewId: Int = 0
    private var firstFrameLoggedForCurrentItem = false
    private var lastPlaybackSignature: Pair<Int, Boolean>? = null
    private var lastSurfaceSize: Pair<Int, Int>? = null

    override val identityHash: Int
        get() = System.identityHashCode(exoPlayer)

    override val isReleased: Boolean
        get() = releasedFlag.get()

    override val isIdle: Boolean
        get() = !releasedFlag.get() && exoPlayer.playbackState == Player.STATE_IDLE

    override val playerStateName: String
        get() = currentPlayerStateLabel()

    override val decoderGeneration: Int
        get() = currentDecoderGen

    override val isDecoderAlive: Boolean
        get() = !releasedFlag.get() && currentDecoderAlive

    override val surfaceEpoch: Int
        get() = currentSurfaceEpoch

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
                if (playbackState == Player.STATE_IDLE) {
                    currentDecoderAlive = false
                }
                val isPlayingNow = exoPlayer.isPlaying
                val posNow = currentPositionMs
                val stateLabel = playbackStateName(playbackState)
                val signature = playbackState to isPlayingNow
                if (lastPlaybackSignature != signature) {
                    lastPlaybackSignature = signature
                    ClimaGraphicsLifecycleDiagnostics.logExoPlaybackMilestone(
                        sessionId = sessionId,
                        playerIdentity = identityHash,
                        playbackState = stateLabel,
                        isPlaying = isPlayingNow,
                        videoResId = currentVideoResId,
                        positionMs = posNow,
                    )
                }
                playbackStateCallback?.invoke(stateLabel, isPlayingNow)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (releasedFlag.get()) return
                val stateNow = exoPlayer.playbackState
                val posNow = currentPositionMs
                val stateLabel = playbackStateName(stateNow)
                ClimaGraphicsLifecycleDiagnostics.logExoIsPlayingChanged(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    isPlaying = isPlaying,
                    playerState = stateLabel,
                    videoResId = currentVideoResId,
                    positionMs = posNow,
                )
                playbackStateCallback?.invoke(stateLabel, isPlaying)
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
                surfaceSizeCallback?.invoke(width, height)
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
                val token =
                    WeatherVideoFrameToken(
                        sessionId = sessionId,
                        playerIdentity = identityHash,
                        videoResId = currentVideoResId,
                        decoderGeneration = currentDecoderGen.coerceAtLeast(1),
                        surfaceEpoch = currentSurfaceEpoch,
                        surfaceViewIdentity = attachedSurfaceViewId,
                        decoderAlive = currentDecoderAlive,
                    )
                val tokenCallback = firstFrameTokenCallback
                if (tokenCallback != null) {
                    tokenCallback.invoke(token)
                } else {
                    firstFrameCallback?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (releasedFlag.get()) return
                val posAtError = currentPositionMs
                val speedAtError = currentPlaybackSpeed
                val causeClass = (error.cause ?: error)::class.java.simpleName
                val deadObject = isMediaCodecDeadObjectOrReclaimed(error)
                currentDecoderAlive = false
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
                        decoderGenerationAtError = currentDecoderGen,
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
                currentDecoderGen += 1
                currentDecoderAlive = true
                firstFrameLoggedForCurrentItem = false
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
                decoderLifecycleCallback?.invoke(true, currentDecoderGen, decoderName)
            }

            override fun onVideoDecoderReleased(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
            ) {
                if (releasedFlag.get()) return
                currentDecoderAlive = false
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
                decoderLifecycleCallback?.invoke(false, currentDecoderGen, decoderName)
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception,
            ) {
                if (releasedFlag.get()) return
                currentDecoderAlive = false
                firstFrameLoggedForCurrentItem = false
                val deadObject = isMediaCodecDeadObjectOrReclaimed(videoCodecError)
                ClimaGraphicsLifecycleDiagnostics.logExoCodecError(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    trackType = "VIDEO",
                    errorClass = videoCodecError::class.java.simpleName,
                    isDeadObject = deadObject,
                    playerState = currentPlayerStateLabel(),
                    videoResId = currentVideoResId,
                    positionMs = currentPositionMs,
                    message = videoCodecError.localizedMessage,
                )
                decoderLifecycleCallback?.invoke(
                    false,
                    currentDecoderGen,
                    "codec_error:${videoCodecError::class.java.simpleName}",
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
        firstFrameLoggedForCurrentItem = false
        exoPlayer.prepare()
    }

    override fun stop() {
        if (releasedFlag.get()) return
        currentDecoderAlive = false
        firstFrameLoggedForCurrentItem = false
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
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = 0
        firstFrameLoggedForCurrentItem = false
        val livePos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        if (livePos > 0L) {
            lastKnownGoodPositionMs = livePos
        }
        runCatching { exoPlayer.clearVideoSurface() }
    }

    override fun setFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameCallback = listener
    }

    override fun setFirstFrameTokenListener(listener: ((WeatherVideoFrameToken) -> Unit)?) {
        firstFrameTokenCallback = listener
    }

    override fun setDecoderLifecycleListener(
        listener: ((initialized: Boolean, decoderGeneration: Int, decoderName: String) -> Unit)?,
    ) {
        decoderLifecycleCallback = listener
    }

    override fun setPlaybackStateListener(listener: ((playerStateName: String, isPlaying: Boolean) -> Unit)?) {
        playbackStateCallback = listener
    }

    override fun setSurfaceSizeListener(listener: ((width: Int, height: Int) -> Unit)?) {
        surfaceSizeCallback = listener
    }

    override fun setCodecErrorListener(listener: ((WeatherVideoCodecErrorDiagnostic) -> Unit)?) {
        codecErrorCallback = listener
    }

    override fun notifySurfaceReattachedForNewFrame() {
        currentSurfaceEpoch += 1
        firstFrameLoggedForCurrentItem = false
    }

    override fun attachToPlayerView(playerView: PlayerView) {
        if (releasedFlag.get()) return
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = System.identityHashCode(playerView)
        firstFrameLoggedForCurrentItem = false
        playerView.player = exoPlayer
    }

    override fun isAttachedToPlayerView(playerView: PlayerView): Boolean =
        playerView.player === exoPlayer

    override fun releaseOnce(): Boolean {
        if (!releasedFlag.compareAndSet(false, true)) return false
        currentDecoderAlive = false
        firstFrameCallback = null
        firstFrameTokenCallback = null
        decoderLifecycleCallback = null
        playbackStateCallback = null
        surfaceSizeCallback = null
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
    private var attachCallback: ((Boolean, ExoSurfaceSnapshot) -> Unit)? = null
    private var observedTextureView: TextureView? = null
    private var preAttachSurfaceListener: TextureView.SurfaceTextureListener? = null

    override val view: View
        get() = playerView

    override val viewIdentity: Int
        get() = System.identityHashCode(playerView)

    override val viewClassName: String
        get() = playerView::class.java.simpleName.ifEmpty { "PlayerView" }

    override fun attachPlayer(player: WeatherVideoPlayerBridge) {
        // Clear any passive pre-attach TextureView listener before ExoPlayer binds its own ComponentListener,
        // avoiding Media3 warnings or double-ownership of TextureView.surfaceTextureListener.
        clearPreAttachSurfaceListener()
        player.attachToPlayerView(playerView)
    }

    override fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean =
        player.isAttachedToPlayerView(playerView)

    override fun detachPlayer() {
        playerView.player = null
        ensurePreAttachSurfaceListener()
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

    private fun clearPreAttachSurfaceListener() {
        val textureView = observedTextureView ?: (runCatching { playerView.videoSurfaceView }.getOrNull() as? TextureView)
        val existingListener = preAttachSurfaceListener
        if (textureView != null && existingListener != null && textureView.surfaceTextureListener === existingListener) {
            textureView.surfaceTextureListener = null
        }
        preAttachSurfaceListener = null
    }

    private fun ensurePreAttachSurfaceListener() {
        val callback = attachCallback ?: return
        if (playerView.player != null) return
        val textureView = runCatching { playerView.videoSurfaceView }.getOrNull() as? TextureView ?: return
        if (textureView.surfaceTextureListener != null && textureView.surfaceTextureListener !== preAttachSurfaceListener) {
            return
        }
        observedTextureView = textureView
        if (preAttachSurfaceListener == null) {
            preAttachSurfaceListener =
                object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        val valid = width > 0 && height > 0
                        callback(
                            playerView.isAttachedToWindow,
                            ExoSurfaceSnapshot(
                                surfaceViewClass = "TextureView",
                                textureAvailable = valid,
                                hasSurfaceTexture = valid,
                            ),
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        val valid = width > 0 && height > 0
                        callback(
                            playerView.isAttachedToWindow,
                            ExoSurfaceSnapshot(
                                surfaceViewClass = "TextureView",
                                textureAvailable = valid && textureView.isAvailable,
                                hasSurfaceTexture = valid && textureView.surfaceTexture != null,
                            ),
                        )
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        callback(
                            playerView.isAttachedToWindow,
                            ExoSurfaceSnapshot(
                                surfaceViewClass = "TextureView",
                                textureAvailable = false,
                                hasSurfaceTexture = false,
                            ),
                        )
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
        }
        textureView.surfaceTextureListener = preAttachSurfaceListener
    }

    override fun attachWindowObserver(
        onAttachStateChanged: (attached: Boolean, snapshot: ExoSurfaceSnapshot) -> Unit,
    ): () -> Unit {
        attachCallback = onAttachStateChanged
        val playerViewListener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    ensurePreAttachSurfaceListener()
                    onAttachStateChanged(true, inspectSurfaceSnapshot())
                }

                override fun onViewDetachedFromWindow(v: View) {
                    onAttachStateChanged(false, inspectSurfaceSnapshot())
                }
            }
        val surfaceView = runCatching { playerView.videoSurfaceView }.getOrNull()
        val surfaceViewListener =
            if (surfaceView != null && surfaceView !== playerView) {
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        ensurePreAttachSurfaceListener()
                        onAttachStateChanged(playerView.isAttachedToWindow, inspectSurfaceSnapshot())
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        onAttachStateChanged(
                            false,
                            ExoSurfaceSnapshot(
                                surfaceViewClass = surfaceView.javaClass.simpleName,
                                textureAvailable = false,
                                hasSurfaceTexture = false,
                            ),
                        )
                    }
                }
            } else {
                null
            }
        playerView.addOnAttachStateChangeListener(playerViewListener)
        if (surfaceView != null && surfaceViewListener != null) {
            surfaceView.addOnAttachStateChangeListener(surfaceViewListener)
        }
        ensurePreAttachSurfaceListener()
        if (playerView.isAttachedToWindow) {
            onAttachStateChanged(true, inspectSurfaceSnapshot())
        }
        return {
            attachCallback = null
            clearPreAttachSurfaceListener()
            observedTextureView = null
            playerView.removeOnAttachStateChangeListener(playerViewListener)
            if (surfaceView != null && surfaceViewListener != null) {
                surfaceView.removeOnAttachStateChangeListener(surfaceViewListener)
            }
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
 * - Enforces surface exclusivity with MapLibre (`MapLocationPicker`): pauses playback, detaches
 *   `PlayerView.player = null`, and clears `ExoPlayer`'s video output surface while any MapLibre
 *   OpenGL TextureView session is active or disposing, then reattaches the same in-memory
 *   `ExoPlayer` instance once MapLibre teardown completes.
 * - Tracks an explicit frame & recovery state machine ([WeatherVideoRecoveryState] +
 *   [WeatherVideoVisualState]) with first-frame timeout, surface-validity gating, monotonic
 *   decoder generation / surface epoch validation, and a 3-tier recovery strategy
 *   (`RECOVER_EXISTING` -> `CONTROLLED_RETRY` -> last-resort `REBUILD_PLAYER` -> `FAILED`).
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
    onVisualStateChanged: (WeatherVideoVisualSnapshot) -> Unit = {},
    modifier: Modifier = Modifier,
    allowVideoSurfaceOutput: Boolean = true,
    enforceMapSurfaceExclusivity: Boolean = !allowVideoSurfaceOutput,
    playerBridgeFactory: ((Context) -> WeatherVideoPlayerBridge)? = null,
    surfaceBridgeFactory: ((Context) -> WeatherVideoSurfaceBridge)? = null,
    firstFrameTimeoutMs: Long = DEFAULT_FIRST_FRAME_TIMEOUT_MS,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameRendered)
    val latestOnVisualStateChanged by rememberUpdatedState(onVisualStateChanged)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestVideoResId by rememberUpdatedState(videoResId)
    val latestPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val coordinationSnapshot by GraphicsSurfaceCoordinator.state.collectAsState()

    fun createAndLogPlayerBridge(@RawRes initialResId: Int, initialPlayWhenReady: Boolean): WeatherVideoPlayerBridge {
        val sessionId = ClimaGraphicsLifecycleDiagnostics.nextSessionId("exo")
        val created =
            playerBridgeFactory?.invoke(context)
                ?: RealExoPlayerBridge(
                    exoPlayer =
                        ExoPlayer.Builder(context).build().apply {
                            repeatMode = Player.REPEAT_MODE_ONE
                            volume = 0f
                            playWhenReady = initialPlayWhenReady
                        },
                    sessionId = sessionId,
                    initialVideoResId = initialResId,
                )
        ClimaGraphicsLifecycleDiagnostics.logExoPlayerCreated(
            sessionId = created.sessionId,
            playerIdentity = created.identityHash,
            videoResId = initialResId,
        )
        return created
    }

    var playerHandle by remember(context) {
        mutableStateOf(
            createAndLogPlayerBridge(
                initialResId = videoResId,
                initialPlayWhenReady = isPlaying && allowVideoSurfaceOutput,
            ),
        )
    }
    var playerGeneration by remember(context) { mutableIntStateOf(1) }

    var surfaceBridgeRef by remember(context) { mutableStateOf<WeatherVideoSurfaceBridge?>(null) }
    var detachWindowObserverRef by remember(context) { mutableStateOf<(() -> Unit)?>(null) }
    var appliedVideoResId by remember(context) { mutableStateOf<Int?>(null) }
    var appliedSpeed by remember(context) { mutableStateOf<Float?>(null) }
    var lastAppliedPlayState by remember(context) { mutableStateOf<Boolean?>(null) }
    var videoSurfaceClearedForMap by remember(context) { mutableStateOf(false) }
    var pendingReattachRequestLogged by remember(context) { mutableStateOf(false) }

    // Visual frame availability & recovery state machine (Etapas 3M, 3N, 3O, 3P.3)
    var visualState by remember(context) { mutableStateOf(WeatherVideoVisualState.VIDEO_NOT_READY) }
    var recoveryState by remember(context) { mutableStateOf(WeatherVideoRecoveryState.IDLE) }
    var activeRecoveryTier by remember(context) { mutableStateOf(WeatherVideoRecoveryTier.NONE) }
    var activeDecoderGeneration by remember(context) { mutableIntStateOf(0) }
    var isDecoderCurrentlyAlive by remember(context) { mutableStateOf(false) }
    var activeSurfaceAttachmentEpoch by remember(context) { mutableIntStateOf(0) }
    var activeSurfaceViewIdentity by remember(context) { mutableIntStateOf(0) }
    var isTextureSurfaceAvailable by remember(context) { mutableStateOf(true) }

    var renderedFrameSessionId by remember(context) { mutableStateOf("") }
    var renderedFramePlayerIdentity by remember(context) { mutableIntStateOf(0) }
    var renderedFrameDecoderGeneration by remember(context) { mutableIntStateOf(-1) }
    var renderedFrameVideoResId by remember(context) { mutableStateOf<Int?>(null) }
    var renderedFrameSurfaceEpoch by remember(context) { mutableIntStateOf(-1) }
    var renderedFrameViewIdentity by remember(context) { mutableIntStateOf(0) }
    var hasValidRenderedFrame by remember(context) { mutableStateOf(false) }

    var hasPendingFatalDecoderError by remember(context) { mutableStateOf(false) }
    var recoveryInProgress by remember(context) { mutableStateOf(false) }
    var recoveryAttemptInFlight by remember(context) { mutableStateOf(false) }
    var recoveryExhaustedForCurrentMedia by remember(context) { mutableStateOf(false) }

    var needsCodecRecovery by remember(context) { mutableStateOf(false) }
    var wasDecoderRevokedWhileDetached by remember(context) { mutableStateOf(false) }
    var recoverySavedPositionMs by remember(context) { mutableLongStateOf(0L) }
    var recoverySavedSpeed by remember(context) { mutableStateOf<Float?>(null) }
    var lastCodecErrorCode by remember(context) { mutableStateOf("NONE") }
    var lastRevocationReason by remember(context) { mutableStateOf("NONE") }
    var codecRecoveryEpoch by remember(context) { mutableIntStateOf(0) }
    var existingPlayerRecoveryAttempts by remember(context) { mutableIntStateOf(0) }
    var playerRebuildAttempts by remember(context) { mutableIntStateOf(0) }
    var recoveryStartElapsedMs by remember(context) { mutableLongStateOf(0L) }
    var firstFrameWatchEpoch by remember(context) { mutableIntStateOf(0) }
    var isIntentionalPlayerStop by remember(context) { mutableStateOf(false) }

    // Effective exclusivity: when mounted via WeatherBackgroundLayer (or when allowVideoSurfaceOutput
    // is false / map exclusivity is active), video output only attaches when allowed AND no MapView
    // session is active or disposing.
    val trackMapExclusivity = remember(context) { mutableStateOf(enforceMapSurfaceExclusivity) }
    if (!allowVideoSurfaceOutput || enforceMapSurfaceExclusivity) {
        trackMapExclusivity.value = true
    }
    val canAttachVideoSurfaceNow =
        allowVideoSurfaceOutput &&
            (!trackMapExclusivity.value || coordinationSnapshot.canAttachVideoOutput)
    val latestCanAttachVideoSurfaceNow by rememberUpdatedState(canAttachVideoSurfaceNow)

    fun isLifecycleStarted(): Boolean =
        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun currentSurfaceStatus(): String {
        val bridge = surfaceBridgeRef ?: return "no_view"
        if (videoSurfaceClearedForMap) return "detached_for_map"
        if (!isTextureSurfaceAvailable) {
            return bridge.inspectSurfaceSnapshot().statusLabel(attachedToWindow = bridge.view.isAttachedToWindow)
        }
        return if (bridge.isPlayerAttached(playerHandle)) "valid" else "player_not_attached"
    }

    fun isSurfaceCurrentlyAttached(): Boolean {
        val bridge = surfaceBridgeRef
        return bridge != null &&
            bridge.isPlayerAttached(playerHandle) &&
            !videoSurfaceClearedForMap &&
            isTextureSurfaceAvailable
    }

    var lastPublishedSnapshot by remember(context) { mutableStateOf<WeatherVideoVisualSnapshot?>(null) }

    fun isCurrentRenderedFrameStrictlyValid(targetResId: Int): Boolean {
        val bridge = surfaceBridgeRef
        val currentViewId = bridge?.viewIdentity ?: 0
        val attached =
            bridge != null &&
                bridge.isPlayerAttached(playerHandle) &&
                !videoSurfaceClearedForMap &&
                isTextureSurfaceAvailable
        return hasValidRenderedFrame &&
            isDecoderCurrentlyAlive &&
            recoveryState == WeatherVideoRecoveryState.PLAYING &&
            renderedFrameSessionId == playerHandle.sessionId &&
            renderedFramePlayerIdentity == playerHandle.identityHash &&
            renderedFrameVideoResId == targetResId &&
            activeDecoderGeneration > 0 &&
            renderedFrameDecoderGeneration == activeDecoderGeneration &&
            renderedFrameSurfaceEpoch == activeSurfaceAttachmentEpoch &&
            (renderedFrameViewIdentity == 0 || renderedFrameViewIdentity == currentViewId) &&
            attached &&
            !hasPendingFatalDecoderError &&
            !recoveryExhaustedForCurrentMedia &&
            !needsCodecRecovery &&
            !recoveryInProgress
    }

    fun publishVisualState(
        nextState: WeatherVideoVisualState,
        nextRecoveryState: WeatherVideoRecoveryState = recoveryState,
        milestoneEvent: String? = null,
        reason: String? = null,
    ) {
        visualState = nextState
        recoveryState = nextRecoveryState
        val currentRes = appliedVideoResId ?: latestVideoResId
        val attached = isSurfaceCurrentlyAttached()
        val frameReadyNow =
            nextState == WeatherVideoVisualState.VIDEO_FRAME_READY &&
                isCurrentRenderedFrameStrictlyValid(currentRes)
        val snapshot =
            WeatherVideoVisualSnapshot(
                visualState = nextState,
                sessionId = playerHandle.sessionId,
                playerIdentity = playerHandle.identityHash,
                videoResId = currentRes,
                playerState = playerHandle.playerStateName,
                decoderGeneration = activeDecoderGeneration,
                frameReady = frameReadyNow,
                surfaceOutputAttached = attached,
                hasPendingFatalError = hasPendingFatalDecoderError,
                recoveryExhausted = recoveryExhaustedForCurrentMedia,
                recoveryState = nextRecoveryState,
                surfaceEpoch = activeSurfaceAttachmentEpoch,
                surfaceAvailable = isTextureSurfaceAvailable && !videoSurfaceClearedForMap,
                recoveryAttempt = existingPlayerRecoveryAttempts + playerRebuildAttempts,
                rebuildCount = playerRebuildAttempts,
                playerGeneration = playerGeneration,
            )
        if (milestoneEvent != null) {
            ClimaGraphicsLifecycleDiagnostics.logBackgroundVisualMilestone(
                eventName = milestoneEvent,
                sessionId = snapshot.sessionId,
                playerIdentity = snapshot.playerIdentity,
                videoResId = snapshot.videoResId,
                playerState = snapshot.playerState,
                decoderGeneration = snapshot.decoderGeneration,
                videoAlpha = if (snapshot.isEligibleForVisibleVideo) 1f else 0f,
                frameReady = snapshot.frameReady,
                visualState = snapshot.visualState.name,
                reason = reason,
                recoveryState = snapshot.recoveryState.name,
                surfaceEpoch = snapshot.surfaceEpoch,
            )
        }
        if (lastPublishedSnapshot != snapshot) {
            lastPublishedSnapshot = snapshot
            latestOnVisualStateChanged(snapshot)
        }
    }

    fun invalidateCurrentFrame(
        nextState: WeatherVideoVisualState,
        nextRecoveryState: WeatherVideoRecoveryState = recoveryState,
        milestoneEvent: String? = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
        reason: String,
    ) {
        val wasFrameReady = hasValidRenderedFrame || visualState == WeatherVideoVisualState.VIDEO_FRAME_READY
        hasValidRenderedFrame = false
        renderedFrameSessionId = ""
        renderedFramePlayerIdentity = 0
        renderedFrameDecoderGeneration = -1
        renderedFrameSurfaceEpoch = -1
        renderedFrameViewIdentity = 0
        val eventToEmit =
            if (milestoneEvent == "BACKGROUND_VIDEO_HIDDEN_NO_FRAME" && !wasFrameReady && visualState == nextState && recoveryState == nextRecoveryState) {
                null
            } else {
                milestoneEvent
            }
        publishVisualState(
            nextState = nextState,
            nextRecoveryState = nextRecoveryState,
            milestoneEvent = eventToEmit,
            reason = reason,
        )
    }

    fun pausePlayerIdempotently() {
        val activePlayer = playerHandle
        if (activePlayer.isReleased) return
        if (lastAppliedPlayState != false) {
            lastAppliedPlayState = false
            activePlayer.setPlayWhenReady(false)
            activePlayer.pause()
        }
    }

    fun clearCallbacksFromPlayer(targetPlayer: WeatherVideoPlayerBridge) {
        targetPlayer.setFirstFrameListener(null)
        targetPlayer.setFirstFrameTokenListener(null)
        targetPlayer.setDecoderLifecycleListener(null)
        targetPlayer.setPlaybackStateListener(null)
        targetPlayer.setSurfaceSizeListener(null)
        targetPlayer.setCodecErrorListener(null)
    }

    lateinit var bindCallbacksToPlayer: (WeatherVideoPlayerBridge) -> Unit

    fun executeRebuildPlayerAsLastResort(reason: String) {
        if (recoveryAttemptInFlight || recoveryExhaustedForCurrentMedia) return
        val oldPlayer = playerHandle
        val bridge = surfaceBridgeRef
        if (bridge == null || !isTextureSurfaceAvailable || !latestCanAttachVideoSurfaceNow) {
            needsCodecRecovery = true
            ClimaGraphicsLifecycleDiagnostics.logVideoRecoveryOutcome(
                sessionId = oldPlayer.sessionId,
                playerIdentity = oldPlayer.identityHash,
                tier = WeatherVideoRecoveryTier.REBUILD_PLAYER.name,
                outcome = "SURFACE_UNAVAILABLE_DEFERRED",
                decoderGeneration = activeDecoderGeneration,
                surfaceEpoch = activeSurfaceAttachmentEpoch,
                surfaceStatus = currentSurfaceStatus(),
                recoveryTimeMs = null,
                videoResId = appliedVideoResId ?: latestVideoResId,
                recoveryState = WeatherVideoRecoveryState.DECODER_REVOKED.name,
            )
            return
        }

        recoveryAttemptInFlight = true
        recoveryInProgress = true
        needsCodecRecovery = false
        activeRecoveryTier = WeatherVideoRecoveryTier.REBUILD_PLAYER
        val currentRes = appliedVideoResId ?: latestVideoResId
        val targetSpeed = (recoverySavedSpeed ?: appliedSpeed ?: latestPlaybackSpeed).coerceIn(0.25f, 2.0f)
        val targetPosMs = maxOf(recoverySavedPositionMs, oldPlayer.currentPositionMs)
        val surfaceStatus = currentSurfaceStatus()

        ClimaGraphicsLifecycleDiagnostics.logVideoPlayerRebuild(
            stage = "REQUESTED",
            previousSessionId = oldPlayer.sessionId,
            previousPlayerId = oldPlayer.identityHash,
            reason = reason,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = surfaceStatus,
        )
        ClimaGraphicsLifecycleDiagnostics.logVideoRecoveryAttempt(
            sessionId = oldPlayer.sessionId,
            playerIdentity = oldPlayer.identityHash,
            tier = WeatherVideoRecoveryTier.REBUILD_PLAYER.name,
            attemptNumber = existingPlayerRecoveryAttempts + playerRebuildAttempts,
            maxAttempts = MAX_EXISTING_PLAYER_RECOVERY_ATTEMPTS + MAX_PLAYER_REBUILD_ATTEMPTS,
            reason = reason,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = surfaceStatus,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
            recoveryState = WeatherVideoRecoveryState.REBUILDING_PLAYER.name,
        )

        publishVisualState(
            nextState = WeatherVideoVisualState.VIDEO_RECOVERING,
            nextRecoveryState = WeatherVideoRecoveryState.REBUILDING_PLAYER,
            milestoneEvent = "BACKGROUND_VIDEO_RECOVERY_STARTED",
            reason = "rebuild_player_$reason",
        )

        // 1. Detach and release old ExoPlayer instance completely BEFORE constructing replacement
        clearCallbacksFromPlayer(oldPlayer)
        if (bridge.isPlayerAttached(oldPlayer)) {
            bridge.detachPlayer()
        }
        if (!oldPlayer.isReleased && !videoSurfaceClearedForMap) {
            oldPlayer.clearVideoSurface()
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                sessionId = oldPlayer.sessionId,
                playerIdentity = oldPlayer.identityHash,
                viewIdentity = bridge.viewIdentity,
                reason = "rebuild_player",
            )
        }
        val oldSession = oldPlayer.sessionId
        val oldId = oldPlayer.identityHash
        if (oldPlayer.releaseOnce()) {
            ClimaGraphicsLifecycleDiagnostics.logExoPlayerReleased(
                sessionId = oldSession,
                playerIdentity = oldId,
            )
        }
        ClimaGraphicsLifecycleDiagnostics.logVideoPlayerRebuild(
            stage = "OLD_PLAYER_RELEASED",
            previousSessionId = oldSession,
            previousPlayerId = oldId,
            reason = reason,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = surfaceStatus,
        )

        // 2. Create single clean replacement ExoPlayer instance and bind callbacks before prepare()
        val newPlayer =
            createAndLogPlayerBridge(
                initialResId = currentRes,
                initialPlayWhenReady = latestIsPlaying && latestCanAttachVideoSurfaceNow,
            )
        playerGeneration += 1
        activeDecoderGeneration = 0
        isDecoderCurrentlyAlive = false
        hasValidRenderedFrame = false
        renderedFrameSessionId = ""
        renderedFramePlayerIdentity = 0
        renderedFrameDecoderGeneration = -1
        renderedFrameSurfaceEpoch = -1
        renderedFrameViewIdentity = 0
        hasPendingFatalDecoderError = true
        playerHandle = newPlayer
        bindCallbacksToPlayer(newPlayer)

        ClimaGraphicsLifecycleDiagnostics.logVideoPlayerRebuild(
            stage = "NEW_PLAYER_CREATED",
            previousSessionId = oldSession,
            previousPlayerId = oldId,
            newSessionId = newPlayer.sessionId,
            newPlayerId = newPlayer.identityHash,
            reason = reason,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = currentSurfaceStatus(),
        )

        // 3. Restore MediaItem, mute, infinite loop, speed, position, attach verified surface, prepare()
        val uri = Uri.parse("android.resource://${context.packageName}/$currentRes")
        newPlayer.setVideoResIdHint(currentRes)
        newPlayer.setMediaItem(uri)
        newPlayer.setVolume(0f)
        newPlayer.setRepeatMode(Player.REPEAT_MODE_ONE)
        newPlayer.setPlaybackSpeed(targetSpeed)
        appliedSpeed = targetSpeed
        if (targetPosMs > 0L) {
            newPlayer.seekTo(targetPosMs)
        }

        val postSnapshot = bridge.inspectSurfaceSnapshot()
        isTextureSurfaceAvailable = postSnapshot.isSurfaceValid
        if (latestCanAttachVideoSurfaceNow && isTextureSurfaceAvailable) {
            activeSurfaceAttachmentEpoch += 1
            activeSurfaceViewIdentity = bridge.viewIdentity
            videoSurfaceClearedForMap = false
            bridge.attachPlayer(newPlayer)
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                sessionId = newPlayer.sessionId,
                playerIdentity = newPlayer.identityHash,
                viewIdentity = bridge.viewIdentity,
                viewClass = bridge.viewClassName,
                surfaceInfo = postSnapshot.formatted(),
            )
        }

        ClimaGraphicsLifecycleDiagnostics.logExoRecoveryAction(
            sessionId = newPlayer.sessionId,
            playerIdentity = newPlayer.identityHash,
            action = "REBUILD_PREPARE",
            reason = reason,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
        )
        ClimaGraphicsLifecycleDiagnostics.logVideoPlayerRebuild(
            stage = "COMPLETED",
            previousSessionId = oldSession,
            previousPlayerId = oldId,
            newSessionId = newPlayer.sessionId,
            newPlayerId = newPlayer.identityHash,
            reason = reason,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = currentSurfaceStatus(),
        )

        lastAppliedPlayState = true
        newPlayer.setPlayWhenReady(true)
        firstFrameWatchEpoch += 1
        newPlayer.prepare()
    }

    fun executePendingRecoveryIfEligible() {
        val activePlayer = playerHandle
        if (activePlayer.isReleased || recoveryExhaustedForCurrentMedia || !needsCodecRecovery) return
        if (recoveryAttemptInFlight) return
        if (!latestIsPlaying || !latestCanAttachVideoSurfaceNow || !isLifecycleStarted()) {
            return
        }
        val bridge = surfaceBridgeRef
        if (bridge == null) return

        val snap = bridge.inspectSurfaceSnapshot()
        if (!snap.isSurfaceValid && !isTextureSurfaceAvailable) {
            return
        }
        if (snap.isSurfaceValid && !isTextureSurfaceAvailable) {
            isTextureSurfaceAvailable = true
        }
        if (!isTextureSurfaceAvailable) {
            return
        }

        if (activeRecoveryTier == WeatherVideoRecoveryTier.REBUILD_PLAYER) {
            executeRebuildPlayerAsLastResort(reason = "codec_error_$lastCodecErrorCode")
            return
        }

        recoveryAttemptInFlight = true
        recoveryInProgress = true
        needsCodecRecovery = false
        hasPendingFatalDecoderError = true
        hasValidRenderedFrame = false
        renderedFrameDecoderGeneration = -1
        renderedFrameSurfaceEpoch = -1
        val currentRes = appliedVideoResId ?: latestVideoResId
        val targetSpeed = (recoverySavedSpeed ?: appliedSpeed ?: latestPlaybackSpeed).coerceIn(0.25f, 2.0f)
        val targetPosMs = maxOf(recoverySavedPositionMs, activePlayer.currentPositionMs)
        val tier = activeRecoveryTier
        val attemptNum = existingPlayerRecoveryAttempts

        if (tier == WeatherVideoRecoveryTier.CONTROLLED_RETRY) {
            // Tier 2: Rebind surface cleanly, stop(), re-apply MediaItem + config + position, and prepare()
            activeSurfaceAttachmentEpoch += 1
            if (bridge.isPlayerAttached(activePlayer)) {
                bridge.detachPlayer()
            }
            activePlayer.clearVideoSurface()
            isIntentionalPlayerStop = true
            runCatching {
                if (!activePlayer.isIdle) {
                    activePlayer.stop()
                }
            }
            isIntentionalPlayerStop = false

            val uri = Uri.parse("android.resource://${context.packageName}/$currentRes")
            activePlayer.setVideoResIdHint(currentRes)
            activePlayer.setMediaItem(uri)
            activePlayer.setVolume(0f)
            activePlayer.setRepeatMode(Player.REPEAT_MODE_ONE)
            activePlayer.setPlaybackSpeed(targetSpeed)
            appliedSpeed = targetSpeed
            if (targetPosMs > 0L) {
                activePlayer.seekTo(targetPosMs)
            }
            val postSnap = bridge.inspectSurfaceSnapshot()
            isTextureSurfaceAvailable = postSnap.isSurfaceValid
            if (isTextureSurfaceAvailable && latestCanAttachVideoSurfaceNow) {
                videoSurfaceClearedForMap = false
                activeSurfaceViewIdentity = bridge.viewIdentity
                bridge.attachPlayer(activePlayer)
            }
        } else {
            // Tier 1: Keep the same ExoPlayer instance and MediaItem, verify surface attachment, seek & prepare()
            if (!bridge.isPlayerAttached(activePlayer) && latestCanAttachVideoSurfaceNow && isTextureSurfaceAvailable) {
                videoSurfaceClearedForMap = false
                activeSurfaceViewIdentity = bridge.viewIdentity
                bridge.attachPlayer(activePlayer)
            }
            if (appliedSpeed != targetSpeed || activePlayer.currentPlaybackSpeed != targetSpeed) {
                activePlayer.setPlaybackSpeed(targetSpeed)
                appliedSpeed = targetSpeed
            }
            if (targetPosMs > 0L && activePlayer.currentPositionMs != targetPosMs) {
                activePlayer.seekTo(targetPosMs)
            }
            if (!activePlayer.isIdle) {
                isIntentionalPlayerStop = true
                runCatching { activePlayer.stop() }
                isIntentionalPlayerStop = false
            }
        }

        val actionLabel =
            if (tier == WeatherVideoRecoveryTier.CONTROLLED_RETRY) {
                "CONTROLLED_RETRY"
            } else {
                "RECOVER_PREPARE"
            }
        ClimaGraphicsLifecycleDiagnostics.logExoRecoveryAction(
            sessionId = activePlayer.sessionId,
            playerIdentity = activePlayer.identityHash,
            action = actionLabel,
            reason = "codec_error_$lastCodecErrorCode",
            playerState = activePlayer.playerStateName,
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
        )
        ClimaGraphicsLifecycleDiagnostics.logVideoRecoveryAttempt(
            sessionId = activePlayer.sessionId,
            playerIdentity = activePlayer.identityHash,
            tier = tier.name,
            attemptNumber = attemptNum,
            maxAttempts = MAX_EXISTING_PLAYER_RECOVERY_ATTEMPTS + MAX_PLAYER_REBUILD_ATTEMPTS,
            reason = lastRevocationReason,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = currentSurfaceStatus(),
            videoResId = currentRes,
            positionMs = targetPosMs,
            playbackSpeed = targetSpeed,
            recoveryState = WeatherVideoRecoveryState.RECOVERING.name,
        )
        publishVisualState(
            nextState = WeatherVideoVisualState.VIDEO_RECOVERING,
            nextRecoveryState = WeatherVideoRecoveryState.RECOVERING,
            milestoneEvent = "BACKGROUND_VIDEO_RECOVERY_STARTED",
            reason = "codec_error_$lastCodecErrorCode",
        )
        lastAppliedPlayState = true
        activePlayer.setPlayWhenReady(true)
        firstFrameWatchEpoch += 1
        activePlayer.prepare()
    }

    fun escalateFailureOrTimeout(
        errorCodeName: String,
        revocationReason: String,
        positionMsHint: Long,
        speedHint: Float,
    ) {
        val activePlayer = playerHandle
        if (activePlayer.isReleased) return

        val posToSave = maxOf(positionMsHint, recoverySavedPositionMs, activePlayer.currentPositionMs)
        val speedToSave =
            (appliedSpeed ?: speedHint.takeIf { it > 0f } ?: latestPlaybackSpeed)
                .coerceIn(0.25f, 2.0f)
        recoverySavedPositionMs = posToSave
        recoverySavedSpeed = speedToSave
        lastCodecErrorCode = errorCodeName
        lastRevocationReason = revocationReason
        lastAppliedPlayState = false
        isDecoderCurrentlyAlive = false
        hasPendingFatalDecoderError = true
        hasValidRenderedFrame = false
        renderedFrameSessionId = ""
        renderedFramePlayerIdentity = 0
        renderedFrameDecoderGeneration = -1
        renderedFrameSurfaceEpoch = -1
        recoveryAttemptInFlight = false
        recoveryInProgress = false
        if (recoveryStartElapsedMs == 0L) {
            recoveryStartElapsedMs = monotonicElapsedMs()
        }

        ClimaGraphicsLifecycleDiagnostics.logVideoRevocationDetected(
            sessionId = activePlayer.sessionId,
            playerIdentity = activePlayer.identityHash,
            revocationReason = revocationReason,
            playerStateAtError = activePlayer.playerStateName,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = currentSurfaceStatus(),
            videoResId = appliedVideoResId ?: latestVideoResId,
            positionMs = posToSave,
            recoveryState = WeatherVideoRecoveryState.DECODER_REVOKED.name,
        )

        invalidateCurrentFrame(
            nextState =
                if (!latestCanAttachVideoSurfaceNow) {
                    WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                } else {
                    WeatherVideoVisualState.VIDEO_RECOVERING
                },
            nextRecoveryState = WeatherVideoRecoveryState.DECODER_REVOKED,
            milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
            reason = "codec_error_$errorCodeName",
        )

        if (!latestCanAttachVideoSurfaceNow || !latestIsPlaying || !isLifecycleStarted() || !isTextureSurfaceAvailable) {
            needsCodecRecovery = true
            wasDecoderRevokedWhileDetached = true
            val deferReason =
                when {
                    !latestCanAttachVideoSurfaceNow -> "map_surface_active_or_disposing"
                    !isTextureSurfaceAvailable -> "surface_texture_unavailable"
                    !isLifecycleStarted() -> "activity_not_started"
                    else -> "playback_paused"
                }
            ClimaGraphicsLifecycleDiagnostics.logExoRecoveryAction(
                sessionId = activePlayer.sessionId,
                playerIdentity = activePlayer.identityHash,
                action = "DEFERRED",
                reason = deferReason,
                playerState = activePlayer.playerStateName,
                videoResId = appliedVideoResId ?: latestVideoResId,
                positionMs = posToSave,
                playbackSpeed = speedToSave,
            )
            ClimaGraphicsLifecycleDiagnostics.logVideoRecoveryOutcome(
                sessionId = activePlayer.sessionId,
                playerIdentity = activePlayer.identityHash,
                tier = activeRecoveryTier.name,
                outcome = if (!isTextureSurfaceAvailable) "SURFACE_UNAVAILABLE_DEFERRED" else "DEFERRED",
                decoderGeneration = activeDecoderGeneration,
                surfaceEpoch = activeSurfaceAttachmentEpoch,
                surfaceStatus = currentSurfaceStatus(),
                recoveryTimeMs = null,
                videoResId = appliedVideoResId ?: latestVideoResId,
                recoveryState = WeatherVideoRecoveryState.DECODER_REVOKED.name,
            )
            return
        }

        when {
            existingPlayerRecoveryAttempts < MAX_EXISTING_PLAYER_RECOVERY_ATTEMPTS -> {
                existingPlayerRecoveryAttempts += 1
                activeRecoveryTier =
                    if (existingPlayerRecoveryAttempts == 1) {
                        WeatherVideoRecoveryTier.RECOVER_EXISTING
                    } else {
                        WeatherVideoRecoveryTier.CONTROLLED_RETRY
                    }
                needsCodecRecovery = true
                codecRecoveryEpoch += 1
            }

            playerRebuildAttempts < MAX_PLAYER_REBUILD_ATTEMPTS -> {
                playerRebuildAttempts += 1
                activeRecoveryTier = WeatherVideoRecoveryTier.REBUILD_PLAYER
                needsCodecRecovery = true
                codecRecoveryEpoch += 1
            }

            else -> {
                needsCodecRecovery = false
                recoveryExhaustedForCurrentMedia = true
                val elapsed = (monotonicElapsedMs() - recoveryStartElapsedMs).coerceAtLeast(0L)
                ClimaGraphicsLifecycleDiagnostics.logVideoRecoveryOutcome(
                    sessionId = activePlayer.sessionId,
                    playerIdentity = activePlayer.identityHash,
                    tier = activeRecoveryTier.name,
                    outcome = "FAILED_EXHAUSTED",
                    decoderGeneration = activeDecoderGeneration,
                    surfaceEpoch = activeSurfaceAttachmentEpoch,
                    surfaceStatus = currentSurfaceStatus(),
                    recoveryTimeMs = elapsed,
                    videoResId = appliedVideoResId ?: latestVideoResId,
                    recoveryState = WeatherVideoRecoveryState.FAILED.name,
                )
                publishVisualState(
                    nextState = WeatherVideoVisualState.VIDEO_NOT_READY,
                    nextRecoveryState = WeatherVideoRecoveryState.FAILED,
                    milestoneEvent = "BACKGROUND_VIDEO_RECOVERY_FAILED",
                    reason = "exhausted_after_${existingPlayerRecoveryAttempts}_existing_${playerRebuildAttempts}_rebuild_$errorCodeName",
                )
            }
        }
    }

    fun resumePlayerIfEligible(requireStartedLifecycle: Boolean = true) {
        val activePlayer = playerHandle
        if (activePlayer.isReleased) return
        val canPlayInCurrentLifecycle = !requireStartedLifecycle || isLifecycleStarted()
        if (!latestIsPlaying || !latestCanAttachVideoSurfaceNow) {
            pausePlayerIdempotently()
            return
        }
        if (!canPlayInCurrentLifecycle || recoveryExhaustedForCurrentMedia) {
            return
        }
        val bridge = surfaceBridgeRef
        if (bridge != null && bridge.isPlayerAttached(activePlayer)) {
            // Re-inspect surface snapshot before attempting playback/recovery
            val snap = bridge.inspectSurfaceSnapshot()
            if (snap.isSurfaceValid && !isTextureSurfaceAvailable) {
                isTextureSurfaceAvailable = true
            }
            if (!isTextureSurfaceAvailable) {
                return
            }

            if (wasDecoderRevokedWhileDetached && !isDecoderCurrentlyAlive && !needsCodecRecovery) {
                // MediaCodec was silently reclaimed while detached (e.g. map open / backgrounded): promote to recovery
                wasDecoderRevokedWhileDetached = false
                if (existingPlayerRecoveryAttempts == 0) {
                    existingPlayerRecoveryAttempts = 1
                    activeRecoveryTier = WeatherVideoRecoveryTier.RECOVER_EXISTING
                }
                needsCodecRecovery = true
            }

            if (needsCodecRecovery) {
                executePendingRecoveryIfEligible()
                return
            }

            if (lastAppliedPlayState != true) {
                lastAppliedPlayState = true
                activePlayer.setPlayWhenReady(true)
                if (activePlayer.isIdle) {
                    hasValidRenderedFrame = false
                    renderedFrameDecoderGeneration = -1
                    firstFrameWatchEpoch += 1
                    publishVisualState(
                        nextState = WeatherVideoVisualState.VIDEO_PREPARING,
                        nextRecoveryState = WeatherVideoRecoveryState.PREPARING,
                        milestoneEvent = "BACKGROUND_VIDEO_WAITING_FIRST_FRAME",
                        reason = "prepare_from_idle",
                    )
                    activePlayer.prepare()
                } else {
                    if (!hasValidRenderedFrame) {
                        firstFrameWatchEpoch += 1
                        publishVisualState(
                            nextState = WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME,
                            nextRecoveryState = WeatherVideoRecoveryState.WAITING_FIRST_FRAME,
                            milestoneEvent = "BACKGROUND_VIDEO_WAITING_FIRST_FRAME",
                            reason = "play_awaiting_first_frame",
                        )
                    }
                    activePlayer.play()
                }
            }
        }
    }

    fun detachVideoOutputForMapExclusivity(bridge: WeatherVideoSurfaceBridge, reason: String) {
        val activePlayer = playerHandle
        pausePlayerIdempotently()
        val wasAttachedToBridge = bridge.isPlayerAttached(activePlayer)
        if (!wasAttachedToBridge && videoSurfaceClearedForMap) {
            if (visualState != WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED) {
                invalidateCurrentFrame(
                    nextState = WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED,
                    nextRecoveryState = if (recoveryExhaustedForCurrentMedia) WeatherVideoRecoveryState.FAILED else WeatherVideoRecoveryState.IDLE,
                    milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                    reason = reason,
                )
            }
            return
        }
        activeSurfaceAttachmentEpoch += 1
        activeSurfaceViewIdentity = 0
        GraphicsSurfaceCoordinator.onVideoOutputDetachForMap(
            sessionId = activePlayer.sessionId,
            playerIdentity = activePlayer.identityHash,
            viewIdentity = bridge.viewIdentity,
            reason = reason,
        )
        if (wasAttachedToBridge) {
            bridge.detachPlayer()
        }
        if (!videoSurfaceClearedForMap && !activePlayer.isReleased) {
            activePlayer.clearVideoSurface()
            videoSurfaceClearedForMap = true
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                sessionId = activePlayer.sessionId,
                playerIdentity = activePlayer.identityHash,
                viewIdentity = bridge.viewIdentity,
                reason = reason,
            )
        }
        pendingReattachRequestLogged = false
        GraphicsSurfaceCoordinator.onVideoOutputDetached(
            sessionId = activePlayer.sessionId,
            playerIdentity = activePlayer.identityHash,
            viewIdentity = bridge.viewIdentity,
            reason = reason,
        )
        invalidateCurrentFrame(
            nextState = WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED,
            nextRecoveryState =
                when {
                    recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                    needsCodecRecovery || hasPendingFatalDecoderError -> WeatherVideoRecoveryState.DECODER_REVOKED
                    else -> WeatherVideoRecoveryState.IDLE
                },
            milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
            reason = reason,
        )
    }

    fun attachVideoOutputIfAllowed(bridge: WeatherVideoSurfaceBridge, reason: String) {
        val activePlayer = playerHandle
        if (activePlayer.isReleased) return
        if (!allowVideoSurfaceOutput) {
            detachVideoOutputForMapExclusivity(bridge, reason = "map_tab_active")
            return
        }
        if (trackMapExclusivity.value && !coordinationSnapshot.canAttachVideoOutput) {
            if (bridge.isPlayerAttached(activePlayer) || !videoSurfaceClearedForMap) {
                detachVideoOutputForMapExclusivity(bridge, reason = "map_surface_busy")
            }
            if (!pendingReattachRequestLogged) {
                pendingReattachRequestLogged = true
                GraphicsSurfaceCoordinator.onVideoOutputReattachRequest(
                    sessionId = activePlayer.sessionId,
                    playerIdentity = activePlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "${reason}_awaiting_map_teardown",
                )
            }
            return
        }

        if (!bridge.isPlayerAttached(activePlayer)) {
            if (!pendingReattachRequestLogged && videoSurfaceClearedForMap) {
                GraphicsSurfaceCoordinator.onVideoOutputReattachRequest(
                    sessionId = activePlayer.sessionId,
                    playerIdentity = activePlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = reason,
                )
            }
            val snapBeforeAttach = bridge.inspectSurfaceSnapshot()
            val wasDetachedForMap = videoSurfaceClearedForMap
            pendingReattachRequestLogged = false
            videoSurfaceClearedForMap = false
            isTextureSurfaceAvailable = snapBeforeAttach.isSurfaceValid
            activeSurfaceAttachmentEpoch += 1
            activeSurfaceViewIdentity = bridge.viewIdentity
            if (wasDetachedForMap) {
                hasValidRenderedFrame = false
                renderedFrameSessionId = ""
                renderedFramePlayerIdentity = 0
                renderedFrameDecoderGeneration = -1
                renderedFrameSurfaceEpoch = -1
                renderedFrameViewIdentity = 0
                activePlayer.notifySurfaceReattachedForNewFrame()
            } else if (hasValidRenderedFrame) {
                renderedFrameSurfaceEpoch = activeSurfaceAttachmentEpoch
                renderedFrameViewIdentity = bridge.viewIdentity
            }
            bridge.attachPlayer(activePlayer)
            val surfaceFormatted = bridge.inspectSurfaceSnapshot().formatted()
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                sessionId = activePlayer.sessionId,
                playerIdentity = activePlayer.identityHash,
                viewIdentity = bridge.viewIdentity,
                viewClass = bridge.viewClassName,
                surfaceInfo = surfaceFormatted,
            )
            GraphicsSurfaceCoordinator.onVideoOutputReattached(
                sessionId = activePlayer.sessionId,
                playerIdentity = activePlayer.identityHash,
                viewIdentity = bridge.viewIdentity,
                surfaceInfo = surfaceFormatted,
            )
            val nextVisualState =
                when {
                    recoveryExhaustedForCurrentMedia -> WeatherVideoVisualState.VIDEO_NOT_READY
                    needsCodecRecovery || recoveryInProgress -> WeatherVideoVisualState.VIDEO_RECOVERING
                    hasValidRenderedFrame && isDecoderCurrentlyAlive && isTextureSurfaceAvailable ->
                        WeatherVideoVisualState.VIDEO_FRAME_READY
                    else -> WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                }
            val nextRecoveryState =
                when {
                    recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                    recoveryInProgress ->
                        if (activeRecoveryTier == WeatherVideoRecoveryTier.REBUILD_PLAYER) {
                            WeatherVideoRecoveryState.REBUILDING_PLAYER
                        } else {
                            WeatherVideoRecoveryState.RECOVERING
                        }
                    needsCodecRecovery || wasDecoderRevokedWhileDetached -> WeatherVideoRecoveryState.DECODER_REVOKED
                    nextVisualState == WeatherVideoVisualState.VIDEO_FRAME_READY -> WeatherVideoRecoveryState.PLAYING
                    else -> WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                }
            if (nextVisualState == WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME) {
                firstFrameWatchEpoch += 1
            }
            publishVisualState(
                nextState = nextVisualState,
                nextRecoveryState = nextRecoveryState,
                milestoneEvent =
                    when (nextVisualState) {
                        WeatherVideoVisualState.VIDEO_FRAME_READY -> "BACKGROUND_VIDEO_FRAME_READY"
                        WeatherVideoVisualState.VIDEO_NOT_READY -> null
                        else -> "BACKGROUND_VIDEO_WAITING_FIRST_FRAME"
                    },
                reason = "surface_reattached_$reason",
            )
        }
        resumePlayerIfEligible(requireStartedLifecycle = true)
    }

    bindCallbacksToPlayer = { boundPlayer ->
        boundPlayer.setDecoderLifecycleListener { initialized, decoderGen, reasonLabel ->
            if (boundPlayer !== playerHandle || boundPlayer.isReleased) return@setDecoderLifecycleListener
            activeDecoderGeneration = maxOf(activeDecoderGeneration, decoderGen)
            if (!initialized) {
                isDecoderCurrentlyAlive = false
                val isCodecError = reasonLabel.startsWith("codec_error:")
                if (isCodecError) {
                    hasPendingFatalDecoderError = true
                    if (recoveryStartElapsedMs == 0L) {
                        recoveryStartElapsedMs = monotonicElapsedMs()
                    }
                    recoverySavedPositionMs = maxOf(recoverySavedPositionMs, boundPlayer.currentPositionMs)
                    recoverySavedSpeed = (appliedSpeed ?: latestPlaybackSpeed).coerceIn(0.25f, 2.0f)
                    lastRevocationReason = reasonLabel
                } else if (!isIntentionalPlayerStop && (!latestCanAttachVideoSurfaceNow || !isTextureSurfaceAvailable || !isLifecycleStarted())) {
                    // Track silent decoder reclaim while paused / map open so resume triggers clean re-prepare
                    wasDecoderRevokedWhileDetached = true
                    recoverySavedPositionMs = maxOf(recoverySavedPositionMs, boundPlayer.currentPositionMs)
                }

                if (isCodecError || hasValidRenderedFrame || visualState == WeatherVideoVisualState.VIDEO_FRAME_READY) {
                    val nextRecovery =
                        when {
                            recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                            isCodecError || needsCodecRecovery || recoveryInProgress -> WeatherVideoRecoveryState.DECODER_REVOKED
                            else -> WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                        }
                    if (!isCodecError && !isIntentionalPlayerStop && latestCanAttachVideoSurfaceNow && isLifecycleStarted()) {
                        firstFrameWatchEpoch += 1
                    }
                    invalidateCurrentFrame(
                        nextState =
                            when {
                                !latestCanAttachVideoSurfaceNow -> WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                                isCodecError || needsCodecRecovery || recoveryInProgress -> WeatherVideoVisualState.VIDEO_RECOVERING
                                else -> WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                            },
                        nextRecoveryState = nextRecovery,
                        milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                        reason = if (isCodecError) reasonLabel else "decoder_released",
                    )
                }
            } else {
                isDecoderCurrentlyAlive = true
                val hadPreviousReadyFrame = hasValidRenderedFrame || visualState == WeatherVideoVisualState.VIDEO_FRAME_READY
                hasValidRenderedFrame = false
                renderedFrameSessionId = ""
                renderedFramePlayerIdentity = 0
                renderedFrameDecoderGeneration = -1
                renderedFrameSurfaceEpoch = -1
                val nextRecovery =
                    when {
                        recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                        activeRecoveryTier == WeatherVideoRecoveryTier.REBUILD_PLAYER && recoveryInProgress ->
                            WeatherVideoRecoveryState.REBUILDING_PLAYER
                        recoveryInProgress -> WeatherVideoRecoveryState.RECOVERING
                        else -> WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                    }
                if (hadPreviousReadyFrame) {
                    publishVisualState(
                        nextState =
                            if (!latestCanAttachVideoSurfaceNow) {
                                WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                            } else if (recoveryInProgress) {
                                WeatherVideoVisualState.VIDEO_RECOVERING
                            } else {
                                WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                            },
                        nextRecoveryState = nextRecovery,
                        milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                        reason = "new_decoder_generation_$activeDecoderGeneration",
                    )
                } else if (latestCanAttachVideoSurfaceNow && !recoveryInProgress) {
                    publishVisualState(
                        nextState = WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME,
                        nextRecoveryState = WeatherVideoRecoveryState.WAITING_FIRST_FRAME,
                        milestoneEvent = "BACKGROUND_VIDEO_WAITING_FIRST_FRAME",
                        reason = "decoder_initialized_gen_$activeDecoderGeneration",
                    )
                }
            }
        }

        boundPlayer.setPlaybackStateListener { stateName, _ ->
            if (boundPlayer !== playerHandle || boundPlayer.isReleased) return@setPlaybackStateListener
            val currentRes = appliedVideoResId ?: latestVideoResId
            when (stateName) {
                "IDLE" -> {
                    isDecoderCurrentlyAlive = false
                    if (hasValidRenderedFrame || visualState == WeatherVideoVisualState.VIDEO_FRAME_READY) {
                        invalidateCurrentFrame(
                            nextState =
                                if (needsCodecRecovery || recoveryInProgress) {
                                    WeatherVideoVisualState.VIDEO_RECOVERING
                                } else {
                                    WeatherVideoVisualState.VIDEO_NOT_READY
                                },
                            nextRecoveryState =
                                when {
                                    recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                                    needsCodecRecovery || recoveryInProgress -> WeatherVideoRecoveryState.RECOVERING
                                    else -> WeatherVideoRecoveryState.IDLE
                                },
                            milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                            reason = "player_state_idle",
                        )
                    }
                }
                "BUFFERING" -> {
                    val canPreserveExistingValidFrameDuringTransientBuffering =
                        isCurrentRenderedFrameStrictlyValid(currentRes)
                    if (!canPreserveExistingValidFrameDuringTransientBuffering) {
                        if (hasValidRenderedFrame || visualState == WeatherVideoVisualState.VIDEO_FRAME_READY) {
                            invalidateCurrentFrame(
                                nextState =
                                    when {
                                        !latestCanAttachVideoSurfaceNow -> WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                                        needsCodecRecovery || recoveryInProgress -> WeatherVideoVisualState.VIDEO_RECOVERING
                                        else -> WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                                    },
                                nextRecoveryState =
                                    when {
                                        recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                                        needsCodecRecovery || recoveryInProgress -> WeatherVideoRecoveryState.RECOVERING
                                        else -> WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                                    },
                                milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                                reason = "buffering_without_valid_frame",
                            )
                        } else if (latestCanAttachVideoSurfaceNow && !recoveryInProgress) {
                            publishVisualState(
                                nextState = WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME,
                                nextRecoveryState = WeatherVideoRecoveryState.WAITING_FIRST_FRAME,
                                milestoneEvent = null,
                                reason = "player_buffering_no_frame",
                            )
                        }
                    }
                }
                "READY" -> {
                    if (!isCurrentRenderedFrameStrictlyValid(currentRes) &&
                        visualState == WeatherVideoVisualState.VIDEO_FRAME_READY
                    ) {
                        invalidateCurrentFrame(
                            nextState = WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME,
                            nextRecoveryState = WeatherVideoRecoveryState.WAITING_FIRST_FRAME,
                            milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                            reason = "ready_without_valid_frame_token",
                        )
                    }
                }
            }
        }

        boundPlayer.setSurfaceSizeListener { width, height ->
            if (boundPlayer !== playerHandle || boundPlayer.isReleased) return@setSurfaceSizeListener
            val bridge = surfaceBridgeRef ?: return@setSurfaceSizeListener
            val snapshot = bridge.inspectSurfaceSnapshot()
            if (width <= 0 || height <= 0) {
                val lostSurface = !snapshot.isSurfaceValid || (width == 0 && height == 0)
                if (lostSurface && isTextureSurfaceAvailable) {
                    isTextureSurfaceAvailable = false
                    activeSurfaceAttachmentEpoch += 1
                    boundPlayer.notifySurfaceReattachedForNewFrame()
                    invalidateCurrentFrame(
                        nextState =
                            if (!latestCanAttachVideoSurfaceNow) {
                                WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                            } else if (recoveryInProgress || needsCodecRecovery) {
                                WeatherVideoVisualState.VIDEO_RECOVERING
                            } else {
                                WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                            },
                        nextRecoveryState =
                            when {
                                recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                                recoveryInProgress || needsCodecRecovery -> WeatherVideoRecoveryState.RECOVERING
                                else -> WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                            },
                        milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                        reason = "surface_size_zero_unavailable",
                    )
                }
            } else if (snapshot.isSurfaceValid && !isTextureSurfaceAvailable) {
                isTextureSurfaceAvailable = true
                activeSurfaceAttachmentEpoch += 1
                boundPlayer.notifySurfaceReattachedForNewFrame()
                if (needsCodecRecovery || wasDecoderRevokedWhileDetached) {
                    codecRecoveryEpoch += 1
                } else {
                    firstFrameWatchEpoch += 1
                    resumePlayerIfEligible()
                }
            }
        }

        boundPlayer.setFirstFrameTokenListener { token ->
            if (boundPlayer !== playerHandle || boundPlayer.isReleased || !latestCanAttachVideoSurfaceNow) {
                return@setFirstFrameTokenListener
            }
            val currentRes = appliedVideoResId ?: latestVideoResId
            val expectedGen = maxOf(activeDecoderGeneration, boundPlayer.decoderGeneration)
            val bridge = surfaceBridgeRef
            val liveSnapshot = bridge?.inspectSurfaceSnapshot()
            // If real TextureView has created its SurfaceTexture by first-frame render time, keep availability synced
            if (bridge != null && liveSnapshot != null && liveSnapshot.isSurfaceValid && bridge is RealPlayerViewSurfaceBridge && bridge.view.isAttachedToWindow && !isTextureSurfaceAvailable) {
                isTextureSurfaceAvailable = true
            }
            val attachedNow =
                bridge != null &&
                    bridge.isPlayerAttached(boundPlayer) &&
                    !videoSurfaceClearedForMap &&
                    isTextureSurfaceAvailable &&
                    (liveSnapshot == null || liveSnapshot.isSurfaceValid)
            val initialPreFactoryPrepare =
                bridge == null &&
                    activeSurfaceAttachmentEpoch == 0 &&
                    !videoSurfaceClearedForMap &&
                    isTextureSurfaceAvailable

            val matchesSessionAndPlayer =
                token.sessionId == boundPlayer.sessionId &&
                    token.playerIdentity == boundPlayer.identityHash
            val matchesMediaItem = token.videoResId == currentRes
            val matchesDecoderGeneration =
                token.decoderGeneration > 0 &&
                    token.decoderGeneration == expectedGen &&
                    token.decoderAlive &&
                    (isDecoderCurrentlyAlive || boundPlayer.isDecoderAlive)
            val matchesSurface =
                when {
                    initialPreFactoryPrepare -> true
                    !attachedNow -> false
                    token.surfaceViewIdentity != 0 && token.surfaceViewIdentity != bridge.viewIdentity -> false
                    token.surfaceEpoch != boundPlayer.surfaceEpoch -> false
                    else -> true
                }

            if (matchesSessionAndPlayer && matchesMediaItem && matchesDecoderGeneration && matchesSurface) {
                activeDecoderGeneration = expectedGen
                isDecoderCurrentlyAlive = true
                val wasRecovering =
                    recoveryInProgress ||
                        recoveryAttemptInFlight ||
                        activeRecoveryTier != WeatherVideoRecoveryTier.NONE
                val completedTier = activeRecoveryTier
                val alreadyReadyForSameToken =
                    hasValidRenderedFrame &&
                        visualState == WeatherVideoVisualState.VIDEO_FRAME_READY &&
                        recoveryState == WeatherVideoRecoveryState.PLAYING &&
                        renderedFrameSessionId == boundPlayer.sessionId &&
                        renderedFramePlayerIdentity == boundPlayer.identityHash &&
                        renderedFrameVideoResId == currentRes &&
                        renderedFrameDecoderGeneration == expectedGen &&
                        renderedFrameSurfaceEpoch == activeSurfaceAttachmentEpoch &&
                        !wasRecovering

                existingPlayerRecoveryAttempts = 0
                playerRebuildAttempts = 0
                activeRecoveryTier = WeatherVideoRecoveryTier.NONE
                recoveryInProgress = false
                recoveryAttemptInFlight = false
                needsCodecRecovery = false
                wasDecoderRevokedWhileDetached = false
                hasPendingFatalDecoderError = false
                recoveryExhaustedForCurrentMedia = false
                hasValidRenderedFrame = true
                renderedFrameSessionId = boundPlayer.sessionId
                renderedFramePlayerIdentity = boundPlayer.identityHash
                renderedFrameDecoderGeneration = expectedGen
                renderedFrameVideoResId = currentRes
                renderedFrameSurfaceEpoch = activeSurfaceAttachmentEpoch
                renderedFrameViewIdentity = bridge?.viewIdentity ?: 0

                if (!alreadyReadyForSameToken) {
                    if (wasRecovering) {
                        val elapsed =
                            if (recoveryStartElapsedMs > 0L) {
                                (monotonicElapsedMs() - recoveryStartElapsedMs).coerceAtLeast(0L)
                            } else {
                                0L
                            }
                        recoveryStartElapsedMs = 0L
                        ClimaGraphicsLifecycleDiagnostics.logVideoRecoveryOutcome(
                            sessionId = boundPlayer.sessionId,
                            playerIdentity = boundPlayer.identityHash,
                            tier = completedTier.name,
                            outcome = "FIRST_FRAME_CONFIRMED",
                            decoderGeneration = expectedGen,
                            surfaceEpoch = activeSurfaceAttachmentEpoch,
                            surfaceStatus = currentSurfaceStatus(),
                            recoveryTimeMs = elapsed,
                            videoResId = currentRes,
                            recoveryState = WeatherVideoRecoveryState.PLAYING.name,
                        )
                        publishVisualState(
                            nextState = WeatherVideoVisualState.VIDEO_FRAME_READY,
                            nextRecoveryState = WeatherVideoRecoveryState.PLAYING,
                            milestoneEvent = "BACKGROUND_VIDEO_RECOVERY_FINISHED",
                            reason = "first_frame_after_recovery_gen_$expectedGen",
                        )
                    } else {
                        recoveryStartElapsedMs = 0L
                    }
                    publishVisualState(
                        nextState = WeatherVideoVisualState.VIDEO_FRAME_READY,
                        nextRecoveryState = WeatherVideoRecoveryState.PLAYING,
                        milestoneEvent = "BACKGROUND_VIDEO_FRAME_READY",
                        reason = "rendered_first_frame_gen_$expectedGen",
                    )
                }
                latestOnFirstFrame()
            } else {
                val rejectReason =
                    when {
                        !matchesSessionAndPlayer -> "stale_session_or_player"
                        !matchesMediaItem -> "stale_media_item_${token.videoResId}_expected_$currentRes"
                        !matchesDecoderGeneration -> "stale_decoder_gen_${token.decoderGeneration}_expected_$expectedGen"
                        else -> "stale_or_detached_surface_epoch_${token.surfaceEpoch}_expected_${boundPlayer.surfaceEpoch}"
                    }
                ClimaGraphicsLifecycleDiagnostics.logBackgroundVisualMilestone(
                    eventName = "BACKGROUND_VIDEO_STALE_FRAME_REJECTED",
                    sessionId = boundPlayer.sessionId,
                    playerIdentity = boundPlayer.identityHash,
                    videoResId = currentRes,
                    playerState = boundPlayer.playerStateName,
                    decoderGeneration = activeDecoderGeneration,
                    videoAlpha = if (visualState == WeatherVideoVisualState.VIDEO_FRAME_READY && hasValidRenderedFrame) 1f else 0f,
                    frameReady = hasValidRenderedFrame,
                    visualState = visualState.name,
                    reason = rejectReason,
                    recoveryState = recoveryState.name,
                    surfaceEpoch = activeSurfaceAttachmentEpoch,
                )
            }
        }

        boundPlayer.setFirstFrameListener {
            if (boundPlayer !== playerHandle || boundPlayer.isReleased) return@setFirstFrameListener
            val currentRes = appliedVideoResId ?: latestVideoResId
            val token =
                WeatherVideoFrameToken(
                    sessionId = boundPlayer.sessionId,
                    playerIdentity = boundPlayer.identityHash,
                    videoResId = currentRes,
                    decoderGeneration = maxOf(activeDecoderGeneration, boundPlayer.decoderGeneration),
                    surfaceEpoch = boundPlayer.surfaceEpoch,
                    surfaceViewIdentity = surfaceBridgeRef?.viewIdentity ?: 0,
                    decoderAlive = isDecoderCurrentlyAlive || boundPlayer.isDecoderAlive,
                )
            val currentBridge = surfaceBridgeRef
            val liveSnapshot = currentBridge?.inspectSurfaceSnapshot()
            val expectedGen = token.decoderGeneration
            val attachedNow =
                currentBridge != null &&
                    currentBridge.isPlayerAttached(boundPlayer) &&
                    !videoSurfaceClearedForMap &&
                    isTextureSurfaceAvailable &&
                    (liveSnapshot == null || liveSnapshot.isSurfaceValid)
            if (latestCanAttachVideoSurfaceNow && expectedGen > 0 && token.decoderAlive && (attachedNow || (currentBridge == null && activeSurfaceAttachmentEpoch == 0))) {
                activeDecoderGeneration = expectedGen
                isDecoderCurrentlyAlive = true
                existingPlayerRecoveryAttempts = 0
                playerRebuildAttempts = 0
                activeRecoveryTier = WeatherVideoRecoveryTier.NONE
                recoveryInProgress = false
                recoveryAttemptInFlight = false
                needsCodecRecovery = false
                wasDecoderRevokedWhileDetached = false
                hasPendingFatalDecoderError = false
                recoveryExhaustedForCurrentMedia = false
                hasValidRenderedFrame = true
                renderedFrameSessionId = boundPlayer.sessionId
                renderedFramePlayerIdentity = boundPlayer.identityHash
                renderedFrameDecoderGeneration = expectedGen
                renderedFrameVideoResId = currentRes
                renderedFrameSurfaceEpoch = activeSurfaceAttachmentEpoch
                renderedFrameViewIdentity = currentBridge?.viewIdentity ?: 0
                publishVisualState(
                    nextState = WeatherVideoVisualState.VIDEO_FRAME_READY,
                    nextRecoveryState = WeatherVideoRecoveryState.PLAYING,
                    milestoneEvent = "BACKGROUND_VIDEO_FRAME_READY",
                    reason = "rendered_first_frame_legacy_gen_$expectedGen",
                )
                latestOnFirstFrame()
            }
        }

        boundPlayer.setCodecErrorListener { diagnostic ->
            if (boundPlayer !== playerHandle || boundPlayer.isReleased) return@setCodecErrorListener
            val revocationLabel =
                if (diagnostic.isDeadObjectOrReclaimed) {
                    "codec_reclaimed_${diagnostic.errorCodeName}"
                } else {
                    "codec_error_${diagnostic.errorCodeName}"
                }
            escalateFailureOrTimeout(
                errorCodeName = diagnostic.errorCodeName,
                revocationReason = revocationLabel,
                positionMsHint = diagnostic.positionMsAtError,
                speedHint = diagnostic.playbackSpeedAtError,
            )
        }
    }

    // Bind callbacks immediately before any LaunchedEffect runs
    bindCallbacksToPlayer(playerHandle)

    DisposableEffect(context) {
        bindCallbacksToPlayer(playerHandle)
        onDispose {
            val activePlayer = playerHandle
            clearCallbacksFromPlayer(activePlayer)
            detachWindowObserverRef?.invoke()
            detachWindowObserverRef = null
            surfaceBridgeRef?.let { bridge ->
                val wasAttached = bridge.isPlayerAttached(activePlayer)
                if (wasAttached) {
                    bridge.detachPlayer()
                }
                if (!videoSurfaceClearedForMap) {
                    activePlayer.clearVideoSurface()
                    videoSurfaceClearedForMap = true
                    ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                        sessionId = activePlayer.sessionId,
                        playerIdentity = activePlayer.identityHash,
                        viewIdentity = bridge.viewIdentity,
                        reason = "onDispose",
                    )
                }
                GraphicsSurfaceCoordinator.onVideoOutputDetached(
                    sessionId = activePlayer.sessionId,
                    playerIdentity = activePlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "onDispose",
                )
            } ?: run {
                if (!videoSurfaceClearedForMap) {
                    activePlayer.clearVideoSurface()
                    videoSurfaceClearedForMap = true
                }
            }
            surfaceBridgeRef = null
            if (activePlayer.releaseOnce()) {
                ClimaGraphicsLifecycleDiagnostics.logExoPlayerReleased(
                    sessionId = activePlayer.sessionId,
                    playerIdentity = activePlayer.identityHash,
                )
            }
        }
    }

    LaunchedEffect(context, videoResId) {
        val activePlayer = playerHandle
        if (activePlayer.isReleased || appliedVideoResId == videoResId) return@LaunchedEffect
        val uri = Uri.parse("android.resource://${context.packageName}/$videoResId")
        val isMediaChange = appliedVideoResId != null
        val playNow = latestIsPlaying && latestCanAttachVideoSurfaceNow
        val hadVisibleFrameBeforeSwitch =
            hasValidRenderedFrame || visualState == WeatherVideoVisualState.VIDEO_FRAME_READY

        needsCodecRecovery = false
        wasDecoderRevokedWhileDetached = false
        recoveryInProgress = false
        recoveryAttemptInFlight = false
        hasPendingFatalDecoderError = false
        recoveryExhaustedForCurrentMedia = false
        recoverySavedPositionMs = 0L
        existingPlayerRecoveryAttempts = 0
        playerRebuildAttempts = 0
        activeRecoveryTier = WeatherVideoRecoveryTier.NONE
        recoveryStartElapsedMs = 0L
        hasValidRenderedFrame = false
        renderedFrameSessionId = ""
        renderedFramePlayerIdentity = 0
        renderedFrameDecoderGeneration = -1
        renderedFrameVideoResId = null
        renderedFrameSurfaceEpoch = -1
        appliedVideoResId = videoResId

        activePlayer.setVideoResIdHint(videoResId)
        if (isMediaChange && hadVisibleFrameBeforeSwitch) {
            publishVisualState(
                nextState =
                    if (latestCanAttachVideoSurfaceNow) {
                        WeatherVideoVisualState.VIDEO_PREPARING
                    } else {
                        WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                    },
                nextRecoveryState = WeatherVideoRecoveryState.PREPARING,
                milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                reason = "media_item_switching",
            )
        }
        publishVisualState(
            nextState =
                if (latestCanAttachVideoSurfaceNow) {
                    WeatherVideoVisualState.VIDEO_PREPARING
                } else {
                    WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                },
            nextRecoveryState = WeatherVideoRecoveryState.PREPARING,
            milestoneEvent = if (isMediaChange) "BACKGROUND_MEDIA_ITEM_CHANGED" else "BACKGROUND_VIDEO_WAITING_FIRST_FRAME",
            reason = if (isMediaChange) "media_item_changed" else "initial_media_prepare",
        )
        activePlayer.setMediaItem(uri)
        activePlayer.setVolume(0f)
        activePlayer.setRepeatMode(Player.REPEAT_MODE_ONE)
        activePlayer.setPlayWhenReady(playNow)
        firstFrameWatchEpoch += 1
        activePlayer.prepare()
        lastAppliedPlayState = playNow
    }

    LaunchedEffect(context, playbackSpeed, playerHandle) {
        val activePlayer = playerHandle
        if (activePlayer.isReleased) return@LaunchedEffect
        val clamped = playbackSpeed.coerceIn(0.25f, 2.0f)
        if (appliedSpeed != clamped || activePlayer.currentPlaybackSpeed != clamped) {
            activePlayer.setPlaybackSpeed(clamped)
            appliedSpeed = clamped
            recoverySavedSpeed = clamped
        }
    }

    LaunchedEffect(
        context,
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

    LaunchedEffect(
        context,
        isPlaying,
        canAttachVideoSurfaceNow,
        isTextureSurfaceAvailable,
        codecRecoveryEpoch,
    ) {
        if (playerHandle.isReleased) return@LaunchedEffect
        resumePlayerIfEligible()
    }

    // First-Frame Watchdog Timeout:
    // Guarantees we NEVER stall forever in PREPARING / WAITING_FIRST_FRAME / DECODER_REVOKED /
    // RECOVERING / REBUILDING_PLAYER if MediaCodec was silently revoked or a surface stopped
    // delivering frames without emitting onPlayerError.
    val isExpectingFirstFrameNow =
        appliedVideoResId != null &&
            !hasValidRenderedFrame &&
            !recoveryExhaustedForCurrentMedia &&
            isPlaying &&
            canAttachVideoSurfaceNow &&
            isTextureSurfaceAvailable &&
            isLifecycleStarted() &&
            surfaceBridgeRef != null

    LaunchedEffect(
        isExpectingFirstFrameNow,
        firstFrameWatchEpoch,
        playerGeneration,
        existingPlayerRecoveryAttempts,
        playerRebuildAttempts,
        recoveryState,
        firstFrameTimeoutMs,
    ) {
        if (!isExpectingFirstFrameNow || firstFrameTimeoutMs <= 0L) return@LaunchedEffect
        delay(firstFrameTimeoutMs)
        val activePlayer = playerHandle
        if (activePlayer.isReleased || hasValidRenderedFrame || recoveryExhaustedForCurrentMedia) return@LaunchedEffect
        if (!latestIsPlaying || !latestCanAttachVideoSurfaceNow || !isTextureSurfaceAvailable || !isLifecycleStarted()) {
            return@LaunchedEffect
        }
        val nextTierLabel =
            when {
                existingPlayerRecoveryAttempts < MAX_EXISTING_PLAYER_RECOVERY_ATTEMPTS ->
                    if (existingPlayerRecoveryAttempts == 0) {
                        WeatherVideoRecoveryTier.RECOVER_EXISTING.name
                    } else {
                        WeatherVideoRecoveryTier.CONTROLLED_RETRY.name
                    }
                playerRebuildAttempts < MAX_PLAYER_REBUILD_ATTEMPTS ->
                    WeatherVideoRecoveryTier.REBUILD_PLAYER.name
                else -> "FAILED"
            }
        ClimaGraphicsLifecycleDiagnostics.logVideoFirstFrameTimeout(
            sessionId = activePlayer.sessionId,
            playerIdentity = activePlayer.identityHash,
            timeoutMs = firstFrameTimeoutMs,
            playerState = activePlayer.playerStateName,
            decoderGeneration = activeDecoderGeneration,
            surfaceEpoch = activeSurfaceAttachmentEpoch,
            surfaceStatus = currentSurfaceStatus(),
            recoveryState = recoveryState.name,
            nextTier = nextTierLabel,
            videoResId = appliedVideoResId ?: latestVideoResId,
        )
        escalateFailureOrTimeout(
            errorCodeName = "FIRST_FRAME_TIMEOUT",
            revocationReason = "first_frame_timeout_${firstFrameTimeoutMs}ms",
            positionMsHint = activePlayer.currentPositionMs,
            speedHint = appliedSpeed ?: latestPlaybackSpeed,
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                val activePlayer = playerHandle
                if (activePlayer.isReleased) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME -> {
                        val bridge = surfaceBridgeRef
                        if (bridge != null && latestCanAttachVideoSurfaceNow && !bridge.isPlayerAttached(activePlayer)) {
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
                    val currentPlayer = playerHandle
                    if (surfaceBridgeRef !== bridge || currentPlayer.isReleased) return@attachWindowObserver
                    ClimaGraphicsLifecycleDiagnostics.logExoViewAttachState(
                        sessionId = currentPlayer.sessionId,
                        playerIdentity = currentPlayer.identityHash,
                        viewClass = bridge.viewClassName,
                        viewIdentity = bridge.viewIdentity,
                        attached = attached,
                        surfaceViewClass = snapshot.surfaceViewClass,
                        textureAvailable = snapshot.textureAvailable,
                        hasSurfaceTexture = snapshot.hasSurfaceTexture,
                    )
                    val lostTextureSurface = !attached || !snapshot.isSurfaceValid
                    val wasUnavailable = !isTextureSurfaceAvailable
                    isTextureSurfaceAvailable = !lostTextureSurface
                    if (lostTextureSurface) {
                        activeSurfaceAttachmentEpoch += 1
                        currentPlayer.notifySurfaceReattachedForNewFrame()
                        invalidateCurrentFrame(
                            nextState =
                                if (!latestCanAttachVideoSurfaceNow || !attached) {
                                    WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED
                                } else if (needsCodecRecovery || recoveryInProgress) {
                                    WeatherVideoVisualState.VIDEO_RECOVERING
                                } else {
                                    WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                                },
                            nextRecoveryState =
                                when {
                                    recoveryExhaustedForCurrentMedia -> WeatherVideoRecoveryState.FAILED
                                    needsCodecRecovery || recoveryInProgress -> WeatherVideoRecoveryState.RECOVERING
                                    else -> WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                                },
                            milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                            reason = if (!attached) "view_detached_from_window" else "surface_texture_unavailable",
                        )
                    } else if (wasUnavailable) {
                        activeSurfaceAttachmentEpoch += 1
                        currentPlayer.notifySurfaceReattachedForNewFrame()
                        if (needsCodecRecovery || wasDecoderRevokedWhileDetached) {
                            codecRecoveryEpoch += 1
                        } else if (latestCanAttachVideoSurfaceNow && latestIsPlaying && isLifecycleStarted()) {
                            if (!bridge.isPlayerAttached(currentPlayer)) {
                                attachVideoOutputIfAllowed(bridge, reason = "surface_texture_available")
                            } else {
                                firstFrameWatchEpoch += 1
                                resumePlayerIfEligible()
                            }
                        }
                    }
                }
            val currentPlayer = playerHandle
            val initialSnapshot = bridge.inspectSurfaceSnapshot()
            isTextureSurfaceAvailable = initialSnapshot.isSurfaceValid
            if (canAttachVideoSurfaceNow) {
                activeSurfaceAttachmentEpoch += 1
                activeSurfaceViewIdentity = bridge.viewIdentity
                bridge.attachPlayer(currentPlayer)
                videoSurfaceClearedForMap = false
                val postAttachSnapshot = bridge.inspectSurfaceSnapshot()
                isTextureSurfaceAvailable = postAttachSnapshot.isSurfaceValid
                if (hasValidRenderedFrame && isDecoderCurrentlyAlive && isTextureSurfaceAvailable) {
                    renderedFrameSurfaceEpoch = activeSurfaceAttachmentEpoch
                    renderedFrameViewIdentity = bridge.viewIdentity
                }
                val surfaceFormatted = postAttachSnapshot.formatted()
                ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                    sessionId = currentPlayer.sessionId,
                    playerIdentity = currentPlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    viewClass = bridge.viewClassName,
                    surfaceInfo = surfaceFormatted,
                )
                GraphicsSurfaceCoordinator.onVideoOutputReattached(
                    sessionId = currentPlayer.sessionId,
                    playerIdentity = currentPlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    surfaceInfo = surfaceFormatted,
                )
                val initialVisualState =
                    if (hasValidRenderedFrame && isDecoderCurrentlyAlive && isTextureSurfaceAvailable) {
                        WeatherVideoVisualState.VIDEO_FRAME_READY
                    } else {
                        WeatherVideoVisualState.VIDEO_WAITING_FIRST_FRAME
                    }
                val initialRecoveryState =
                    if (initialVisualState == WeatherVideoVisualState.VIDEO_FRAME_READY) {
                        WeatherVideoRecoveryState.PLAYING
                    } else {
                        WeatherVideoRecoveryState.WAITING_FIRST_FRAME
                    }
                publishVisualState(
                    nextState = initialVisualState,
                    nextRecoveryState = initialRecoveryState,
                    milestoneEvent =
                        if (initialVisualState == WeatherVideoVisualState.VIDEO_FRAME_READY) {
                            "BACKGROUND_VIDEO_FRAME_READY"
                        } else {
                            "BACKGROUND_VIDEO_WAITING_FIRST_FRAME"
                        },
                    reason = "factory_attach",
                )
            } else {
                videoSurfaceClearedForMap = true
                activeSurfaceViewIdentity = 0
                GraphicsSurfaceCoordinator.onVideoOutputDetached(
                    sessionId = currentPlayer.sessionId,
                    playerIdentity = currentPlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "factory_map_active",
                )
                publishVisualState(
                    nextState = WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED,
                    nextRecoveryState = WeatherVideoRecoveryState.IDLE,
                    milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                    reason = "factory_map_active",
                )
            }
            bridge.view
        },
        update = {
            val bridge = surfaceBridgeRef ?: return@AndroidView
            val activePlayer = playerHandle
            if (!allowVideoSurfaceOutput) {
                detachVideoOutputForMapExclusivity(bridge, reason = "map_tab_active")
            } else if (!activePlayer.isReleased && canAttachVideoSurfaceNow && !bridge.isPlayerAttached(activePlayer)) {
                attachVideoOutputIfAllowed(bridge, reason = "android_view_update")
            }
        },
        onRelease = { releasedView ->
            val bridge = surfaceBridgeRef
            val activePlayer = playerHandle
            if (bridge != null && bridge.view === releasedView) {
                detachWindowObserverRef?.invoke()
                detachWindowObserverRef = null
                isTextureSurfaceAvailable = false
                activeSurfaceAttachmentEpoch += 1
                activeSurfaceViewIdentity = 0
                activePlayer.notifySurfaceReattachedForNewFrame()
                if (bridge.isPlayerAttached(activePlayer)) {
                    bridge.detachPlayer()
                }
                if (!activePlayer.isReleased && !videoSurfaceClearedForMap) {
                    activePlayer.clearVideoSurface()
                    videoSurfaceClearedForMap = true
                    ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                        sessionId = activePlayer.sessionId,
                        playerIdentity = activePlayer.identityHash,
                        viewIdentity = bridge.viewIdentity,
                        reason = "onRelease",
                    )
                }
                GraphicsSurfaceCoordinator.onVideoOutputDetached(
                    sessionId = activePlayer.sessionId,
                    playerIdentity = activePlayer.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "onRelease",
                )
                invalidateCurrentFrame(
                    nextState = WeatherVideoVisualState.VIDEO_OUTPUT_DETACHED,
                    nextRecoveryState =
                        if (recoveryExhaustedForCurrentMedia) {
                            WeatherVideoRecoveryState.FAILED
                        } else {
                            WeatherVideoRecoveryState.IDLE
                        },
                    milestoneEvent = "BACKGROUND_VIDEO_HIDDEN_NO_FRAME",
                    reason = "onRelease",
                )
                surfaceBridgeRef = null
            }
        },
    )
}
