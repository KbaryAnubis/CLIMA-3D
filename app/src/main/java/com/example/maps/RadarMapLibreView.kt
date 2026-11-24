package com.example.maps

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.RainViewerRadarFrame
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaTextMutedOnDark
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

private const val RADAR_RAIN_SOURCE_PREFIX = "clima-radar-rainviewer-source-"
private const val RADAR_RAIN_LAYER_ID = "clima-radar-rainviewer-layer"

/**
 * Native MapLibre + OpenStreetMap map host for the Radar screen that dynamically overlays
 * real historical RainViewer precipitation tiles validated via [MeteorologicalRadarTileProvider].
 *
 * Key guarantees:
 * - Uses valid MapLibre Style Spec v8 JSON with OpenStreetMap base tiles decoupled from RainViewer.
 * - Uses `textureMode(true)` (TextureView) for reliable Jetpack Compose compositing & tab switching.
 * - Uses [MapViewLifecycleController] + [ComponentCallbacks2] for exact, non-duplicated lifecycle calls.
 * - Never hides the OSM base map when RainViewer frames are missing or fail; displays transparent status.
 */
@Composable
fun RadarMapLibreView(
    center: CityMapCoordinate,
    zoom: Double,
    activeFrame: RainViewerRadarFrame?,
    layerStyle: RadarBaseStyle,
    maxRadarZoom: Int,
    modifier: Modifier = Modifier,
    isOnline: Boolean = true,
    radarErrorMessage: String? = null,
    onRetryReload: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val tileProvider = remember(activeFrame?.tileUrlTemplate) {
        activeFrame?.toTileProvider()
    }
    val layerSpec = remember(activeFrame?.tileUrlTemplate, layerStyle) {
        activeFrame?.toLayerSpec(
            transparency = 0.28f,
            baseStyle = layerStyle,
        )
    }

    var reloadNonce by remember { mutableIntStateOf(0) }
    var rainLayerError by remember { mutableStateOf<String?>(null) }

    val mapLibreReady = remember(context) { MapLibreInitializer.ensureInitialized(context) }

    val radarUnavailableNotice =
        remember(layerStyle, activeFrame, radarErrorMessage, rainLayerError, isOnline) {
            when {
                !isOnline -> null // Handled by offline state overlay
                layerStyle != RadarBaseStyle.PRECIPITATION_MAP -> null
                rainLayerError != null -> "Camada de radar indisponível: $rainLayerError"
                activeFrame == null && !radarErrorMessage.isNullOrBlank() -> "Radar indisponível: $radarErrorMessage"
                activeFrame == null -> "Radar indisponível: nenhum quadro histórico do RainViewer no momento."
                else -> null
            }
        }

    if (!mapLibreReady) {
        val jvmState =
            when {
                !isOnline -> MapSurfaceLoadState.Offline
                !MapLibreInitializer.isRobolectricEnvironment() && MapLibreInitializer.lastInitError != null ->
                    MapSurfaceLoadState.Error(MapLibreInitializer.lastInitError.orEmpty())
                else -> MapSurfaceLoadState.Loaded
            }

        Box(
            modifier = modifier.fillMaxSize().testTag("radar_maplibre_jvm_surface"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "MapLibre Native + OpenStreetMap (${center.formatted()}) • Zoom ${zoom.toInt()}",
                    color = ClimaTextMutedOnDark,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                if (layerStyle == RadarBaseStyle.PRECIPITATION_MAP && tileProvider != null && tileProvider.isValidTemplate()) {
                    Text(
                        text = "Camada RainViewer ativa: ${activeFrame?.formattedLocalTime.orEmpty()}",
                        color = ClimaAccentCyan,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            MapSurfaceStatusOverlay(
                loadState = jvmState,
                secondaryNotice = radarUnavailableNotice,
                onRetry = {
                    reloadNonce++
                    onRetryReload()
                },
                testTagPrefix = "radar_map",
                modifier =
                    if (jvmState == MapSurfaceLoadState.Loaded) {
                        Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 46.dp)
                    } else {
                        Modifier.align(Alignment.Center)
                    },
            )
        }
        return
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lifecycleControllerRef by remember { mutableStateOf<MapViewLifecycleController?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleRef by remember { mutableStateOf<Style?>(null) }
    var activeRainSourceId by remember { mutableStateOf<String?>(null) }
    var loadState by remember {
        mutableStateOf<MapSurfaceLoadState>(
            if (isOnline) MapSurfaceLoadState.Loading else MapSurfaceLoadState.Offline,
        )
    }

    fun applyBaseOsmStyle(map: MapLibreMap) {
        loadState = if (isOnline) MapSurfaceLoadState.Loading else MapSurfaceLoadState.Offline
        loadedStyleRef = null
        activeRainSourceId = null
        rainLayerError = null
        ClimaMapLogger.i(ClimaMapLogTags.MAP_STYLE, "Carregando estilo base OSM v8 para Radar.")
        runCatching {
            val styleBuilder = OpenStreetMapTileConfig.buildRadarBaseStyleBuilder()
            map.setStyle(styleBuilder) { style ->
                loadedStyleRef = style
                loadState = MapSurfaceLoadState.Loaded
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAP_STYLE,
                    "Estilo base OSM do Radar carregado com sucesso (camada=${OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID}).",
                )
            }
        }.onFailure { err ->
            val msg = "Erro ao aplicar estilo do Radar: ${err.message ?: err.javaClass.simpleName}"
            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, msg, err)
            loadState = MapSurfaceLoadState.Error(msg)
        }
    }

    // Reload base OSM style when connectivity returns or user explicitly taps Retry
    LaunchedEffect(mapLibreMapRef, reloadNonce, isOnline) {
        val map = mapLibreMapRef ?: return@LaunchedEffect
        if (!isOnline && loadedStyleRef == null) {
            loadState = MapSurfaceLoadState.Offline
        } else if (reloadNonce > 0 || (isOnline && loadState is MapSurfaceLoadState.Offline)) {
            applyBaseOsmStyle(map)
        }
    }

    // Update camera when city or zoom changes
    LaunchedEffect(mapLibreMapRef, center.latitude, center.longitude, zoom) {
        val map = mapLibreMapRef ?: return@LaunchedEffect
        val position =
            CameraPosition.Builder()
                .target(LatLng(center.latitude, center.longitude))
                .zoom(zoom)
                .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 280)
    }

    // Dynamically swap RainViewer raster layer AFTER base OSM style is loaded; never hides OSM base
    LaunchedEffect(loadedStyleRef, activeFrame?.tileUrlTemplate, layerStyle, maxRadarZoom) {
        val style = loadedStyleRef ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        runCatching {
            style.removeLayer(RADAR_RAIN_LAYER_ID)
            activeRainSourceId?.let { previousId -> style.removeSource(previousId) }
            activeRainSourceId = null
            rainLayerError = null

            if (layerStyle == RadarBaseStyle.PRECIPITATION_MAP && tileProvider != null && tileProvider.isValidTemplate() && layerSpec != null) {
                val sourceId = "$RADAR_RAIN_SOURCE_PREFIX${activeFrame?.timestampSeconds ?: 0L}"
                val tileSet =
                    TileSet("2.2", tileProvider.tileUrlTemplate).apply {
                        minZoom = 0f
                        maxZoom = maxRadarZoom.toFloat()
                        attribution = RainViewerRadarFrame.ATTRIBUTION
                    }
                style.addSource(RasterSource(sourceId, tileSet, tileProvider.tileWidthPx))
                val rainLayer =
                    RasterLayer(RADAR_RAIN_LAYER_ID, sourceId).withProperties(
                        rasterOpacity(layerSpec.opacity),
                    )
                if (style.getLayer(OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID) != null) {
                    style.addLayerAbove(rainLayer, OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID)
                } else {
                    style.addLayer(rainLayer)
                }
                activeRainSourceId = sourceId
                ClimaMapLogger.i(
                    ClimaMapLogTags.RAINVIEWER_TILES,
                    "Camada RainViewer aplicada acima do OSM base (frame=${activeFrame?.formattedLocalTime}, maxZoom=$maxRadarZoom).",
                )
            } else if (layerStyle == RadarBaseStyle.PRECIPITATION_MAP) {
                ClimaMapLogger.w(
                    ClimaMapLogTags.RADAR_FRAMES,
                    "Nenhum frame histórico válido do RainViewer disponível; mantendo mapa-base OSM visível.",
                )
            }
        }.onFailure { err ->
            val msg = err.message ?: err.javaClass.simpleName
            rainLayerError = msg
            ClimaMapLogger.e(
                ClimaMapLogTags.RAINVIEWER_TILES,
                "Falha ao aplicar camada RainViewer (mapa-base OSM preservado): $msg",
                err,
            )
        }
    }

    // Symmetrical, non-duplicated Compose lifecycle & low-memory bridge for MapView
    DisposableEffect(lifecycleOwner, lifecycleControllerRef, context) {
        val controller = lifecycleControllerRef
        val observer =
            LifecycleEventObserver { _, event ->
                controller?.onLifecycleEvent(event)
            }
        val lowMemoryCallbacks =
            object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {
                    controller?.dispatchLowMemory()
                }
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        controller?.dispatchLowMemory()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        context.applicationContext?.registerComponentCallbacks(lowMemoryCallbacks)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.applicationContext?.unregisterComponentCallbacks(lowMemoryCallbacks)
            controller?.destroy()
            mapViewRef = null
            mapLibreMapRef = null
            loadedStyleRef = null
            activeRainSourceId = null
            ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "MapView do Radar liberado com segurança no onDispose.")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("radar_maplibre_native_view"),
            factory = { viewContext ->
                val options = MapLibreInitializer.createTextureMapOptions(viewContext)
                ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "Criando MapView do Radar em modo TextureView (OpenGL ES).")
                val view = MapView(viewContext, options)
                val firstFrameLogged = AtomicBoolean(false)

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
                lifecycleControllerRef = controller
                mapViewRef = view

                view.addOnDidFailLoadingMapListener { errorMessage ->
                    val reason = errorMessage.ifBlank { "Erro ao carregar mapa-base OpenStreetMap." }
                    ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, "Falha no carregamento do mapa Radar: $reason")
                    loadState = if (!isOnline) MapSurfaceLoadState.Offline else MapSurfaceLoadState.Error(reason)
                }
                view.addOnDidFinishLoadingStyleListener {
                    ClimaMapLogger.i(ClimaMapLogTags.MAP_STYLE, "Callback onDidFinishLoadingStyle disparado (Radar).")
                }
                view.addOnDidFinishLoadingMapListener {
                    ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "Mapa Radar carregado completamente.")
                    loadState = MapSurfaceLoadState.Loaded
                }
                view.addOnDidFinishRenderingFrameListener { _ ->
                    if (firstFrameLogged.compareAndSet(false, true)) {
                        ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "Primeiro frame do MapView Radar renderizado.")
                    }
                }

                view.getMapAsync { map ->
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
                            .target(LatLng(center.latitude, center.longitude))
                            .zoom(zoom)
                            .build()

                    applyBaseOsmStyle(map)
                }
                view
            },
        )

        MapSurfaceStatusOverlay(
            loadState = loadState,
            secondaryNotice = radarUnavailableNotice,
            onRetry = {
                reloadNonce++
                onRetryReload()
            },
            testTagPrefix = "radar_map",
            modifier =
                if (loadState == MapSurfaceLoadState.Loaded) {
                    Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 46.dp)
                } else {
                    Modifier.align(Alignment.Center)
                },
        )
    }
}
