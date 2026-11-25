package com.example.maps

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.media.ClimaUiIcons
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.WeatherGlassSpecs
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Native Jetpack Compose section for the Cities tab backed by MapLibre Native Android SDK
 * (OpenGL ES + TextureView) and OpenStreetMap raster tiles (no WebView, no HTML, no Google Maps, no API key).
 *
 * Provides interactive map navigation, zoom controls, tap-to-place markers, saved markers,
 * OpenStreetMap attribution, runtime-permission-safe GPS support, and 8 explicit load/error/timeout states.
 */
@Composable
fun CityMapLibreSection(
    modifier: Modifier = Modifier,
    initialState: CityMapUiState = CityMapUiState(),
    isOnline: Boolean = true,
    isPermanentlyDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
    onRetryReload: () -> Unit = {},
    onStateChanged: ((CityMapUiState) -> Unit)? = null,
) {
    CityMapLibreSectionImpl(
        modifier = modifier,
        initialState = initialState,
        isOnline = isOnline,
        isPermanentlyDenied = isPermanentlyDenied,
        onOpenAppSettings = onOpenAppSettings,
        onRetryReload = onRetryReload,
        onStateChanged = onStateChanged,
    )
}

@Composable
internal fun CityMapLibreSectionImpl(
    modifier: Modifier = Modifier,
    initialState: CityMapUiState = CityMapUiState(),
    isOnline: Boolean = true,
    isPermanentlyDenied: Boolean = false,
    onOpenAppSettings: () -> Unit = {},
    onRetryReload: () -> Unit = {},
    onStateChanged: ((CityMapUiState) -> Unit)? = null,
    surfacePortFactory: ((Context) -> MapViewSurfacePort)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spec = remember { WeatherGlassSpecs.forTab(ClimaTab.CITIES) }
    var uiState by remember {
        val snapshot =
            LocationPermissionCoordinator.inspect(
                context = context,
                initialPromptShown = initialState.permissionState == LocationPermissionState.DENIED,
            )
        mutableStateOf(
            if (snapshot.hasAnyPermission) {
                initialState.withPermissionState(
                    state = LocationPermissionState.GRANTED,
                    precision = snapshot.precisionLevel,
                    clearCurrentLocationIfDenied = false,
                )
            } else {
                initialState.withPermissionState(
                    state = initialState.permissionState,
                    precision = LocationPrecisionLevel.NONE,
                )
            },
        )
    }

    fun updateState(next: CityMapUiState) {
        uiState = next
        onStateChanged?.invoke(next)
    }

    LaunchedEffect(initialState.savedMarkers) {
        if (uiState.savedMarkers != initialState.savedMarkers) {
            uiState = uiState.withSavedMarkers(initialState.savedMarkers)
        }
    }

    LaunchedEffect(
        initialState.permissionState,
        initialState.locationPrecision,
        initialState.locationStatusNotice,
    ) {
        if (
            uiState.permissionState != initialState.permissionState ||
                uiState.locationPrecision != initialState.locationPrecision ||
                uiState.locationStatusNotice != initialState.locationStatusNotice
        ) {
            uiState =
                uiState.withPermissionState(
                    state = initialState.permissionState,
                    precision = initialState.locationPrecision,
                    clearCurrentLocationIfDenied = initialState.permissionState != LocationPermissionState.GRANTED,
                    notice = initialState.locationStatusNotice,
                )
        }
    }

    LaunchedEffect(initialState.currentUserLocation) {
        val incoming = initialState.currentUserLocation
        if (incoming != null && uiState.currentUserLocation != incoming) {
            uiState =
                uiState.withCurrentLocation(
                    coordinate = incoming,
                    label = uiState.selectedMarker.label,
                    precision =
                        if (initialState.locationPrecision != LocationPrecisionLevel.NONE) {
                            initialState.locationPrecision
                        } else {
                            uiState.locationPrecision
                        },
                )
        }
    }

    // Re-evaluate permissions and GPS status when returning from System Settings without recreating MapView
    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val snapshot =
                        LocationPermissionCoordinator.inspect(
                            context = context,
                            initialPromptShown = uiState.permissionState == LocationPermissionState.DENIED,
                        )
                    val nextPermState =
                        if (snapshot.hasAnyPermission) {
                            LocationPermissionState.GRANTED
                        } else {
                            snapshot.mapPermissionState
                        }
                    val nextPrecision = snapshot.precisionLevel
                    val serviceNotice =
                        if (snapshot.hasAnyPermission && !snapshot.isLocationServiceEnabled) {
                            "Localização do aparelho desativada no sistema."
                        } else {
                            null
                        }
                    if (
                        nextPermState != uiState.permissionState ||
                            nextPrecision != uiState.locationPrecision ||
                            serviceNotice != uiState.locationStatusNotice
                    ) {
                        updateState(
                            uiState.withPermissionState(
                                state = nextPermState,
                                precision = nextPrecision,
                                clearCurrentLocationIfDenied = !snapshot.hasAnyPermission,
                                notice = serviceNotice,
                            ),
                        )
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentLocationLabel = stringResource(R.string.cities_map_my_location)
    val latestUiState by rememberUpdatedState(uiState)
    val latestPermanentlyDenied by rememberUpdatedState(isPermanentlyDenied)
    var localPermanentlyDenied by remember { mutableStateOf(false) }

    fun handleLocationLookup() {
        val currentState = latestUiState
        if (currentState.isLocatingUser) return
        updateState(currentState.withLocatingUser(true))
        LocationPermissionCoordinator.fetchRealLocation(context) { result ->
            val currentAfterLookup = latestUiState
            when (result) {
                is LocationLookupResult.Success -> {
                    updateState(
                        currentAfterLookup.withCurrentLocation(
                            coordinate = result.coordinate,
                            label = currentLocationLabel,
                            precision = result.precision,
                        ),
                    )
                }

                LocationLookupResult.PermissionDenied -> {
                    updateState(currentAfterLookup.withPermissionState(LocationPermissionState.DENIED))
                }

                LocationLookupResult.LocationServiceDisabled -> {
                    updateState(
                        currentAfterLookup.withLocationNotice(
                            "Ative o serviço de localização do aparelho para centralizar no mapa.",
                        ),
                    )
                }

                is LocationLookupResult.UnavailableTemporarily -> {
                    updateState(currentAfterLookup.withLocationNotice(result.reason))
                }
            }
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            val canRationale =
                LocationPermissionCoordinator.canShowAnyLocationRationale(
                    LocationPermissionCoordinator.findActivity(context),
                )
            val resolved =
                LocationPermissionCoordinator.resolvePermissionCallback(
                    grantResults = results,
                    initialPromptShown = true,
                    canShowRationale = canRationale,
                    isLocationServiceEnabled = LocationPermissionCoordinator.isLocationServiceEnabled(context),
                )
            val effectiveSnapshot =
                if (resolved.hasAnyPermission) {
                    resolved
                } else {
                    LocationPermissionCoordinator.inspect(context, initialPromptShown = true)
                }
            if (effectiveSnapshot.hasAnyPermission) {
                localPermanentlyDenied = false
                updateState(
                    latestUiState.withPermissionState(
                        state = LocationPermissionState.GRANTED,
                        precision = effectiveSnapshot.precisionLevel,
                        clearCurrentLocationIfDenied = false,
                        notice = null,
                    ),
                )
                handleLocationLookup()
            } else {
                localPermanentlyDenied = effectiveSnapshot.permanentlyDenied
                updateState(latestUiState.withPermissionState(LocationPermissionState.DENIED))
            }
        }

    Surface(
        modifier = modifier.fillMaxWidth().testTag("cities_map_section"),
        shape = RoundedCornerShape(22.dp),
        color = WeatherGlassSpecs.glassContainerColor(spec.cardAlpha),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(ClimaUiIcons.Map),
                    contentDescription = null,
                    tint = ClimaAccentCyan,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.cities_map_title),
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.cities_map_subtitle),
                        color = ClimaTextMutedOnDark,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(ClimaDeepSky.copy(alpha = 0.72f))
                    .testTag("cities_map_viewport")
            ) {
                CityMapLibreViewImpl(
                    state = uiState,
                    isOnline = isOnline,
                    onRetryReload = onRetryReload,
                    onMapTap = { coordinate ->
                        updateState(
                            uiState.selectMarkerAt(
                                coordinate = coordinate,
                                moveCameraToMarker = false,
                            )
                        )
                    },
                    onCameraGestureSettled = { coordinate, zoom ->
                        updateState(uiState.syncCameraFromGesture(coordinate, zoom))
                    },
                    modifier = Modifier.fillMaxSize(),
                    surfacePortFactory = surfacePortFactory,
                )

                // Map zoom and GPS controls overlay
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MapOverlayControlButton(
                        iconRes = ClimaUiIcons.Plus,
                        contentDescription = stringResource(R.string.cities_map_zoom_in),
                        testTag = "city_map_zoom_in",
                        onClick = { updateState(uiState.zoomIn()) },
                    )
                    MapOverlayControlButton(
                        iconRes = null,
                        textGlyph = "−",
                        contentDescription = stringResource(R.string.cities_map_zoom_out),
                        testTag = "city_map_zoom_out",
                        onClick = { updateState(uiState.zoomOut()) },
                    )
                    MapOverlayControlButton(
                        iconRes = ClimaUiIcons.Location,
                        contentDescription = stringResource(R.string.cities_map_my_location),
                        testTag = "city_map_my_location",
                        isLoading = uiState.isLocatingUser,
                        loadingIndicatorTestTag = "city_map_locating_indicator",
                        tint =
                            if (uiState.permissionState == LocationPermissionState.GRANTED) {
                                ClimaToastEmerald
                            } else {
                                ClimaTextOnDark
                            },
                        onClick = {
                            val snapshot =
                                LocationPermissionCoordinator.inspect(
                                    context = context,
                                    initialPromptShown = uiState.permissionState == LocationPermissionState.DENIED,
                                )
                            if (snapshot.hasAnyPermission) {
                                localPermanentlyDenied = false
                                updateState(
                                    latestUiState.withPermissionState(
                                        state = LocationPermissionState.GRANTED,
                                        precision = snapshot.precisionLevel,
                                        clearCurrentLocationIfDenied = false,
                                        notice = null,
                                    ),
                                )
                                handleLocationLookup()
                            } else if (latestPermanentlyDenied || localPermanentlyDenied || snapshot.permanentlyDenied) {
                                localPermanentlyDenied = true
                                updateState(latestUiState.withPermissionState(LocationPermissionState.DENIED))
                            } else {
                                runCatching {
                                    locationPermissionLauncher.launch(LocationPermissionCoordinator.REQUIRED_PERMISSIONS)
                                }
                            }
                        },
                    )
                }

                // Required OpenStreetMap attribution pill
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .testTag("city_map_osm_attribution"),
                    shape = RoundedCornerShape(10.dp),
                    color = ClimaDeepSky.copy(alpha = 0.78f),
                    border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
                ) {
                    Text(
                        text = stringResource(R.string.cities_map_osm_attribution),
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Coordinate, marker, zoom & GPS status readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${stringResource(R.string.cities_map_selected_point)}: ${uiState.selectedMarker.label}",
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("city_map_marker_label"),
                    )
                    Text(
                        text = uiState.selectedMarker.coordinate.formatted(),
                        color = ClimaTextMutedOnDark,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("city_map_coordinates"),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ClimaAccentCyan.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = "Zoom ${uiState.formattedZoomLabel()}",
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("city_map_zoom_value"),
                    )
                }
            }

            val effectivePermanentlyDenied = isPermanentlyDenied || localPermanentlyDenied
            if (uiState.permissionState == LocationPermissionState.DENIED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.cities_map_location_denied),
                        color = ClimaAlertAttentionAmber,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).testTag("city_map_location_status"),
                    )
                    if (effectivePermanentlyDenied) {
                        TextButton(
                            onClick = onOpenAppSettings,
                            modifier = Modifier.testTag("city_map_open_settings_button"),
                        ) {
                            Text(
                                text = "Abrir ajustes",
                                color = ClimaAccentCyan,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else if (uiState.isLocatingUser) {
                Text(
                    text = "Obtendo localização atual…",
                    color = ClimaAccentCyan,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("city_map_location_status"),
                )
            } else if (uiState.locationStatusNotice != null) {
                Text(
                    text = uiState.locationStatusNotice.orEmpty(),
                    color = ClimaAlertAttentionAmber,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("city_map_location_status"),
                )
            } else if (uiState.currentUserLocation != null) {
                val activeLabel =
                    if (uiState.locationPrecision == LocationPrecisionLevel.APPROXIMATE) {
                        stringResource(R.string.cities_map_location_approx_active)
                    } else {
                        stringResource(R.string.cities_map_location_active)
                    }
                Text(
                    text = "$activeLabel: ${uiState.currentUserLocation?.formatted()}",
                    color = ClimaToastEmerald,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("city_map_location_status"),
                )
            }
        }
    }
}

/**
 * Low-level native MapLibre MapView wrapper for Jetpack Compose.
 *
 * Uses:
 * - `MapLibreMapOptions.textureMode(true)` (TextureView) for clean Compose compositing & clipping.
 * - Valid MapLibre Style Spec v8 JSON (`Style.Builder().fromJson(...)`) with OpenStreetMap raster tiles.
 * - [MapLoadStateMachine] + [TileTransportDiagnostics] for separated stages, timeout, and clean retry.
 * - [MapViewLifecycleController] + [ComponentCallbacks2] + explicit listener cleanup on dispose.
 */
@Composable
fun CityMapLibreView(
    state: CityMapUiState,
    onMapTap: (CityMapCoordinate) -> Unit,
    onCameraGestureSettled: (CityMapCoordinate, Double) -> Unit,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
    onRetryReload: () -> Unit = {},
) {
    CityMapLibreViewImpl(
        state = state,
        onMapTap = onMapTap,
        onCameraGestureSettled = onCameraGestureSettled,
        modifier = modifier,
        isOnline = isOnline,
        onRetryReload = onRetryReload,
    )
}

@Composable
internal fun CityMapLibreViewImpl(
    state: CityMapUiState,
    onMapTap: (CityMapCoordinate) -> Unit,
    onCameraGestureSettled: (CityMapCoordinate, Double) -> Unit,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
    onRetryReload: () -> Unit = {},
    surfacePortFactory: ((Context) -> MapViewSurfacePort)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnCameraSettled by rememberUpdatedState(onCameraGestureSettled)
    val latestIsOnline by rememberUpdatedState(isOnline)
    val latestOnRetryReload by rememberUpdatedState(onRetryReload)

    var reloadNonce by remember { mutableIntStateOf(0) }
    var viewRecreationNonce by remember { mutableIntStateOf(0) }
    val mapLibreReady =
        remember(context, surfacePortFactory) {
            surfacePortFactory != null || MapLibreInitializer.ensureInitialized(context)
        }

    if (!mapLibreReady) {
        val jvmState =
            when {
                !isOnline ->
                    MapSurfaceLoadState.ConnectionError("Sem conexão com a internet para carregar os tiles do OpenStreetMap.")
                !MapLibreInitializer.isRobolectricEnvironment() && MapLibreInitializer.lastInitError != null ->
                    MapSurfaceLoadState.RenderError(MapLibreInitializer.lastInitError.orEmpty())
                else -> MapSurfaceLoadState.Available
            }

        Box(
            modifier = modifier.fillMaxSize().testTag("city_maplibre_jvm_surface"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "MapLibre Native + OpenStreetMap (${state.cameraCenter.formatted()})",
                color = ClimaTextMutedOnDark,
                style = MaterialTheme.typography.bodySmall,
            )
            MapSurfaceStatusOverlay(
                loadState = jvmState,
                onRetry = {
                    reloadNonce++
                    onRetryReload()
                },
                testTagPrefix = "city_map",
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    val savedMarkersJson =
        remember(state.savedMarkers) { CityMapGeoJsonBuilder.buildFeatureCollection(state.savedMarkers) }
    val selectedMarkerJson =
        remember(state.selectedMarker) {
            CityMapGeoJsonBuilder.buildFeatureCollection(listOf(state.selectedMarker))
        }
    val latestSavedMarkersJson by rememberUpdatedState(savedMarkersJson)
    val latestSelectedMarkerJson by rememberUpdatedState(selectedMarkerJson)

    val stateMachine = remember { MapLoadStateMachine(screenName = "Cidades") }
    val tileSnapshot by TileTransportDiagnostics.snapshot.collectAsState()

    var mapViewRef by remember { mutableStateOf<MapViewSurfacePort?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreControllerPort?>(null) }
    var loadedStyleRef by remember { mutableStateOf<MapStylePort?>(null) }
    val sessionDisposedRef = remember(viewRecreationNonce) { AtomicBoolean(false) }
    var lastAppliedMoveVersion by remember(viewRecreationNonce) { mutableIntStateOf(state.cameraMoveVersion) }
    val latestCameraState by rememberUpdatedState(state)
    var loadState by remember {
        mutableStateOf(
            stateMachine.beginAttempt(
                isOnline = isOnline,
                hasMapReady = false,
                tileSnapshot = TileTransportDiagnostics.snapshot.value,
            )
        )
    }
    var attemptKey by remember { mutableIntStateOf(stateMachine.attemptId) }

    fun applyCitiesStyle(
        map: MapLibreControllerPort,
        isSessionCurrent: () -> Boolean = { !sessionDisposedRef.get() && mapLibreMapRef === map },
    ) {
        if (!isSessionCurrent()) return
        val onlineNow = latestIsOnline
        loadedStyleRef = null
        loadState = stateMachine.beginAttempt(
            isOnline = onlineNow,
            hasMapReady = true,
            tileSnapshot = TileTransportDiagnostics.snapshot.value,
        )
        attemptKey = stateMachine.attemptId
        if (!onlineNow) {
            ClimaMapLogger.w(ClimaMapLogTags.MAP_STYLE, "Mapa de Cidades sem conexão: aguardando rede.")
            return
        }
        loadState = stateMachine.onStyleRequested(isOnline = true)
        ClimaMapLogger.i(
            ClimaMapLogTags.MAP_STYLE,
            "Estilo base OSM v8 solicitado (Cidades, coords=${latestCameraState.cameraCenter.formatted()}, zoom=${latestCameraState.zoom}).",
        )
        runCatching {
            map.setStyle(
                MapStyleSpecRequest.Cities(
                    savedMarkersGeoJson = latestSavedMarkersJson,
                    selectedMarkerGeoJson = latestSelectedMarkerJson,
                ),
            ) { style ->
                if (!isSessionCurrent()) return@setStyle
                loadedStyleRef = style
                loadState = stateMachine.onStyleLoaded()
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAP_STYLE,
                    "Estilo OSM do Mapa de Cidades carregado em ${stateMachine.elapsedMs()}ms (fullyLoaded=${style.isFullyLoaded}).",
                )
            }
        }.onFailure { err ->
            if (!isSessionCurrent()) return@onFailure
            val msg = "Erro ao aplicar estilo do Mapa de Cidades: ${err.message ?: err.javaClass.simpleName}"
            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, msg, err)
            loadState = stateMachine.onStyleFailed(msg, isOnline = latestIsOnline)
        }
    }

    // Feed real OkHttp tile transport telemetry into the Cities map state machine
    LaunchedEffect(tileSnapshot, isOnline) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        loadState = stateMachine.onTileTransportUpdated(tileSnapshot, isOnline)
    }

    // Initial load timeout guard: prevents indefinite "Carregando mapa..." without faking Available
    LaunchedEffect(attemptKey, isOnline) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        if (!isOnline) {
            loadState = stateMachine.onTileTransportUpdated(TileTransportDiagnostics.snapshot.value, isOnline = false)
            return@LaunchedEffect
        }
        val trackedAttempt = attemptKey
        delay(MapLoadStateMachine.INITIAL_LOAD_TIMEOUT_MS)
        if (sessionDisposedRef.get()) return@LaunchedEffect
        if (stateMachine.attemptId == trackedAttempt && stateMachine.state.isLoading) {
            val surfaceInfo = mapViewRef?.inspectSurfaceInfo() ?: "MapView=null"
            val timedOutState = stateMachine.onTimeout(TileTransportDiagnostics.snapshot.value, surfaceInfo)
            loadState = timedOutState
            val cause = (timedOutState as? MapSurfaceLoadState.TimedOut)?.knownCause.orEmpty()
            ClimaMapLogger.w(
                ClimaMapLogTags.MAP_RENDER,
                "Tempo limite excedido no Mapa de Cidades (${MapLoadStateMachine.INITIAL_LOAD_TIMEOUT_MS}ms): $cause",
            )
        }
    }

    // Reload style when connectivity returns or user triggers a soft retry
    LaunchedEffect(mapLibreMapRef, reloadNonce, isOnline) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val map = mapLibreMapRef ?: return@LaunchedEffect
        if (!isOnline && loadedStyleRef == null) {
            loadState = stateMachine.beginAttempt(isOnline = false, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
            attemptKey = stateMachine.attemptId
        } else if (reloadNonce > 0 || (isOnline && loadState is MapSurfaceLoadState.ConnectionError)) {
            applyCitiesStyle(map)
        }
    }

    // Update marker GeoJSON layers whenever marker state changes
    LaunchedEffect(loadedStyleRef, savedMarkersJson, selectedMarkerJson) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val style = loadedStyleRef ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        style.updateGeoJsonSource(OpenStreetMapTileConfig.SAVED_MARKERS_SOURCE_ID, savedMarkersJson)
        style.updateGeoJsonSource(OpenStreetMapTileConfig.SELECTED_MARKER_SOURCE_ID, selectedMarkerJson)
    }

    // Sync programmatic zoom/pan/location camera commands to MapLibreMap cleanly without side effects
    LaunchedEffect(mapLibreMapRef, state.cameraMoveVersion) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val map = mapLibreMapRef ?: return@LaunchedEffect
        if (state.cameraMoveVersion == lastAppliedMoveVersion) return@LaunchedEffect
        lastAppliedMoveVersion = state.cameraMoveVersion
        runCatching {
            map.animateCamera(
                latitude = state.cameraCenter.latitude,
                longitude = state.cameraCenter.longitude,
                zoom = state.zoom,
                durationMs = 260,
            )
        }
    }

    // Synchronize native MapLibre LocationComponent ONLY when map/style/permission/precision changes
    LaunchedEffect(mapLibreMapRef, loadedStyleRef, state.permissionState, state.locationPrecision) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val map = mapLibreMapRef ?: return@LaunchedEffect
        val style = loadedStyleRef ?: return@LaunchedEffect
        map.syncLocationComponent(context, style)
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(viewRecreationNonce) {
            var sessionHolder by remember { mutableStateOf<MapViewSessionHandle?>(null) }
            val localSessionDisposed = remember { AtomicBoolean(false) }

            DisposableEffect(lifecycleOwner, context) {
                val observer =
                    LifecycleEventObserver { _, event ->
                        sessionHolder?.controller?.onLifecycleEvent(event)
                    }
                val lowMemoryCallbacks =
                    object : ComponentCallbacks2 {
                        override fun onConfigurationChanged(newConfig: Configuration) = Unit
                        @Deprecated("Deprecated in Java")
                        override fun onLowMemory() {
                            sessionHolder?.controller?.dispatchLowMemory()
                        }
                        override fun onTrimMemory(level: Int) {
                            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                                sessionHolder?.controller?.dispatchLowMemory()
                            }
                        }
                    }
                lifecycleOwner.lifecycle.addObserver(observer)
                context.applicationContext?.registerComponentCallbacks(lowMemoryCallbacks)
                onDispose {
                    localSessionDisposed.set(true)
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    context.applicationContext?.unregisterComponentCallbacks(lowMemoryCallbacks)
                    val holder = sessionHolder
                    if (holder == null || mapViewRef === holder.surfacePort) {
                        sessionDisposedRef.set(true)
                    }
                    holder?.pauseAndStopBeforeDetach("onDispose")
                    if (holder != null && mapViewRef === holder.surfacePort) {
                        mapViewRef = null
                        mapLibreMapRef = null
                        loadedStyleRef = null
                    }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("city_maplibre_native_view"),
                factory = { viewContext ->
                    val sessionId = ClimaGraphicsLifecycleDiagnostics.nextSessionId("cities_map")
                    val port = surfacePortFactory?.invoke(viewContext) ?: RealMapViewSurfacePort(viewContext)
                    var sessionMapController: MapLibreControllerPort? = null
                    fun isThisSessionCurrent(): Boolean =
                        !localSessionDisposed.get() &&
                            mapViewRef === port &&
                            (sessionHolder == null || sessionHolder?.isSessionActive == true)

                    ClimaMapLogger.i(
                        ClimaMapLogTags.MAP_RENDER,
                        "Criando MapView de Cidades (TextureView, OpenGL ES, tentativa=${viewRecreationNonce + 1}).",
                    )
                    ClimaGraphicsLifecycleDiagnostics.logMapViewCreated(
                        screen = "Cidades",
                        sessionId = sessionId,
                        viewIdentity = port.viewIdentity,
                        attempt = viewRecreationNonce + 1,
                        viewClass = port.viewClassName,
                    )
                    val firstFrameLogged = AtomicBoolean(false)
                    val fullyRenderedLogged = AtomicBoolean(false)

                    val controller =
                        MapViewLifecycleController(
                            onCreate = { port.onCreate() },
                            onStart = {
                                port.onStart()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Cidades", sessionId, port.viewIdentity, "ON_START")
                            },
                            onResume = {
                                port.onResume()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Cidades", sessionId, port.viewIdentity, "ON_RESUME")
                            },
                            onPause = {
                                port.onPause()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Cidades", sessionId, port.viewIdentity, "ON_PAUSE")
                            },
                            onStop = {
                                port.onStop()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Cidades", sessionId, port.viewIdentity, "ON_STOP")
                            },
                            onDestroy = { port.onDestroy() },
                            onLowMemory = { port.onLowMemory() },
                        )
                    controller.syncToLifecycleState(lifecycleOwner.lifecycle.currentState)

                    val detachWindowObserver =
                        port.attachWindowObserver { attached, surfaceInfo ->
                            ClimaGraphicsLifecycleDiagnostics.logMapViewAttachState(
                                screen = "Cidades",
                                sessionId = sessionId,
                                viewClass = port.viewClassName,
                                viewIdentity = port.viewIdentity,
                                attached = attached,
                                surfaceInfo = surfaceInfo,
                            )
                        }

                    val detachListeners =
                        port.attachListeners(
                            MapSurfaceCallbacks(
                                onWillStartLoading = {
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    ClimaMapLogger.i(ClimaMapLogTags.MAP_STYLE, "MapView Cidades iniciou carregamento do mapa.")
                                },
                                onDidFinishLoadingStyle = {
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_STYLE,
                                        "Callback onDidFinishLoadingStyle disparado (Cidades, ${stateMachine.elapsedMs()}ms).",
                                    )
                                },
                                onDidFinishLoadingMap = {
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_RENDER,
                                        "onDidFinishLoadingMap disparado (Cidades, ${stateMachine.elapsedMs()}ms).",
                                    )
                                    port.postToUi {
                                        if (!isThisSessionCurrent()) return@postToUi
                                        loadState = stateMachine.onMapLoadedCallback(
                                            TileTransportDiagnostics.snapshot.value,
                                            latestIsOnline,
                                        )
                                    }
                                },
                                onDidFailLoadingMap = { errorMessage ->
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    val reason = errorMessage.ifBlank { "Erro ao carregar mapa-base OpenStreetMap." }
                                    ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, "Falha no estilo/mapa Cidades: $reason")
                                    port.postToUi {
                                        if (!isThisSessionCurrent()) return@postToUi
                                        loadState = stateMachine.onStyleFailed(reason, isOnline = latestIsOnline)
                                    }
                                },
                                onWillStartRenderingMap = {
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    stateMachine.updateSurfaceDiagnostics(port.inspectSurfaceInfo())
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_RENDER,
                                        "MapView Cidades iniciou pipeline de renderização (${stateMachine.surfaceDiagnostics}).",
                                    )
                                },
                                onFirstFrameRendered = {
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    if (firstFrameLogged.compareAndSet(false, true)) {
                                        ClimaMapLogger.i(
                                            ClimaMapLogTags.MAP_RENDER,
                                            "Primeiro quadro do MapView de Cidades renderizado em ${stateMachine.elapsedMs()}ms.",
                                        )
                                        port.postToUi {
                                            if (!isThisSessionCurrent()) return@postToUi
                                            loadState = stateMachine.onFirstFrameRendered(TileTransportDiagnostics.snapshot.value)
                                        }
                                    }
                                },
                                onDidFinishRenderingMap = { fully ->
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    if (fully && fullyRenderedLogged.compareAndSet(false, true)) {
                                        ClimaMapLogger.i(
                                            ClimaMapLogTags.MAP_RENDER,
                                            "Mapa de Cidades totalmente renderizado com tiles em ${stateMachine.elapsedMs()}ms.",
                                        )
                                    }
                                    port.postToUi {
                                        if (!isThisSessionCurrent()) return@postToUi
                                        loadState = stateMachine.onMapFinishedRendering(
                                            fully = fully,
                                            tileSnapshot = TileTransportDiagnostics.snapshot.value,
                                        )
                                    }
                                },
                                onRenderError = { reason ->
                                    if (!isThisSessionCurrent()) return@MapSurfaceCallbacks
                                    ClimaMapLogger.e(ClimaMapLogTags.MAP_RENDER, "Erro nativo de renderização no MapView de Cidades.")
                                    port.postToUi {
                                        if (!isThisSessionCurrent()) return@postToUi
                                        loadState = stateMachine.onRenderError(reason)
                                    }
                                },
                            ),
                        )

                    sessionHolder =
                        MapViewSessionHandle(
                            screenName = "Cidades",
                            sessionId = sessionId,
                            surfacePort = port,
                            controller = controller,
                            onDetachListeners = detachListeners,
                            onDetachWindowObserver = detachWindowObserver,
                            onCancelTransitions = {
                                sessionMapController?.cancelTransitions()
                                sessionMapController?.clearMapListeners()
                            },
                        )
                    mapViewRef = port
                    sessionDisposedRef.set(false)

                    port.getMapAsync { map ->
                        if (!isThisSessionCurrent()) return@getMapAsync
                        sessionMapController = map
                        mapLibreMapRef = map
                        stateMachine.updateSurfaceDiagnostics(port.inspectSurfaceInfo())
                        loadState = stateMachine.onMapReady(latestIsOnline)
                        ClimaMapLogger.i(
                            ClimaMapLogTags.MAP_RENDER,
                            "getMapAsync pronto em Cidades (${stateMachine.surfaceDiagnostics}).",
                        )
                        map.configureUiSettings()

                        val initialCamera = latestCameraState
                        lastAppliedMoveVersion = initialCamera.cameraMoveVersion
                        map.setInitialCamera(
                            latitude = initialCamera.cameraCenter.latitude,
                            longitude = initialCamera.cameraCenter.longitude,
                            zoom = initialCamera.zoom,
                        )

                        applyCitiesStyle(map, ::isThisSessionCurrent)

                        map.setOnMapClick { coord ->
                            if (!isThisSessionCurrent()) return@setOnMapClick
                            latestOnMapTap(coord)
                        }

                        map.setOnCameraIdle { coord, settledZoom ->
                            if (!isThisSessionCurrent()) return@setOnCameraIdle
                            latestOnCameraSettled(coord, settledZoom)
                        }
                    }
                    port.androidView
                },
                onRelease = {
                    localSessionDisposed.set(true)
                    val holder = sessionHolder
                    if (holder == null || mapViewRef === holder.surfacePort) {
                        sessionDisposedRef.set(true)
                    }
                    holder?.destroyOnceAfterDetach()
                    if (holder != null && mapViewRef === holder.surfacePort) {
                        mapViewRef = null
                        mapLibreMapRef = null
                        loadedStyleRef = null
                    }
                    ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "MapView de Cidades destruído e listeners removidos.")
                },
            )
        }

        MapSurfaceStatusOverlay(
            loadState = loadState,
            onRetry = {
                val shouldRecreateView =
                    mapLibreMapRef == null ||
                        loadState is MapSurfaceLoadState.RenderError ||
                        (loadState as? MapSurfaceLoadState.TimedOut)?.stalledStage == MapLoadStage.INITIALIZING
                if (shouldRecreateView) {
                    mapLibreMapRef = null
                    loadedStyleRef = null
                    loadState = stateMachine.beginAttempt(
                        isOnline = latestIsOnline,
                        hasMapReady = false,
                        tileSnapshot = TileTransportDiagnostics.snapshot.value,
                    )
                    attemptKey = stateMachine.attemptId
                    viewRecreationNonce++
                } else {
                    reloadNonce++
                }
                latestOnRetryReload()
            },
            testTagPrefix = "city_map",
            modifier =
                if (loadState.isLoading) {
                    Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                } else {
                    Modifier.align(Alignment.Center)
                },
        )
    }
}

@Composable
private fun MapOverlayControlButton(
    iconRes: Int?,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    textGlyph: String? = null,
    isLoading: Boolean = false,
    loadingIndicatorTestTag: String? = null,
    tint: Color = ClimaTextOnDark,
) {
    Surface(
        shape = CircleShape,
        color = ClimaDeepSky.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
        modifier = Modifier.minimumInteractiveComponentSize(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).testTag(testTag),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = ClimaAccentCyan,
                    strokeWidth = 2.dp,
                    modifier =
                        Modifier
                            .size(18.dp)
                            .let { mod ->
                                if (loadingIndicatorTestTag != null) mod.testTag(loadingIndicatorTestTag) else mod
                            },
                )
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            } else if (textGlyph != null) {
                Text(
                    text = textGlyph,
                    color = tint,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

internal enum class LocationComponentSyncResult {
    ACTIVATED_WITH_FIX,
    ACTIVATED_AWAITING_FIX,
    ALREADY_ACTIVE,
    STYLE_NOT_READY,
    PERMISSION_DENIED,
    DISABLED_AFTER_REVOCATION,
    FAILED_NON_FATAL,
}

internal interface MapLibreLocationGateway {
    val isStyleFullyLoaded: Boolean
    val isActivated: Boolean
    val isEnabled: Boolean
    fun matchesConfiguration(useHighAccuracy: Boolean): Boolean = false
    fun activate(useHighAccuracy: Boolean)
    fun applyModes(useHighAccuracy: Boolean)
    fun disableIfActivated()
    fun queryLastKnownCoordinate(): CityMapCoordinate?
}

private data class AppliedLocationConfig(
    val styleIdentity: Int,
    val useHighAccuracy: Boolean,
)

private class RealMapLibreLocationGateway(
    private val context: Context,
    private val map: MapLibreMap,
    private val style: Style,
) : MapLibreLocationGateway {

    override val isStyleFullyLoaded: Boolean
        get() = runCatching { style.isFullyLoaded }.getOrDefault(false)

    override val isActivated: Boolean
        get() = runCatching { map.locationComponent.isLocationComponentActivated }.getOrDefault(false)

    override val isEnabled: Boolean
        get() = isActivated && runCatching { map.locationComponent.isLocationComponentEnabled }.getOrDefault(false)

    override fun matchesConfiguration(useHighAccuracy: Boolean): Boolean {
        if (!isActivated || !isEnabled) return false
        val applied = appliedConfigs[map] ?: return false
        return applied.styleIdentity == System.identityHashCode(style) &&
            applied.useHighAccuracy == useHighAccuracy
    }

    private fun buildLocationEngineRequest(useHighAccuracy: Boolean): LocationEngineRequest =
        LocationEngineRequest.Builder(5_000L)
            .setPriority(
                if (useHighAccuracy) {
                    LocationEngineRequest.PRIORITY_HIGH_ACCURACY
                } else {
                    LocationEngineRequest.PRIORITY_BALANCED_POWER_ACCURACY
                },
            )
            .setMaxWaitTime(10_000L)
            .setFastestInterval(2_000L)
            .build()

    @Suppress("MissingPermission")
    override fun activate(useHighAccuracy: Boolean) {
        val options =
            LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(true)
                .locationEngineRequest(buildLocationEngineRequest(useHighAccuracy))
                .build()
        map.locationComponent.activateLocationComponent(options)
    }

    @Suppress("MissingPermission")
    override fun applyModes(useHighAccuracy: Boolean) {
        val component = map.locationComponent
        component.isLocationComponentEnabled = true
        component.cameraMode = CameraMode.NONE
        component.renderMode =
            if (useHighAccuracy) {
                RenderMode.COMPASS
            } else {
                RenderMode.NORMAL
            }
        runCatching {
            component.locationEngineRequest = buildLocationEngineRequest(useHighAccuracy)
        }
        appliedConfigs[map] =
            AppliedLocationConfig(
                styleIdentity = System.identityHashCode(style),
                useHighAccuracy = useHighAccuracy,
            )
    }

    @Suppress("MissingPermission")
    override fun disableIfActivated() {
        appliedConfigs.remove(map)
        val component = map.locationComponent
        if (component.isLocationComponentActivated && component.isLocationComponentEnabled) {
            component.isLocationComponentEnabled = false
        }
    }

    @Suppress("MissingPermission")
    override fun queryLastKnownCoordinate(): CityMapCoordinate? {
        return try {
            val loc = map.locationComponent.lastKnownLocation
            if (loc != null && isValidCoordinate(loc.latitude, loc.longitude)) {
                CityMapCoordinate(loc.latitude, loc.longitude)
            } else {
                null
            }
        } catch (sec: SecurityException) {
            ClimaMapLogger.w(ClimaMapLogTags.MAP_RENDER, "Sem permissão ao consultar lastKnownLocation: ${sec.message}")
            null
        } catch (err: Exception) {
            ClimaMapLogger.w(
                ClimaMapLogTags.MAP_RENDER,
                "Última localização ainda indisponível (${err.message ?: err.javaClass.simpleName}).",
            )
            null
        }
    }

    companion object {
        private val appliedConfigs = WeakHashMap<MapLibreMap, AppliedLocationConfig>()
    }
}

private fun isValidCoordinate(lat: Double, lon: Double): Boolean =
    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

/**
 * Pure, unit-testable coordinator for [enableMapLibreLocationComponent].
 *
 * Guarantees:
 * - Never activates [LocationComponent] without `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION`.
 * - Deactivates an already active [LocationComponent] if permissions are revoked while open.
 * - Requires loaded style before activation.
 * - Idempotent when already active for the same style + accuracy configuration (avoids duplicate GPS/mode resets).
 * - Adjusts precision mode (`useHighAccuracy` true for `ACCESS_FINE_LOCATION`, false for `ACCESS_COARSE_LOCATION`).
 * - Handles missing `lastKnownLocation` ("Last location unavailable") gracefully without throwing or
 *   propagating false connection/render failures to [MapSurfaceLoadState].
 */
internal fun syncMapLibreLocationComponent(
    context: Context,
    gateway: MapLibreLocationGateway,
    onValidLocation: ((CityMapCoordinate) -> Unit)? = null,
): LocationComponentSyncResult {
    val fine = LocationPermissionCoordinator.hasFinePermission(context)
    val coarse = LocationPermissionCoordinator.hasCoarsePermission(context)
    if (!fine && !coarse) {
        return if (gateway.isActivated || gateway.isEnabled) {
            runCatching { gateway.disableIfActivated() }
            ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "LocationComponent desativado após revogação de permissão.")
            LocationComponentSyncResult.DISABLED_AFTER_REVOCATION
        } else {
            LocationComponentSyncResult.PERMISSION_DENIED
        }
    }

    if (!gateway.isStyleFullyLoaded) {
        return LocationComponentSyncResult.STYLE_NOT_READY
    }

    val useHighAccuracy = fine
    val precision =
        if (useHighAccuracy) LocationPrecisionLevel.PRECISE else LocationPrecisionLevel.APPROXIMATE

    if (gateway.isActivated && gateway.isEnabled && gateway.matchesConfiguration(useHighAccuracy)) {
        return LocationComponentSyncResult.ALREADY_ACTIVE
    }

    return try {
        val wasActivated = gateway.isActivated
        val configChanged = !gateway.matchesConfiguration(useHighAccuracy)
        if (!wasActivated) {
            gateway.activate(useHighAccuracy)
        }
        if (!wasActivated || !gateway.isEnabled || configChanged) {
            gateway.applyModes(useHighAccuracy)
        }

        val coord =
            runCatching { gateway.queryLastKnownCoordinate() }.getOrNull()
                ?: LocationPermissionCoordinator.fallbackSystemLastLocation(context, precision)

        if (coord != null && isValidCoordinate(coord.latitude, coord.longitude)) {
            onValidLocation?.invoke(coord)
            LocationComponentSyncResult.ACTIVATED_WITH_FIX
        } else {
            LocationComponentSyncResult.ACTIVATED_AWAITING_FIX
        }
    } catch (sec: SecurityException) {
        ClimaMapLogger.w(ClimaMapLogTags.MAP_RENDER, "SecurityException no LocationComponent (sem falha fatal): ${sec.message}")
        runCatching { gateway.disableIfActivated() }
        LocationComponentSyncResult.PERMISSION_DENIED
    } catch (err: Exception) {
        ClimaMapLogger.w(
            ClimaMapLogTags.MAP_RENDER,
            "Falha não fatal no LocationComponent (${err.message ?: err.javaClass.simpleName}); mapa OSM mantido.",
        )
        LocationComponentSyncResult.FAILED_NON_FATAL
    }
}

internal fun enableMapLibreLocationComponent(
    context: Context,
    map: MapLibreMap,
    style: Style,
    onValidLocation: ((CityMapCoordinate) -> Unit)? = null,
): LocationComponentSyncResult =
    syncMapLibreLocationComponent(
        context = context,
        gateway = RealMapLibreLocationGateway(context = context, map = map, style = style),
        onValidLocation = onValidLocation,
    )
