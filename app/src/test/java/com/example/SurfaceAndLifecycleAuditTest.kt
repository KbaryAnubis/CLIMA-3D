package com.example

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.RainViewerRadarFrame
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.diagnostics.GraphicsSurfaceCoordinator
import com.example.diagnostics.MapSurfaceSessionState
import com.example.diagnostics.VideoSurfaceOutputState
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapLibreSectionImpl
import com.example.maps.CityMapLibreViewImpl
import com.example.maps.CityMapMarker
import com.example.maps.CityMapUiState
import com.example.maps.LocationComponentSyncResult
import com.example.maps.MapLibreControllerPort
import com.example.maps.MapStylePort
import com.example.maps.MapStyleSpecRequest
import com.example.maps.MapSurfaceCallbacks
import com.example.maps.MapViewLifecycleController
import com.example.maps.MapViewSessionHandle
import com.example.maps.MapViewSurfacePort
import com.example.maps.RadarBaseStyle
import com.example.maps.RadarMapLibreViewImpl
import com.example.maps.TileTransportDiagnostics
import com.example.media.WeatherCondition
import com.example.ui.components.WeatherBackgroundLayerImpl
import com.example.ui.components.WeatherVideoBackgroundImpl
import com.example.ui.components.WeatherVideoPlayerBridge
import com.example.ui.components.WeatherVideoSurfaceBridge
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
class SurfaceAndLifecycleAuditTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        TileTransportDiagnostics.reset()
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = true
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
    }

    @After
    fun teardown() {
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = null
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
    }

    // 1. MapView não é recriada por recomposição simples
    @Test
    fun `test_01_map_view_not_recreated_by_simple_recomposition`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var cityState by mutableStateOf(CityMapUiState())
        var radarTick by mutableIntStateOf(0)
        var screenMode by mutableStateOf("cities")

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (screenMode == "cities") {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text("recompose-$radarTick")
                        CityMapLibreViewImpl(
                            state = cityState,
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx ->
                                FakeMapViewSurfacePort(ctx).also { createdPorts += it }
                            },
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text("radar-recompose-$radarTick")
                        RadarMapLibreViewImpl(
                            center = CityMapCoordinate(-23.55, -46.63),
                            zoom = 6.0,
                            activeFrame = sampleRadarFrame(1700000000L + radarTick * 600L),
                            layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                            maxRadarZoom = 7,
                            surfacePortFactory = { ctx ->
                                FakeMapViewSurfacePort(ctx).also { createdPorts += it }
                            },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, createdPorts.size)

        // Trigger multiple recompositions on Cities without viewRecreationNonce changing
        repeat(4) { step ->
            composeTestRule.runOnUiThread {
                radarTick++
                cityState = cityState.copy(
                    savedMarkers = listOf(
                        CityMapMarker(
                            id = "m-$step",
                            label = "Cidade $step",
                            coordinate = CityMapCoordinate(-23.5 + step * 0.1, -46.6),
                        ),
                    ),
                )
            }
            composeTestRule.waitForIdle()
        }
        assertEquals("Cities MapView must not recreate across recompositions", 1, createdPorts.size)

        // Switch to Radar and trigger multiple recompositions
        composeTestRule.runOnUiThread { screenMode = "radar" }
        composeTestRule.waitForIdle()
        assertEquals(2, createdPorts.size)

        repeat(4) {
            composeTestRule.runOnUiThread { radarTick++ }
            composeTestRule.waitForIdle()
        }
        assertEquals("Radar MapView must not recreate across recompositions", 2, createdPorts.size)
    }

    // 2. MapView é destruída/fechada apenas uma vez e respeita ordem pause -> stop -> detach -> destroy
    @Test
    fun `test_02_map_view_destroyed_only_once_in_ordered_teardown`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (mounted) {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx ->
                            FakeMapViewSurfacePort(ctx).also { createdPorts += it }
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val port = createdPorts.single()
        assertEquals(1, port.createCount)
        assertEquals(0, port.destroyCount)

        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        assertEquals("MapView.onDestroy must run exactly once", 1, port.destroyCount)
        assertEquals(1, port.pauseCount)
        assertEquals(1, port.stopCount)
        assertEquals(1, port.listenerDetachCount)

        val pauseIdx = port.events.indexOf("onPause")
        val stopIdx = port.events.indexOf("onStop")
        val destroyIdx = port.events.indexOf("onDestroy")
        assertTrue(pauseIdx >= 0 && stopIdx > pauseIdx && destroyIdx > stopIdx)

        // Direct session handle double-destroy must remain idempotent
        val extraDestroyCalls = AtomicInteger(0)
        val controller = MapViewLifecycleController(
            onCreate = {},
            onStart = {},
            onResume = {},
            onPause = {},
            onStop = {},
            onDestroy = { extraDestroyCalls.incrementAndGet() },
            onLowMemory = {},
        )
        controller.ensureCreated()
        val handle = MapViewSessionHandle(
            screenName = "Radar",
            sessionId = "s-1",
            surfacePort = port,
            controller = controller,
            onDetachListeners = {},
            onCancelTransitions = {},
        )
        handle.destroyOnceAfterDetach()
        handle.destroyOnceAfterDetach()
        assertEquals(1, extraDestroyCalls.get())
    }

    // 3. Callbacks tardios são descartados após dispose
    @Test
    fun `test_03_late_callbacks_discarded_after_dispose`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var tapCount = 0
        var cameraIdleCount = 0
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (mounted) {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = { tapCount++ },
                        onCameraGestureSettled = { _, _ -> cameraIdleCount++ },
                        surfacePortFactory = { ctx ->
                            FakeMapViewSurfacePort(ctx, autoReady = false).also { createdPorts += it }
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val port = createdPorts.single()

        // Capture pending callbacks before disposal
        val capturedReady = port.pendingMapReadyCallback!!
        val capturedCallbacks = port.attachedCallbacks!!

        // Dispose composition BEFORE getMapAsync / listeners fire
        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        // Fire late callbacks AFTER dispose — none must crash or update tap/camera state
        composeTestRule.runOnUiThread {
            capturedReady(port.controllerPort)
            capturedCallbacks.onDidFinishLoadingStyle()
            capturedCallbacks.onDidFinishLoadingMap()
            capturedCallbacks.onFirstFrameRendered()
            capturedCallbacks.onDidFinishRenderingMap(true)
            capturedCallbacks.onRenderError("late error")
            port.flushPendingUiPosts()
            port.controllerPort.simulateMapTap(CityMapCoordinate(-23.5, -46.6))
            port.controllerPort.simulateCameraIdle(CityMapCoordinate(-23.5, -46.6), 12.0)
        }
        composeTestRule.waitForIdle()

        assertEquals(0, port.controllerPort.setStyleCalls)
        assertEquals(0, tapCount)
        assertEquals(0, cameraIdleCount)
    }

    // 4. Não há eventos de ciclo de vida duplicados
    @Test
    fun `test_04_no_duplicate_lifecycle_events`() {
        var lifecycleOwner by mutableStateOf(TestLifecycleOwner(Lifecycle.State.CREATED))
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_sun_day,
                    playbackSpeed = 1.0f,
                    isPlaying = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val initialPlayCalls = player.playCalls

        // Normal Android startup: CREATED -> STARTED -> RESUMED must not fire redundant play()
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeTestRule.waitForIdle()
        assertEquals(initialPlayCalls, player.playCalls)

        // Swapping LifecycleOwner while RESUMED replays ON_START/ON_RESUME on the new owner -> must not duplicate play() or release player
        composeTestRule.runOnUiThread {
            lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        }
        composeTestRule.waitForIdle()
        assertEquals(initialPlayCalls, player.playCalls)
        assertEquals(0, player.releaseCalls)

        // Pause + Stop -> pause() should fire once, not twice
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, player.pauseCalls)

        // Start + Resume -> play() should fire once, not twice
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeTestRule.waitForIdle()
        assertEquals(initialPlayCalls + 1, player.playCalls)
    }

    // 5. ExoPlayer não é duplicado durante recomposições
    @Test
    fun `test_05_exoplayer_not_duplicated_during_recompositions`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var recomposeTick by mutableIntStateOf(0)

        composeTestRule.setContent {
            Column {
                Text("tick-$recomposeTick")
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_cloudy_day,
                    playbackSpeed = 1.0f,
                    isPlaying = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(1, surfaces.size)

        repeat(5) {
            composeTestRule.runOnUiThread { recomposeTick++ }
            composeTestRule.waitForIdle()
        }

        val player = players.single()
        assertEquals(1, players.size)
        assertEquals(1, surfaces.size)
        assertEquals(1, player.setMediaItemCalls)
        assertEquals(1, player.prepareCalls)
        assertEquals(0, player.stopCalls)
    }

    // 6. Player não é liberado repetidamente e superfície é limpa antes do release()
    @Test
    fun `test_06_player_not_released_repeatedly_and_clears_surface_before_release`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            if (mounted) {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_rain_day,
                    playbackSpeed = 1.0f,
                    isPlaying = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()

        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        assertEquals(1, player.releaseCalls)
        assertFalse(player.releaseOnce())
        assertEquals(1, player.releaseCalls)
        assertTrue(surface.detachCount >= 1)
        assertTrue(player.clearSurfaceCount >= 1)

        val clearIdx = player.events.indexOf("clearVideoSurface")
        val releaseIdx = player.events.indexOf("release")
        assertTrue("clearVideoSurface must run before release", clearIdx in 0 until releaseIdx)
        assertTrue("Surface detach must run before player release", surface.firstDetachOrder < player.releaseOrder)
    }

    // 7. Mudança de playbackSpeed sem recriar player nem resetar setMediaItem/prepare
    @Test
    fun `test_07_playback_speed_change_does_not_recreate_player_or_reset_media`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var speed by mutableStateOf(1.0f)

        composeTestRule.setContent {
            WeatherVideoBackgroundImpl(
                videoResId = R.raw.wx_bg_rain_day,
                playbackSpeed = speed,
                isPlaying = true,
                playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { speed = 0.85f }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { speed = 1.25f }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1, players.size)
        assertEquals(1, player.setMediaItemCalls)
        assertEquals(1, player.prepareCalls)
        assertEquals(0, player.stopCalls)
        assertEquals(0, player.releaseCalls)
        assertEquals(listOf(1.0f, 0.85f, 1.25f), player.speedHistory)
    }

    // 8. Pausa de vídeo nas abas Radar e Cidades (e retomada ao voltar às abas meteorológicas)
    @Test
    fun `test_08_video_pauses_on_radar_and_cities_and_resumes_on_home`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        assertEquals(1, players.size)
        assertTrue(player.lastPlayWhenReady == true)

        // Switch to RADAR -> pauses without releasing
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(0, player.releaseCalls)
        assertEquals(1, player.pauseCalls)
        assertEquals(false, player.lastPlayWhenReady)

        // Switch to CITIES -> remains paused without recreating
        composeTestRule.runOnUiThread { activeTab = ClimaTab.CITIES }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(0, player.releaseCalls)
        assertEquals(false, player.lastPlayWhenReady)

        // Return to HOME -> resumes existing player cleanly
        val playsBeforeResume = player.playCalls
        composeTestRule.runOnUiThread { activeTab = ClimaTab.HOME }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(0, player.releaseCalls)
        assertTrue(player.playCalls > playsBeforeResume)
        assertEquals(true, player.lastPlayWhenReady)
    }

    // 9. Navegação entre abas sem acúmulo de sessões ativas
    @Test
    fun `test_09_tab_navigation_does_not_accumulate_map_sessions`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                when (activeTab) {
                    ClimaTab.RADAR ->
                        RadarMapLibreViewImpl(
                            center = CityMapCoordinate(-23.55, -46.63),
                            zoom = 6.0,
                            activeFrame = sampleRadarFrame(1700000000L),
                            layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                            maxRadarZoom = 7,
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                        )
                    ClimaTab.CITIES ->
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                        )
                    else -> Text("tab-${activeTab.name}")
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(0, createdPorts.size)

        val sequence = listOf(
            ClimaTab.RADAR,
            ClimaTab.CITIES,
            ClimaTab.HOME,
            ClimaTab.RADAR,
            ClimaTab.SETTINGS,
            ClimaTab.CITIES,
            ClimaTab.HOME,
        )
        sequence.forEach { tab ->
            composeTestRule.runOnUiThread { activeTab = tab }
            composeTestRule.waitForIdle()
            val activeSessions = createdPorts.count { it.destroyCount == 0 }
            val expectedActive = if (tab == ClimaTab.RADAR || tab == ClimaTab.CITIES) 1 else 0
            assertEquals("Active map sessions on tab $tab must be $expectedActive", expectedActive, activeSessions)
        }

        assertEquals(4, createdPorts.size)
        assertEquals(4, createdPorts.count { it.destroyCount == 1 })
    }

    // 10. Atualização de estado meteorológico sem destruir superfície do mapa ou do player
    @Test
    fun `test_10_meteorological_updates_preserve_map_and_player_surfaces`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var condition by mutableStateOf(WeatherCondition.CHUVA)
        val frames = listOf(sampleRadarFrame(1700000000L), sampleRadarFrame(1700000600L), sampleRadarFrame(1700001200L))
        var activeFrameIdx by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = condition,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        modifier = Modifier.weight(1f),
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    )
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = 6.0,
                        frames = frames,
                        activeFrame = frames[activeFrameIdx],
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        modifier = Modifier.weight(1f),
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Weather severity changes sharing the same background MP4 (`wx_bg_rain_day`) + radar frame tick
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA_FRACA
            activeFrameIdx = 1
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA
            activeFrameIdx = 2
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val port = createdPorts.single()
        assertEquals(1, players.size)
        assertEquals(0, player.releaseCalls)
        assertEquals(1, player.setMediaItemCalls)
        assertEquals(1, createdPorts.size)
        assertEquals(0, port.destroyCount)
        assertEquals(1, port.controllerPort.setStyleCalls)
    }

    // 11. Controles de zoom sem recriação da MapView
    @Test
    fun `test_11_zoom_controls_animate_camera_without_recreating_map_view`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var radarZoom by mutableStateOf(6.0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSectionImpl(
                        initialState = CityMapUiState(zoom = 10.0),
                        isOnline = true,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = radarZoom,
                        activeFrame = sampleRadarFrame(1700000000L),
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(2, createdPorts.size)
        val citiesPort = createdPorts[0]
        val radarPort = createdPorts[1]

        // Click Cities + and - zoom controls
        composeTestRule.onNodeWithTag("city_map_zoom_in").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_zoom_out").performClick()
        composeTestRule.waitForIdle()

        // Update Radar zoom
        composeTestRule.runOnUiThread { radarZoom = 7.0 }
        composeTestRule.waitForIdle()

        assertEquals(2, createdPorts.size)
        assertEquals(0, citiesPort.destroyCount)
        assertEquals(0, radarPort.destroyCount)
        assertEquals(listOf(11.0, 10.0), citiesPort.controllerPort.animatedZooms)
        assertTrue(radarPort.controllerPort.animatedZooms.contains(7.0))
    }

    // 12. Cancelamento seguro de transições de câmera ao encerrar/trocar de aba
    @Test
    fun `test_12_camera_transitions_canceled_safely_before_destroy`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var state by mutableStateOf(CityMapUiState(zoom = 10.0))
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (mounted) {
                    CityMapLibreViewImpl(
                        state = state,
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val port = createdPorts.single()

        // Start a camera transition and immediately dispose
        composeTestRule.runOnUiThread {
            state = state.zoomIn()
        }
        composeTestRule.waitForIdle()
        assertEquals(1, port.controllerPort.animatedZooms.size)

        composeTestRule.runOnUiThread {
            mounted = false
        }
        composeTestRule.waitForIdle()

        assertTrue(port.controllerPort.cancelTransitionsCalls >= 1)
        val cancelIdx = port.events.indexOf("cancelTransitions")
        val destroyIdx = port.events.indexOf("onDestroy")
        assertTrue("cancelTransitions must run before onDestroy", cancelIdx in 0 until destroyIdx)

        // Verify BuildConfig.DEBUG milestone diagnostics logged bounded session milestones
        val logged = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logged.any { it.contains("MAP_CREATE") })
        assertTrue(logged.any { it.contains("MAP_DISPOSE_REQ") })
        assertTrue(logged.any { it.contains("MAP_DESTROYED") })
    }

    // 13. Sessões MapLibre possuem sessionId único e identidade distinta
    @Test
    fun `test_13_maplibre_sessions_have_unique_session_id_and_identity`() {
        val ports = mutableListOf<FakeMapViewSurfacePort>()
        var screenMode by mutableStateOf("cities")

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (screenMode == "cities") {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { ports += it } },
                    )
                } else {
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = 6.0,
                        activeFrame = sampleRadarFrame(1700000000L),
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { ports += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { screenMode = "radar" }
        composeTestRule.waitForIdle()

        val creates = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().filter { it.contains("MAP_CREATE") }
        assertEquals(2, creates.size)
        val sessions = creates.mapNotNull { extractToken(it, "session=") }.toSet()
        val viewIds = creates.mapNotNull { extractToken(it, "viewId=") }.toSet()
        assertEquals(2, sessions.size)
        assertEquals(2, viewIds.size)
    }

    // 14. Sessões ExoPlayer possuem sessionId único e identidade distinta
    @Test
    fun `test_14_exoplayer_sessions_have_unique_session_id_and_identity`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var showFirst by mutableStateOf(true)

        composeTestRule.setContent {
            if (showFirst) {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_sun_day,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            } else {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_rain_day,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { showFirst = false }
        composeTestRule.waitForIdle()

        val creates = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().filter { it.contains("EXO_CREATE") }
        assertEquals(2, creates.size)
        val sessions = creates.mapNotNull { extractToken(it, "session=") }.toSet()
        val playerIds = creates.mapNotNull { extractToken(it, "playerId=") }.toSet()
        assertEquals(2, sessions.size)
        assertEquals(2, playerIds.size)
    }

    // 15. Attach e detach de janela para MapView e ExoPlayer contêm metadados completos de correlação
    @Test
    fun `test_15_ordered_window_attach_and_detach_logging_with_correlation_fields`() {
        val ports = mutableListOf<FakeMapViewSurfacePort>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var mounted by mutableStateOf(true)

        ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.RADAR)

        composeTestRule.setContent {
            if (mounted) {
                Column {
                    WeatherVideoBackgroundImpl(
                        videoResId = R.raw.wx_bg_sun_day,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge() },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = 6.0,
                        activeFrame = sampleRadarFrame(1700000000L),
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { ports += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val mapPort = ports.single()
        val exoSurface = surfaces.single()
        composeTestRule.runOnUiThread {
            mapPort.emitAttach(true)
            exoSurface.emitAttach(true)
            mapPort.emitAttach(false)
            exoSurface.emitAttach(false)
        }
        composeTestRule.waitForIdle()

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        val mapAttachIdx = logs.indexOfFirst { it.contains("MAP_VIEW_ATTACH") }
        val mapDetachIdx = logs.indexOfFirst { it.contains("MAP_VIEW_DETACH") }
        val exoAttachIdx = logs.indexOfFirst { it.contains("EXO_VIEW_ATTACH") }
        val exoDetachIdx = logs.indexOfFirst { it.contains("EXO_VIEW_DETACH") }

        assertTrue(mapAttachIdx >= 0 && mapDetachIdx > mapAttachIdx)
        assertTrue(exoAttachIdx >= 0 && exoDetachIdx > exoAttachIdx)

        listOf(logs[mapAttachIdx], logs[mapDetachIdx], logs[exoAttachIdx], logs[exoDetachIdx]).forEach { line ->
            assertTrue("Line missing wall timestamp: $line", line.contains("wall="))
            assertTrue("Line missing monoNs: $line", line.contains("monoNs="))
            assertTrue("Line missing pid: $line", line.contains("pid="))
            assertTrue("Line missing tab=RADAR: $line", line.contains("tab=RADAR"))
            assertTrue("Line missing thread: $line", line.contains("thread="))
        }
    }

    // 16. MAP_DISPOSE_REQ é registrado antes de MAP_DESTROYED para a mesma sessão
    @Test
    fun `test_16_map_dispose_req_logged_before_map_destroyed`() {
        var mounted by mutableStateOf(true)
        composeTestRule.setContent {
            if (mounted) {
                RadarMapLibreViewImpl(
                    center = CityMapCoordinate(-23.55, -46.63),
                    zoom = 6.0,
                    activeFrame = sampleRadarFrame(1700000000L),
                    layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                    maxRadarZoom = 7,
                    surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        val createLine = logs.first { it.contains("MAP_CREATE") }
        val sessionId = extractToken(createLine, "session=")!!
        val disposeReqIdx = logs.indexOfFirst { it.contains("MAP_DISPOSE_REQ") && it.contains("session=$sessionId") }
        val destroyedIdx = logs.indexOfFirst { it.contains("MAP_DESTROYED") && it.contains("session=$sessionId") }

        assertTrue(disposeReqIdx >= 0 && destroyedIdx > disposeReqIdx)
    }

    // 17. EXO_SURFACE_CLEAR precede EXO_RELEASED único e releaseOnce permanece idempotente
    @Test
    fun `test_17_exoplayer_single_idempotent_release_after_detach_and_clear_surface`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            if (mounted) {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_sun_day,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()

        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        assertFalse(player.releaseOnce())
        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        val sessionToken = "session=${player.sessionId}"
        val clearIdx = logs.indexOfFirst { it.contains("EXO_SURFACE_CLEAR") && it.contains(sessionToken) }
        val releasedLines = logs.withIndex().filter { it.value.contains("EXO_RELEASED") && it.value.contains(sessionToken) }

        assertEquals(1, releasedLines.size)
        assertTrue(clearIdx in 0 until releasedLines.single().index)
    }

    // 18. Callbacks tardios são descartados após dispose no Radar e em Cidades
    @Test
    fun `test_18_late_callbacks_discarded_after_dispose_in_both_radar_and_cities`() {
        val ports = mutableListOf<FakeMapViewSurfacePort>()
        var mounted by mutableStateOf(true)
        var cityTapCount = 0

        composeTestRule.setContent {
            if (mounted) {
                Column {
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = 6.0,
                        activeFrame = sampleRadarFrame(1700000000L),
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx, autoReady = false).also { ports += it } },
                    )
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = { cityTapCount++ },
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx, autoReady = false).also { ports += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val radarPort = ports[0]
        val cityPort = ports[1]
        val radarReadyCb = radarPort.pendingMapReadyCallback!!
        val radarCallbacks = radarPort.attachedCallbacks!!
        val cityReadyCb = cityPort.pendingMapReadyCallback!!
        val cityCallbacks = cityPort.attachedCallbacks!!

        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread {
            radarReadyCb(radarPort.controllerPort)
            radarCallbacks.onDidFinishLoadingStyle()
            radarCallbacks.onFirstFrameRendered()
            radarCallbacks.onRenderError("late radar failure")
            radarPort.flushPendingUiPosts()

            cityReadyCb(cityPort.controllerPort)
            cityCallbacks.onDidFinishLoadingStyle()
            cityCallbacks.onFirstFrameRendered()
            cityCallbacks.onRenderError("late city failure")
            cityPort.flushPendingUiPosts()
            cityPort.controllerPort.simulateMapTap(CityMapCoordinate(-23.5, -46.6))
        }
        composeTestRule.waitForIdle()

        assertEquals(0, radarPort.controllerPort.setStyleCalls)
        assertEquals(0, cityPort.controllerPort.setStyleCalls)
        assertEquals(0, cityTapCount)
    }

    // 19. destroyOnceAfterDetach é idempotente em múltiplas chamadas
    @Test
    fun `test_19_idempotent_mapview_destroy_once_after_detach`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val port = FakeMapViewSurfacePort(context)
        val pauseCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val destroyCalls = AtomicInteger(0)
        val detachListenersCalls = AtomicInteger(0)
        val detachWindowCalls = AtomicInteger(0)

        val controller =
            MapViewLifecycleController(
                onCreate = {},
                onStart = {},
                onResume = {},
                onPause = { pauseCalls.incrementAndGet() },
                onStop = { stopCalls.incrementAndGet() },
                onDestroy = { destroyCalls.incrementAndGet() },
                onLowMemory = {},
            )
        controller.ensureCreated()
        controller.syncToLifecycleState(Lifecycle.State.RESUMED)

        val handle =
            MapViewSessionHandle(
                screenName = "Cities",
                sessionId = "map-idempotent-19",
                surfacePort = port,
                controller = controller,
                onDetachListeners = { detachListenersCalls.incrementAndGet() },
                onCancelTransitions = {},
                onDetachWindowObserver = { detachWindowCalls.incrementAndGet() },
            )

        handle.destroyOnceAfterDetach()
        handle.destroyOnceAfterDetach()
        handle.destroyOnceAfterDetach()

        assertEquals(1, pauseCalls.get())
        assertEquals(1, stopCalls.get())
        assertEquals(1, detachListenersCalls.get())
        assertEquals(1, detachWindowCalls.get())
        assertEquals(1, destroyCalls.get())
        assertEquals(
            1,
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count {
                it.contains("MAP_DESTROYED") && it.contains("session=map-idempotent-19")
            },
        )
    }

    // 20. Diagnósticos permanecem desativados fora de BuildConfig.DEBUG
    @Test
    fun `test_20_diagnostics_completely_disabled_when_not_debug`() {
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = false
        ClimaGraphicsLifecycleDiagnostics.clearForTest()

        ClimaGraphicsLifecycleDiagnostics.logMapViewCreated("Radar", "map-off", 101, 1)
        ClimaGraphicsLifecycleDiagnostics.logExoPlayerCreated("exo-off", 202, R.raw.wx_bg_sun_day)
        ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "OPEN")
        ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.RADAR)

        assertFalse(ClimaGraphicsLifecycleDiagnostics.isEnabled())
        assertTrue(ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().isEmpty())
    }

    // 21. Ausência de log por frame ou por recomposição (buffer limitado e sem spam)
    @Test
    fun `test_21_no_per_frame_or_per_recomposition_log_spam`() {
        var frameIdx by mutableIntStateOf(0)
        var recomposeTick by mutableIntStateOf(0)
        val frames = List(9) { i -> sampleRadarFrame(1700000000L + i * 600L) }

        composeTestRule.setContent {
            Column {
                Text("recompose-$recomposeTick")
                RadarMapLibreViewImpl(
                    center = CityMapCoordinate(-23.55, -46.63),
                    zoom = 6.0,
                    frames = frames,
                    activeFrame = frames[frameIdx],
                    layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                    maxRadarZoom = 7,
                    surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val countAfterInit = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().size

        // Simulate 15 animated radar frames + 15 Compose recompositions
        repeat(15) { step ->
            composeTestRule.runOnUiThread {
                frameIdx = (frameIdx + 1) % frames.size
                recomposeTick = step + 1
            }
            composeTestRule.waitForIdle()
        }

        val logsAfterTicks = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertEquals("Ticks and recompositions must emit zero extra lifecycle log entries", countAfterInit, logsAfterTicks.size)
        assertTrue(logsAfterTicks.size <= 128)
    }

    // 22. Navegação repetida entre Home, Radar, Cidades e Ajustes não acumula mapas nem players ativos
    @Test
    fun `test_22_multi_tab_navigation_no_active_map_or_player_accumulation`() {
        val createdMapPorts = mutableListOf<FakeMapViewSurfacePort>()
        val createdPlayers = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL_NUVENS,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { createdPlayers += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    )
                    when (activeTab) {
                        ClimaTab.RADAR ->
                            RadarMapLibreViewImpl(
                                center = CityMapCoordinate(-23.55, -46.63),
                                zoom = 6.0,
                                activeFrame = sampleRadarFrame(1700000000L),
                                layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                                maxRadarZoom = 7,
                                surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdMapPorts += it } },
                            )

                        ClimaTab.CITIES ->
                            CityMapLibreViewImpl(
                                state = CityMapUiState(),
                                onMapTap = {},
                                onCameraGestureSettled = { _, _ -> },
                                surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdMapPorts += it } },
                            )

                        else -> Text("tab-${activeTab.name}")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val routeSequence =
            listOf(
                ClimaTab.RADAR,
                ClimaTab.CITIES,
                ClimaTab.SETTINGS,
                ClimaTab.HOME,
                ClimaTab.RADAR,
                ClimaTab.CITIES,
                ClimaTab.HOME,
            )
        routeSequence.forEach { next ->
            val prev = activeTab
            composeTestRule.runOnUiThread {
                ClimaGraphicsLifecycleDiagnostics.logTabChanged(prev, next)
                activeTab = next
            }
            composeTestRule.waitForIdle()
            val activeMaps = createdMapPorts.count { it.destroyCount == 0 }
            val expectedActive = if (next == ClimaTab.RADAR || next == ClimaTab.CITIES) 1 else 0
            assertEquals(expectedActive, activeMaps)
        }

        assertEquals(1, createdPlayers.size)
        assertEquals(0, createdPlayers.single().releaseCalls)
        assertEquals(4, createdMapPorts.size)
        assertEquals(4, createdMapPorts.count { it.destroyCount == 1 })
    }

    // 23. Etapa 3J (1): Player é pausado ao entrar em RADAR
    @Test
    fun `test_23_player_paused_when_entering_radar`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        assertEquals(true, player.lastPlayWhenReady)

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.RADAR)
            activeTab = ClimaTab.RADAR
        }
        composeTestRule.waitForIdle()

        assertEquals(false, player.lastPlayWhenReady)
        assertEquals(1, player.pauseCalls)
        assertEquals(0, player.releaseCalls)
    }

    // 24. Etapa 3J (2): Player é pausado ao entrar em CITIES
    @Test
    fun `test_24_player_paused_when_entering_cities`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        assertEquals(true, player.lastPlayWhenReady)

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.CITIES)
            activeTab = ClimaTab.CITIES
        }
        composeTestRule.waitForIdle()

        assertEquals(false, player.lastPlayWhenReady)
        assertEquals(1, player.pauseCalls)
        assertEquals(0, player.releaseCalls)
    }

    // 25. Etapa 3J (3): Saída de vídeo é desassociada (detachPlayer + clearVideoSurface) ao entrar em abas de mapa
    @Test
    fun `test_25_video_output_disassociated_and_cleared_when_entering_map_tabs`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL_NUVENS,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.RADAR)
            activeTab = ClimaTab.RADAR
        }
        composeTestRule.waitForIdle()

        assertFalse("PlayerView must be detached from ExoPlayer on RADAR", surface.isPlayerAttached(player))
        assertEquals(1, surface.detachCount)
        assertEquals(1, player.clearSurfaceCount)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logs.any { it.contains("VIDEO_OUTPUT_DETACH_FOR_MAP") })
        assertTrue(logs.any { it.contains("VIDEO_OUTPUT_DETACHED") })
    }

    // 26. Etapa 3J (4): Player permanece em memória e não é liberado ao trocar de aba
    @Test
    fun `test_26_player_stays_in_memory_and_is_not_released_across_tab_switches`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.TEMPESTADE,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val originalPlayer = players.single()

        listOf(
            ClimaTab.RADAR,
            ClimaTab.CITIES,
            ClimaTab.SETTINGS,
            ClimaTab.ALERTS,
            ClimaTab.RADAR,
            ClimaTab.HOME,
        ).forEach { tab ->
            composeTestRule.runOnUiThread { activeTab = tab }
            composeTestRule.waitForIdle()
        }

        assertEquals("Only a single ExoPlayer instance may exist", 1, players.size)
        assertTrue(players.single() === originalPlayer)
        assertEquals(0, originalPlayer.releaseCalls)
        assertFalse(originalPlayer.isReleased)
        assertEquals(1, originalPlayer.setMediaItemCalls)
        assertEquals(1, originalPlayer.prepareCalls)
        assertEquals(0, originalPlayer.stopCalls)
    }

    // 27. Etapa 3J (5): Posição de reprodução é preservada após desanexar e reanexar saída de vídeo
    @Test
    fun `test_27_playback_position_preserved_across_map_detach_and_reattach`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge().apply { simulatedPositionMs = 4_320L }.also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertEquals(4_320L, player.currentPositionMs)

        // Enter RADAR (detach output) -> position must remain 4320ms
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(4_320L, player.currentPositionMs)
        assertEquals(0, player.stopCalls)

        // Return to HOME (reattach output) -> position still 4320ms without re-preparing media
        composeTestRule.runOnUiThread { activeTab = ClimaTab.HOME }
        composeTestRule.waitForIdle()
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(4_320L, player.currentPositionMs)
        assertEquals(1, player.setMediaItemCalls)
        assertEquals(1, player.prepareCalls)
    }

    // 28. Etapa 3J (6): PlaybackSpeed (ex.: 0.85f para CHUVA_FRACA) é preservado após desanexar e reanexar
    @Test
    fun `test_28_playback_speed_preserved_across_map_detach_and_reattach`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA_FRACA,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        composeTestRule.runOnUiThread { activeTab = ClimaTab.CITIES }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        composeTestRule.runOnUiThread { activeTab = ClimaTab.HOME }
        composeTestRule.waitForIdle()
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)
        assertEquals(listOf(0.85f), player.speedHistory)
    }

    // 29. Etapa 3J (7): Saída de vídeo não é anexada enquanto houver sessão MapLibre ativa ou em descarte
    @Test
    fun `test_29_video_output_not_attached_while_maplibre_session_active_or_disposing`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val manualMapPort = FakeMapViewSurfacePort(context)
        val manualController =
            MapViewLifecycleController(
                onCreate = { manualMapPort.onCreate() },
                onStart = { manualMapPort.onStart() },
                onResume = { manualMapPort.onResume() },
                onPause = { manualMapPort.onPause() },
                onStop = { manualMapPort.onStop() },
                onDestroy = { manualMapPort.onDestroy() },
                onLowMemory = { manualMapPort.onLowMemory() },
            )
        manualController.ensureCreated()

        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.RADAR)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()

        // Create an active MapLibre session handle while on RADAR
        val mapHandle =
            MapViewSessionHandle(
                screenName = "Radar",
                sessionId = "radar-manual-29",
                surfacePort = manualMapPort,
                controller = manualController,
                onDetachListeners = {},
                onCancelTransitions = {},
            )
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertFalse(surface.isPlayerAttached(player))

        // User switches tab to HOME, while MapView is transitioning to MAP_SURFACE_DISPOSING (phase 1)
        composeTestRule.runOnUiThread {
            mapHandle.pauseAndStopBeforeDetach("onDispose")
            activeTab = ClimaTab.HOME
        }
        composeTestRule.waitForIdle()

        assertEquals(MapSurfaceSessionState.MAP_SURFACE_DISPOSING, GraphicsSurfaceCoordinator.state.value.mapState)
        assertFalse(
            "Video surface MUST NOT attach while MapView session is still MAP_SURFACE_DISPOSING",
            surface.isPlayerAttached(player),
        )
        assertTrue(
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().any {
                it.contains("VIDEO_OUTPUT_REATTACH_REQUEST") && it.contains("canAttachNow=false")
            },
        )

        // Phase 2 completes: MapView finishes detach & destroyOnceAfterDetach -> NO_ACTIVE_MAP_SURFACE
        composeTestRule.runOnUiThread {
            mapHandle.destroyOnceAfterDetach()
        }
        composeTestRule.waitForIdle()

        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertTrue(
            "Video surface must reattach immediately once MapView session reaches NO_ACTIVE_MAP_SURFACE",
            surface.isPlayerAttached(player),
        )
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals(true, player.lastPlayWhenReady)
    }

    // 30. Etapa 3J (8): Troca RADAR -> CITIES (e CITIES -> RADAR) não reanexa o vídeo entre as duas telas de mapa
    @Test
    fun `test_30_switching_radar_to_cities_never_reattaches_video_between_map_tabs`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        val mapPorts = mutableListOf<FakeMapViewSurfacePort>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.NUBLADO,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    when (activeTab) {
                        ClimaTab.RADAR ->
                            RadarMapLibreViewImpl(
                                center = CityMapCoordinate(-23.55, -46.63),
                                zoom = 6.0,
                                activeFrame = sampleRadarFrame(1700000000L),
                                layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                                maxRadarZoom = 7,
                                surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { mapPorts += it } },
                            )
                        ClimaTab.CITIES ->
                            CityMapLibreViewImpl(
                                state = CityMapUiState(),
                                onMapTap = {},
                                onCameraGestureSettled = { _, _ -> },
                                surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { mapPorts += it } },
                            )
                        else -> Text("home")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertEquals(1, surface.attachCount)

        // HOME -> RADAR: detaches video output once
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.RADAR)
            activeTab = ClimaTab.RADAR
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(1, surface.attachCount)
        assertEquals(1, surface.detachCount)

        // RADAR -> CITIES: video must stay detached (attachCount remains 1)
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.RADAR, ClimaTab.CITIES)
            activeTab = ClimaTab.CITIES
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals("Video must not reattach between RADAR and CITIES", 1, surface.attachCount)
        assertEquals(1, surface.detachCount)

        // CITIES -> RADAR: video must still stay detached (attachCount remains 1)
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.CITIES, ClimaTab.RADAR)
            activeTab = ClimaTab.RADAR
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals("Video must not reattach between CITIES and RADAR", 1, surface.attachCount)
        assertEquals(1, surface.detachCount)
    }

    // 31. Etapa 3J (9): Retorno para HOME reanexa o mesmo player após o encerramento da sessão do mapa
    @Test
    fun `test_31_returning_to_home_reattaches_same_player_after_map_session_teardown`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        val mapPorts = mutableListOf<FakeMapViewSurfacePort>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    if (activeTab == ClimaTab.RADAR) {
                        RadarMapLibreViewImpl(
                            center = CityMapCoordinate(-23.55, -46.63),
                            zoom = 6.0,
                            activeFrame = sampleRadarFrame(1700000000L),
                            layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                            maxRadarZoom = 7,
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { mapPorts += it } },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val initialPlayer = players.single()
        val surface = surfaces.single()

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.RADAR)
            activeTab = ClimaTab.RADAR
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(initialPlayer))
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.RADAR, ClimaTab.HOME)
            activeTab = ClimaTab.HOME
        }
        composeTestRule.waitForIdle()

        assertEquals(1, players.size)
        assertTrue(players.single() === initialPlayer)
        assertEquals(1, mapPorts.single().destroyCount)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertTrue(surface.isPlayerAttached(initialPlayer))
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        val mapDetachedIdx = logs.indexOfLast { it.contains("MAP_SURFACE_DETACHED") }
        val videoReattachedIdx = logs.indexOfLast { it.contains("VIDEO_OUTPUT_REATTACHED") }
        assertTrue(
            "MAP_SURFACE_DETACHED must precede VIDEO_OUTPUT_REATTACHED (mapDetached=$mapDetachedIdx, reattached=$videoReattachedIdx)",
            mapDetachedIdx in 0 until videoReattachedIdx,
        )
    }

    // 32. Etapa 3J (10): Reanexação respeita ON_START e ON_RESUME da Activity
    @Test
    fun `test_32_video_reattachment_respects_activity_on_start_and_on_resume`() {
        val lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.RADAR)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                Clima3DTheme(darkTheme = true) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertFalse(surface.isPlayerAttached(player))

        // Activity moves to CREATED (ON_PAUSE + ON_STOP) while returning from RADAR to HOME
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            activeTab = ClimaTab.HOME
        }
        composeTestRule.waitForIdle()

        // While Activity is stopped, player must remain paused
        assertEquals(false, player.lastPlayWhenReady)
        val playsWhileStopped = player.playCalls

        // Activity enters ON_START + ON_RESUME -> playback resumes once without duplicate play()
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeTestRule.waitForIdle()

        assertTrue(surface.isPlayerAttached(player))
        assertEquals(true, player.lastPlayWhenReady)
        assertEquals(playsWhileStopped + 1, player.playCalls)
    }

    // 33. Etapa 3J (11): Não ocorrem chamadas duplicadas de attach de superfície
    @Test
    fun `test_33_no_duplicate_surface_attach_calls_on_recomposition`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var tick by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column {
                    Text("tick-$tick")
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val surface = surfaces.single()
        assertEquals(1, surface.attachCount)

        repeat(6) {
            composeTestRule.runOnUiThread { tick++ }
            composeTestRule.waitForIdle()
        }
        assertEquals("Recomposition on HOME must not re-invoke attachPlayer", 1, surface.attachCount)

        // Detach on RADAR and return to HOME -> attachCount increments by exactly 1 (total 2)
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { activeTab = ClimaTab.HOME }
        composeTestRule.waitForIdle()
        assertEquals(2, surface.attachCount)

        repeat(6) {
            composeTestRule.runOnUiThread { tick++ }
            composeTestRule.waitForIdle()
        }
        assertEquals("Recomposition after reattach must not duplicate attachPlayer", 2, surface.attachCount)
    }

    // 34. Etapa 3J (12): Não ocorrem chamadas repetidas de clearVideoSurface
    @Test
    fun `test_34_no_redundant_clear_video_surface_calls`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var tick by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column {
                    Text("tick-$tick")
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.CHUVA,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        assertEquals(0, player.clearSurfaceCount)

        // Enter RADAR -> clears video surface once
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()
        assertEquals(1, player.clearSurfaceCount)

        // Multiple recompositions + switching RADAR -> CITIES -> RADAR must NOT call clearVideoSurface again
        repeat(4) {
            composeTestRule.runOnUiThread { tick++ }
            composeTestRule.waitForIdle()
        }
        composeTestRule.runOnUiThread { activeTab = ClimaTab.CITIES }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()

        assertEquals("clearVideoSurface must be called only once while detached on map tabs", 1, player.clearSurfaceCount)
    }

    // 35. Etapa 3J (13): MapView é destruída apenas após detach (MAP_SURFACE_CREATED -> MAP_SURFACE_DISPOSING -> MAP_SURFACE_DETACHED)
    @Test
    fun `test_35_mapview_destroyed_only_after_detach_with_ordered_coordinator_events`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (mounted) {
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = 6.0,
                        activeFrame = sampleRadarFrame(1700000000L),
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)

        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()

        val port = createdPorts.single()
        assertEquals(1, port.destroyCount)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        val createdIdx = logs.indexOfFirst { it.contains("MAP_SURFACE_CREATED") }
        val disposingIdx = logs.indexOfFirst { it.contains("MAP_SURFACE_DISPOSING") }
        val detachedIdx = logs.indexOfFirst { it.contains("MAP_SURFACE_DETACHED") }
        assertTrue(createdIdx >= 0 && disposingIdx > createdIdx && detachedIdx > disposingIdx)
    }

    // 36. Etapa 3J (14): Callbacks tardios após dispose são descartados sem alterar estado do coordenador
    @Test
    fun `test_36_late_callbacks_after_dispose_discarded_without_corrupting_coordinator`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (mounted) {
                    RadarMapLibreViewImpl(
                        center = CityMapCoordinate(-23.55, -46.63),
                        zoom = 6.0,
                        activeFrame = sampleRadarFrame(1700000000L),
                        layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                        maxRadarZoom = 7,
                        surfacePortFactory = { ctx ->
                            FakeMapViewSurfacePort(ctx, autoReady = false).also { createdPorts += it }
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val port = createdPorts.single()
        val readyCb = port.pendingMapReadyCallback!!
        val callbacks = port.attachedCallbacks!!

        composeTestRule.runOnUiThread { mounted = false }
        composeTestRule.waitForIdle()
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)

        composeTestRule.runOnUiThread {
            readyCb(port.controllerPort)
            callbacks.onDidFinishLoadingStyle()
            callbacks.onFirstFrameRendered()
            callbacks.onDidFinishRenderingMap(true)
            port.flushPendingUiPosts()
        }
        composeTestRule.waitForIdle()

        assertEquals(0, port.controllerPort.setStyleCalls)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
    }

    // 37. Etapa 3J (15): Abrir e fechar LocationPickerDialog na Home não altera estados de superfície do vídeo ou do mapa
    @Test
    fun `test_37_opening_and_closing_location_picker_on_home_preserves_video_and_map_surface_state`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var showDialog by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    if (showDialog) {
                        Text("LocationPickerModalOverlay")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()

        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertEquals(1, surface.attachCount)
        assertEquals(0, surface.detachCount)

        // Open LocationPickerDialog on HOME
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "OPEN")
            showDialog = true
        }
        composeTestRule.waitForIdle()

        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(1, surface.attachCount)
        assertEquals(0, surface.detachCount)
        assertEquals(0, player.clearSurfaceCount)

        // Close LocationPickerDialog on HOME
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "CLOSE")
            showDialog = false
        }
        composeTestRule.waitForIdle()

        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(1, surface.attachCount)
        assertEquals(0, surface.detachCount)
        assertEquals(0, player.clearSurfaceCount)
    }

    // 38. Etapa 3J (16): Navegação repetida entre abas não acumula sessões de mapa nem players e coordena estados sem vazamento
    @Test
    fun `test_38_repeated_navigation_across_all_tabs_coordinates_surfaces_without_accumulation`() {
        val createdMapPorts = mutableListOf<FakeMapViewSurfacePort>()
        val createdPlayers = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val createdSurfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.CHUVA_FRACA,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { createdPlayers += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { createdSurfaces += it } },
                    )
                    when (activeTab) {
                        ClimaTab.RADAR ->
                            RadarMapLibreViewImpl(
                                center = CityMapCoordinate(-23.55, -46.63),
                                zoom = 6.0,
                                activeFrame = sampleRadarFrame(1700000000L),
                                layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                                maxRadarZoom = 7,
                                surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdMapPorts += it } },
                            )
                        ClimaTab.CITIES ->
                            CityMapLibreViewImpl(
                                state = CityMapUiState(),
                                onMapTap = {},
                                onCameraGestureSettled = { _, _ -> },
                                surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdMapPorts += it } },
                            )
                        else -> Text("tab-${activeTab.name}")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val cycle =
            listOf(
                ClimaTab.RADAR,
                ClimaTab.CITIES,
                ClimaTab.RADAR,
                ClimaTab.HOME,
                ClimaTab.SETTINGS,
                ClimaTab.CITIES,
                ClimaTab.ALERTS,
                ClimaTab.RADAR,
                ClimaTab.HOME,
            )
        cycle.forEach { tab ->
            val prev = activeTab
            val prevWasMap = prev == ClimaTab.RADAR || prev == ClimaTab.CITIES
            val nextIsMap = tab == ClimaTab.RADAR || tab == ClimaTab.CITIES
            val logCountBefore = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().size

            composeTestRule.runOnUiThread {
                ClimaGraphicsLifecycleDiagnostics.logTabChanged(prev, tab)
                activeTab = tab
            }
            composeTestRule.waitForIdle()

            val transitionLogs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().drop(logCountBefore)
            val coord = GraphicsSurfaceCoordinator.state.value
            if (nextIsMap) {
                assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, coord.mapState)
                assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, coord.videoState)
                assertFalse(createdSurfaces.single().isPlayerAttached(createdPlayers.single()))
                if (!prevWasMap) {
                    val detachForMapIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_DETACH_FOR_MAP") }
                    val exoClearIdx = transitionLogs.indexOfFirst { it.contains("EXO_SURFACE_CLEAR") }
                    val videoDetachedIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_DETACHED") }
                    val mapCreatedIdx = transitionLogs.indexOfFirst { it.contains("MAP_SURFACE_CREATED") }
                    assertTrue(
                        "Entry to $tab from $prev must follow VIDEO_OUTPUT_DETACH_FOR_MAP -> EXO_SURFACE_CLEAR -> VIDEO_OUTPUT_DETACHED -> MAP_SURFACE_CREATED, got:\n${transitionLogs.joinToString("\n")}",
                        detachForMapIdx >= 0 &&
                            exoClearIdx > detachForMapIdx &&
                            videoDetachedIdx > exoClearIdx &&
                            mapCreatedIdx > videoDetachedIdx,
                    )
                    assertTrue(
                        "MAP_SURFACE_CREATED must observe videoState=VIDEO_OUTPUT_DETACHED",
                        transitionLogs[mapCreatedIdx].contains("videoState=VIDEO_OUTPUT_DETACHED"),
                    )
                } else {
                    assertTrue(
                        "Direct map-to-map switch ($prev -> $tab) must never reattach video output",
                        transitionLogs.none { it.contains("VIDEO_OUTPUT_REATTACHED") || it.contains("EXO_SURFACE_ATTACH") },
                    )
                }
            } else {
                assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, coord.mapState)
                assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, coord.videoState)
                assertTrue(createdSurfaces.single().isPlayerAttached(createdPlayers.single()))
                if (prevWasMap) {
                    val mapDisposingIdx = transitionLogs.indexOfFirst { it.contains("MAP_SURFACE_DISPOSING") }
                    val mapDestroyedIdx = transitionLogs.indexOfFirst { it.contains("MAP_DESTROYED") }
                    val mapDetachedIdx = transitionLogs.indexOfFirst { it.contains("MAP_SURFACE_DETACHED") }
                    val reattachReqIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_REATTACH_REQUEST") }
                    val reattachedIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_REATTACHED") }
                    assertTrue(
                        "Exit from $prev to $tab must follow MAP_SURFACE_DISPOSING -> MAP_DESTROYED -> MAP_SURFACE_DETACHED -> VIDEO_OUTPUT_REATTACH_REQUEST -> VIDEO_OUTPUT_REATTACHED, got:\n${transitionLogs.joinToString("\n")}",
                        mapDisposingIdx >= 0 &&
                            mapDestroyedIdx > mapDisposingIdx &&
                            mapDetachedIdx > mapDestroyedIdx &&
                            reattachReqIdx > mapDetachedIdx &&
                            reattachedIdx > reattachReqIdx,
                    )
                    assertTrue(
                        "VIDEO_OUTPUT_REATTACHED must observe mapState=NO_ACTIVE_MAP_SURFACE",
                        transitionLogs[reattachedIdx].contains("mapState=NO_ACTIVE_MAP_SURFACE"),
                    )
                }
            }
        }

        assertEquals(1, createdPlayers.size)
        assertEquals(0, createdPlayers.single().releaseCalls)
        assertEquals(1, createdSurfaces.size)
        assertEquals(5, createdMapPorts.size)
        assertEquals(5, createdMapPorts.count { it.destroyCount == 1 })
        assertTrue(GraphicsSurfaceCoordinator.state.value.activeMapSessions.isEmpty())
        assertTrue(GraphicsSurfaceCoordinator.state.value.disposingMapSessions.isEmpty())
    }

    private fun extractToken(line: String, prefix: String): String? {
        val idx = line.indexOf(prefix)
        if (idx < 0) return null
        return line.substring(idx + prefix.length).substringBefore(' ').substringBefore(']')
    }

    private fun sampleRadarFrame(ts: Long) =
        RainViewerRadarFrame(
            timestampSeconds = ts,
            path = "/v2/radar/$ts",
            tileUrlTemplate = "https://tilecache.rainviewer.com/v2/radar/$ts/512/{z}/{x}/{y}/2/1_1.png",
            formattedLocalTime = "12:00",
        )
}

private class TestLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = initialState }
    override val lifecycle: Lifecycle
        get() = registry
}

private object GlobalOrderClock {
    val counter = AtomicInteger(0)
}

private class FakeWeatherVideoPlayerBridge : WeatherVideoPlayerBridge {
    override val sessionId: String = "fake-exo-${GlobalOrderClock.counter.incrementAndGet()}"
    override val identityHash: Int = System.identityHashCode(this)

    var released = false
    var idleState = true
    var setMediaItemCalls = 0
    var prepareCalls = 0
    var stopCalls = 0
    var playCalls = 0
    var pauseCalls = 0
    var releaseCalls = 0
    var clearSurfaceCount = 0
    var lastPlayWhenReady: Boolean? = null
    var releaseOrder = Int.MAX_VALUE
    var simulatedPositionMs: Long = 0L
    var currentSpeedValue: Float = 1.0f
    val speedHistory = mutableListOf<Float>()
    val events = mutableListOf<String>()
    private var attachedView: Any? = null
    private var firstFrameListener: (() -> Unit)? = null

    override val isReleased: Boolean
        get() = released

    override val isIdle: Boolean
        get() = idleState

    override val currentPositionMs: Long
        get() = simulatedPositionMs

    override val currentPlaybackSpeed: Float
        get() = currentSpeedValue

    override fun setMediaItem(uri: Uri) {
        setMediaItemCalls++
        events += "setMediaItem"
    }

    override fun setVolume(volume: Float) = Unit

    override fun setRepeatMode(repeatMode: Int) = Unit

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        lastPlayWhenReady = playWhenReady
    }

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeedValue = speed
        speedHistory += speed
    }

    override fun prepare() {
        prepareCalls++
        idleState = false
        events += "prepare"
        firstFrameListener?.invoke()
    }

    override fun stop() {
        stopCalls++
        idleState = true
        events += "stop"
    }

    override fun play() {
        playCalls++
        events += "play"
    }

    override fun pause() {
        pauseCalls++
        events += "pause"
    }

    override fun clearVideoSurface() {
        clearSurfaceCount++
        events += "clearVideoSurface"
    }

    override fun setFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameListener = listener
    }

    override fun attachToPlayerView(playerView: androidx.media3.ui.PlayerView) {
        attachedView = playerView
    }

    override fun isAttachedToPlayerView(playerView: androidx.media3.ui.PlayerView): Boolean =
        attachedView === playerView

    override fun releaseOnce(): Boolean {
        if (released) return false
        released = true
        releaseCalls++
        releaseOrder = GlobalOrderClock.counter.incrementAndGet()
        events += "release"
        return true
    }
}

private class FakeWeatherVideoSurfaceBridge(context: Context) : WeatherVideoSurfaceBridge {
    override val view: View = View(context)
    override val viewIdentity: Int = System.identityHashCode(view)
    override val viewClassName: String = "FakePlayerView"
    private var boundPlayer: WeatherVideoPlayerBridge? = null
    private var attachObserver: ((Boolean, com.example.ui.components.ExoSurfaceSnapshot) -> Unit)? = null
    var attachCount = 0
    var detachCount = 0
    var firstDetachOrder = Int.MAX_VALUE

    override fun attachPlayer(player: WeatherVideoPlayerBridge) {
        attachCount++
        boundPlayer = player
    }

    override fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean =
        boundPlayer === player

    override fun detachPlayer() {
        detachCount++
        if (firstDetachOrder == Int.MAX_VALUE) {
            firstDetachOrder = GlobalOrderClock.counter.incrementAndGet()
        }
        boundPlayer = null
    }

    override fun inspectSurfaceSnapshot() =
        com.example.ui.components.ExoSurfaceSnapshot(
            surfaceViewClass = "TextureView",
            textureAvailable = true,
            hasSurfaceTexture = true,
        )

    override fun attachWindowObserver(
        onAttachStateChanged: (Boolean, com.example.ui.components.ExoSurfaceSnapshot) -> Unit,
    ): () -> Unit {
        attachObserver = onAttachStateChanged
        return { attachObserver = null }
    }

    fun emitAttach(attached: Boolean) {
        attachObserver?.invoke(attached, inspectSurfaceSnapshot())
    }
}

private class FakeMapStylePort : MapStylePort {
    override val isFullyLoaded: Boolean = true
    val layers = mutableSetOf<String>()

    override fun updateGeoJsonSource(sourceId: String, geoJson: String) = Unit

    override fun addRainViewerSourceAndLayer(
        sourceId: String,
        layerId: String,
        frame: RainViewerRadarFrame,
        maxRadarZoom: Int,
        opacity: Float,
        isVisible: Boolean,
        aboveLayerId: String,
    ) {
        layers += layerId
    }

    override fun setLayerVisibility(layerId: String, isVisible: Boolean) = Unit

    override fun removeSourceAndLayer(sourceId: String, layerId: String) {
        layers.remove(layerId)
    }
}

private class FakeMapLibreControllerPort(
    private val events: MutableList<String>,
) : MapLibreControllerPort {
    var setStyleCalls = 0
    var cancelTransitionsCalls = 0
    val animatedZooms = mutableListOf<Double>()
    private var mapClickHandler: ((CityMapCoordinate) -> Unit)? = null
    private var cameraIdleHandler: ((CityMapCoordinate, Double) -> Unit)? = null

    override fun configureUiSettings() = Unit
    override fun setInitialCamera(latitude: Double, longitude: Double, zoom: Double) = Unit

    override fun animateCamera(latitude: Double, longitude: Double, zoom: Double, durationMs: Int) {
        animatedZooms += zoom
        events += "animateCamera:$zoom"
    }

    override fun cancelTransitions() {
        cancelTransitionsCalls++
        events += "cancelTransitions"
    }

    override fun setStyle(request: MapStyleSpecRequest, onLoaded: (MapStylePort) -> Unit) {
        setStyleCalls++
        events += "setStyle"
        onLoaded(FakeMapStylePort())
    }

    override fun setOnMapClick(listener: (CityMapCoordinate) -> Unit) {
        mapClickHandler = listener
    }

    override fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit) {
        cameraIdleHandler = listener
    }

    override fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult =
        LocationComponentSyncResult.PERMISSION_DENIED

    fun simulateMapTap(coordinate: CityMapCoordinate) {
        mapClickHandler?.invoke(coordinate)
    }

    fun simulateCameraIdle(coordinate: CityMapCoordinate, zoom: Double) {
        cameraIdleHandler?.invoke(coordinate, zoom)
    }
}

private class FakeMapViewSurfacePort(
    context: Context,
    private val autoReady: Boolean = true,
) : MapViewSurfacePort {
    override val androidView: View = View(context)
    override val viewIdentity: Int = System.identityHashCode(androidView)
    override val viewClassName: String = "FakeMapView"
    val events = mutableListOf<String>()
    val controllerPort = FakeMapLibreControllerPort(events)
    private val pendingUiPosts = mutableListOf<() -> Unit>()
    private var windowObserver: ((Boolean, String) -> Unit)? = null

    var createCount = 0
    var startCount = 0
    var resumeCount = 0
    var pauseCount = 0
    var stopCount = 0
    var destroyCount = 0
    var listenerDetachCount = 0
    var attachedCallbacks: MapSurfaceCallbacks? = null
    var pendingMapReadyCallback: ((MapLibreControllerPort) -> Unit)? = null

    override fun onCreate() {
        createCount++
        events += "onCreate"
    }

    override fun onStart() {
        startCount++
        events += "onStart"
    }

    override fun onResume() {
        resumeCount++
        events += "onResume"
    }

    override fun onPause() {
        pauseCount++
        events += "onPause"
    }

    override fun onStop() {
        stopCount++
        events += "onStop"
    }

    override fun onDestroy() {
        destroyCount++
        events += "onDestroy"
    }

    override fun onLowMemory() {
        events += "onLowMemory"
    }

    override fun inspectSurfaceInfo(): String = "FakeMapView=1080x900, attached=true, textureAvailable=true"

    override fun attachWindowObserver(onAttachStateChanged: (attached: Boolean, surfaceInfo: String) -> Unit): () -> Unit {
        windowObserver = onAttachStateChanged
        return { windowObserver = null }
    }

    fun emitAttach(attached: Boolean) {
        windowObserver?.invoke(attached, inspectSurfaceInfo())
    }

    override fun postToUi(action: () -> Unit) {
        pendingUiPosts += action
    }

    fun flushPendingUiPosts() {
        val snapshot = pendingUiPosts.toList()
        pendingUiPosts.clear()
        snapshot.forEach { it() }
    }

    override fun attachListeners(callbacks: MapSurfaceCallbacks): () -> Unit {
        attachedCallbacks = callbacks
        return {
            listenerDetachCount++
            events += "detachListeners"
        }
    }

    override fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit) {
        if (autoReady) {
            onReady(controllerPort)
        } else {
            pendingMapReadyCallback = onReady
        }
    }
}
