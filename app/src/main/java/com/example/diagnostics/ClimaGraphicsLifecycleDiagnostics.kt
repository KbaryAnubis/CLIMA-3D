package com.example.diagnostics

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import com.example.BuildConfig
import com.example.ui.theme.ClimaTab
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, BuildConfig.DEBUG-only graphics & lifecycle milestone diagnostics.
 *
 * Design rules:
 * - Zero overhead and zero logging in release builds (`!BuildConfig.DEBUG`).
 * - Monotonic timestamps (`SystemClock.elapsedRealtime()`) and incremental session IDs.
 * - Logs ONLY structural lifecycle milestones (MapView create/dispose/destroy, ExoPlayer
 *   create/surface attach/surface clear/release, Activity lifecycle, tab changes, refresh cycles).
 * - NEVER logs per frame, per tile, or per Compose recomposition.
 *
 * Platform note:
 * Native `E/Surface: freeAllBuffers` and `E/Surface: getSlotFromBufferLocked: unknown buffer: 0x0`
 * originate inside Android's native `libgui` / `BufferQueue` / emulator GL driver layer and cannot
 * be directly intercepted from Kotlin. Comparing monotonic milestone timestamps from this logger
 * against native log timestamps determines whether those messages coincide with app surface
 * transitions or occur independently in the emulator compositor.
 */
object ClimaGraphicsLifecycleDiagnostics {
    const val TAG = "CLIMA_GFX_LIFECYCLE"
    private const val MAX_RING_ENTRIES = 64

    private val sessionSeq = AtomicLong(0L)
    private val lock = Any()
    private val recentEvents = ArrayDeque<String>(MAX_RING_ENTRIES)

    @Volatile
    internal var debugOverrideForTest: Boolean? = null

    fun isEnabled(): Boolean = debugOverrideForTest ?: BuildConfig.DEBUG

    fun nextSessionId(prefix: String): String = "$prefix-${sessionSeq.incrementAndGet()}"

    private fun monotonicNowMs(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }

    fun recentEventsSnapshot(): List<String> =
        synchronized(lock) { recentEvents.toList() }

    internal fun clearForTest() {
        synchronized(lock) { recentEvents.clear() }
    }

    private fun emit(event: String) {
        if (!isEnabled()) return
        val line = "[t=${monotonicNowMs()}ms] $event"
        synchronized(lock) {
            if (recentEvents.size >= MAX_RING_ENTRIES) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(line)
        }
        runCatching { Log.i(TAG, line) }
    }

    fun logMapViewCreated(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        attempt: Int,
    ) {
        emit("MAP_CREATE screen=$screen session=$sessionId viewId=$viewIdentity attempt=$attempt")
    }

    fun logMapViewDisposeRequested(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        stage: String,
    ) {
        emit("MAP_DISPOSE_REQ screen=$screen session=$sessionId viewId=$viewIdentity stage=$stage")
    }

    fun logMapViewDestroyed(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
    ) {
        emit("MAP_DESTROYED screen=$screen session=$sessionId viewId=$viewIdentity")
    }

    fun logExoPlayerCreated(
        sessionId: String,
        playerIdentity: Int,
        videoResId: Int,
    ) {
        emit("EXO_CREATE session=$sessionId playerId=$playerIdentity resId=$videoResId")
    }

    fun logPlayerSurfaceAttached(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
    ) {
        emit("EXO_SURFACE_ATTACH session=$sessionId playerId=$playerIdentity viewId=$viewIdentity")
    }

    fun logPlayerSurfaceCleared(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
    ) {
        emit("EXO_SURFACE_CLEAR session=$sessionId playerId=$playerIdentity viewId=$viewIdentity reason=$reason")
    }

    fun logExoPlayerReleased(
        sessionId: String,
        playerIdentity: Int,
    ) {
        emit("EXO_RELEASED session=$sessionId playerId=$playerIdentity")
    }

    fun logActivityLifecycle(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_RESUME,
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP, -> emit("ACTIVITY_LIFECYCLE event=${event.name}")
            else -> Unit
        }
    }

    fun logTabChanged(
        fromTab: ClimaTab,
        toTab: ClimaTab,
    ) {
        emit("TAB_CHANGE from=${fromTab.name} to=${toTab.name}")
    }

    fun logRefreshStarted(
        reason: String,
        locationId: Long?,
    ) {
        emit("REFRESH_START reason=$reason locationId=${locationId ?: "none"}")
    }

    fun logRefreshFinished(
        reason: String,
        locationId: Long?,
        durationMs: Long,
        outcome: String,
    ) {
        emit("REFRESH_END reason=$reason locationId=${locationId ?: "none"} durationMs=$durationMs outcome=$outcome")
    }
}
