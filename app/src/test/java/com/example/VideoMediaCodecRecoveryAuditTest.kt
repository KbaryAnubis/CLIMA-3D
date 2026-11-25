package com.example

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.diagnostics.GraphicsSurfaceCoordinator
import com.example.diagnostics.VideoSurfaceOutputState
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapLibreViewImpl
import com.example.maps.CityMapUiState
import com.example.maps.LocationComponentSyncResult
import com.example.maps.MapLibreControllerPort
import com.example.maps.MapStylePort
import com.example.maps.MapStyleSpecRequest
import com.example.maps.MapSurfaceCallbacks
import com.example.maps.MapViewSurfacePort
import com.example.media.WeatherCondition
import com.example.ui.components.ExoSurfaceSnapshot
import com.example.ui.components.WeatherBackgroundLayerImpl
import com.example.ui.components.WeatherVideoBackgroundImpl
import com.example.ui.components.WeatherVideoCodecErrorDiagnostic
import com.example.ui.components.WeatherVideoFrameToken
import com.example.ui.components.WeatherVideoPlayerBridge
import com.example.ui.components.WeatherVideoRecoveryState
import com.example.ui.components.WeatherVideoSurfaceBridge
import com.example.ui.components.WeatherVideoVisualSnapshot
import com.example.ui.components.WeatherVideoVisualState
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoMediaCodecRecoveryAuditTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = true
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
    }

    @After
    fun teardown() {
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = null
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
    }

    // 1. Revocation (`Released by resource manager` / DEAD_OBJECT): invalidates old frame immediately,
    // keeps static WebP wallpaper visible, recovers same ExoPlayer, and restores PLAYING on new first frame.
    @Test
    fun `mediacodec_released_by_resource_manager_hides_video_keeps_wallpaper_and_recovers_automatically`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        val snapshots = mutableListOf<WeatherVideoVisualSnapshot>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA_FRACA, // 0.85x speed
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = true).apply {
                            simulatedPositionMs = 4_250L
                        }.also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        // Hold first frame during recovery so we verify WebP fallback and DECODER_REVOKED -> RECOVERING transition
        player.autoFirstFrameOnPrepare = false
        composeTestRule.runOnUiThread {
            player.simulateMediaCodecRevocation(
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "E/MediaCodec: Released by resource manager (DEAD_OBJECT)",
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        assertEquals("Tier 1 must preserve single ExoPlayer instance", 1, players.size)
        assertFalse(player.isReleased)
        assertEquals(2, player.prepareCalls)
        assertEquals(4_250L, player.currentPositionMs)
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        val logsMidRecovery = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsMidRecovery.any { it.contains("VIDEO_REVOCATION_DETECTED") && it.contains("recoveryState=DECODER_REVOKED") })
        assertTrue(logsMidRecovery.any { it.contains("VIDEO_RECOVERY_ATTEMPT") && it.contains("tier=RECOVER_EXISTING") })

        // New decoder renders first frame -> video restores smoothly
        composeTestRule.runOnUiThread { player.emitFirstFrameRendered() }
        composeTestRule.waitForIdle()

        assertEquals(1f, observedVideoAlpha, 0.001f)
        val logsAfter = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsAfter.any { it.contains("VIDEO_RECOVERY_OUTCOME") && it.contains("outcome=FIRST_FRAME_CONFIRMED") })
    }

    // 2. Prioritized 3-tier escalation: Tier 1 (RECOVER_EXISTING) -> Tier 2 (CONTROLLED_RETRY) ->
    // Tier 3 (REBUILD_PLAYER as last resort). Verifies old player released BEFORE new player created,
    // zero duplicate active players, and full preservation of videoResId, position, speed, mute, repeat, playWhenReady.
    @Test
    fun `last_resort_player_rebuild_releases_old_player_before_creating_replacement_and_preserves_playback_config`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        var autoFirstFrame = true
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA_FRACA,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = autoFirstFrame).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player1 = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        player1.simulatedPositionMs = 9_150L
        autoFirstFrame = false
        player1.autoFirstFrameOnPrepare = false

        // Failure #1 -> Tier 1 (RECOVER_EXISTING)
        composeTestRule.runOnUiThread {
            player1.simulateMediaCodecRevocation(errorCodeName = "ERROR_CODE_DECODING_FAILED_T1")
        }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(2, player1.prepareCalls)
        assertFalse(player1.isReleased)

        // Failure #2 -> Tier 2 (CONTROLLED_RETRY: surface rebind + stop + setMediaItem + seekTo + prepare)
        composeTestRule.runOnUiThread {
            player1.simulateMediaCodecRevocation(errorCodeName = "ERROR_CODE_DECODING_FAILED_T2")
        }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(3, player1.prepareCalls)
        assertTrue(player1.clearSurfaceCount >= 1)
        assertEquals(2, player1.setMediaItemCalls)
        assertFalse(player1.isReleased)

        // Failure #3 -> Tier 3 (REBUILD_PLAYER): new player WILL succeed on prepare()
        autoFirstFrame = true
        composeTestRule.runOnUiThread {
            player1.simulateMediaCodecRevocation(errorCodeName = "ERROR_CODE_DECODING_FAILED_T3")
        }
        composeTestRule.waitForIdle()

        assertEquals("Rebuild must produce exactly 1 replacement player (total 2)", 2, players.size)
        val player2 = players[1]
        assertTrue("Old player must be released on Tier 3 rebuild", player1.isReleased)
        assertEquals(1, player1.releaseCalls)
        assertFalse("Replacement player must be active", player2.isReleased)
        assertEquals("Never more than 1 non-released active player", 1, players.count { !it.isReleased })
        assertTrue("Old player releaseOnce() MUST execute before new player creation", player1.releaseOrder < player2.creationOrder)

        // Verify all configuration was preserved onto rebuilt player2
        assertEquals(WeatherCondition.CHUVA_FRACA.backgroundVideo.rawRes, player2.currentVideoResHint)
        assertTrue(player2.appliedUriString?.endsWith("/${WeatherCondition.CHUVA_FRACA.backgroundVideo.rawRes}") == true)
        assertEquals(9_150L, player2.currentPositionMs)
        assertEquals(0.85f, player2.currentPlaybackSpeed, 0.001f)
        assertEquals(0f, player2.lastVolume ?: -1f, 0.001f)
        assertEquals(Player.REPEAT_MODE_ONE, player2.lastRepeatMode)
        assertEquals(true, player2.lastPlayWhenReady)
        assertEquals(1f, observedVideoAlpha, 0.001f)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logs.any { it.contains("VIDEO_PLAYER_REBUILD") && it.contains("stage=OLD_PLAYER_RELEASED") })
        assertTrue(logs.any { it.contains("VIDEO_PLAYER_REBUILD") && it.contains("stage=NEW_PLAYER_CREATED") })
        assertTrue(logs.any { it.contains("VIDEO_PLAYER_REBUILD") && it.contains("stage=COMPLETED") })
        assertTrue(logs.any { it.contains("VIDEO_RECOVERY_OUTCOME") && it.contains("tier=REBUILD_PLAYER") && it.contains("outcome=FIRST_FRAME_CONFIRMED") })
    }

    // 3. First-frame watchdog timeout detects silent decoder stall (e.g. onVideoDecoderReleased without onPlayerError),
    // escalates through Tier 1 -> Tier 2 -> Tier 3 -> FAILED without infinite loops, keeping WebP visible.
    @Test
    fun `first_frame_timeout_escalates_silent_decoder_loss_and_stops_cleanly_at_failed_without_infinite_loop`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        val snapshots = mutableListOf<WeatherVideoVisualSnapshot>()
        var observedVideoAlpha = -1f
        var condition by mutableStateOf(WeatherCondition.TEMPESTADE)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = condition,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                    firstFrameTimeoutMs = 500L,
                )
            }
        }
        composeTestRule.waitForIdle()

        val player1 = players.single()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        assertEquals(1, player1.prepareCalls)

        // Advance past timeout #1 -> triggers Tier 1 (RECOVER_EXISTING)
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals(2, player1.prepareCalls)
        assertEquals(1, players.size)

        // Advance past timeout #2 -> triggers Tier 2 (CONTROLLED_RETRY)
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals(3, player1.prepareCalls)
        assertEquals(1, players.size)

        // Advance past timeout #3 -> triggers Tier 3 (REBUILD_PLAYER)
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals(2, players.size)
        assertTrue(player1.isReleased)
        val player2 = players[1]
        assertEquals(1, player2.prepareCalls)

        // Advance past timeout #4 -> enters FAILED cleanly, no more prepare() or rebuilds
        composeTestRule.mainClock.advanceTimeBy(550L)
        composeTestRule.waitForIdle()
        assertEquals(2, players.size)
        assertEquals(1, player2.prepareCalls)

        // Wait several more timeout windows to prove zero infinite loop
        repeat(5) {
            composeTestRule.mainClock.advanceTimeBy(1_000L)
            composeTestRule.waitForIdle()
        }
        assertEquals("No extra prepare() after FAILED state", 1, player2.prepareCalls)
        assertEquals("No extra rebuild after FAILED state", 2, players.size)
        assertEquals(0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logs.any { it.contains("VIDEO_FIRST_FRAME_TIMEOUT") })
        assertTrue(logs.any { it.contains("VIDEO_RECOVERY_OUTCOME") && it.contains("outcome=FAILED_EXHAUSTED") })
        assertTrue(logs.any { it.contains("BACKGROUND_VIDEO_RECOVERY_FAILED") })

        // Changing condition resets exhaustion cleanly for the new MediaItem
        composeTestRule.runOnUiThread {
            player2.autoFirstFrameOnPrepare = true
            condition = WeatherCondition.SOL
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 4. First-frame timeout must NOT fire while MapLocationPicker is active, and codec revocation while
    // MapLibre is active must defer until MapView finishes teardown, then recover and render first frame.
    @Test
    fun `map_location_picker_suppresses_timeout_and_defers_revocation_recovery_until_map_destroyed`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        val surfaces = mutableListOf<RecoveryFakeSurfaceBridge>()
        val mapPorts = mutableListOf<RecoveryFakeMapPort>()
        var isMapPickerOpen by mutableStateOf(false)
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = {
                            RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = false).apply {
                                simulatedPositionMs = 3_000L
                            }.also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx).also { surfaces += it } },
                        onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                        firstFrameTimeoutMs = 400L,
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> RecoveryFakeMapPort(ctx).also { mapPorts += it } },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()

        // Open MapLocationPicker while still waiting for first frame
        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))

        // Advance time well beyond timeout while map is open: MUST NOT timeout or call prepare()
        composeTestRule.mainClock.advanceTimeBy(2_000L)
        composeTestRule.waitForIdle()
        assertEquals("Timeout must not trigger prepare() while map surface is open", 1, player.prepareCalls)

        // Simulate MediaCodec revoked while map picker is open -> must defer without attaching video surface
        composeTestRule.runOnUiThread {
            player.simulateMediaCodecRevocation(errorCodeName = "ERROR_CODE_DECODING_FAILED_WHILE_MAP_OPEN")
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(1, player.prepareCalls)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)

        // Close MapLocationPicker -> after MapView destroys & detaches, video surface reattaches and deferred recovery succeeds
        player.autoFirstFrameOnPrepare = true
        composeTestRule.runOnUiThread { isMapPickerOpen = false }
        composeTestRule.waitForIdle()

        assertTrue(surface.isPlayerAttached(player))
        assertEquals(2, player.prepareCalls)
        assertEquals(3_000L, player.currentPositionMs)
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 5. Surface destroyed during PREPARING and during PLAYING:
    // hides video immediately, defers recovery while TextureView surface is unavailable, and restores when surface returns.
    @Test
    fun `surface_destroyed_during_preparing_and_playing_hides_video_defers_recovery_and_resumes_on_valid_surface`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        val surfaces = mutableListOf<RecoveryFakeSurfaceBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.NUBLADO,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                    firstFrameTimeoutMs = 500L,
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()

        // 1. Destroy SurfaceTexture while still PREPARING -> timeout must NOT burn retries while surface is unavailable
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = false, hasSurfaceTexture = false)
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1_500L)
        composeTestRule.waitForIdle()
        assertEquals("Must not retry prepare() while TextureView surface is unavailable", 1, player.prepareCalls)
        assertEquals(0f, observedVideoAlpha, 0.001f)

        // Restore valid surface and render first frame -> PLAYING (alpha=1f)
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = true, hasSurfaceTexture = true)
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)

        // 2. While PLAYING, destroy SurfaceTexture AND revoke MediaCodec
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = false, hasSurfaceTexture = false)
        }
        composeTestRule.waitForIdle()
        assertEquals("Surface loss during PLAYING must hide video immediately", 0f, observedVideoAlpha, 0.001f)

        composeTestRule.runOnUiThread {
            player.simulateMediaCodecRevocation(errorCodeName = "ERROR_CODE_SURFACE_LOST")
        }
        composeTestRule.waitForIdle()
        // Recovery MUST wait for surface availability!
        assertEquals(1, player.prepareCalls)

        // Verify diagnostic recorded SURFACE_UNAVAILABLE_DEFERRED
        assertTrue(
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
                .any { it.contains("VIDEO_RECOVERY_OUTCOME") && it.contains("outcome=SURFACE_UNAVAILABLE_DEFERRED") },
        )

        // 3. New valid SurfaceTexture becomes available -> recovery executes automatically and restores PLAYING
        player.autoFirstFrameOnPrepare = true
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = true, hasSurfaceTexture = true)
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 6. Late onRenderedFirstFrame callbacks from a released/old ExoPlayer or stale decoder/surface epoch
    // are strictly rejected after Tier 3 rebuild or recovery.
    @Test
    fun `late_first_frame_callbacks_from_released_player_or_stale_epoch_are_rejected_after_rebuild`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        val surfaces = mutableListOf<RecoveryFakeSurfaceBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player1 = players.single()
        val surface = surfaces.single()

        // Escalate player1 through Tier 1, Tier 2, Tier 3 rebuild -> player2 active waiting for first frame
        repeat(3) {
            composeTestRule.runOnUiThread {
                player1.simulateMediaCodecRevocation(errorCodeName = "REBUILD_TRIGGER_$it")
            }
            composeTestRule.waitForIdle()
        }
        assertEquals(2, players.size)
        val player2 = players[1]
        assertTrue(player1.isReleased)
        assertEquals(0f, observedVideoAlpha, 0.001f)

        // Fire late callback claiming to be from player1 (both raw callback and token) -> MUST be rejected
        composeTestRule.runOnUiThread {
            player1.emitFirstFrameRendered()
            player2.emitFirstFrameRenderedWithToken(
                WeatherVideoFrameToken(
                    sessionId = player1.sessionId, // stale session/player
                    playerIdentity = player1.identityHash,
                    videoResId = WeatherCondition.SOL.backgroundVideo.rawRes,
                    decoderGeneration = 1,
                    surfaceEpoch = player2.surfaceEpoch,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
            // Fire stale surfaceEpoch callback on player2 -> MUST be rejected
            player2.emitFirstFrameRenderedWithToken(
                WeatherVideoFrameToken(
                    sessionId = player2.sessionId,
                    playerIdentity = player2.identityHash,
                    videoResId = WeatherCondition.SOL.backgroundVideo.rawRes,
                    decoderGeneration = player2.decoderGeneration,
                    surfaceEpoch = player2.surfaceEpoch - 1,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Late/stale tokens must never reveal video", 0f, observedVideoAlpha, 0.001f)

        // Valid token on player2 reveals video cleanly
        composeTestRule.runOnUiThread { player2.emitFirstFrameRendered() }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 7. Direct verification on WeatherVideoBackgroundImpl that recoveryState exposes all expected states
    @Test
    fun `weather_video_background_exposes_explicit_recovery_state_transitions`() {
        val players = mutableListOf<RecoveryFakePlayerBridge>()
        val snapshots = mutableListOf<WeatherVideoVisualSnapshot>()

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_sun_day,
                    playbackSpeed = 1.0f,
                    isPlaying = true,
                    allowVideoSurfaceOutput = true,
                    onVisualStateChanged = { snapshots += it },
                    playerBridgeFactory = {
                        RecoveryFakePlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> RecoveryFakeSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player1 = players.single()
        assertTrue(snapshots.any { it.recoveryState == WeatherVideoRecoveryState.PREPARING || it.recoveryState == WeatherVideoRecoveryState.WAITING_FIRST_FRAME })

        composeTestRule.runOnUiThread { player1.emitFirstFrameRendered() }
        composeTestRule.waitForIdle()
        assertEquals(WeatherVideoRecoveryState.PLAYING, snapshots.last().recoveryState)
        assertEquals(WeatherVideoVisualState.VIDEO_FRAME_READY, snapshots.last().visualState)

        // Revoke -> RECOVERING -> PLAYING
        composeTestRule.runOnUiThread { player1.simulateMediaCodecRevocation() }
        composeTestRule.waitForIdle()
        assertTrue(snapshots.any { it.recoveryState == WeatherVideoRecoveryState.DECODER_REVOKED })
        assertEquals(WeatherVideoRecoveryState.RECOVERING, snapshots.last().recoveryState)

        composeTestRule.runOnUiThread { player1.emitFirstFrameRendered() }
        composeTestRule.waitForIdle()
        assertEquals(WeatherVideoRecoveryState.PLAYING, snapshots.last().recoveryState)
    }
}

private object RecoveryTestOrderClock {
    val seq = AtomicInteger(0)
}

private class RecoveryFakePlayerBridge(
    var autoFirstFrameOnPrepare: Boolean = true,
) : WeatherVideoPlayerBridge {
    override val sessionId: String = "rec-exo-${RecoveryTestOrderClock.seq.incrementAndGet()}"
    override val identityHash: Int = System.identityHashCode(this)
    val creationOrder: Int = RecoveryTestOrderClock.seq.incrementAndGet()
    var releaseOrder: Int = Int.MAX_VALUE

    var released = false
    var idleState = true
    var currentPlayerState = "IDLE"
    var currentDecoderGen = 0
    var currentDecoderAlive = false
    var currentSurfaceEpoch = 0
    var attachedSurfaceViewId = 0

    var setMediaItemCalls = 0
    var prepareCalls = 0
    var clearSurfaceCount = 0
    var releaseCalls = 0
    var appliedUriString: String? = null
    var lastVolume: Float? = null
    var lastRepeatMode: Int? = null
    var lastPlayWhenReady: Boolean? = null
    var simulatedPositionMs: Long = 0L
    var currentSpeedValue: Float = 1.0f
    var currentVideoResHint: Int = R.raw.wx_bg_sun_day

    private var attachedView: View? = null
    private var firstFrameListener: (() -> Unit)? = null
    private var firstFrameTokenListener: ((WeatherVideoFrameToken) -> Unit)? = null
    private var decoderLifecycleListener: ((Boolean, Int, String) -> Unit)? = null
    private var playbackStateListener: ((String, Boolean) -> Unit)? = null
    private var surfaceSizeListener: ((Int, Int) -> Unit)? = null
    private var codecErrorListener: ((WeatherVideoCodecErrorDiagnostic) -> Unit)? = null

    override val isReleased: Boolean get() = released
    override val isIdle: Boolean get() = idleState
    override val playerStateName: String get() = if (released) "RELEASED" else currentPlayerState
    override val decoderGeneration: Int get() = currentDecoderGen
    override val isDecoderAlive: Boolean get() = !released && currentDecoderAlive
    override val surfaceEpoch: Int get() = currentSurfaceEpoch
    override val currentPositionMs: Long get() = simulatedPositionMs
    override val currentPlaybackSpeed: Float get() = currentSpeedValue

    override fun setVideoResIdHint(resId: Int) {
        currentVideoResHint = resId
    }

    override fun setMediaItem(uri: Uri) {
        setMediaItemCalls++
        appliedUriString = uri.toString()
    }

    override fun setVolume(volume: Float) {
        lastVolume = volume
    }

    override fun setRepeatMode(repeatMode: Int) {
        lastRepeatMode = repeatMode
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        lastPlayWhenReady = playWhenReady
    }

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeedValue = speed
    }

    override fun seekTo(positionMs: Long) {
        simulatedPositionMs = positionMs
    }

    override fun prepare() {
        prepareCalls++
        idleState = false
        currentPlayerState = "READY"
        currentDecoderGen += 1
        currentDecoderAlive = true
        decoderLifecycleListener?.invoke(true, currentDecoderGen, "c2.android.avc.decoder")
        playbackStateListener?.invoke("READY", lastPlayWhenReady == true)
        if (lastPlayWhenReady == true && autoFirstFrameOnPrepare) {
            emitFirstFrameRendered()
        }
    }

    override fun stop() {
        idleState = true
        currentDecoderAlive = false
        currentPlayerState = "IDLE"
        playbackStateListener?.invoke("IDLE", false)
    }

    override fun play() = Unit
    override fun pause() = Unit

    override fun clearVideoSurface() {
        clearSurfaceCount++
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = 0
    }

    override fun notifySurfaceReattachedForNewFrame() {
        currentSurfaceEpoch += 1
    }

    fun onBoundSurface(viewIdentity: Int) {
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = viewIdentity
    }

    fun emitFirstFrameRendered() {
        val token =
            WeatherVideoFrameToken(
                sessionId = sessionId,
                playerIdentity = identityHash,
                videoResId = currentVideoResHint,
                decoderGeneration = currentDecoderGen.coerceAtLeast(1),
                surfaceEpoch = currentSurfaceEpoch,
                surfaceViewIdentity = attachedSurfaceViewId,
                decoderAlive = currentDecoderAlive,
            )
        val cb = firstFrameTokenListener
        if (cb != null) {
            cb.invoke(token)
        } else {
            firstFrameListener?.invoke()
        }
    }

    fun emitFirstFrameRenderedWithToken(token: WeatherVideoFrameToken) {
        firstFrameTokenListener?.invoke(token)
    }

    fun simulateMediaCodecRevocation(
        errorCodeName: String = "ERROR_CODE_DECODING_FAILED",
        errorMessage: String = "MediaCodec released by resource manager (DEAD_OBJECT)",
    ) {
        val posBefore = simulatedPositionMs
        val speedBefore = currentSpeedValue
        idleState = true
        currentDecoderAlive = false
        currentPlayerState = "IDLE"
        simulatedPositionMs = 0L
        decoderLifecycleListener?.invoke(false, currentDecoderGen, "c2.android.avc.decoder")
        playbackStateListener?.invoke("IDLE", false)
        codecErrorListener?.invoke(
            WeatherVideoCodecErrorDiagnostic(
                errorCodeName = errorCodeName,
                errorClass = "MediaCodecVideoDecoderException",
                isDeadObjectOrReclaimed = true,
                positionMsAtError = posBefore,
                playbackSpeedAtError = speedBefore,
                decoderGenerationAtError = currentDecoderGen,
                message = errorMessage,
            ),
        )
    }

    override fun setFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameListener = listener
    }

    override fun setFirstFrameTokenListener(listener: ((WeatherVideoFrameToken) -> Unit)?) {
        firstFrameTokenListener = listener
    }

    override fun setDecoderLifecycleListener(
        listener: ((initialized: Boolean, decoderGeneration: Int, decoderName: String) -> Unit)?,
    ) {
        decoderLifecycleListener = listener
    }

    override fun setPlaybackStateListener(listener: ((playerStateName: String, isPlaying: Boolean) -> Unit)?) {
        playbackStateListener = listener
    }

    override fun setSurfaceSizeListener(listener: ((width: Int, height: Int) -> Unit)?) {
        surfaceSizeListener = listener
    }

    override fun setCodecErrorListener(listener: ((WeatherVideoCodecErrorDiagnostic) -> Unit)?) {
        codecErrorListener = listener
    }

    override fun attachToPlayerView(playerView: PlayerView) {
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = System.identityHashCode(playerView)
    }

    override fun isAttachedToPlayerView(playerView: PlayerView): Boolean =
        attachedView === playerView

    override fun releaseOnce(): Boolean {
        if (released) return false
        released = true
        currentDecoderAlive = false
        releaseCalls++
        releaseOrder = RecoveryTestOrderClock.seq.incrementAndGet()
        return true
    }
}

private class RecoveryFakeSurfaceBridge(context: Context) : WeatherVideoSurfaceBridge {
    override val view: View = View(context)
    override val viewIdentity: Int = System.identityHashCode(view)
    override val viewClassName: String = "RecoveryFakePlayerView"
    private var boundPlayer: WeatherVideoPlayerBridge? = null
    private var attachObserver: ((Boolean, ExoSurfaceSnapshot) -> Unit)? = null
    private var textureAvailable = true
    private var hasSurfaceTexture = true

    override fun attachPlayer(player: WeatherVideoPlayerBridge) {
        boundPlayer = player
        if (player is RecoveryFakePlayerBridge) {
            player.onBoundSurface(viewIdentity)
        }
    }

    override fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean =
        boundPlayer === player

    override fun detachPlayer() {
        boundPlayer = null
    }

    override fun inspectSurfaceSnapshot(): ExoSurfaceSnapshot =
        ExoSurfaceSnapshot(
            surfaceViewClass = "TextureView",
            textureAvailable = textureAvailable,
            hasSurfaceTexture = hasSurfaceTexture,
        )

    override fun attachWindowObserver(
        onAttachStateChanged: (attached: Boolean, snapshot: ExoSurfaceSnapshot) -> Unit,
    ): () -> Unit {
        attachObserver = onAttachStateChanged
        return { attachObserver = null }
    }

    fun emitSurfaceAvailability(
        attached: Boolean,
        textureAvailable: Boolean,
        hasSurfaceTexture: Boolean,
    ) {
        this.textureAvailable = textureAvailable
        this.hasSurfaceTexture = hasSurfaceTexture
        attachObserver?.invoke(attached, inspectSurfaceSnapshot())
    }
}

private class RecoveryFakeMapPort(context: Context) : MapViewSurfacePort {
    override val androidView: View = View(context)
    override val viewIdentity: Int = System.identityHashCode(androidView)
    override val viewClassName: String = "RecoveryFakeMapView"
    private var windowObserver: ((Boolean, String) -> Unit)? = null

    override fun onCreate() = Unit
    override fun onStart() = Unit
    override fun onResume() = Unit
    override fun onPause() = Unit
    override fun onStop() = Unit
    override fun onDestroy() = Unit
    override fun onLowMemory() = Unit
    override fun inspectSurfaceInfo(): String = "FakeMapView=1080x900"
    override fun postToUi(action: () -> Unit) = action()

    override fun attachWindowObserver(onAttachStateChanged: (Boolean, String) -> Unit): () -> Unit {
        windowObserver = onAttachStateChanged
        return { windowObserver = null }
    }

    override fun attachListeners(callbacks: MapSurfaceCallbacks): () -> Unit = {}

    override fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit) {
        onReady(
            object : MapLibreControllerPort {
                override fun configureUiSettings() = Unit
                override fun setInitialCamera(latitude: Double, longitude: Double, zoom: Double) = Unit
                override fun animateCamera(latitude: Double, longitude: Double, zoom: Double, durationMs: Int) = Unit
                override fun cancelTransitions() = Unit
                override fun setStyle(request: MapStyleSpecRequest, onLoaded: (MapStylePort) -> Unit) {
                    onLoaded(
                        object : MapStylePort {
                            override val isFullyLoaded: Boolean = true
                            override fun updateGeoJsonSource(sourceId: String, geoJson: String) = Unit
                        },
                    )
                }
                override fun setOnMapClick(listener: (CityMapCoordinate) -> Unit) = Unit
                override fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit) = Unit
                override fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult =
                    LocationComponentSyncResult.PERMISSION_DENIED
            },
        )
    }
}
