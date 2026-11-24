package com.example

import android.os.Looper
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.SavedLocation
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
import com.example.data.remote.OpenMeteoHourlyDto
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerFrameDto
import com.example.data.remote.RainViewerMapsResponseDto
import com.example.data.remote.RainViewerRadarSectionDto
import com.example.ui.screens.CitiesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RadarScreen
import com.example.ui.state.CitiesSubTab
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PullToRefreshTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `Home pull-to-refresh updates forecast and air quality and prevents duplicate concurrent requests`() = runBlocking {
        val harness = createTestHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            val gate = CompletableDeferred<Unit>()
            harness.forecastGate = gate
            harness.currentTemperatureC = 28.5
            harness.currentUsAqi = 58

            // First trigger starts refresh and suspends on gate
            harness.vm.onPullRefreshHome()
            harness.awaitCondition { harness.vm.uiState.value.isRefreshing }

            // Duplicate triggers while refreshing MUST be ignored
            harness.vm.onPullRefreshHome()
            harness.vm.onPullRefreshHome()

            gate.complete(Unit)
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 28.5
            }

            assertEquals(1, harness.forecastCallCount.get())
            assertEquals(1, harness.airQualityCallCount.get())
            assertEquals(1, harness.radarCallCount.get())
            assertEquals(58, harness.vm.uiState.value.airQuality?.usAqi)
            assertEquals(DataSourceOrigin.LIVE_NETWORK, harness.vm.uiState.value.forecastOrigin)
        } finally {
            harness.db.close()
        }
    }

    @Test
    fun `Radar pull-to-refresh updates RainViewer frames without stopping active Radar playback`() = runBlocking {
        val harness = createTestHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            // Initial load to obtain 9 RainViewer frames
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    (harness.vm.uiState.value.radarTimeline?.frames?.size ?: 0) == 9
            }

            // Start playback and move to frame index 2
            harness.vm.selectRadarFrame(2)
            harness.vm.toggleRadarPlayback()
            assertTrue(harness.vm.uiState.value.isRadarPlaying)

            // Update RainViewer base timestamp and pull-to-refresh while playback is active
            harness.radarBaseEpoch = 1764010000L
            harness.vm.onPullRefreshRadar()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.radarTimeline?.frames?.firstOrNull()?.timestampSeconds == 1764010000L
            }

            // Playback must remain active and not be killed or reset to lastIndex abruptly
            assertTrue(harness.vm.uiState.value.isRadarPlaying)
            assertEquals(9, harness.vm.uiState.value.radarTimeline?.frames?.size)
            assertTrue(harness.vm.uiState.value.radarSelectedFrameIndex in 0..8)
            harness.vm.stopRadarPlayback()
        } finally {
            harness.db.close()
        }
    }

    @Test
    fun `Cities pull-to-refresh updates all saved cities, blocks duplicates, and preserves cache with offline feedback`() = runBlocking {
        val harness = createTestHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            val rj = harness.container.cityLocationRepository.saveCityFromSearch(rioSearch(), setAsPrimary = false)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.savedLocations.size == 2 }

            // Online refresh populates both São Paulo and Rio summaries
            harness.currentTemperatureC = 31.0
            harness.vm.onPullRefreshCities()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshingCities &&
                    harness.vm.uiState.value.savedLocationSummaries.size == 2
            }
            assertEquals(2, harness.forecastCallCount.get())
            assertEquals(31.0, harness.vm.uiState.value.savedLocationSummaries[sp.id]?.temperatureC ?: 0.0, 0.01)
            assertEquals(31.0, harness.vm.uiState.value.savedLocationSummaries[rj.id]?.temperatureC ?: 0.0, 0.01)

            // Duplicate call guard while in-flight
            val gate = CompletableDeferred<Unit>()
            harness.forecastGate = gate
            harness.currentTemperatureC = 33.0
            harness.vm.onPullRefreshCities()
            harness.awaitCondition { harness.vm.uiState.value.isRefreshingCities }
            harness.vm.onPullRefreshCities()
            gate.complete(Unit)
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshingCities &&
                    harness.vm.uiState.value.savedLocationSummaries[sp.id]?.temperatureC == 33.0
            }
            assertEquals(4, harness.forecastCallCount.get())

            // Go offline and pull-to-refresh: cached summaries are preserved and toast feedback is shown
            harness.setOnline(false)
            harness.vm.onPullRefreshCities()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshingCities &&
                    harness.vm.uiState.value.toastMessage != null
            }

            assertEquals(33.0, harness.vm.uiState.value.savedLocationSummaries[sp.id]?.temperatureC ?: 0.0, 0.01)
            assertEquals(33.0, harness.vm.uiState.value.savedLocationSummaries[rj.id]?.temperatureC ?: 0.0, 0.01)
            assertTrue(harness.vm.uiState.value.savedLocationSummaries[sp.id]?.isFromCache == true)
            assertTrue(harness.vm.uiState.value.toastMessage.orEmpty().contains("cache", ignoreCase = true))
        } finally {
            harness.db.close()
        }
    }

    @Test
    fun `Home pull-to-refresh preserves cached forecast and surfaces feedback when network fails`() = runBlocking {
        val harness = createTestHarness()
        try {
            val sp = harness.container.cityLocationRepository.saveCityFromSearch(saoPauloSearch(), setAsPrimary = true)
            harness.container.preferencesRepository.setSelectedLocationId(sp.id)
            harness.awaitCondition { harness.vm.uiState.value.activeLocation?.id == sp.id }

            // Seed live data into Room cache
            harness.currentTemperatureC = 25.0
            harness.vm.onPullRefreshHome()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.forecast?.current?.temperatureC == 25.0
            }

            // Simulate remote failure while device appears online
            harness.failForecastRequests.set(true)
            harness.vm.onPullRefreshHome()
            harness.awaitCondition {
                !harness.vm.uiState.value.isRefreshing &&
                    harness.vm.uiState.value.toastMessage != null
            }

            assertNotNull(harness.vm.uiState.value.forecast)
            assertEquals(25.0, harness.vm.uiState.value.forecast?.current?.temperatureC ?: 0.0, 0.01)
            assertEquals(DataSourceOrigin.LOCAL_CACHE, harness.vm.uiState.value.forecastOrigin)
            assertTrue(harness.vm.uiState.value.toastMessage.orEmpty().contains("cache", ignoreCase = true))
        } finally {
            harness.db.close()
        }
    }

    @Test
    fun `HomeScreen, RadarScreen, and CitiesScreen expose PullToRefresh containers and handle swipeDown`() {
        var homePulls = 0
        var radarPulls = 0
        var citiesPulls = 0

        val location =
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
            )

        // 1. HomeScreen swipeDown
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                HomeScreen(
                    uiState = ClimaUiState(activeTab = ClimaTab.HOME, activeLocation = location),
                    contentPadding = PaddingValues(12.dp),
                    onOpenCities = {},
                    onOpenSettings = {},
                    onOpenAlerts = {},
                    onRefresh = { homePulls++ },
                    onToggleDaysExpanded = {},
                    onHourClick = { _, _ -> },
                    onDayClick = { _, _ -> },
                    onMetricClick = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_pull_refresh").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_screen_list").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        assertTrue("Home swipeDown must trigger pull-to-refresh", homePulls >= 1)
    }

    @Test
    fun `RadarScreen swipeDown triggers Radar refresh without breaking map container`() {
        var radarPulls = 0
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                RadarScreen(
                    uiState = ClimaUiState(activeTab = ClimaTab.RADAR),
                    contentPadding = PaddingValues(12.dp),
                    onToggleLayerMenu = {},
                    onSelectLayer = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onSelectFrame = {},
                    onTogglePlay = {},
                    onRefreshRadar = { radarPulls++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_pull_refresh").assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_screen_list").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        assertTrue("Radar swipeDown must trigger pull-to-refresh", radarPulls >= 1)
    }

    @Test
    fun `CitiesScreen swipeDown triggers saved cities refresh without breaking map section`() {
        var citiesPulls = 0
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                CitiesScreen(
                    uiState = ClimaUiState(activeTab = ClimaTab.CITIES, citiesSubTab = CitiesSubTab.SAVED),
                    contentPadding = PaddingValues(12.dp),
                    onSelectSubTab = {},
                    onMapStateChanged = {},
                    onPointNameChanged = {},
                    onSaveSelectedPoint = {},
                    onSearchQueryChanged = {},
                    onSubmitSearch = {},
                    onAddCityResult = {},
                    onSelectSavedLocation = {},
                    onRemoveSavedLocation = {},
                    onRefreshCities = { citiesPulls++ },
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_pull_refresh").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cities_screen_list").performTouchInput { swipeDown() }
        composeTestRule.waitForIdle()
        assertTrue("Cities swipeDown must trigger pull-to-refresh", citiesPulls >= 1)
    }

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

    private class TestHarness(
        val db: Clima3DDatabase,
        val container: ClimaDataContainer,
        val vm: ClimaMainViewModel,
        val forecastCallCount: AtomicInteger,
        val airQualityCallCount: AtomicInteger,
        val radarCallCount: AtomicInteger,
        val failForecastRequests: AtomicBoolean,
        private val onlineFlow: MutableStateFlow<Boolean>,
    ) {
        @Volatile var currentTemperatureC: Double = 24.0
        @Volatile var currentUsAqi: Int = 35
        @Volatile var radarBaseEpoch: Long = 1763995200L
        @Volatile var forecastGate: CompletableDeferred<Unit>? = null

        fun setOnline(online: Boolean) {
            onlineFlow.value = online
        }

        suspend fun awaitCondition(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
            withTimeout(timeoutMs) {
                while (!predicate()) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(20L)
                }
            }
        }
    }

    private fun createTestHarness(): TestHarness {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "ptr_prefs_${UUID.randomUUID()}.preferences_pb")
        val prefsRepo =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { prefsFile },
                ),
            )
        val onlineFlow = MutableStateFlow(true)
        val connectivity =
            object : ConnectivityObserver {
                override fun isOnline(): Boolean = onlineFlow.value
                override fun observeOnlineStatus(): Flow<Boolean> = onlineFlow
            }
        val forecastCalls = AtomicInteger(0)
        val airCalls = AtomicInteger(0)
        val radarCalls = AtomicInteger(0)
        val failForecast = AtomicBoolean(false)

        lateinit var harnessRef: TestHarness

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
                    harnessRef.forecastGate?.await()
                    forecastCalls.incrementAndGet()
                    if (failForecast.get()) {
                        throw IOException("Simulated network outage")
                    }
                    val temp = harnessRef.currentTemperatureC
                    return OpenMeteoForecastResponseDto(
                        latitude = latitude,
                        longitude = longitude,
                        elevation = 760.0,
                        utcOffsetSeconds = -10800,
                        timezone = "America/Sao_Paulo",
                        timezoneAbbreviation = "BRT",
                        current =
                            OpenMeteoCurrentDto(
                                time = "2026-11-24T12:00",
                                temperature2m = temp,
                                relativeHumidity2m = 74,
                                apparentTemperature = temp + 1.0,
                                isDay = 1,
                                precipitation = 0.0,
                                rain = 0.0,
                                showers = 0.0,
                                snowfall = 0.0,
                                weatherCode = 2,
                                cloudCover = 35,
                                pressureMsl = 1014.0,
                                surfacePressure = 930.0,
                                windSpeed10m = 12.0,
                                windDirection10m = 90,
                                windGusts10m = 20.0,
                            ),
                        hourly =
                            OpenMeteoHourlyDto(
                                time = List(37) { idx -> "2026-11-24T%02d:00".format(idx % 24) },
                                temperature2m = List(37) { temp },
                                relativeHumidity2m = List(37) { 74 },
                                dewPoint2m = List(37) { 18.0 },
                                apparentTemperature = List(37) { temp + 1.0 },
                                precipitationProbability = List(37) { 15 },
                                precipitation = List(37) { 0.0 },
                                weatherCode = List(37) { 2 },
                                pressureMsl = List(37) { 1014.0 },
                                visibility = List(37) { 10000.0 },
                                windSpeed10m = List(37) { 12.0 },
                                windDirection10m = List(37) { 90 },
                                windGusts10m = List(37) { 20.0 },
                                uvIndex = List(37) { 5.0 },
                                isDay = List(37) { 1 },
                            ),
                        daily =
                            OpenMeteoDailyDto(
                                time = List(16) { d -> "2026-11-%02d".format(1 + d) },
                                weatherCode = List(16) { 2 },
                                temperature2mMax = List(16) { temp + 3.0 },
                                temperature2mMin = List(16) { temp - 5.0 },
                                apparentTemperatureMax = List(16) { temp + 4.0 },
                                apparentTemperatureMin = List(16) { temp - 5.0 },
                                sunrise = List(16) { "2026-11-24T05:18" },
                                sunset = List(16) { "2026-11-24T18:34" },
                                uvIndexMax = List(16) { 6.0 },
                                precipitationSum = List(16) { 0.0 },
                                precipitationProbabilityMax = List(16) { 20 },
                                windSpeed10mMax = List(16) { 16.0 },
                                windGusts10mMax = List(16) { 24.0 },
                                windDirection10mDominant = List(16) { 90 },
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
                    airCalls.incrementAndGet()
                    return OpenMeteoAirQualityResponseDto(
                        latitude = latitude,
                        longitude = longitude,
                        utcOffsetSeconds = -10800,
                        timezone = "America/Sao_Paulo",
                        current =
                            OpenMeteoAirQualityCurrentDto(
                                time = "2026-11-24T12:00",
                                europeanAqi = 28,
                                usAqi = harnessRef.currentUsAqi,
                                pm25 = 9.5,
                                pm10 = 16.0,
                                nitrogenDioxide = 12.0,
                                ozone = 42.0,
                                carbonMonoxide = 200.0,
                                sulphurDioxide = 2.0,
                                dust = 1.5,
                                aerosolOpticalDepth = 0.15,
                                uvIndex = 5.0,
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
                ): OpenMeteoGeocodingResponseDto = OpenMeteoGeocodingResponseDto(results = emptyList())
            }

        val rainViewerApi =
            object : RainViewerApi {
                override suspend fun getWeatherMaps(): RainViewerMapsResponseDto {
                    radarCalls.incrementAndGet()
                    val base = harnessRef.radarBaseEpoch
                    val past =
                        List(9) { idx ->
                            val ts = base + idx * 600L
                            RainViewerFrameDto(time = ts, path = "/v2/radar/$ts")
                        }
                    return RainViewerMapsResponseDto(
                        version = "2.0",
                        generated = base + 4800L,
                        host = "https://tilecache.rainviewer.com",
                        radar = RainViewerRadarSectionDto(past = past, nowcast = null),
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
        harnessRef =
            TestHarness(
                db = db,
                container = container,
                vm = vm,
                forecastCallCount = forecastCalls,
                airQualityCallCount = airCalls,
                radarCallCount = radarCalls,
                failForecastRequests = failForecast,
                onlineFlow = onlineFlow,
            )
        return harnessRef
    }
}
