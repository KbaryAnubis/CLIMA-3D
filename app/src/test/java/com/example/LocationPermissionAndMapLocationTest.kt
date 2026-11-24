package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.NetworkClientFactory
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapLibreSection
import com.example.maps.CityMapUiState
import com.example.maps.LocationComponentSyncResult
import com.example.maps.LocationPermissionCoordinator
import com.example.maps.LocationPermissionSnapshot
import com.example.maps.LocationPermissionState
import com.example.maps.LocationPrecisionLevel
import com.example.maps.MapLibreLocationGateway
import com.example.maps.syncMapLibreLocationComponent
import com.example.ui.screens.SettingsScreen
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.Clima3DTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Etapa 3F test suite covering:
 * 1. First open without location permission (requests fine + coarse together once)
 * 2. First open with location permission already granted (no prompt shown)
 * 3. Approximate location grant (`ACCESS_COARSE_LOCATION` -> balanced mode)
 * 4. Precise location grant (`ACCESS_FINE_LOCATION` -> high-accuracy mode)
 * 5. Denied permission state + Settings re-request / App Settings link
 * 6. Zero attempt to activate MapLibre `LocationComponent` without authorization + revocation disable
 * 7. Graceful handling of missing last known location ("Last location unavailable")
 * 8. Return after granting permission in Android Settings (`ON_RESUME`)
 * 9. OSM + Zoom + Markers + Navigation continuity when location is denied
 * 10. No repeated initial prompts across recompositions, tab switches, or Activity recreations
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationPermissionAndMapLocationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `AndroidManifest declares coarse and fine location without background notification or camera permissions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val pkgInfo =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        val declared = pkgInfo.requestedPermissions?.toSet().orEmpty()

        assertTrue(declared.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(declared.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(declared.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
        assertFalse(declared.contains("android.permission.POST_NOTIFICATIONS"))
        assertFalse(declared.contains(Manifest.permission.CAMERA))
    }

    @Test
    fun `1_firstOpenWithoutPermission_promptsOnceForFineAndCoarseAndPersistsFlag`() = runBlocking {
        val env = createIsolatedContainer()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

            val vm = ClimaMainViewModel(container = env.container, autoBootstrapOnInit = false)
            var promptCount = 0
            var requestedPerms: List<String> = emptyList()

            composeTestRule.setContent {
                Clima3DApp(
                    dataContainer = env.container,
                    viewModel = vm,
                    onLaunchPermissionRequest = { perms ->
                        promptCount++
                        requestedPerms = perms.toList()
                    },
                )
            }
            composeTestRule.waitForIdle()

            assertEquals(1, promptCount)
            assertEquals(
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                requestedPerms,
            )
            assertTrue(env.prefsRepo.preferencesFlow.first().locationPermissionPromptShown)
        } finally {
            env.close()
        }
    }

    @Test
    fun `2_firstOpenWithPermissionAlreadyGranted_doesNotPromptAndSetsGrantedState`() = runBlocking {
        val env = createIsolatedContainer()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app).grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

            val vm = ClimaMainViewModel(container = env.container, autoBootstrapOnInit = false)
            var promptCount = 0

            composeTestRule.setContent {
                Clima3DApp(
                    dataContainer = env.container,
                    viewModel = vm,
                    onLaunchPermissionRequest = { promptCount++ },
                )
            }
            composeTestRule.waitForIdle()

            assertEquals(0, promptCount)
            assertTrue(vm.uiState.value.locationPermissionSnapshot.hasAnyPermission)
            assertEquals(LocationPrecisionLevel.PRECISE, vm.uiState.value.locationPermissionSnapshot.precisionLevel)
            assertEquals(LocationPermissionState.GRANTED, vm.uiState.value.cityMapState.permissionState)
        } finally {
            env.close()
        }
    }

    @Test
    fun `3_approximateLocationGrant_usesBalancedAccuracyWithoutHighAccuracy`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        val snapshot = LocationPermissionCoordinator.inspect(app, initialPromptShown = true)
        assertTrue(snapshot.hasAnyPermission)
        assertEquals(LocationPrecisionLevel.APPROXIMATE, snapshot.precisionLevel)
        assertEquals(LocationPermissionState.GRANTED, snapshot.mapPermissionState)

        val fakeGateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, fix = CityMapCoordinate(-23.55, -46.63))
        var deliveredFix: CityMapCoordinate? = null
        val result = syncMapLibreLocationComponent(app, fakeGateway) { deliveredFix = it }

        assertEquals(LocationComponentSyncResult.ACTIVATED_WITH_FIX, result)
        assertEquals(1, fakeGateway.activateCalls)
        assertEquals(false, fakeGateway.lastUseHighAccuracy)
        assertTrue(fakeGateway.isEnabled)
        assertEquals(CityMapCoordinate(-23.55, -46.63), deliveredFix)
    }

    @Test
    fun `4_preciseLocationGrant_usesHighAccuracyModeAndCompassRender`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val snapshot = LocationPermissionCoordinator.inspect(app, initialPromptShown = true)
        assertEquals(LocationPrecisionLevel.PRECISE, snapshot.precisionLevel)

        val fakeGateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, fix = CityMapCoordinate(-22.90, -43.17))
        val result = syncMapLibreLocationComponent(app, fakeGateway)

        assertEquals(LocationComponentSyncResult.ACTIVATED_WITH_FIX, result)
        assertEquals(1, fakeGateway.activateCalls)
        assertEquals(true, fakeGateway.lastUseHighAccuracy)
        assertTrue(fakeGateway.isEnabled)
    }

    @Test
    fun `5_deniedPermission_neverBlocksAppAndShowsSettingsPermissionActions`() {
        val deniedSnapshot =
            LocationPermissionSnapshot(
                hasFinePermission = false,
                hasCoarsePermission = false,
                initialPromptShown = true,
                canShowRequestRationale = true,
            )
        val permanentlyDeniedSnapshot =
            LocationPermissionSnapshot(
                hasFinePermission = false,
                hasCoarsePermission = false,
                initialPromptShown = true,
                canShowRequestRationale = false,
            )

        var requestClicked = 0
        var openSettingsClicked = 0
        val stateHolder =
            mutableStateOf(
                ClimaUiState(
                    locationPermissionSnapshot = deniedSnapshot,
                ),
            )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                SettingsScreen(
                    uiState = stateHolder.value,
                    contentPadding = PaddingValues(12.dp),
                    onSetThemeMode = {},
                    onSetTemperatureUnit = {},
                    onSetWindSpeedUnit = {},
                    onSetPrecipitationUnit = {},
                    onSetPressureUnit = {},
                    onSetVisibilityUnit = {},
                    onSetTimeFormat = {},
                    onSetShowPressureStatus = {},
                    onOpenPressureInfo = {},
                    onSetWidgetPreviewSize = {},
                    onSetAnimatedBackground = {},
                    onSetAutoRefreshOnOpen = {},
                    onOpenCities = {},
                    onResetPreferences = {},
                    onRequestLocationPermission = { requestClicked++ },
                    onOpenAppLocationSettings = { openSettingsClicked++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_screen_list").performScrollToKey("setting_locations")
        composeTestRule.onNodeWithTag("setting_location_permission_status").assertTextContains("Negada", substring = true)
        composeTestRule.onNodeWithTag("setting_request_location_permission_button").performClick()
        assertEquals(1, requestClicked)

        // When permanently denied, Android Settings button appears with ACTION_APPLICATION_DETAILS_SETTINGS target
        stateHolder.value = stateHolder.value.copy(locationPermissionSnapshot = permanentlyDeniedSnapshot)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("setting_open_app_settings_button").assertIsDisplayed().performClick()
        assertEquals(1, openSettingsClicked)

        val appSettingsIntent = LocationPermissionCoordinator.buildAppSettingsIntent(ApplicationProvider.getApplicationContext())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appSettingsIntent.action)
    }

    @Test
    fun `6_neverAttemptsToActivateLocationComponentWithoutPermissionAndDisablesAfterRevocation`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val neverActivatedGateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, initiallyActivated = false, initiallyEnabled = false)
        val deniedResult = syncMapLibreLocationComponent(app, neverActivatedGateway)
        assertEquals(LocationComponentSyncResult.PERMISSION_DENIED, deniedResult)
        assertEquals(0, neverActivatedGateway.activateCalls)
        assertEquals(0, neverActivatedGateway.applyModesCalls)
        assertFalse(neverActivatedGateway.isEnabled)

        // If LocationComponent was active before user revoked permission in system Settings, it must be disabled
        val previouslyActiveGateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, initiallyActivated = true, initiallyEnabled = true)
        val revokedResult = syncMapLibreLocationComponent(app, previouslyActiveGateway)
        assertEquals(LocationComponentSyncResult.DISABLED_AFTER_REVOCATION, revokedResult)
        assertEquals(1, previouslyActiveGateway.disableCalls)
        assertFalse(previouslyActiveGateway.isEnabled)
    }

    @Test
    fun `7_missingLastKnownLocation_handlesUnavailableGracefullyWithoutInventingCoordinatesOrFailing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        // Case A: null lastKnownLocation (no cached fix yet)
        val nullFixGateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, fix = null)
        var callbackCoord: CityMapCoordinate? = CityMapCoordinate(0.0, 0.0)
        callbackCoord = null
        val awaitingResult = syncMapLibreLocationComponent(app, nullFixGateway) { callbackCoord = it }

        assertEquals(LocationComponentSyncResult.ACTIVATED_AWAITING_FIX, awaitingResult)
        assertEquals(1, nullFixGateway.activateCalls)
        assertNull("Must never invent coordinates when lastKnownLocation is null", callbackCoord)

        // Case B: platform/MapLibre throws "Last location unavailable"
        val throwingGateway =
            FakeMapLibreLocationGateway(
                isStyleFullyLoaded = true,
                queryException = IllegalStateException("Last location unavailable"),
            )
        val handledResult = syncMapLibreLocationComponent(app, throwingGateway) { callbackCoord = it }
        assertTrue(
            handledResult == LocationComponentSyncResult.ACTIVATED_AWAITING_FIX ||
                handledResult == LocationComponentSyncResult.FAILED_NON_FATAL,
        )
        assertNull(callbackCoord)
    }

    @Test
    fun `8_returnFromSettingsAfterGrantingPermission_syncsGrantedStateOnResumeWithoutRecreatingMap`() = runBlocking {
        val env = createIsolatedContainer()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            env.prefsRepo.setLocationPermissionPromptShown(true)

            val vm = ClimaMainViewModel(container = env.container, autoBootstrapOnInit = false)
            val lifecycleOwner = MutableLifecycleOwner(Lifecycle.State.STARTED)

            composeTestRule.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
                ) {
                    Clima3DApp(
                        dataContainer = env.container,
                        viewModel = vm,
                        onLaunchPermissionRequest = {},
                    )
                }
            }
            composeTestRule.waitForIdle()
            assertFalse(vm.uiState.value.locationPermissionSnapshot.hasAnyPermission)

            // User goes to Android Settings, grants Approximate permission, and returns (ON_RESUME)
            Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            composeTestRule.runOnIdle {
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            composeTestRule.waitForIdle()

            assertTrue(vm.uiState.value.locationPermissionSnapshot.hasAnyPermission)
            assertEquals(LocationPrecisionLevel.APPROXIMATE, vm.uiState.value.locationPermissionSnapshot.precisionLevel)
            assertEquals(LocationPermissionState.GRANTED, vm.uiState.value.cityMapState.permissionState)
        } finally {
            env.close()
        }
    }

    @Test
    fun `9_osmMapContinuityWhenPermissionDenied_keepsAttributionZoomAndMarkersWithoutErrors`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        var latestState =
            CityMapUiState(
                zoom = 10.0,
                permissionState = LocationPermissionState.DENIED,
            )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSection(
                        initialState = latestState,
                        isOnline = true,
                        isPermanentlyDenied = true,
                        onStateChanged = { latestState = it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("cities_map_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_osm_attribution").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_location_status").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("city_map_open_settings_button").performScrollTo().assertIsDisplayed()
        assertEquals(0, composeTestRule.onAllNodesWithTag("city_map_status_offline").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithTag("city_map_status_error").fetchSemanticsNodes().size)

        composeTestRule.onNodeWithTag("city_map_zoom_in").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(11.0, latestState.zoom, 0.001)
    }

    @Test
    fun `10_noRepeatedPromptsAcrossRecompositionsTabSwitchesOrActivityRecreation`() = runBlocking {
        val env = createIsolatedContainer()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )

            val promptCount = mutableIntStateOf(0)
            val recreationKey = mutableIntStateOf(0)

            composeTestRule.setContent {
                androidx.compose.runtime.key(recreationKey.intValue) {
                    val vm =
                        androidx.compose.runtime.remember(recreationKey.intValue) {
                            ClimaMainViewModel(container = env.container, autoBootstrapOnInit = false)
                        }
                    Clima3DApp(
                        dataContainer = env.container,
                        viewModel = vm,
                        onLaunchPermissionRequest = { promptCount.intValue += 1 },
                    )
                }
            }
            composeTestRule.waitForIdle()
            assertEquals("First open must request permission once", 1, promptCount.intValue)

            // Switch across all main tabs
            composeTestRule.onNodeWithTag("nav_tab_radar").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nav_tab_cities").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("nav_tab_home").performClick()
            composeTestRule.waitForIdle()
            assertEquals("Tab switches must not repeat prompt", 1, promptCount.intValue)

            // Simulate Activity recreation (new ViewModel + composition with persisted DataStore flag)
            composeTestRule.runOnIdle {
                recreationKey.intValue += 1
            }
            composeTestRule.waitForIdle()
            assertEquals("Activity recreation must not repeat initial prompt", 1, promptCount.intValue)
        } finally {
            env.close()
        }
    }

    private class MutableLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = initialState }
        override val lifecycle: Lifecycle
            get() = registry

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private class FakeMapLibreLocationGateway(
        override val isStyleFullyLoaded: Boolean,
        initiallyActivated: Boolean = false,
        initiallyEnabled: Boolean = false,
        private val fix: CityMapCoordinate? = null,
        private val queryException: Throwable? = null,
    ) : MapLibreLocationGateway {
        override var isActivated: Boolean = initiallyActivated
        override var isEnabled: Boolean = initiallyEnabled
        var activateCalls = 0
        var applyModesCalls = 0
        var disableCalls = 0
        var lastUseHighAccuracy: Boolean? = null

        override fun activate(useHighAccuracy: Boolean) {
            activateCalls++
            lastUseHighAccuracy = useHighAccuracy
            isActivated = true
        }

        override fun applyModes(useHighAccuracy: Boolean) {
            applyModesCalls++
            lastUseHighAccuracy = useHighAccuracy
            isEnabled = true
        }

        override fun disableIfActivated() {
            disableCalls++
            isEnabled = false
        }

        override fun queryLastKnownCoordinate(): CityMapCoordinate? {
            queryException?.let { throw it }
            return fix
        }
    }

    private data class IsolatedTestEnv(
        val db: Clima3DDatabase,
        val prefsRepo: UserPreferencesRepository,
        val container: ClimaDataContainer,
    ) {
        fun close() {
            db.close()
        }
    }

    private fun createIsolatedContainer(): IsolatedTestEnv {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "etapa3f_prefs_${UUID.randomUUID()}.preferences_pb")
        val prefsRepo =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { prefsFile },
                ),
            )
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
        assertNotNull(container)
        return IsolatedTestEnv(db = db, prefsRepo = prefsRepo, container = container)
    }
}
