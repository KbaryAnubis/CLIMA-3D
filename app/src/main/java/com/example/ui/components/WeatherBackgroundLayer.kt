package com.example.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.example.media.WeatherCondition
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.WeatherGlassSpecs

/**
 * Persistent full-screen weather background layer mounted behind all tabs:
 * 1. Always shows the matching static WebP wallpaper (`wx_wall_*`) immediately (zero black flash).
 * 2. When dynamic scenarios are enabled and system reduced-motion is off, mounts the Media3
 *    looping MP4 clip (`wx_bg_*`) and fades it in upon first rendered frame.
 * 3. For `NOITE_CHUVA`, applies the subtle procedural rain-streak overlay over `cloudy_night`
 *    matching V53's visual specification.
 * 4. Respects V39/V53 per-tab shade transparency.
 */
@Composable
fun WeatherBackgroundLayer(
    condition: WeatherCondition,
    activeTab: ClimaTab,
    animatedEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    WeatherBackgroundLayerImpl(
        condition = condition,
        activeTab = activeTab,
        animatedEnabled = animatedEnabled,
        modifier = modifier,
    )
}

@Composable
internal fun WeatherBackgroundLayerImpl(
    condition: WeatherCondition,
    activeTab: ClimaTab,
    animatedEnabled: Boolean,
    modifier: Modifier = Modifier,
    playerBridgeFactory: ((Context) -> WeatherVideoPlayerBridge)? = null,
    surfaceBridgeFactory: ((Context) -> WeatherVideoSurfaceBridge)? = null,
) {
    val context = LocalContext.current
    val systemReducedMotion = remember(context) { isSystemReducedMotionEnabled(context) }
    val shouldMountVideo = animatedEnabled && !systemReducedMotion
    val isMapTabActive = shouldPauseBackgroundMotionForTab(activeTab)
    val shouldPlayVideoNow = shouldMountVideo && !isMapTabActive

    var firstFrameReady by remember(condition.backgroundVideo) { mutableStateOf(false) }

    LaunchedEffect(shouldMountVideo) {
        if (!shouldMountVideo) {
            firstFrameReady = false
        }
    }

    val videoAlpha by animateFloatAsState(
        targetValue = if (shouldPlayVideoNow && firstFrameReady) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "weather_video_fade",
    )

    val tabSpec = remember(activeTab) { WeatherGlassSpecs.forTab(activeTab) }

    Box(modifier = modifier.fillMaxSize().background(ClimaDeepSky)) {
        // Layer 1: Static WebP wallpaper fallback / immediate poster
        Image(
            painter = painterResource(id = condition.staticWallpaperRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 2: Media3 looping muted MP4 video (paused and detached from output surface while MapLibre TextureView tabs are active)
        if (shouldMountVideo) {
            WeatherVideoBackgroundImpl(
                videoResId = condition.backgroundVideo.rawRes,
                playbackSpeed = condition.videoPlaybackSpeed,
                isPlaying = shouldPlayVideoNow,
                allowVideoSurfaceOutput = !isMapTabActive,
                onFirstFrameRendered = { firstFrameReady = true },
                modifier = Modifier.fillMaxSize().alpha(videoAlpha),
                playerBridgeFactory = playerBridgeFactory,
                surfaceBridgeFactory = surfaceBridgeFactory,
            )
        }

        // Layer 3: Night rain procedural overlay matching V53's `noite_chuva` over `cloudy_night`
        if (condition.needsNightRainOverlay && shouldPlayVideoNow) {
            NightRainOverlay(modifier = Modifier.fillMaxSize())
        }

        // Layer 4: Subtle top/bottom atmospheric legibility scrim + optional tab shade
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.16f + tabSpec.wallpaperShadeAlpha),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.24f + tabSpec.wallpaperShadeAlpha),
                        )
                    )
                )
        )
    }
}

@Composable
private fun NightRainOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "night_rain_overlay")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "night_rain_phase",
    )

    // Deterministic drop seeds matching V53's rain layer feel
    val drops = remember {
        List(48) { index ->
            val xFraction = ((index * 37 + 11) % 100) / 100f
            val yOffset = ((index * 53 + 7) % 100) / 100f
            val lengthFraction = 0.025f + (index % 4) * 0.01f
            val alpha = 0.14f + (index % 5) * 0.04f
            Triple(xFraction, yOffset, Pair(lengthFraction, alpha))
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val slantX = -w * 0.018f
        drops.forEach { (xFrac, ySeed, style) ->
            val (lenFrac, alpha) = style
            val yProgress = (ySeed + phase) % 1f
            val startX = xFrac * w
            val startY = yProgress * (h + h * lenFrac) - h * lenFrac
            val endX = startX + slantX
            val endY = startY + h * lenFrac
            drawLine(
                color = Color(0xFFB8E2FF).copy(alpha = alpha),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = 2.2f,
            )
        }
    }
}

private fun isSystemReducedMotionEnabled(context: Context): Boolean {
    return runCatching {
        val scale =
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
        scale == 0f
    }.getOrDefault(false)
}

internal fun shouldPauseBackgroundMotionForTab(tab: ClimaTab): Boolean =
    tab == ClimaTab.RADAR || tab == ClimaTab.CITIES

