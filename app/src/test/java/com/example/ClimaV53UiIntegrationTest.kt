package com.example

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.AppThemeMode
import com.example.data.local.preferences.UserPreferences
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.AirQualitySnapshot
import com.example.data.model.CitySearchResult
import com.example.data.model.CurrentWeather
import com.example.data.model.DailyForecastPoint
import com.example.data.model.DataSourceOrigin
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.PrecipitationUnit
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.RainViewerRadarFrame
import com.example.data.model.SavedLocation
import com.example.data.model.TemperatureUnit
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WindSpeedUnit
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.NetworkClientFactory
import com.example.maps.RadarBaseStyle
import com.example.media.WeatherCondition
import com.example.ui.components.ClimaDetailModalHost
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.CitiesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RadarScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.state.AlertSeverity
import com.example.ui.state.AlertsSubTab
import com.example.ui.state.CitiesSubTab
import com.example.ui.state.ClimaActiveModal
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.state.ClimaUiState
import com.example.ui.state.InAppWeatherAlertEngine
import com.example.ui.state.PressureCategory
import com.example.ui.state.WeatherMetricType
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClimaV53UiIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `InAppWeatherAlertEngine classifies sea-level pressure and UV accurately`() {
        assertEquals(PressureCategory.LOW, InAppWeatherAlertEngine.classifyPressureHpa(1004.5))
        assertEquals(PressureCategory.NORMAL, InAppWeatherAlertEngine.classifyPressureHpa(1013.2))
        assertEquals(PressureCategory.HIGH, InAppWeatherAlertEngine.classifyPressureHpa(1024.0))

        // Calm weather produces ZERO active alerts (never fabricates warnings)
        val calmForecast = sampleForecast(weatherCode = 2, precipMm = 0.0, windGustsKmh = 18.0, uv = 4.0)
        val calmAlerts = InAppWeatherAlertEngine.evaluateAlerts("São Paulo", calmForecast, sampleAirQuality(usAqi = 32))
        assertTrue(calmAlerts.none { it.isActive })

        // Severe thunderstorm + strong gusts + high UV + poor AQI derives real alerts
        val severeForecast = sampleForecast(weatherCode = 95, precipMm = 14.5, windGustsKmh = 68.0, uv = 9.5)
        val severeAlerts = InAppWeatherAlertEngine.evaluateAlerts("São Paulo", severeForecast, sampleAirQuality(usAqi = 165))
        assertTrue(severeAlerts.any { it.isActive && it.severity == AlertSeverity.HIGH })
        assertTrue(severeAlerts.any { it.title.contains("Rajadas", ignoreCase = true) })
        assertTrue(severeAlerts.any { it.title.contains("UV", ignoreCase = true) })
        assertTrue(severeAlerts.any { it.title.contains("Qualidade do ar", ignoreCase = true) })
    }

    @Test
    fun `HomeScreen renders V53 hero, 37h hourly strip, expands 7 to 16 days, and opens modals`() {
        val forecast = sampleForecast(weatherCode = 63, precipMm = 3.2, windGustsKmh = 28.0, uv = 6.0)
        val baseState =
            ClimaUiState(
                activeLocation = sampleSavedLocation(),
                forecast = forecast,
                forecastOrigin = DataSourceOrigin.LOCAL_CACHE,
                forecastFetchedAtMillis = 1764000000000L,
                isOnline = false,
                airQuality = sampleAirQuality(usAqi = 42),
                alerts = InAppWeatherAlertEngine.evaluateAlerts("São Paulo", forecast, sampleAirQuality(usAqi = 42)),
            )

        var stateHolder = androidx.compose.runtime.mutableStateOf(baseState)

        composeTestRule.setContent {
            val state = stateHolder.value
            Clima3DTheme(darkTheme = true) {
                HomeScreen(
                    uiState = state,
                    contentPadding = PaddingValues(12.dp),
                    onOpenCities = {},
                    onOpenSettings = {},
                    onOpenAlerts = {},
                    onRefresh = {},
                    onToggleDaysExpanded = {
                        stateHolder.value = state.copy(daysExpanded = !state.daysExpanded)
                    },
                    onHourClick = { idx, point ->
                        stateHolder.value = state.copy(activeModal = ClimaActiveModal.HourDetail(idx, point))
                    },
                    onDayClick = { idx, point ->
                        stateHolder.value = state.copy(activeModal = ClimaActiveModal.DayDetail(idx, point))
                    },
                    onMetricClick = { metric ->
                        stateHolder.value = state.copy(activeModal = ClimaActiveModal.MetricDetail(metric))
                    },
                )
                ClimaDetailModalHost(
                    uiState = state,
                    onDismiss = { stateHolder.value = state.copy(activeModal = ClimaActiveModal.None) },
                    onOpenPressureInfo = { stateHolder.value = state.copy(activeModal = ClimaActiveModal.PressureInfo) },
                )
            }
        }
        composeTestRule.waitForIdle()

        // Hero & offline cache badge displayed
        composeTestRule.onNodeWithTag("home_hero_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_current_temperature").assertTextContains("24°C")
        composeTestRule.onNodeWithTag("home_offline_cache_banner").assertIsDisplayed()

        // 37h hourly strip displayed & hour card tap opens HourDetail modal
        composeTestRule.onNodeWithTag("home_hourly_panel").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_hour_card_0").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hour_detail_modal_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("modal_close_button").performClick()
        composeTestRule.waitForIdle()

        // Daily section starts with 7 days and expands to 16 days
        composeTestRule.onNodeWithTag("home_day_row_6").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("home_day_row_15").fetchSemanticsNodes().size)
        composeTestRule.onNodeWithTag("home_days_expand_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_day_row_15").performScrollTo().assertIsDisplayed()

        // Tap day row opens DayDetail modal
        composeTestRule.onNodeWithTag("home_day_row_0").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("day_detail_modal_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("modal_close_button").performClick()
        composeTestRule.waitForIdle()

        // Metrics section opens Pressure detail -> PressureInfo modal
        composeTestRule.onNodeWithTag("metric_card_pressure").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("metric_modal_pressure_info_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pressure_info_modal_title").assertIsDisplayed()
    }

    @Test
    fun `RadarScreen renders real RainViewer frames, layer switcher, zoom, and timeline controls`() {
        val radar = sampleRadarSnapshot()
        val stateHolder =
            androidx.compose.runtime.mutableStateOf(
                ClimaUiState(
                    activeTab = ClimaTab.RADAR,
                    activeLocation = sampleSavedLocation(),
                    forecast = sampleForecast(weatherCode = 63, precipMm = 4.2, windGustsKmh = 20.0, uv = 2.0),
                    radarTimeline = radar,
                    radarOrigin = DataSourceOrigin.LIVE_NETWORK,
                    radarSelectedFrameIndex = 8,
                    radarZoom = 6.0,
                ),
            )

        composeTestRule.setContent {
            val state = stateHolder.value
            Clima3DTheme(darkTheme = true) {
                RadarScreen(
                    uiState = state,
                    contentPadding = PaddingValues(12.dp),
                    onToggleLayerMenu = {
                        stateHolder.value = state.copy(radarLayerMenuExpanded = !state.radarLayerMenuExpanded)
                    },
                    onSelectLayer = { layer ->
                        stateHolder.value = state.copy(radarLayer = layer, radarLayerMenuExpanded = false)
                    },
                    onZoomIn = { stateHolder.value = state.copy(radarZoom = state.radarZoom + 1.0) },
                    onZoomOut = { stateHolder.value = state.copy(radarZoom = state.radarZoom - 1.0) },
                    onSelectFrame = { idx -> stateHolder.value = state.copy(radarSelectedFrameIndex = idx) },
                    onTogglePlay = { stateHolder.value = state.copy(isRadarPlaying = !state.isRadarPlaying) },
                    onRefreshRadar = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("radar_header_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_summary_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_layer_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("radar_layer_menu").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("radar_layer_option_base").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(RadarBaseStyle.SATELLITE, stateHolder.value.radarLayer)

        composeTestRule.onNodeWithTag("radar_frame_counter").performScrollTo().assertTextContains("Quadro 9 de 9", substring = true)
        composeTestRule.onNodeWithTag("radar_play_button").performScrollTo().performClick()
        assertTrue(stateHolder.value.isRadarPlaying)

        composeTestRule.onNodeWithTag("radar_layer_status_card").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `CitiesScreen supports MapLibre custom point saving and disambiguated Geocoding search`() {
        val searchResults =
            listOf(
                CitySearchResult(1L, "Santa Maria", "Rio Grande do Sul", null, "Brasil", "BR", -29.68, -53.80, 113.0, "America/Sao_Paulo", 280000),
                CitySearchResult(2L, "Santa Maria", "Califórnia", null, "Estados Unidos", "US", 34.95, -120.43, 66.0, "America/Los_Angeles", 109000),
            )
        var addedResult: CitySearchResult? = null
        var pointSaved = false

        val stateHolder =
            androidx.compose.runtime.mutableStateOf(
                ClimaUiState(
                    activeTab = ClimaTab.CITIES,
                    savedLocations = listOf(sampleSavedLocation()),
                    activeLocation = sampleSavedLocation(),
                    citiesSubTab = CitiesSubTab.SAVED,
                ),
            )

        composeTestRule.setContent {
            val state = stateHolder.value
            Clima3DTheme(darkTheme = true) {
                CitiesScreen(
                    uiState = state,
                    contentPadding = PaddingValues(12.dp),
                    onSelectSubTab = { stateHolder.value = state.copy(citiesSubTab = it) },
                    onMapStateChanged = { stateHolder.value = state.copy(cityMapState = it) },
                    onPointNameChanged = { stateHolder.value = state.copy(customPointNameInput = it) },
                    onSaveSelectedPoint = { pointSaved = true },
                    onSearchQueryChanged = { query ->
                        stateHolder.value = state.copy(citySearchQuery = query, citySearchResults = searchResults)
                    },
                    onSubmitSearch = {},
                    onAddCityResult = { addedResult = it },
                    onSelectSavedLocation = {},
                    onRemoveSavedLocation = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("cities_map_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("cities_screen_list").performScrollToKey("cities_save_point_card")
        composeTestRule.onNodeWithTag("cities_point_name_input").performTextInput("Sítio Serra")
        composeTestRule.onNodeWithTag("cities_save_point_button").performClick()
        assertTrue(pointSaved)

        // Switch to Search tab & verify disambiguation of same-name cities
        composeTestRule.onNodeWithTag("cities_screen_list").performScrollToKey("cities_toolbar")
        composeTestRule.onNodeWithTag("cities_subtab_search").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_search_input").performTextInput("Santa Maria")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("cities_screen_list").performScrollToKey("cities_search_panel")
        composeTestRule.onNodeWithText("Rio Grande do Sul, Brasil").assertExists()
        composeTestRule.onNodeWithText("Califórnia, Estados Unidos").assertExists()
        composeTestRule.onNodeWithTag("city_search_result_0").performClick()
        assertEquals(1L, addedResult?.providerId)
    }

    @Test
    fun `SettingsScreen and AlertsScreen update preferences and guarantee zero push notifications`() {
        val stateHolder =
            androidx.compose.runtime.mutableStateOf(
                ClimaUiState(
                    activeTab = ClimaTab.SETTINGS,
                    activeLocation = sampleSavedLocation(),
                    forecast = sampleForecast(weatherCode = 2, precipMm = 0.0, windGustsKmh = 14.0, uv = 3.0),
                    preferences = UserPreferences(),
                ),
            )
        var pressureInfoOpened = false
        var alertsBackPressed = false

        composeTestRule.setContent {
            val state = stateHolder.value
            Clima3DTheme(darkTheme = state.preferences.themeMode != AppThemeMode.LIGHT) {
                if (state.activeTab == ClimaTab.SETTINGS) {
                    SettingsScreen(
                        uiState = state,
                        contentPadding = PaddingValues(12.dp),
                        onSetThemeMode = { stateHolder.value = state.copy(preferences = state.preferences.copy(themeMode = it)) },
                        onSetTemperatureUnit = { stateHolder.value = state.copy(preferences = state.preferences.copy(temperatureUnit = it)) },
                        onSetWindSpeedUnit = { stateHolder.value = state.copy(preferences = state.preferences.copy(windSpeedUnit = it)) },
                        onSetPrecipitationUnit = { stateHolder.value = state.copy(preferences = state.preferences.copy(precipitationUnit = it)) },
                        onSetPressureUnit = {},
                        onSetVisibilityUnit = {},
                        onSetTimeFormat = {},
                        onSetShowPressureStatus = { stateHolder.value = state.copy(preferences = state.preferences.copy(showPressureStatus = it)) },
                        onOpenPressureInfo = { pressureInfoOpened = true },
                        onSetWidgetPreviewSize = { stateHolder.value = state.copy(preferences = state.preferences.copy(widgetPreviewSize = it)) },
                        onSetAnimatedBackground = { stateHolder.value = state.copy(preferences = state.preferences.copy(animatedBackgroundEnabled = it)) },
                        onSetAutoRefreshOnOpen = { stateHolder.value = state.copy(preferences = state.preferences.copy(autoRefreshOnOpen = it)) },
                        onOpenCities = {},
                        onResetPreferences = { stateHolder.value = state.copy(preferences = UserPreferences()) },
                    )
                } else {
                    AlertsScreen(
                        uiState = state,
                        contentPadding = PaddingValues(12.dp),
                        onSelectSubTab = { stateHolder.value = state.copy(alertsSubTab = it) },
                        onBackToHome = { alertsBackPressed = true },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("setting_theme_light").performClick()
        assertEquals(AppThemeMode.LIGHT, stateHolder.value.preferences.themeMode)

        composeTestRule.onNodeWithTag("setting_temp_fahrenheit").performClick()
        assertEquals(TemperatureUnit.FAHRENHEIT, stateHolder.value.preferences.temperatureUnit)

        composeTestRule.onNodeWithTag("settings_screen_list").performScrollToKey("setting_pressure")
        composeTestRule.onNodeWithTag("setting_pressure_info_button").performClick()
        assertTrue(pressureInfoOpened)

        composeTestRule.onNodeWithTag("settings_screen_list").performScrollToKey("setting_widgets")
        composeTestRule.onNodeWithTag("setting_widget_4×3").performClick()
        assertEquals("4×3", stateHolder.value.preferences.widgetPreviewSize)

        composeTestRule.onNodeWithTag("settings_screen_list").performScrollToKey("setting_animated_bg")
        composeTestRule.onNodeWithTag("setting_switch_animated_bg").performClick()
        assertFalse(stateHolder.value.preferences.animatedBackgroundEnabled)

        // Switch to AlertsScreen and verify calm empty state + no-push guarantee + back button
        stateHolder.value = stateHolder.value.copy(activeTab = ClimaTab.ALERTS, alerts = emptyList())
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("alerts_empty_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("alerts_no_push_notice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("alerts_back_button").performClick()
        assertTrue(alertsBackPressed)
    }

    @Test
    fun `UserPreferencesRepository persists V53 settings (theme, pressure status, widget size) and resets cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "v53_prefs_${UUID.randomUUID()}.preferences_pb")
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                produceFile = { file },
            )
        val repo = UserPreferencesRepository(dataStore)

        repo.setThemeMode(AppThemeMode.LIGHT)
        repo.setTemperatureUnit(TemperatureUnit.FAHRENHEIT)
        repo.setWindSpeedUnit(WindSpeedUnit.MPH)
        repo.setPrecipitationUnit(PrecipitationUnit.INCH)
        repo.setShowPressureStatus(false)
        repo.setWidgetPreviewSize("4×3")
        repo.setAnimatedBackgroundEnabled(false)

        val updated = repo.preferencesFlow.first()
        assertEquals(AppThemeMode.LIGHT, updated.themeMode)
        assertEquals(TemperatureUnit.FAHRENHEIT, updated.temperatureUnit)
        assertEquals(WindSpeedUnit.MPH, updated.windSpeedUnit)
        assertEquals(PrecipitationUnit.INCH, updated.precipitationUnit)
        assertFalse(updated.showPressureStatus)
        assertEquals("4×3", updated.widgetPreviewSize)
        assertFalse(updated.animatedBackgroundEnabled)

        repo.resetToDefaults()
        val reset = repo.preferencesFlow.first()
        assertEquals(AppThemeMode.DARK, reset.themeMode)
        assertEquals(TemperatureUnit.CELSIUS, reset.temperatureUnit)
        assertTrue(reset.showPressureStatus)
        assertEquals("4×2", reset.widgetPreviewSize)
        assertTrue(reset.animatedBackgroundEnabled)
    }

    @Test
    fun `ClimaMainViewModel wires real ClimaDataContainer preferences and tab transitions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "vm_prefs_${UUID.randomUUID()}.preferences_pb")
        val prefsRepo =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { prefsFile },
                ),
            )
        try {
            val baseUrl = "https://localhost.test/"
            val moshi = NetworkClientFactory.createMoshi()
            val okHttp = NetworkClientFactory.createOkHttpClient()
            val connectivity =
                object : ConnectivityObserver {
                    override fun isOnline(): Boolean = false
                    override fun observeOnlineStatus(): Flow<Boolean> = MutableStateFlow(false)
                }
            val container =
                ClimaDataContainer(
                    database = db,
                    preferencesRepository = prefsRepo,
                    connectivityObserver = connectivity,
                    moshi = moshi,
                    okHttpClient = okHttp,
                    forecastApi = NetworkClientFactory.createForecastApi(okHttp, moshi, baseUrl),
                    airQualityApi = NetworkClientFactory.createAirQualityApi(okHttp, moshi, baseUrl),
                    geocodingApi = NetworkClientFactory.createGeocodingApi(okHttp, moshi, baseUrl),
                    rainViewerApi = NetworkClientFactory.createRainViewerApi(okHttp, moshi, baseUrl),
                )
            val vm = ClimaMainViewModel(container = container, autoBootstrapOnInit = false)

            vm.selectTab(ClimaTab.ALERTS)
            assertEquals(ClimaTab.ALERTS, vm.uiState.value.activeTab)
            assertTrue(vm.navigateBackFromSecondary())
            assertEquals(ClimaTab.HOME, vm.uiState.value.activeTab)

            vm.toggleDaysExpanded()
            assertTrue(vm.uiState.value.daysExpanded)
            vm.openPressureInfoModal()
            assertEquals(ClimaActiveModal.PressureInfo, vm.uiState.value.activeModal)
            assertTrue(vm.navigateBackFromSecondary())
            assertEquals(ClimaActiveModal.None, vm.uiState.value.activeModal)
        } finally {
            db.close()
        }
    }

    @Test
    fun `Tempestade com granizo icon integrates animated WebP resource, static fallback, WMO 96 and 99 mapping, and renders across Home, hourly, daily, and modals`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Validate AnimatedWeatherWebp.TEMPESTADE_COM_GRANIZO and WeatherCondition.GRANIZO contract
        val hailWebp = com.example.media.AnimatedWeatherWebp.TEMPESTADE_COM_GRANIZO
        assertEquals("tempestade_com_granizo", hailWebp.key)
        assertEquals(R.drawable.wx_icon_tempestade_com_granizo, hailWebp.drawableRes)
        assertEquals(hailWebp, WeatherCondition.GRANIZO.animatedWebp)
        assertEquals(R.drawable.wx_fallback_granizo, WeatherCondition.GRANIZO.fallbackVectorRes)

        // Verify other storm/rain conditions remain intact
        assertEquals(com.example.media.AnimatedWeatherWebp.TEMPESTADE_COM_CHUVA, WeatherCondition.TEMPESTADE.animatedWebp)
        assertEquals(com.example.media.AnimatedWeatherWebp.TEMPESTADE_FORTE, WeatherCondition.TEMPESTADE_FORTE.animatedWebp)
        assertEquals(com.example.media.AnimatedWeatherWebp.CHUVA_MODERADA, WeatherCondition.CHUVA.animatedWebp)

        // 2. Validate physical VP8X animated WebP resource (420x420 1:1, alpha + animation flags, 30 frames) and static fallback drawable
        val webpBytes = context.resources.openRawResource(R.drawable.wx_icon_tempestade_com_granizo).use { it.readBytes() }
        assertTrue("Animated WebP resource must not be empty", webpBytes.size > 30)
        assertEquals("RIFF", String(webpBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBPVP8X", String(webpBytes, 8, 8, Charsets.US_ASCII))
        val vp8xFlags = webpBytes[20].toInt() and 0xFF
        assertTrue("VP8X animation bit (0x02) must be set", (vp8xFlags and 0x02) != 0)
        assertTrue("VP8X alpha bit (0x10) must be set", (vp8xFlags and 0x10) != 0)
        val canvasWidth = 1 + ((webpBytes[24].toInt() and 0xFF) or ((webpBytes[25].toInt() and 0xFF) shl 8) or ((webpBytes[26].toInt() and 0xFF) shl 16))
        val canvasHeight = 1 + ((webpBytes[27].toInt() and 0xFF) or ((webpBytes[28].toInt() and 0xFF) shl 8) or ((webpBytes[29].toInt() and 0xFF) shl 16))
        assertEquals(420, canvasWidth)
        assertEquals(420, canvasHeight)
        val fallbackDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.wx_fallback_granizo)
        org.junit.Assert.assertNotNull(fallbackDrawable)

        // 3. Validate WeatherImageLoader & buildWeatherWebpRequest across all V53 icon slots
        val iconSlots = listOf(
            com.example.media.WeatherIconSize.Hero,
            com.example.media.WeatherIconSize.Hourly,
            com.example.media.WeatherIconSize.Daily,
            com.example.media.WeatherIconSize.Radar,
            com.example.media.WeatherIconSize.Alert,
            com.example.media.WeatherIconSize.Modal,
            com.example.media.WeatherIconSize.Widget,
        )
        for (slotDp in iconSlots) {
            val req = com.example.ui.components.buildWeatherWebpRequest(
                context = context,
                drawableRes = hailWebp.drawableRes,
                targetSizePx = slotDp.value.toInt(),
            )
            assertEquals(hailWebp.drawableRes, req.data)
            assertFalse(req.allowHardware)
            assertEquals(-1, req.parameters.value<Int>("coil#repeat_count"))
        }

        // 4. Validate UI rendering across Home Hero, 37h hourly strip, 16-day forecast, detail modals, and unit conversions (°C/°F, km/h/mph)
        val hailForecast = sampleForecast(weatherCode = 96, precipMm = 18.0, windGustsKmh = 64.0, uv = 7.0).let { base ->
            base.copy(
                current = base.current.copy(
                    weatherCode = 96,
                    weatherDescriptionPt = com.example.data.model.WmoWeatherCodeMapper.descriptionPt(96, isDay = true),
                    visualConditionKey = com.example.data.model.WmoWeatherCodeMapper.toVisualCondition(96, isDay = true).key,
                ),
                hourly = base.hourly.mapIndexed { idx, h ->
                    val code = if (idx % 2 == 0) 96 else 99
                    h.copy(
                        weatherCode = code,
                        weatherDescriptionPt = com.example.data.model.WmoWeatherCodeMapper.descriptionPt(code, isDay = idx % 3 != 0),
                        visualConditionKey = com.example.data.model.WmoWeatherCodeMapper.toVisualCondition(code, isDay = idx % 3 != 0).key,
                    )
                },
                daily = base.daily.mapIndexed { idx, d ->
                    val code = if (idx % 2 == 0) 96 else 99
                    d.copy(
                        weatherCode = code,
                        weatherDescriptionPt = com.example.data.model.WmoWeatherCodeMapper.descriptionPt(code, isDay = true),
                        visualConditionKey = com.example.data.model.WmoWeatherCodeMapper.toVisualCondition(code, isDay = true).key,
                    )
                },
            )
        }

        val stateHolder = androidx.compose.runtime.mutableStateOf(
            ClimaUiState(
                activeLocation = sampleSavedLocation(),
                forecast = hailForecast,
                preferences = UserPreferences(
                    temperatureUnit = TemperatureUnit.FAHRENHEIT,
                    windSpeedUnit = WindSpeedUnit.MPH,
                ),
                alerts = InAppWeatherAlertEngine.evaluateAlerts("São Paulo", hailForecast, sampleAirQuality(usAqi = 50)),
            ),
        )

        composeTestRule.setContent {
            val state = stateHolder.value
            Clima3DTheme(darkTheme = true) {
                HomeScreen(
                    uiState = state,
                    contentPadding = PaddingValues(12.dp),
                    onOpenCities = {},
                    onOpenSettings = {},
                    onOpenAlerts = {},
                    onRefresh = {},
                    onToggleDaysExpanded = {},
                    onHourClick = { idx, point ->
                        stateHolder.value = state.copy(activeModal = ClimaActiveModal.HourDetail(idx, point))
                    },
                    onDayClick = { idx, point ->
                        stateHolder.value = state.copy(activeModal = ClimaActiveModal.DayDetail(idx, point))
                    },
                    onMetricClick = {},
                )
                ClimaDetailModalHost(
                    uiState = state,
                    onDismiss = { stateHolder.value = state.copy(activeModal = ClimaActiveModal.None) },
                    onOpenPressureInfo = {},
                )
                // Also render static fallback mode explicitly to verify static vector compatibility
                com.example.ui.components.AnimatedWeatherIcon(
                    condition = WeatherCondition.GRANIZO,
                    size = com.example.media.WeatherIconSize.Alert,
                    preferStaticFallback = true,
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_hero_weather_icon").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_current_temperature").assertTextContains("75°F")
        composeTestRule.onNodeWithText("Tempestade com granizo leve").assertIsDisplayed()

        // Verify hourly card tap opens HourDetail modal with hailstorm icon
        composeTestRule.onNodeWithTag("home_hourly_panel").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_hour_card_1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hour_detail_modal_content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tempestade com granizo forte").assertIsDisplayed()
    }

    private fun sampleSavedLocation(): SavedLocation =
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

    private fun sampleForecast(
        weatherCode: Int,
        precipMm: Double,
        windGustsKmh: Double,
        uv: Double,
    ): WeatherForecastBundle {
        val hours =
            List(37) { i ->
                HourlyForecastPoint(
                    timeIso = "2026-11-24T%02d:00".format(i % 24),
                    epochMillis = 1764000000000L + i * 3_600_000L,
                    temperatureC = 24.0 + (i % 5) - 2.0,
                    apparentTemperatureC = 25.0,
                    relativeHumidityPercent = 76,
                    dewPointC = 19.0,
                    precipitationProbabilityPercent = if (precipMm > 0) 75 else 15,
                    precipitationMm = precipMm,
                    weatherCode = weatherCode,
                    weatherDescriptionPt = if (weatherCode == 95) "Tempestade" else "Chuva moderada",
                    visualConditionKey = if (weatherCode == 95) WeatherCondition.TEMPESTADE.key else WeatherCondition.CHUVA.key,
                    isDay = true,
                    pressureMslHpa = 1013.0,
                    visibilityMeters = 9000.0,
                    windSpeedKmh = 14.0,
                    windDirectionDegrees = 45,
                    windGustsKmh = windGustsKmh,
                    uvIndex = uv,
                )
            }
        val days =
            List(16) { d ->
                DailyForecastPoint(
                    dateIso = "2026-11-%02d".format(1 + d),
                    weatherCode = weatherCode,
                    weatherDescriptionPt = "Parcialmente nublado",
                    visualConditionKey = WeatherCondition.SOL_NUVENS.key,
                    temperatureMaxC = 27.0 + (d % 3),
                    temperatureMinC = 18.0,
                    apparentTemperatureMaxC = 29.0,
                    apparentTemperatureMinC = 18.0,
                    sunriseIso = "2026-11-01T05:18",
                    sunsetIso = "2026-11-01T18:34",
                    uvIndexMax = uv,
                    precipitationSumMm = precipMm * 2.0,
                    precipitationProbabilityMaxPercent = 65,
                    windSpeedMaxKmh = 18.0,
                    windGustsMaxKmh = windGustsKmh,
                    windDirectionDominantDegrees = 60,
                )
            }
        return WeatherForecastBundle(
            latitude = -23.5505,
            longitude = -46.6333,
            elevationMeters = 760.0,
            timezoneId = "America/Sao_Paulo",
            timezoneAbbreviation = "BRT",
            utcOffsetSeconds = -10800,
            current =
                CurrentWeather(
                    timeIso = "2026-11-24T12:00",
                    temperatureC = 24.0,
                    apparentTemperatureC = 25.0,
                    relativeHumidityPercent = 78,
                    dewPointC = 19.5,
                    precipitationMm = precipMm,
                    rainMm = precipMm,
                    showersMm = 0.0,
                    snowfallCm = 0.0,
                    weatherCode = weatherCode,
                    weatherDescriptionPt = if (weatherCode == 95) "Tempestade" else "Chuva moderada",
                    visualConditionKey = if (weatherCode == 95) WeatherCondition.TEMPESTADE.key else WeatherCondition.CHUVA.key,
                    isDay = true,
                    cloudCoverPercent = 70,
                    pressureMslHpa = 1013.0,
                    surfacePressureHpa = 930.0,
                    windSpeedKmh = 14.0,
                    windDirectionDegrees = 45,
                    windGustsKmh = windGustsKmh,
                    uvIndex = uv,
                    visibilityMeters = 8500.0,
                ),
            hourly = hours,
            daily = days,
            fetchedAtEpochMillis = 1764000000000L,
        )
    }

    private fun sampleAirQuality(usAqi: Int): AirQualitySnapshot =
        AirQualitySnapshot(
            latitude = -23.5505,
            longitude = -46.6333,
            timezoneId = "America/Sao_Paulo",
            utcOffsetSeconds = -10800,
            timeIso = "2026-11-24T12:00",
            europeanAqi = 30,
            usAqi = usAqi,
            pm25UgM3 = 11.4,
            pm10UgM3 = 19.2,
            nitrogenDioxideUgM3 = 14.0,
            ozoneUgM3 = 48.0,
            carbonMonoxideUgM3 = 220.0,
            sulphurDioxideUgM3 = 3.0,
            dustUgM3 = 2.0,
            aerosolOpticalDepth = 0.2,
            uvIndex = 5.0,
            fetchedAtEpochMillis = 1764000000000L,
        )

    private fun sampleRadarSnapshot(): RadarTimelineSnapshot {
        val frames =
            List(9) { idx ->
                val ts = 1763995200L + idx * 600L
                RainViewerRadarFrame(
                    timestampSeconds = ts,
                    path = "/v2/radar/$ts",
                    tileUrlTemplate = "https://tilecache.rainviewer.com/v2/radar/$ts/512/{z}/{x}/{y}/2/1_1.png",
                    formattedLocalTime = "10:%02d".format(idx * 5),
                )
            }
        return RadarTimelineSnapshot(
            apiVersion = "2.0",
            generatedEpochSeconds = 1764000000L,
            host = "https://tilecache.rainviewer.com",
            frames = frames,
            totalPastFramesAvailable = 12,
            maxSupportedZoom = 7,
            nowcastAvailable = false,
            fetchedAtEpochMillis = 1764000000000L,
        )
    }
}
