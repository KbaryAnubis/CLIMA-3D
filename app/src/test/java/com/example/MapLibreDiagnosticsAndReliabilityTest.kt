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
import com.example.maps.MapLibreInitializer
import com.example.maps.MapSurfaceLoadState
import com.example.maps.MapSurfaceStatusOverlay
import com.example.maps.MapViewLifecycleController
import com.example.maps.OpenStreetMapTileConfig
import com.example.maps.RadarBaseStyle
import com.example.ui.screens.RadarScreen
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Etapa 3C verification suite covering MapLibre initialization, valid Style Spec v8 JSON,
 * OpenStreetMap tile configuration, symmetrical Compose MapView lifecycle, style/network error
 * handling, OSM vs RainViewer layer separation, missing historical frames, and repeated tab switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapLibreDiagnosticsAndReliabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `MapLibreInitializer handles JVM initialization cleanly and configures TextureView options and tile tags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(MapLibreInitializer.isRobolectricEnvironment())
        // Must not crash on JVM without native .so libraries
        val ready = MapLibreInitializer.ensureInitialized(context)
        assertFalse(ready)

        // Verify TextureView mode is enabled on MapLibreMapOptions for Compose compatibility
        val options = MapLibreInitializer.createTextureMapOptions(context)
        assertTrue("MapLibre must use TextureView inside Jetpack Compose", options.textureMode)

        // Verify tile log tag classification separates OSM vs RainViewer requests
        assertEquals(
            ClimaMapLogTags.OSM_TILES,
            MapLibreInitializer.classifyTileLogTag("tile.openstreetmap.org"),
        )
        assertEquals(
            ClimaMapLogTags.RAINVIEWER_TILES,
            MapLibreInitializer.classifyTileLogTag("tilecache.rainviewer.com"),
        )

        // Verify OkHttpClient builds with OSM timeouts and User-Agent interceptor
        val client = MapLibreInitializer.buildTileOkHttpClient()
        assertNotNull(client)
        assertTrue(client.interceptors.isNotEmpty())
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
    }

    @Test
    fun `OpenStreetMap base Style JSON v8 is valid and decoupled from RainViewer overlay`() {
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
        assertEquals(OpenStreetMapTileConfig.TILE_URL, citiesSource.getJSONArray("tiles").getString(0))
        assertEquals(256, citiesSource.getInt("tileSize"))
        assertEquals(OpenStreetMapTileConfig.ATTRIBUTION, citiesSource.getString("attribution"))

        val citiesLayers = citiesDoc.getJSONArray("layers")
        assertEquals(2, citiesLayers.length())
        assertEquals("background", citiesLayers.getJSONObject(0).getString("type"))
        assertEquals(OpenStreetMapTileConfig.OSM_LAYER_ID, citiesLayers.getJSONObject(1).getString("id"))

        // Radar base style must contain OSM base without hardcoding RainViewer source so failure in RainViewer never hides OSM
        val radarDoc = JSONObject(radarStyleJson)
        assertEquals(8, radarDoc.getInt("version"))
        val radarSources = radarDoc.getJSONObject("sources")
        assertTrue(radarSources.has(OpenStreetMapTileConfig.RADAR_OSM_SOURCE_ID))
        assertFalse(radarStyleJson.contains("rainviewer", ignoreCase = true))
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

        // Initial sync to RESUMED (like AndroidView.factory)
        controller.syncToLifecycleState(Lifecycle.State.RESUMED)
        assertEquals(listOf("onCreate", "onStart", "onResume"), events)

        // Replay of ON_CREATE/ON_START/ON_RESUME by LifecycleRegistry.addObserver must NOT duplicate calls
        controller.onLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller.onLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(listOf("onCreate", "onStart", "onResume"), events)

        // Low memory forwarded while active
        controller.dispatchLowMemory()
        assertEquals(listOf("onCreate", "onStart", "onResume", "onLowMemory"), events)

        // Background and resume again
        controller.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        controller.onLifecycleEvent(Lifecycle.Event.ON_STOP)
        controller.onLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        assertEquals(
            listOf("onCreate", "onStart", "onResume", "onLowMemory", "onPause", "onStop", "onStart", "onResume"),
            events,
        )

        // Tab switch dispose followed by stray ON_DESTROY must destroy exactly once
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
    fun `MapSurfaceStatusOverlay displays style loading failure and triggers retry callback`() {
        var retryClicks = 0

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                MapSurfaceStatusOverlay(
                    loadState = MapSurfaceLoadState.Error("Style JSON inválido ou falha de rede"),
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

        // Base map container remains mounted and visible even without RainViewer frames
        composeTestRule.onNodeWithTag("radar_map_container").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_map_secondary_notice").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_map_retry_button").performScrollTo().performClick()
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

        composeTestRule.onNodeWithTag("radar_map_container").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_map_status_offline").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_map_retry_button").performScrollTo().performClick()
        assertEquals(1, retryCount)
    }

    @Test
    fun `Repeated tab switching between Home, Radar, and Cities preserves both map screens without errors`() {
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Clima3DNativeAssetsFoundationApp()
            }
        }
        composeTestRule.waitForIdle()

        // 1. Open Radar
        composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_map_container").performScrollTo().assertIsDisplayed()

        // 2. Switch to Cities & select Map sub-tab
        composeTestRule.onNodeWithTag("nav_tab_cities").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_subtab_map").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_map_section").assertIsDisplayed()

        // 3. Switch back to Radar -> Home -> Radar -> Cities repeatedly
        composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_map_container").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_home").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_map_container").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_cities").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_map_section").assertIsDisplayed()
    }
}
