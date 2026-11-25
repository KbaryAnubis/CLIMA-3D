package com.example.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.diagnostics.GraphicsSurfaceCoordinator
import com.example.diagnostics.VideoSurfaceOutputState
import com.example.media.WeatherCondition
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.WeatherGlassSpecs

/**
 * Persistent full-screen weather background layer mounted behind all tabs:
 * 1. Always keeps the matching static WebP wallpaper (`wx_wall_*`) mounted and visible underneath
 *    as an unconditional safety layer (zero black flash during startup, buffering, IDLE, MediaItem
 *    switches, map tab transitions, or MediaCodec reclaim/recovery).
 * 2. When dynamic scenarios are enabled and system reduced-motion is off, mounts the Media3
 *    looping MP4 clip (`wx_bg_*`).
 * 3. Hides the video layer immediately (`snap()` -> `alpha = 0f`) whenever a valid rendered frame
 *    is unavailable, and fades it in smoothly (`tween(280)`) only after `onRenderedFirstFrame`
 *    confirms a valid frame for the current `MediaItem` and decoder generation.
 * 4. For `NOITE_CHUVA`, applies the subtle procedural rain-streak overlay over `cloudy_night`
 *    matching V53's visual specification.
 * 5. Respects V39/V53 per-tab shade transparency.
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
    onEffectiveVideoAlphaChanged: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coordinationSnapshot by GraphicsSurfaceCoordinator.state.collectAsState()
    val systemReducedMotion = remember(context) { isSystemReducedMotionEnabled(context) }
    val shouldMountVideo = animatedEnabled && !systemReducedMotion
    val isMapTabActive = shouldPauseBackgroundMotionForTab(activeTab)
    val allowVideoSurfaceOutput = !isMapTabActive
    val shouldPlayVideoNow = shouldMountVideo && allowVideoSurfaceOutput

    var isActivityStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, _ ->
                isActivityStarted =
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var visualSnapshot by remember {
        mutableStateOf(WeatherVideoVisualSnapshot())
    }

    val currentVideoResId = condition.backgroundVideo.rawRes

    LaunchedEffect(shouldMountVideo) {
        if (!shouldMountVideo) {
            visualSnapshot =
                visualSnapshot.copy(
                    visualState = WeatherVideoVisualState.VIDEO_NOT_READY,
                    frameReady = false,
                    surfaceOutputAttached = false,
                )
        }
    }

    // All required conditions for video layer visibility (Etapa 3M item 4):
    // 1. Animated background preference enabled & system reduced-motion off (shouldMountVideo)
    // 2. Active tab is not RADAR or CITIES (allowVideoSurfaceOutput / shouldPlayVideoNow)
    // 3. Activity is at least STARTED (isActivityStarted)
    // 4. Video surface output is attached and coordinator permits video output
    // 5. Valid MediaItem matches current condition's rawRes
    // 6. Valid visual frame confirmed by onRenderedFirstFrame for current decoder generation
    // 7. No fatal decoder error or recovery pending
    val canDisplayVideoFrame =
        shouldPlayVideoNow &&
            allowVideoSurfaceOutput &&
            isActivityStarted &&
            coordinationSnapshot.canAttachVideoOutput &&
            coordinationSnapshot.videoState == VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE &&
            visualSnapshot.videoResId == currentVideoResId &&
            visualSnapshot.isEligibleForVisibleVideo

    val targetVideoAlpha = if (canDisplayVideoFrame) 1f else 0f

    // Single source of truth for videoAlpha:
    // - Fade in smoothly (280ms) only when a confirmed valid frame becomes ready.
    // - Snap immediately to 0f when frame readiness is lost (codec error, media change, map tab)
    //   so an invalid or black TextureView buffer is never exposed over the static wallpaper.
    val videoAlpha by animateFloatAsState(
        targetValue = targetVideoAlpha,
        animationSpec = if (targetVideoAlpha > 0f) tween(durationMillis = 280) else snap(),
        label = "weather_video_fade",
    )

    LaunchedEffect(videoAlpha) {
        onEffectiveVideoAlphaChanged(videoAlpha)
    }

    var lastWallpaperMilestoneSignature by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var lastFadeInLoggedKey by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }

    LaunchedEffect(
        currentVideoResId,
        canDisplayVideoFrame,
        visualSnapshot.sessionId,
        visualSnapshot.decoderGeneration,
        visualSnapshot.visualState,
    ) {
        val wallpaperVisibleAsPrimary = !canDisplayVideoFrame
        val wallpaperSig = currentVideoResId to wallpaperVisibleAsPrimary
        if (wallpaperVisibleAsPrimary && lastWallpaperMilestoneSignature != wallpaperSig) {
            lastWallpaperMilestoneSignature = wallpaperSig
            ClimaGraphicsLifecycleDiagnostics.logBackgroundVisualMilestone(
                eventName = "BACKGROUND_WALLPAPER_VISIBLE",
                sessionId = visualSnapshot.sessionId.ifEmpty { "wallpaper-only" },
                playerIdentity = visualSnapshot.playerIdentity,
                videoResId = currentVideoResId,
                playerState = visualSnapshot.playerState,
                decoderGeneration = visualSnapshot.decoderGeneration,
                videoAlpha = 0f,
                frameReady = visualSnapshot.frameReady,
                visualState = visualSnapshot.visualState.name,
                reason = if (!shouldMountVideo) "animated_disabled" else "fallback_active",
            )
        } else if (!wallpaperVisibleAsPrimary) {
            lastWallpaperMilestoneSignature = wallpaperSig
            val fadeKey =
                Triple(
                    visualSnapshot.sessionId,
                    currentVideoResId,
                    visualSnapshot.decoderGeneration,
                )
            if (lastFadeInLoggedKey != fadeKey) {
                lastFadeInLoggedKey = fadeKey
                ClimaGraphicsLifecycleDiagnostics.logBackgroundVisualMilestone(
                    eventName = "BACKGROUND_VIDEO_FADE_IN",
                    sessionId = visualSnapshot.sessionId,
                    playerIdentity = visualSnapshot.playerIdentity,
                    videoResId = currentVideoResId,
                    playerState = visualSnapshot.playerState,
                    decoderGeneration = visualSnapshot.decoderGeneration,
                    videoAlpha = 1f,
                    frameReady = true,
                    visualState = visualSnapshot.visualState.name,
                    reason = "valid_frame_confirmed",
                )
            }
        }
    }

    val tabSpec = remember(activeTab) { WeatherGlassSpecs.forTab(activeTab) }

    Box(modifier = modifier.fillMaxSize().background(ClimaDeepSky)) {
        // Layer 1: Static WebP wallpaper safety layer (always mounted, never unmounted or hidden during video errors/recovery)
        Image(
            painter = painterResource(id = condition.staticWallpaperRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().testTag("weather_static_wallpaper"),
        )

        // Layer 2: Media3 looping muted MP4 video (hidden at alpha=0f whenever no valid frame is confirmed)
        if (shouldMountVideo) {
            WeatherVideoBackgroundImpl(
                videoResId = currentVideoResId,
                playbackSpeed = condition.videoPlaybackSpeed,
                isPlaying = shouldPlayVideoNow,
                allowVideoSurfaceOutput = allowVideoSurfaceOutput,
                onVisualStateChanged = { snapshot ->
                    visualSnapshot = snapshot
                },
                modifier = Modifier.fillMaxSize().alpha(videoAlpha).testTag("weather_video_layer"),
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

