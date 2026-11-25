package com.example.ui.components

import android.content.Context
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * 3. Hides the video layer immediately (`0f` on the exact same composition frame) whenever a valid
 *    rendered frame is unavailable, and fades it in smoothly (`tween(280)`) only after
 *    `onRenderedFirstFrame` confirms a valid frame for the current `MediaItem`, active decoder
 *    generation, and attached surface.
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
    val currentWallpaperResId = condition.staticWallpaperRes

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

    // All required conditions for video layer visibility (Etapa 3M / 3N):
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

    val currentFrameIdentity =
        Triple(
            visualSnapshot.sessionId,
            currentVideoResId,
            visualSnapshot.decoderGeneration,
        )

    // Single centralized Animatable controller for videoAlpha:
    // - Eliminates the 1-frame asynchronous lag of animateFloatAsState(..., snap()) by clamping
    //   videoAlpha to 0f synchronously during composition whenever canDisplayVideoFrame is false
    //   or when a new frame identity (session, MediaItem, decoderGeneration) has not yet started its fade.
    // - Fades in smoothly (280ms) once per valid confirmed frame identity.
    val videoAlphaAnimatable = remember { Animatable(0f) }
    var activeVisibleFrameIdentity by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var lastFadeInLoggedKey by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var lastWallpaperMilestoneSignature by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    if (!visualSnapshot.isEligibleForVisibleVideo || visualSnapshot.videoResId != currentVideoResId) {
        // Genuine frame invalidation (codec error, decoder release, MediaItem switch, map tab detach):
        // require a fresh fade-in when the next valid first frame is confirmed.
        if (activeVisibleFrameIdentity != null) {
            activeVisibleFrameIdentity = null
        }
        if (lastFadeInLoggedKey != null) {
            lastFadeInLoggedKey = null
        }
    }

    val videoAlpha =
        if (!canDisplayVideoFrame) {
            0f
        } else if (activeVisibleFrameIdentity != currentFrameIdentity) {
            0f
        } else {
            videoAlphaAnimatable.value
        }

    LaunchedEffect(videoAlpha) {
        onEffectiveVideoAlphaChanged(videoAlpha)
    }

    LaunchedEffect(
        currentVideoResId,
        canDisplayVideoFrame,
        currentFrameIdentity,
        visualSnapshot.visualState,
    ) {
        val wallpaperVisibleAsPrimary = !canDisplayVideoFrame
        val wallpaperSig = currentVideoResId to wallpaperVisibleAsPrimary
        if (wallpaperVisibleAsPrimary) {
            if (videoAlphaAnimatable.value != 0f) {
                videoAlphaAnimatable.snapTo(0f)
            }
            if (lastWallpaperMilestoneSignature != wallpaperSig) {
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
            }
        } else {
            lastWallpaperMilestoneSignature = wallpaperSig
            if (activeVisibleFrameIdentity != currentFrameIdentity) {
                activeVisibleFrameIdentity = currentFrameIdentity
                videoAlphaAnimatable.snapTo(0f)
                if (lastFadeInLoggedKey != currentFrameIdentity) {
                    lastFadeInLoggedKey = currentFrameIdentity
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
                videoAlphaAnimatable.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 280),
                )
            } else if (videoAlphaAnimatable.value != 1f) {
                // Restoring visibility of the exact same already-validated frame (e.g., Activity ON_STOP -> ON_START
                // where the decoder and frame remained intact) without re-emitting a duplicate fade-in event.
                videoAlphaAnimatable.snapTo(1f)
            }
        }
    }

    val tabSpec = remember(activeTab) { WeatherGlassSpecs.forTab(activeTab) }

    Box(modifier = modifier.fillMaxSize().background(ClimaDeepSky)) {
        // Layer 1: Static WebP wallpaper safety layer (always mounted, isolated in a skippable composable
        // so videoAlpha changes and unrelated state updates never reinitialize its Painter)
        StaticWeatherWallpaperLayer(wallpaperResId = currentWallpaperResId)

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
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = videoAlpha }
                        .alpha(videoAlpha)
                        .testTag("weather_video_layer"),
                playerBridgeFactory = playerBridgeFactory,
                surfaceBridgeFactory = surfaceBridgeFactory,
            )
        }

        // Layer 3: Night rain procedural overlay matching V53's `noite_chuva` over `cloudy_night`
        if (condition.needsNightRainOverlay && shouldPlayVideoNow) {
            NightRainOverlay(modifier = Modifier.fillMaxSize().testTag("weather_night_rain_overlay"))
        }

        // Layer 4: Subtle top/bottom atmospheric legibility scrim + optional tab shade
        AtmosphericScrimLayer(wallpaperShadeAlpha = tabSpec.wallpaperShadeAlpha)
    }
}

@Composable
private fun StaticWeatherWallpaperLayer(@DrawableRes wallpaperResId: Int) {
    Image(
        painter = painterResource(id = wallpaperResId),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().testTag("weather_static_wallpaper"),
    )
}

@Composable
private fun AtmosphericScrimLayer(wallpaperShadeAlpha: Float) {
    val scrimBrush =
        remember(wallpaperShadeAlpha) {
            Brush.verticalGradient(
                colors =
                    listOf(
                        Color.Black.copy(alpha = 0.16f + wallpaperShadeAlpha),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.24f + wallpaperShadeAlpha),
                    ),
            )
        }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(scrimBrush)
                .testTag("weather_atmospheric_scrim"),
    )
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

