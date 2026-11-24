package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import java.util.concurrent.atomic.AtomicBoolean

internal interface WeatherVideoPlayerBridge {
    val sessionId: String
    val identityHash: Int
    val isReleased: Boolean
    val isIdle: Boolean

    fun setMediaItem(uri: Uri)
    fun setVolume(volume: Float)
    fun setRepeatMode(repeatMode: Int)
    fun setPlayWhenReady(playWhenReady: Boolean)
    fun setPlaybackSpeed(speed: Float)
    fun prepare()
    fun stop()
    fun play()
    fun pause()
    fun clearVideoSurface()
    fun setFirstFrameListener(listener: (() -> Unit)?)
    fun attachToPlayerView(playerView: PlayerView)
    fun isAttachedToPlayerView(playerView: PlayerView): Boolean
    fun releaseOnce(): Boolean
}

internal interface WeatherVideoSurfaceBridge {
    val view: View
    val viewIdentity: Int
    fun attachPlayer(player: WeatherVideoPlayerBridge)
    fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean
    fun detachPlayer()
}

private class RealExoPlayerBridge(
    private val exoPlayer: ExoPlayer,
    override val sessionId: String,
) : WeatherVideoPlayerBridge {
    private val releasedFlag = AtomicBoolean(false)
    private var activeListener: Player.Listener? = null

    override val identityHash: Int
        get() = System.identityHashCode(exoPlayer)

    override val isReleased: Boolean
        get() = releasedFlag.get()

    override val isIdle: Boolean
        get() = !releasedFlag.get() && exoPlayer.playbackState == Player.STATE_IDLE

    override fun setMediaItem(uri: Uri) {
        if (releasedFlag.get()) return
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
        exoPlayer.pause()
    }

    override fun clearVideoSurface() {
        if (releasedFlag.get()) return
        runCatching { exoPlayer.clearVideoSurface() }
    }

    override fun setFirstFrameListener(listener: (() -> Unit)?) {
        activeListener?.let { prev ->
            runCatching { exoPlayer.removeListener(prev) }
            activeListener = null
        }
        if (listener != null && !releasedFlag.get()) {
            val wrapped =
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        listener()
                    }
                }
            activeListener = wrapped
            exoPlayer.addListener(wrapped)
        }
    }

    override fun attachToPlayerView(playerView: PlayerView) {
        if (releasedFlag.get()) return
        playerView.player = exoPlayer
    }

    override fun isAttachedToPlayerView(playerView: PlayerView): Boolean =
        playerView.player === exoPlayer

    override fun releaseOnce(): Boolean {
        if (!releasedFlag.compareAndSet(false, true)) return false
        setFirstFrameListener(null)
        exoPlayer.release()
        return true
    }
}

private class RealPlayerViewSurfaceBridge(
    private val playerView: PlayerView,
) : WeatherVideoSurfaceBridge {
    override val view: View
        get() = playerView

    override val viewIdentity: Int
        get() = System.identityHashCode(playerView)

    override fun attachPlayer(player: WeatherVideoPlayerBridge) {
        player.attachToPlayerView(playerView)
    }

    override fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean =
        player.isAttachedToPlayerView(playerView)

    override fun detachPlayer() {
        playerView.player = null
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
    playerBridgeFactory: ((Context) -> WeatherVideoPlayerBridge)? = null,
    surfaceBridgeFactory: ((Context) -> WeatherVideoSurfaceBridge)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameRendered)
    val latestIsPlaying by rememberUpdatedState(isPlaying)

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
                                playWhenReady = isPlaying
                            },
                        sessionId = sessionId,
                    )
            ClimaGraphicsLifecycleDiagnostics.logExoPlayerCreated(
                sessionId = created.sessionId,
                playerIdentity = created.identityHash,
                videoResId = videoResId,
            )
            created
        }

    var surfaceBridgeRef by remember { mutableStateOf<WeatherVideoSurfaceBridge?>(null) }
    var appliedVideoResId by remember(playerHandle) { mutableStateOf<Int?>(null) }
    var appliedSpeed by remember(playerHandle) { mutableStateOf<Float?>(null) }
    var lastAppliedPlayState by remember(playerHandle) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(playerHandle, videoResId) {
        if (playerHandle.isReleased || appliedVideoResId == videoResId) return@LaunchedEffect
        val uri = Uri.parse("android.resource://${context.packageName}/$videoResId")
        if (appliedVideoResId != null) {
            playerHandle.stop()
        }
        playerHandle.setMediaItem(uri)
        playerHandle.setVolume(0f)
        playerHandle.setRepeatMode(Player.REPEAT_MODE_ONE)
        playerHandle.setPlayWhenReady(latestIsPlaying)
        playerHandle.prepare()
        appliedVideoResId = videoResId
        lastAppliedPlayState = latestIsPlaying
    }

    LaunchedEffect(playerHandle, playbackSpeed) {
        if (playerHandle.isReleased) return@LaunchedEffect
        val clamped = playbackSpeed.coerceIn(0.25f, 2.0f)
        if (appliedSpeed != clamped) {
            playerHandle.setPlaybackSpeed(clamped)
            appliedSpeed = clamped
        }
    }

    LaunchedEffect(playerHandle, isPlaying) {
        if (playerHandle.isReleased || lastAppliedPlayState == isPlaying) return@LaunchedEffect
        lastAppliedPlayState = isPlaying
        playerHandle.setPlayWhenReady(isPlaying)
        if (isPlaying) {
            if (playerHandle.isIdle) {
                playerHandle.prepare()
            } else {
                playerHandle.play()
            }
        } else {
            playerHandle.pause()
        }
    }

    DisposableEffect(lifecycleOwner, playerHandle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (playerHandle.isReleased) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME -> {
                        if (latestIsPlaying && lastAppliedPlayState != true) {
                            lastAppliedPlayState = true
                            playerHandle.setPlayWhenReady(true)
                            if (playerHandle.isIdle) {
                                playerHandle.prepare()
                            } else {
                                playerHandle.play()
                            }
                        }
                    }

                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP -> {
                        if (lastAppliedPlayState != false) {
                            lastAppliedPlayState = false
                            playerHandle.setPlayWhenReady(false)
                            playerHandle.pause()
                        }
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
            latestOnFirstFrame()
        }
        onDispose {
            playerHandle.setFirstFrameListener(null)
            surfaceBridgeRef?.let { bridge ->
                bridge.detachPlayer()
                ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                    reason = "onDispose",
                )
            }
            surfaceBridgeRef = null
            playerHandle.clearVideoSurface()
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
            bridge.attachPlayer(playerHandle)
            surfaceBridgeRef = bridge
            ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                sessionId = playerHandle.sessionId,
                playerIdentity = playerHandle.identityHash,
                viewIdentity = bridge.viewIdentity,
            )
            bridge.view
        },
        update = {
            val bridge = surfaceBridgeRef ?: return@AndroidView
            if (!playerHandle.isReleased && !bridge.isPlayerAttached(playerHandle)) {
                bridge.attachPlayer(playerHandle)
                ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceAttached(
                    sessionId = playerHandle.sessionId,
                    playerIdentity = playerHandle.identityHash,
                    viewIdentity = bridge.viewIdentity,
                )
            }
        },
        onRelease = {
            val bridge = surfaceBridgeRef
            if (bridge != null) {
                bridge.detachPlayer()
                if (!playerHandle.isReleased) {
                    playerHandle.clearVideoSurface()
                }
                ClimaGraphicsLifecycleDiagnostics.logPlayerSurfaceCleared(
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
