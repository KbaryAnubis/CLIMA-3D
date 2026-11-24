package com.example.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.Lifecycle
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
 * and OpenStreetMap raster tiles (no WebView, no HTML, no Google Maps Platform, no API key).
 *
 * Provides interactive map navigation, zoom controls, tap-to-place markers, saved markers,
 * OpenStreetMap attribution, and runtime-permission-safe current GPS location support.
 */
@Composable
fun CityMapLibreSection(
    modifier: Modifier = Modifier,
    initialState: CityMapUiState = CityMapUiState(),
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
 */
@Composable
fun CityMapLibreView(
    state: CityMapUiState,
    onMapTap: (CityMapCoordinate) -> Unit,
    onCameraGestureSettled: (CityMapCoordinate, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnCameraSettled by rememberUpdatedState(onCameraGestureSettled)

    val mapLibreReady = remember(context) { MapLibreInitializer.ensureInitialized(context) }
    if (!mapLibreReady) {
        // Host JVM test environments without native GL binaries render a lightweight placeholder
        Box(
            modifier = modifier.fillMaxSize().testTag("city_maplibre_jvm_surface"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "MapLibre Native + OpenStreetMap (${state.cameraCenter.formatted()})",
                color = ClimaTextMutedOnDark,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleRef by remember { mutableStateOf<Style?>(null) }

    val savedMarkersJson =
        remember(state.savedMarkers) { CityMapGeoJsonBuilder.buildFeatureCollection(state.savedMarkers) }
    val selectedMarkerJson =
        remember(state.selectedMarker) {
            CityMapGeoJsonBuilder.buildFeatureCollection(listOf(state.selectedMarker))
        }

    // Update marker GeoJSON layers whenever marker state changes
    LaunchedEffect(loadedStyleRef, savedMarkersJson, selectedMarkerJson) {
        val style = loadedStyleRef ?: return@LaunchedEffect
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

    // Forward Compose lifecycle to MapLibre MapView
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mapView = mapViewRef
        val observer =
            LifecycleEventObserver { _, event ->
                if (mapView == null) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier.testTag("city_maplibre_native_view"),
        factory = { viewContext ->
            MapView(viewContext).apply {
                onCreate(null)
                onStart()
                onResume()
                mapViewRef = this

                getMapAsync { map ->
                    mapLibreMapRef = map
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

                    val styleBuilder =
                        OpenStreetMapTileConfig.buildStyleBuilder(
                            savedMarkersGeoJson = savedMarkersJson,
                            selectedMarkerGeoJson = selectedMarkerJson,
                        )
                    map.setStyle(styleBuilder) { style ->
                        loadedStyleRef = style
                        if (hasLocationPermission(viewContext)) {
                            enableMapLibreLocationComponent(viewContext, map, style)
                        }
                    }

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
            }
        },
    )
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
