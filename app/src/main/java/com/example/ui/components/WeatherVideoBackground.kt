package com.example.ui.components

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/**
 * Media3 ExoPlayer looping background video component:
 * - Infinite repeat (`REPEAT_MODE_ONE`)
 * - Muted (`volume = 0f`)
 * - TextureView surface (`view_weather_video_texture.xml`) so translucent Compose glass cards
 *   composite cleanly over the video with zero SurfaceView Z-order artifacts.
 * - Supports V53 playback speed adjustments (e.g., 0.85f for `chuva_fraca`).
 * - Lifecycle-aware pause/resume and automatic release on dispose.
 */
@Composable
fun WeatherVideoBackground(
    @RawRes videoResId: Int,
    playbackSpeed: Float = 1.0f,
    isPlaying: Boolean = true,
    onFirstFrameRendered: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameRendered)
    val latestIsPlaying by rememberUpdatedState(isPlaying)

    val exoPlayer =
        remember(context) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
            }
        }

    LaunchedEffect(videoResId, playbackSpeed) {
        val uri = Uri.parse("android.resource://${context.packageName}/$videoResId")
        exoPlayer.stop()
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.25f, 2.0f))
        exoPlayer.volume = 0f
        exoPlayer.repeatMode = Player.REPEAT_MODE_ONE
        exoPlayer.playWhenReady = latestIsPlaying
        exoPlayer.prepare()
    }

    LaunchedEffect(isPlaying) {
        exoPlayer.playWhenReady = isPlaying
        if (isPlaying && exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    latestOnFirstFrame()
                }
            }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        if (latestIsPlaying) {
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        }
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        exoPlayer.pause()
                    }

                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val inflated =
                LayoutInflater.from(viewContext)
                    .inflate(R.layout.view_weather_video_texture, null, false) as PlayerView
            inflated.player = exoPlayer
            inflated
        },
        update = { playerView ->
            if (playerView.player !== exoPlayer) {
                playerView.player = exoPlayer
            }
        },
        onRelease = { playerView ->
            playerView.player = null
        },
    )
}
