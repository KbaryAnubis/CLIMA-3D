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

    @Volatile
    private var currentActivityState: String = "RESUMED"

    fun currentActivityStateName(): String = currentActivityState

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
        currentActivityState = "RESUMED"
        GraphicsSurfaceCoordinator.resetForTest()
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

    fun logVideoOutputDetachForMap(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
    ) {
        emit(
            "VIDEO_OUTPUT_DETACH_FOR_MAP session=$sessionId playerId=$playerIdentity viewId=$viewIdentity " +
                "reason=$reason mapState=${mapState.name} videoState=${videoState.name}",
        )
    }

    fun logVideoOutputDetached(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
    ) {
        emit(
            "VIDEO_OUTPUT_DETACHED session=$sessionId playerId=$playerIdentity viewId=$viewIdentity " +
                "reason=$reason mapState=${mapState.name} videoState=${videoState.name}",
        )
    }

    fun logMapSurfaceCreated(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit(
            "MAP_SURFACE_CREATED screen=$screen session=$sessionId viewId=$viewIdentity " +
                "mapState=${mapState.name} videoState=${videoState.name}$surfaceSuffix",
        )
    }

    fun logMapSurfaceDisposing(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        stage: String,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit(
            "MAP_SURFACE_DISPOSING screen=$screen session=$sessionId viewId=$viewIdentity stage=$stage " +
                "mapState=${mapState.name} videoState=${videoState.name}$surfaceSuffix",
        )
    }

    fun logMapSurfaceDetached(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit(
            "MAP_SURFACE_DETACHED screen=$screen session=$sessionId viewId=$viewIdentity " +
                "mapState=${mapState.name} videoState=${videoState.name}$surfaceSuffix",
        )
    }

    fun logVideoOutputReattachRequest(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
        canAttachNow: Boolean,
    ) {
        emit(
            "VIDEO_OUTPUT_REATTACH_REQUEST session=$sessionId playerId=$playerIdentity viewId=$viewIdentity " +
                "reason=$reason mapState=${mapState.name} videoState=${videoState.name} canAttachNow=$canAttachNow",
        )
    }

    fun logVideoOutputReattached(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        mapState: MapSurfaceSessionState,
        videoState: VideoSurfaceOutputState,
        surfaceInfo: String? = null,
    ) {
        val surfaceSuffix = surfaceInfo?.let { " surface=[$it]" }.orEmpty()
        emit(
            "VIDEO_OUTPUT_REATTACHED session=$sessionId playerId=$playerIdentity viewId=$viewIdentity " +
                "mapState=${mapState.name} videoState=${videoState.name}$surfaceSuffix",
        )
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
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_PLAYBACK_STATE session=$sessionId playerId=$playerIdentity state=$playbackState " +
                "playing=$isPlaying posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix",
        )
    }

    fun logExoIsPlayingChanged(
        sessionId: String,
        playerIdentity: Int,
        isPlaying: Boolean,
        playerState: String,
        videoResId: Int? = null,
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_IS_PLAYING session=$sessionId playerId=$playerIdentity playing=$isPlaying " +
                "playerState=$playerState posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix",
        )
    }

    fun logExoMediaItemTransition(
        sessionId: String,
        playerIdentity: Int,
        mediaId: String? = null,
        reason: String = "AUTO",
        transitionReason: String = reason,
        playerState: String = "READY",
        videoResId: Int? = null,
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_MEDIA_ITEM session=$sessionId playerId=$playerIdentity media=${mediaId ?: "none"} " +
                "reason=$transitionReason playerState=$playerState posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix",
        )
    }

    fun logExoDecoderInitialized(
        sessionId: String,
        playerIdentity: Int,
        trackType: String,
        decoderName: String,
        initDurationMs: Long,
        playerState: String,
        videoResId: Int? = null,
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_DECODER_INITIALIZED session=$sessionId playerId=$playerIdentity track=$trackType " +
                "decoder=$decoderName initDurationMs=$initDurationMs playerState=$playerState " +
                "posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix",
        )
    }

    fun logExoDecoderReleased(
        sessionId: String,
        playerIdentity: Int,
        trackType: String,
        decoderName: String,
        playerState: String,
        videoResId: Int? = null,
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_DECODER_RELEASED session=$sessionId playerId=$playerIdentity track=$trackType " +
                "decoder=$decoderName playerState=$playerState posMs=$positionMs " +
                "activityState=$activityState videoState=${videoState.name}$resSuffix",
        )
    }

    fun logExoCodecError(
        sessionId: String,
        playerIdentity: Int,
        trackType: String,
        errorClass: String,
        message: String?,
        isDeadObject: Boolean,
        playerState: String,
        videoResId: Int? = null,
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_CODEC_ERROR session=$sessionId playerId=$playerIdentity track=$trackType " +
                "errorClass=$errorClass deadObject=$isDeadObject playerState=$playerState " +
                "posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix " +
                "msg=${message ?: "unknown"}",
        )
    }

    fun logExoFirstFrame(
        sessionId: String,
        playerIdentity: Int,
        videoResId: Int? = null,
        positionMs: Long = 0L,
        playerState: String = "READY",
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_FIRST_FRAME session=$sessionId playerId=$playerIdentity playerState=$playerState " +
                "posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix",
        )
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
        errorClass: String = "PlaybackException",
        isDeadObject: Boolean = false,
        playerState: String = "IDLE",
        videoResId: Int? = null,
        positionMs: Long = 0L,
        activityState: String = currentActivityState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_ERROR session=$sessionId playerId=$playerIdentity code=$errorCodeName " +
                "errorClass=$errorClass deadObject=$isDeadObject playerState=$playerState " +
                "posMs=$positionMs activityState=$activityState videoState=${videoState.name}$resSuffix " +
                "msg=${message ?: "unknown"}",
        )
    }

    fun logExoRecoveryAction(
        sessionId: String,
        playerIdentity: Int,
        action: String,
        reason: String,
        playerState: String = "IDLE",
        videoResId: Int? = null,
        positionMs: Long = 0L,
        playbackSpeed: Float = 1.0f,
        speed: Float = playbackSpeed,
        activityState: String = currentActivityState,
        mapState: MapSurfaceSessionState = GraphicsSurfaceCoordinator.state.value.mapState,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
    ) {
        val resSuffix = videoResId?.let { " resId=$it" }.orEmpty()
        emit(
            "EXO_RECOVERY session=$sessionId playerId=$playerIdentity action=$action reason=$reason " +
                "playerState=$playerState posMs=$positionMs speed=$speed activityState=$activityState " +
                "mapState=${mapState.name} videoState=${videoState.name}$resSuffix",
        )
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
        currentActivityState =
            when (event) {
                Lifecycle.Event.ON_CREATE -> "CREATED"
                Lifecycle.Event.ON_START -> "STARTED"
                Lifecycle.Event.ON_RESUME -> "RESUMED"
                Lifecycle.Event.ON_PAUSE -> "STARTED"
                Lifecycle.Event.ON_STOP -> "CREATED"
                Lifecycle.Event.ON_DESTROY -> "DESTROYED"
                Lifecycle.Event.ON_ANY -> currentActivityState
            }
        when (event) {
            Lifecycle.Event.ON_START,
            Lifecycle.Event.ON_RESUME,
            Lifecycle.Event.ON_PAUSE,
            Lifecycle.Event.ON_STOP, -> emit("ACTIVITY_LIFECYCLE event=${event.name} activityState=$currentActivityState")
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

    fun logBackgroundVisualMilestone(
        eventName: String,
        sessionId: String,
        playerIdentity: Int,
        videoResId: Int,
        playerState: String,
        decoderGeneration: Int,
        videoAlpha: Float,
        frameReady: Boolean,
        visualState: String = "UNKNOWN",
        reason: String? = null,
        videoState: VideoSurfaceOutputState = GraphicsSurfaceCoordinator.state.value.videoState,
        mapState: MapSurfaceSessionState = GraphicsSurfaceCoordinator.state.value.mapState,
        recoveryState: String? = null,
        surfaceEpoch: Int? = null,
    ) {
        val reasonSuffix = reason?.let { " reason=$it" }.orEmpty()
        val recoverySuffix = recoveryState?.let { " recoveryState=$it" }.orEmpty()
        val surfaceEpochSuffix = surfaceEpoch?.let { " surfaceEpoch=$it" }.orEmpty()
        val alphaFormatted = String.format(Locale.US, "%.2f", videoAlpha)
        emit(
            "$eventName session=$sessionId playerId=$playerIdentity resId=$videoResId " +
                "playerState=$playerState decoderGen=$decoderGeneration videoAlpha=$alphaFormatted " +
                "frameReady=$frameReady visualState=$visualState$recoverySuffix$surfaceEpochSuffix " +
                "videoState=${videoState.name} mapState=${mapState.name}$reasonSuffix",
        )
    }

    fun logVideoRevocationDetected(
        sessionId: String,
        playerIdentity: Int,
        revocationReason: String,
        playerStateAtError: String,
        decoderGeneration: Int,
        surfaceEpoch: Int,
        surfaceStatus: String,
        videoResId: Int,
        positionMs: Long,
        recoveryState: String,
    ) {
        emit(
            "VIDEO_REVOCATION_DETECTED session=$sessionId playerId=$playerIdentity " +
                "revocationReason=$revocationReason playerState=$playerStateAtError " +
                "decoderGen=$decoderGeneration surfaceEpoch=$surfaceEpoch surfaceStatus=$surfaceStatus " +
                "resId=$videoResId posMs=$positionMs recoveryState=$recoveryState",
        )
    }

    fun logVideoRecoveryAttempt(
        sessionId: String,
        playerIdentity: Int,
        tier: String,
        attemptNumber: Int,
        maxAttempts: Int,
        reason: String,
        decoderGeneration: Int,
        surfaceEpoch: Int,
        surfaceStatus: String,
        videoResId: Int,
        positionMs: Long,
        playbackSpeed: Float,
        recoveryState: String,
    ) {
        val speedFormatted = String.format(Locale.US, "%.2f", playbackSpeed)
        emit(
            "VIDEO_RECOVERY_ATTEMPT session=$sessionId playerId=$playerIdentity " +
                "tier=$tier attempt=$attemptNumber/$maxAttempts reason=$reason " +
                "decoderGen=$decoderGeneration surfaceEpoch=$surfaceEpoch surfaceStatus=$surfaceStatus " +
                "resId=$videoResId posMs=$positionMs speed=$speedFormatted recoveryState=$recoveryState",
        )
    }

    fun logVideoRecoveryOutcome(
        sessionId: String,
        playerIdentity: Int,
        tier: String,
        outcome: String,
        decoderGeneration: Int,
        surfaceEpoch: Int,
        surfaceStatus: String,
        recoveryTimeMs: Long?,
        videoResId: Int,
        recoveryState: String,
    ) {
        val durationSuffix = recoveryTimeMs?.let { " recoveryTimeMs=$it" }.orEmpty()
        emit(
            "VIDEO_RECOVERY_OUTCOME session=$sessionId playerId=$playerIdentity " +
                "tier=$tier outcome=$outcome decoderGen=$decoderGeneration " +
                "surfaceEpoch=$surfaceEpoch surfaceStatus=$surfaceStatus$durationSuffix " +
                "resId=$videoResId recoveryState=$recoveryState",
        )
    }

    fun logVideoPlayerRebuild(
        stage: String,
        previousSessionId: String,
        previousPlayerId: Int,
        newSessionId: String? = null,
        newPlayerId: Int? = null,
        reason: String,
        videoResId: Int,
        positionMs: Long,
        playbackSpeed: Float,
        decoderGeneration: Int,
        surfaceEpoch: Int,
        surfaceStatus: String,
    ) {
        val speedFormatted = String.format(Locale.US, "%.2f", playbackSpeed)
        val newSessionSuffix = newSessionId?.let { " newSession=$it" }.orEmpty()
        val newPlayerSuffix = newPlayerId?.let { " newPlayerId=$it" }.orEmpty()
        emit(
            "VIDEO_PLAYER_REBUILD stage=$stage prevSession=$previousSessionId prevPlayerId=$previousPlayerId" +
                "$newSessionSuffix$newPlayerSuffix reason=$reason resId=$videoResId posMs=$positionMs " +
                "speed=$speedFormatted decoderGen=$decoderGeneration surfaceEpoch=$surfaceEpoch " +
                "surfaceStatus=$surfaceStatus",
        )
    }

    fun logVideoFirstFrameTimeout(
        sessionId: String,
        playerIdentity: Int,
        timeoutMs: Long,
        playerState: String,
        decoderGeneration: Int,
        surfaceEpoch: Int,
        surfaceStatus: String,
        recoveryState: String,
        nextTier: String,
        videoResId: Int,
    ) {
        emit(
            "VIDEO_FIRST_FRAME_TIMEOUT session=$sessionId playerId=$playerIdentity " +
                "timeoutMs=$timeoutMs playerState=$playerState decoderGen=$decoderGeneration " +
                "surfaceEpoch=$surfaceEpoch surfaceStatus=$surfaceStatus " +
                "recoveryState=$recoveryState nextTier=$nextTier resId=$videoResId",
        )
    }
}
