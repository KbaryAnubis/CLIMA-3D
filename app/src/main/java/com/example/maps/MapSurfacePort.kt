package com.example.maps

import android.content.Context
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

internal sealed interface MapStyleSpecRequest {
    data class Cities(
        val savedMarkersGeoJson: String,
        val selectedMarkerGeoJson: String,
    ) : MapStyleSpecRequest
}

internal interface MapStylePort {
    val isFullyLoaded: Boolean
    fun updateGeoJsonSource(sourceId: String, geoJson: String)
}

internal interface MapLibreControllerPort {
    fun configureUiSettings()
    fun setInitialCamera(latitude: Double, longitude: Double, zoom: Double)
    fun animateCamera(latitude: Double, longitude: Double, zoom: Double, durationMs: Int)
    fun cancelTransitions()
    fun setStyle(request: MapStyleSpecRequest, onLoaded: (MapStylePort) -> Unit)
    fun setOnMapClick(listener: (CityMapCoordinate) -> Unit)
    fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit)
    fun clearMapListeners() {}
    fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult
    fun invalidate() {}
}

internal data class MapSurfaceCallbacks(
    val onWillStartLoading: () -> Unit,
    val onDidFinishLoadingStyle: () -> Unit,
    val onDidFinishLoadingMap: () -> Unit,
    val onDidFailLoadingMap: (String) -> Unit,
    val onWillStartRenderingMap: () -> Unit,
    val onFirstFrameRendered: () -> Unit,
    val onDidFinishRenderingMap: (Boolean) -> Unit,
    val onRenderError: (String) -> Unit,
)

internal interface MapViewSurfacePort {
    val androidView: View
    val viewIdentity: Int
    val viewClassName: String
        get() = androidView.javaClass.simpleName
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
    fun inspectSurfaceInfo(): String
    fun postToUi(action: () -> Unit)
    fun attachWindowObserver(onAttachStateChanged: (Boolean, String) -> Unit): () -> Unit = { }
    fun attachListeners(callbacks: MapSurfaceCallbacks): () -> Unit
    fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit)
}

private class RealMapStylePort(
    val style: Style,
    private val isStillValid: () -> Boolean = { true },
) : MapStylePort {
    override val isFullyLoaded: Boolean
        get() = isStillValid() && runCatching { style.isFullyLoaded }.getOrDefault(false)

    override fun updateGeoJsonSource(sourceId: String, geoJson: String) {
        if (!isStillValid() || !isFullyLoaded) return
        runCatching {
            style.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(geoJson)
        }
    }
}

private class RealMapLibreControllerPort(
    private val map: MapLibreMap,
    private val isHostDestroyed: () -> Boolean = { false },
    private val isHostActive: () -> Boolean = { true },
) : MapLibreControllerPort {
    private val invalidated = AtomicBoolean(false)
    private var activeClickListener: MapLibreMap.OnMapClickListener? = null
    private var activeCameraIdleListener: MapLibreMap.OnCameraIdleListener? = null

    private fun canOperateActive(): Boolean =
        !invalidated.get() && !isHostDestroyed() && isHostActive()

    private fun canOperateBeforeDestroy(): Boolean =
        !invalidated.get() && !isHostDestroyed()

    override fun configureUiSettings() {
        if (!canOperateActive()) return
        map.uiSettings.apply {
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isDoubleTapGesturesEnabled = true
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
        }
    }

    override fun setInitialCamera(latitude: Double, longitude: Double, zoom: Double) {
        if (!canOperateActive()) return
        map.cameraPosition =
            CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .build()
    }

    override fun animateCamera(latitude: Double, longitude: Double, zoom: Double, durationMs: Int) {
        if (!canOperateActive()) return
        val position =
            CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), durationMs)
    }

    override fun cancelTransitions() {
        if (!canOperateBeforeDestroy()) return
        map.cancelTransitions()
    }

    override fun setStyle(request: MapStyleSpecRequest, onLoaded: (MapStylePort) -> Unit) {
        if (!canOperateActive()) return
        val builder =
            when (request) {
                is MapStyleSpecRequest.Cities ->
                    OpenStreetMapTileConfig.buildStyleBuilder(
                        savedMarkersGeoJson = request.savedMarkersGeoJson,
                        selectedMarkerGeoJson = request.selectedMarkerGeoJson,
                    )
            }
        map.setStyle(builder) { style ->
            if (!canOperateActive()) return@setStyle
            onLoaded(RealMapStylePort(style) { canOperateActive() })
        }
    }

    override fun setOnMapClick(listener: (CityMapCoordinate) -> Unit) {
        if (!canOperateActive()) return
        activeClickListener?.let { runCatching { map.removeOnMapClickListener(it) } }
        val wrapped = MapLibreMap.OnMapClickListener { point ->
            if (!canOperateActive()) return@OnMapClickListener false
            listener(CityMapCoordinate(point.latitude, point.longitude))
            true
        }
        activeClickListener = wrapped
        map.addOnMapClickListener(wrapped)
    }

    override fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit) {
        if (!canOperateActive()) return
        activeCameraIdleListener?.let { runCatching { map.removeOnCameraIdleListener(it) } }
        val wrapped = MapLibreMap.OnCameraIdleListener {
            if (!canOperateActive()) return@OnCameraIdleListener
            val target = map.cameraPosition.target ?: return@OnCameraIdleListener
            listener(CityMapCoordinate(target.latitude, target.longitude), map.cameraPosition.zoom)
        }
        activeCameraIdleListener = wrapped
        map.addOnCameraIdleListener(wrapped)
    }

    override fun clearMapListeners() {
        if (!canOperateBeforeDestroy()) {
            activeClickListener = null
            activeCameraIdleListener = null
            return
        }
        activeClickListener?.let { runCatching { map.removeOnMapClickListener(it) } }
        activeClickListener = null
        activeCameraIdleListener?.let { runCatching { map.removeOnCameraIdleListener(it) } }
        activeCameraIdleListener = null
    }

    override fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult {
        if (!canOperateActive()) return LocationComponentSyncResult.STYLE_NOT_READY
        val realStyle = (style as? RealMapStylePort)?.style ?: return LocationComponentSyncResult.STYLE_NOT_READY
        return enableMapLibreLocationComponent(context, map, realStyle)
    }

    override fun invalidate() {
        if (invalidated.compareAndSet(false, true)) {
            activeClickListener = null
            activeCameraIdleListener = null
        }
    }
}

internal class RealMapViewSurfacePort(
    context: Context,
) : MapViewSurfacePort {
    private val destroyed = AtomicBoolean(false)
    private val closingOrDestroyed = AtomicBoolean(false)

    private val mapView: MapView = MapView(context, MapLibreInitializer.createTextureMapOptions(context)).apply {
        @Suppress("ClickableViewAccessibility")
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    fun markClosing() {
        closingOrDestroyed.set(true)
    }

    override val androidView: View
        get() = mapView

    override val viewIdentity: Int
        get() = System.identityHashCode(mapView)

    override fun onCreate() {
        if (!destroyed.get()) mapView.onCreate(null)
    }

    override fun onStart() {
        if (!destroyed.get()) mapView.onStart()
    }

    override fun onResume() {
        if (!destroyed.get()) mapView.onResume()
    }

    override fun onPause() {
        if (!destroyed.get()) mapView.onPause()
    }

    override fun onStop() {
        if (!destroyed.get()) mapView.onStop()
    }

    override fun onDestroy() {
        closingOrDestroyed.set(true)
        if (destroyed.compareAndSet(false, true)) {
            mapView.onDestroy()
        }
    }

    override fun onLowMemory() {
        if (!destroyed.get()) mapView.onLowMemory()
    }

    override fun inspectSurfaceInfo(): String {
        if (destroyed.get()) {
            return "MapView=destroyed"
        }
        val renderView = runCatching { mapView.renderView }.getOrNull()
        val renderViewClass = renderView?.javaClass?.simpleName ?: "none"
        val textureView = renderView as? TextureView
        val textureAvailable = textureView?.isAvailable
        val hasSurfaceTexture = textureView?.surfaceTexture != null
        return "MapView=${mapView.width}x${mapView.height}, attached=${mapView.isAttachedToWindow}, renderView=$renderViewClass, textureAvailable=$textureAvailable, hasSurfaceTexture=$hasSurfaceTexture"
    }

    override fun postToUi(action: () -> Unit) {
        if (destroyed.get() || closingOrDestroyed.get()) return
        mapView.post {
            if (!destroyed.get() && !closingOrDestroyed.get()) {
                action()
            }
        }
    }

    override fun attachWindowObserver(onAttachStateChanged: (Boolean, String) -> Unit): () -> Unit {
        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (!destroyed.get()) {
                        onAttachStateChanged(true, inspectSurfaceInfo())
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    onAttachStateChanged(false, inspectSurfaceInfo())
                }
            }
        mapView.addOnAttachStateChangeListener(listener)
        return {
            runCatching { mapView.removeOnAttachStateChangeListener(listener) }
        }
    }

    override fun attachListeners(callbacks: MapSurfaceCallbacks): () -> Unit {
        val firstFrameDelivered = AtomicBoolean(false)
        val willStartLoadingListener = MapView.OnWillStartLoadingMapListener {
            if (!destroyed.get() && !closingOrDestroyed.get()) callbacks.onWillStartLoading()
        }
        val styleLoadedListener = MapView.OnDidFinishLoadingStyleListener {
            if (!destroyed.get() && !closingOrDestroyed.get()) callbacks.onDidFinishLoadingStyle()
        }
        val mapLoadedListener = MapView.OnDidFinishLoadingMapListener {
            if (!destroyed.get() && !closingOrDestroyed.get()) callbacks.onDidFinishLoadingMap()
        }
        val mapFailListener = MapView.OnDidFailLoadingMapListener { err ->
            if (!destroyed.get() && !closingOrDestroyed.get()) callbacks.onDidFailLoadingMap(err)
        }
        val willStartRenderingListener = MapView.OnWillStartRenderingMapListener {
            if (!destroyed.get() && !closingOrDestroyed.get()) callbacks.onWillStartRenderingMap()
        }
        val frameRenderedListener =
            MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                if (!destroyed.get() && !closingOrDestroyed.get() && firstFrameDelivered.compareAndSet(false, true)) {
                    callbacks.onFirstFrameRendered()
                }
            }
        val mapRenderedListener = MapView.OnDidFinishRenderingMapListener { fully ->
            if (!destroyed.get() && !closingOrDestroyed.get()) callbacks.onDidFinishRenderingMap(fully)
        }
        val renderErrorListener = MapView.OnRenderErrorListener {
            if (!destroyed.get() && !closingOrDestroyed.get()) {
                callbacks.onRenderError("Erro nativo de renderização OpenGL/TextureView.")
            }
        }
        val shaderErrorListener = MapView.OnShaderCompileFailedListener { id, _, _ ->
            if (!destroyed.get() && !closingOrDestroyed.get()) {
                callbacks.onRenderError("Falha ao compilar shader OpenGL (id=$id).")
            }
        }

        mapView.addOnWillStartLoadingMapListener(willStartLoadingListener)
        mapView.addOnDidFinishLoadingStyleListener(styleLoadedListener)
        mapView.addOnDidFinishLoadingMapListener(mapLoadedListener)
        mapView.addOnDidFailLoadingMapListener(mapFailListener)
        mapView.addOnWillStartRenderingMapListener(willStartRenderingListener)
        mapView.addOnDidFinishRenderingFrameListener(frameRenderedListener)
        mapView.addOnDidFinishRenderingMapListener(mapRenderedListener)
        mapView.addOnRenderErrorListener(renderErrorListener)
        mapView.addOnShaderCompileFailedListener(shaderErrorListener)

        return {
            runCatching {
                mapView.removeOnWillStartLoadingMapListener(willStartLoadingListener)
                mapView.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
                mapView.removeOnDidFinishLoadingMapListener(mapLoadedListener)
                mapView.removeOnDidFailLoadingMapListener(mapFailListener)
                mapView.removeOnWillStartRenderingMapListener(willStartRenderingListener)
                mapView.removeOnDidFinishRenderingFrameListener(frameRenderedListener)
                mapView.removeOnDidFinishRenderingMapListener(mapRenderedListener)
                mapView.removeOnRenderErrorListener(renderErrorListener)
                mapView.removeOnShaderCompileFailedListener(shaderErrorListener)
            }
        }
    }

    override fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit) {
        if (destroyed.get() || closingOrDestroyed.get()) return
        mapView.getMapAsync { map ->
            if (destroyed.get() || closingOrDestroyed.get()) return@getMapAsync
            onReady(
                RealMapLibreControllerPort(
                    map = map,
                    isHostDestroyed = { destroyed.get() },
                    isHostActive = { !destroyed.get() && !closingOrDestroyed.get() },
                ),
            )
        }
    }
}

internal enum class MapSessionPhase {
    ACTIVE,
    CLOSING,
    DESTROYED,
}

/**
 * Coordinates strict, isolated, idempotent MapView session teardown:
 * 1. Marks session as CLOSING (blocking new callbacks and camera operations immediately).
 * 2. Cancels active camera transitions and clears MapLibre listeners ONCE while MapView is still valid.
 * 3. Detaches MapView listeners once.
 * 4. Pauses and stops the MapView (`stopIfActive`) BEFORE View detachment.
 * 5. Detaches window observers and destroys the MapView (`controller.destroy`) exactly ONCE.
 * 6. Transitions session to DESTROYED and invalidates all session references.
 */
internal class MapViewSessionHandle(
    val screenName: String,
    val sessionId: String,
    val surfacePort: MapViewSurfacePort,
    val controller: MapViewLifecycleController,
    private val onDetachListeners: () -> Unit,
    private val onDetachWindowObserver: () -> Unit = {},
    private val onCancelTransitions: () -> Unit,
    private val onInvalidateSession: () -> Unit = {},
) {
    private val lock = Any()
    @Volatile
    private var phase: MapSessionPhase = MapSessionPhase.ACTIVE
    private val transitionsCancelled = AtomicBoolean(false)
    private val listenersDetached = AtomicBoolean(false)
    private val windowObserverDetached = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    val currentPhase: MapSessionPhase
        get() = phase

    val isSessionActive: Boolean
        get() = phase == MapSessionPhase.ACTIVE &&
            !destroyed.get() &&
            controller.currentStage != MapViewLifecycleController.Stage.DESTROYED

    val isClosingOrDestroyed: Boolean
        get() = phase != MapSessionPhase.ACTIVE || destroyed.get()

    val isDestroyed: Boolean
        get() = phase == MapSessionPhase.DESTROYED ||
            destroyed.get() ||
            controller.currentStage == MapViewLifecycleController.Stage.DESTROYED

    init {
        com.example.diagnostics.GraphicsSurfaceCoordinator.onMapSurfaceCreated(
            screen = screenName,
            sessionId = sessionId,
            viewIdentity = surfacePort.viewIdentity,
            surfaceInfo = runCatching { surfacePort.inspectSurfaceInfo() }.getOrNull(),
        )
    }

    fun onLifecycleEvent(event: androidx.lifecycle.Lifecycle.Event) {
        if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
            destroyOnceAfterDetach("lifecycle_on_destroy")
            return
        }
        synchronized(lock) {
            if (phase != MapSessionPhase.ACTIVE || destroyed.get()) return
            controller.onLifecycleEvent(event)
        }
    }

    fun dispatchLowMemory() {
        synchronized(lock) {
            if (phase != MapSessionPhase.ACTIVE || destroyed.get()) return
            controller.dispatchLowMemory()
        }
    }

    fun pauseAndStopBeforeDetach(stage: String) {
        val firstClosingTransition: Boolean
        val shouldLogDispose: Boolean
        synchronized(lock) {
            if (phase == MapSessionPhase.DESTROYED ||
                destroyed.get() ||
                controller.currentStage == MapViewLifecycleController.Stage.DESTROYED
            ) {
                return
            }
            firstClosingTransition = (phase == MapSessionPhase.ACTIVE)
            if (firstClosingTransition) {
                phase = MapSessionPhase.CLOSING
                (surfacePort as? RealMapViewSurfacePort)?.markClosing()
            }
            shouldLogDispose = firstClosingTransition || (stage != "release" && stage != "lifecycle_on_destroy")
        }

        val surfaceSnapshot = runCatching { surfacePort.inspectSurfaceInfo() }.getOrNull()
        if (shouldLogDispose) {
            com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics.logMapViewDisposeRequested(
                screen = screenName,
                sessionId = sessionId,
                viewIdentity = surfacePort.viewIdentity,
                stage = stage,
                surfaceInfo = surfaceSnapshot,
            )
        }
        if (firstClosingTransition) {
            com.example.diagnostics.GraphicsSurfaceCoordinator.onMapSurfaceDisposing(
                screen = screenName,
                sessionId = sessionId,
                viewIdentity = surfacePort.viewIdentity,
                stage = stage,
                surfaceInfo = surfaceSnapshot,
            )
        }

        synchronized(lock) {
            if (phase == MapSessionPhase.DESTROYED ||
                destroyed.get() ||
                controller.currentStage == MapViewLifecycleController.Stage.DESTROYED
            ) {
                return
            }
            // Step 3: Cancel active camera transitions and clear map listeners ONCE while MapView is still valid
            if (transitionsCancelled.compareAndSet(false, true)) {
                runCatching { onCancelTransitions() }
            }
            // Step 4: Detach MapView listeners once
            if (listenersDetached.compareAndSet(false, true)) {
                runCatching { onDetachListeners() }
            }
            // Step 5: Pause & stop MapView before View detachment
            controller.stopIfActive()
        }
    }

    fun destroyOnceAfterDetach(stage: String = "release") {
        pauseAndStopBeforeDetach(stage)
        val shouldDestroy: Boolean
        val surfaceSnapshot = runCatching { surfacePort.inspectSurfaceInfo() }.getOrNull()
        synchronized(lock) {
            if (windowObserverDetached.compareAndSet(false, true)) {
                runCatching { onDetachWindowObserver() }
            }
            shouldDestroy =
                if (destroyed.compareAndSet(false, true)) {
                    phase = MapSessionPhase.DESTROYED
                    true
                } else {
                    phase = MapSessionPhase.DESTROYED
                    false
                }
        }
        if (shouldDestroy) {
            controller.destroy()
            runCatching { onInvalidateSession() }
            com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics.logMapViewDestroyed(
                screen = screenName,
                sessionId = sessionId,
                viewIdentity = surfacePort.viewIdentity,
                surfaceInfo = surfaceSnapshot,
            )
            com.example.diagnostics.GraphicsSurfaceCoordinator.onMapSurfaceDetached(
                screen = screenName,
                sessionId = sessionId,
                viewIdentity = surfacePort.viewIdentity,
                surfaceInfo = surfaceSnapshot,
            )
        }
    }
}

