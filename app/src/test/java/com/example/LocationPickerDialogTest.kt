package com.example

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.WeatherResult
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
import com.example.data.remote.OpenMeteoGeocodingResponseDto
import com.example.data.remote.OpenMeteoGeocodingResultDto
import com.example.data.remote.OpenMeteoHourlyDto
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerFrameDto
import com.example.data.remote.RainViewerMapsResponseDto
import com.example.data.remote.RainViewerRadarSectionDto
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.maps.CityMapCoordinate
import com.example.maps.LocationLookupResult
import com.example.maps.LocationPermissionSnapshot
import com.example.maps.LocationPrecisionLevel
import com.example.maps.MapLibreInitializer
import com.example.ui.state.ClimaActiveModal
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.theme.ClimaTab
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationPickerDialogTest {

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

    // 1. Tapping Home location indicator opens LocationPickerDialog immediately
    @Test
    fun `test_01_tap_home_location_selector_opens_location_picker_modal`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            val sp = harness.seedSaoPauloPrimary()
            harness.vm.refreshActiveLocation()
            harness.awaitCondition {
                harness.vm.uiState.value.activeLocation?.id == sp.id &&
                    harness.vm.uiState.value.forecast != null
            }

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("location_picker_dialog").assertIsDisplayed()
            composeTestRule.onNodeWithTag("location_picker_title").assertIsDisplayed()
            assertEquals(ClimaActiveModal.LocationPicker, harness.vm.uiState.value.activeModal)
        } finally {
            harness.close()
        }
    }

    // 2. Active tab remains HOME
    @Test
    fun `test_02_active_tab_remains_home_when_opening_picker`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            assertEquals(ClimaTab.HOME, harness.vm.uiState.value.activeTab)
        } finally {
            harness.close()
        }
    }

    // 3. Does not navigate to Cities screen
    @Test
    fun `test_03_opening_picker_does_not_navigate_to_cities_screen`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            assertTrue(composeTestRule.onAllNodesWithTag("cities_screen_list").fetchSemanticsNodes().isEmpty())
            assertTrue(composeTestRule.onAllNodesWithTag("home_screen_list").fetchSemanticsNodes().isNotEmpty())
        } finally {
            harness.close()
        }
    }

    // 4. Opening quick location picker does NOT initialize MapLibre
    @Test
    fun `test_04_opening_picker_does_not_initialize_maplibre`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            val initializedBefore = MapLibreInitializer.isInitialized()
            harness.seedSaoPauloPrimary()

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            assertEquals(initializedBefore, MapLibreInitializer.isInitialized())
            val mapEvents = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().filter { it.contains("MAP_CREATE") }
            assertEquals(0, mapEvents.size)
        } finally {
            harness.close()
        }
    }

    // 5. Opening and closing picker does NOT recreate or initialize new ExoPlayer
    @Test
    fun `test_05_opening_and_closing_picker_does_not_recreate_exoplayer`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            val exoCreatesBefore = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("EXO_CREATE") }
            val exoReleasesBefore = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("EXO_RELEASED") }

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("location_picker_close_button").performClick()
            composeTestRule.waitForIdle()

            val eventsAfter = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
            assertEquals(exoCreatesBefore, eventsAfter.count { it.contains("EXO_CREATE") })
            assertEquals(exoReleasesBefore, eventsAfter.count { it.contains("EXO_RELEASED") })
            assertTrue(eventsAfter.any { it.contains("MODAL_EVENT modal=LocationPicker action=OPEN") })
            assertTrue(eventsAfter.any { it.contains("MODAL_EVENT modal=LocationPicker action=CLOSE") })
        } finally {
            harness.close()
        }
    }

    // 6. Closing dialog without selecting preserves current active location and forecast
    @Test
    fun `test_06_dismissing_picker_preserves_current_active_location_and_forecast`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()
            harness.tempForCoordinate[keyFor(-23.5505, -46.6333)] = 25.0
            harness.vm.refreshActiveLocation()
            harness.awaitCondition { harness.vm.uiState.value.forecast?.current?.temperatureC == 25.0 }

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("location_picker_search_input").performTextInput("Curitiba")
            composeTestRule.onNodeWithTag("location_picker_close_button").performClick()
            composeTestRule.waitForIdle()

            assertEquals(ClimaActiveModal.None, harness.vm.uiState.value.activeModal)
            assertEquals("São Paulo", harness.vm.uiState.value.activeLocation?.name)
            assertEquals(25.0, harness.vm.uiState.value.forecast?.current?.temperatureC)
            assertEquals(ClimaTab.HOME, harness.vm.uiState.value.activeTab)
        } finally {
            harness.close()
        }
    }

    // 7. Selecting geocoding result persists selection and updates activeLocation + forecast + hourly + daily + AQI
    @Test
    fun `test_07_selecting_geocoding_result_persists_and_updates_forecast_hourly_daily_aqi`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()
            harness.tempForCoordinate[keyFor(-23.5505, -46.6333)] = 22.0
            harness.tempForCoordinate[keyFor(-25.4284, -49.2733)] = 16.5
            harness.aqiForCoordinate[keyFor(-25.4284, -49.2733)] = 19
            harness.vm.refreshActiveLocation()
            harness.awaitCondition { harness.vm.uiState.value.forecast?.current?.temperatureC == 22.0 }

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("location_picker_search_input").performTextInput("Curitiba")
            composeTestRule.runOnIdle { harness.vm.submitLocationPickerSearch() }
            harness.awaitCondition {
                harness.vm.uiState.value.locationPickerState.searchResults.any { it.name == "Curitiba" }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("location_picker_search_result_0").performScrollTo().performClick()
            harness.awaitCondition {
                harness.vm.uiState.value.activeLocation?.name == "Curitiba" &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 16.5 &&
                    harness.vm.uiState.value.airQuality?.usAqi == 19
            }
            composeTestRule.waitForIdle()

            val state = harness.vm.uiState.value
            assertEquals(ClimaTab.HOME, state.activeTab)
            assertEquals(ClimaActiveModal.None, state.activeModal)
            assertEquals("Curitiba", state.activeLocation?.name)
            assertEquals(37, state.forecast?.hourly?.size)
            assertEquals(16, state.forecast?.daily?.size)
            assertEquals(19, state.airQuality?.usAqi)

            val savedCuritiba =
                harness.container.cityLocationRepository.getSavedLocations().firstOrNull { it.name == "Curitiba" }
            assertNotNull(savedCuritiba)
            assertTrue(savedCuritiba!!.isPrimary)
            assertEquals(savedCuritiba.id, harness.prefsRepo.preferencesFlow.first().selectedLocationId)
        } finally {
            harness.close()
        }
    }

    // 8. Selecting saved city updates activeLocation and forecast/hourly/daily/AQI on Home
    @Test
    fun `test_08_selecting_saved_city_updates_active_location_and_weather_sections`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            val rio = harness.container.cityLocationRepository.saveCityFromSearch(rioSearch(), setAsPrimary = false)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }
            harness.tempForCoordinate[keyFor(-23.5505, -46.6333)] = 21.0
            harness.tempForCoordinate[keyFor(-22.9068, -43.1729)] = 31.5
            harness.aqiForCoordinate[keyFor(-22.9068, -43.1729)] = 48
            harness.vm.refreshActiveLocation()
            harness.awaitCondition { harness.vm.uiState.value.forecast?.current?.temperatureC == 21.0 }

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("location_picker_saved_item_${rio.id}").performScrollTo().performClick()
            harness.awaitCondition {
                harness.vm.uiState.value.activeLocation?.id == rio.id &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 31.5 &&
                    harness.vm.uiState.value.airQuality?.usAqi == 48
            }

            assertEquals(ClimaTab.HOME, harness.vm.uiState.value.activeTab)
            assertEquals("Rio de Janeiro", harness.vm.uiState.value.activeLocation?.name)
            assertEquals(rio.id, harness.prefsRepo.preferencesFlow.first().selectedLocationId)
        } finally {
            harness.close()
        }
    }

    // 9. "Usar minha localização" respects granted, denied, and permanently denied permissions
    @Test
    fun `test_09_use_my_location_respects_granted_denied_and_permanently_denied_permissions`() = runBlocking {
        // Phase A & B: Denied once vs permanently denied
        val harnessDenied = createHarness(grantFinePermission = false)
        try {
            harnessDenied.seedSaoPauloPrimary()
            var permPromptCount = 0
            var openedSettingsCount = 0

            composeTestRule.setContent {
                Clima3DApp(
                    dataContainer = harnessDenied.container,
                    viewModel = harnessDenied.vm,
                    onLaunchPermissionRequest = { permPromptCount++ },
                    onOpenAppSettingsOverride = { openedSettingsCount++ },
                )
            }
            composeTestRule.waitForIdle()

            // Set temporary denial (canShowRequestRationale = true) AFTER initial LaunchedEffect ran
            harnessDenied.vm.syncLocationPermissionSnapshot(
                LocationPermissionSnapshot(
                    hasFinePermission = false,
                    hasCoarsePermission = false,
                    initialPromptShown = true,
                    canShowRequestRationale = true,
                    isLocationServiceEnabled = true,
                ),
            )
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("location_picker_use_my_location_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(1, permPromptCount)
            assertEquals(0, openedSettingsCount)

            // Phase B: Permanently denied -> displays Settings button and triggers Settings callback
            harnessDenied.vm.syncLocationPermissionSnapshot(
                LocationPermissionSnapshot(
                    hasFinePermission = false,
                    hasCoarsePermission = false,
                    initialPromptShown = true,
                    canShowRequestRationale = false,
                    isLocationServiceEnabled = true,
                ),
            )
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("location_picker_open_settings_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(1, openedSettingsCount)
        } finally {
            harnessDenied.close()
        }

        // Phase C: Permission granted -> uses real GPS lookup and activates GPS coordinates on Home
        val harnessGranted = createHarness(grantFinePermission = true)
        try {
            harnessGranted.seedSaoPauloPrimary()
            harnessGranted.tempForCoordinate[keyFor(-22.4123, -42.9656)] = 19.2

            composeTestRule.runOnUiThread {
                harnessGranted.vm.openLocationPickerModal()
            }
            composeTestRule.waitForIdle()

            val seq = harnessGranted.vm.beginLocationPickerGpsLookup()
            harnessGranted.vm.activateCurrentGpsCoordinatesFromPicker(
                coordinate = CityMapCoordinate(-22.4123, -42.9656),
                requestSeq = seq,
            )

            harnessGranted.awaitCondition {
                harnessGranted.vm.uiState.value.activeLocation?.isCurrentLocation == true &&
                    harnessGranted.vm.uiState.value.forecast?.current?.temperatureC == 19.2
            }
            assertEquals(ClimaActiveModal.None, harnessGranted.vm.uiState.value.activeModal)
            assertEquals(ClimaTab.HOME, harnessGranted.vm.uiState.value.activeTab)
        } finally {
            harnessGranted.close()
        }
    }

    // 10. Absent/disabled GPS or lookup failure keeps dialog open with retry and manual selection working
    @Test
    fun `test_10_gps_disabled_or_lookup_error_keeps_dialog_open_with_retry_and_manual_selection`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()
            harness.tempForCoordinate[keyFor(-25.4284, -49.2733)] = 18.0
            val lookupAttempts = AtomicInteger(0)

            composeTestRule.setContent {
                Clima3DApp(
                    dataContainer = harness.container,
                    viewModel = harness.vm,
                    onFetchPickerGpsLocation = { _, cb ->
                        if (lookupAttempts.incrementAndGet() == 1) {
                            cb(LocationLookupResult.LocationServiceDisabled)
                        } else {
                            cb(LocationLookupResult.UnavailableTemporarily("Sem sinal GPS no momento."))
                        }
                    },
                )
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("home_location_selector").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("location_picker_use_my_location_button").performClick()
            composeTestRule.waitForIdle()

            // Modal remains open with GPS error + retry button
            assertEquals(ClimaActiveModal.LocationPicker, harness.vm.uiState.value.activeModal)
            composeTestRule.onNodeWithTag("location_picker_gps_error").assertIsDisplayed()
            composeTestRule.onNodeWithTag("location_picker_gps_retry_button").performClick()
            composeTestRule.waitForIdle()
            assertEquals(2, lookupAttempts.get())

            // User can still search manually and pick a city smoothly
            composeTestRule.onNodeWithTag("location_picker_search_input").performTextInput("Curitiba")
            composeTestRule.runOnIdle { harness.vm.submitLocationPickerSearch() }
            harness.awaitCondition {
                harness.vm.uiState.value.locationPickerState.searchResults.any { it.name == "Curitiba" }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("location_picker_search_result_0").performScrollTo().performClick()
            harness.awaitCondition {
                harness.vm.uiState.value.activeLocation?.name == "Curitiba" &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 18.0
            }
        } finally {
            harness.close()
        }
    }

    // 11. Superseded / canceled slow geocoding search NEVER overwrites newer results
    @Test
    fun `test_11_superseded_or_canceled_search_response_never_overwrites_newer_results`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            val slowAlphaGate = CompletableDeferred<Unit>()
            harness.perQueryGates["AlphaSlow"] = slowAlphaGate

            harness.vm.openLocationPickerModal()
            harness.vm.updateLocationPickerSearchQuery("AlphaSlow")
            harness.vm.submitLocationPickerSearch()
            harness.awaitCondition { harness.searchQueriesReceived.contains("AlphaSlow") }

            // User changes mind and searches "Curitiba" quickly while "AlphaSlow" is still suspended
            harness.vm.updateLocationPickerSearchQuery("Curitiba")
            harness.vm.submitLocationPickerSearch()
            harness.awaitCondition {
                harness.vm.uiState.value.locationPickerState.searchResults.any { it.name == "Curitiba" }
            }

            // Now complete the old slow request
            slowAlphaGate.complete(Unit)
            delay(100L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val results = harness.vm.uiState.value.locationPickerState.searchResults
            assertEquals(1, results.size)
            assertEquals("Curitiba", results.first().name)
        } finally {
            harness.close()
        }
    }

    // 12. Duplicate identical search queries are avoided
    @Test
    fun `test_12_duplicate_identical_search_queries_are_avoided`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)

            harness.vm.openLocationPickerModal()
            harness.vm.updateLocationPickerSearchQuery("Curitiba")
            harness.vm.submitLocationPickerSearch()
            harness.awaitCondition {
                harness.vm.uiState.value.locationPickerState.hasSearchedCurrentQuery &&
                    !harness.vm.uiState.value.locationPickerState.isSearching
            }
            val countAfterFirst = harness.geocodingCallCount.get()
            assertEquals(1, countAfterFirst)

            // Same trimmed query with trailing space or repeated IME submit must NOT hit Open-Meteo again
            harness.vm.updateLocationPickerSearchQuery("Curitiba ")
            harness.vm.submitLocationPickerSearch()
            delay(350L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, harness.geocodingCallCount.get())
        } finally {
            harness.close()
        }
    }

    // 13. Room-cached weather displays immediately when offline or before live refresh returns
    @Test
    fun `test_13_room_cached_weather_displays_immediately_when_offline_or_before_network`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            val rio = harness.container.cityLocationRepository.saveCityFromSearch(rioSearch(), setAsPrimary = false)

            // Pre-populate Room cache for Rio de Janeiro
            harness.tempForCoordinate[keyFor(rio.latitude, rio.longitude)] = 29.4
            val preload =
                harness.container.weatherRepository.getForecast(
                    latitude = rio.latitude,
                    longitude = rio.longitude,
                    policy = com.example.data.model.CachePolicy.NetworkFirst,
                )
            assertTrue(preload is WeatherResult.Success)

            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            // Go completely offline and hold any network gate
            harness.setOnline(false)
            harness.failForecastRequests.set(true)

            harness.vm.openLocationPickerModal()
            harness.vm.selectSavedLocationFromPicker(rio)

            harness.awaitCondition {
                harness.vm.uiState.value.activeLocation?.id == rio.id &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 29.4
            }

            assertEquals(DataSourceOrigin.LOCAL_CACHE, harness.vm.uiState.value.forecastOrigin)
            assertEquals(ClimaActiveModal.None, harness.vm.uiState.value.activeModal)
            assertEquals(ClimaTab.HOME, harness.vm.uiState.value.activeTab)
        } finally {
            harness.close()
        }
    }

    // 14. Opening, closing, and reopening picker repeatedly does not accumulate players, maps, or leaked state
    @Test
    fun `test_14_repeated_open_close_picker_cycles_do_not_leak_or_accumulate_sessions`() = runBlocking {
        val harness = createHarness(grantFinePermission = true)
        try {
            harness.seedSaoPauloPrimary()

            composeTestRule.setContent {
                Clima3DApp(dataContainer = harness.container, viewModel = harness.vm)
            }
            composeTestRule.waitForIdle()

            val exoCreateBaseline = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot().count { it.contains("EXO_CREATE") }

            repeat(5) { idx ->
                composeTestRule.onNodeWithTag("home_location_selector").performClick()
                composeTestRule.waitForIdle()
                composeTestRule.onNodeWithTag("location_picker_dialog").assertIsDisplayed()
                composeTestRule.onNodeWithTag("location_picker_search_input").performTextReplacement("Teresopolis")
                if (idx % 2 == 0) {
                    composeTestRule.onNodeWithTag("location_picker_close_button").performClick()
                } else {
                    composeTestRule.onNodeWithTag("location_picker_footer_close_button").performClick()
                }
                composeTestRule.waitForIdle()
            }

            // Old search query is cleanly cleared on dismiss & no extra Map/Exo sessions spawned
            val events = ClimaGraphicsLifecycleDiagnostics.recentEventsSnapshot()
            assertEquals(0, events.count { it.contains("MAP_CREATE") })
            assertEquals(exoCreateBaseline, events.count { it.contains("EXO_CREATE") })
            assertEquals("", harness.vm.uiState.value.locationPickerState.searchQuery)
            assertEquals(ClimaActiveModal.None, harness.vm.uiState.value.activeModal)
            assertEquals(ClimaTab.HOME, harness.vm.uiState.value.activeTab)
        } finally {
            harness.close()
        }
    }

    private fun keyFor(lat: Double, lon: Double): String = "${"%.2f".format(lat)}_${"%.2f".format(lon)}"

    private fun saoPauloSearch() =
        CitySearchResult(
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
        )

    private fun rioSearch() =
        CitySearchResult(
            providerId = 3451190L,
            name = "Rio de Janeiro",
            admin1 = "Rio de Janeiro",
            admin2 = null,
            country = "Brasil",
            countryCode = "BR",
            latitude = -22.9068,
            longitude = -43.1729,
            elevationMeters = 11.0,
            timezoneId = "America/Sao_Paulo",
            population = 6747815,
        )

    private inner class PickerHarness(
        val db: Clima3DDatabase,
        val prefsRepo: UserPreferencesRepository,
        val container: ClimaDataContainer,
        val vm: ClimaMainViewModel,
        val geocodingCallCount: AtomicInteger,
        val searchQueriesReceived: MutableList<String>,
        val perQueryGates: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
        val tempForCoordinate: ConcurrentHashMap<String, Double>,
        val aqiForCoordinate: ConcurrentHashMap<String, Int>,
        val failForecastRequests: AtomicBoolean,
        private val onlineFlow: MutableStateFlow<Boolean>,
        private val dataStoreScope: CoroutineScope,
    ) {
        fun setOnline(online: Boolean) {
            onlineFlow.value = online
        }

        suspend fun seedSaoPauloPrimary(): com.example.data.model.SavedLocation {
            val sp = container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
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
            runCatching { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        }
    }

    private fun createHarness(grantFinePermission: Boolean): PickerHarness {
        val context = ApplicationProvider.getApplicationContext<Application>()
        if (grantFinePermission) {
            Shadows.shadowOf(context).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        } else {
            Shadows.shadowOf(context).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "picker_prefs_${UUID.randomUUID()}.preferences_pb")
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

        val geocodingCalls = AtomicInteger(0)
        val queries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queryGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val tempMap = ConcurrentHashMap<String, Double>()
        val aqiMap = ConcurrentHashMap<String, Int>()
        val failForecast = AtomicBoolean(false)

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
                    if (failForecast.get()) throw IOException("Simulated offline")
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
                                time = List(72) { idx -> "2026-11-%02dT%02d:00".format(24 + (idx / 24), idx % 24) },
                                temperature2m = List(72) { temp },
                                relativeHumidity2m = List(72) { 70 },
                                dewPoint2m = List(72) { 16.0 },
                                apparentTemperature = List(72) { temp + 0.5 },
                                precipitationProbability = List(72) { 10 },
                                precipitation = List(72) { 0.0 },
                                weatherCode = List(72) { 1 },
                                pressureMsl = List(72) { 1015.0 },
                                visibility = List(72) { 10000.0 },
                                windSpeed10m = List(72) { 10.0 },
                                windDirection10m = List(72) { 120 },
                                windGusts10m = List(72) { 18.0 },
                                uvIndex = List(72) { 4.0 },
                                isDay = List(72) { 1 },
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
                ): OpenMeteoAirQualityResponseDto {
                    val aqi = aqiMap[keyFor(latitude, longitude)] ?: 30
                    return OpenMeteoAirQualityResponseDto(
                        latitude = latitude,
                        longitude = longitude,
                        utcOffsetSeconds = -10800,
                        timezone = "America/Sao_Paulo",
                        current =
                            OpenMeteoAirQualityCurrentDto(
                                time = "2026-11-24T12:00",
                                europeanAqi = 22,
                                usAqi = aqi,
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
            }

        val geocodingApi =
            object : OpenMeteoGeocodingApi {
                override suspend fun searchCities(
                    name: String,
                    count: Int,
                    language: String,
                    format: String,
                ): OpenMeteoGeocodingResponseDto {
                    geocodingCalls.incrementAndGet()
                    queries += name
                    queryGates[name]?.await()
                    val matched =
                        when {
                            name.contains("Curitiba", ignoreCase = true) ->
                                listOf(
                                    OpenMeteoGeocodingResultDto(
                                        id = 3464975L,
                                        name = "Curitiba",
                                        latitude = -25.4284,
                                        longitude = -49.2733,
                                        elevation = 935.0,
                                        countryCode = "BR",
                                        country = "Brasil",
                                        admin1 = "Paraná",
                                        timezone = "America/Sao_Paulo",
                                        population = 1963726,
                                    ),
                                )

                            name.contains("AlphaSlow", ignoreCase = true) ->
                                listOf(
                                    OpenMeteoGeocodingResultDto(
                                        id = 9999001L,
                                        name = "AlphaSlow City",
                                        latitude = -10.0,
                                        longitude = -50.0,
                                        countryCode = "BR",
                                        country = "Brasil",
                                        admin1 = "Goiás",
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

        return PickerHarness(
            db = db,
            prefsRepo = prefsRepo,
            container = container,
            vm = vm,
            geocodingCallCount = geocodingCalls,
            searchQueriesReceived = queries,
            perQueryGates = queryGates,
            tempForCoordinate = tempMap,
            aqiForCoordinate = aqiMap,
            failForecastRequests = failForecast,
            onlineFlow = onlineFlow,
            dataStoreScope = dataStoreScope,
        )
    }
}
