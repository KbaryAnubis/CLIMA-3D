package com.example.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.view.MotionEvent
import android.view.TextureView
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.media.ClimaUiIcons
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.WeatherGlassSpecs
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

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
    onRetryReload: () -> Unit = {},
    onStateChanged: ((CityMapUiState) -> Unit)? = null,
) {
    val context = LocalContext.current
    val spec = remember { WeatherGlassSpecs.forTab(ClimaTab.CITIES) }
    var uiState by remember {
        val granted = hasLocationPermission(context)
        mutableStateOf(
            initialState.withPermissionState(
                if (granted) LocationPermissionState.GRANTED else initialState.permissionState
            )
        )
    }

    LaunchedEffect(initialState.savedMarkers) {
        if (uiState.savedMarkers != initialState.savedMarkers) {
            uiState = uiState.withSavedMarkers(initialState.savedMarkers)
        }
    }

    fun updateState(next: CityMapUiState) {
        uiState = next
        onStateChanged?.invoke(next)
    }

    val currentLocationLabel = stringResource(R.string.cities_map_my_location)

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val granted =
                results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (granted) {
                updateState(uiState.withPermissionState(LocationPermissionState.GRANTED))
                fetchLastOrCurrentLocation(context) { coordinate ->
                    if (coordinate != null) {
                        updateState(
                            uiState.withCurrentLocation(
                                coordinate = coordinate,
                                label = currentLocationLabel,
                            )
                        )
                    }
                }
            } else {
                updateState(uiState.withPermissionState(LocationPermissionState.DENIED))
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
                CityMapLibreView(
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
                        tint =
                            if (uiState.permissionState == LocationPermissionState.GRANTED) {
                                ClimaToastEmerald
                            } else {
                                ClimaTextOnDark
                            },
                        onClick = {
                            if (hasLocationPermission(context)) {
                                updateState(uiState.withPermissionState(LocationPermissionState.GRANTED))
                                fetchLastOrCurrentLocation(context) { coordinate ->
                                    if (coordinate != null) {
                                        updateState(
                                            uiState.withCurrentLocation(
                                                coordinate = coordinate,
                                                label = currentLocationLabel,
                                            )
                                        )
                                    }
                                }
                            } else {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
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

            if (uiState.permissionState == LocationPermissionState.DENIED) {
                Text(
                    text = stringResource(R.string.cities_map_location_denied),
                    color = ClimaAlertAttentionAmber,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("city_map_location_status"),
                )
            } else if (uiState.currentUserLocation != null) {
                Text(
                    text = "${stringResource(R.string.cities_map_location_active)}: ${uiState.currentUserLocation?.formatted()}",
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnCameraSettled by rememberUpdatedState(onCameraGestureSettled)
    val latestIsOnline by rememberUpdatedState(isOnline)
    val latestOnRetryReload by rememberUpdatedState(onRetryReload)

    var reloadNonce by remember { mutableIntStateOf(0) }
    var viewRecreationNonce by remember { mutableIntStateOf(0) }
    val mapLibreReady = remember(context) { MapLibreInitializer.ensureInitialized(context) }

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

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleRef by remember { mutableStateOf<Style?>(null) }
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

    fun inspectSurfaceInfo(view: MapView?): String {
        if (view == null) return "MapView=null"
        val renderView = runCatching { view.renderView }.getOrNull()
        val textureAvailable = (renderView as? TextureView)?.isAvailable
        return "MapView=${view.width}x${view.height}, attached=${view.isAttachedToWindow}, textureAvailable=$textureAvailable"
    }

    fun applyCitiesStyle(map: MapLibreMap, viewContext: Context) {
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
            "Estilo base OSM v8 solicitado (Cidades, coords=${state.cameraCenter.formatted()}, zoom=${state.zoom}).",
        )
        runCatching {
            val styleBuilder =
                OpenStreetMapTileConfig.buildStyleBuilder(
                    savedMarkersGeoJson = latestSavedMarkersJson,
                    selectedMarkerGeoJson = latestSelectedMarkerJson,
                )
            map.setStyle(styleBuilder) { style ->
                loadedStyleRef = style
                loadState = stateMachine.onStyleLoaded()
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAP_STYLE,
                    "Estilo OSM do Mapa de Cidades carregado em ${stateMachine.elapsedMs()}ms (fullyLoaded=${style.isFullyLoaded}).",
                )
                if (hasLocationPermission(viewContext)) {
                    enableMapLibreLocationComponent(viewContext, map, style)
                }
            }
        }.onFailure { err ->
            val msg = "Erro ao aplicar estilo do Mapa de Cidades: ${err.message ?: err.javaClass.simpleName}"
            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, msg, err)
            loadState = stateMachine.onStyleFailed(msg, isOnline = latestIsOnline)
        }
    }

    // Feed real OkHttp tile transport telemetry into the Cities map state machine
    LaunchedEffect(tileSnapshot, isOnline) {
        loadState = stateMachine.onTileTransportUpdated(tileSnapshot, isOnline)
    }

    // Initial load timeout guard: prevents indefinite "Carregando mapa..." without faking Available
    LaunchedEffect(attemptKey, isOnline) {
        if (!isOnline) {
            loadState = stateMachine.onTileTransportUpdated(TileTransportDiagnostics.snapshot.value, isOnline = false)
            return@LaunchedEffect
        }
        val trackedAttempt = attemptKey
        delay(MapLoadStateMachine.INITIAL_LOAD_TIMEOUT_MS)
        if (stateMachine.attemptId == trackedAttempt && stateMachine.state.isLoading) {
            val surfaceInfo = inspectSurfaceInfo(mapViewRef)
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
        val map = mapLibreMapRef ?: return@LaunchedEffect
        if (!isOnline && loadedStyleRef == null) {
            loadState = stateMachine.beginAttempt(isOnline = false, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
            attemptKey = stateMachine.attemptId
        } else if (reloadNonce > 0 || (isOnline && loadState is MapSurfaceLoadState.ConnectionError)) {
            applyCitiesStyle(map, context)
        }
    }

    // Update marker GeoJSON layers whenever marker state changes
    LaunchedEffect(loadedStyleRef, savedMarkersJson, selectedMarkerJson) {
        val style = loadedStyleRef ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(OpenStreetMapTileConfig.SAVED_MARKERS_SOURCE_ID)
            ?.setGeoJson(savedMarkersJson)
        style.getSourceAs<GeoJsonSource>(OpenStreetMapTileConfig.SELECTED_MARKER_SOURCE_ID)
            ?.setGeoJson(selectedMarkerJson)
    }

    // Sync programmatic zoom/pan/location camera commands to MapLibreMap
    LaunchedEffect(mapLibreMapRef, state.cameraMoveVersion) {
        val map = mapLibreMapRef ?: return@LaunchedEffect
        val position =
            CameraPosition.Builder()
                .target(LatLng(state.cameraCenter.latitude, state.cameraCenter.longitude))
                .zoom(state.zoom)
                .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 300)
    }

    // Activate native MapLibre LocationComponent when permission is granted
    LaunchedEffect(mapLibreMapRef, loadedStyleRef, state.permissionState) {
        val map = mapLibreMapRef ?: return@LaunchedEffect
        val style = loadedStyleRef ?: return@LaunchedEffect
        if (state.permissionState == LocationPermissionState.GRANTED && hasLocationPermission(context)) {
            enableMapLibreLocationComponent(context, map, style)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(viewRecreationNonce) {
            var sessionHolder by remember { mutableStateOf<CityMapViewSession?>(null) }

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
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    context.applicationContext?.unregisterComponentCallbacks(lowMemoryCallbacks)
                    sessionHolder?.dispose()
                    sessionHolder = null
                    mapViewRef = null
                    mapLibreMapRef = null
                    loadedStyleRef = null
                    ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "MapView de Cidades destruído e listeners removidos.")
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("city_maplibre_native_view"),
                factory = { viewContext ->
                    val options = MapLibreInitializer.createTextureMapOptions(viewContext)
                    ClimaMapLogger.i(
                        ClimaMapLogTags.MAP_RENDER,
                        "Criando MapView de Cidades (TextureView, OpenGL ES, tentativa=${viewRecreationNonce + 1}).",
                    )
                    val view = MapView(viewContext, options)
                    val firstFrameLogged = AtomicBoolean(false)
                    val fullyRenderedLogged = AtomicBoolean(false)

                    val controller =
                        MapViewLifecycleController(
                            onCreate = { view.onCreate(null) },
                            onStart = { view.onStart() },
                            onResume = { view.onResume() },
                            onPause = { view.onPause() },
                            onStop = { view.onStop() },
                            onDestroy = { view.onDestroy() },
                            onLowMemory = { view.onLowMemory() },
                        )
                    controller.syncToLifecycleState(lifecycleOwner.lifecycle.currentState)

                    @Suppress("ClickableViewAccessibility")
                    view.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }

                    val willStartLoadingListener =
                        MapView.OnWillStartLoadingMapListener {
                            ClimaMapLogger.i(ClimaMapLogTags.MAP_STYLE, "MapView Cidades iniciou carregamento do mapa.")
                        }
                    val styleLoadedListener =
                        MapView.OnDidFinishLoadingStyleListener {
                            ClimaMapLogger.i(
                                ClimaMapLogTags.MAP_STYLE,
                                "Callback onDidFinishLoadingStyle disparado (Cidades, ${stateMachine.elapsedMs()}ms).",
                            )
                        }
                    val mapLoadedListener =
                        MapView.OnDidFinishLoadingMapListener {
                            ClimaMapLogger.i(
                                ClimaMapLogTags.MAP_RENDER,
                                "onDidFinishLoadingMap disparado (Cidades, ${stateMachine.elapsedMs()}ms).",
                            )
                            view.post {
                                loadState = stateMachine.onMapLoadedCallback(
                                    TileTransportDiagnostics.snapshot.value,
                                    latestIsOnline,
                                )
                            }
                        }
                    val mapFailListener =
                        MapView.OnDidFailLoadingMapListener { errorMessage ->
                            val reason = errorMessage.ifBlank { "Erro ao carregar mapa-base OpenStreetMap." }
                            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, "Falha no estilo/mapa Cidades: $reason")
                            view.post {
                                loadState = stateMachine.onStyleFailed(reason, isOnline = latestIsOnline)
                            }
                        }
                    val willStartRenderingMapListener =
                        MapView.OnWillStartRenderingMapListener {
                            stateMachine.updateSurfaceDiagnostics(inspectSurfaceInfo(view))
                            ClimaMapLogger.i(
                                ClimaMapLogTags.MAP_RENDER,
                                "MapView Cidades iniciou pipeline de renderização (${stateMachine.surfaceDiagnostics}).",
                            )
                        }
                    val frameRenderedListener =
                        MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                            if (firstFrameLogged.compareAndSet(false, true)) {
                                ClimaMapLogger.i(
                                    ClimaMapLogTags.MAP_RENDER,
                                    "Primeiro quadro do MapView de Cidades renderizado em ${stateMachine.elapsedMs()}ms.",
                                )
                                view.post {
                                    loadState = stateMachine.onFirstFrameRendered(TileTransportDiagnostics.snapshot.value)
                                }
                            }
                        }
                    val mapRenderedListener =
                        MapView.OnDidFinishRenderingMapListener { fully ->
                            if (fully) {
                                if (fullyRenderedLogged.compareAndSet(false, true)) {
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_RENDER,
                                        "Mapa de Cidades totalmente renderizado com tiles em ${stateMachine.elapsedMs()}ms.",
                                    )
                                }
                                view.post {
                                    loadState = stateMachine.onMapFinishedRendering(
                                        fully = true,
                                        tileSnapshot = TileTransportDiagnostics.snapshot.value,
                                    )
                                }
                            } else {
                                view.post {
                                    loadState = stateMachine.onMapFinishedRendering(
                                        fully = false,
                                        tileSnapshot = TileTransportDiagnostics.snapshot.value,
                                    )
                                }
                            }
                        }
                    val renderErrorListener =
                        MapView.OnRenderErrorListener {
                            ClimaMapLogger.e(ClimaMapLogTags.MAP_RENDER, "Erro nativo de renderização no MapView de Cidades.")
                            view.post {
                                loadState = stateMachine.onRenderError("Erro nativo de renderização OpenGL/TextureView em Cidades.")
                            }
                        }
                    val shaderErrorListener =
                        MapView.OnShaderCompileFailedListener { id, type, _ ->
                            ClimaMapLogger.e(ClimaMapLogTags.MAP_RENDER, "Falha ao compilar shader MapLibre Cidades (id=$id, type=$type).")
                            view.post {
                                loadState = stateMachine.onRenderError("Falha ao compilar shader OpenGL (id=$id).")
                            }
                        }

                    view.addOnWillStartLoadingMapListener(willStartLoadingListener)
                    view.addOnDidFinishLoadingStyleListener(styleLoadedListener)
                    view.addOnDidFinishLoadingMapListener(mapLoadedListener)
                    view.addOnDidFailLoadingMapListener(mapFailListener)
                    view.addOnWillStartRenderingMapListener(willStartRenderingMapListener)
                    view.addOnDidFinishRenderingFrameListener(frameRenderedListener)
                    view.addOnDidFinishRenderingMapListener(mapRenderedListener)
                    view.addOnRenderErrorListener(renderErrorListener)
                    view.addOnShaderCompileFailedListener(shaderErrorListener)

                    sessionHolder =
                        CityMapViewSession(
                            mapView = view,
                            controller = controller,
                            onDetachListeners = {
                                view.removeOnWillStartLoadingMapListener(willStartLoadingListener)
                                view.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
                                view.removeOnDidFinishLoadingMapListener(mapLoadedListener)
                                view.removeOnDidFailLoadingMapListener(mapFailListener)
                                view.removeOnWillStartRenderingMapListener(willStartRenderingMapListener)
                                view.removeOnDidFinishRenderingFrameListener(frameRenderedListener)
                                view.removeOnDidFinishRenderingMapListener(mapRenderedListener)
                                view.removeOnRenderErrorListener(renderErrorListener)
                                view.removeOnShaderCompileFailedListener(shaderErrorListener)
                            },
                        )
                    mapViewRef = view

                    view.getMapAsync { map ->
                        mapLibreMapRef = map
                        stateMachine.updateSurfaceDiagnostics(inspectSurfaceInfo(view))
                        loadState = stateMachine.onMapReady(latestIsOnline)
                        ClimaMapLogger.i(
                            ClimaMapLogTags.MAP_RENDER,
                            "getMapAsync pronto em Cidades (${stateMachine.surfaceDiagnostics}).",
                        )
                        map.uiSettings.apply {
                            isZoomGesturesEnabled = true
                            isScrollGesturesEnabled = true
                            isDoubleTapGesturesEnabled = true
                            isRotateGesturesEnabled = false
                            isTiltGesturesEnabled = false
                            isLogoEnabled = false
                            isAttributionEnabled = false
                        }

                        map.cameraPosition =
                            CameraPosition.Builder()
                                .target(LatLng(state.cameraCenter.latitude, state.cameraCenter.longitude))
                                .zoom(state.zoom)
                                .build()

                        applyCitiesStyle(map, viewContext)

                        map.addOnMapClickListener { point ->
                            latestOnMapTap(CityMapCoordinate(point.latitude, point.longitude))
                            true
                        }

                        map.addOnCameraIdleListener {
                            val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                            latestOnCameraSettled(
                                CityMapCoordinate(target.latitude, target.longitude),
                                map.cameraPosition.zoom,
                            )
                        }
                    }
                    view
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

private class CityMapViewSession(
    val mapView: MapView,
    val controller: MapViewLifecycleController,
    private val onDetachListeners: () -> Unit,
) {
    fun dispose() {
        runCatching { onDetachListeners() }
        controller.destroy()
    }
}

@Composable
private fun MapOverlayControlButton(
    iconRes: Int?,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    textGlyph: String? = null,
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
            if (iconRes != null) {
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

private fun hasLocationPermission(context: Context): Boolean {
    val fine =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val coarse =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

@SuppressLint("MissingPermission")
private fun enableMapLibreLocationComponent(
    context: Context,
    map: MapLibreMap,
    style: Style,
) {
    runCatching {
        val locationComponent = map.locationComponent
        if (!locationComponent.isLocationComponentActivated) {
            val options = LocationComponentActivationOptions.builder(context, style).build()
            locationComponent.activateLocationComponent(options)
        }
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.NONE
        locationComponent.renderMode = RenderMode.COMPASS
    }
}

@SuppressLint("MissingPermission")
private fun fetchLastOrCurrentLocation(
    context: Context,
    onResult: (CityMapCoordinate?) -> Unit,
) {
    if (!hasLocationPermission(context)) {
        onResult(null)
        return
    }

    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    fusedClient.lastLocation
        .addOnSuccessListener { lastKnown: Location? ->
            if (lastKnown != null) {
                onResult(CityMapCoordinate(lastKnown.latitude, lastKnown.longitude))
            } else {
                val cancellation = CancellationTokenSource()
                fusedClient
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                    .addOnSuccessListener { current ->
                        if (current != null) {
                            onResult(CityMapCoordinate(current.latitude, current.longitude))
                        } else {
                            onResult(fallbackSystemLastLocation(context))
                        }
                    }
                    .addOnFailureListener {
                        onResult(fallbackSystemLastLocation(context))
                    }
            }
        }
        .addOnFailureListener {
            onResult(fallbackSystemLastLocation(context))
        }
}

@SuppressLint("MissingPermission")
private fun fallbackSystemLastLocation(context: Context): CityMapCoordinate? {
    return runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val best =
            providers
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        best?.let { CityMapCoordinate(it.latitude, it.longitude) }
    }.getOrNull()
}
