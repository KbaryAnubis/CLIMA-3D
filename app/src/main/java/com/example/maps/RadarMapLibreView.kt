package com.example.maps

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.TextureView
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
import kotlinx.coroutines.delay
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
 * - Decouples base OSM map from RainViewer so RainViewer errors never hide or stall the base map.
 * - Tracks Initialization, Style Load, First Frame, and Full Render separately via [MapLoadStateMachine].
 * - Enforces an explicit initial load timeout with real diagnostic cause and clean retry without duplicates.
 * - Uses `textureMode(true)` (TextureView) and symmetrical [MapViewLifecycleController] + listener cleanup.
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
            transparency = RainViewerPaletteSpec.DEFAULT_OVERLAY_TRANSPARENCY,
            baseStyle = layerStyle,
        )
    }

    var reloadNonce by remember { mutableIntStateOf(0) }
    var viewRecreationNonce by remember { mutableIntStateOf(0) }
    var rainLayerError by remember { mutableStateOf<String?>(null) }
    val latestIsOnline by rememberUpdatedState(isOnline)
    val latestOnRetryReload by rememberUpdatedState(onRetryReload)

    val mapLibreReady = remember(context) { MapLibreInitializer.ensureInitialized(context) }

    val radarUnavailableNotice =
        remember(layerStyle, activeFrame, radarErrorMessage, rainLayerError, isOnline) {
            when {
                !isOnline -> null
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
                !isOnline ->
                    MapSurfaceLoadState.ConnectionError("Sem conexão com a internet para carregar os tiles do OpenStreetMap.")
                !MapLibreInitializer.isRobolectricEnvironment() && MapLibreInitializer.lastInitError != null ->
                    MapSurfaceLoadState.RenderError(MapLibreInitializer.lastInitError.orEmpty())
                else -> MapSurfaceLoadState.Available
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
                    if (jvmState == MapSurfaceLoadState.Available) {
                        Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 46.dp)
                    } else {
                        Modifier.align(Alignment.Center)
                    },
            )
        }
        return
    }

    val stateMachine = remember { MapLoadStateMachine(screenName = "Radar") }
    val tileSnapshot by TileTransportDiagnostics.snapshot.collectAsState()

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleRef by remember { mutableStateOf<Style?>(null) }
    var activeRainSourceId by remember { mutableStateOf<String?>(null) }
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

    fun applyBaseOsmStyle(map: MapLibreMap) {
        val onlineNow = latestIsOnline
        loadedStyleRef = null
        activeRainSourceId = null
        rainLayerError = null
        loadState = stateMachine.beginAttempt(
            isOnline = onlineNow,
            hasMapReady = true,
            tileSnapshot = TileTransportDiagnostics.snapshot.value,
        )
        attemptKey = stateMachine.attemptId
        if (!onlineNow) {
            ClimaMapLogger.w(ClimaMapLogTags.MAP_STYLE, "Radar sem conexão: aguardando rede para aplicar estilo OSM.")
            return
        }
        loadState = stateMachine.onStyleRequested(isOnline = true)
        ClimaMapLogger.i(
            ClimaMapLogTags.MAP_STYLE,
            "Estilo base OSM v8 solicitado (Radar, coords=${center.formatted()}, zoom=$zoom).",
        )
        runCatching {
            val styleBuilder = OpenStreetMapTileConfig.buildRadarBaseStyleBuilder()
            map.setStyle(styleBuilder) { style ->
                loadedStyleRef = style
                loadState = stateMachine.onStyleLoaded()
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAP_STYLE,
                    "Estilo base OSM do Radar carregado em ${stateMachine.elapsedMs()}ms (fullyLoaded=${style.isFullyLoaded}, camada=${OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID}).",
                )
            }
        }.onFailure { err ->
            val msg = "Erro ao aplicar estilo do Radar: ${err.message ?: err.javaClass.simpleName}"
            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, msg, err)
            loadState = stateMachine.onStyleFailed(msg, isOnline = latestIsOnline)
        }
    }

    // Feed real OkHttp tile transport telemetry into the Radar map state machine
    LaunchedEffect(tileSnapshot, isOnline) {
        loadState = stateMachine.onTileTransportUpdated(tileSnapshot, isOnline)
    }

    // Initial load timeout guard: never freezes in "Carregando mapa..." and never fakes Available
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
                "Tempo limite excedido no mapa Radar (${MapLoadStateMachine.INITIAL_LOAD_TIMEOUT_MS}ms): $cause",
            )
        }
    }

    // Reload base OSM style when connectivity returns or user triggers a soft retry
    LaunchedEffect(mapLibreMapRef, reloadNonce, isOnline) {
        val map = mapLibreMapRef ?: return@LaunchedEffect
        if (!isOnline && loadedStyleRef == null) {
            loadState = stateMachine.beginAttempt(isOnline = false, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
            attemptKey = stateMachine.attemptId
        } else if (reloadNonce > 0 || (isOnline && loadState is MapSurfaceLoadState.ConnectionError)) {
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

    // Dynamically swap RainViewer raster layer AFTER base OSM style is loaded; never attaches on failed base map
    LaunchedEffect(loadedStyleRef, loadState.isFailure, activeFrame?.tileUrlTemplate, layerStyle, maxRadarZoom) {
        val style = loadedStyleRef ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect
        if (loadState is MapSurfaceLoadState.ConnectionError ||
            loadState is MapSurfaceLoadState.StyleError ||
            loadState is MapSurfaceLoadState.RenderError
        ) {
            runCatching {
                style.removeLayer(RADAR_RAIN_LAYER_ID)
                activeRainSourceId?.let { style.removeSource(it) }
                activeRainSourceId = null
            }
            return@LaunchedEffect
        }

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

    Box(modifier = modifier.fillMaxSize()) {
        key(viewRecreationNonce) {
            var sessionHolder by remember { mutableStateOf<RadarMapViewSession?>(null) }

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
                    activeRainSourceId = null
                    ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "MapView do Radar destruído e listeners removidos.")
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("radar_maplibre_native_view"),
                factory = { viewContext ->
                    val options = MapLibreInitializer.createTextureMapOptions(viewContext)
                    ClimaMapLogger.i(
                        ClimaMapLogTags.MAP_RENDER,
                        "Criando MapView do Radar (TextureView, OpenGL ES, tentativa=${viewRecreationNonce + 1}).",
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
                            ClimaMapLogger.i(ClimaMapLogTags.MAP_STYLE, "MapView Radar iniciou carregamento do mapa.")
                        }
                    val styleLoadedListener =
                        MapView.OnDidFinishLoadingStyleListener {
                            ClimaMapLogger.i(
                                ClimaMapLogTags.MAP_STYLE,
                                "Callback onDidFinishLoadingStyle disparado (Radar, ${stateMachine.elapsedMs()}ms).",
                            )
                        }
                    val mapLoadedListener =
                        MapView.OnDidFinishLoadingMapListener {
                            ClimaMapLogger.i(
                                ClimaMapLogTags.MAP_RENDER,
                                "onDidFinishLoadingMap disparado (Radar, ${stateMachine.elapsedMs()}ms).",
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
                            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, "Falha no estilo/mapa Radar: $reason")
                            view.post {
                                loadState = stateMachine.onStyleFailed(reason, isOnline = latestIsOnline)
                            }
                        }
                    val willStartRenderingMapListener =
                        MapView.OnWillStartRenderingMapListener {
                            stateMachine.updateSurfaceDiagnostics(inspectSurfaceInfo(view))
                            ClimaMapLogger.i(
                                ClimaMapLogTags.MAP_RENDER,
                                "MapView Radar iniciou pipeline de renderização (${stateMachine.surfaceDiagnostics}).",
                            )
                        }
                    val frameRenderedListener =
                        MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                            if (firstFrameLogged.compareAndSet(false, true)) {
                                ClimaMapLogger.i(
                                    ClimaMapLogTags.MAP_RENDER,
                                    "Primeiro quadro do MapView Radar renderizado em ${stateMachine.elapsedMs()}ms.",
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
                                        "Mapa Radar totalmente renderizado com tiles em ${stateMachine.elapsedMs()}ms.",
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
                            ClimaMapLogger.e(ClimaMapLogTags.MAP_RENDER, "Erro nativo de renderização no MapView Radar.")
                            view.post {
                                loadState = stateMachine.onRenderError("Erro nativo de renderização OpenGL/TextureView no Radar.")
                            }
                        }
                    val shaderErrorListener =
                        MapView.OnShaderCompileFailedListener { id, type, _ ->
                            ClimaMapLogger.e(ClimaMapLogTags.MAP_RENDER, "Falha ao compilar shader MapLibre Radar (id=$id, type=$type).")
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
                        RadarMapViewSession(
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
                            "getMapAsync pronto no Radar (${stateMachine.surfaceDiagnostics}).",
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
                                .target(LatLng(center.latitude, center.longitude))
                                .zoom(zoom)
                                .build()

                        applyBaseOsmStyle(map)
                    }
                    view
                },
            )
        }

        val effectiveNotice =
            if (loadState == MapSurfaceLoadState.Available) radarUnavailableNotice else null

        MapSurfaceStatusOverlay(
            loadState = loadState,
            secondaryNotice = effectiveNotice,
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
            testTagPrefix = "radar_map",
            modifier =
                when {
                    loadState == MapSurfaceLoadState.Available ->
                        Modifier.align(Alignment.BottomCenter).padding(start = 10.dp, end = 10.dp, bottom = 46.dp)
                    loadState.isLoading ->
                        Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    else ->
                        Modifier.align(Alignment.Center)
                },
        )
    }
}

private class RadarMapViewSession(
    val mapView: MapView,
    val controller: MapViewLifecycleController,
    private val onDetachListeners: () -> Unit,
) {
    fun dispose() {
        runCatching { onDetachListeners() }
        controller.destroy()
    }
}
