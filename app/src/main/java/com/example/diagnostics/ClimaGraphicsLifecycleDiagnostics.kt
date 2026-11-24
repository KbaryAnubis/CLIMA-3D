package com.example.diagnostics

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import com.example.BuildConfig
import com.example.ui.theme.ClimaTab
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, BuildConfig.DEBUG-only graphics & lifecycle milestone diagnostics.
 *
 * Design rules:
 * - Zero overhead and zero logging in release builds (`!BuildConfig.DEBUG`).
 * - Wall-clock timestamp (`yyyy-MM-dd HH:mm:ss.SSS Z`) for direct Android Logcat correlation,
 *   alongside monotonic nanoseconds (`SystemClock.elapsedRealtimeNanos()`), PID, active tab,
 *   thread name, and unique session/view/player identities.
 * - Logs ONLY structural lifecycle milestones (MapView create/attach/lifecycle/dispose/detach/destroy,
 *   ExoPlayer create/surface attach/view attach/state/first frame/surface size/clear/release,
 *   Activity lifecycle, tab changes, modal open/close, refresh cycles).
 * - NEVER logs per frame, per tile, or per Compose recomposition.
 *
 * Platform note:
 * Native `E/Surface: freeAllBuffers` and `E/Surface: getSlotFromBufferLocked: unknown buffer`
 * originate inside Android's native `libgui` / `BufferQueue` / emulator GL driver layer and cannot
 * be directly intercepted from Kotlin or mapped to private Surface handles without reflection.
 * Comparing wall-clock + monotonic timestamps and active surface states from this logger against
 * Logcat entries for the same PID reveals whether native warnings coincide with MapView,
 * ExoPlayer, Activity lifecycle transitions, or emulator compositor activity.
 */
object ClimaGraphicsLifecycleDiagnostics {
    const val TAG = "CLIMA_GFX_LIFECYCLE"
    private const val MAX_RING_ENTRIES = 128

    private val sessionSeq = AtomicLong(0L)
    private val lock = Any()
    private val recentEvents = ArrayDeque<String>(MAX_RING_ENTRIES)

    private val wallClockFormatter =
        object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        }

    @Volatile
    internal var debugOverrideForTest: Boolean? = null

    @Volatile
    private var currentTab: ClimaTab = ClimaTab.HOME

    fun isEnabled(): Boolean = debugOverrideForTest ?: BuildConfig.DEBUG

    fun nextSessionId(prefix: String): String = "$prefix-${sessionSeq.incrementAndGet()}"

    private fun monotonicNowNanos(): Long =
        runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }

    private fun currentPid(): Int =
        runCatching { Process.myPid() }.getOrElse { -1 }

    private fun formatWallClockNow(): String =
        runCatching {
            val fmt = wallClockFormatter.get() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            fmt.format(Date())
        }.getOrElse { "unknown" }

    fun recentEventsSnapshot(): List<String> =
        synchronized(lock) { recentEvents.toList() }

    internal fun clearForTest() {
        synchronized(lock) { recentEvents.clear() }
        currentTab = ClimaTab.HOME
    }

    private fun emit(event: String) {
        if (!isEnabled()) return
        val wall = formatWallClockNow()
        val monoNs = monotonicNowNanos()
        val pid = currentPid()
        val tabName = currentTab.name
        val threadName = Thread.currentThread().name
        val line = "[wall=$wall monoNs=$monoNs pid=$pid tab=$tabName thread=$threadName] $event"
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
        viewClass: String = "MapView",
    ) {
        emit("MAP_CREATE screen=$screen session=$sessionId viewClass=$viewClass viewId=$viewIdentity attempt=$attempt")
    }

    fun logMapViewAttachState(
        screen: String,
        sessionId: String,
        viewClass: String,
        viewIdentity: Int,
        attached: Boolean,
        surfaceInfo: String,
    ) {
        val eventName = if (attached) "MAP_VIEW_ATTACH" else "MAP_VIEW_DETACH"
        emit("$eventName screen=$screen session=$sessionId viewClass=$viewClass viewId=$viewIdentity attached=$attached surface=[$surfaceInfo]")
    }

    fun logMapLifecycle(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        stage: String,
    ) {
        emit("MAP_LIFECYCLE screen=$screen session=$sessionId viewId=$viewIdentity stage=$stage")
    }

    fun logMapViewDisposeRequested(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        stage: String,
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit("MAP_DISPOSE_REQ screen=$screen session=$sessionId viewId=$viewIdentity stage=$stage$surfaceSuffix")
    }

    fun logMapViewDestroyed(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit("MAP_DESTROYED screen=$screen session=$sessionId viewId=$viewIdentity$surfaceSuffix")
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
        viewClass: String = "PlayerView",
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit("EXO_SURFACE_ATTACH session=$sessionId playerId=$playerIdentity viewClass=$viewClass viewId=$viewIdentity$surfaceSuffix")
    }

    fun logExoViewAttachState(
        sessionId: String,
        playerIdentity: Int,
        viewClass: String,
        viewIdentity: Int,
        attached: Boolean,
        surfaceViewClass: String?,
        textureAvailable: Boolean?,
        hasSurfaceTexture: Boolean?,
    ) {
        val eventName = if (attached) "EXO_VIEW_ATTACH" else "EXO_VIEW_DETACH"
        emit(
            "$eventName session=$sessionId playerId=$playerIdentity viewClass=$viewClass viewId=$viewIdentity " +
                "attached=$attached surfaceViewClass=${surfaceViewClass ?: "none"} " +
                "textureAvailable=${textureAvailable ?: "unknown"} hasSurfaceTexture=${hasSurfaceTexture ?: "unknown"}",
        )
    }

    fun logExoPlaybackMilestone(
        sessionId: String,
        playerIdentity: Int,
        playbackState: String,
        isPlaying: Boolean,
        videoResId: Int? = null,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit("EXO_PLAYBACK_STATE session=$sessionId playerId=$playerIdentity state=$playbackState playing=$isPlaying$resSuffix")
    }

    fun logExoFirstFrame(
        sessionId: String,
        playerIdentity: Int,
        videoResId: Int? = null,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit("EXO_FIRST_FRAME session=$sessionId playerId=$playerIdentity$resSuffix")
    }

    fun logExoSurfaceSizeChanged(
        sessionId: String,
        playerIdentity: Int,
        width: Int,
        height: Int,
    ) {
        emit("EXO_SURFACE_SIZE session=$sessionId playerId=$playerIdentity size=${width}x$height")
    }

    fun logExoPlayerError(
        sessionId: String,
        playerIdentity: Int,
        errorCodeName: String,
        message: String?,
    ) {
        emit("EXO_ERROR session=$sessionId playerId=$playerIdentity code=$errorCodeName msg=${message ?: "unknown"}")
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
        currentTab = toTab
        emit("TAB_CHANGE from=${fromTab.name} to=${toTab.name}")
    }

    fun logModalLifecycle(
        modalName: String,
        action: String,
    ) {
        emit("MODAL_EVENT modal=$modalName action=$action")
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
