package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.diagnostics.VideoSurfaceOutputState
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapLibreViewImpl
import com.example.maps.CityMapUiState
import com.example.maps.LocationComponentSyncResult
import com.example.maps.MapLibreControllerPort
import com.example.maps.MapLocationPickerScreenImpl
import com.example.maps.MapSessionPhase
import com.example.maps.MapStylePort
import com.example.maps.MapStyleSpecRequest
import com.example.maps.MapSurfaceCallbacks
import com.example.maps.MapViewLifecycleController
import com.example.maps.MapViewSessionHandle
import com.example.maps.MapViewSurfacePort
import com.example.media.WeatherCondition
import com.example.ui.components.ExoSurfaceSnapshot
import com.example.ui.components.WeatherBackgroundLayerImpl
import com.example.ui.components.WeatherVideoCodecErrorDiagnostic
import com.example.ui.components.WeatherVideoFrameToken
import com.example.ui.components.WeatherVideoPlayerBridge
import com.example.ui.components.WeatherVideoSurfaceBridge
import com.example.ui.state.ClimaActiveModal
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Etapa 3P.2 — Verification suite covering:
 * 1. MapLibre lifecycle safety: `cancelTransitions` strictly before `onDestroy`, zero post-destroy calls,
 *    `onDispose -> onRelease`, `onRelease -> onDispose`, repeated teardown, late camera/style/async
 *    callbacks, old-vs-new session isolation, Back during camera animation, Confirm/Cancel with pending ops,
 *    and Activity `ON_DESTROY` while map is open.
 * 2. Search auto-collapse UX: keyboard + search box visible during typing, tapping suggestion collapses box,
 *    clears focus, hides keyboard natively, hides suggestion list, keeps map open with preserved selection,
 *    reopens without clearing pending selection, updates on second suggestion, supports manual tap/pan/zoom/GPS
 *    while collapsed.
 * 3. ExoPlayer surface coordination & static wallpaper fallback without player duplication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MapLibreLifecycleAndSearchCollapseTest {

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

    // --- 1. MapLibre Lifecycle Tests ---

    @Test
    fun `lifecycle_01_cancelTransitions_runs_before_onDestroy_and_zero_calls_after_onDestroy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val port = StrictNativeGuardSurfacePort(context)
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

        lateinit var activeCtrl: StrictNativeControllerPort
        port.getMapAsync { activeCtrl = it as StrictNativeControllerPort }
        val detachListeners = port.attachListeners(noopSurfaceCallbacks())
        val detachWindow = port.attachWindowObserver { _, _ -> }

        val sessionHandle =
            MapViewSessionHandle(
                screenName = "Cidades",
                sessionId = "strict-order-test",
                surfacePort = port,
                controller = controller,
                onDetachListeners = detachListeners,
                onDetachWindowObserver = detachWindow,
                onCancelTransitions = {
                    activeCtrl.cancelTransitions()
                    activeCtrl.clearMapListeners()
                    activeCtrl.invalidate()
                },
            )

        sessionHandle.pauseAndStopBeforeDetach("onDispose")
        assertEquals(MapSessionPhase.CLOSING, sessionHandle.currentPhase)
        assertEquals(1, activeCtrl.cancelTransitionsCount)
        assertEquals(0, port.destroyCount)

        // Attempt extra cancel after closing started -> ignored
        sessionHandle.pauseAndStopBeforeDetach("after_closing")
        activeCtrl.cancelTransitions()
        activeCtrl.animateCamera(-23.5, -46.6, 12.0, 250)
        assertEquals(1, activeCtrl.cancelTransitionsCount)
        assertEquals(0, activeCtrl.animateCameraCount)

        // Destroy once after detach
        sessionHandle.destroyOnceAfterDetach("release")
        assertEquals(MapSessionPhase.DESTROYED, sessionHandle.currentPhase)
        assertEquals(1, port.destroyCount)
        assertEquals(0, port.postDestroyViolationCount)
        assertEquals(0, activeCtrl.postDestroyViolationCount)

        // Strict sequence: cancelTransitions -> clearMapListeners -> onPause -> onStop -> onDestroy
        val idxCancel = port.eventLog.indexOf("cancelTransitions")
        val idxClear = port.eventLog.indexOf("clearMapListeners")
        val idxPause = port.eventLog.indexOf("onPause")
        val idxStop = port.eventLog.indexOf("onStop")
        val idxDestroy = port.eventLog.indexOf("onDestroy")
        assertTrue(idxCancel in 0 until idxClear)
        assertTrue(idxClear < idxPause)
        assertTrue(idxPause < idxStop)
        assertTrue(idxStop < idxDestroy)
    }

    @Test
    fun `lifecycle_02_onRelease_followed_by_onDispose_and_repeated_teardown_destroy_once_and_never_call_post_destroy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val port = StrictNativeGuardSurfacePort(context)
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
        lateinit var activeCtrl: StrictNativeControllerPort
        port.getMapAsync { activeCtrl = it as StrictNativeControllerPort }

        val sessionHandle =
            MapViewSessionHandle(
                screenName = "Cidades",
                sessionId = "release-first-test",
                surfacePort = port,
                controller = controller,
                onDetachListeners = port.attachListeners(noopSurfaceCallbacks()),
                onDetachWindowObserver = port.attachWindowObserver { _, _ -> },
                onCancelTransitions = {
                    activeCtrl.cancelTransitions()
                    activeCtrl.clearMapListeners()
                    activeCtrl.invalidate()
                },
            )

        // Reverse order: onRelease arrives BEFORE onDispose, followed by repeated teardown calls
        sessionHandle.destroyOnceAfterDetach("release")
        sessionHandle.pauseAndStopBeforeDetach("onDispose")
        sessionHandle.pauseAndStopBeforeDetach("onDispose_again")
        sessionHandle.destroyOnceAfterDetach("release_again")

        assertEquals(1, activeCtrl.cancelTransitionsCount)
        assertEquals(1, port.destroyCount)
        assertEquals(0, port.postDestroyViolationCount)
        assertEquals(0, activeCtrl.postDestroyViolationCount)
        assertTrue(port.eventLog.indexOf("cancelTransitions") < port.eventLog.indexOf("onDestroy"))
    }

    @Test
    fun `lifecycle_03_activity_onDestroy_while_map_open_cancels_before_destroy_and_ignores_subsequent_dispose_release`(): Unit = runBlocking {
        val lifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)
        val ports = mutableListOf<StrictNativeGuardSurfacePort>()
        var showMap by mutableStateOf(true)

        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                Clima3DTheme(darkTheme = true) {
                    if (showMap) {
                        CityMapLibreViewImpl(
                            state = CityMapUiState(),
                            onMapTap = {},
                            onCameraGestureSettled = { _, _ -> },
                            surfacePortFactory = { ctx -> StrictNativeGuardSurfacePort(ctx).also { ports += it } },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        val port = ports.single()
        val ctrl = port.latestController!!

        // Simulate Activity finishing while MapLocationPicker is still open
        composeTestRule.runOnUiThread {
            lifecycleOwner.handleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleEvent(Lifecycle.Event.ON_DESTROY)
        }
        composeTestRule.waitForIdle()

        // Compose subsequently removes/disposes the view after Activity ON_DESTROY
        showMap = false
        composeTestRule.waitForIdle()

        assertEquals(1, port.destroyCount)
        assertEquals(1, ctrl.cancelTransitionsCount)
        assertEquals(0, port.postDestroyViolationCount)
        assertEquals(0, ctrl.postDestroyViolationCount)
        assertTrue(port.eventLog.indexOf("cancelTransitions") < port.eventLog.indexOf("onDestroy"))
    }

    @Test
    fun `lifecycle_04_late_camera_callbacks_and_old_session_isolation_across_recreation`() {
        val createdPorts = mutableListOf<StrictNativeGuardSurfacePort>()
        val settledPoints = mutableListOf<CityMapCoordinate>()
        val tappedPoints = mutableListOf<CityMapCoordinate>()
        var mapState by mutableStateOf(CityMapUiState())
        var recreationTrigger by mutableIntStateOf(0)

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                androidx.compose.runtime.key(recreationTrigger) {
                    CityMapLibreViewImpl(
                        state = mapState,
                        onMapTap = { tappedPoints += it },
                        onCameraGestureSettled = { coord, _ -> settledPoints += coord },
                        surfacePortFactory = { ctx ->
                            // Session 1 defers getMapAsync until after teardown if desired; test synchronous ready first
                            StrictNativeGuardSurfacePort(ctx, autoDeliverMapAsync = true).also { createdPorts += it }
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, createdPorts.size)
        val oldPort = createdPorts[0]
        val oldCtrl = oldPort.latestController!!

        // Start a camera animation in old session and immediately replace with new session
        mapState = mapState.selectMarkerAt(CityMapCoordinate(-22.97, -43.18), "Rio", moveCameraToMarker = true)
        recreationTrigger = 1
        composeTestRule.waitForIdle()

        assertEquals(2, createdPorts.size)
        val newPort = createdPorts[1]
        val newCtrl = newPort.latestController!!

        // Old session destroyed exactly once with cancel before destroy, zero violations
        assertEquals(1, oldPort.destroyCount)
        assertEquals(1, oldCtrl.cancelTransitionsCount)
        assertEquals(0, oldPort.postDestroyViolationCount)
        assertEquals(0, oldCtrl.postDestroyViolationCount)

        // New session remains intact and active!
        assertEquals(0, newPort.destroyCount)
        assertEquals(0, newCtrl.cancelTransitionsCount)

        // Fire late callbacks on old controller/port -> MUST be completely ignored
        composeTestRule.runOnUiThread {
            oldCtrl.simulateLateCameraIdle(CityMapCoordinate(10.0, 20.0), 8.0)
            oldCtrl.simulateLateMapTap(CityMapCoordinate(10.0, 20.0))
            oldPort.latestCallbacks?.onFirstFrameRendered()
        }
        composeTestRule.waitForIdle()
        assertTrue("Late callbacks from destroyed session must be ignored", settledPoints.isEmpty())
        assertTrue("Late map tap from destroyed session must be ignored", tappedPoints.isEmpty())

        // New session still receives valid taps normally
        composeTestRule.runOnUiThread {
            newCtrl.simulateLateMapTap(CityMapCoordinate(-19.91, -43.93))
        }
        composeTestRule.waitForIdle()
        assertEquals(1, tappedPoints.size)
        assertEquals(-19.91, tappedPoints[0].latitude, 0.0001)
    }

    @Test
    fun `lifecycle_05_back_confirm_and_cancel_during_camera_animation_never_call_maplibre_after_destroy`(): Unit = runBlocking {
        val harness = createHarness()
        try {
            harness.seedSaoPauloPrimary()
            val ports = mutableListOf<StrictNativeGuardSurfacePort>()

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
                            onReopenSearch = harness.vm::reopenMapPickerSearch,
                            onMapStateChange = harness.vm::updateCityMapState,
                            onCustomPointNameChange = harness.vm::updateCustomPointNameInput,
                            onConfirmSelection = harness.vm::confirmMapLocationSelection,
                            onCancel = harness.vm::closeMapLocationPickerModal,
                            onOpenAppSettings = {},
                            onRetryReload = {},
                            surfacePortFactory = { ctx -> StrictNativeGuardSurfacePort(ctx).also { ports += it } },
                        )
                    }
                }
            }

            // Test 1: Back during pending camera animation
            composeTestRule.runOnUiThread { harness.vm.openMapLocationPickerModal() }
            composeTestRule.waitForIdle()
            assertEquals(1, ports.size)
            composeTestRule.runOnUiThread {
                harness.vm.updateCityMapState(
                    harness.vm.uiState.value.cityMapState.selectMarkerAt(
                        CityMapCoordinate(-22.9711, -43.1825),
                        "Copacabana",
                        moveCameraToMarker = true,
                    ),
                )
                harness.vm.navigateBackFromSecondary()
            }
            composeTestRule.waitForIdle()
            assertEquals(1, ports[0].destroyCount)
            assertEquals(0, ports[0].postDestroyViolationCount)
            assertEquals(0, ports[0].latestController?.postDestroyViolationCount)

            // Test 2: Cancel with pending animation
            composeTestRule.runOnUiThread {
                harness.vm.openMapLocationPickerModal()
                harness.vm.updateCityMapState(harness.vm.uiState.value.cityMapState.zoomIn())
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_picker_cancel_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(1, ports[1].destroyCount)
            assertEquals(0, ports[1].postDestroyViolationCount)
            assertEquals(0, ports[1].latestController?.postDestroyViolationCount)

            // Test 3: Confirm with pending animation
            composeTestRule.runOnUiThread {
                harness.vm.openMapLocationPickerModal()
                harness.vm.updateCityMapState(
                    harness.vm.uiState.value.cityMapState.selectMarkerAt(
                        CityMapCoordinate(-19.9167, -43.9345),
                        "Belo Horizonte",
                        moveCameraToMarker = true,
                    ),
                )
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_picker_confirm_button").performScrollTo().performClick()
            harness.awaitCondition { harness.vm.uiState.value.activeModal == ClimaActiveModal.None }
            composeTestRule.waitForIdle()
            assertEquals(1, ports[2].destroyCount)
            assertEquals(0, ports[2].postDestroyViolationCount)
            assertEquals(0, ports[2].latestController?.postDestroyViolationCount)
        } finally {
            harness.close()
        }
    }

    // --- 2. Auto-Collapse Search & Keyboard UX Tests ---

    @Test
    fun `search_01_typing_collapses_on_suggestion_hides_keyboard_reopens_preserving_point_and_allows_collapsed_map_interaction`(): Unit = runBlocking {
        val harness = createHarness()
        val fakeKeyboard = RecordingSoftwareKeyboardController()
        val baseFocusRef = arrayOfNulls<FocusManager>(1)
        var focusClearedCount = 0

        try {
            harness.seedSaoPauloPrimary()
            val ports = mutableListOf<StrictNativeGuardSurfacePort>()

            composeTestRule.setContent {
                val state by harness.vm.uiState.collectAsStateWithLifecycle()
                val realFocusManager = LocalFocusManager.current
                val recordingFocusManager =
                    object : FocusManager {
                        override fun clearFocus(force: Boolean) {
                            focusClearedCount++
                            realFocusManager.clearFocus(force)
                        }

                        override fun moveFocus(focusDirection: FocusDirection): Boolean =
                            realFocusManager.moveFocus(focusDirection)
                    }.also { baseFocusRef[0] = it }

                CompositionLocalProvider(
                    LocalSoftwareKeyboardController provides fakeKeyboard,
                    LocalFocusManager provides recordingFocusManager,
                ) {
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
                                onReopenSearch = harness.vm::reopenMapPickerSearch,
                                onMapStateChange = harness.vm::updateCityMapState,
                                onCustomPointNameChange = harness.vm::updateCustomPointNameInput,
                                onConfirmSelection = harness.vm::confirmMapLocationSelection,
                                onCancel = harness.vm::closeMapLocationPickerModal,
                                onOpenAppSettings = {},
                                onRetryReload = {},
                                surfacePortFactory = { ctx -> StrictNativeGuardSurfacePort(ctx).also { ports += it } },
                            )
                        }
                    }
                }
            }

            composeTestRule.runOnUiThread { harness.vm.openMapLocationPickerModal() }
            composeTestRule.waitForIdle()
            val initialPort = ports.single()
            assertEquals(1, initialPort.latestController?.uiSettingsConfiguredCount)

            // Search box open while typing
            composeTestRule.onNodeWithTag("map_picker_search_box").assertIsDisplayed()
            composeTestRule.onNodeWithTag("map_picker_search_input").performTextReplacement("Copacabana")
            composeTestRule.runOnUiThread { harness.vm.submitMapPickerSearch() }
            harness.awaitCondition { harness.vm.uiState.value.mapLocationPickerState.searchResults.isNotEmpty() }
            composeTestRule.waitForIdle()

            // Suggestions visible
            composeTestRule.onNodeWithTag("map_picker_search_results_list").assertIsDisplayed()
            val hideCallsBeforePick = fakeKeyboard.hideCalls
            val focusClearBeforePick = focusClearedCount

            // Tap suggestion -> auto-collapses search, clears focus, hides keyboard, hides suggestions, keeps map open
            composeTestRule.onNodeWithTag("map_picker_search_result_0").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            assertFalse(harness.vm.uiState.value.mapLocationPickerState.isSearchExpanded)
            assertEquals(ClimaActiveModal.MapLocationPicker, harness.vm.uiState.value.activeModal)
            assertEquals(0, composeTestRule.onAllNodesWithTag("map_picker_search_box").fetchSemanticsNodes().size)
            assertEquals(0, composeTestRule.onAllNodesWithTag("map_picker_search_results_list").fetchSemanticsNodes().size)
            composeTestRule.onNodeWithTag("map_picker_reopen_search_button").performScrollTo().assertIsDisplayed()
            assertTrue("Keyboard.hide() must be triggered on suggestion selection", fakeKeyboard.hideCalls > hideCallsBeforePick)
            assertTrue("FocusManager.clearFocus() must be triggered on suggestion selection", focusClearedCount > focusClearBeforePick)

            // Preserved name & coordinates, MapView NOT destroyed
            assertEquals(0, initialPort.destroyCount)
            assertEquals("Copacabana", harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName)
            composeTestRule.onNodeWithTag("map_picker_selected_name").performScrollTo().assertTextContains("Copacabana")
            composeTestRule.onNodeWithTag("map_picker_selected_coordinates").performScrollTo().assertTextContains("-22.9711", substring = true)

            // Collapsed search does not block zoom or map movement
            composeTestRule.onNodeWithTag("city_map_zoom_in").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertEquals(13.5, harness.vm.uiState.value.cityMapState.zoom, 0.001)
            assertFalse(harness.vm.uiState.value.mapLocationPickerState.isSearchExpanded)

            // Reopen search via discrete button -> search box opens AND pending selection ("Copacabana") is NOT cleared!
            val showCallsBeforeReopen = fakeKeyboard.showCalls
            composeTestRule.onNodeWithTag("map_picker_reopen_search_button").performScrollTo().performClick()
            composeTestRule.waitForIdle()
            assertTrue(harness.vm.uiState.value.mapLocationPickerState.isSearchExpanded)
            composeTestRule.onNodeWithTag("map_picker_search_box").performScrollTo().assertIsDisplayed()
            assertEquals("Copacabana", harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName)
            assertEquals(-22.9711, harness.vm.uiState.value.cityMapState.selectedMarker.coordinate.latitude, 0.0001)
            assertTrue("Reopening search should request keyboard show", fakeKeyboard.showCalls > showCallsBeforeReopen)

            // Search another locality -> selecting updates pending location and auto-collapses again
            composeTestRule.onNodeWithTag("map_picker_search_input").performScrollTo().performTextReplacement("Ipanema")
            composeTestRule.runOnUiThread { harness.vm.submitMapPickerSearch() }
            harness.awaitCondition {
                harness.vm.uiState.value.mapLocationPickerState.searchResults.any { it.name == "Ipanema" }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("map_picker_search_result_0").performScrollTo().performClick()
            composeTestRule.waitForIdle()

            assertFalse(harness.vm.uiState.value.mapLocationPickerState.isSearchExpanded)
            assertEquals("Ipanema", harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName)
            composeTestRule.onNodeWithTag("map_picker_selected_name").performScrollTo().assertTextContains("Ipanema")

            // Manual map tap while collapsed keeps search collapsed and replaces searched name with manual point
            composeTestRule.runOnUiThread {
                initialPort.latestController!!.simulateLateMapTap(CityMapCoordinate(-23.5489, -46.6388))
            }
            composeTestRule.waitForIdle()
            assertFalse(harness.vm.uiState.value.mapLocationPickerState.isSearchExpanded)
            assertNull(harness.vm.uiState.value.mapLocationPickerState.selectedSearchResult)
            assertTrue(harness.vm.uiState.value.mapLocationPickerState.selectedPlaceName.startsWith("Ponto "))
        } finally {
            harness.close()
        }
    }

    // --- 3. ExoPlayer + MapLibre Integration & Wallpaper Fallback ---

    @Test
    fun `integration_01_video_detaches_before_map_reattaches_after_destroy_and_preserves_same_player_and_wallpaper`() {
        val createdPlayers = mutableListOf<TrackingExoPlayerBridge>()
        val createdMapPorts = mutableListOf<StrictNativeGuardSurfacePort>()
        var isMapOpen by mutableStateOf(false)
        var effectiveVideoAlpha = 1f

        composeTestRule.setContent {
            Clima3DTheme(darkTheme = true) {
                WeatherBackgroundLayerImpl(
                    condition = WeatherCondition.SOL,
                    activeTab = ClimaTab.HOME,
                    animatedEnabled = true,
                    isMapLocationPickerOpen = isMapOpen,
                    playerBridgeFactory = { _ -> TrackingExoPlayerBridge().also { createdPlayers += it } },
                    surfaceBridgeFactory = { ctx -> TrackingVideoSurfaceBridge(ctx) },
                    onEffectiveVideoAlphaChanged = { effectiveVideoAlpha = it },
                )
                if (isMapOpen) {
                    CityMapLibreViewImpl(
                        state = CityMapUiState(),
                        onMapTap = {},
                        onCameraGestureSettled = { _, _ -> },
                        surfacePortFactory = { ctx -> StrictNativeGuardSurfacePort(ctx).also { createdMapPorts += it } },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(1, createdPlayers.size)
        val player = createdPlayers.single()
        val initialPlayerIdentity = player.identityHash
        composeTestRule.runOnUiThread { player.emitFirstFrame() }
        composeTestRule.waitForIdle()
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertTrue(effectiveVideoAlpha >= 0f)

        // Open map: video output must detach while static wallpaper stays mounted (video hidden at alpha 0f)
        isMapOpen = true
        composeTestRule.waitForIdle()
        assertEquals(1, createdMapPorts.size)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals(MapSurfaceSessionState.MAP_SURFACE_ACTIVE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertEquals(0f, effectiveVideoAlpha, 0.001f)
        assertEquals("Same ExoPlayer instance must be preserved", 1, createdPlayers.size)

        // Close map: MapView destroys strictly once, video reattaches ONLY after NO_ACTIVE_MAP_SURFACE
        isMapOpen = false
        composeTestRule.waitForIdle()
        assertEquals(1, createdMapPorts.single().destroyCount)
        assertEquals(0, createdMapPorts.single().postDestroyViolationCount)
        assertEquals(MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE, GraphicsSurfaceCoordinator.state.value.mapState)
        assertEquals(VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE, GraphicsSurfaceCoordinator.state.value.videoState)
        assertEquals("Reattached ExoPlayer must be exact same instance", initialPlayerIdentity, createdPlayers.single().identityHash)
        assertEquals(1, createdPlayers.size)
    }

    // --- Test Helpers ---

    private fun noopSurfaceCallbacks() =
        MapSurfaceCallbacks(
            onWillStartLoading = {},
            onDidFinishLoadingStyle = {},
            onDidFinishLoadingMap = {},
            onDidFailLoadingMap = {},
            onWillStartRenderingMap = {},
            onFirstFrameRendered = {},
            onDidFinishRenderingMap = {},
            onRenderError = {},
        )

    private class RecordingSoftwareKeyboardController : SoftwareKeyboardController {
        var showCalls = 0
        var hideCalls = 0

        override fun show() {
            showCalls++
        }

        override fun hide() {
            hideCalls++
        }
    }

    private class TestLifecycleOwner(initialState: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = initialState }
        override val lifecycle: Lifecycle
            get() = registry

        fun handleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private class StrictNativeControllerPort(
        private val eventLog: MutableList<String>,
        private val isSurfaceDestroyed: () -> Boolean,
    ) : MapLibreControllerPort {
        private var invalidated = false
        var cancelTransitionsCount = 0
        var animateCameraCount = 0
        var uiSettingsConfiguredCount = 0
        var postDestroyViolationCount = 0
        private var clickListener: ((CityMapCoordinate) -> Unit)? = null
        private var idleListener: ((CityMapCoordinate, Double) -> Unit)? = null

        private fun checkNotDestroyed(op: String): Boolean {
            if (isSurfaceDestroyed()) {
                postDestroyViolationCount++
                eventLog += "VIOLATION_AFTER_DESTROY:$op"
                return false
            }
            return !invalidated
        }

        override fun configureUiSettings() {
            if (!checkNotDestroyed("configureUiSettings")) return
            uiSettingsConfiguredCount++
            eventLog += "configureUiSettings"
        }

        override fun setInitialCamera(latitude: Double, longitude: Double, zoom: Double) {
            if (!checkNotDestroyed("setInitialCamera")) return
            eventLog += "setInitialCamera"
        }

        override fun animateCamera(latitude: Double, longitude: Double, zoom: Double, durationMs: Int) {
            if (!checkNotDestroyed("animateCamera")) return
            animateCameraCount++
            eventLog += "animateCamera"
        }

        override fun cancelTransitions() {
            if (!checkNotDestroyed("cancelTransitions")) return
            cancelTransitionsCount++
            eventLog += "cancelTransitions"
        }

        override fun setStyle(request: MapStyleSpecRequest, onLoaded: (MapStylePort) -> Unit) {
            if (!checkNotDestroyed("setStyle")) return
            eventLog += "setStyle"
            onLoaded(
                object : MapStylePort {
                    override val isFullyLoaded: Boolean = true
                    override fun updateGeoJsonSource(sourceId: String, geoJson: String) = Unit
                },
            )
        }

        override fun setOnMapClick(listener: (CityMapCoordinate) -> Unit) {
            if (!checkNotDestroyed("setOnMapClick")) return
            clickListener = listener
        }

        override fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit) {
            if (!checkNotDestroyed("setOnCameraIdle")) return
            idleListener = listener
        }

        override fun clearMapListeners() {
            if (!checkNotDestroyed("clearMapListeners")) return
            eventLog += "clearMapListeners"
            clickListener = null
            idleListener = null
        }

        override fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult {
            if (!checkNotDestroyed("syncLocationComponent")) return LocationComponentSyncResult.STYLE_NOT_READY
            return LocationComponentSyncResult.ALREADY_ACTIVE
        }

        override fun invalidate() {
            invalidated = true
        }

        fun simulateLateMapTap(coord: CityMapCoordinate) {
            clickListener?.invoke(coord)
        }

        fun simulateLateCameraIdle(coord: CityMapCoordinate, zoom: Double) {
            idleListener?.invoke(coord, zoom)
        }
    }

    private class StrictNativeGuardSurfacePort(
        context: Context,
        private val autoDeliverMapAsync: Boolean = true,
    ) : MapViewSurfacePort {
        override val androidView: View = View(context)
        override val viewIdentity: Int = System.identityHashCode(androidView)
        val eventLog = mutableListOf<String>()
        var destroyCount = 0
        var postDestroyViolationCount = 0
        var latestController: StrictNativeControllerPort? = null
        var latestCallbacks: MapSurfaceCallbacks? = null
        private var destroyed = false

        private fun checkAlive(op: String): Boolean {
            if (destroyed) {
                postDestroyViolationCount++
                eventLog += "SURFACE_VIOLATION_AFTER_DESTROY:$op"
                return false
            }
            return true
        }

        override fun onCreate() {
            if (!checkAlive("onCreate")) return
            eventLog += "onCreate"
        }

        override fun onStart() {
            if (!checkAlive("onStart")) return
            eventLog += "onStart"
        }

        override fun onResume() {
            if (!checkAlive("onResume")) return
            eventLog += "onResume"
        }

        override fun onPause() {
            if (!checkAlive("onPause")) return
            eventLog += "onPause"
        }

        override fun onStop() {
            if (!checkAlive("onStop")) return
            eventLog += "onStop"
        }

        override fun onDestroy() {
            destroyCount++
            destroyed = true
            eventLog += "onDestroy"
        }

        override fun onLowMemory() {
            checkAlive("onLowMemory")
        }

        override fun inspectSurfaceInfo(): String = "StrictSurface(320x300, destroyed=$destroyed)"

        override fun postToUi(action: () -> Unit) {
            if (!destroyed) action()
        }

        override fun attachWindowObserver(onAttachStateChanged: (Boolean, String) -> Unit): () -> Unit {
            onAttachStateChanged(true, inspectSurfaceInfo())
            return { eventLog += "detachWindowObserver" }
        }

        override fun attachListeners(callbacks: MapSurfaceCallbacks): () -> Unit {
            latestCallbacks = callbacks
            callbacks.onDidFinishLoadingStyle()
            callbacks.onFirstFrameRendered()
            callbacks.onDidFinishRenderingMap(true)
            return { eventLog += "detachListeners" }
        }

        override fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit) {
            if (!checkAlive("getMapAsync")) return
            val ctrl = StrictNativeControllerPort(eventLog) { destroyed }
            latestController = ctrl
            if (autoDeliverMapAsync) {
                onReady(ctrl)
            }
        }
    }

    private class TrackingExoPlayerBridge : WeatherVideoPlayerBridge {
        override val sessionId: String = "audit-exo-${System.identityHashCode(this)}"
        override val identityHash: Int = System.identityHashCode(this)
        private var released = false
        private var idle = true
        private var currentResId = 0
        private var firstFrameTokenListener: ((WeatherVideoFrameToken) -> Unit)? = null
        override val isReleased: Boolean get() = released
        override val isIdle: Boolean get() = idle
        override val decoderGeneration: Int get() = 1
        override val isDecoderAlive: Boolean get() = !released

        override fun setVideoResIdHint(resId: Int) {
            currentResId = resId
        }

        override fun setMediaItem(uri: android.net.Uri) {
            idle = false
        }

        override fun setVolume(volume: Float) = Unit
        override fun setRepeatMode(repeatMode: Int) = Unit
        override fun setPlayWhenReady(playWhenReady: Boolean) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun prepare() {
            idle = false
        }

        override fun stop() {
            idle = true
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun clearVideoSurface() = Unit
        override fun setFirstFrameListener(listener: (() -> Unit)?) = Unit
        override fun setFirstFrameTokenListener(listener: ((WeatherVideoFrameToken) -> Unit)?) {
            firstFrameTokenListener = listener
        }

        fun emitFirstFrame(resId: Int = currentResId) {
            firstFrameTokenListener?.invoke(
                WeatherVideoFrameToken(
                    sessionId = sessionId,
                    playerIdentity = identityHash,
                    videoResId = if (currentResId != 0) currentResId else resId,
                    decoderGeneration = 1,
                    surfaceEpoch = 1,
                    surfaceViewIdentity = 100,
                    decoderAlive = true,
                ),
            )
        }

        override fun attachToPlayerView(playerView: androidx.media3.ui.PlayerView) = Unit
        override fun isAttachedToPlayerView(playerView: androidx.media3.ui.PlayerView): Boolean = false
        override fun releaseOnce(): Boolean {
            val first = !released
            released = true
            return first
        }
    }

    private class TrackingVideoSurfaceBridge(context: Context) : WeatherVideoSurfaceBridge {
        override val view: View = View(context)
        override val viewIdentity: Int = System.identityHashCode(view)
        private var attachedPlayer: WeatherVideoPlayerBridge? = null

        override fun attachPlayer(player: WeatherVideoPlayerBridge) {
            attachedPlayer = player
        }

        override fun isPlayerAttached(player: WeatherVideoPlayerBridge): Boolean = attachedPlayer === player
        override fun detachPlayer() {
            attachedPlayer = null
        }

        override fun inspectSurfaceSnapshot(): ExoSurfaceSnapshot =
            ExoSurfaceSnapshot(surfaceViewClass = "TextureView", textureAvailable = true, hasSurfaceTexture = true)

        override fun attachWindowObserver(onAttachStateChanged: (Boolean, ExoSurfaceSnapshot) -> Unit): () -> Unit {
            onAttachStateChanged(true, inspectSurfaceSnapshot())
            return {}
        }
    }

    private class AuditHarness(
        val db: Clima3DDatabase,
        val prefsRepo: UserPreferencesRepository,
        val container: ClimaDataContainer,
        val vm: ClimaMainViewModel,
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
        val prefsFile = File(context.cacheDir, "audit_3p2_prefs_${UUID.randomUUID()}.preferences_pb")
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
                ): OpenMeteoForecastResponseDto =
                    OpenMeteoForecastResponseDto(
                        latitude = latitude,
                        longitude = longitude,
                        elevation = 700.0,
                        utcOffsetSeconds = -10800,
                        timezone = "America/Sao_Paulo",
                        timezoneAbbreviation = "BRT",
                        current =
                            OpenMeteoCurrentDto(
                                time = "2026-11-25T12:00",
                                temperature2m = 25.0,
                                relativeHumidity2m = 65,
                                apparentTemperature = 25.5,
                                isDay = 1,
                                precipitation = 0.0,
                                rain = 0.0,
                                snowfall = 0.0,
                                showers = 0.0,
                                weatherCode = 1,
                                cloudCover = 15,
                                pressureMsl = 1015.0,
                                surfacePressure = 930.0,
                                windSpeed10m = 10.0,
                                windDirection10m = 120,
                                windGusts10m = 18.0,
                            ),
                        hourly =
                            OpenMeteoHourlyDto(
                                time = List(24) { idx -> "2026-11-25T%02d:00".format(idx) },
                                temperature2m = List(24) { 25.0 },
                                relativeHumidity2m = List(24) { 65 },
                                dewPoint2m = List(24) { 16.0 },
                                apparentTemperature = List(24) { 25.5 },
                                precipitationProbability = List(24) { 10 },
                                precipitation = List(24) { 0.0 },
                                weatherCode = List(24) { 1 },
                                pressureMsl = List(24) { 1015.0 },
                                visibility = List(24) { 10000.0 },
                                windSpeed10m = List(24) { 10.0 },
                                windDirection10m = List(24) { 120 },
                                windGusts10m = List(24) { 18.0 },
                                uvIndex = List(24) { 5.0 },
                                isDay = List(24) { 1 },
                            ),
                        daily =
                            OpenMeteoDailyDto(
                                time = List(7) { d -> "2026-11-%02d".format(20 + d) },
                                weatherCode = List(7) { 1 },
                                temperature2mMax = List(7) { 28.0 },
                                temperature2mMin = List(7) { 19.0 },
                                apparentTemperatureMax = List(7) { 28.5 },
                                apparentTemperatureMin = List(7) { 19.0 },
                                sunrise = List(7) { "2026-11-25T05:20" },
                                sunset = List(7) { "2026-11-25T18:30" },
                                uvIndexMax = List(7) { 6.0 },
                                precipitationSum = List(7) { 0.0 },
                                precipitationProbabilityMax = List(7) { 15 },
                                windSpeed10mMax = List(7) { 14.0 },
                                windGusts10mMax = List(7) { 22.0 },
                                windDirection10mDominant = List(7) { 120 },
                            ),
                    )
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
                                time = "2026-11-25T12:00",
                                europeanAqi = 20,
                                usAqi = 28,
                                pm25 = 6.0,
                                pm10 = 11.0,
                                nitrogenDioxide = 9.0,
                                ozone = 36.0,
                                carbonMonoxide = 170.0,
                                sulphurDioxide = 1.2,
                                dust = 1.0,
                                aerosolOpticalDepth = 0.1,
                                uvIndex = 5.0,
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
                                )
                            name.contains("Ipanema", ignoreCase = true) ->
                                listOf(
                                    OpenMeteoGeocodingResultDto(
                                        id = 3461001L,
                                        name = "Ipanema",
                                        latitude = -22.9836,
                                        longitude = -43.2044,
                                        countryCode = "BR",
                                        country = "Brasil",
                                        admin1 = "Rio de Janeiro",
                                        admin2 = "Rio de Janeiro",
                                        timezone = "America/Sao_Paulo",
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
            dataStoreScope = dataStoreScope,
        )
    }
}
