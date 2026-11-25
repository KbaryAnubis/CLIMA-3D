package com.example

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SavedLocation
import com.example.maps.CityMapLibreSection
import com.example.maps.CityMapUiState
import com.example.maps.ClimaMapLogTags
import com.example.maps.MapLoadStage
import com.example.maps.MapLoadStateMachine
import com.example.maps.MapLibreInitializer
import com.example.maps.MapSurfaceLoadState
import com.example.maps.MapSurfaceStatusOverlay
import com.example.maps.MapViewLifecycleController
import com.example.maps.OpenStreetMapTileConfig
import com.example.maps.RadarBaseStyle
import com.example.maps.TileTransportDiagnostics
import com.example.maps.TileTransportFailureKind
import com.example.ui.screens.RadarScreen
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Etapa 3C + 3D verification suite covering MapLibre initialization, valid Style Spec v8 JSON,
 * OpenStreetMap tile configuration, OkHttp User-Agent & HTTP/DNS diagnostics, separated load stages
 * (Initializing -> LoadingStyle -> LoadingMap -> Available), timeout with known cause & late
 * completion, clean retry, symmetrical lifecycle cleanup, and OSM vs RainViewer isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapLibreDiagnosticsAndReliabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun resetDiagnostics() {
        TileTransportDiagnostics.reset()
    }

    @Test
    fun `MapLibreInitializer handles JVM initialization cleanly and configures TextureView options and tile tags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(MapLibreInitializer.isRobolectricEnvironment())
        val ready = MapLibreInitializer.ensureInitialized(context)
        assertFalse(ready)

        val options = MapLibreInitializer.createTextureMapOptions(context)
        assertTrue("MapLibre must use TextureView inside Jetpack Compose", options.textureMode)

        assertEquals(
            ClimaMapLogTags.OSM_TILES,
            MapLibreInitializer.classifyTileLogTag("tile.openstreetmap.org"),
        )
        assertEquals(
            ClimaMapLogTags.RAINVIEWER_TILES,
            MapLibreInitializer.classifyTileLogTag("tilecache.rainviewer.com"),
        )

        val client = MapLibreInitializer.buildTileOkHttpClient()
        assertNotNull(client)
        assertTrue(client.interceptors.isNotEmpty())
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(25_000, client.callTimeoutMillis)
        assertEquals(20, client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun `OpenStreetMap base Style JSON v8 is valid and raster layer is linked to OSM source`() {
        val citiesStyleJson =
            OpenStreetMapTileConfig.buildBaseOsmStyleJson(
                sourceId = OpenStreetMapTileConfig.OSM_SOURCE_ID,
                layerId = OpenStreetMapTileConfig.OSM_LAYER_ID,
            )
        val radarStyleJson =
            OpenStreetMapTileConfig.buildBaseOsmStyleJson(
                sourceId = OpenStreetMapTileConfig.RADAR_OSM_SOURCE_ID,
                layerId = OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID,
            )

        val citiesDoc = JSONObject(citiesStyleJson)
        assertEquals(8, citiesDoc.getInt("version"))
        val citiesSource = citiesDoc.getJSONObject("sources").getJSONObject(OpenStreetMapTileConfig.OSM_SOURCE_ID)
        assertEquals("raster", citiesSource.getString("type"))
        assertEquals("xyz", citiesSource.getString("scheme"))
        assertEquals(OpenStreetMapTileConfig.TILE_URL, citiesSource.getJSONArray("tiles").getString(0))
        assertEquals(256, citiesSource.getInt("tileSize"))
        assertEquals(OpenStreetMapTileConfig.ATTRIBUTION, citiesSource.getString("attribution"))

        val citiesLayers = citiesDoc.getJSONArray("layers")
        assertEquals(2, citiesLayers.length())
        assertEquals("background", citiesLayers.getJSONObject(0).getString("type"))
        val rasterLayer = citiesLayers.getJSONObject(1)
        assertEquals(OpenStreetMapTileConfig.OSM_LAYER_ID, rasterLayer.getString("id"))
        assertEquals("raster", rasterLayer.getString("type"))
        assertEquals(OpenStreetMapTileConfig.OSM_SOURCE_ID, rasterLayer.getString("source"))

        val radarDoc = JSONObject(radarStyleJson)
        assertEquals(8, radarDoc.getInt("version"))
        assertTrue(radarDoc.getJSONObject("sources").has(OpenStreetMapTileConfig.RADAR_OSM_SOURCE_ID))
        assertFalse(radarStyleJson.contains("rainviewer", ignoreCase = true))
    }

    @Test
    fun `MapLoadStateMachine separates style loaded, first frame, and full map render with no false Available`() {
        var nowMs = 1_000L
        val machine = MapLoadStateMachine(screenName = "Radar", clock = { nowMs })

        assertEquals(
            MapSurfaceLoadState.Initializing,
            machine.beginAttempt(isOnline = true, hasMapReady = false),
        )

        nowMs += 200L
        assertEquals(MapSurfaceLoadState.LoadingStyle, machine.onMapReady(isOnline = true))

        // Style loaded must NOT falsely mark map Available before tiles/render
        nowMs += 150L
        val afterStyle = machine.onStyleLoaded()
        assertEquals(MapSurfaceLoadState.LoadingMap, afterStyle)
        assertTrue(machine.styleLoaded)
        assertFalse(machine.firstFrameRendered)
        assertFalse(machine.mapFullyRendered)

        // First frame alone (e.g. background fill before tiles arrive) must NOT mark Available
        nowMs += 50L
        val afterFirstFrame = machine.onFirstFrameRendered()
        assertEquals(MapSurfaceLoadState.LoadingMap, afterFirstFrame)
        assertTrue(machine.firstFrameRendered)

        // Partial render (fully = false) must still remain in LoadingMap
        val afterPartialRender = machine.onMapFinishedRendering(fully = false, tileSnapshot = TileTransportDiagnostics.snapshot.value)
        assertEquals(MapSurfaceLoadState.LoadingMap, afterPartialRender)

        // Full render (fully = true) after style loaded transitions cleanly to Available
        TileTransportDiagnostics.recordRequestStart(isRainViewer = false, sanitizedUrl = "https://tile.openstreetmap.org/6/23/36.png")
        TileTransportDiagnostics.recordHttpResponse(isRainViewer = false, statusCode = 200, durationMs = 42L, sanitizedUrl = "https://tile.openstreetmap.org/6/23/36.png")
        val afterFullRender = machine.onMapFinishedRendering(fully = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
        assertEquals(MapSurfaceLoadState.Available, afterFullRender)
    }

    @Test
    fun `Tile interceptor enforces OSM User-Agent and records HTTP 403 and DNS UnknownHostException failures`() {
        var capturedUserAgent: String? = null
        var nextStatusCode = 403
        var throwDns = false

        val testClient =
            OkHttpClient.Builder()
                .addInterceptor(MapLibreInitializer.createTileTransportInterceptor())
                .addInterceptor { chain ->
                    val req = chain.request()
                    capturedUserAgent = req.header("User-Agent")
                    if (throwDns) {
                        throw UnknownHostException("Unable to resolve host \"tile.openstreetmap.org\"")
                    }
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(nextStatusCode)
                        .message("Mock")
                        .body("".toResponseBody("image/png".toMediaType()))
                        .build()
                }
                .build()

        // 1. HTTP 403 tile rejection
        val req1 = Request.Builder().url("https://tile.openstreetmap.org/6/23/36.png").build()
        testClient.newCall(req1).execute().close()
        testClient.newCall(req1).execute().close()

        assertEquals(OpenStreetMapTileConfig.userAgent, capturedUserAgent)
        val httpSnap = TileTransportDiagnostics.snapshot.value
        assertEquals(2, httpSnap.osmRequestCount)
        assertEquals(0, httpSnap.osmSuccessCount)
        assertEquals(2, httpSnap.osmErrorCount)
        assertEquals(403, httpSnap.lastOsmStatusCode)
        assertEquals(TileTransportFailureKind.HTTP_BLOCKED_OR_RATE_LIMIT, httpSnap.lastOsmFailureKind)

        val machine = MapLoadStateMachine("Cidades")
        machine.beginAttempt(isOnline = true, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
        // Fresh attempt baseline sees 0 errors until 2 new errors occur
        assertEquals(MapSurfaceLoadState.LoadingStyle, machine.state)

        testClient.newCall(req1).execute().close()
        testClient.newCall(req1).execute().close()
        val afterHttpErrors = machine.onTileTransportUpdated(TileTransportDiagnostics.snapshot.value, isOnline = true)
        assertTrue(afterHttpErrors is MapSurfaceLoadState.RenderError)
        assertTrue((afterHttpErrors as MapSurfaceLoadState.RenderError).reason.contains("403"))

        // 2. DNS failure
        TileTransportDiagnostics.reset()
        throwDns = true
        val dnsMachine = MapLoadStateMachine("Radar")
        dnsMachine.beginAttempt(isOnline = true, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
        dnsMachine.onStyleLoaded()
        repeat(2) {
            runCatching { testClient.newCall(req1).execute().close() }
        }
        val dnsSnap = TileTransportDiagnostics.snapshot.value
        assertEquals(TileTransportFailureKind.DNS_OR_NETWORK, dnsSnap.lastOsmFailureKind)
        val afterDns = dnsMachine.onTileTransportUpdated(dnsSnap, isOnline = true)
        assertTrue(afterDns is MapSurfaceLoadState.ConnectionError)
        assertTrue((afterDns as MapSurfaceLoadState.ConnectionError).reason.contains("DNS"))
    }

    @Test
    fun `Tile interceptor classifies HTTP cancellations as CANCELED without incrementing error counts or triggering ConnectionError`() {
        val canceledByMessageClient =
            OkHttpClient.Builder()
                .addInterceptor(MapLibreInitializer.createTileTransportInterceptor())
                .addInterceptor { _ ->
                    throw IOException("Canceled")
                }
                .build()

        val callCanceledClient =
            OkHttpClient.Builder()
                .addInterceptor(MapLibreInitializer.createTileTransportInterceptor())
                .addInterceptor { chain ->
                    chain.call().cancel()
                    throw IOException("Socket closed")
                }
                .build()

        val rainViewerCanceledClient =
            OkHttpClient.Builder()
                .addInterceptor(MapLibreInitializer.createTileTransportInterceptor())
                .addInterceptor { _ ->
                    throw IOException("stream was reset: CANCEL")
                }
                .build()

        val machine = MapLoadStateMachine("Radar")
        machine.beginAttempt(isOnline = true, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
        machine.onStyleLoaded()

        val osmReq = Request.Builder().url("https://tile.openstreetmap.org/7/47/72.png").build()
        val rvReq = Request.Builder().url("https://tilecache.rainviewer.com/v2/radar/1763995200/512/7/47/72/2/1_1.png").build()

        repeat(4) {
            runCatching { canceledByMessageClient.newCall(osmReq).execute().close() }
        }
        repeat(2) {
            runCatching { callCanceledClient.newCall(osmReq).execute().close() }
        }
        repeat(3) {
            runCatching { rainViewerCanceledClient.newCall(rvReq).execute().close() }
        }

        val snap = TileTransportDiagnostics.snapshot.value
        assertEquals(6, snap.osmCanceledCount)
        assertEquals(0, snap.osmErrorCount)
        assertEquals(null, snap.lastOsmError)
        assertEquals(TileTransportFailureKind.NONE, snap.lastOsmFailureKind)
        assertEquals(3, snap.rainViewerCanceledCount)
        assertEquals(0, snap.rainViewerErrorCount)
        assertEquals(null, snap.lastRainViewerError)

        val stateAfterCancels = machine.onTileTransportUpdated(snap, isOnline = true)
        assertEquals(MapSurfaceLoadState.LoadingMap, stateAfterCancels)

        // Direct classification distinguishes CANCELED, TIMEOUT, DNS, TLS
        assertEquals(
            TileTransportFailureKind.CANCELED,
            TileTransportDiagnostics.classifyThrowable(IOException("Canceled")).first,
        )
        assertEquals(
            TileTransportFailureKind.CANCELED,
            TileTransportDiagnostics.classifyThrowable(CancellationException("viewport changed")).first,
        )
        assertEquals(
            TileTransportFailureKind.CANCELED,
            TileTransportDiagnostics.classifyThrowable(IOException("Socket closed"), isCallCanceled = true).first,
        )
        assertEquals(
            TileTransportFailureKind.TIMEOUT,
            TileTransportDiagnostics.classifyThrowable(SocketTimeoutException("Read timed out")).first,
        )
        assertEquals(
            TileTransportFailureKind.TIMEOUT,
            TileTransportDiagnostics.classifyThrowable(InterruptedIOException("timeout")).first,
        )
        assertEquals(
            TileTransportFailureKind.DNS_OR_NETWORK,
            TileTransportDiagnostics.classifyThrowable(UnknownHostException("tile.openstreetmap.org")).first,
        )
        assertEquals(
            TileTransportFailureKind.TLS,
            TileTransportDiagnostics.classifyThrowable(SSLException("Handshake failed")).first,
        )
    }

    @Test
    fun `Timeout sets TimedOut with knownCause without faking Available and supports late completion and retry`() {
        val machine = MapLoadStateMachine("Radar")
        machine.beginAttempt(isOnline = true, hasMapReady = false)
        assertEquals(MapSurfaceLoadState.Initializing, machine.state)

        // Timeout during INITIALIZING surfaces exact surface cause
        val initTimeout = machine.onTimeout(TileTransportSnapshotEmpty(), "MapView=0x0, attached=false, textureAvailable=false")
        assertTrue(initTimeout is MapSurfaceLoadState.TimedOut)
        initTimeout as MapSurfaceLoadState.TimedOut
        assertEquals(MapLoadStage.INITIALIZING, initTimeout.stalledStage)
        assertTrue(initTimeout.knownCause.orEmpty().contains("MapView=0x0"))

        // Late completion after timeout still advances cleanly to Available once style + full render arrive
        assertEquals(MapSurfaceLoadState.LoadingStyle, machine.onMapReady(isOnline = true))
        assertEquals(MapSurfaceLoadState.LoadingMap, machine.onStyleLoaded())
        assertEquals(
            MapSurfaceLoadState.Available,
            machine.onMapFinishedRendering(fully = true, tileSnapshot = TileTransportDiagnostics.snapshot.value),
        )

        // Retry starts a fresh attempt cleanly
        val prevAttempt = machine.attemptId
        val retried = machine.beginAttempt(isOnline = true, hasMapReady = true)
        assertEquals(prevAttempt + 1, machine.attemptId)
        assertEquals(MapSurfaceLoadState.LoadingStyle, retried)
        assertFalse(machine.styleLoaded)
        assertFalse(machine.firstFrameRendered)
        assertFalse(machine.mapFullyRendered)
    }

    @Test
    fun `Radar and Cities state machines stay completely independent`() {
        val radarMachine = MapLoadStateMachine("Radar")
        val citiesMachine = MapLoadStateMachine("Cidades")

        radarMachine.beginAttempt(isOnline = true, hasMapReady = true)
        citiesMachine.beginAttempt(isOnline = true, hasMapReady = true)

        radarMachine.onStyleLoaded()
        radarMachine.onMapFinishedRendering(fully = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)

        citiesMachine.onStyleFailed("Erro simulado apenas em Cidades", isOnline = true)

        assertEquals(MapSurfaceLoadState.Available, radarMachine.state)
        assertTrue(citiesMachine.state is MapSurfaceLoadState.StyleError)
    }

    @Test
    fun `MapViewLifecycleController prevents duplicate start-resume and double destroy while forwarding lowMemory`() {
        val events = mutableListOf<String>()
        val controller =
            MapViewLifecycleController(
                onCreate = { events.add("onCreate") },
                onStart = { events.add("onStart") },
                onResume = { events.add("onResume") },
                onPause = { events.add("onPause") },
                onStop = { events.add("onStop") },
                onDestroy = { events.add("onDestroy") },
                onLowMemory = { events.add("onLowMemory") },
            )

        controller.syncToLifecycleState(Lifecycle.State.RESUMED)
        assertEquals(listOf("onCreate", "onStart", "onResume"), events)

        controller.onLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller.onLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(listOf("onCreate", "onStart", "onResume"), events)

        controller.dispatchLowMemory()
        assertEquals(listOf("onCreate", "onStart", "onResume", "onLowMemory"), events)

        controller.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        controller.onLifecycleEvent(Lifecycle.Event.ON_STOP)
        controller.onLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(
            listOf("onCreate", "onStart", "onResume", "onLowMemory", "onPause", "onStop", "onStart", "onResume"),
            events,
        )

        controller.destroy()
        controller.onLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        controller.dispatchLowMemory()
        assertEquals(
            listOf(
                "onCreate",
                "onStart",
                "onResume",
                "onLowMemory",
                "onPause",
                "onStop",
                "onStart",
                "onResume",
                "onPause",
                "onStop",
                "onDestroy",
            ),
            events,
        )
        assertEquals(MapViewLifecycleController.Stage.DESTROYED, controller.currentStage)
    }

    @Test
    fun `MapSurfaceStatusOverlay displays style failure and timeout with retry callback`() {
        var retryClicks = 0

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                MapSurfaceStatusOverlay(
                    loadState = MapSurfaceLoadState.StyleError("Style JSON inválido ou falha de rede"),
                    onRetry = { retryClicks++ },
                    testTagPrefix = "test_map",
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("test_map_status_error").assertIsDisplayed()
        composeTestRule.onNodeWithTag("test_map_retry_button").assertIsDisplayed().performClick()
        assertEquals(1, retryClicks)
    }

    @Test
    fun `MapSurfaceStatusOverlay displays timeout state with known cause and working retry`() {
        var retryClicks = 0

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                MapSurfaceStatusOverlay(
                    loadState =
                        MapSurfaceLoadState.TimedOut(
                            stalledStage = MapLoadStage.LOADING_MAP,
                            knownCause = "Tiles OSM sem resposta (HTTP 429)",
                        ),
                    onRetry = { retryClicks++ },
                    testTagPrefix = "timeout_map",
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("timeout_map_status_timeout").assertIsDisplayed()
        composeTestRule.onNodeWithTag("timeout_map_retry_button").assertIsDisplayed().performClick()
        assertEquals(1, retryClicks)
    }

    @Test
    fun `CityMapLibreSection shows clear offline error overlay and retry button when offline`() {
        var retryCount = 0

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                CityMapLibreSection(
                    initialState = CityMapUiState(),
                    isOnline = false,
                    onRetryReload = { retryCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("cities_map_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_status_offline").assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_retry_button").performClick()
        assertEquals(1, retryCount)
    }

    @Test
    fun `RadarScreen preserves base OSM container and shows Radar unavailable notice when RainViewer frames are missing`() {
        var refreshCount = 0
        val emptyRadarState =
            ClimaUiState(
                activeTab = ClimaTab.RADAR,
                isOnline = true,
                activeLocation =
                    SavedLocation(
                        id = 1L,
                        providerId = 3448439L,
                        name = "São Paulo",
                        region = "São Paulo",
                        country = "Brasil",
                        countryCode = "BR",
                        latitude = -23.5505,
                        longitude = -46.6333,
                        timezoneId = "America/Sao_Paulo",
                        isPrimary = true,
                    ),
                radarTimeline = null,
                radarErrorMessage = "Serviço RainViewer temporariamente indisponível",
                radarLayer = RadarBaseStyle.PRECIPITATION_MAP,
            )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                RadarScreen(
                    uiState = emptyRadarState,
                    contentPadding = PaddingValues(12.dp),
                    onToggleLayerMenu = {},
                    onSelectLayer = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onSelectFrame = {},
                    onTogglePlay = {},
                    onRefreshRadar = { refreshCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("radar_precipitation_evolution_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_status_provider_error").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_retry_button").performScrollTo().performClick()
        assertEquals(1, refreshCount)

        composeTestRule.onNodeWithTag("radar_frame_counter")
            .performScrollTo()
            .assertTextContains("Nenhum quadro disponível", substring = true)
    }

    @Test
    fun `RadarScreen displays clear offline state when network is unavailable`() {
        var retryCount = 0
        val offlineRadarState =
            ClimaUiState(
                activeTab = ClimaTab.RADAR,
                isOnline = false,
                radarTimeline = null,
            )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                RadarScreen(
                    uiState = offlineRadarState,
                    contentPadding = PaddingValues(12.dp),
                    onToggleLayerMenu = {},
                    onSelectLayer = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onSelectFrame = {},
                    onTogglePlay = {},
                    onRefreshRadar = { retryCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("radar_precipitation_evolution_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_status_offline").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_retry_button").performScrollTo().performClick()
        assertEquals(1, retryCount)
    }

    @Test
    fun `Repeated tab switching between Home, Radar, and Cities mounts zero permanent maps and opens on-demand MapLocationPicker cleanly`() {
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Clima3DNativeAssetsFoundationApp()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_precipitation_evolution_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("cities_map_section").assertDoesNotExist()

        composeTestRule.onNodeWithTag("nav_tab_cities").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_open_map_picker_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cities_map_section").assertDoesNotExist()

        // Open on-demand map location picker via "Mapa" subtab or button
        composeTestRule.onNodeWithTag("cities_subtab_map").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_location_picker_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cities_map_section").performScrollTo().assertIsDisplayed()

        // Close on-demand map location picker
        composeTestRule.onNodeWithTag("map_picker_close_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("map_location_picker_screen").assertDoesNotExist()
        composeTestRule.onNodeWithTag("cities_map_section").assertDoesNotExist()

        composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_precipitation_evolution_card").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_home").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_map_section").assertDoesNotExist()
    }

    private fun TileTransportSnapshotEmpty() = TileTransportDiagnostics.snapshot.value
}
