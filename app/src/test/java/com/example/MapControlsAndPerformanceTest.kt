package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil.size.Dimension
import coil.size.Precision
import com.example.data.ClimaDataContainer
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.CachePolicy
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.RainViewerRadarFrame
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
import com.example.data.remote.OpenMeteoHourlyDto
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerFrameDto
import com.example.data.remote.RainViewerMapper
import com.example.data.remote.RainViewerMapsResponseDto
import com.example.data.remote.RainViewerRadarSectionDto
import com.example.data.repository.AirQualityRepository
import com.example.data.repository.RadarRepository
import com.example.data.repository.WeatherRepository
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapLibreSection
import com.example.maps.CityMapUiState
import com.example.maps.LocationComponentSyncResult
import com.example.maps.LocationLookupResult
import com.example.maps.LocationPermissionCoordinator
import com.example.maps.LocationPermissionState
import com.example.maps.LocationPrecisionLevel
import com.example.maps.MapLibreLocationGateway
import com.example.maps.MapViewLifecycleController
import com.example.maps.OpenStreetMapTileConfig
import com.example.maps.RadarBaseStyle
import com.example.maps.RadarLayerPoolState
import com.example.maps.syncMapLibreLocationComponent
import com.example.media.AnimatedWeatherWebp
import com.example.media.WeatherIconSize
import com.example.ui.components.buildWeatherWebpRequest
import com.example.ui.components.shouldPauseBackgroundMotionForTab
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Etapa 3G automated verification suite (18 mandatory tests) covering:
 * - Cities map zoom & My Location controls without side effects or fake coordinates
 * - LocationComponent idempotency & revocation handling
 * - MapViewLifecycleController duplicate-event prevention
 * - Pooled RainViewer source/layer reuse & in-place visibility toggling
 * - Background-dispatcher offloading for Room/Moshi/Mappers/Alerts & parallel cache hydration
 * - Refresh deduplication & failure recovery
 * - Tab-scoped background motion pause & bounded WebP request sizing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapControlsAndPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // 1. Zoom + incrementa zoom dentro dos limites sem efeitos colaterais
    @Test
    fun `test_01_zoom_in_updates_zoom_only_without_location_style_or_radar_side_effects`() {
        val initialCenter = CityMapCoordinate(-23.5505, -46.6333)
        var latestState =
            CityMapUiState(
                cameraCenter = initialCenter,
                zoom = 10.0,
                permissionState = LocationPermissionState.DENIED,
            )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSection(
                        initialState = latestState,
                        isOnline = true,
                        onStateChanged = { latestState = it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val initialMoveVersion = latestState.cameraMoveVersion
        composeTestRule.onNodeWithTag("city_map_zoom_in").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(11.0, latestState.zoom, 0.001)
        assertEquals(initialCenter, latestState.cameraCenter)
        assertEquals(initialMoveVersion + 1, latestState.cameraMoveVersion)
        assertNull(latestState.currentUserLocation)
        assertFalse(latestState.isLocatingUser)
        assertEquals(LocationPermissionState.DENIED, latestState.permissionState)
    }

    // 2. Zoom − decrementa zoom dentro dos limites sem efeitos colaterais
    @Test
    fun `test_02_zoom_out_updates_zoom_only_without_location_style_or_radar_side_effects`() {
        val initialCenter = CityMapCoordinate(-22.9068, -43.1729)
        var latestState =
            CityMapUiState(
                cameraCenter = initialCenter,
                zoom = 12.0,
                permissionState = LocationPermissionState.NOT_REQUESTED,
            )

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSection(
                        initialState = latestState,
                        isOnline = true,
                        onStateChanged = { latestState = it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val initialMoveVersion = latestState.cameraMoveVersion
        composeTestRule.onNodeWithTag("city_map_zoom_out").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(11.0, latestState.zoom, 0.001)
        assertEquals(initialCenter, latestState.cameraCenter)
        assertEquals(initialMoveVersion + 1, latestState.cameraMoveVersion)
        assertNull(latestState.currentUserLocation)
        assertFalse(latestState.isLocatingUser)
    }

    // 3. Limites mínimo e máximo de zoom sem crash nem incremento redundante de câmera
    @Test
    fun `test_03_zoom_respects_min_and_max_bounds_without_churn`() {
        val maxState = CityMapUiState(zoom = OpenStreetMapTileConfig.MAX_ZOOM)
        val afterZoomIn = maxState.zoomIn()
        assertEquals(OpenStreetMapTileConfig.MAX_ZOOM, afterZoomIn.zoom, 0.001)
        assertEquals(maxState.cameraMoveVersion, afterZoomIn.cameraMoveVersion)

        val minState = CityMapUiState(zoom = OpenStreetMapTileConfig.MIN_ZOOM)
        val afterZoomOut = minState.zoomOut()
        assertEquals(OpenStreetMapTileConfig.MIN_ZOOM, afterZoomOut.zoom, 0.001)
        assertEquals(minState.cameraMoveVersion, afterZoomOut.cameraMoveVersion)

        val clampedUp = CityMapUiState(zoom = OpenStreetMapTileConfig.MAX_ZOOM - 0.5).zoomIn(step = 2.0)
        assertEquals(OpenStreetMapTileConfig.MAX_ZOOM, clampedUp.zoom, 0.001)
        assertEquals(1, clampedUp.cameraMoveVersion)
    }

    // 4. Botão Minha localização sem permissão solicita permissão ou direciona às configurações
    @Test
    fun `test_04_my_location_without_permission_never_invents_coordinates_and_handles_temporary_vs_permanent_denial`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        var launchCount = 0
        var lastRequestedPerms: Array<String>? = null
        var openSettingsCount = 0
        var permanentlyDenied by mutableStateOf(false)
        var latestState = CityMapUiState(permissionState = LocationPermissionState.NOT_REQUESTED)

        val fakeRegistryOwner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry =
                    object : ActivityResultRegistry() {
                        @Suppress("UNCHECKED_CAST")
                        override fun <I, O> onLaunch(
                            requestCode: Int,
                            contract: ActivityResultContract<I, O>,
                            input: I,
                            options: ActivityOptionsCompat?,
                        ) {
                            launchCount++
                            lastRequestedPerms = input as? Array<String>
                            dispatchResult(
                                requestCode,
                                mapOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION to false,
                                    Manifest.permission.ACCESS_COARSE_LOCATION to false,
                                ),
                            )
                        }
                    }
            }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeRegistryOwner) {
                Clima3DTheme(darkTheme = true) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        CityMapLibreSection(
                            initialState = latestState,
                            isOnline = true,
                            isPermanentlyDenied = permanentlyDenied,
                            onOpenAppSettings = { openSettingsCount++ },
                            onStateChanged = { latestState = it },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Temporary denial: tapping My Location triggers official runtime permission prompt
        composeTestRule.onNodeWithTag("city_map_my_location").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, launchCount)
        assertEquals(LocationPermissionCoordinator.REQUIRED_PERMISSIONS.toList(), lastRequestedPerms?.toList())
        assertNull(latestState.currentUserLocation)
        assertEquals(LocationPermissionState.DENIED, latestState.permissionState)
        assertFalse(latestState.isLocatingUser)

        // Permanent denial: tapping My Location never calls dead launch() and surfaces "Abrir ajustes"
        permanentlyDenied = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_my_location").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, launchCount)
        assertNull(latestState.currentUserLocation)
        composeTestRule.onNodeWithTag("city_map_open_settings_button").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, openSettingsCount)
    }

    // 5. Botão Minha localização com permissão precisa e coordenada válida centraliza e atualiza marcador
    @Test
    fun `test_05_my_location_with_precise_permission_centers_map_and_sets_precise_status`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setLocationEnabled(true)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        Shadows.shadowOf(lm).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = -22.9068
                longitude = -43.1729
                time = System.currentTimeMillis()
            },
        )

        val coord = LocationPermissionCoordinator.fallbackSystemLastLocation(app, LocationPrecisionLevel.PRECISE)
        assertNotNull(coord)
        val settled =
            CityMapUiState().withLocatingUser(true).withCurrentLocation(
                coordinate = coord!!,
                label = "Minha localização",
                precision = LocationPrecisionLevel.PRECISE,
            )

        assertEquals(-22.9068, settled.cameraCenter.latitude, 0.0001)
        assertEquals(-43.1729, settled.cameraCenter.longitude, 0.0001)
        assertEquals(coord, settled.currentUserLocation)
        assertEquals(LocationPrecisionLevel.PRECISE, settled.locationPrecision)
        assertFalse(settled.isLocatingUser)

        var renderedState = settled
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSection(
                        initialState = renderedState,
                        isOnline = true,
                        onStateChanged = { renderedState = it },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_location_status").performScrollTo().assertTextContains("Localização atual ativa", substring = true)
    }

    // 6. Botão Minha localização com permissão aproximada centraliza usando precisão aproximada
    @Test
    fun `test_06_my_location_with_approximate_permission_centers_map_and_uses_balanced_mode`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setLocationEnabled(true)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        Shadows.shadowOf(lm).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = -19.9167
                longitude = -43.9345
                time = System.currentTimeMillis()
            },
        )

        val snapshot = LocationPermissionCoordinator.inspect(app, initialPromptShown = true)
        assertEquals(LocationPrecisionLevel.APPROXIMATE, snapshot.precisionLevel)

        val fakeGateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, fix = null)
        var deliveredCoord: CityMapCoordinate? = null
        val syncResult = syncMapLibreLocationComponent(app, fakeGateway) { deliveredCoord = it }

        assertEquals(LocationComponentSyncResult.ACTIVATED_WITH_FIX, syncResult)
        assertEquals(false, fakeGateway.lastUseHighAccuracy)
        assertEquals(CityMapCoordinate(-19.9167, -43.9345), deliveredCoord)

        val approxState =
            CityMapUiState().withCurrentLocation(
                coordinate = deliveredCoord!!,
                precision = LocationPrecisionLevel.APPROXIMATE,
            )
        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSection(initialState = approxState, isOnline = true)
                }
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("city_map_location_status").performScrollTo().assertTextContains("Localização aproximada ativa", substring = true)
    }

    // 7. Minha localização com lastKnownLocation ausente trata erro sem travar nem inventar coordenada
    @Test
    fun `test_07_my_location_with_missing_last_known_location_settles_without_fake_coordinates`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setLocationEnabled(true)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)

        val fallback = LocationPermissionCoordinator.fallbackSystemLastLocation(app, LocationPrecisionLevel.PRECISE)
        assertNull(fallback)

        val fakeGateway =
            FakeMapLibreLocationGateway(
                isStyleFullyLoaded = true,
                fix = null,
                queryException = IllegalStateException("Last location unavailable"),
            )
        var delivered: CityMapCoordinate? = null
        val result = syncMapLibreLocationComponent(app, fakeGateway) { delivered = it }
        assertEquals(LocationComponentSyncResult.ACTIVATED_AWAITING_FIX, result)
        assertNull(delivered)
    }

    // 8. Timeout na obtenção da localização atual não deixa spinner travado nem bloqueia o mapa
    @Test
    fun `test_08_my_location_bounded_timeout_clears_loading_and_keeps_map_interactive`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setLocationEnabled(true)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        Shadows.shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)

        var lookupResult: LocationLookupResult? = null
        var callbackCount = 0
        LocationPermissionCoordinator.fetchRealLocation(app, timeoutMs = 150L) { res ->
            callbackCount++
            lookupResult = res
        }

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(300L, TimeUnit.MILLISECONDS)

        assertEquals(1, callbackCount)
        assertTrue(lookupResult is LocationLookupResult.UnavailableTemporarily)

        val settled =
            CityMapUiState()
                .withLocatingUser(true)
                .withLocationNotice((lookupResult as LocationLookupResult.UnavailableTemporarily).reason)
        assertFalse(settled.isLocatingUser)
        assertNull(settled.currentUserLocation)
        assertNotNull(settled.locationStatusNotice)

        // Zoom remains responsive after timeout notice
        val zoomed = settled.zoomIn()
        assertEquals(11.0, zoomed.zoom, 0.001)
    }

    // 9. GPS/localização do sistema desativado orienta o usuário sem exibir ConnectionError do mapa
    @Test
    fun `test_09_my_location_with_disabled_location_service_shows_notice_without_connection_error`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(lm).setLocationEnabled(false)

        var result: LocationLookupResult? = null
        LocationPermissionCoordinator.fetchRealLocation(app) { result = it }
        assertEquals(LocationLookupResult.LocationServiceDisabled, result)

        val stateWithNotice =
            CityMapUiState(
                permissionState = LocationPermissionState.GRANTED,
                locationPrecision = LocationPrecisionLevel.PRECISE,
            ).withLocationNotice("Ative o serviço de localização do aparelho para centralizar no mapa.")

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CityMapLibreSection(initialState = stateWithNotice, isOnline = true)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("city_map_location_status").performScrollTo().assertTextContains("desativada", substring = true)
        assertEquals(0, composeTestRule.onAllNodesWithTag("city_map_status_offline").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithTag("city_map_status_error").fetchSemanticsNodes().size)
    }

    // 10. Coordenada real fora do Brasil não é substituída por coordenada fixa brasileira
    @Test
    fun `test_10_my_location_accepts_non_brazil_worldwide_coordinates`() {
        val mountainView = CityMapCoordinate(37.4220, -122.0841)
        val tokyo = CityMapCoordinate(35.6762, 139.6503)

        assertTrue(LocationPermissionCoordinator.isValidCoordinate(mountainView.latitude, mountainView.longitude))
        assertTrue(LocationPermissionCoordinator.isValidCoordinate(tokyo.latitude, tokyo.longitude))
        assertFalse(LocationPermissionCoordinator.isValidCoordinate(Double.NaN, 0.0))
        assertFalse(LocationPermissionCoordinator.isValidCoordinate(95.0, 0.0))

        val state =
            CityMapUiState(cameraCenter = CityMapCoordinate(-23.5505, -46.6333))
                .withCurrentLocation(mountainView, precision = LocationPrecisionLevel.PRECISE)

        assertEquals(37.4220, state.cameraCenter.latitude, 0.0001)
        assertEquals(-122.0841, state.cameraCenter.longitude, 0.0001)
        assertEquals(mountainView, state.currentUserLocation)
        assertEquals(mountainView, state.selectedMarker.coordinate)
    }

    // 11. Idempotência de LocationComponent quando estilo e precisão não mudam
    @Test
    fun `test_11_sync_location_component_is_idempotent_when_style_and_precision_unchanged`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val gateway =
            FakeMapLibreLocationGateway(
                isStyleFullyLoaded = true,
                fix = CityMapCoordinate(-23.5505, -46.6333),
            )

        val first = syncMapLibreLocationComponent(app, gateway)
        assertEquals(LocationComponentSyncResult.ACTIVATED_WITH_FIX, first)
        assertEquals(1, gateway.activateCalls)
        assertEquals(1, gateway.applyModesCalls)
        assertEquals(1, gateway.queryCalls)

        // Second sync with identical active style + high-accuracy config short-circuits cleanly
        val second = syncMapLibreLocationComponent(app, gateway)
        assertEquals(LocationComponentSyncResult.ALREADY_ACTIVE, second)
        assertEquals(1, gateway.activateCalls)
        assertEquals(1, gateway.applyModesCalls)
        assertEquals(1, gateway.queryCalls)
    }

    // 12. Revogação desativa LocationComponent e alteração de precisão/estilo reconfigura apenas uma vez
    @Test
    fun `test_12_sync_location_component_handles_revocation_and_precision_change`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val gateway = FakeMapLibreLocationGateway(isStyleFullyLoaded = true, fix = CityMapCoordinate(-23.55, -46.63))
        assertEquals(LocationComponentSyncResult.ACTIVATED_WITH_FIX, syncMapLibreLocationComponent(app, gateway))

        // Downgrade from PRECISE to APPROXIMATE -> config changed -> applies modes without re-activating
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val downgraded = syncMapLibreLocationComponent(app, gateway)
        assertEquals(LocationComponentSyncResult.ACTIVATED_WITH_FIX, downgraded)
        assertEquals(1, gateway.activateCalls)
        assertEquals(2, gateway.applyModesCalls)
        assertEquals(false, gateway.lastUseHighAccuracy)

        // Full revocation -> disables active LocationComponent
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val revoked = syncMapLibreLocationComponent(app, gateway)
        assertEquals(LocationComponentSyncResult.DISABLED_AFTER_REVOCATION, revoked)
        assertEquals(1, gateway.disableCalls)
        assertFalse(gateway.isEnabled)
    }

    // 13. MapViewLifecycleController evita eventos duplicados em trocas rápidas de aba
    @Test
    fun `test_13_map_lifecycle_controller_prevents_duplicate_events_across_rapid_tab_switches`() {
        val calls = mutableListOf<String>()
        val controller =
            MapViewLifecycleController(
                onCreate = { calls += "onCreate" },
                onStart = { calls += "onStart" },
                onResume = { calls += "onResume" },
                onPause = { calls += "onPause" },
                onStop = { calls += "onStop" },
                onDestroy = { calls += "onDestroy" },
                onLowMemory = { calls += "onLowMemory" },
            )

        controller.syncToLifecycleState(Lifecycle.State.RESUMED)
        // Replayed lifecycle events from LifecycleEventObserver must not re-fire onStart/onResume
        controller.onLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller.onLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("onCreate", "onStart", "onResume"), calls)

        // Rapid pause/resume + double destroy
        controller.onLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        controller.onLifecycleEvent(Lifecycle.Event.ON_STOP)
        controller.onLifecycleEvent(Lifecycle.Event.ON_START)
        controller.onLifecycleEvent(Lifecycle.Event.ON_RESUME)
        controller.destroy()
        controller.destroy()
        controller.dispatchLowMemory()

        assertEquals(
            listOf(
                "onCreate",
                "onStart",
                "onResume",
                "onPause",
                "onStop",
                "onStart",
                "onResume",
                "onPause",
                "onStop",
                "onDestroy",
            ),
            calls,
        )
    }

    // 14. Pool de camadas do Radar cria fontes uma vez por timeline e só alterna visibility durante animação
    @Test
    fun `test_14_radar_layer_pool_attaches_sources_once_and_toggles_visibility_on_ticks`() {
        val pool = RadarLayerPoolState()
        val baseTs = 1795530000L
        val frames =
            List(9) { idx ->
                val ts = baseTs + idx * 600L
                RainViewerRadarFrame(
                    timestampSeconds = ts,
                    path = "/v2/radar/$ts",
                    tileUrlTemplate = RainViewerMapper.buildTileUrlTemplate(
                        host = "https://tilecache.rainviewer.com",
                        path = "/v2/radar/$ts",
                    ),
                    formattedLocalTime = "12:0$idx",
                )
            }

        val addedSources = mutableListOf<String>()
        val removedLayers = mutableListOf<String>()
        val visibilityMap = mutableMapOf<String, Boolean>()

        // Initial timeline attachment
        val firstDiff =
            pool.syncPool(
                frames = frames,
                activeFrame = frames[0],
                layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                maxRadarZoom = 12,
                addSourceAndLayer = { _, sourceId, layerId, _, isVisible ->
                    addedSources += sourceId
                    visibilityMap[layerId] = isVisible
                },
                setLayerVisibility = { layerId, isVisible -> visibilityMap[layerId] = isVisible },
                removeSourceAndLayer = { _, layerId -> removedLayers += layerId },
            )

        assertEquals(9, firstDiff.addedCount)
        assertEquals(0, firstDiff.removedCount)
        assertEquals(9, pool.attachedCount)
        assertEquals(1, visibilityMap.values.count { it })

        // Simulate 650ms frame advances across the rest of the timeline -> zero source/layer adds or removes
        for (idx in 1 until frames.size) {
            val tickDiff =
                pool.syncPool(
                    frames = frames,
                    activeFrame = frames[idx],
                    layerStyle = RadarBaseStyle.PRECIPITATION_MAP,
                    maxRadarZoom = 12,
                    addSourceAndLayer = { _, sourceId, _, _, _ -> addedSources += sourceId },
                    setLayerVisibility = { layerId, isVisible -> visibilityMap[layerId] = isVisible },
                    removeSourceAndLayer = { _, layerId -> removedLayers += layerId },
                )
            assertEquals(0, tickDiff.addedCount)
            assertEquals(0, tickDiff.removedCount)
            assertEquals(2, tickDiff.visibilityUpdates)
            assertEquals(1, visibilityMap.values.count { it })
            val activeKey = RadarLayerPoolState.buildPoolKey(frames[idx], 12)
            assertEquals(true, visibilityMap[pool.layerIdFor(activeKey)])
        }

        // Switching to Satellite clears pooled RainViewer layers cleanly
        val clearDiff =
            pool.syncPool(
                frames = frames,
                activeFrame = frames.last(),
                layerStyle = RadarBaseStyle.SATELLITE,
                maxRadarZoom = 12,
                addSourceAndLayer = { _, _, _, _, _ -> },
                setLayerVisibility = { _, _ -> },
                removeSourceAndLayer = { _, layerId -> removedLayers += layerId },
            )
        assertEquals(9, clearDiff.removedCount)
        assertEquals(0, pool.attachedCount)
        assertEquals(9, removedLayers.size)
    }

    // 15. Repositórios e ViewModel executam I/O, JSON/mapping/alertas fora da thread principal e hidratam em paralelo
    @Test
    fun `test_15_repositories_and_viewmodel_run_json_mapping_alerts_off_main_and_hydrate_concurrently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "etapa3g_perf_${UUID.randomUUID()}.preferences_pb")
        val prefsRepo =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { prefsFile },
                ),
            )

        val ioThreads = CopyOnWriteArrayList<String>()
        val cpuThreads = CopyOnWriteArrayList<String>()
        val trackingIoDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    Dispatchers.IO.dispatch(context) {
                        ioThreads += Thread.currentThread().name
                        block.run()
                    }
                }
            }
        val trackingCpuDispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                    Dispatchers.Default.dispatch(context) {
                        cpuThreads += Thread.currentThread().name
                        block.run()
                    }
                }
            }

        try {
            val moshi = NetworkClientFactory.createMoshi()
            val connectivity =
                object : ConnectivityObserver {
                    private val flow = MutableStateFlow(true)
                    override fun isOnline(): Boolean = true
                    override fun observeOnlineStatus(): Flow<Boolean> = flow
                }

            val weatherRepo =
                WeatherRepository(
                    forecastApi = fakeForecastApi(stormAlert = true),
                    cacheDao = db.weatherCacheDao(),
                    connectivityObserver = connectivity,
                    moshi = moshi,
                    ioDispatcher = trackingIoDispatcher,
                    cpuDispatcher = trackingCpuDispatcher,
                )
            val airRepo =
                AirQualityRepository(
                    airQualityApi = fakeAirQualityApi(),
                    cacheDao = db.weatherCacheDao(),
                    connectivityObserver = connectivity,
                    moshi = moshi,
                    ioDispatcher = trackingIoDispatcher,
                    cpuDispatcher = trackingCpuDispatcher,
                )
            val radarRepo =
                RadarRepository(
                    rainViewerApi = fakeRainViewerApi(listOf(1795533600L, 1795534200L)),
                    cacheDao = db.weatherCacheDao(),
                    connectivityObserver = connectivity,
                    moshi = moshi,
                    ioDispatcher = trackingIoDispatcher,
                    cpuDispatcher = trackingCpuDispatcher,
                )

            val mainThreadName = Thread.currentThread().name

            // Verify all three repositories use background IO/CPU dispatchers off the caller/Main thread
            assertTrue(weatherRepo.getForecast(-23.55, -46.63) is WeatherResult.Success)
            assertTrue(airRepo.getAirQuality(-23.55, -46.63) is WeatherResult.Success)
            assertTrue(radarRepo.getRadarTimeline("America/Sao_Paulo") is WeatherResult.Success)

            assertTrue("Expected DAO work on ioDispatcher", ioThreads.isNotEmpty())
            assertTrue("Expected JSON/mapping work on cpuDispatcher", cpuThreads.isNotEmpty())
            assertFalse(cpuThreads.any { it == mainThreadName })

            // Verify ViewModel alert evaluation uses cpuDispatcher and saved location hydration populates concurrently
            val container =
                ClimaDataContainer(
                    database = db,
                    preferencesRepository = prefsRepo,
                    connectivityObserver = connectivity,
                    moshi = moshi,
                    okHttpClient = NetworkClientFactory.createOkHttpClient(),
                    forecastApi = fakeForecastApi(stormAlert = true),
                    airQualityApi = fakeAirQualityApi(),
                    geocodingApi = fakeGeocodingApi(),
                    rainViewerApi = fakeRainViewerApi(listOf(1795533600L, 1795534200L)),
                )
            val sp = container.cityLocationRepository.saveCityFromSearch(sampleSearchResult("São Paulo", -23.5505, -46.6333), setAsPrimary = true)
            val rj = container.cityLocationRepository.saveCityFromSearch(sampleSearchResult("Rio de Janeiro", -22.9068, -43.1729, 3451190L))
            // Pre-seed cache for RJ as well
            container.weatherRepository.getForecast(rj.latitude, rj.longitude, CachePolicy.NetworkFirst)
            container.weatherRepository.getForecast(sp.latitude, sp.longitude, CachePolicy.NetworkFirst)

            val vmCpuThreads = CopyOnWriteArrayList<String>()
            val vmTrackingCpu =
                object : CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                        Dispatchers.Default.dispatch(context) {
                            vmCpuThreads += Thread.currentThread().name
                            block.run()
                        }
                    }
                }
            val vm = ClimaMainViewModel(container = container, autoBootstrapOnInit = false, cpuDispatcher = vmTrackingCpu)

            withTimeout(5_000L) {
                while (vm.uiState.value.savedLocationSummaries.size < 2) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(20L)
                }
            }
            assertEquals(2, vm.uiState.value.savedLocationSummaries.size)

            vm.onPullRefreshHome()
            withTimeout(5_000L) {
                while (vm.uiState.value.isRefreshing || vm.uiState.value.forecast == null) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(20L)
                }
            }
            assertTrue("ViewModel alert evaluation must dispatch to cpuDispatcher", vmCpuThreads.isNotEmpty())
            assertTrue(vm.uiState.value.alerts.isNotEmpty())
        } finally {
            db.close()
        }
    }

    // 16. Atualização duplicada simultânea é suprimida e isRefreshing volta a false mesmo em falha
    @Test
    fun `test_16_pull_to_refresh_and_resume_suppress_duplicates_and_reset_isRefreshing_on_error`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, Clima3DDatabase::class.java).allowMainThreadQueries().build()
        val prefsFile = File(context.cacheDir, "etapa3g_dedup_${UUID.randomUUID()}.preferences_pb")
        val prefsRepo =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { prefsFile },
                ),
            )
        try {
            val forecastCalls = AtomicInteger(0)
            var shouldThrow = false
            val slowForecastApi =
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
                        forecastCalls.incrementAndGet()
                        delay(180L)
                        if (shouldThrow) throw IOException("Simulated network drop")
                        return sampleForecastDto(latitude, longitude, stormAlert = false)
                    }
                }
            val onlineFlow = MutableStateFlow(true)
            val connectivity =
                object : ConnectivityObserver {
                    override fun isOnline(): Boolean = true
                    override fun observeOnlineStatus(): Flow<Boolean> = onlineFlow
                }
            val moshi = NetworkClientFactory.createMoshi()
            val container =
                ClimaDataContainer(
                    database = db,
                    preferencesRepository = prefsRepo,
                    connectivityObserver = connectivity,
                    moshi = moshi,
                    okHttpClient = NetworkClientFactory.createOkHttpClient(),
                    forecastApi = slowForecastApi,
                    airQualityApi = fakeAirQualityApi(),
                    geocodingApi = fakeGeocodingApi(),
                    rainViewerApi = fakeRainViewerApi(listOf(1795534200L)),
                )
            val sp = container.cityLocationRepository.saveCityFromSearch(sampleSearchResult("São Paulo", -23.5505, -46.6333), setAsPrimary = true)
            container.preferencesRepository.setSelectedLocationId(sp.id)

            val vm = ClimaMainViewModel(container = container, autoBootstrapOnInit = false)
            withTimeout(5_000L) {
                while (vm.uiState.value.activeLocation?.id != sp.id || !vm.uiState.value.preferencesLoaded) {
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
                    delay(10L)
                }
            }

            // Fire Home pull, Radar pull, and App Resume concurrently -> only 1 in-flight network refresh runs
            vm.onPullRefreshHome()
            vm.onPullRefreshRadar()
            vm.onAppResumeRefresh()
            assertTrue(vm.uiState.value.isRefreshing)

            withTimeout(5_000L) {
                while (vm.uiState.value.isRefreshing) {
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
                    delay(10L)
                }
            }
            assertEquals("Concurrent pull/resume triggers must coalesce into a single fetch", 1, forecastCalls.get())

            // Cities pull-to-refresh coalescing
            vm.onPullRefreshCities()
            vm.onPullRefreshCities()
            assertTrue(vm.uiState.value.isRefreshingCities)
            withTimeout(5_000L) {
                while (vm.uiState.value.isRefreshingCities) {
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
                    delay(10L)
                }
            }
            assertEquals(2, forecastCalls.get())

            // Failure resets isRefreshing cleanly and keeps UI responsive
            shouldThrow = true
            vm.onPullRefreshHome()
            withTimeout(5_000L) {
                while (vm.uiState.value.isRefreshing) {
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(50L, TimeUnit.MILLISECONDS)
                    delay(10L)
                }
            }
            assertFalse(vm.uiState.value.isRefreshing)
            assertNotNull(vm.uiState.value.forecast) // preserved from prior cache
        } finally {
            db.close()
        }
    }

    // 17. Vídeo/efeitos de fundo pausam nas abas Radar e Cidades mantendo as abas climáticas ativas
    @Test
    fun `test_17_background_video_and_rain_pause_on_map_tabs_and_resume_on_weather_tabs`() {
        assertFalse(shouldPauseBackgroundMotionForTab(ClimaTab.HOME))
        assertTrue(shouldPauseBackgroundMotionForTab(ClimaTab.RADAR))
        assertTrue(shouldPauseBackgroundMotionForTab(ClimaTab.CITIES))
        assertFalse(shouldPauseBackgroundMotionForTab(ClimaTab.SETTINGS))
        assertFalse(shouldPauseBackgroundMotionForTab(ClimaTab.ALERTS))
    }

    // 18. Ícones WebP animados usam tamanho alvo limitado em pixels coerente com cada slot
    @Test
    fun `test_18_animated_weather_webp_request_uses_bounded_pixel_target_size`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val heroReq = buildWeatherWebpRequest(context, AnimatedWeatherWebp.TEMPESTADE_FORTE.drawableRes, 396)
        val heroSize = runBlocking { heroReq.sizeResolver.size() }
        assertEquals(Dimension.Pixels(396), heroSize.width)
        assertEquals(Dimension.Pixels(396), heroSize.height)
        assertEquals(Precision.INEXACT, heroReq.precision)

        val hourlyReq = buildWeatherWebpRequest(context, AnimatedWeatherWebp.CHUVA_MODERADA.drawableRes, 114)
        val hourlySize = runBlocking { hourlyReq.sizeResolver.size() }
        assertEquals(Dimension.Pixels(114), hourlySize.width)
        assertEquals(Dimension.Pixels(114), hourlySize.height)

        // Ensure hero and hourly target dimensions reflect WeatherIconSize hierarchy and clamp invalid <=0 px
        assertTrue(WeatherIconSize.Hero > WeatherIconSize.Hourly)
        val clampedReq = buildWeatherWebpRequest(context, AnimatedWeatherWebp.SOL.drawableRes, 0)
        val clampedSize = runBlocking { clampedReq.sizeResolver.size() }
        assertEquals(Dimension.Pixels(1), clampedSize.width)
    }

    private class FakeMapLibreLocationGateway(
        override val isStyleFullyLoaded: Boolean,
        private val fix: CityMapCoordinate? = null,
        private val queryException: Throwable? = null,
    ) : MapLibreLocationGateway {
        override var isActivated: Boolean = false
        override var isEnabled: Boolean = false
        private var appliedAccuracy: Boolean? = null
        var activateCalls = 0
        var applyModesCalls = 0
        var disableCalls = 0
        var queryCalls = 0
        var lastUseHighAccuracy: Boolean? = null

        override fun matchesConfiguration(useHighAccuracy: Boolean): Boolean =
            isActivated && isEnabled && appliedAccuracy == useHighAccuracy

        override fun activate(useHighAccuracy: Boolean) {
            activateCalls++
            lastUseHighAccuracy = useHighAccuracy
            isActivated = true
        }

        override fun applyModes(useHighAccuracy: Boolean) {
            applyModesCalls++
            lastUseHighAccuracy = useHighAccuracy
            appliedAccuracy = useHighAccuracy
            isEnabled = true
        }

        override fun disableIfActivated() {
            disableCalls++
            appliedAccuracy = null
            isEnabled = false
        }

        override fun queryLastKnownCoordinate(): CityMapCoordinate? {
            queryCalls++
            queryException?.let { throw it }
            return fix
        }
    }

    private fun sampleSearchResult(
        name: String,
        lat: Double,
        lon: Double,
        id: Long = 3448439L,
    ) = CitySearchResult(
        providerId = id,
        name = name,
        admin1 = name,
        admin2 = null,
        country = "Brasil",
        countryCode = "BR",
        latitude = lat,
        longitude = lon,
        elevationMeters = 760.0,
        timezoneId = "America/Sao_Paulo",
        population = 1000000,
    )

    private fun fakeForecastApi(stormAlert: Boolean) =
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
            ): OpenMeteoForecastResponseDto = sampleForecastDto(latitude, longitude, stormAlert)
        }

    private fun sampleForecastDto(
        latitude: Double,
        longitude: Double,
        stormAlert: Boolean,
    ) = OpenMeteoForecastResponseDto(
        latitude = latitude,
        longitude = longitude,
        elevation = 760.0,
        utcOffsetSeconds = -10800,
        timezone = "America/Sao_Paulo",
        timezoneAbbreviation = "BRT",
        current = OpenMeteoCurrentDto(
            time = "2026-11-24T12:00",
            temperature2m = 27.0,
            relativeHumidity2m = 74,
            apparentTemperature = 29.0,
            isDay = 1,
            precipitation = if (stormAlert) 12.0 else 0.0,
            rain = if (stormAlert) 12.0 else 0.0,
            showers = 0.0,
            snowfall = 0.0,
            weatherCode = if (stormAlert) 95 else 2,
            cloudCover = if (stormAlert) 95 else 40,
            pressureMsl = 1011.0,
            surfacePressure = 928.0,
            windSpeed10m = if (stormAlert) 48.0 else 12.0,
            windDirection10m = 180,
            windGusts10m = if (stormAlert) 75.0 else 18.0,
        ),
        hourly = OpenMeteoHourlyDto(
            time = List(24) { idx -> "2026-11-24T%02d:00".format(idx) },
            temperature2m = List(24) { 26.0 },
            relativeHumidity2m = List(24) { 70 },
            dewPoint2m = List(24) { 19.0 },
            apparentTemperature = List(24) { 28.0 },
            precipitationProbability = List(24) { if (stormAlert) 90 else 15 },
            precipitation = List(24) { if (stormAlert) 8.0 else 0.0 },
            weatherCode = List(24) { if (stormAlert) 95 else 2 },
            pressureMsl = List(24) { 1011.0 },
            visibility = List(24) { 10000.0 },
            windSpeed10m = List(24) { if (stormAlert) 42.0 else 12.0 },
            windDirection10m = List(24) { 180 },
            windGusts10m = List(24) { if (stormAlert) 70.0 else 20.0 },
            uvIndex = List(24) { 6.0 },
            isDay = List(24) { 1 },
        ),
        daily = OpenMeteoDailyDto(
            time = List(16) { d -> "2026-11-%02d".format(1 + d) },
            weatherCode = List(16) { if (stormAlert) 95 else 2 },
            temperature2mMax = List(16) { 30.0 },
            temperature2mMin = List(16) { 20.0 },
            apparentTemperatureMax = List(16) { 31.0 },
            apparentTemperatureMin = List(16) { 20.0 },
            sunrise = List(16) { "2026-11-24T05:18" },
            sunset = List(16) { "2026-11-24T18:34" },
            uvIndexMax = List(16) { 7.0 },
            precipitationSum = List(16) { if (stormAlert) 35.0 else 0.0 },
            precipitationProbabilityMax = List(16) { if (stormAlert) 90 else 15 },
            windSpeed10mMax = List(16) { if (stormAlert) 50.0 else 14.0 },
            windGusts10mMax = List(16) { if (stormAlert) 75.0 else 22.0 },
            windDirection10mDominant = List(16) { 180 },
        ),
    )

    private fun fakeAirQualityApi() =
        object : OpenMeteoAirQualityApi {
            override suspend fun getAirQuality(
                latitude: Double,
                longitude: Double,
                current: String,
                timezone: String,
            ): OpenMeteoAirQualityResponseDto = OpenMeteoAirQualityResponseDto(
                latitude = latitude,
                longitude = longitude,
                utcOffsetSeconds = -10800,
                timezone = "America/Sao_Paulo",
                current = OpenMeteoAirQualityCurrentDto(
                    time = "2026-11-24T12:00",
                    europeanAqi = 28,
                    usAqi = 34,
                    pm25 = 9.0,
                    pm10 = 15.0,
                    nitrogenDioxide = 12.0,
                    ozone = 42.0,
                    carbonMonoxide = 190.0,
                    sulphurDioxide = 2.0,
                    dust = 1.0,
                    aerosolOpticalDepth = 0.15,
                    uvIndex = 6.0,
                ),
            )
        }

    private fun fakeGeocodingApi() =
        object : OpenMeteoGeocodingApi {
            override suspend fun searchCities(
                name: String,
                count: Int,
                language: String,
                format: String,
            ): OpenMeteoGeocodingResponseDto = OpenMeteoGeocodingResponseDto(results = emptyList())
        }

    private fun fakeRainViewerApi(timestamps: List<Long>) =
        object : RainViewerApi {
            override suspend fun getWeatherMaps(): RainViewerMapsResponseDto =
                RainViewerMapsResponseDto(
                    version = "2.0",
                    generated = timestamps.lastOrNull() ?: 1795534200L,
                    host = "https://tilecache.rainviewer.com",
                    radar = RainViewerRadarSectionDto(
                        past = timestamps.map { ts -> RainViewerFrameDto(time = ts, path = "/v2/radar/$ts") },
                        nowcast = null,
                    ),
                )
        }
}
