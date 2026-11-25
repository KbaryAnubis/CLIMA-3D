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
import androidx.compose.runtime.key
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
                key(screenMode) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text("$screenMode-recompose-$radarTick")
                        CityMapLibreViewImpl(
                            state = cityState,
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
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

        // Trigger multiple recompositions on on-demand MapLocationPicker without viewRecreationNonce changing
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
        assertEquals("MapLocationPicker MapView must not recreate across recompositions", 1, createdPorts.size)

        // Reopen a fresh picker session and trigger multiple recompositions
        composeTestRule.runOnUiThread { screenMode = "picker_session_2" }
        composeTestRule.waitForIdle()
        assertEquals(2, createdPorts.size)

        repeat(4) {
            composeTestRule.runOnUiThread { radarTick++ }
            composeTestRule.waitForIdle()
        }
        assertEquals("Second MapLocationPicker MapView must not recreate across recompositions", 2, createdPorts.size)
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

    // 8. Pausa de vídeo exclusivamente ao abrir o seletor de localização no mapa (e retomada ao fechar)
    @Test
    fun `test_08_video_pauses_on_radar_and_cities_and_resumes_on_home`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        assertEquals(1, players.size)
        assertTrue(player.lastPlayWhenReady == true)

        // Etapa 3P: Switching to RADAR or CITIES keeps video active because neither tab mounts a MapView
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()
        assertEquals(true, player.lastPlayWhenReady)
        assertEquals(0, player.pauseCalls)

        composeTestRule.runOnUiThread { activeTab = ClimaTab.CITIES }
        composeTestRule.waitForIdle()
        assertEquals(true, player.lastPlayWhenReady)
        assertEquals(0, player.pauseCalls)

        // Open on-demand MapLocationPicker -> pauses without releasing
        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(0, player.releaseCalls)
        assertEquals(1, player.pauseCalls)
        assertEquals(false, player.lastPlayWhenReady)

        // Close on-demand MapLocationPicker -> resumes existing player cleanly
        val playsBeforeResume = player.playCalls
        composeTestRule.runOnUiThread {
            activeTab = ClimaTab.HOME
            isMapPickerOpen = false
        }
        composeTestRule.waitForIdle()
        assertEquals(1, players.size)
        assertEquals(0, player.releaseCalls)
        assertTrue(player.playCalls > playsBeforeResume)
        assertEquals(true, player.lastPlayWhenReady)
    }

    // 9. Navegação entre abas não monta nem acumula sessões de mapa; abertura sob demanda encerra corretamente
    @Test
    fun `test_09_tab_navigation_does_not_accumulate_map_sessions`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text("tab-${activeTab.name}")
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(0, createdPorts.size)

        // Navigating across all permanent tabs creates ZERO MapView sessions
        val permanentTabs = listOf(
            ClimaTab.RADAR,
            ClimaTab.CITIES,
            ClimaTab.HOME,
            ClimaTab.RADAR,
            ClimaTab.SETTINGS,
            ClimaTab.CITIES,
            ClimaTab.HOME,
        )
        permanentTabs.forEach { tab ->
            composeTestRule.runOnUiThread { activeTab = tab }
            composeTestRule.waitForIdle()
            assertEquals("Permanent tab $tab must mount 0 MapViews", 0, createdPorts.size)
        }

        // Opening and closing MapLocationPicker 4 times keeps at most 1 active session and destroys all closed ones
        repeat(4) {
            composeTestRule.runOnUiThread { isMapPickerOpen = true }
            composeTestRule.waitForIdle()
            assertEquals(1, createdPorts.count { it.destroyCount == 0 })

            composeTestRule.runOnUiThread { isMapPickerOpen = false }
            composeTestRule.waitForIdle()
            assertEquals(0, createdPorts.count { it.destroyCount == 0 })
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
        var mapState by mutableStateOf(CityMapUiState())

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
                    CityMapLibreViewImpl(
                        state = mapState,
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
                        modifier = Modifier.weight(1f),
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Weather severity changes sharing the same background MP4 (`wx_bg_rain_day`) + marker updates
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA_FRACA
            mapState = mapState.selectMarkerAt(CityMapCoordinate(-23.56, -46.64), "Ponto 1")
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA
            mapState = mapState.selectMarkerAt(CityMapCoordinate(-23.57, -46.65), "Ponto 2")
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

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSectionImpl(
                        initialState = CityMapUiState(zoom = 10.0),
                        isOnline = true,
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, createdPorts.size)
        val citiesPort = createdPorts[0]

        // Click + and - zoom controls
        composeTestRule.onNodeWithTag("city_map_zoom_in").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_zoom_out").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, createdPorts.size)
        assertEquals(0, citiesPort.destroyCount)
        assertEquals(listOf(11.0, 10.0), citiesPort.controllerPort.animatedZooms)
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
                key(screenMode) {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { ports += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { screenMode = "picker_2" }
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
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
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
                CityMapLibreViewImpl(
                    state = CityMapUiState(),
                    onMapTap = {},
                    onCameraGestureSettled = { _, _ -> },
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
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
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
        var mapState by mutableStateOf(CityMapUiState())
        var recomposeTick by mutableIntStateOf(0)

        composeTestRule.setContent {
            Column {
                Text("recompose-$recomposeTick")
                CityMapLibreViewImpl(
                    state = mapState,
                    onMapTap = {},
                    onCameraGestureSettled = { _, _ -> },
                    surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val countAfterInit = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().size

        // Simulate 15 marker state updates + 15 Compose recompositions
        repeat(15) { step ->
            composeTestRule.runOnUiThread {
                mapState = mapState.selectMarkerAt(
                    CityMapCoordinate(-23.55 + step * 0.001, -46.63),
                    "Ponto $step",
                )
                recomposeTick = step + 1
            }
            composeTestRule.waitForIdle()
        }

        val logsAfterTicks = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertEquals("Ticks and recompositions must emit zero extra lifecycle log entries", countAfterInit, logsAfterTicks.size)
        assertTrue(logsAfterTicks.size <= 128)
    }

    // 22. Navegação repetida entre Home, Radar, Cidades e Ajustes + abertura do seletor no mapa não acumula mapas nem players ativos
    @Test
    fun `test_22_multi_tab_navigation_no_active_map_or_player_accumulation`() {
        val createdMapPorts = mutableListOf<FakeMapViewSurfacePort>()
        val createdPlayers = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL_NUVENS,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { createdPlayers += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdMapPorts += it } },
                        )
                    } else {
                        Text("tab-${activeTab.name}")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Switching across permanent tabs mounts 0 maps
        listOf(ClimaTab.RADAR, ClimaTab.CITIES, ClimaTab.SETTINGS, ClimaTab.HOME).forEach { next ->
            val prev = activeTab
            composeTestRule.runOnUiThread {
                ClimaGraphicsLifecycleDiagnostics.logTabChanged(prev, next)
                activeTab = next
            }
            composeTestRule.waitForIdle()
            assertEquals(0, createdMapPorts.size)
        }

        // Opening and closing MapLocationPicker across tabs keeps at most 1 active map and destroys cleanly
        listOf(true, false, true, false, true, false).forEach { pickerOpen ->
            composeTestRule.runOnUiThread { isMapPickerOpen = pickerOpen }
            composeTestRule.waitForIdle()
            val activeMaps = createdMapPorts.count { it.destroyCount == 0 }
            assertEquals(if (pickerOpen) 1 else 0, activeMaps)
        }

        assertEquals(1, createdPlayers.size)
        assertEquals(0, createdPlayers.single().releaseCalls)
        assertEquals(3, createdMapPorts.size)
        assertEquals(3, createdMapPorts.count { it.destroyCount == 1 })
    }

    // 23. Etapa 3J/3P: Player continua ativo em RADAR sem mapa e é pausado ao abrir o seletor no mapa
    @Test
    fun `test_23_player_paused_when_entering_radar`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
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
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()

        assertEquals(false, player.lastPlayWhenReady)
        assertEquals(1, player.pauseCalls)
        assertEquals(0, player.releaseCalls)
    }

    // 24. Etapa 3J/3P: Player é pausado ao abrir o seletor no mapa a partir de CITIES
    @Test
    fun `test_24_player_paused_when_entering_cities`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
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
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()

        assertEquals(false, player.lastPlayWhenReady)
        assertEquals(1, player.pauseCalls)
        assertEquals(0, player.releaseCalls)
    }

    // 25. Etapa 3J/3P: Saída de vídeo é desassociada (detachPlayer + clearVideoSurface) ao abrir o seletor de localização no mapa
    @Test
    fun `test_25_video_output_disassociated_and_cleared_when_entering_map_tabs`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL_NUVENS,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
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
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "OPEN")
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()

        assertFalse("PlayerView must be detached from ExoPlayer when MapLocationPicker opens", surface.isPlayerAttached(player))
        assertEquals(1, surface.detachCount)
        assertEquals(1, player.clearSurfaceCount)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logs.any { it.contains("VIDEO_OUTPUT_DETACH_FOR_MAP") })
        assertTrue(logs.any { it.contains("VIDEO_OUTPUT_DETACHED") })
    }

    // 26. Etapa 3J (4): Player permanece em memória e não é liberado ao trocar de aba ou abrir/fechar seletor de mapa
    @Test
    fun `test_26_player_stays_in_memory_and_is_not_released_across_tab_switches`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.TEMPESTADE,
                    activeTab = activeTab,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()
        val originalPlayer = players.single()

        listOf(
            ClimaTab.RADAR to false,
            ClimaTab.CITIES to true,
            ClimaTab.CITIES to false,
            ClimaTab.SETTINGS to false,
            ClimaTab.ALERTS to false,
            ClimaTab.HOME to true,
            ClimaTab.HOME to false,
        ).forEach { (tab, pickerOpen) ->
            composeTestRule.runOnUiThread {
                activeTab = tab
                isMapPickerOpen = pickerOpen
            }
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
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
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

        // Open MapLocationPicker (detach output) -> position must remain 4320ms
        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(4_320L, player.currentPositionMs)
        assertEquals(0, player.stopCalls)

        // Close MapLocationPicker (reattach output) -> position still 4320ms without re-preparing media
        composeTestRule.runOnUiThread { isMapPickerOpen = false }
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
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA_FRACA,
                    activeTab = ClimaTab.CITIES,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        composeTestRule.runOnUiThread { isMapPickerOpen = false }
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
        var isMapPickerOpen by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapPickerOpen,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()

        // Create an active MapLibre session handle while picker is open
        val mapHandle =
            MapViewSessionHandle(
                screenName = "Cidades",
                sessionId = "picker-manual-29",
                surfacePort = manualMapPort,
                controller = controllerOrManual(manualController),
                onDetachListeners = {},
                onCancelTransitions = {},
            )
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertFalse(surface.isPlayerAttached(player))

        // User closes picker while MapView is transitioning to MAP_SURFACE_DISPOSING (phase 1)
        composeTestRule.runOnUiThread {
            mapHandle.pauseAndStopBeforeDetach("onDispose")
            isMapPickerOpen = false
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

    private fun controllerOrManual(controller: MapViewLifecycleController): MapViewLifecycleController = controller

    // 30. Etapa 3J/3P: Troca de abas subjacentes enquanto o seletor de mapa está aberto não reanexa o vídeo antes do fechamento
    @Test
    fun `test_30_switching_radar_to_cities_never_reattaches_video_between_map_tabs`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        val mapPorts = mutableListOf<FakeMapViewSurfacePort>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.NUBLADO,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { mapPorts += it } },
                        )
                    } else {
                        Text("tab-${activeTab.name}")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        val surface = surfaces.single()
        assertEquals(1, surface.attachCount)

        // Open MapLocationPicker -> detaches video output once
        composeTestRule.runOnUiThread {
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(1, surface.attachCount)
        assertEquals(1, surface.detachCount)

        // Underlying tab state transitions while picker remains active must NOT reattach video
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.CITIES)
            activeTab = ClimaTab.CITIES
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals("Video must stay detached while MapLocationPicker is open", 1, surface.attachCount)
        assertEquals(1, surface.detachCount)

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.CITIES, ClimaTab.RADAR)
            activeTab = ClimaTab.RADAR
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals("Video must still stay detached while MapLocationPicker is open", 1, surface.attachCount)
        assertEquals(1, surface.detachCount)
    }

    // 31. Etapa 3J (9): Fechar seletor no mapa reanexa o mesmo player após o encerramento da sessão do mapa
    @Test
    fun `test_31_returning_to_home_reattaches_same_player_after_map_session_teardown`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        val mapPorts = mutableListOf<FakeMapViewSurfacePort>()
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
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
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "OPEN")
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(initialPlayer))
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)

        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "CLOSE")
            isMapPickerOpen = false
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
        var isMapPickerOpen by mutableStateOf(true)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                Clima3DTheme(darkTheme = true) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
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

        // Activity moves to CREATED (ON_PAUSE + ON_STOP) while closing MapLocationPicker
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            isMapPickerOpen = false
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
        var isMapPickerOpen by mutableStateOf(false)
        var tick by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column {
                    Text("tick-$tick")
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
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

        // Detach on MapLocationPicker and return to HOME -> attachCount increments by exactly 1 (total 2)
        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { isMapPickerOpen = false }
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
        var isMapPickerOpen by mutableStateOf(false)
        var tick by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column {
                    Text("tick-$tick")
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.CHUVA,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        val player = players.single()
        assertEquals(0, player.clearSurfaceCount)

        // Open MapLocationPicker -> clears video surface once
        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        assertEquals(1, player.clearSurfaceCount)

        // Multiple recompositions + tab updates while picker remains open must NOT call clearVideoSurface again
        repeat(4) {
            composeTestRule.runOnUiThread { tick++ }
            composeTestRule.waitForIdle()
        }
        composeTestRule.runOnUiThread { activeTab = ClimaTab.CITIES }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()

        assertEquals("clearVideoSurface must be called only once while detached for map picker", 1, player.clearSurfaceCount)
    }

    // 35. Etapa 3J (13): MapView é destruída apenas após detach (MAP_SURFACE_CREATED -> MAP_SURFACE_DISPOSING -> MAP_SURFACE_DETACHED)
    @Test
    fun `test_35_mapview_destroyed_only_after_detach_with_ordered_coordinator_events`() {
        val createdPorts = mutableListOf<FakeMapViewSurfacePort>()
        var mounted by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (mounted) {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
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
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
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

    // 38. Etapa 3J/3P: Navegação repetida entre abas permanentes + abertura/fechamento do seletor no mapa coordena superfícies sem vazamento
    @Test
    fun `test_38_repeated_navigation_across_all_tabs_coordinates_surfaces_without_accumulation`() {
        val createdMapPorts = mutableListOf<FakeMapViewSurfacePort>()
        val createdPlayers = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val createdSurfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.CHUVA_FRACA,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { createdPlayers += it } },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { createdSurfaces += it } },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { createdMapPorts += it } },
                        )
                    } else {
                        Text("tab-${activeTab.name}")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val cycle =
            listOf(
                ClimaTab.RADAR to false,
                ClimaTab.CITIES to false,
                ClimaTab.CITIES to true,
                ClimaTab.RADAR to true,
                ClimaTab.HOME to false,
                ClimaTab.SETTINGS to false,
                ClimaTab.CITIES to true,
                ClimaTab.ALERTS to false,
                ClimaTab.HOME to true,
                ClimaTab.HOME to false,
            )
        cycle.forEach { (tab, pickerOpen) ->
            val prevTab = activeTab
            val prevHadMap = isMapPickerOpen
            val nextHasMap = pickerOpen
            val logCountBefore = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().size

            composeTestRule.runOnUiThread {
                if (prevTab != tab) {
                    ClimaGraphicsLifecycleDiagnostics.logTabChanged(prevTab, tab)
                }
                activeTab = tab
                isMapPickerOpen = pickerOpen
            }
            composeTestRule.waitForIdle()

            val transitionLogs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().drop(logCountBefore)
            val coord = GraphicsSurfaceCoordinator.state.value
            if (nextHasMap) {
                assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, coord.mapState)
                assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, coord.videoState)
                assertFalse(createdSurfaces.single().isPlayerAttached(createdPlayers.single()))
                if (!prevHadMap) {
                    val detachForMapIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_DETACH_FOR_MAP") }
                    val exoClearIdx = transitionLogs.indexOfFirst { it.contains("EXO_SURFACE_CLEAR") }
                    val videoDetachedIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_DETACHED") }
                    val mapCreatedIdx = transitionLogs.indexOfFirst { it.contains("MAP_SURFACE_CREATED") }
                    assertTrue(
                        "Opening MapLocationPicker on $tab must follow VIDEO_OUTPUT_DETACH_FOR_MAP -> EXO_SURFACE_CLEAR -> VIDEO_OUTPUT_DETACHED -> MAP_SURFACE_CREATED, got:\n${transitionLogs.joinToString("\n")}",
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
                        "Tab change while MapLocationPicker remains open must never reattach video output",
                        transitionLogs.none { it.contains("VIDEO_OUTPUT_REATTACHED") || it.contains("EXO_SURFACE_ATTACH") },
                    )
                }
            } else {
                assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, coord.mapState)
                assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, coord.videoState)
                assertTrue(createdSurfaces.single().isPlayerAttached(createdPlayers.single()))
                if (prevHadMap) {
                    val mapDisposingIdx = transitionLogs.indexOfFirst { it.contains("MAP_SURFACE_DISPOSING") }
                    val mapDestroyedIdx = transitionLogs.indexOfFirst { it.contains("MAP_DESTROYED") }
                    val mapDetachedIdx = transitionLogs.indexOfFirst { it.contains("MAP_SURFACE_DETACHED") }
                    val reattachReqIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_REATTACH_REQUEST") }
                    val reattachedIdx = transitionLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_REATTACHED") }
                    assertTrue(
                        "Closing MapLocationPicker on $tab must follow MAP_SURFACE_DISPOSING -> MAP_DESTROYED -> MAP_SURFACE_DETACHED -> VIDEO_OUTPUT_REATTACH_REQUEST -> VIDEO_OUTPUT_REATTACHED, got:\n${transitionLogs.joinToString("\n")}",
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
        assertEquals(3, createdMapPorts.size)
        assertEquals(3, createdMapPorts.count { it.destroyCount == 1 })
        assertTrue(GraphicsSurfaceCoordinator.state.value.activeMapSessions.isEmpty())
        assertTrue(GraphicsSurfaceCoordinator.state.value.disposingMapSessions.isEmpty())
    }

    // 39. Etapa 3L (1): Erro fatal / reclaim simulado do MediaCodec na HOME recupera o mesmo ExoPlayer preservando MediaItem, posição e velocidade
    @Test
    fun `test_39_mediacodec_error_on_home_recovers_same_exoplayer_preserving_media_position_and_speed`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA_FRACA,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge().apply { simulatedPositionMs = 6_400L }.also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(1, player.setMediaItemCalls)
        assertEquals(1, player.prepareCalls)
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)
        assertEquals(6_400L, player.currentPositionMs)

        // Simulate MediaCodec reclaim / DEAD_OBJECT error while playing on HOME
        // Note: In unit tests, this is an application-level simulation of Media3 AnalyticsListener + Player.Listener
        // codec failure callbacks, NOT the native Android ResourceManagerService process.
        composeTestRule.runOnUiThread {
            player.simulateFatalMediaCodecReclaim(
                decoderName = "c2.android.avc.decoder",
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "MediaCodec VideoRenderer error: Released by resource manager (DEAD_OBJECT)",
                resetLivePositionOnIdle = true,
            )
        }
        composeTestRule.waitForIdle()

        // Same ExoPlayer instance is preserved (no duplicate creation, no release, no MediaItem reset)
        assertEquals("Must not duplicate ExoPlayer instance on codec recovery", 1, players.size)
        assertTrue(players.single() === player)
        assertEquals(0, player.releaseCalls)
        assertFalse(player.isReleased)
        assertEquals("MediaItem must be preserved without calling setMediaItem again", 1, player.setMediaItemCalls)
        assertEquals("Player must re-prepare once to recover from STATE_IDLE after codec reclaim", 2, player.prepareCalls)
        assertFalse("Player must no longer be in IDLE after recovery prepare()", player.isIdle)
        assertEquals("Playback position must be restored to 6400ms", 6_400L, player.currentPositionMs)
        assertEquals("Playback speed 0.85f (CHUVA_FRACA) must be preserved", 0.85f, player.currentPlaybackSpeed, 0.001f)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logs.any { it.contains("EXO_CODEC_ERROR") && it.contains("track=VIDEO") && it.contains("deadObject=true") })
        assertTrue(logs.any { it.contains("EXO_ERROR") && it.contains("code=ERROR_CODE_DECODING_FAILED") && it.contains("deadObject=true") })
        assertTrue(logs.any { it.contains("EXO_DECODER_RELEASED") && it.contains("decoder=c2.android.avc.decoder") })
        assertTrue(
            logs.any {
                it.contains("EXO_RECOVERY") &&
                    it.contains("action=RECOVER_PREPARE") &&
                    it.contains("posMs=6400") &&
                    it.contains("speed=0.85")
            },
        )
        assertTrue(logs.count { it.contains("EXO_DECODER_INITIALIZED") && it.contains("track=VIDEO") } >= 2)
        assertTrue(logs.count { it.contains("EXO_FIRST_FRAME") } >= 2)
    }

    // 40. Etapa 3L/3P: Reclaim simulado do MediaCodec enquanto o seletor de mapa está aberto adia recuperação até fechar o seletor
    @Test
    fun `test_40_mediacodec_reclaim_while_on_radar_defers_recovery_without_attaching_surface_until_return_to_home`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        val mapPorts = mutableListOf<FakeMapViewSurfacePort>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.CHUVA_FRACA,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = {
                            FakeWeatherVideoPlayerBridge().apply { simulatedPositionMs = 5_120L }.also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { mapPorts += it } },
                        )
                    } else {
                        Text("tab-${activeTab.name}")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()
        assertEquals(1, player.prepareCalls)
        assertEquals(1, surface.attachCount)

        // 1. Open MapLocationPicker (detaches video surface before map creation)
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "OPEN")
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()

        assertFalse(surface.isPlayerAttached(player))
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)

        // 2. Simulate MediaCodec reclaim while paused & surface-detached during MapLocationPicker
        val logCountBeforeReclaim = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().size
        composeTestRule.runOnUiThread {
            player.simulateFatalMediaCodecReclaim(
                decoderName = "c2.android.avc.decoder",
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "Released by resource manager while MapLocationPicker active",
                resetLivePositionOnIdle = true,
            )
        }
        composeTestRule.waitForIdle()

        // While map picker is open, player MUST NOT re-prepare or attach video surface
        assertFalse("Video surface MUST NOT attach while MapLibre session is active", surface.isPlayerAttached(player))
        assertEquals("attachCount must remain 1 while map picker open", 1, surface.attachCount)
        assertEquals("prepareCalls must remain 1 (deferred while map picker open)", 1, player.prepareCalls)
        assertTrue("Player remains in IDLE until closing map picker", player.isIdle)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)

        val logsDuringMapReclaim = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().drop(logCountBeforeReclaim)
        assertTrue(
            "Must log EXO_RECOVERY action=DEFERRED reason=map_surface_active_or_disposing",
            logsDuringMapReclaim.any {
                it.contains("EXO_RECOVERY") &&
                    it.contains("action=DEFERRED") &&
                    it.contains("reason=map_surface_active_or_disposing") &&
                    it.contains("posMs=5120")
            },
        )
        assertTrue(
            "Must NOT log EXO_SURFACE_ATTACH or VIDEO_OUTPUT_REATTACHED while map is active",
            logsDuringMapReclaim.none { it.contains("EXO_SURFACE_ATTACH") || it.contains("VIDEO_OUTPUT_REATTACHED") },
        )

        // 3. Underlying tab changes while recovery is still pending: must still remain deferred and detached
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(ClimaTab.HOME, ClimaTab.CITIES)
            activeTab = ClimaTab.CITIES
        }
        composeTestRule.waitForIdle()
        assertFalse(surface.isPlayerAttached(player))
        assertEquals(1, surface.attachCount)
        assertEquals(1, player.prepareCalls)

        // 4. Close MapLocationPicker: after MAP_SURFACE_DETACHED, video surface reattaches and deferred recovery executes
        val logCountBeforeReturn = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().size
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "CLOSE")
            isMapPickerOpen = false
        }
        composeTestRule.waitForIdle()

        assertEquals(1, players.size)
        assertTrue(players.single() === player)
        assertEquals(0, player.releaseCalls)
        assertTrue(surface.isPlayerAttached(player))
        assertEquals(2, surface.attachCount)
        assertEquals("MediaItem must not be reset", 1, player.setMediaItemCalls)
        assertEquals("Deferred recovery prepare() must run once on closing map picker", 2, player.prepareCalls)
        assertFalse(player.isIdle)
        assertEquals("Position 5120ms must be restored upon deferred recovery", 5_120L, player.currentPositionMs)
        assertEquals("Speed 0.85f must be preserved upon deferred recovery", 0.85f, player.currentPlaybackSpeed, 0.001f)

        val returnLogs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().drop(logCountBeforeReturn)
        val mapDetachedIdx = returnLogs.indexOfFirst { it.contains("MAP_SURFACE_DETACHED") }
        val videoReattachedIdx = returnLogs.indexOfFirst { it.contains("VIDEO_OUTPUT_REATTACHED") }
        val recoverPrepareIdx =
            returnLogs.indexOfFirst { it.contains("EXO_RECOVERY") && it.contains("action=RECOVER_PREPARE") }
        assertTrue(
            "Closing map picker after codec reclaim must follow MAP_SURFACE_DETACHED -> VIDEO_OUTPUT_REATTACHED -> EXO_RECOVERY(RECOVER_PREPARE), got:\n${returnLogs.joinToString("\n")}",
            mapDetachedIdx >= 0 &&
                videoReattachedIdx > mapDetachedIdx &&
                recoverPrepareIdx > videoReattachedIdx,
        )
    }

    // 41. Etapa 3M (1, 2, 3): Wallpaper permanece visível antes do primeiro frame, VIDEO_WAITING_FIRST_FRAME mantém videoAlpha == 0f e onRenderedFirstFrame habilita a transição visual
    @Test
    fun `test_41_wallpaper_visible_and_video_alpha_zero_until_on_rendered_first_frame`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        assertEquals("Player is READY, but first frame has not rendered yet", "READY", player.playerStateName)
        assertEquals("videoAlpha MUST remain 0f while waiting for first frame", 0f, observedVideoAlpha, 0.001f)

        val logsBeforeFrame = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsBeforeFrame.any { it.contains("BACKGROUND_WALLPAPER_VISIBLE") && it.contains("videoAlpha=0.00") })
        assertTrue(logsBeforeFrame.any { it.contains("BACKGROUND_VIDEO_WAITING_FIRST_FRAME") && it.contains("frameReady=false") })
        assertFalse(logsBeforeFrame.any { it.contains("BACKGROUND_VIDEO_FRAME_READY") })

        // Now emit onRenderedFirstFrame -> video transitions smoothly to alpha = 1f
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()

        assertEquals("videoAlpha must reach 1f after onRenderedFirstFrame", 1f, observedVideoAlpha, 0.001f)
        val logsAfterFrame = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsAfterFrame.any { it.contains("BACKGROUND_VIDEO_FRAME_READY") && it.contains("frameReady=true") })
        assertTrue(logsAfterFrame.any { it.contains("BACKGROUND_VIDEO_FADE_IN") && it.contains("videoAlpha=1.00") })
    }

    // 42. Etapa 3M (4, 9, 11): Erro de decoder esconde o vídeo imediatamente (alpha=0f) sem remover o wallpaper, mantém wallpaper em STATE_IDLE/recuperação e só restaura após novo onRenderedFirstFrame
    @Test
    fun `test_42_decoder_error_immediately_hides_video_keeps_wallpaper_during_idle_and_restores_on_new_first_frame`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).apply {
                            simulatedPositionMs = 3_800L
                        }.also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        // Configure recovery prepare() to NOT emit first frame immediately so we can inspect VIDEO_RECOVERING / IDLE state
        player.autoFirstFrameOnPrepare = false
        composeTestRule.runOnUiThread {
            player.simulateFatalMediaCodecReclaim(
                decoderName = "c2.android.avc.decoder",
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "Codec error: ERROR_RECLAIMED",
            )
        }
        composeTestRule.waitForIdle()

        // Video must snap immediately to alpha = 0f while static wallpaper remains mounted
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        assertEquals("Video must hide immediately (alpha=0f) when codec fails and before new first frame", 0f, observedVideoAlpha, 0.001f)
        val logsDuringRecovery = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsDuringRecovery.any { it.contains("BACKGROUND_VIDEO_HIDDEN_NO_FRAME") && it.contains("reason=codec_error_ERROR_CODE_DECODING_FAILED") })
        assertTrue(logsDuringRecovery.any { it.contains("BACKGROUND_VIDEO_RECOVERY_STARTED") && it.contains("visualState=VIDEO_RECOVERING") })

        // Now new decoder generation renders its first frame -> smooth restoration to alpha = 1f
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()

        assertEquals(1f, observedVideoAlpha, 0.001f)
        val logsAfterRecovery = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsAfterRecovery.any { it.contains("BACKGROUND_VIDEO_RECOVERY_FINISHED") && it.contains("frameReady=true") })
    }

    // 43. Etapa 3M (5, 6): ERROR_RECLAIMED incrementa geração do decoder, não reutiliza decoder antigo e não dispara prepare() duplicado
    @Test
    fun `test_43_error_reclaimed_advances_decoder_generation_and_avoids_duplicate_prepare`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val snapshots = mutableListOf<com.example.ui.components.WeatherVideoVisualSnapshot>()

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherVideoBackgroundImpl(
                    videoResId = R.raw.wx_bg_sun_day,
                    playbackSpeed = 1.0f,
                    isPlaying = true,
                    allowVideoSurfaceOutput = true,
                    onVisualStateChanged = { snapshots += it },
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1, player.prepareCalls)
        assertEquals(1, player.decoderGeneration)
        assertEquals(1, snapshots.last().decoderGeneration)
        assertTrue(snapshots.last().frameReady)

        // Simulate ERROR_RECLAIMED: old decoder gen 1 is released; recovery prepare() initializes new decoder gen 2
        composeTestRule.runOnUiThread {
            player.simulateFatalMediaCodecReclaim(
                decoderName = "c2.android.avc.decoder",
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "android.media.MediaCodec\$CodecException: Error 0xffffec77 (ERROR_RECLAIMED)",
            )
        }
        composeTestRule.waitForIdle()

        assertEquals("Recovery must invoke prepare() exactly once (total 2)", 2, player.prepareCalls)
        assertEquals("New decoder generation must be 2 (old reclaimed decoder gen 1 discarded)", 2, player.decoderGeneration)
        assertEquals(2, snapshots.last().decoderGeneration)
        assertTrue(snapshots.last().frameReady)
    }

    // 44. Etapa 3M (10): BUFFERING sem frame válido não produz flash preto (mantém videoAlpha == 0f e wallpaper visível)
    @Test
    fun `test_44_buffering_before_first_frame_keeps_video_hidden_and_wallpaper_visible`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.NUBLADO,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        composeTestRule.runOnUiThread {
            player.simulatePlaybackStateChange("BUFFERING", isPlaying = false)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        assertEquals("videoAlpha must stay 0f during BUFFERING before first frame", 0f, observedVideoAlpha, 0.001f)
    }

    // 45. Etapa 3M/3P.3 (12): Falhas repetidas de decoder escalam até rebuild único do player e entram em FAILED sem loop infinito, mantendo o wallpaper estático
    @Test
    fun `test_45_repeated_codec_failures_exhaust_recovery_without_infinite_prepare_loop_and_keep_wallpaper`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.TEMPESTADE,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player1 = players.single()
        assertEquals(1, player1.prepareCalls)

        // Failures 1 & 2 -> Tier 1 (RECOVER_EXISTING) & Tier 2 (CONTROLLED_RETRY) on player1
        // Failure 3 -> Tier 3 (REBUILD_PLAYER): releases player1 BEFORE creating player2
        repeat(3) { attempt ->
            composeTestRule.runOnUiThread {
                player1.simulateFatalMediaCodecReclaim(
                    errorCodeName = "ERROR_CODE_DECODING_FAILED",
                    errorMessage = "Repeated codec failure #$attempt",
                )
            }
            composeTestRule.waitForIdle()
        }

        assertEquals("Existing player prepareCalls bounded to initial + 2 retries", 3, player1.prepareCalls)
        assertTrue("Old player must be released before replacement is created", player1.isReleased)
        assertEquals("Last-resort rebuild must create 1 replacement player", 2, players.size)
        val player2 = players[1]
        assertEquals(1, player2.prepareCalls)
        assertEquals("Never more than 1 active non-released player", 1, players.count { !it.isReleased })

        // Failures on player2 exhaust rebuild budget -> enters FAILED without infinite loop
        repeat(2) { attempt ->
            composeTestRule.runOnUiThread {
                player2.simulateFatalMediaCodecReclaim(
                    errorCodeName = "ERROR_CODE_DECODING_FAILED",
                    errorMessage = "Rebuilt player failure #$attempt",
                )
            }
            composeTestRule.waitForIdle()
        }

        assertEquals("Rebuilt player must not loop prepare() after exhaustion", 1, player2.prepareCalls)
        assertEquals("No extra ExoPlayer rebuild beyond bounded limit", 2, players.size)
        assertEquals("Video must stay hidden at alpha=0f when recovery fails", 0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(
            "Must log BACKGROUND_VIDEO_RECOVERY_FAILED when repeated recovery attempts are exhausted",
            logs.any { it.contains("BACKGROUND_VIDEO_RECOVERY_FAILED") && it.contains("frameReady=false") },
        )
    }

    // 46. Etapa 3M (13, 14): Troca de MediaItem (condição meteorológica) e de velocidade não desmonta o wallpaper, não recria o player e esconde vídeo até o novo primeiro frame
    @Test
    fun `test_46_changing_weather_condition_and_speed_keeps_same_player_and_hides_video_until_new_first_frame`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var condition by mutableStateOf(WeatherCondition.SOL)
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = condition,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        assertEquals(1.0f, player.currentPlaybackSpeed, 0.001f)

        // Hold first frame on next media change so we verify alpha snaps to 0f during transition
        player.autoFirstFrameOnPrepare = false
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA_FRACA // rawRes = wx_bg_rain, speed = 0.85f
        }
        composeTestRule.waitForIdle()

        assertEquals("Must reuse the exact same ExoPlayer instance across condition changes", 1, players.size)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        assertEquals("Video must be hidden (alpha=0f) until new MediaItem renders its first frame", 0f, observedVideoAlpha, 0.001f)
        assertEquals("Playback speed must update to 0.85f on the same player", 0.85f, player.currentPlaybackSpeed, 0.001f)
        assertEquals("prepare() must be called once for initial and once for new MediaItem", 2, player.prepareCalls)

        val logsDuringSwitch = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logsDuringSwitch.any { it.contains("BACKGROUND_MEDIA_ITEM_CHANGED") })

        // Render first frame of new MediaItem -> video fades back in
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 47. Etapa 3M/3P: Fechar o seletor de mapa não mostra vídeo antes de onRenderedFirstFrame confirmar frame válido na superfície reanexada
    @Test
    fun `test_47_returning_from_map_to_home_keeps_video_hidden_until_first_frame_on_reattached_surface`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
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
                            FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                        onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx) },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)

        // Open MapLocationPicker -> video surface detaches and videoAlpha snaps immediately to 0f
        player.autoFirstFrameOnPlayAfterReattach = false
        composeTestRule.runOnUiThread {
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)

        // Close MapLocationPicker -> surface reattaches, but before onRenderedFirstFrame fires on the reattached surface,
        // videoAlpha MUST remain 0f (static wallpaper visible)
        composeTestRule.runOnUiThread {
            isMapPickerOpen = false
        }
        composeTestRule.waitForIdle()

        assertTrue(surfaces.single().isPlayerAttached(player))
        assertEquals(
            "Video must NOT become visible on closing map picker before onRenderedFirstFrame confirms a valid frame on the reattached surface",
            0f,
            observedVideoAlpha,
            0.001f,
        )

        // Once onRenderedFirstFrame fires on the reattached surface, video fades in to 1f
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 48. Etapa 3M (17, 18): LocationPickerDialog e recomposições na HOME não reiniciam fades nem alteram o estado visual do vídeo
    @Test
    fun `test_48_location_picker_and_recompositions_do_not_restart_fades_or_alter_visual_state`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val alphaHistory = mutableListOf<Float>()
        var showLocationModal by mutableStateOf(false)
        var recomposeCounter by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text("tick-$recomposeCounter")
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        playerBridgeFactory = {
                            FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                        onEffectiveVideoAlphaChanged = { alphaHistory += it },
                    )
                    if (showLocationModal) {
                        Text("LocationPickerDialogOverlay")
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, alphaHistory.last(), 0.001f)

        val fadeInCountBefore =
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("BACKGROUND_VIDEO_FADE_IN") }
        assertEquals(1, fadeInCountBefore)

        // Open and close LocationPickerDialog and trigger 10 recompositions
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "OPEN")
            showLocationModal = true
        }
        composeTestRule.waitForIdle()
        repeat(10) {
            composeTestRule.runOnUiThread { recomposeCounter++ }
            composeTestRule.waitForIdle()
        }
        composeTestRule.runOnUiThread {
            ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "CLOSE")
            showLocationModal = false
        }
        composeTestRule.waitForIdle()

        val fadeInCountAfter =
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("BACKGROUND_VIDEO_FADE_IN") }
        assertEquals("Fades must not restart across modal open/close or recompositions", 1, fadeInCountAfter)
        assertEquals(1f, alphaHistory.last(), 0.001f)
    }

    // 49. Etapa 3M (19): Fundo animado desativado respeita a preferência do usuário (não monta vídeo, mantém wallpaper estático)
    @Test
    fun `test_49_animated_background_disabled_respects_user_preference_and_keeps_static_wallpaper`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.TEMPESTADE,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = false,
                    playerBridgeFactory = { FakeWeatherVideoPlayerBridge().also { players += it } },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        assertTrue("No ExoPlayer instance should be created when animatedEnabled=false", players.isEmpty())
        assertEquals(0f, observedVideoAlpha, 0.001f)
        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(logs.any { it.contains("BACKGROUND_WALLPAPER_VISIBLE") && it.contains("reason=animated_disabled") })
    }

    // 50. Etapa 3M (20): Ciclo ON_PAUSE/ON_STOP e ON_START/ON_RESUME não provoca oscilações repetidas nem prepare() duplicado
    @Test
    fun `test_50_activity_pause_stop_and_resume_cycle_does_not_cause_repeated_oscillations`() {
        val lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                Clima3DTheme(darkTheme = true) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        playerBridgeFactory = {
                            FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                        onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        assertEquals(1, player.prepareCalls)

        // Move Activity to background (ON_PAUSE -> ON_STOP)
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        composeTestRule.waitForIdle()

        assertEquals("Video layer must hide cleanly when Activity is stopped", 0f, observedVideoAlpha, 0.001f)
        assertEquals(1, player.pauseCalls)
        assertEquals(1, player.prepareCalls)

        // Return Activity to foreground (ON_START -> ON_RESUME)
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeTestRule.waitForIdle()

        assertEquals("Video layer must restore smoothly on Activity resume", 1f, observedVideoAlpha, 0.001f)
        assertEquals("prepare() must NOT be re-invoked on normal ON_STOP -> ON_RESUME cycle", 1, player.prepareCalls)
        assertEquals(1, player.playCalls)
    }

    // 51. Etapa 3N (3, 11 #2, #3, #8): Callbacks tardios/obsoletos de onRenderedFirstFrame (geração antiga, decoder liberado, MediaItem anterior ou superfície antiga) são descartados e nunca tornam o vídeo visível
    @Test
    fun `test_51_stale_first_frame_callbacks_from_old_decoder_generation_old_media_or_detached_surface_are_discarded`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var condition by mutableStateOf(WeatherCondition.SOL)
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = condition,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        assertEquals(1, player.decoderGeneration)

        // Hold automatic first frame on next prepare() so we can inject stale callbacks
        player.autoFirstFrameOnPrepare = false
        composeTestRule.runOnUiThread {
            player.simulateFatalMediaCodecReclaim(
                decoderName = "c2.android.avc.decoder",
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "Codec error: ERROR_RECLAIMED",
            )
        }
        composeTestRule.waitForIdle()

        // Recovery prepare() has initialized decoderGeneration = 2, waiting for gen 2's first frame
        assertEquals(2, player.decoderGeneration)
        assertEquals("Video must be hidden (0f) while waiting for gen 2 first frame", 0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        // 1. Late callback from OLD decoder generation (gen 1 when active is gen 2) -> MUST be discarded
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day,
                    decoderGeneration = 1,
                    surfaceEpoch = player.surfaceEpoch,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Stale callback from old decoder generation (gen=1) must NOT reveal video", 0f, observedVideoAlpha, 0.001f)

        // 2. Late callback claiming decoderAlive = false -> MUST be discarded
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day,
                    decoderGeneration = 2,
                    surfaceEpoch = player.surfaceEpoch,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = false,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Callback from dead/released decoder must NOT reveal video", 0f, observedVideoAlpha, 0.001f)

        // 3. Late callback from a detached/old surface epoch -> MUST be discarded
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day,
                    decoderGeneration = 2,
                    surfaceEpoch = player.surfaceEpoch - 1,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Stale callback from previous surfaceEpoch must NOT reveal video", 0f, observedVideoAlpha, 0.001f)

        // 4. Switch weather condition to CHUVA (new MediaItem) and fire a late callback for the old MediaItem (SOL)
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA
        }
        composeTestRule.waitForIdle()
        assertEquals(3, player.decoderGeneration)
        assertEquals(0f, observedVideoAlpha, 0.001f)

        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day, // stale MediaItem!
                    decoderGeneration = 3,
                    surfaceEpoch = player.surfaceEpoch,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Stale callback from previous MediaItem (wx_bg_sun_day) must NOT reveal video", 0f, observedVideoAlpha, 0.001f)

        // 5. Finally emit the authentic first frame for the active MediaItem (CHUVA) & active decoder generation (gen 3)
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals("Valid first frame matching all 5 axes must restore videoAlpha to 1f", 1f, observedVideoAlpha, 0.001f)
    }

    // 52. Etapa 3N (4, 11 #4, #5): Buffering transitório com frame válido preserva exibição sem oscilação, enquanto buffering após perda do decoder oculta o vídeo
    @Test
    fun `test_52_transient_buffering_with_valid_frame_preserves_video_visibility_without_oscillation_or_fade_restart`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val alphaHistory = mutableListOf<Float>()

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { alphaHistory += it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, alphaHistory.last(), 0.001f)
        val initialFadeInCount =
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("BACKGROUND_VIDEO_FADE_IN") }
        assertEquals(1, initialFadeInCount)

        // 1. Transient STATE_BUFFERING -> STATE_READY while decoder gen 1 is alive and surface remains attached
        val alphaCountBeforeTransientBuffering = alphaHistory.size
        composeTestRule.runOnUiThread {
            player.simulatePlaybackStateChange("BUFFERING", isPlaying = false)
        }
        composeTestRule.waitForIdle()
        assertEquals("Transient BUFFERING with valid frame must preserve videoAlpha=1f", 1f, alphaHistory.last(), 0.001f)

        composeTestRule.runOnUiThread {
            player.simulatePlaybackStateChange("READY", isPlaying = true)
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, alphaHistory.last(), 0.001f)
        assertTrue(
            "videoAlpha must never dip below 1f during transient BUFFERING",
            alphaHistory.drop(alphaCountBeforeTransientBuffering).all { it == 1f },
        )
        val fadeInCountAfterTransientBuffering =
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("BACKGROUND_VIDEO_FADE_IN") }
        assertEquals("Fade-in must not restart after transient BUFFERING", 1, fadeInCountAfterTransientBuffering)

        // 2. Now release decoder (real frame loss) followed by BUFFERING and READY without onRenderedFirstFrame
        composeTestRule.runOnUiThread {
            player.simulateDecoderReleasedOnly()
            player.simulatePlaybackStateChange("BUFFERING", isPlaying = false)
        }
        composeTestRule.waitForIdle()
        assertEquals("BUFFERING after decoder release must hide video immediately (alpha=0f)", 0f, alphaHistory.last(), 0.001f)

        // Even if player reports STATE_READY, without onRenderedFirstFrame for a new alive decoder generation, video MUST stay hidden
        composeTestRule.runOnUiThread {
            player.simulatePlaybackStateChange("READY", isPlaying = true)
        }
        composeTestRule.waitForIdle()
        assertEquals("STATE_READY alone must never reveal video without onRenderedFirstFrame", 0f, alphaHistory.last(), 0.001f)
    }

    // 53. Etapa 3N (5, 11 #9, #10, #11): Callbacks redundantes de onRenderedFirstFrame e mudança entre condições que compartilham o mesmo vídeo (CHUVA <-> CHUVA_FRACA 0.85x) não reiniciam fade nem piscam
    @Test
    fun `test_53_redundant_first_frame_callbacks_and_same_video_condition_changes_do_not_restart_fade_or_flicker`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val alphaHistory = mutableListOf<Float>()
        var condition by mutableStateOf(WeatherCondition.CHUVA)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = condition,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { alphaHistory += it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, alphaHistory.last(), 0.001f)
        val historyIndexAfterInitialFade = alphaHistory.size

        // Emit 3 redundant onRenderedFirstFrame callbacks for the same active MediaItem & decoderGeneration
        repeat(3) {
            composeTestRule.runOnUiThread {
                player.emitFirstFrameRendered()
            }
            composeTestRule.waitForIdle()
        }

        // Switch CHUVA (1.0x) -> CHUVA_FRACA (0.85x) -> CHUVA (1.0x), which share R.raw.wx_bg_rain_day
        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA_FRACA
        }
        composeTestRule.waitForIdle()
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        composeTestRule.runOnUiThread {
            condition = WeatherCondition.CHUVA
        }
        composeTestRule.waitForIdle()
        assertEquals(1.0f, player.currentPlaybackSpeed, 0.001f)

        assertEquals("prepare() must only run once because rawRes did not change", 1, player.prepareCalls)
        assertEquals("videoAlpha must remain at 1f throughout", 1f, alphaHistory.last(), 0.001f)
        assertTrue(
            "No dip to 0f may occur during redundant first-frame callbacks or speed switches on the same video",
            alphaHistory.drop(historyIndexAfterInitialFade).all { it == 1f },
        )

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertEquals(
            "BACKGROUND_VIDEO_FADE_IN must be logged exactly once",
            1,
            logs.count { it.contains("BACKGROUND_VIDEO_FADE_IN") },
        )
        assertEquals(
            "BACKGROUND_VIDEO_FRAME_READY must be deduplicated for identical token",
            1,
            logs.count { it.contains("BACKGROUND_VIDEO_FRAME_READY") },
        )
    }

    // 54. Etapa 3N (6, 11 #16): Ciclo ON_PAUSE -> ON_RESUME (sem ON_STOP) pausa e retoma o player mantendo videoAlpha == 1f sem nenhuma oscilação
    @Test
    fun `test_54_on_pause_to_on_resume_without_on_stop_keeps_video_and_wallpaper_stable_with_zero_alpha_oscillation`() {
        val lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val alphaHistory = mutableListOf<Float>()

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                Clima3DTheme(darkTheme = true) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.SOL,
                        activeTab = ClimaTab.HOME,
                        animatedEnabled = true,
                        playerBridgeFactory = {
                            FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                        onEffectiveVideoAlphaChanged = { alphaHistory += it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, alphaHistory.last(), 0.001f)
        val idxAfterReady = alphaHistory.size

        // Simulate ON_PAUSE -> ON_RESUME (Activity stays STARTED, e.g. system overlay / dialog)
        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, player.pauseCalls)
        assertEquals(1f, alphaHistory.last(), 0.001f)

        composeTestRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, player.playCalls)
        assertEquals(1, player.prepareCalls)
        assertEquals(1f, alphaHistory.last(), 0.001f)
        assertTrue(
            "videoAlpha must not oscillate during ON_PAUSE -> ON_RESUME",
            alphaHistory.drop(idxAfterReady).all { it == 1f },
        )
        val fadeCount =
            ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("BACKGROUND_VIDEO_FADE_IN") }
        assertEquals(1, fadeCount)
    }

    // 55. Etapa 3N/3P: NightRainOverlay, wallpaper estático e scrim atmosférico permanecem íntegros durante falhas do decoder, trocas de abas e abertura/fechamento do seletor no mapa
    @Test
    fun `test_55_night_rain_overlay_and_atmospheric_scrim_remain_mounted_across_codec_errors_and_surface_switches`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var activeTab by mutableStateOf(ClimaTab.HOME)
        var isMapPickerOpen by mutableStateOf(false)
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WeatherBackgroundLayerImpl(
                        condition = WeatherCondition.NOITE_CHUVA,
                        activeTab = activeTab,
                        animatedEnabled = true,
                        isMapLocationPickerOpen = isMapPickerOpen,
                        playerBridgeFactory = {
                            FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                        onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx) },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        composeTestRule.onNodeWithTag("weather_night_rain_overlay").assertExists()
        composeTestRule.onNodeWithTag("weather_atmospheric_scrim").assertExists()

        // Simulate MediaCodec hardware fault before recovery completes
        player.autoFirstFrameOnPrepare = false
        composeTestRule.runOnUiThread {
            player.simulateVideoCodecFaultBeforePlayerError()
        }
        composeTestRule.waitForIdle()

        // Video must snap immediately to 0f while static wallpaper, NightRainOverlay, and atmospheric scrim stay mounted
        assertEquals(0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        composeTestRule.onNodeWithTag("weather_night_rain_overlay").assertExists()
        composeTestRule.onNodeWithTag("weather_atmospheric_scrim").assertExists()

        // Navigate HOME -> RADAR -> CITIES -> open/close MapLocationPicker -> HOME
        player.autoFirstFrameOnPlayAfterReattach = false
        composeTestRule.runOnUiThread { activeTab = ClimaTab.RADAR }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        composeTestRule.onNodeWithTag("weather_atmospheric_scrim").assertExists()

        composeTestRule.runOnUiThread {
            activeTab = ClimaTab.CITIES
            isMapPickerOpen = true
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        composeTestRule.onNodeWithTag("weather_atmospheric_scrim").assertExists()

        composeTestRule.runOnUiThread {
            activeTab = ClimaTab.HOME
            isMapPickerOpen = false
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        composeTestRule.onNodeWithTag("weather_night_rain_overlay").assertExists()
        composeTestRule.onNodeWithTag("weather_atmospheric_scrim").assertExists()
    }

    // 56. Etapa 3N (2A, 3, 11 #18): Perda de SurfaceTexture ou detach da janela oculta imediatamente o vídeo e exige novo primeiro frame na superfície válida
    @Test
    fun `test_56_surface_texture_loss_or_window_detach_immediately_hides_video_until_new_frame_on_valid_surface`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)

        val staleEpochBeforeLoss = player.surfaceEpoch

        // Simulate TextureView losing its SurfaceTexture (textureAvailable = false)
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = false, hasSurfaceTexture = false)
        }
        composeTestRule.waitForIdle()

        assertEquals("Video must snap immediately to 0f when SurfaceTexture is lost", 0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()
        composeTestRule.onNodeWithTag("weather_atmospheric_scrim").assertExists()

        // Late frame token from before SurfaceTexture loss must be rejected
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day,
                    decoderGeneration = player.decoderGeneration,
                    surfaceEpoch = staleEpochBeforeLoss,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)

        // Restore SurfaceTexture availability and emit a new valid first frame on the current surfaceEpoch
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = true, hasSurfaceTexture = true)
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 57. Etapa 3O (5 #1, #2): Callback de primeiro frame pertencente a geração antiga ou recebido após desanexação da superfície (mesmo durante VIDEO_PREPARING/WAITING_FIRST_FRAME) é rejeitado com diagnóstico explícito
    @Test
    fun `test_57_etapa3o_first_frame_callback_after_surface_detach_during_initial_preparing_and_old_decoder_generation_are_rejected`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        // Do NOT auto-emit first frame on initial prepare: stay in VIDEO_WAITING_FIRST_FRAME
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = false).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        val epochBeforeDetach = player.surfaceEpoch

        // 1. Detach surface while still waiting for the very first frame (hasValidRenderedFrame == false)
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = false, textureAvailable = false, hasSurfaceTexture = false)
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)

        // 2. Callback arrives after surface detachment -> MUST be rejected even with current epoch or old epoch
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day,
                    decoderGeneration = player.decoderGeneration,
                    surfaceEpoch = epochBeforeDetach,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals("First frame callback while surface is detached must be rejected", 0f, observedVideoAlpha, 0.001f)

        // 3. Reattach surface, advance decoder to generation 2, and fire a stale callback for generation 1
        composeTestRule.runOnUiThread {
            surface.emitSurfaceAvailability(attached = true, textureAvailable = true, hasSurfaceTexture = true)
            player.prepare() // advances decoderGeneration to 2
        }
        composeTestRule.waitForIdle()
        assertEquals(2, player.decoderGeneration)
        assertEquals(0f, observedVideoAlpha, 0.001f)

        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = R.raw.wx_bg_sun_day,
                    decoderGeneration = 1, // old generation
                    surfaceEpoch = player.surfaceEpoch,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Stale callback from decoderGeneration=1 must be rejected", 0f, observedVideoAlpha, 0.001f)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(
            "Expected BACKGROUND_VIDEO_STALE_FRAME_REJECTED for detached surface",
            logs.any { it.contains("BACKGROUND_VIDEO_STALE_FRAME_REJECTED") && it.contains("stale_or_detached_surface_epoch") },
        )
        assertTrue(
            "Expected BACKGROUND_VIDEO_STALE_FRAME_REJECTED for old decoder generation",
            logs.any { it.contains("BACKGROUND_VIDEO_STALE_FRAME_REJECTED") && it.contains("stale_decoder_gen_1_expected_2") },
        )

        // 4. Valid first frame for generation 2 on attached surface succeeds
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 58. Etapa 3O (2, 3, 5 #3, #5): Erro de codec antes de onPlayerError invalida imediatamente o frame, bloqueia callbacks tardios na janela intermediária e recupera preservando posição e velocidade
    @Test
    fun `test_58_etapa3o_codec_error_before_on_player_error_immediately_blocks_frames_and_recovers_preserving_position_and_speed`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val surfaces = mutableListOf<FakeWeatherVideoSurfaceBridge>()
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.CHUVA_FRACA, // 0.85x speed
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx).also { surfaces += it } },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        val surface = surfaces.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        assertEquals(0.85f, player.currentPlaybackSpeed, 0.001f)

        // Advance playback position to 4320ms
        player.simulatedPositionMs = 4320L
        player.autoFirstFrameOnPrepare = false

        // Step 1: Fire AnalyticsListener.onVideoCodecError BEFORE Player.Listener.onPlayerError
        composeTestRule.runOnUiThread {
            player.simulateVideoCodecFaultBeforePlayerError()
        }
        composeTestRule.waitForIdle()

        // Must immediately clamp videoAlpha to 0f and enter VIDEO_RECOVERING before onPlayerError arrives
        assertEquals("onVideoCodecError must hide video immediately before onPlayerError", 0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        // Step 2: Inject a queued onRenderedFirstFrame callback in the gap between onVideoCodecError and onPlayerError
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRenderedWithToken(
                com.example.ui.components.WeatherVideoFrameToken(
                    sessionId = player.sessionId,
                    playerIdentity = player.identityHash,
                    videoResId = WeatherCondition.CHUVA_FRACA.backgroundVideo.rawRes,
                    decoderGeneration = 1,
                    surfaceEpoch = player.surfaceEpoch,
                    surfaceViewIdentity = surface.viewIdentity,
                    decoderAlive = true,
                ),
            )
        }
        composeTestRule.waitForIdle()
        assertEquals("Queued frame in gap between onVideoCodecError and onPlayerError must be blocked", 0f, observedVideoAlpha, 0.001f)

        // Step 3: Now Player.Listener.onPlayerError arrives (ERROR_RECLAIMED), triggering recovery on the SAME player
        composeTestRule.runOnUiThread {
            player.simulateFatalMediaCodecReclaim(
                decoderName = "c2.android.avc.decoder",
                errorCodeName = "ERROR_CODE_DECODING_FAILED",
                errorMessage = "MediaCodec released by resource manager",
            )
        }
        composeTestRule.waitForIdle()

        assertEquals("Same ExoPlayer instance must be preserved", 1, players.size)
        assertEquals("Recovery must seek back to saved position 4320ms", 4320L, player.seekHistory.last())
        assertEquals("Recovery must preserve 0.85x speed", 0.85f, player.currentPlaybackSpeed, 0.001f)
        assertEquals("Video must remain hidden (0f) until new decoder generation renders its first frame", 0f, observedVideoAlpha, 0.001f)

        // Step 4: Emit valid first frame for recovered decoder generation 2
        composeTestRule.runOnUiThread {
            player.emitFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertEquals(1f, observedVideoAlpha, 0.001f)
    }

    // 59. Etapa 3O/3P.3: Falha definitiva do decoder após Tier 1, Tier 2 e Tier 3 (rebuild único) entra em FAILED mantendo o wallpaper estático visível sem loop infinito
    @Test
    fun `test_59_etapa3o_definitive_codec_failure_after_retry_limit_keeps_static_wallpaper_without_infinite_loop`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        var autoFirstFrame = true
        var observedVideoAlpha = -1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.TEMPESTADE,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    playerBridgeFactory = {
                        FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = autoFirstFrame).also { players += it }
                    },
                    surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        val player1 = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)
        assertEquals(1, player1.prepareCalls)

        // Subsequent recovery prepare() calls and any rebuilt player fail to produce a first frame
        autoFirstFrame = false
        player1.autoFirstFrameOnPrepare = false

        // Failure #1 -> Tier 1 (RECOVER_EXISTING, prepareCalls becomes 2)
        composeTestRule.runOnUiThread {
            player1.simulateVideoCodecFaultBeforePlayerError()
            player1.simulateFatalMediaCodecReclaim(errorCodeName = "ERROR_CODE_DECODING_FAILED_1")
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        assertEquals(2, player1.prepareCalls)
        assertEquals(1, players.size)

        // Failure #2 -> Tier 2 (CONTROLLED_RETRY, prepareCalls becomes 3)
        composeTestRule.runOnUiThread {
            player1.simulateVideoCodecFaultBeforePlayerError()
            player1.simulateFatalMediaCodecReclaim(errorCodeName = "ERROR_CODE_DECODING_FAILED_2")
        }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        assertEquals(3, player1.prepareCalls)
        assertEquals(1, players.size)

        // Failure #3 -> Tier 3 (REBUILD_PLAYER): releases player1 BEFORE creating player2
        composeTestRule.runOnUiThread {
            player1.simulateVideoCodecFaultBeforePlayerError()
            player1.simulateFatalMediaCodecReclaim(errorCodeName = "ERROR_CODE_DECODING_FAILED_3")
        }
        composeTestRule.waitForIdle()
        assertEquals(3, player1.prepareCalls)
        assertTrue("player1 must be released on Tier 3 rebuild", player1.isReleased)
        assertEquals(2, players.size)
        val player2 = players[1]
        assertTrue("player1 must be released before player2 creation", player1.releaseOrder < player2.creationOrder)
        assertEquals(1, player2.prepareCalls)
        assertEquals(0f, observedVideoAlpha, 0.001f)

        // Failure #4 on player2 -> budget exhausted! Must enter FAILED and stop prepare() (no infinite loop)
        composeTestRule.runOnUiThread {
            player2.simulateVideoCodecFaultBeforePlayerError()
            player2.simulateFatalMediaCodecReclaim(errorCodeName = "ERROR_CODE_DECODING_FAILED_4")
        }
        composeTestRule.waitForIdle()
        assertEquals("Recovery must stop after bounded existing + rebuild attempts (no infinite loop)", 1, player2.prepareCalls)
        assertEquals(2, players.size)
        assertEquals("Video must stay hidden at 0f on definitive failure", 0f, observedVideoAlpha, 0.001f)
        composeTestRule.onNodeWithTag("weather_static_wallpaper").assertExists()

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertTrue(
            "Expected BACKGROUND_VIDEO_RECOVERY_FAILED milestone",
            logs.any { it.contains("BACKGROUND_VIDEO_RECOVERY_FAILED") && it.contains("exhausted_after_2_existing_1_rebuild") },
        )
    }

    // 60. Etapa 3O/3P: Abertura/fechamento rápido do seletor de localização no mapa respeita a ordem de 7 passos das superfícies e bloqueia reanexação do vídeo antes da liberação total do mapa
    @Test
    fun `test_60_etapa3o_rapid_tab_switching_home_radar_cities_enforces_7_step_surface_order_and_blocks_video_reattach_before_map_release`() {
        val players = mutableListOf<FakeWeatherVideoPlayerBridge>()
        val cityPorts = mutableListOf<FakeMapViewSurfacePort>()
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
                            FakeWeatherVideoPlayerBridge(autoFirstFrameOnPrepare = true).also { players += it }
                        },
                        surfaceBridgeFactory = { ctx -> FakeWeatherVideoSurfaceBridge(ctx) },
                        onEffectiveVideoAlphaChanged = { observedVideoAlpha = it },
                    )
                    if (isMapPickerOpen) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> FakeMapViewSurfacePort(ctx).also { cityPorts += it } },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        val player = players.single()
        assertEquals(1f, observedVideoAlpha, 0.001f)

        // Open and close on-demand MapLocationPicker while verifying strict 7-step ordering
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
        GraphicsSurfaceCoordinator.onVideoOutputReattached(
            sessionId = player.sessionId,
            playerIdentity = player.identityHash,
            viewIdentity = 101,
        )

        composeTestRule.runOnUiThread { isMapPickerOpen = true }
        composeTestRule.waitForIdle()
        assertEquals(0f, observedVideoAlpha, 0.001f)
        assertEquals(1, cityPorts.size)
        cityPorts.last().emitAttach(true)

        // Verify that before returning to HOME, if a map session is still disposing (Step 4 in progress, Step 6 not yet done),
        // video reattachment is strictly blocked (Item 5 #8)
        GraphicsSurfaceCoordinator.onMapSurfaceCreated(
            screen = "HoldDisposing",
            sessionId = "hold-map-disposing",
            viewIdentity = 9999,
        )
        GraphicsSurfaceCoordinator.onMapSurfaceDisposing(
            screen = "HoldDisposing",
            sessionId = "hold-map-disposing",
            viewIdentity = 9999,
            stage = "onDispose",
        )

        composeTestRule.runOnUiThread {
            cityPorts.last().emitAttach(false)
            isMapPickerOpen = false
        }
        composeTestRule.waitForIdle()

        // Because "hold-map-disposing" has not completed Step 6 (onMapSurfaceDetached), video output MUST NOT reattach yet!
        assertFalse(GraphicsSurfaceCoordinator.state.value.canAttachVideoOutput)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals("Video must remain hidden (0f) while map surface is still disposing", 0f, observedVideoAlpha, 0.001f)

        // Now complete Step 6 for "hold-map-disposing" -> video immediately reattaches (Step 7) and renders first frame
        composeTestRule.runOnUiThread {
            GraphicsSurfaceCoordinator.onMapSurfaceDetached(
                screen = "HoldDisposing",
                sessionId = "hold-map-disposing",
                viewIdentity = 9999,
            )
        }
        composeTestRule.waitForIdle()

        assertTrue(GraphicsSurfaceCoordinator.state.value.canAttachVideoOutput)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals("Same ExoPlayer instance must be preserved across map picker open/close", 1, players.size)
        assertEquals(1f, observedVideoAlpha, 0.001f)

        // Verify the 7-step lifecycle ordering in diagnostics logs:
        // 1. VIDEO_OUTPUT_DETACH_FOR_MAP -> 2. EXO_SURFACE_CLEAR / VIDEO_OUTPUT_DETACHED ->
        // 3. MAP_CREATE / MAP_SURFACE_CREATED -> 4. MAP_DISPOSE_REQ / MAP_SURFACE_DISPOSING ->
        // 5. MAP_VIEW_DETACH -> 6. MAP_DESTROYED / MAP_SURFACE_DETACHED -> 7. VIDEO_OUTPUT_REATTACHED
        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        val idxStep1 = logs.indexOfFirst { it.contains("VIDEO_OUTPUT_DETACH_FOR_MAP") }
        val idxStep2 = logs.indexOfFirst { it.contains("EXO_SURFACE_CLEAR") }
        val idxStep3 = logs.indexOfFirst { it.contains("MAP_SURFACE_CREATED screen=Cidades") }
        val idxStep4 = logs.indexOfFirst { it.contains("MAP_SURFACE_DISPOSING screen=Cidades") }
        val idxStep5 = logs.indexOfFirst { it.contains("MAP_VIEW_DETACH screen=Cidades") }
        val idxStep6 = logs.indexOfFirst { it.contains("MAP_DESTROYED screen=Cidades") }
        val idxStep7 = logs.indexOfLast { it.contains("VIDEO_OUTPUT_REATTACHED") }

        assertTrue("Step 1 (VIDEO_OUTPUT_DETACH_FOR_MAP) must occur", idxStep1 >= 0)
        assertTrue("Step 2 (EXO_SURFACE_CLEAR) must follow Step 1", idxStep2 > idxStep1)
        assertTrue("Step 3 (MAP_SURFACE_CREATED) must follow Step 2", idxStep3 > idxStep2)
        assertTrue("Step 4 (MAP_SURFACE_DISPOSING) must follow Step 3", idxStep4 > idxStep3)
        assertTrue("Step 5 (MAP_VIEW_DETACH) must occur before Step 6", idxStep5 in (idxStep3 + 1) until idxStep6)
        assertTrue("Step 6 (MAP_DESTROYED) must follow Step 4", idxStep6 > idxStep4)
        assertTrue("Step 7 (VIDEO_OUTPUT_REATTACHED) must follow Step 6", idxStep7 > idxStep6)
    }

    // 61. Etapa 3O (4 #6, 5 #7): Destruição duplicada de MapView é idempotente e destrói a instância nativa exatamente uma única vez
    @Test
    fun `test_61_etapa3o_duplicate_map_view_destruction_is_idempotent_and_destroys_instance_only_once`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val port = FakeMapViewSurfacePort(context)
        var cancelTransitionsCount = 0
        val controller =
            MapViewLifecycleController(
                onCreate = { port.onCreate() },
                onStart = { port.onStart() },
                onResume = { port.onResume() },
                onPause = { port.onPause() },
                onStop = { port.onStop() },
                onDestroy = { port.onDestroy() },
                onLowMemory = { port.onLowMemory() },
            )
        controller.syncToLifecycleState(Lifecycle.State.RESUMED)
        assertEquals(1, port.createCount)
        assertEquals(1, port.startCount)
        assertEquals(1, port.resumeCount)

        val detachListeners = port.attachListeners(
            MapSurfaceCallbacks(
                onWillStartLoading = {},
                onDidFinishLoadingStyle = {},
                onDidFinishLoadingMap = {},
                onDidFailLoadingMap = {},
                onWillStartRenderingMap = {},
                onFirstFrameRendered = {},
                onDidFinishRenderingMap = {},
                onRenderError = {},
            ),
        )
        val detachWindow = port.attachWindowObserver { _, _ -> }

        val sessionHandle =
            MapViewSessionHandle(
                screenName = "Radar",
                sessionId = "radar-dup-destroy-test",
                surfacePort = port,
                controller = controller,
                onDetachListeners = detachListeners,
                onDetachWindowObserver = detachWindow,
                onCancelTransitions = { cancelTransitionsCount++ },
            )
        assertTrue(sessionHandle.isSessionActive)
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)

        // Call pauseAndStopBeforeDetach multiple times and destroyOnceAfterDetach 3 times
        sessionHandle.pauseAndStopBeforeDetach("onDispose")
        assertFalse("Session must be marked inactive immediately on pauseAndStopBeforeDetach", sessionHandle.isSessionActive)
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_DISPOSING, GraphicsSurfaceCoordinator.state.value.mapState)

        sessionHandle.pauseAndStopBeforeDetach("duplicate_dispose")
        sessionHandle.destroyOnceAfterDetach()
        sessionHandle.destroyOnceAfterDetach()
        sessionHandle.destroyOnceAfterDetach()
        controller.destroy()

        assertEquals("MapView.onPause() must execute exactly once", 1, port.pauseCount)
        assertEquals("MapView.onStop() must execute exactly once", 1, port.stopCount)
        assertEquals("MapView.onDestroy() must execute exactly once despite duplicate calls", 1, port.destroyCount)
        assertEquals("Listeners must be detached exactly once", 1, port.listenerDetachCount)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)

        val logs = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
        assertEquals(
            "MAP_DESTROYED must be logged exactly once",
            1,
            logs.count { it.contains("MAP_DESTROYED") && it.contains("session=radar-dup-destroy-test") },
        )
        assertEquals(
            "MAP_SURFACE_DETACHED must be logged exactly once",
            1,
            logs.count { it.contains("MAP_SURFACE_DETACHED") && it.contains("session=radar-dup-destroy-test") },
        )
    }

    // 62. Etapa 3O (4, 5 #9): Eventos e callbacks atrasados de uma sessão anterior (MapView ou ExoPlayer) não modificam o estado da sessão nova ativa
    @Test
    fun `test_62_etapa3o_late_events_from_previous_map_and_video_sessions_cannot_corrupt_new_active_session`() {
        val createdMapPorts = mutableListOf<FakeMapViewSurfacePort>()
        val tappedCoordinates = mutableListOf<CityMapCoordinate>()
        var showMap by mutableStateOf(true)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                if (showMap) {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = { tappedCoordinates += it },
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx ->
                            // First port stays pending in INITIALIZING so retry triggers full viewRecreationNonce++
                            val autoReady = createdMapPorts.isNotEmpty()
                            FakeMapViewSurfacePort(ctx, autoReady = autoReady).also { createdMapPorts += it }
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, createdMapPorts.size)
        val oldPort = createdMapPorts[0]

        // Trigger a render error on oldPort so retry button appears, then click retry to recreate a new MapView session
        composeTestRule.runOnUiThread {
            oldPort.attachedCallbacks?.onRenderError("Falha simulada na sessão 1")
            oldPort.flushPendingUiPosts()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("city_map_retry_button").performClick()
        composeTestRule.waitForIdle()

        // New MapView session (newPort) is now created and active
        assertEquals(2, createdMapPorts.size)
        val newPort = createdMapPorts[1]
        composeTestRule.runOnUiThread {
            newPort.attachedCallbacks?.onFirstFrameRendered()
            newPort.attachedCallbacks?.onDidFinishRenderingMap(true)
            newPort.flushPendingUiPosts()
        }
        composeTestRule.waitForIdle()

        // Now fire LATE callbacks from the disposed oldPort (getMapAsync, onRenderError, onDidFailLoadingMap, mapTap)
        composeTestRule.runOnUiThread {
            oldPort.pendingMapReadyCallback?.invoke(oldPort.controllerPort)
            oldPort.attachedCallbacks?.onRenderError("Erro atrasado da sessão 1 já encerrada")
            oldPort.attachedCallbacks?.onDidFailLoadingMap("Falha de estilo atrasada da sessão 1")
            oldPort.flushPendingUiPosts()
            oldPort.controllerPort.simulateMapTap(CityMapCoordinate(-10.0, -50.0))
        }
        composeTestRule.waitForIdle()

        // Verify oldPort's late callbacks did NOT trigger the error overlay on newPort or register a fake tap
        composeTestRule.onNodeWithTag("city_map_retry_button").assertDoesNotExist()
        assertTrue("Taps from disposed MapView session must be ignored", tappedCoordinates.isEmpty())

        // Verify taps on the active newPort work normally
        composeTestRule.runOnUiThread {
            newPort.controllerPort.simulateMapTap(CityMapCoordinate(-23.55, -46.63))
        }
        composeTestRule.waitForIdle()
        assertEquals(1, tappedCoordinates.size)

        // Close map and verify stale ExoPlayer session detach cannot clobber active video session in GraphicsSurfaceCoordinator
        composeTestRule.runOnUiThread { showMap = false }
        composeTestRule.waitForIdle()

        GraphicsSurfaceCoordinator.onVideoOutputReattached(
            sessionId = "exo-active-new",
            playerIdentity = 2002,
            viewIdentity = 3003,
        )
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals("exo-active-new", GraphicsSurfaceCoordinator.state.value.activeVideoSessionId)

        // Fire a delayed detach from a previous video session ("exo-old-stale") and from an older viewIdentity (1111)
        GraphicsSurfaceCoordinator.onVideoOutputDetached(
            sessionId = "exo-old-stale",
            playerIdentity = 1001,
            viewIdentity = 1111,
            reason = "onDispose",
        )
        GraphicsSurfaceCoordinator.onVideoOutputDetached(
            sessionId = "exo-old-stale-2",
            playerIdentity = 2002,
            viewIdentity = 1111,
            reason = "onRelease",
        )

        assertEquals(
            "Stale video session/view detach must NOT clobber active video session",
            VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE,
            GraphicsSurfaceCoordinator.state.value.videoState,
        )
        assertEquals("exo-active-new", GraphicsSurfaceCoordinator.state.value.activeVideoSessionId)
        assertTrue(GraphicsSurfaceCoordinator.state.value.videoOutputAttached)
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

private class FakeWeatherVideoPlayerBridge(
    var autoFirstFrameOnPrepare: Boolean = true,
    var autoFirstFrameOnPlayAfterReattach: Boolean = true,
) : WeatherVideoPlayerBridge {
    override val sessionId: String = "fake-exo-${GlobalOrderClock.counter.incrementAndGet()}"
    override val identityHash: Int = System.identityHashCode(this)

    var released = false
    var idleState = true
    var currentPlayerState = "IDLE"
    var currentDecoderGen = 0
    var currentDecoderAlive = false
    var currentSurfaceEpoch = 0
    var attachedSurfaceViewId = 0
    var setMediaItemCalls = 0
    var prepareCalls = 0
    var stopCalls = 0
    var playCalls = 0
    var pauseCalls = 0
    var releaseCalls = 0
    var clearSurfaceCount = 0
    var seekToCalls = 0
    var lastPlayWhenReady: Boolean? = null
    val creationOrder = GlobalOrderClock.counter.incrementAndGet()
    var releaseOrder = Int.MAX_VALUE
    var simulatedPositionMs: Long = 0L
    var currentSpeedValue: Float = 1.0f
    var currentVideoResHint: Int = R.raw.wx_bg_sun_day
    private var needsFrameAfterSurfaceReattach = false
    val speedHistory = mutableListOf<Float>()
    val seekHistory = mutableListOf<Long>()
    val events = mutableListOf<String>()
    private var attachedView: Any? = null
    private var firstFrameListener: (() -> Unit)? = null
    private var firstFrameTokenListener: ((com.example.ui.components.WeatherVideoFrameToken) -> Unit)? = null
    private var decoderLifecycleListener: ((Boolean, Int, String) -> Unit)? = null
    private var playbackStateListener: ((String, Boolean) -> Unit)? = null
    private var codecErrorListener: ((com.example.ui.components.WeatherVideoCodecErrorDiagnostic) -> Unit)? = null

    override val isReleased: Boolean
        get() = released

    override val isIdle: Boolean
        get() = idleState

    override val playerStateName: String
        get() = if (released) "RELEASED" else currentPlayerState

    override val decoderGeneration: Int
        get() = currentDecoderGen

    override val isDecoderAlive: Boolean
        get() = !released && currentDecoderAlive

    override val surfaceEpoch: Int
        get() = currentSurfaceEpoch

    override val currentPositionMs: Long
        get() = simulatedPositionMs

    override val currentPlaybackSpeed: Float
        get() = currentSpeedValue

    override fun setVideoResIdHint(resId: Int) {
        currentVideoResHint = resId
    }

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

    override fun seekTo(positionMs: Long) {
        seekToCalls++
        simulatedPositionMs = positionMs
        seekHistory += positionMs
        events += "seekTo:$positionMs"
    }

    override fun prepare() {
        prepareCalls++
        idleState = false
        currentPlayerState = "READY"
        currentDecoderGen += 1
        currentDecoderAlive = true
        events += "prepare"
        ClimaGraphicsLifecycleDiagnostics.logExoDecoderInitialized(
            sessionId = sessionId,
            playerIdentity = identityHash,
            trackType = "VIDEO",
            decoderName = "c2.android.avc.decoder",
            initDurationMs = 12L,
            playerState = "READY",
            videoResId = currentVideoResHint,
            positionMs = simulatedPositionMs,
        )
        decoderLifecycleListener?.invoke(true, currentDecoderGen, "c2.android.avc.decoder")
        ClimaGraphicsLifecycleDiagnostics.logExoPlaybackMilestone(
            sessionId = sessionId,
            playerIdentity = identityHash,
            playbackState = "READY",
            isPlaying = lastPlayWhenReady == true,
            videoResId = currentVideoResHint,
            positionMs = simulatedPositionMs,
        )
        playbackStateListener?.invoke("READY", lastPlayWhenReady == true)
        if (lastPlayWhenReady == true && autoFirstFrameOnPrepare) {
            emitFirstFrameRendered()
        }
    }

    fun emitFirstFrameRendered() {
        needsFrameAfterSurfaceReattach = false
        ClimaGraphicsLifecycleDiagnostics.logExoFirstFrame(
            sessionId = sessionId,
            playerIdentity = identityHash,
            videoResId = currentVideoResHint,
            positionMs = simulatedPositionMs,
            playerState = currentPlayerState,
        )
        val token =
            com.example.ui.components.WeatherVideoFrameToken(
                sessionId = sessionId,
                playerIdentity = identityHash,
                videoResId = currentVideoResHint,
                decoderGeneration = currentDecoderGen.coerceAtLeast(1),
                surfaceEpoch = currentSurfaceEpoch,
                surfaceViewIdentity = attachedSurfaceViewId,
                decoderAlive = currentDecoderAlive,
            )
        val tokenCb = firstFrameTokenListener
        if (tokenCb != null) {
            tokenCb.invoke(token)
        } else {
            firstFrameListener?.invoke()
        }
    }

    fun emitFirstFrameRenderedWithToken(token: com.example.ui.components.WeatherVideoFrameToken) {
        firstFrameTokenListener?.invoke(token)
    }

    fun simulatePlaybackStateChange(stateName: String, isPlaying: Boolean) {
        currentPlayerState = stateName
        idleState = stateName == "IDLE"
        if (idleState) {
            currentDecoderAlive = false
        }
        playbackStateListener?.invoke(stateName, isPlaying)
    }

    fun simulateDecoderReleasedOnly(decoderName: String = "c2.android.avc.decoder") {
        currentDecoderAlive = false
        decoderLifecycleListener?.invoke(false, currentDecoderGen, decoderName)
    }

    fun simulateVideoCodecFaultBeforePlayerError() {
        currentDecoderAlive = false
        decoderLifecycleListener?.invoke(false, currentDecoderGen, "codec_error:MediaCodecVideoDecoderException")
    }

    override fun stop() {
        stopCalls++
        idleState = true
        currentDecoderAlive = false
        currentPlayerState = "IDLE"
        events += "stop"
        playbackStateListener?.invoke("IDLE", false)
    }

    override fun play() {
        playCalls++
        events += "play"
        if (needsFrameAfterSurfaceReattach && autoFirstFrameOnPlayAfterReattach) {
            emitFirstFrameRendered()
        }
    }

    override fun pause() {
        pauseCalls++
        events += "pause"
    }

    override fun clearVideoSurface() {
        clearSurfaceCount++
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = 0
        events += "clearVideoSurface"
    }

    override fun notifySurfaceReattachedForNewFrame() {
        currentSurfaceEpoch += 1
        needsFrameAfterSurfaceReattach = true
    }

    fun onBoundToFakeSurface(viewIdentity: Int) {
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = viewIdentity
    }

    override fun setFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameListener = listener
    }

    override fun setFirstFrameTokenListener(
        listener: ((com.example.ui.components.WeatherVideoFrameToken) -> Unit)?,
    ) {
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

    override fun setCodecErrorListener(
        listener: ((com.example.ui.components.WeatherVideoCodecErrorDiagnostic) -> Unit)?,
    ) {
        codecErrorListener = listener
    }

    fun simulateFatalMediaCodecReclaim(
        decoderName: String = "c2.android.avc.decoder",
        errorCodeName: String = "ERROR_CODE_DECODING_FAILED",
        errorMessage: String = "MediaCodec released by resource manager (DEAD_OBJECT)",
        resetLivePositionOnIdle: Boolean = true,
    ) {
        val posBeforeError = simulatedPositionMs
        val speedBeforeError = currentSpeedValue
        idleState = true
        currentDecoderAlive = false
        currentPlayerState = "IDLE"
        if (resetLivePositionOnIdle) {
            simulatedPositionMs = 0L
        }
        events += "codecReclaim:$errorCodeName"
        ClimaGraphicsLifecycleDiagnostics.logExoCodecError(
            sessionId = sessionId,
            playerIdentity = identityHash,
            trackType = "VIDEO",
            errorClass = "MediaCodecVideoDecoderException",
            isDeadObject = true,
            playerState = "IDLE",
            videoResId = currentVideoResHint,
            positionMs = posBeforeError,
            message = errorMessage,
        )
        ClimaGraphicsLifecycleDiagnostics.logExoDecoderReleased(
            sessionId = sessionId,
            playerIdentity = identityHash,
            trackType = "VIDEO",
            decoderName = decoderName,
            playerState = "IDLE",
            videoResId = currentVideoResHint,
            positionMs = posBeforeError,
        )
        decoderLifecycleListener?.invoke(false, currentDecoderGen, decoderName)
        playbackStateListener?.invoke("IDLE", false)
        ClimaGraphicsLifecycleDiagnostics.logExoPlayerError(
            sessionId = sessionId,
            playerIdentity = identityHash,
            errorCodeName = errorCodeName,
            errorClass = "MediaCodecVideoDecoderException",
            isDeadObject = true,
            playerState = "IDLE",
            videoResId = currentVideoResHint,
            positionMs = posBeforeError,
            message = errorMessage,
        )
        codecErrorListener?.invoke(
            com.example.ui.components.WeatherVideoCodecErrorDiagnostic(
                errorCodeName = errorCodeName,
                errorClass = "MediaCodecVideoDecoderException",
                isDeadObjectOrReclaimed = true,
                positionMsAtError = posBeforeError,
                playbackSpeedAtError = speedBeforeError,
                decoderGenerationAtError = currentDecoderGen,
                message = errorMessage,
            ),
        )
    }

    override fun attachToPlayerView(playerView: androidx.media3.ui.PlayerView) {
        currentSurfaceEpoch += 1
        attachedSurfaceViewId = System.identityHashCode(playerView)
        attachedView = playerView
    }

    override fun isAttachedToPlayerView(playerView: androidx.media3.ui.PlayerView): Boolean =
        attachedView === playerView

    override fun releaseOnce(): Boolean {
        if (released) return false
        released = true
        currentDecoderAlive = false
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
    private var currentTextureAvailable: Boolean = true
    private var currentHasSurfaceTexture: Boolean = true
    var attachCount = 0
    var detachCount = 0
    var firstDetachOrder = Int.MAX_VALUE

    override fun attachPlayer(player: WeatherVideoPlayerBridge) {
        attachCount++
        boundPlayer = player
        if (player is FakeWeatherVideoPlayerBridge) {
            player.onBoundToFakeSurface(viewIdentity)
        }
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
            textureAvailable = currentTextureAvailable,
            hasSurfaceTexture = currentHasSurfaceTexture,
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

    fun emitSurfaceAvailability(
        attached: Boolean,
        textureAvailable: Boolean,
        hasSurfaceTexture: Boolean,
    ) {
        currentTextureAvailable = textureAvailable
        currentHasSurfaceTexture = hasSurfaceTexture
        attachObserver?.invoke(attached, inspectSurfaceSnapshot())
    }
}

private class FakeMapStylePort : MapStylePort {
    override val isFullyLoaded: Boolean = true
    val layers = mutableSetOf<String>()

    override fun updateGeoJsonSource(sourceId: String, geoJson: String) = Unit
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
