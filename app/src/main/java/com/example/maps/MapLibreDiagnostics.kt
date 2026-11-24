package com.example.maps

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.example.media.ClimaUiIcons
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaAlertHighRed
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.WeatherGlassSpecs
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Technical log tags requested for diagnosing MapLibre initialization, style loading,
 * OpenStreetMap tile delivery, rendering lifecycle, and RainViewer radar overlays.
 */
object ClimaMapLogTags {
    const val MAPLIBRE_INIT = "MAPLIBRE_INIT"
    const val MAP_STYLE = "MAP_STYLE"
    const val OSM_TILES = "OSM_TILES"
    const val MAP_RENDER = "MAP_RENDER"
    const val RADAR_FRAMES = "RADAR_FRAMES"
    const val RAINVIEWER_TILES = "RAINVIEWER_TILES"
}

/**
 * Safe Android logger that never crashes in unit-test environments without Log stubs.
 */
object ClimaMapLogger {
    fun i(tag: String, message: String) {
        runCatching { Log.i(tag, message) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }
}

/**
 * Explicit lifecycle stage of a MapLibre surface so initialization, style loading,
 * tile/frame rendering, errors, and timeouts are never conflated.
 */
enum class MapLoadStage(val labelPt: String) {
    INITIALIZING("Inicializando mapa"),
    LOADING_STYLE("Carregando estilo"),
    LOADING_MAP("Carregando mapa"),
    AVAILABLE("Mapa disponível"),
    CONNECTION_ERROR("Falha de conexão"),
    STYLE_ERROR("Falha de estilo"),
    RENDER_ERROR("Falha de renderização"),
    TIMED_OUT("Tempo de carregamento excedido"),
}

/**
 * Independent, coherent UI state for a native MapLibre surface.
 */
@Immutable
sealed interface MapSurfaceLoadState {
    data object Initializing : MapSurfaceLoadState
    data object LoadingStyle : MapSurfaceLoadState
    data object LoadingMap : MapSurfaceLoadState
    data object Available : MapSurfaceLoadState
    data class ConnectionError(val reason: String) : MapSurfaceLoadState
    data class StyleError(val reason: String) : MapSurfaceLoadState
    data class RenderError(val reason: String) : MapSurfaceLoadState
    data class TimedOut(val stalledStage: MapLoadStage, val knownCause: String?) : MapSurfaceLoadState

    val stage: MapLoadStage
        get() =
            when (this) {
                Initializing -> MapLoadStage.INITIALIZING
                LoadingStyle -> MapLoadStage.LOADING_STYLE
                LoadingMap -> MapLoadStage.LOADING_MAP
                Available -> MapLoadStage.AVAILABLE
                is ConnectionError -> MapLoadStage.CONNECTION_ERROR
                is StyleError -> MapLoadStage.STYLE_ERROR
                is RenderError -> MapLoadStage.RENDER_ERROR
                is TimedOut -> MapLoadStage.TIMED_OUT
            }

    val isLoading: Boolean
        get() = this is Initializing || this is LoadingStyle || this is LoadingMap

    val isFailure: Boolean
        get() = this is ConnectionError || this is StyleError || this is RenderError || this is TimedOut
}

enum class TileTransportFailureKind {
    NONE,
    DNS_OR_NETWORK,
    TIMEOUT,
    TLS,
    HTTP_BLOCKED_OR_RATE_LIMIT,
    HTTP_SERVER_OR_NOT_FOUND,
    UNKNOWN,
}

@Immutable
data class TileTransportSnapshot(
    val osmRequestCount: Int = 0,
    val osmSuccessCount: Int = 0,
    val osmErrorCount: Int = 0,
    val lastOsmStatusCode: Int? = null,
    val lastOsmError: String? = null,
    val lastOsmFailureKind: TileTransportFailureKind = TileTransportFailureKind.NONE,
    val lastOsmDurationMs: Long? = null,
    val lastOsmUrl: String? = null,
    val rainViewerRequestCount: Int = 0,
    val rainViewerSuccessCount: Int = 0,
    val rainViewerErrorCount: Int = 0,
    val lastRainViewerStatusCode: Int? = null,
    val lastRainViewerError: String? = null,
)

/**
 * Thread-safe telemetry recorder wired directly into MapLibre's OkHttp tile interceptor.
 * Gives Radar and Cities surfaces real visibility into tile requests, HTTP codes, DNS/TLS
 * failures, and request timings.
 */
object TileTransportDiagnostics {
    private val _snapshot = MutableStateFlow(TileTransportSnapshot())
    val snapshot: StateFlow<TileTransportSnapshot> = _snapshot.asStateFlow()

    fun reset() {
        _snapshot.value = TileTransportSnapshot()
    }

    fun recordRequestStart(isRainViewer: Boolean, sanitizedUrl: String) {
        _snapshot.update { cur ->
            if (isRainViewer) {
                cur.copy(rainViewerRequestCount = cur.rainViewerRequestCount + 1)
            } else {
                cur.copy(
                    osmRequestCount = cur.osmRequestCount + 1,
                    lastOsmUrl = sanitizedUrl,
                )
            }
        }
    }

    fun recordHttpResponse(
        isRainViewer: Boolean,
        statusCode: Int,
        durationMs: Long,
        sanitizedUrl: String,
    ) {
        val success = statusCode in 200..299
        _snapshot.update { cur ->
            if (isRainViewer) {
                if (success) {
                    cur.copy(
                        rainViewerSuccessCount = cur.rainViewerSuccessCount + 1,
                        lastRainViewerStatusCode = statusCode,
                        lastRainViewerError = null,
                    )
                } else {
                    cur.copy(
                        rainViewerErrorCount = cur.rainViewerErrorCount + 1,
                        lastRainViewerStatusCode = statusCode,
                        lastRainViewerError = classifyHttpStatusMessage(statusCode, "RainViewer"),
                    )
                }
            } else {
                if (success) {
                    cur.copy(
                        osmSuccessCount = cur.osmSuccessCount + 1,
                        lastOsmStatusCode = statusCode,
                        lastOsmError = null,
                        lastOsmFailureKind = TileTransportFailureKind.NONE,
                        lastOsmDurationMs = durationMs,
                        lastOsmUrl = sanitizedUrl,
                    )
                } else {
                    val kind =
                        if (statusCode == 403 || statusCode == 429) {
                            TileTransportFailureKind.HTTP_BLOCKED_OR_RATE_LIMIT
                        } else {
                            TileTransportFailureKind.HTTP_SERVER_OR_NOT_FOUND
                        }
                    cur.copy(
                        osmErrorCount = cur.osmErrorCount + 1,
                        lastOsmStatusCode = statusCode,
                        lastOsmError = classifyHttpStatusMessage(statusCode, "OpenStreetMap"),
                        lastOsmFailureKind = kind,
                        lastOsmDurationMs = durationMs,
                        lastOsmUrl = sanitizedUrl,
                    )
                }
            }
        }
    }

    fun recordTransportFailure(
        isRainViewer: Boolean,
        error: Throwable,
        durationMs: Long,
        sanitizedUrl: String,
    ) {
        val (kind, message) = classifyThrowable(error)
        _snapshot.update { cur ->
            if (isRainViewer) {
                cur.copy(
                    rainViewerErrorCount = cur.rainViewerErrorCount + 1,
                    lastRainViewerError = message,
                )
            } else {
                cur.copy(
                    osmErrorCount = cur.osmErrorCount + 1,
                    lastOsmError = message,
                    lastOsmFailureKind = kind,
                    lastOsmDurationMs = durationMs,
                    lastOsmUrl = sanitizedUrl,
                )
            }
        }
    }

    fun classifyHttpStatusMessage(statusCode: Int, provider: String): String =
        when (statusCode) {
            403 -> "HTTP 403: servidor $provider recusou a requisição de tile (verifique User-Agent)."
            404 -> "HTTP 404: tile não encontrado no servidor $provider."
            429 -> "HTTP 429: limite de requisições atingido no servidor $provider."
            in 500..599 -> "HTTP $statusCode: erro interno temporário no servidor $provider."
            else -> "HTTP $statusCode ao baixar tile do $provider."
        }

    fun classifyThrowable(error: Throwable): Pair<TileTransportFailureKind, String> =
        when (error) {
            is UnknownHostException ->
                TileTransportFailureKind.DNS_OR_NETWORK to
                    "Falha DNS ao resolver servidor de tiles (${error.message ?: "sem resposta"})."
            is SocketTimeoutException ->
                TileTransportFailureKind.TIMEOUT to
                    "Timeout de rede ao baixar tiles (${error.message ?: "tempo esgotado"})."
            is SSLException ->
                TileTransportFailureKind.TLS to
                    "Falha TLS/HTTPS na conexão com servidor de tiles (${error.message ?: error.javaClass.simpleName})."
            is ConnectException ->
                TileTransportFailureKind.DNS_OR_NETWORK to
                    "Conexão recusada ou indisponível com o servidor de tiles."
            is IOException ->
                TileTransportFailureKind.DNS_OR_NETWORK to
                    "Erro de E/S de rede: ${error.message ?: error.javaClass.simpleName}."
            else ->
                TileTransportFailureKind.UNKNOWN to
                    "Erro ao requisitar tile: ${error.message ?: error.javaClass.simpleName}."
        }
}

/**
 * Pure, deterministic state coordinator tracking MapLibre lifecycle milestones independently:
 * 1. MapView / Surface ready (`getMapAsync`)
 * 2. Style requested & loaded (`onStyleLoaded`) — does NOT mark Available
 * 3. First frame rendered (`onDidFinishRenderingFrame`) — logged separately
 * 4. Full map render with tiles (`onDidFinishRenderingMap(fully = true)` or `onDidFinishLoadingMap` + frame)
 * 5. Timeouts with real diagnostic cause and support for late completion & clean retry.
 */
class MapLoadStateMachine(
    private val screenName: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var state: MapSurfaceLoadState = MapSurfaceLoadState.Initializing
        private set

    var styleLoaded: Boolean = false
        private set

    var firstFrameRendered: Boolean = false
        private set

    var mapLoadedCallbackFired: Boolean = false
        private set

    var mapFullyRendered: Boolean = false
        private set

    var attemptId: Int = 0
        private set

    private var attemptStartedAtMs: Long = clock()
    private var baselineOsmRequestCount: Int = 0
    private var baselineOsmSuccessCount: Int = 0
    private var baselineOsmErrorCount: Int = 0
    var surfaceDiagnostics: String = "superfície ainda não medida"
        private set

    fun updateSurfaceDiagnostics(info: String) {
        if (info.isNotBlank()) {
            surfaceDiagnostics = info
        }
    }

    fun elapsedMs(): Long = (clock() - attemptStartedAtMs).coerceAtLeast(0L)

    fun beginAttempt(
        isOnline: Boolean,
        hasMapReady: Boolean,
        tileSnapshot: TileTransportSnapshot = TileTransportSnapshot(),
    ): MapSurfaceLoadState {
        attemptId++
        attemptStartedAtMs = clock()
        styleLoaded = false
        firstFrameRendered = false
        mapLoadedCallbackFired = false
        mapFullyRendered = false
        baselineOsmRequestCount = tileSnapshot.osmRequestCount
        baselineOsmSuccessCount = tileSnapshot.osmSuccessCount
        baselineOsmErrorCount = tileSnapshot.osmErrorCount

        state =
            when {
                !isOnline -> MapSurfaceLoadState.ConnectionError("Sem conexão com a internet para carregar os tiles do OpenStreetMap.")
                hasMapReady -> MapSurfaceLoadState.LoadingStyle
                else -> MapSurfaceLoadState.Initializing
            }
        return state
    }

    fun onMapReady(isOnline: Boolean): MapSurfaceLoadState {
        if (!isOnline) {
            state = MapSurfaceLoadState.ConnectionError("Sem conexão com a internet.")
            return state
        }
        if (state is MapSurfaceLoadState.Initializing ||
            (state as? MapSurfaceLoadState.TimedOut)?.stalledStage == MapLoadStage.INITIALIZING
        ) {
            state = MapSurfaceLoadState.LoadingStyle
        }
        return state
    }

    fun onStyleRequested(isOnline: Boolean): MapSurfaceLoadState {
        if (!isOnline) {
            state = MapSurfaceLoadState.ConnectionError("Sem conexão com a internet para carregar o mapa.")
            return state
        }
        styleLoaded = false
        mapLoadedCallbackFired = false
        mapFullyRendered = false
        state = MapSurfaceLoadState.LoadingStyle
        return state
    }

    fun onStyleLoaded(): MapSurfaceLoadState {
        styleLoaded = true
        // Loading the Style JSON only means the cartographic style tree was built — tiles and frames may still be pending.
        if (state !is MapSurfaceLoadState.ConnectionError &&
            state !is MapSurfaceLoadState.RenderError
        ) {
            state =
                if (mapFullyRendered) {
                    MapSurfaceLoadState.Available
                } else {
                    MapSurfaceLoadState.LoadingMap
                }
        }
        return state
    }

    fun onStyleFailed(reason: String, isOnline: Boolean): MapSurfaceLoadState {
        val cleanReason = reason.ifBlank { "Falha ao carregar o estilo cartográfico v8." }
        state =
            if (!isOnline || cleanReason.contains("network", ignoreCase = true) ||
                cleanReason.contains("host", ignoreCase = true) ||
                cleanReason.contains("timeout", ignoreCase = true)
            ) {
                MapSurfaceLoadState.ConnectionError(cleanReason)
            } else {
                MapSurfaceLoadState.StyleError(cleanReason)
            }
        return state
    }

    fun onFirstFrameRendered(tileSnapshot: TileTransportSnapshot = TileTransportSnapshot()): MapSurfaceLoadState {
        firstFrameRendered = true
        // First frame alone may only be the background color before tiles arrive; only complete if full render or loaded+tiles occurred.
        if (styleLoaded && (mapFullyRendered || (mapLoadedCallbackFired && hasTileDeliveryEvidence(tileSnapshot)))) {
            state = MapSurfaceLoadState.Available
        }
        return state
    }

    fun onMapLoadedCallback(
        tileSnapshot: TileTransportSnapshot,
        isOnline: Boolean,
    ): MapSurfaceLoadState {
        mapLoadedCallbackFired = true
        if (!isOnline && attemptOsmSuccesses(tileSnapshot) == 0) {
            state = MapSurfaceLoadState.ConnectionError("Sem conexão com a internet.")
            return state
        }
        if (styleLoaded && (mapFullyRendered || (firstFrameRendered && attemptOsmErrorsWithoutSuccess(tileSnapshot) == 0))) {
            state = MapSurfaceLoadState.Available
        }
        return state
    }

    fun onMapFinishedRendering(
        fully: Boolean,
        tileSnapshot: TileTransportSnapshot,
    ): MapSurfaceLoadState {
        if (fully) {
            mapFullyRendered = true
            firstFrameRendered = true
            if (styleLoaded && state !is MapSurfaceLoadState.RenderError && state !is MapSurfaceLoadState.StyleError) {
                if (attemptOsmErrorsWithoutSuccess(tileSnapshot) >= 2 && tileSnapshot.lastOsmError != null) {
                    state = failureFromTileSnapshot(tileSnapshot)
                } else {
                    state = MapSurfaceLoadState.Available
                }
            }
        } else {
            firstFrameRendered = true
        }
        return state
    }

    fun onTileTransportUpdated(
        tileSnapshot: TileTransportSnapshot,
        isOnline: Boolean,
    ): MapSurfaceLoadState {
        if (!isOnline && state.isLoading && attemptOsmSuccesses(tileSnapshot) == 0) {
            state = MapSurfaceLoadState.ConnectionError("Conexão com a internet interrompida.")
            return state
        }
        if (state.isLoading || state is MapSurfaceLoadState.TimedOut) {
            val failedCount = attemptOsmErrorsWithoutSuccess(tileSnapshot)
            if (failedCount >= 2 && tileSnapshot.lastOsmError != null) {
                state = failureFromTileSnapshot(tileSnapshot)
                return state
            }
            if (styleLoaded && firstFrameRendered && mapLoadedCallbackFired && attemptOsmSuccesses(tileSnapshot) > 0) {
                state = MapSurfaceLoadState.Available
            }
        }
        return state
    }

    fun onRenderError(reason: String): MapSurfaceLoadState {
        state = MapSurfaceLoadState.RenderError(reason.ifBlank { "Erro na superfície gráfica OpenGL/TextureView." })
        return state
    }

    fun onTimeout(
        tileSnapshot: TileTransportSnapshot,
        surfaceInfoOverride: String? = null,
    ): MapSurfaceLoadState {
        if (!surfaceInfoOverride.isNullOrBlank()) {
            surfaceDiagnostics = surfaceInfoOverride
        }
        if (!state.isLoading) return state

        val stalledStage = state.stage
        val requests = (tileSnapshot.osmRequestCount - baselineOsmRequestCount).coerceAtLeast(0)
        val successes = attemptOsmSuccesses(tileSnapshot)
        val errors = (tileSnapshot.osmErrorCount - baselineOsmErrorCount).coerceAtLeast(0)

        val knownCause =
            when (stalledStage) {
                MapLoadStage.INITIALIZING ->
                    "A superfície nativa MapLibre ($surfaceDiagnostics) não concluiu a inicialização a tempo."
                MapLoadStage.LOADING_STYLE ->
                    "O estilo cartográfico v8 do $screenName foi solicitado, mas o MapLibre não confirmou o carregamento."
                MapLoadStage.LOADING_MAP ->
                    when {
                        tileSnapshot.lastOsmError != null && successes == 0 ->
                            "Estilo carregado, mas tiles OpenStreetMap falharam: ${tileSnapshot.lastOsmError} (req=$requests, err=$errors)."
                        requests == 0 ->
                            "Estilo carregado, mas nenhuma requisição de tile OpenStreetMap foi disparada ($surfaceDiagnostics)."
                        successes > 0 && !firstFrameRendered ->
                            "Tiles OpenStreetMap recebidos ($successes), porém nenhum quadro foi renderizado ($surfaceDiagnostics)."
                        else ->
                            "Aguardando renderização completa dos tiles OpenStreetMap (req=$requests, ok=$successes, 1º quadro=$firstFrameRendered)."
                    }
                else -> null
            }

        state = MapSurfaceLoadState.TimedOut(stalledStage = stalledStage, knownCause = knownCause)
        return state
    }

    private fun attemptOsmSuccesses(tileSnapshot: TileTransportSnapshot): Int =
        (tileSnapshot.osmSuccessCount - baselineOsmSuccessCount).coerceAtLeast(0)

    private fun attemptOsmErrorsWithoutSuccess(tileSnapshot: TileTransportSnapshot): Int =
        if (attemptOsmSuccesses(tileSnapshot) > 0) 0
        else (tileSnapshot.osmErrorCount - baselineOsmErrorCount).coerceAtLeast(0)

    private fun hasTileDeliveryEvidence(tileSnapshot: TileTransportSnapshot): Boolean =
        attemptOsmSuccesses(tileSnapshot) > 0 || attemptOsmErrorsWithoutSuccess(tileSnapshot) == 0

    private fun failureFromTileSnapshot(tileSnapshot: TileTransportSnapshot): MapSurfaceLoadState {
        val reason = tileSnapshot.lastOsmError ?: "Falha ao baixar tiles do OpenStreetMap."
        return when (tileSnapshot.lastOsmFailureKind) {
            TileTransportFailureKind.DNS_OR_NETWORK,
            TileTransportFailureKind.TIMEOUT,
            TileTransportFailureKind.TLS -> MapSurfaceLoadState.ConnectionError(reason)
            TileTransportFailureKind.HTTP_BLOCKED_OR_RATE_LIMIT,
            TileTransportFailureKind.HTTP_SERVER_OR_NOT_FOUND,
            TileTransportFailureKind.UNKNOWN,
            TileTransportFailureKind.NONE -> MapSurfaceLoadState.RenderError(reason)
        }
    }

    companion object {
        const val INITIAL_LOAD_TIMEOUT_MS: Long = 12_000L
    }
}

/**
 * Deterministic, idempotent lifecycle bridge between Compose/AndroidX Lifecycle and MapLibre MapView.
 *
 * Prevents duplicate `onStart`/`onResume` calls caused by calling lifecycle methods inside
 * `AndroidView.factory` AND attaching a `LifecycleEventObserver` (which replays past events),
 * and prevents double `onDestroy` during rapid tab switches or disposal.
 */
class MapViewLifecycleController(
    private val onCreate: () -> Unit,
    private val onStart: () -> Unit,
    private val onResume: () -> Unit,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit,
    private val onDestroy: () -> Unit,
    private val onLowMemory: () -> Unit,
) {
    enum class Stage {
        UNINITIALIZED,
        CREATED,
        STARTED,
        RESUMED,
        DESTROYED,
    }

    var currentStage: Stage = Stage.UNINITIALIZED
        private set

    fun ensureCreated() {
        if (currentStage == Stage.UNINITIALIZED) {
            onCreate()
            currentStage = Stage.CREATED
        }
    }

    fun syncToLifecycleState(state: Lifecycle.State) {
        if (currentStage == Stage.DESTROYED) return
        ensureCreated()
        when (state) {
            Lifecycle.State.RESUMED -> {
                moveToStarted()
                moveToResumed()
            }
            Lifecycle.State.STARTED -> {
                if (currentStage == Stage.RESUMED) {
                    moveToPaused()
                } else {
                    moveToStarted()
                }
            }
            Lifecycle.State.CREATED -> {
                moveToPaused()
                moveToStopped()
            }
            Lifecycle.State.DESTROYED -> {
                destroy()
            }
            Lifecycle.State.INITIALIZED -> Unit
        }
    }

    fun onLifecycleEvent(event: Lifecycle.Event) {
        if (currentStage == Stage.DESTROYED) return
        when (event) {
            Lifecycle.Event.ON_CREATE -> ensureCreated()
            Lifecycle.Event.ON_START -> {
                ensureCreated()
                moveToStarted()
            }
            Lifecycle.Event.ON_RESUME -> {
                ensureCreated()
                moveToStarted()
                moveToResumed()
            }
            Lifecycle.Event.ON_PAUSE -> moveToPaused()
            Lifecycle.Event.ON_STOP -> {
                moveToPaused()
                moveToStopped()
            }
            Lifecycle.Event.ON_DESTROY -> destroy()
            Lifecycle.Event.ON_ANY -> Unit
        }
    }

    fun dispatchLowMemory() {
        if (currentStage != Stage.UNINITIALIZED && currentStage != Stage.DESTROYED) {
            onLowMemory()
        }
    }

    fun destroy() {
        if (currentStage == Stage.UNINITIALIZED || currentStage == Stage.DESTROYED) return
        moveToPaused()
        moveToStopped()
        onDestroy()
        currentStage = Stage.DESTROYED
    }

    private fun moveToStarted() {
        if (currentStage == Stage.CREATED) {
            onStart()
            currentStage = Stage.STARTED
        }
    }

    private fun moveToResumed() {
        if (currentStage == Stage.STARTED) {
            onResume()
            currentStage = Stage.RESUMED
        }
    }

    private fun moveToPaused() {
        if (currentStage == Stage.RESUMED) {
            onPause()
            currentStage = Stage.STARTED
        }
    }

    private fun moveToStopped() {
        if (currentStage == Stage.STARTED) {
            onStop()
            currentStage = Stage.CREATED
        }
    }
}

/**
 * Compact, unobtrusive status overlay displayed on top of a MapLibre viewport reflecting
 * each stage independently (Initializing, LoadingStyle, LoadingMap, Available, ConnectionError,
 * StyleError, RenderError, TimedOut) without ever faking completion.
 */
@Composable
fun MapSurfaceStatusOverlay(
    loadState: MapSurfaceLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryNotice: String? = null,
    testTagPrefix: String = "map",
) {
    when (loadState) {
        MapSurfaceLoadState.Initializing,
        MapSurfaceLoadState.LoadingStyle,
        MapSurfaceLoadState.LoadingMap -> {
            val loadingText =
                when (loadState) {
                    MapSurfaceLoadState.Initializing -> "Inicializando mapa…"
                    MapSurfaceLoadState.LoadingStyle -> "Carregando estilo…"
                    MapSurfaceLoadState.LoadingMap -> "Carregando mapa…"
                    else -> "Carregando…"
                }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = ClimaDeepSky.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.5f)),
                modifier = modifier.testTag("${testTagPrefix}_status_loading"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        color = ClimaAccentCyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = loadingText,
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        MapSurfaceLoadState.Available -> {
            if (!secondaryNotice.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ClimaDeepSky.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, ClimaAlertAttentionAmber.copy(alpha = 0.7f)),
                    modifier = modifier.testTag("${testTagPrefix}_secondary_notice"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painter = painterResource(ClimaUiIcons.Alert),
                            contentDescription = null,
                            tint = ClimaAlertAttentionAmber,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = secondaryNotice,
                            color = ClimaTextOnDark,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        OutlinedButton(
                            onClick = onRetry,
                            border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ClimaAccentCyan),
                            modifier = Modifier.testTag("${testTagPrefix}_retry_button"),
                        ) {
                            Text(
                                text = "Recarregar",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        is MapSurfaceLoadState.ConnectionError -> {
            MapBlockingErrorCard(
                title = "Falha de conexão",
                message = loadState.reason.ifBlank {
                    "Não foi possível carregar os tiles do OpenStreetMap offline. Conecte-se e tente novamente."
                },
                accent = ClimaAlertAttentionAmber,
                onRetry = onRetry,
                cardTag = "${testTagPrefix}_status_offline",
                retryTag = "${testTagPrefix}_retry_button",
                modifier = modifier,
            )
        }

        is MapSurfaceLoadState.StyleError -> {
            MapBlockingErrorCard(
                title = "Falha de estilo do mapa",
                message = loadState.reason.ifBlank { "Erro ao aplicar o estilo cartográfico v8." },
                accent = ClimaAlertHighRed,
                onRetry = onRetry,
                cardTag = "${testTagPrefix}_status_error",
                retryTag = "${testTagPrefix}_retry_button",
                modifier = modifier,
            )
        }

        is MapSurfaceLoadState.RenderError -> {
            MapBlockingErrorCard(
                title = "Falha de renderização",
                message = loadState.reason.ifBlank { "Erro na renderização da superfície MapLibre." },
                accent = ClimaAlertHighRed,
                onRetry = onRetry,
                cardTag = "${testTagPrefix}_status_error",
                retryTag = "${testTagPrefix}_retry_button",
                modifier = modifier,
            )
        }

        is MapSurfaceLoadState.TimedOut -> {
            val detail =
                loadState.knownCause?.takeIf { it.isNotBlank() }
                    ?: "Etapa interrompida: ${loadState.stalledStage.labelPt}."
            MapBlockingErrorCard(
                title = "Tempo de carregamento excedido",
                message = detail,
                accent = ClimaAlertAttentionAmber,
                onRetry = onRetry,
                cardTag = "${testTagPrefix}_status_timeout",
                retryTag = "${testTagPrefix}_retry_button",
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MapBlockingErrorCard(
    title: String,
    message: String,
    accent: Color,
    onRetry: () -> Unit,
    cardTag: String,
    retryTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = ClimaDeepSky.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.75f)),
        modifier = modifier.padding(12.dp).testTag(cardTag),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(ClimaUiIcons.Alert),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = title,
                color = ClimaTextOnDark,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                color = ClimaTextMutedOnDark,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = onRetry,
                border = BorderStroke(1.dp, ClimaAccentCyan),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ClimaAccentCyan),
                modifier = Modifier.testTag(retryTag),
            ) {
                Text(
                    text = "Tentar novamente",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Small status chip indicating the exact map loading stage.
 */
@Composable
fun MapLoadedStatusChip(
    loadState: MapSurfaceLoadState,
    modifier: Modifier = Modifier,
    testTag: String = "map_state_chip",
) {
    val (label, dotColor) =
        when (loadState) {
            MapSurfaceLoadState.Initializing -> "Inicializando" to ClimaAccentCyan
            MapSurfaceLoadState.LoadingStyle -> "Carregando estilo" to ClimaAccentCyan
            MapSurfaceLoadState.LoadingMap -> "Carregando mapa" to ClimaAccentCyan
            MapSurfaceLoadState.Available -> "Mapa disponível" to ClimaToastEmerald
            is MapSurfaceLoadState.ConnectionError -> "Falha de conexão" to ClimaAlertAttentionAmber
            is MapSurfaceLoadState.StyleError -> "Falha de estilo" to ClimaAlertHighRed
            is MapSurfaceLoadState.RenderError -> "Falha de renderização" to ClimaAlertHighRed
            is MapSurfaceLoadState.TimedOut -> "Tempo excedido" to ClimaAlertAttentionAmber
        }
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = ClimaDeepSky.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
        modifier = modifier.testTag(testTag),
    ) {
        Text(
            text = label,
            color = dotColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
