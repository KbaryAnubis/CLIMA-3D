package com.example.maps

import android.content.ComponentCallbacks2
import android.content.Context
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
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaTextMutedOnDark
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

private const val RADAR_RAIN_SOURCE_PREFIX = "clima-radar-rainviewer-source-"
private const val RADAR_RAIN_LAYER_PREFIX = "clima-radar-rainviewer-layer-"

/**
 * Tracks attached RainViewer RasterSource/RasterLayer pairs keyed by stable frame identity
 * so 650ms timeline animation toggles layer visibility in-place without rebuilding sources/layers.
 */
internal class RadarLayerPoolState {
    private val attachedEntries = linkedMapOf<String, Pair<String, String>>() // poolKey -> (sourceId, layerId)
    var activePoolKey: String? = null
        private set

    val attachedCount: Int
        get() = attachedEntries.size

    fun sourceIdFor(poolKey: String): String? = attachedEntries[poolKey]?.first
    fun layerIdFor(poolKey: String): String? = attachedEntries[poolKey]?.second

    fun attachedKeys(): Set<String> = attachedEntries.keys.toSet()

    fun syncPool(
        frames: List<RainViewerRadarFrame>,
        activeFrame: RainViewerRadarFrame?,
        layerStyle: RadarBaseStyle,
        maxRadarZoom: Int,
        addSourceAndLayer: (poolKey: String, sourceId: String, layerId: String, frame: RainViewerRadarFrame, isVisible: Boolean) -> Unit,
        setLayerVisibility: (layerId: String, isVisible: Boolean) -> Unit,
        removeSourceAndLayer: (sourceId: String, layerId: String) -> Unit,
    ): RadarLayerPoolDiff {
        if (layerStyle != RadarBaseStyle.PRECIPITATION_MAP || frames.isEmpty()) {
            var removed = 0
            attachedEntries.values.toList().forEach { (sourceId, layerId) ->
                removeSourceAndLayer(sourceId, layerId)
                removed++
            }
            attachedEntries.clear()
            activePoolKey = null
            return RadarLayerPoolDiff(addedCount = 0, removedCount = removed, visibilityUpdates = 0)
        }

        val desired = linkedMapOf<String, RainViewerRadarFrame>()
        frames.forEach { frame ->
            val provider = frame.toTileProvider()
            if (provider.isValidTemplate()) {
                val key = buildPoolKey(frame, maxRadarZoom)
                desired[key] = frame
            }
        }

        var removed = 0
        val staleKeys = attachedEntries.keys.filter { it !in desired.keys }
        staleKeys.forEach { key ->
            val (sourceId, layerId) = attachedEntries.remove(key) ?: return@forEach
            removeSourceAndLayer(sourceId, layerId)
            removed++
            if (activePoolKey == key) activePoolKey = null
        }

        val targetActiveKey = activeFrame?.let { buildPoolKey(it, maxRadarZoom) }?.takeIf { it in desired.keys }
        var added = 0
        var visibilityUpdates = 0

        desired.forEach { (key, frame) ->
            val shouldBeVisible = key == targetActiveKey
            if (key !in attachedEntries) {
                val suffix = "${frame.timestampSeconds}_${key.hashCode().toUInt()}"
                val sourceId = "$RADAR_RAIN_SOURCE_PREFIX$suffix"
                val layerId = "$RADAR_RAIN_LAYER_PREFIX$suffix"
                addSourceAndLayer(key, sourceId, layerId, frame, shouldBeVisible)
                attachedEntries[key] = sourceId to layerId
                added++
            }
        }

        if (activePoolKey != targetActiveKey) {
            val prevKey = activePoolKey
            if (prevKey != null) {
                attachedEntries[prevKey]?.second?.let { prevLayerId ->
                    setLayerVisibility(prevLayerId, false)
                    visibilityUpdates++
                }
            }
            if (targetActiveKey != null) {
                attachedEntries[targetActiveKey]?.second?.let { nextLayerId ->
                    setLayerVisibility(nextLayerId, true)
                    visibilityUpdates++
                }
            }
            activePoolKey = targetActiveKey
        } else if (added > 0) {
            // Ensure freshly attached batch has coherent visibility even if active key matched prior epoch
            attachedEntries.forEach { (key, pair) ->
                setLayerVisibility(pair.second, key == targetActiveKey)
                visibilityUpdates++
            }
        }

        return RadarLayerPoolDiff(addedCount = added, removedCount = removed, visibilityUpdates = visibilityUpdates)
    }

    fun clearTrackingOnly() {
        attachedEntries.clear()
        activePoolKey = null
    }

    companion object {
        fun buildPoolKey(frame: RainViewerRadarFrame, maxRadarZoom: Int): String =
            "${frame.timestampSeconds}|${frame.tileUrlTemplate}|$maxRadarZoom"
    }
}

internal data class RadarLayerPoolDiff(
    val addedCount: Int,
    val removedCount: Int,
    val visibilityUpdates: Int,
)

/**
 * Native MapLibre + OpenStreetMap map host for the Radar screen that dynamically overlays
 * real historical RainViewer precipitation tiles validated via [MeteorologicalRadarTileProvider].
 *
 * Key guarantees:
 * - Decouples base OSM map from RainViewer so RainViewer errors never hide or stall the base map.
 * - Pools timeline frame RasterSources/RasterLayers and toggles layer visibility on 650ms ticks
 *   without rebuilding sources/layers every frame.
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
    frames: List<RainViewerRadarFrame> = listOfNotNull(activeFrame),
    isOnline: Boolean = true,
    radarErrorMessage: String? = null,
    onRetryReload: () -> Unit = {},
) {
    RadarMapLibreViewImpl(
        center = center,
        zoom = zoom,
        activeFrame = activeFrame,
        layerStyle = layerStyle,
        maxRadarZoom = maxRadarZoom,
        modifier = modifier,
        frames = frames,
        isOnline = isOnline,
        radarErrorMessage = radarErrorMessage,
        onRetryReload = onRetryReload,
    )
}

@Composable
internal fun RadarMapLibreViewImpl(
    center: CityMapCoordinate,
    zoom: Double,
    activeFrame: RainViewerRadarFrame?,
    layerStyle: RadarBaseStyle,
    maxRadarZoom: Int,
    modifier: Modifier = Modifier,
    frames: List<RainViewerRadarFrame> = listOfNotNull(activeFrame),
    isOnline: Boolean = true,
    radarErrorMessage: String? = null,
    onRetryReload: () -> Unit = {},
    surfacePortFactory: ((Context) -> MapViewSurfacePort)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val effectiveFrames = remember(frames, activeFrame) {
        if (frames.isNotEmpty()) frames else listOfNotNull(activeFrame)
    }
    val tileProvider = remember(activeFrame?.tileUrlTemplate) {
        activeFrame?.toTileProvider()
    }

    var reloadNonce by remember { mutableIntStateOf(0) }
    var viewRecreationNonce by remember { mutableIntStateOf(0) }
    var rainLayerError by remember { mutableStateOf<String?>(null) }
    val latestIsOnline by rememberUpdatedState(isOnline)
    val latestOnRetryReload by rememberUpdatedState(onRetryReload)

    val mapLibreReady =
        remember(context, surfacePortFactory) {
            surfacePortFactory != null || MapLibreInitializer.ensureInitialized(context)
        }

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
    val layerPool = remember { RadarLayerPoolState() }

    var mapViewRef by remember { mutableStateOf<MapViewSurfacePort?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreControllerPort?>(null) }
    var loadedStyleRef by remember { mutableStateOf<MapStylePort?>(null) }
    val sessionDisposedRef = remember(viewRecreationNonce) { AtomicBoolean(false) }
    var appliedCameraSignature by remember(viewRecreationNonce) {
        mutableStateOf<Triple<Double, Double, Double>?>(null)
    }
    val latestCenter by rememberUpdatedState(center)
    val latestZoom by rememberUpdatedState(zoom)
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

    fun applyBaseOsmStyle(map: MapLibreControllerPort) {
        if (sessionDisposedRef.get()) return
        val onlineNow = latestIsOnline
        loadedStyleRef = null
        layerPool.clearTrackingOnly()
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
            "Estilo base OSM v8 solicitado (Radar, coords=${latestCenter.formatted()}, zoom=$latestZoom).",
        )
        runCatching {
            map.setStyle(MapStyleSpecRequest.RadarBase) { style ->
                if (sessionDisposedRef.get()) return@setStyle
                layerPool.clearTrackingOnly()
                loadedStyleRef = style
                loadState = stateMachine.onStyleLoaded()
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAP_STYLE,
                    "Estilo base OSM do Radar carregado em ${stateMachine.elapsedMs()}ms (fullyLoaded=${style.isFullyLoaded}, camada=${OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID}).",
                )
            }
        }.onFailure { err ->
            if (sessionDisposedRef.get()) return@onFailure
            val msg = "Erro ao aplicar estilo do Radar: ${err.message ?: err.javaClass.simpleName}"
            ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, msg, err)
            loadState = stateMachine.onStyleFailed(msg, isOnline = latestIsOnline)
        }
    }

    // Feed real OkHttp tile transport telemetry into the Radar map state machine
    LaunchedEffect(tileSnapshot, isOnline) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        loadState = stateMachine.onTileTransportUpdated(tileSnapshot, isOnline)
    }

    // Initial load timeout guard: never freezes in "Carregando mapa..." and never fakes Available
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
                "Tempo limite excedido no mapa Radar (${MapLoadStateMachine.INITIAL_LOAD_TIMEOUT_MS}ms): $cause",
            )
        }
    }

    // Reload base OSM style when connectivity returns or user triggers a soft retry
    LaunchedEffect(mapLibreMapRef, reloadNonce, isOnline) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val map = mapLibreMapRef ?: return@LaunchedEffect
        if (!isOnline && loadedStyleRef == null) {
            loadState = stateMachine.beginAttempt(isOnline = false, hasMapReady = true, tileSnapshot = TileTransportDiagnostics.snapshot.value)
            attemptKey = stateMachine.attemptId
        } else if (reloadNonce > 0 || (isOnline && loadState is MapSurfaceLoadState.ConnectionError)) {
            applyBaseOsmStyle(map)
        }
    }

    // Update camera ONLY when target coordinates or zoom actually change after initial camera setup
    LaunchedEffect(mapLibreMapRef, center.latitude, center.longitude, zoom) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val map = mapLibreMapRef ?: return@LaunchedEffect
        val targetSignature = Triple(center.latitude, center.longitude, zoom)
        if (appliedCameraSignature == targetSignature) return@LaunchedEffect
        appliedCameraSignature = targetSignature
        runCatching {
            map.animateCamera(
                latitude = center.latitude,
                longitude = center.longitude,
                zoom = zoom,
                durationMs = 260,
            )
        }
    }

    // Pooled RainViewer raster layer sync: adds sources/layers once per timeline and toggles visibility on ticks
    LaunchedEffect(loadedStyleRef, loadState.isFailure, effectiveFrames, activeFrame?.timestampSeconds, layerStyle, maxRadarZoom) {
        if (sessionDisposedRef.get()) return@LaunchedEffect
        val style = loadedStyleRef ?: return@LaunchedEffect
        if (!style.isFullyLoaded) return@LaunchedEffect

        val isBaseError =
            loadState is MapSurfaceLoadState.ConnectionError ||
                loadState is MapSurfaceLoadState.StyleError ||
                loadState is MapSurfaceLoadState.RenderError

        runCatching {
            rainLayerError = null
            val targetOpacity = RainViewerPaletteSpec.DEFAULT_OVERLAY_OPACITY
            val diff =
                layerPool.syncPool(
                    frames = if (isBaseError) emptyList() else effectiveFrames,
                    activeFrame = if (isBaseError) null else activeFrame,
                    layerStyle = layerStyle,
                    maxRadarZoom = maxRadarZoom,
                    addSourceAndLayer = { _, sourceId, layerId, frame, isVisible ->
                        style.addRainViewerSourceAndLayer(
                            sourceId = sourceId,
                            layerId = layerId,
                            frame = frame,
                            maxRadarZoom = maxRadarZoom,
                            opacity = targetOpacity,
                            isVisible = isVisible,
                            aboveLayerId = OpenStreetMapTileConfig.RADAR_OSM_LAYER_ID,
                        )
                    },
                    setLayerVisibility = { layerId, isVisible ->
                        style.setLayerVisibility(layerId, isVisible)
                    },
                    removeSourceAndLayer = { sourceId, layerId ->
                        style.removeSourceAndLayer(sourceId, layerId)
                    },
                )

            if (diff.addedCount > 0) {
                ClimaMapLogger.i(
                    ClimaMapLogTags.RAINVIEWER_TILES,
                    "Pool RainViewer sincronizado (adicionados=${diff.addedCount}, removidos=${diff.removedCount}, maxZoom=$maxRadarZoom).",
                )
            } else if (layerStyle == RadarBaseStyle.PRECIPITATION_MAP && effectiveFrames.isEmpty() && !isBaseError) {
                ClimaMapLogger.w(
                    ClimaMapLogTags.RADAR_FRAMES,
                    "Nenhum frame histórico válido do RainViewer disponível; mantendo mapa-base OSM visível.",
                )
            }
        }.onFailure { err ->
            if (sessionDisposedRef.get()) return@LaunchedEffect
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
            var sessionHolder by remember { mutableStateOf<MapViewSessionHandle?>(null) }

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
                    sessionDisposedRef.set(true)
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    context.applicationContext?.unregisterComponentCallbacks(lowMemoryCallbacks)
                    sessionHolder?.pauseAndStopBeforeDetach("onDispose")
                    mapViewRef = null
                    mapLibreMapRef = null
                    loadedStyleRef = null
                    layerPool.clearTrackingOnly()
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize().testTag("radar_maplibre_native_view"),
                factory = { viewContext ->
                    val sessionId = ClimaGraphicsLifecycleDiagnostics.nextSessionId("radar_map")
                    val port = surfacePortFactory?.invoke(viewContext) ?: RealMapViewSurfacePort(viewContext)
                    ClimaMapLogger.i(
                        ClimaMapLogTags.MAP_RENDER,
                        "Criando MapView do Radar (TextureView, OpenGL ES, tentativa=${viewRecreationNonce + 1}).",
                    )
                    ClimaGraphicsLifecycleDiagnostics.logMapViewCreated(
                        screen = "Radar",
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
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Radar", sessionId, port.viewIdentity, "ON_START")
                            },
                            onResume = {
                                port.onResume()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Radar", sessionId, port.viewIdentity, "ON_RESUME")
                            },
                            onPause = {
                                port.onPause()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Radar", sessionId, port.viewIdentity, "ON_PAUSE")
                            },
                            onStop = {
                                port.onStop()
                                ClimaGraphicsLifecycleDiagnostics.logMapLifecycle("Radar", sessionId, port.viewIdentity, "ON_STOP")
                            },
                            onDestroy = { port.onDestroy() },
                            onLowMemory = { port.onLowMemory() },
                        )
                    controller.syncToLifecycleState(lifecycleOwner.lifecycle.currentState)

                    val detachWindowObserver =
                        port.attachWindowObserver { attached, surfaceInfo ->
                            ClimaGraphicsLifecycleDiagnostics.logMapViewAttachState(
                                screen = "Radar",
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
                                    ClimaMapLogger.i(ClimaMapLogTags.MAP_STYLE, "MapView Radar iniciou carregamento do mapa.")
                                },
                                onDidFinishLoadingStyle = {
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_STYLE,
                                        "Callback onDidFinishLoadingStyle disparado (Radar, ${stateMachine.elapsedMs()}ms).",
                                    )
                                },
                                onDidFinishLoadingMap = {
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_RENDER,
                                        "onDidFinishLoadingMap disparado (Radar, ${stateMachine.elapsedMs()}ms).",
                                    )
                                    port.postToUi {
                                        if (sessionDisposedRef.get()) return@postToUi
                                        loadState = stateMachine.onMapLoadedCallback(
                                            TileTransportDiagnostics.snapshot.value,
                                            latestIsOnline,
                                        )
                                    }
                                },
                                onDidFailLoadingMap = { errorMessage ->
                                    val reason = errorMessage.ifBlank { "Erro ao carregar mapa-base OpenStreetMap." }
                                    ClimaMapLogger.e(ClimaMapLogTags.MAP_STYLE, "Falha no estilo/mapa Radar: $reason")
                                    port.postToUi {
                                        if (sessionDisposedRef.get()) return@postToUi
                                        loadState = stateMachine.onStyleFailed(reason, isOnline = latestIsOnline)
                                    }
                                },
                                onWillStartRenderingMap = {
                                    stateMachine.updateSurfaceDiagnostics(port.inspectSurfaceInfo())
                                    ClimaMapLogger.i(
                                        ClimaMapLogTags.MAP_RENDER,
                                        "MapView Radar iniciou pipeline de renderização (${stateMachine.surfaceDiagnostics}).",
                                    )
                                },
                                onFirstFrameRendered = {
                                    if (firstFrameLogged.compareAndSet(false, true)) {
                                        ClimaMapLogger.i(
                                            ClimaMapLogTags.MAP_RENDER,
                                            "Primeiro quadro do MapView Radar renderizado em ${stateMachine.elapsedMs()}ms.",
                                        )
                                        port.postToUi {
                                            if (sessionDisposedRef.get()) return@postToUi
                                            loadState = stateMachine.onFirstFrameRendered(TileTransportDiagnostics.snapshot.value)
                                        }
                                    }
                                },
                                onDidFinishRenderingMap = { fully ->
                                    if (fully && fullyRenderedLogged.compareAndSet(false, true)) {
                                        ClimaMapLogger.i(
                                            ClimaMapLogTags.MAP_RENDER,
                                            "Mapa Radar totalmente renderizado com tiles em ${stateMachine.elapsedMs()}ms.",
                                        )
                                    }
                                    port.postToUi {
                                        if (sessionDisposedRef.get()) return@postToUi
                                        loadState = stateMachine.onMapFinishedRendering(
                                            fully = fully,
                                            tileSnapshot = TileTransportDiagnostics.snapshot.value,
                                        )
                                    }
                                },
                                onRenderError = { reason ->
                                    ClimaMapLogger.e(ClimaMapLogTags.MAP_RENDER, "Erro nativo de renderização no MapView Radar.")
                                    port.postToUi {
                                        if (sessionDisposedRef.get()) return@postToUi
                                        loadState = stateMachine.onRenderError(reason)
                                    }
                                },
                            ),
                        )

                    sessionHolder =
                        MapViewSessionHandle(
                            screenName = "Radar",
                            sessionId = sessionId,
                            surfacePort = port,
                            controller = controller,
                            onDetachListeners = detachListeners,
                            onDetachWindowObserver = detachWindowObserver,
                            onCancelTransitions = {
                                mapLibreMapRef?.cancelTransitions()
                                mapLibreMapRef?.clearMapListeners()
                            },
                        )
                    mapViewRef = port

                    port.getMapAsync { map ->
                        if (sessionDisposedRef.get()) return@getMapAsync
                        mapLibreMapRef = map
                        stateMachine.updateSurfaceDiagnostics(port.inspectSurfaceInfo())
                        loadState = stateMachine.onMapReady(latestIsOnline)
                        ClimaMapLogger.i(
                            ClimaMapLogTags.MAP_RENDER,
                            "getMapAsync pronto no Radar (${stateMachine.surfaceDiagnostics}).",
                        )
                        map.configureUiSettings()

                        val initCenter = latestCenter
                        val initZoom = latestZoom
                        appliedCameraSignature = Triple(initCenter.latitude, initCenter.longitude, initZoom)
                        map.setInitialCamera(
                            latitude = initCenter.latitude,
                            longitude = initCenter.longitude,
                            zoom = initZoom,
                        )

                        applyBaseOsmStyle(map)
                    }
                    port.androidView
                },
                onRelease = {
                    sessionDisposedRef.set(true)
                    sessionHolder?.destroyOnceAfterDetach()
                    sessionHolder = null
                    mapViewRef = null
                    mapLibreMapRef = null
                    loadedStyleRef = null
                    layerPool.clearTrackingOnly()
                    ClimaMapLogger.i(ClimaMapLogTags.MAP_RENDER, "MapView do Radar destruído e listeners removidos.")
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
