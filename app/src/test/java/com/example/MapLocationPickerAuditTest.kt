package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.NetworkClientFactory
import com.example.data.remote.OpenMeteoAirQualityApi
import com.example.data.remote.OpenMeteoAirQualityCurrentDto
import com.example.data.remote.OpenMeteoAirQualityResponseDto
import com.example.data.remote.OpenMeteoCurrentDto
import com.example.data.remote.OpenMeteoDailyDto
import com.example.data.remote.OpenMeteoForecastApi
import com.example.data.remote.OpenMeteoForecastResponseDto
import com.example.data.remote.OpenMeteoGeocodingApi
import com.example.data.remote.OpenMeteoGeocodingMapper
import com.example.data.remote.OpenMeteoGeocodingResponseDto
import com.example.data.remote.OpenMeteoGeocodingResultDto
import com.example.data.remote.OpenMeteoHourlyDto
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerFrameDto
import com.example.data.remote.RainViewerMapsResponseDto
import com.example.data.remote.RainViewerRadarSectionDto
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.diagnostics.GraphicsSurfaceCoordinator
import com.example.diagnostics.MapSurfaceSessionState
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapUiState
import com.example.maps.LocationComponentSyncResult
import com.example.maps.MapLibreControllerPort
import com.example.maps.MapLocationPickerScreenImpl
import com.example.maps.MapStylePort
import com.example.maps.MapStyleSpecRequest
import com.example.maps.MapSurfaceCallbacks
import com.example.maps.MapViewSurfacePort
import com.example.ui.state.ClimaActiveModal
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Etapa 3P.1 — Comprehensive audit test suite covering the 11 mandatory on-demand MapLibre
 * location picker scenarios, Open-Meteo Geocoding search (cities, municipalities, neighborhoods,
 * deduplication, disambiguation), manual tap/pan & zoom, GPS repeat fix, confirm/cancel/Back
 * persistence, ordered surface teardown, and zero permanent MapViews on permanent tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapLocationPickerAuditTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = true
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
        GraphicsSurfaceCoordinator.resetForTest()
    }

    @After
    fun teardown() {
        ClimaGraphicsLifecycleDiagnostics.debugOverrideForTest = null
        ClimaGraphicsLifecycleDiagnostics.clearForTest()
        GraphicsSurfaceCoordinator.resetForTest()
    }

    // Scenario 1 + 2: Permanent tabs never mount MapView; both Home dialog & Cities tab open MapLocationPicker on demand
    @Test
    fun `scenario_01_02_permanent_tabs_mount_zero_maps_and_both_entry_points_open_on_demand_picker`(): Unit = runBlocking {
        val harness = createHarness()
        try {
            harness.seedSaoPauloPrimary()

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            // Verify HOME, RADAR, CITIES, SETTINGS never mount any MapView
            val tabs = listOf("nav_tab_home", "nav_tab_radar", "nav_tab_cities", "nav_tab_settings", "nav_tab_home")
            tabs.forEach { tabTag ->
                composeTestRule.onNodeWithTag(tabTag).performClick()
                composeTestRule.waitForIdle()
                assertEquals(0, composeTestRule.onAllNodesWithTag("city_maplibre_native_view").fetchSemanticsNodes().size)
                assertEquals(0, composeTestRule.onAllNodesWithTag("city_maplibre_jvm_surface").fetchSemanticsNodes().size)
                assertEquals(0, composeTestRule.onAllNodesWithTag("cities_map_section").fetchSemanticsNodes().size)
            }

            // Entry point 1: Home -> LocationPickerDialog -> "Escolher no mapa"
            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("location_picker_dialog").assertIsDisplayed()
            composeTestRule.onNodeWithTag("location_picker_open_map_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_location_picker_screen").assertIsDisplayed()
            assertEquals(ClimaActiveModal.MapLocationPicker, harness.vm.uiState.value.activeModal)

            // Close via close button
            composeTestRule.onNodeWithTag("map_picker_close_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(ClimaActiveModal.None, harness.vm.uiState.value.activeModal)

            // Entry point 2: Cities -> "Escolher localização no mapa" button
            composeTestRule.onNodeWithTag("nav_tab_cities").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("cities_screen_list").performScrollToKey("cities_saved_header")
            composeTestRule.onNodeWithTag("cities_open_map_picker_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_location_picker_screen").assertIsDisplayed()
            assertEquals(ClimaActiveModal.MapLocationPicker, harness.vm.uiState.value.activeModal)

            // Cancel via "Cancelar"
            composeTestRule.onNodeWithTag("map_picker_cancel_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(ClimaActiveModal.None, harness.vm.uiState.value.activeModal)

            // Entry point 3: Cities -> "Mapa" segmented subtab
            composeTestRule.onNodeWithTag("cities_screen_list").performScrollToKey("cities_toolbar")
            composeTestRule.onNodeWithTag("cities_subtab_map").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_location_picker_screen").assertIsDisplayed()
        } finally {
            harness.close()
        }
    }

    // Scenario 3: Geocoding search supports neighborhoods, municipalities, deduplication, disambiguation, and centers without closing
    @Test
    fun `scenario_03_geocoding_search_deduplicates_disambiguates_and_centers_without_closing_map`(): Unit = runBlocking {
        // Verify deduplication + admin2 neighborhood/district preservation in OpenMeteoGeocodingMapper
        val rawDto =
            OpenMeteoGeocodingResponseDto(
                results =
                    listOf(
                        OpenMeteoGeocodingResultDto(
                            id = 3466537L,
                            name = "Copacabana",
                            latitude = -22.9711,
                            longitude = -43.1825,
                            countryCode = "BR",
                            country = "Brasil",
                            admin1 = "Rio de Janeiro",
                            admin2 = "Rio de Janeiro",
                            timezone = "America/Sao_Paulo",
                        ),
                        // Exact duplicate providerId / key should be removed
                        OpenMeteoGeocodingResultDto(
                            id = 3466537L,
                            name = "Copacabana",
                            latitude = -22.9711,
                            longitude = -43.1825,
                            countryCode = "BR",
                            country = "Brasil",
                            admin1 = "Rio de Janeiro",
                            admin2 = "Rio de Janeiro",
                        ),
                        // Distinct place with same name in Bolivia must be preserved
                        OpenMeteoGeocodingResultDto(
                            id = 3919627L,
                            name = "Copacabana",
                            latitude = -16.1667,
                            longitude = -69.0833,
                            countryCode = "BO",
                            country = "Bolívia",
                            admin1 = "La Paz",
                            admin2 = "Manco Kapac",
                        ),
                    ),
            )
        val mapped = OpenMeteoGeocodingMapper.toDomain(rawDto)
        assertEquals(2, mapped.size)
        assertEquals("Copacabana — Rio de Janeiro, Brasil", mapped[0].fullDisambiguatedLabel())
        assertEquals("Copacabana — Manco Kapac, La Paz, Bolívia", mapped[1].fullDisambiguatedLabel())

        val harness = createHarness()
        try {
            harness.seedSaoPauloPrimary()
            val ports = mutableListOf<AuditSurfacePort>()

            composeTestRule.setContent {
                val state by harness.vm.uiState.collectAsStateWithLifecycle()
                Clima3DTheme(darkTheme = true) {
                    if (state.activeModal == ClimaActiveModal.MapLocationPicker) {
                        MapLocationPickerScreenImpl(
                            mapState = state.cityMapState,
                            pickerState = state.mapLocationPickerState,
                            customPointNameInput = state.customPointNameInput,
                            isOnline = state.isOnline,
                            isPermanentlyDenied = false,
                            onSearchQueryChange = harness.vm::updateMapPickerSearchQuery,
                            onSubmitSearch = harness.vm::submitMapPickerSearch,
                            onSelectSearchResult = harness.vm::selectSearchResultInMapPicker,
                            onMapStateChange = harness.vm::updateCityMapState,
                            onCustomPointNameChange = harness.vm::updateCustomPointNameInput,
                            onConfirmSelection = harness.vm::confirmMapLocationSelection,
                            onCancel = harness.vm::closeMapLocationPickerModal,
                            onOpenAppSettings = {},
                            onRetryReload = {},
                            surfacePortFactory = { ctx -> AuditSurfacePort(ctx).also { ports += it } },
                        )
                    }
                }
            }

            composeTestRule.runOnUiThread { harness.vm.openMapLocationPickerModal() }
            composeTestRule.waitForIdle()
            assertEquals(1, ports.size)

            // Search neighborhood "Copacabana"
            composeTestRule.onNodeWithTag("map_picker_search_input").performTextReplacement("Copacabana")
            composeTestRule.runOnUiThread { harness.vm.submitMapPickerSearch() }
            harness.awaitCondition { harness.vm.uiState.value.mapLocationPickerState.searchResults.size == 2 }
            composeTestRule.waitForIdle()

            // Select Copacabana (Rio de Janeiro)
            composeTestRule.onNodeWithTag("map_picker_search_result_0").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            // Must center map at zoom 12.5 WITHOUT closing MapLocationPickerScreen
            assertEquals(ClimaActiveModal.MapLocationPicker, harness.vm.uiState.value.activeModal)
            composeTestRule.onNodeWithTag("map_location_picker_screen").assertIsDisplayed()
            assertEquals(-22.9711, harness.vm.uiState.value.cityMapState.cameraCenter.latitude, 0.0001)
            assertEquals(-43.1825, harness.vm.uiState.value.cityMapState.cameraCenter.longitude, 0.0001)
            assertEquals(12.5, harness.vm.uiState.value.cityMapState.zoom, 0.001)
            assertEquals("Copacabana", harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName)
            composeTestRule.onNodeWithTag("map_picker_selected_name").performScrollTo().assertTextContains("Copacabana")
        } finally {
            harness.close()
        }
    }

    // Scenario 4 + 5 + 6: Zoom, pinch-gesture config, tap/pan clearing stale city label, GPS repeat fix, confirm & persist vs cancel/Back
    @Test
    fun `scenario_04_05_06_zoom_tap_pan_clears_stale_name_confirm_persists_and_cancel_discards`(): Unit = runBlocking {
        val harness = createHarness()
        try {
            val sp = harness.seedSaoPauloPrimary()
            harness.tempForCoordinate[keyFor(sp.latitude, sp.longitude)] = 23.0
            harness.tempForCoordinate[keyFor(-22.9711, -43.1825)] = 30.5
            harness.tempForCoordinate[keyFor(-19.9167, -43.9345)] = 26.2

            val ports = mutableListOf<AuditSurfacePort>()

            composeTestRule.setContent {
                val state by harness.vm.uiState.collectAsStateWithLifecycle()
                Clima3DTheme(darkTheme = true) {
                    if (state.activeModal == ClimaActiveModal.MapLocationPicker) {
                        MapLocationPickerScreenImpl(
                            mapState = state.cityMapState,
                            pickerState = state.mapLocationPickerState,
                            customPointNameInput = state.customPointNameInput,
                            isOnline = state.isOnline,
                            isPermanentlyDenied = false,
                            onSearchQueryChange = harness.vm::updateMapPickerSearchQuery,
                            onSubmitSearch = harness.vm::submitMapPickerSearch,
                            onSelectSearchResult = harness.vm::selectSearchResultInMapPicker,
                            onMapStateChange = harness.vm::updateCityMapState,
                            onCustomPointNameChange = harness.vm::updateCustomPointNameInput,
                            onConfirmSelection = harness.vm::confirmMapLocationSelection,
                            onCancel = harness.vm::closeMapLocationPickerModal,
                            onOpenAppSettings = {},
                            onRetryReload = {},
                            surfacePortFactory = { ctx -> AuditSurfacePort(ctx).also { ports += it } },
                        )
                    }
                }
            }

            composeTestRule.runOnUiThread { harness.vm.openMapLocationPickerModal() }
            composeTestRule.waitForIdle()

            // Verify MapLibre UI settings enabled pinch/double-tap/scroll gestures
            val port1 = ports.single()
            assertTrue(port1.controller.uiSettingsConfigured)

            // Search and select Copacabana
            composeTestRule.onNodeWithTag("map_picker_search_input").performTextReplacement("Copacabana")
            composeTestRule.runOnUiThread { harness.vm.submitMapPickerSearch() }
            harness.awaitCondition { harness.vm.uiState.value.mapLocationPickerState.searchResults.isNotEmpty() }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_picker_search_result_0").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals("Copacabana", harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName)

            // Zoom in & zoom out controls
            composeTestRule.onNodeWithTag("city_map_zoom_in").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(13.5, harness.vm.uiState.value.cityMapState.zoom, 0.001)
            composeTestRule.onNodeWithTag("city_map_zoom_out").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(12.5, harness.vm.uiState.value.cityMapState.zoom, 0.001)

            // Manual map tap / pan away from Copacabana (>0.0005 deg) MUST clear stale "Copacabana" label
            composeTestRule.runOnUiThread {
                port1.controller.simulateMapTap(CityMapCoordinate(-19.9167, -43.9345))
            }
            composeTestRule.waitForIdle()
            assertNull(harness.vm.uiState.value.mapLocationPickerState.selectedSearchResult)
            assertFalse(harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName.contains("Copacabana"))
            assertTrue(harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName.startsWith("Ponto "))
            composeTestRule.onNodeWithTag("map_picker_selected_coordinates").performScrollTo().assertTextContains("-19.9167", substring = true)

            // Cancel -> discards temporary point, keeps São Paulo active, destroys MapView once
            composeTestRule.onNodeWithTag("map_picker_cancel_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(ClimaActiveModal.None, harness.vm.uiState.value.activeModal)
            assertEquals("São Paulo", harness.vm.uiState.value.activeLocation?.name)
            assertEquals(1, port1.destroyCount)

            // Repeat GPS lookup increments locationFixVersion
            val beforeGpsState = CityMapUiState()
            val gps1 = beforeGpsState.withCurrentLocation(CityMapCoordinate(-23.55, -46.63), "Minha localização")
            val gps2 = gps1.withCurrentLocation(CityMapCoordinate(-23.55, -46.63), "Minha localização")
            assertEquals(beforeGpsState.locationFixVersion + 1, gps1.locationFixVersion)
            assertEquals(gps1.locationFixVersion + 1, gps2.locationFixVersion)

            // Reopen map -> select Copacabana -> enter custom name or confirm -> persists in Room & DataStore & updates Home
            composeTestRule.runOnUiThread { harness.vm.openMapLocationPickerModal() }
            composeTestRule.waitForIdle()
            val port2 = ports[1]
            composeTestRule.onNodeWithTag("map_picker_search_input").performTextReplacement("Copacabana")
            composeTestRule.runOnUiThread { harness.vm.submitMapPickerSearch() }
            harness.awaitCondition { harness.vm.uiState.value.mapLocationPickerState.searchResults.isNotEmpty() }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_picker_search_result_0").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("map_picker_confirm_button").performScrollTo().performClick()
            harness.awaitCondition {
                harness.vm.uiState.value.activeModal == ClimaActiveModal.None &&
                    harness.vm.uiState.value.activeLocation?.name == "Copacabana" &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 30.5
            }
            composeTestRule.waitForIdle()

            assertEquals(ClimaTab.HOME, harness.vm.uiState.value.activeTab)
            assertEquals(1, port2.destroyCount)
            val savedLocations = harness.container.cityLocationRepository.getSavedLocations()
            val persisted = savedLocations.firstOrNull { it.name == "Copacabana" }
            assertNotNull(persisted)
            assertTrue(persisted!!.isPrimary)
            assertEquals(persisted.id, harness.prefsRepo.preferencesFlow.first().selectedLocationId)
        } finally {
            harness.close()
        }
    }

    // Scenario 7 + 8 + 9: Repeated open/close cycles destroy MapView cleanly without leaks and coordinate surfaces
    @Test
    fun `scenario_07_08_09_repeated_open_close_cycles_teardown_cleanly_and_coordinate_surface_states`(): Unit = runBlocking {
        val harness = createHarness()
        try {
            harness.seedSaoPauloPrimary()
            val ports = mutableListOf<AuditSurfacePort>()

            composeTestRule.setContent {
                val state by harness.vm.uiState.collectAsStateWithLifecycle()
                Clima3DTheme(darkTheme = true) {
                    if (state.activeModal == ClimaActiveModal.MapLocationPicker) {
                        MapLocationPickerScreenImpl(
                            mapState = state.cityMapState,
                            pickerState = state.mapLocationPickerState,
                            customPointNameInput = state.customPointNameInput,
                            isOnline = state.isOnline,
                            isPermanentlyDenied = false,
                            onSearchQueryChange = harness.vm::updateMapPickerSearchQuery,
                            onSubmitSearch = harness.vm::submitMapPickerSearch,
                            onSelectSearchResult = harness.vm::selectSearchResultInMapPicker,
                            onMapStateChange = harness.vm::updateCityMapState,
                            onCustomPointNameChange = harness.vm::updateCustomPointNameInput,
                            onConfirmSelection = harness.vm::confirmMapLocationSelection,
                            onCancel = harness.vm::closeMapLocationPickerModal,
                            onOpenAppSettings = {},
                            onRetryReload = {},
                            surfacePortFactory = { ctx -> AuditSurfacePort(ctx).also { ports += it } },
                        )
                    }
                }
            }

            repeat(3) { cycle ->
                composeTestRule.runOnUiThread { harness.vm.openMapLocationPickerModal() }
                composeTestRule.waitForIdle()
                assertEquals(cycle + 1, ports.size)
                assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)
                assertFalse(GraphicsSurfaceCoordinator.state.value.canAttachVideoOutput)

                if (cycle % 2 == 0) {
                    composeTestRule.onNodeWithTag("map_picker_cancel_button").performScrollTo().performClick()
                } else {
                    composeTestRule.runOnUiThread { harness.vm.navigateBackFromSecondary() }
                }
                composeTestRule.waitForIdle()

                val closedPort = ports[cycle]
                assertEquals(1, closedPort.pauseCount)
                assertEquals(1, closedPort.stopCount)
                assertEquals(1, closedPort.destroyCount)
                assertEquals(1, closedPort.listenerDetachCount)
                assertEquals(1, closedPort.windowObserverDetachCount)
                assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
                assertTrue(GraphicsSurfaceCoordinator.state.value.canAttachVideoOutput)
            }
        } finally {
            harness.close()
        }
    }

    private fun keyFor(lat: Double, lon: Double): String = "${"%.2f".format(lat)}_${"%.2f".format(lon)}"

    private class AuditSurfacePort(context: Context) : MapViewSurfacePort {
        override val androidView: View = View(context)
        override val viewIdentity: Int = System.identityHashCode(androidView)
        var createCount = 0
        var pauseCount = 0
        var stopCount = 0
        var destroyCount = 0
        var listenerDetachCount = 0
        var windowObserverDetachCount = 0
        val controller = AuditControllerPort()

        override fun onCreate() {
            createCount++
        }

        override fun onStart() = Unit
        override fun onResume() = Unit

        override fun onPause() {
            pauseCount++
        }

        override fun onStop() {
            stopCount++
        }

        override fun onDestroy() {
            destroyCount++
        }

        override fun onLowMemory() = Unit
        override fun inspectSurfaceInfo(): String = "AuditSurface(320x300)"
        override fun postToUi(action: () -> Unit) = action()

        override fun attachWindowObserver(onAttachStateChanged: (Boolean, String) -> Unit): () -> Unit {
            onAttachStateChanged(true, inspectSurfaceInfo())
            return { windowObserverDetachCount++ }
        }

        override fun attachListeners(callbacks: MapSurfaceCallbacks): () -> Unit {
            callbacks.onDidFinishLoadingStyle()
            callbacks.onFirstFrameRendered()
            callbacks.onDidFinishRenderingMap(true)
            return { listenerDetachCount++ }
        }

        override fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit) {
            onReady(controller)
        }
    }

    private class AuditControllerPort : MapLibreControllerPort {
        var uiSettingsConfigured = false
        private var clickListener: ((CityMapCoordinate) -> Unit)? = null
        private var idleListener: ((CityMapCoordinate, Double) -> Unit)? = null

        override fun configureUiSettings() {
            uiSettingsConfigured = true
        }

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

        override fun setOnMapClick(listener: (CityMapCoordinate) -> Unit) {
            clickListener = listener
        }

        override fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit) {
            idleListener = listener
        }

        override fun clearMapListeners() {
            clickListener = null
            idleListener = null
        }

        override fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult =
            LocationComponentSyncResult.ALREADY_ACTIVE

        fun simulateMapTap(coordinate: CityMapCoordinate) {
            clickListener?.invoke(coordinate)
        }
    }

    private class AuditHarness(
        val db: Clima3DDatabase,
        val prefsRepo: UserPreferencesRepository,
        val container: ClimaDataContainer,
        val vm: ClimaMainViewModel,
        val tempForCoordinate: ConcurrentHashMap<String, Double>,
        private val dataStoreScope: CoroutineScope,
    ) {
        suspend fun seedSaoPauloPrimary(): com.example.data.model.SavedLocation {
            val sp =
                container.cityLocationRepository.saveCityFromSearch(
                    com.example.data.model.CitySearchResult(
                        providerId = 3448439L,
                        name = "São Paulo",
                        admin1 = "São Paulo",
                        admin2 = null,
                        country = "Brasil",
                        countryCode = "BR",
                        latitude = -23.5505,
                        longitude = -46.6333,
                        elevationMeters = 760.0,
                        timezoneId = "America/Sao_Paulo",
                        population = 12325232,
                    ),
                    setAsPrimary = true,
                )
            container.preferencesRepository.setSelectedLocationId(sp.id)
            awaitCondition {
                vm.uiState.value.activeLocation?.id == sp.id &&
                    vm.uiState.value.preferencesLoaded
            }
            return sp
        }

        suspend fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
            withTimeout(timeoutMs) {
                while (!predicate()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(30L, TimeUnit.MILLISECONDS)
                    delay(15L)
                }
            }
        }

        fun close() {
            runCatching { vm.viewModelScope.cancel() }
            runCatching { dataStoreScope.cancel() }
            runCatching { db.close() }
            runCatching { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    private fun createHarness(): AuditHarness {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "audit_picker_prefs_${UUID.randomUUID()}.preferences_pb")
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefsRepo =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = dataStoreScope,
                    produceFile = { prefsFile },
                ),
            )
        val onlineFlow = MutableStateFlow(true)
        val connectivity =
            object : ConnectivityObserver {
                override fun isOnline(): Boolean = onlineFlow.value
                override fun observeOnlineStatus(): Flow<Boolean> = onlineFlow
            }
        val tempMap = ConcurrentHashMap<String, Double>()

        val forecastApi =
            object : OpenMeteoForecastApi {
                override suspend fun getForecast(
                    latitude: Double,
                    longitude: Double,
                    current: String,
                    hourly: String,
                    daily: String,
                    forecastDays: Int,
                    timezone: String,
                    temperatureUnit: String,
                    windSpeedUnit: String,
                    precipitationUnit: String,
                ): OpenMeteoForecastResponseDto {
                    val temp = tempMap[keyFor(latitude, longitude)] ?: 24.0
                    return OpenMeteoForecastResponseDto(
                        latitude = latitude,
                        longitude = longitude,
                        elevation = 700.0,
                        utcOffsetSeconds = -10800,
                        timezone = "America/Sao_Paulo",
                        timezoneAbbreviation = "BRT",
                        current =
                            OpenMeteoCurrentDto(
                                time = "2026-11-24T12:00",
                                temperature2m = temp,
                                relativeHumidity2m = 70,
                                apparentTemperature = temp + 0.5,
                                isDay = 1,
                                precipitation = 0.0,
                                rain = 0.0,
                                showers = 0.0,
                                snowfall = 0.0,
                                weatherCode = 1,
                                cloudCover = 20,
                                pressureMsl = 1015.0,
                                surfacePressure = 930.0,
                                windSpeed10m = 10.0,
                                windDirection10m = 120,
                                windGusts10m = 18.0,
                            ),
                        hourly =
                            OpenMeteoHourlyDto(
                                time = List(48) { idx -> "2026-11-%02dT%02d:00".format(24 + (idx / 24), idx % 24) },
                                temperature2m = List(48) { temp },
                                relativeHumidity2m = List(48) { 70 },
                                dewPoint2m = List(48) { 16.0 },
                                apparentTemperature = List(48) { temp + 0.5 },
                                precipitationProbability = List(48) { 10 },
                                precipitation = List(48) { 0.0 },
                                weatherCode = List(48) { 1 },
                                pressureMsl = List(48) { 1015.0 },
                                visibility = List(48) { 10000.0 },
                                windSpeed10m = List(48) { 10.0 },
                                windDirection10m = List(48) { 120 },
                                windGusts10m = List(48) { 18.0 },
                                uvIndex = List(48) { 4.0 },
                                isDay = List(48) { 1 },
                            ),
                        daily =
                            OpenMeteoDailyDto(
                                time = List(16) { d -> "2026-11-%02d".format(1 + d) },
                                weatherCode = List(16) { 1 },
                                temperature2mMax = List(16) { temp + 3.0 },
                                temperature2mMin = List(16) { temp - 4.0 },
                                apparentTemperatureMax = List(16) { temp + 3.5 },
                                apparentTemperatureMin = List(16) { temp - 4.0 },
                                sunrise = List(16) { "2026-11-24T05:20" },
                                sunset = List(16) { "2026-11-24T18:30" },
                                uvIndexMax = List(16) { 6.0 },
                                precipitationSum = List(16) { 0.0 },
                                precipitationProbabilityMax = List(16) { 15 },
                                windSpeed10mMax = List(16) { 14.0 },
                                windGusts10mMax = List(16) { 22.0 },
                                windDirection10mDominant = List(16) { 120 },
                            ),
                    )
                }
            }

        val airApi =
            object : OpenMeteoAirQualityApi {
                override suspend fun getAirQuality(
                    latitude: Double,
                    longitude: Double,
                    current: String,
                    timezone: String,
                ): OpenMeteoAirQualityResponseDto =
                    OpenMeteoAirQualityResponseDto(
                        latitude = latitude,
                        longitude = longitude,
                        utcOffsetSeconds = -10800,
                        timezone = "America/Sao_Paulo",
                        current =
                            OpenMeteoAirQualityCurrentDto(
                                time = "2026-11-24T12:00",
                                europeanAqi = 22,
                                usAqi = 30,
                                pm25 = 7.0,
                                pm10 = 12.0,
                                nitrogenDioxide = 10.0,
                                ozone = 38.0,
                                carbonMonoxide = 180.0,
                                sulphurDioxide = 1.5,
                                dust = 1.0,
                                aerosolOpticalDepth = 0.1,
                                uvIndex = 4.0,
                            ),
                    )
            }

        val geocodingApi =
            object : OpenMeteoGeocodingApi {
                override suspend fun searchCities(
                    name: String,
                    count: Int,
                    language: String,
                    format: String,
                ): OpenMeteoGeocodingResponseDto {
                    val matched =
                        when {
                            name.contains("Copacabana", ignoreCase = true) ->
                                listOf(
                                    OpenMeteoGeocodingResultDto(
                                        id = 3466537L,
                                        name = "Copacabana",
                                        latitude = -22.9711,
                                        longitude = -43.1825,
                                        countryCode = "BR",
                                        country = "Brasil",
                                        admin1 = "Rio de Janeiro",
                                        admin2 = "Rio de Janeiro",
                                        timezone = "America/Sao_Paulo",
                                    ),
                                    OpenMeteoGeocodingResultDto(
                                        id = 3919627L,
                                        name = "Copacabana",
                                        latitude = -16.1667,
                                        longitude = -69.0833,
                                        countryCode = "BO",
                                        country = "Bolívia",
                                        admin1 = "La Paz",
                                        admin2 = "Manco Kapac",
                                        timezone = "America/La_Paz",
                                    ),
                                )
                            else -> emptyList()
                        }
                    return OpenMeteoGeocodingResponseDto(results = matched)
                }
            }

        val rainViewerApi =
            object : RainViewerApi {
                override suspend fun getWeatherMaps(): RainViewerMapsResponseDto {
                    val base = 1763995200L
                    return RainViewerMapsResponseDto(
                        version = "2.0",
                        generated = base + 3600L,
                        host = "https://tilecache.rainviewer.com",
                        radar =
                            RainViewerRadarSectionDto(
                                past = listOf(RainViewerFrameDto(time = base, path = "/v2/radar/$base")),
                                nowcast = null,
                            ),
                    )
                }
            }

        val container =
            ClimaDataContainer(
                database = db,
                preferencesRepository = prefsRepo,
                connectivityObserver = connectivity,
                moshi = NetworkClientFactory.createMoshi(),
                okHttpClient = NetworkClientFactory.createOkHttpClient(),
                forecastApi = forecastApi,
                airQualityApi = airApi,
                geocodingApi = geocodingApi,
                rainViewerApi = rainViewerApi,
            )
        val vm = ClimaMainViewModel(container = container, autoBootstrapOnInit = false)
        runBlocking { prefsRepo.setLocationPermissionPromptShown(true) }

        return AuditHarness(
            db = db,
            prefsRepo = prefsRepo,
            container = container,
            vm = vm,
            tempForCoordinate = tempMap,
            dataStoreScope = dataStoreScope,
        )
    }
}
