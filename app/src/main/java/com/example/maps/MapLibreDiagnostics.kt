package com.example.maps

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
 * Visual loading/error status of a native MapLibre surface.
 */
@Immutable
sealed interface MapSurfaceLoadState {
    data object Loading : MapSurfaceLoadState
    data object Loaded : MapSurfaceLoadState
    data object Offline : MapSurfaceLoadState
    data class Error(val reason: String) : MapSurfaceLoadState
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
 * Compact, unobtrusive status overlay displayed on top of a MapLibre viewport when the map is
 * loading, offline, failed to load its style/tiles, or when the RainViewer overlay is unavailable.
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
        MapSurfaceLoadState.Loading -> {
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
                        text = "Carregando mapa…",
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        MapSurfaceLoadState.Loaded -> {
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

        MapSurfaceLoadState.Offline -> {
            MapBlockingErrorCard(
                title = "Sem conexão com a internet",
                message = "Não foi possível carregar os tiles do OpenStreetMap offline. Conecte-se e toque em recarregar.",
                accent = ClimaAlertAttentionAmber,
                onRetry = onRetry,
                cardTag = "${testTagPrefix}_status_offline",
                retryTag = "${testTagPrefix}_retry_button",
                modifier = modifier,
            )
        }

        is MapSurfaceLoadState.Error -> {
            MapBlockingErrorCard(
                title = "Falha ao carregar o mapa",
                message = loadState.reason.ifBlank { "Erro ao inicializar o estilo cartográfico ou renderização." },
                accent = ClimaAlertHighRed,
                onRetry = onRetry,
                cardTag = "${testTagPrefix}_status_error",
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
 * Small status chip indicating when the base map is loaded or diagnosing state cleanly.
 */
@Composable
fun MapLoadedStatusChip(
    loadState: MapSurfaceLoadState,
    modifier: Modifier = Modifier,
    testTag: String = "map_state_chip",
) {
    val (label, dotColor) =
        when (loadState) {
            MapSurfaceLoadState.Loading -> "Carregando mapa" to ClimaAccentCyan
            MapSurfaceLoadState.Loaded -> "Mapa carregado" to ClimaToastEmerald
            MapSurfaceLoadState.Offline -> "Sem conexão" to ClimaAlertAttentionAmber
            is MapSurfaceLoadState.Error -> "Falha no mapa" to ClimaAlertHighRed
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
