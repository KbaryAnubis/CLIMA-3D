package com.example.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoSurfaceOutputState {
    VIDEO_OUTPUT_ACTIVE,
    VIDEO_OUTPUT_DETACHED,
}

enum class MapSurfaceSessionState {
    NO_ACTIVE_MAP_SURFACE,
    MAP_SURFACE_ACTIVE,
    MAP_SURFACE_DISPOSING,
}

data class GraphicsSurfaceCoordinationSnapshot(
    val videoState: VideoSurfaceOutputState = VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED,
    val mapState: MapSurfaceSessionState = MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE,
    val activeMapSessions: Set<String> = emptySet(),
    val disposingMapSessions: Set<String> = emptySet(),
    val activeVideoSessionId: String? = null,
    val videoOutputAttached: Boolean = false,
) {
    /**
     * Video output may only attach when no MapLibre OpenGL TextureView session is active or disposing.
     */
    val canAttachVideoOutput: Boolean
        get() = activeMapSessions.isEmpty() && disposingMapSessions.isEmpty() &&
            mapState == MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE
}

/**
 * Coordinates surface exclusivity and ordered handoff between:
 * - Media3 ExoPlayer (`PlayerView` with `app:surface_type="texture_view"`)
 * - MapLibre Native (`MapView` with `textureMode(true)` OpenGL ES `TextureView`)
 *
 * Guarantees:
 * - Zero artificial delays (`Thread.sleep`, fixed timers, or main-thread blocking).
 * - Reactive state transitions via [state] so `WeatherVideoBackgroundImpl` detaches video output
 *   when entering map tabs (`RADAR`, `CITIES`) and waits until MapLibre completes its two-phase
 *   teardown (`MAP_SURFACE_DISPOSING` -> `NO_ACTIVE_MAP_SURFACE`) before reattaching the existing
 *   in-memory `ExoPlayer` instance on non-map tabs.
 */
object GraphicsSurfaceCoordinator {
    private val lock = Any()
    private val activeMapSessionIds = linkedSetOf<String>()
    private val disposingMapSessionIds = linkedSetOf<String>()
    private var currentVideoState: VideoSurfaceOutputState = VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED
    private var currentVideoSessionId: String? = null
    private var currentVideoViewIdentity: Int = 0
    private var isVideoAttached: Boolean = false

    private val _state = MutableStateFlow(GraphicsSurfaceCoordinationSnapshot())
    val state: StateFlow<GraphicsSurfaceCoordinationSnapshot> = _state.asStateFlow()

    private fun computeMapStateLocked(): MapSurfaceSessionState =
        when {
            activeMapSessionIds.isNotEmpty() -> MapSurfaceSessionState.MAP_SURFACE_ACTIVE
            disposingMapSessionIds.isNotEmpty() -> MapSurfaceSessionState.MAP_SURFACE_DISPOSING
            else -> MapSurfaceSessionState.NO_ACTIVE_MAP_SURFACE
        }

    private fun publishLocked(): GraphicsSurfaceCoordinationSnapshot {
        val next =
            GraphicsSurfaceCoordinationSnapshot(
                videoState = currentVideoState,
                mapState = computeMapStateLocked(),
                activeMapSessions = activeMapSessionIds.toSet(),
                disposingMapSessions = disposingMapSessionIds.toSet(),
                activeVideoSessionId = currentVideoSessionId,
                videoOutputAttached = isVideoAttached,
            )
        _state.value = next
        return next
    }

    fun onMapSurfaceCreated(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        surfaceInfo: String? = null,
    ): GraphicsSurfaceCoordinationSnapshot {
        val snapshot =
            synchronized(lock) {
                disposingMapSessionIds.remove(sessionId)
                activeMapSessionIds.add(sessionId)
                publishLocked()
            }
        ClimaGraphicsLifecycleDiagnostics.logMapSurfaceCreated(
            screen = screen,
            sessionId = sessionId,
            viewIdentity = viewIdentity,
            mapState = snapshot.mapState,
            videoState = snapshot.videoState,
            surfaceInfo = surfaceInfo,
        )
        return snapshot
    }

    fun onMapSurfaceDisposing(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        stage: String,
        surfaceInfo: String? = null,
    ): GraphicsSurfaceCoordinationSnapshot {
        var shouldLog = false
        val snapshot =
            synchronized(lock) {
                val wasActive = activeMapSessionIds.remove(sessionId)
                val addedDisposing = disposingMapSessionIds.add(sessionId)
                shouldLog = wasActive || addedDisposing
                publishLocked()
            }
        if (shouldLog) {
            ClimaGraphicsLifecycleDiagnostics.logMapSurfaceDisposing(
                screen = screen,
                sessionId = sessionId,
                viewIdentity = viewIdentity,
                stage = stage,
                mapState = snapshot.mapState,
                videoState = snapshot.videoState,
                surfaceInfo = surfaceInfo,
            )
        }
        return snapshot
    }

    fun onMapSurfaceDetached(
        screen: String,
        sessionId: String,
        viewIdentity: Int,
        surfaceInfo: String? = null,
    ): GraphicsSurfaceCoordinationSnapshot {
        var shouldLog = false
        val snapshot =
            synchronized(lock) {
                val removedActive = activeMapSessionIds.remove(sessionId)
                val removedDisposing = disposingMapSessionIds.remove(sessionId)
                shouldLog = removedActive || removedDisposing
                publishLocked()
            }
        if (shouldLog) {
            ClimaGraphicsLifecycleDiagnostics.logMapSurfaceDetached(
                screen = screen,
                sessionId = sessionId,
                viewIdentity = viewIdentity,
                mapState = snapshot.mapState,
                videoState = snapshot.videoState,
                surfaceInfo = surfaceInfo,
            )
        }
        return snapshot
    }

    fun onVideoOutputDetachForMap(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
    ) {
        val snapshot = _state.value
        ClimaGraphicsLifecycleDiagnostics.logVideoOutputDetachForMap(
            sessionId = sessionId,
            playerIdentity = playerIdentity,
            viewIdentity = viewIdentity,
            reason = reason,
            mapState = snapshot.mapState,
            videoState = snapshot.videoState,
        )
    }

    fun onVideoOutputDetached(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
    ): GraphicsSurfaceCoordinationSnapshot {
        var effectiveReason = reason
        val snapshot =
            synchronized(lock) {
                val isStaleSession =
                    isVideoAttached &&
                        currentVideoSessionId != null &&
                        currentVideoSessionId != sessionId
                if (isStaleSession) {
                    effectiveReason = "${reason}_stale_ignored"
                    _state.value
                } else {
                    currentVideoSessionId = sessionId
                    currentVideoViewIdentity = 0
                    currentVideoState = VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED
                    isVideoAttached = false
                    publishLocked()
                }
            }
        ClimaGraphicsLifecycleDiagnostics.logVideoOutputDetached(
            sessionId = sessionId,
            playerIdentity = playerIdentity,
            viewIdentity = viewIdentity,
            reason = effectiveReason,
            mapState = snapshot.mapState,
            videoState = snapshot.videoState,
        )
        return snapshot
    }

    fun onVideoOutputReattachRequest(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        reason: String,
    ) {
        val snapshot = _state.value
        ClimaGraphicsLifecycleDiagnostics.logVideoOutputReattachRequest(
            sessionId = sessionId,
            playerIdentity = playerIdentity,
            viewIdentity = viewIdentity,
            reason = reason,
            mapState = snapshot.mapState,
            videoState = snapshot.videoState,
            canAttachNow = snapshot.canAttachVideoOutput,
        )
    }

    fun onVideoOutputReattached(
        sessionId: String,
        playerIdentity: Int,
        viewIdentity: Int,
        surfaceInfo: String? = null,
    ): GraphicsSurfaceCoordinationSnapshot {
        val snapshot =
            synchronized(lock) {
                currentVideoSessionId = sessionId
                currentVideoViewIdentity = viewIdentity
                currentVideoState = VideoSurfaceOutputState.VIDEO_OUTPUT_ACTIVE
                isVideoAttached = true
                publishLocked()
            }
        ClimaGraphicsLifecycleDiagnostics.logVideoOutputReattached(
            sessionId = sessionId,
            playerIdentity = playerIdentity,
            viewIdentity = viewIdentity,
            mapState = snapshot.mapState,
            videoState = snapshot.videoState,
            surfaceInfo = surfaceInfo,
        )
        return snapshot
    }

    internal fun resetForTest() {
        synchronized(lock) {
            activeMapSessionIds.clear()
            disposingMapSessionIds.clear()
            currentVideoState = VideoSurfaceOutputState.VIDEO_OUTPUT_DETACHED
            currentVideoSessionId = null
            currentVideoViewIdentity = 0
            isVideoAttached = false
            publishLocked()
        }
    }
}
