package com.example.maps

import android.content.Context
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import com.example.data.model.RainViewerRadarFrame
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

internal sealed interface MapStyleSpecRequest {
    data class Cities(
        val savedMarkersGeoJson: String,
        val selectedMarkerGeoJson: String,
    ) : MapStyleSpecRequest

    data object RadarBase : MapStyleSpecRequest
}

internal interface MapStylePort {
    val isFullyLoaded: Boolean
    fun updateGeoJsonSource(sourceId: String, geoJson: String)
    fun addRainViewerSourceAndLayer(
        sourceId: String,
        layerId: String,
        frame: RainViewerRadarFrame,
        maxRadarZoom: Int,
        opacity: Float,
        isVisible: Boolean,
        aboveLayerId: String,
    )
    fun setLayerVisibility(layerId: String, isVisible: Boolean)
    fun removeSourceAndLayer(sourceId: String, layerId: String)
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
) : MapStylePort {
    override val isFullyLoaded: Boolean
        get() = style.isFullyLoaded

    override fun updateGeoJsonSource(sourceId: String, geoJson: String) {
        style.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(geoJson)
    }

    override fun addRainViewerSourceAndLayer(
        sourceId: String,
        layerId: String,
        frame: RainViewerRadarFrame,
        maxRadarZoom: Int,
        opacity: Float,
        isVisible: Boolean,
        aboveLayerId: String,
    ) {
        val provider = frame.toTileProvider()
        val tileSet =
            TileSet("2.2", provider.tileUrlTemplate).apply {
                minZoom = 0f
                maxZoom = maxRadarZoom.toFloat()
                attribution = RainViewerRadarFrame.ATTRIBUTION
            }
        style.addSource(RasterSource(sourceId, tileSet, provider.tileWidthPx))
        val rainLayer =
            RasterLayer(layerId, sourceId).withProperties(
                rasterOpacity(opacity),
                visibility(if (isVisible) Property.VISIBLE else Property.NONE),
            )
        if (style.getLayer(aboveLayerId) != null) {
            style.addLayerAbove(rainLayer, aboveLayerId)
        } else {
            style.addLayer(rainLayer)
        }
    }

    override fun setLayerVisibility(layerId: String, isVisible: Boolean) {
        style.getLayerAs<RasterLayer>(layerId)?.setProperties(
            visibility(if (isVisible) Property.VISIBLE else Property.NONE),
        )
    }

    override fun removeSourceAndLayer(sourceId: String, layerId: String) {
        style.removeLayer(layerId)
        style.removeSource(sourceId)
    }
}

private class RealMapLibreControllerPort(
    private val map: MapLibreMap,
) : MapLibreControllerPort {
    private var activeClickListener: MapLibreMap.OnMapClickListener? = null
    private var activeCameraIdleListener: MapLibreMap.OnCameraIdleListener? = null

    override fun configureUiSettings() {
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
        map.cameraPosition =
            CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .build()
    }

    override fun animateCamera(latitude: Double, longitude: Double, zoom: Double, durationMs: Int) {
        val position =
            CameraPosition.Builder()
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position), durationMs)
    }

    override fun cancelTransitions() {
        map.cancelTransitions()
    }

    override fun setStyle(request: MapStyleSpecRequest, onLoaded: (MapStylePort) -> Unit) {
        val builder =
            when (request) {
                is MapStyleSpecRequest.Cities ->
                    OpenStreetMapTileConfig.buildStyleBuilder(
                        savedMarkersGeoJson = request.savedMarkersGeoJson,
                        selectedMarkerGeoJson = request.selectedMarkerGeoJson,
                    )
                MapStyleSpecRequest.RadarBase ->
                    OpenStreetMapTileConfig.buildRadarBaseStyleBuilder()
            }
        map.setStyle(builder) { style ->
            onLoaded(RealMapStylePort(style))
        }
    }

    override fun setOnMapClick(listener: (CityMapCoordinate) -> Unit) {
        activeClickListener?.let { runCatching { map.removeOnMapClickListener(it) } }
        val wrapped = MapLibreMap.OnMapClickListener { point ->
            listener(CityMapCoordinate(point.latitude, point.longitude))
            true
        }
        activeClickListener = wrapped
        map.addOnMapClickListener(wrapped)
    }

    override fun setOnCameraIdle(listener: (CityMapCoordinate, Double) -> Unit) {
        activeCameraIdleListener?.let { runCatching { map.removeOnCameraIdleListener(it) } }
        val wrapped = MapLibreMap.OnCameraIdleListener {
            val target = map.cameraPosition.target ?: return@OnCameraIdleListener
            listener(CityMapCoordinate(target.latitude, target.longitude), map.cameraPosition.zoom)
        }
        activeCameraIdleListener = wrapped
        map.addOnCameraIdleListener(wrapped)
    }

    override fun clearMapListeners() {
        activeClickListener?.let { runCatching { map.removeOnMapClickListener(it) } }
        activeClickListener = null
        activeCameraIdleListener?.let { runCatching { map.removeOnCameraIdleListener(it) } }
        activeCameraIdleListener = null
    }

    override fun syncLocationComponent(context: Context, style: MapStylePort): LocationComponentSyncResult {
        val realStyle = (style as? RealMapStylePort)?.style ?: return LocationComponentSyncResult.STYLE_NOT_READY
        return enableMapLibreLocationComponent(context, map, realStyle)
    }
}

internal class RealMapViewSurfacePort(
    context: Context,
) : MapViewSurfacePort {
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

    override val androidView: View
        get() = mapView

    override val viewIdentity: Int
        get() = System.identityHashCode(mapView)

    override fun onCreate() = mapView.onCreate(null)
    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onDestroy() = mapView.onDestroy()
    override fun onLowMemory() = mapView.onLowMemory()

    override fun inspectSurfaceInfo(): String {
        val renderView = runCatching { mapView.renderView }.getOrNull()
        val renderViewClass = renderView?.javaClass?.simpleName ?: "none"
        val textureView = renderView as? TextureView
        val textureAvailable = textureView?.isAvailable
        val hasSurfaceTexture = textureView?.surfaceTexture != null
        return "MapView=${mapView.width}x${mapView.height}, attached=${mapView.isAttachedToWindow}, renderView=$renderViewClass, textureAvailable=$textureAvailable, hasSurfaceTexture=$hasSurfaceTexture"
    }

    override fun postToUi(action: () -> Unit) {
        mapView.post(action)
    }

    override fun attachWindowObserver(onAttachStateChanged: (Boolean, String) -> Unit): () -> Unit {
        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    onAttachStateChanged(true, inspectSurfaceInfo())
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
        val willStartLoadingListener = MapView.OnWillStartLoadingMapListener { callbacks.onWillStartLoading() }
        val styleLoadedListener = MapView.OnDidFinishLoadingStyleListener { callbacks.onDidFinishLoadingStyle() }
        val mapLoadedListener = MapView.OnDidFinishLoadingMapListener { callbacks.onDidFinishLoadingMap() }
        val mapFailListener = MapView.OnDidFailLoadingMapListener { err -> callbacks.onDidFailLoadingMap(err) }
        val willStartRenderingListener = MapView.OnWillStartRenderingMapListener { callbacks.onWillStartRenderingMap() }
        val frameRenderedListener =
            MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
                if (firstFrameDelivered.compareAndSet(false, true)) {
                    callbacks.onFirstFrameRendered()
                }
            }
        val mapRenderedListener = MapView.OnDidFinishRenderingMapListener { fully -> callbacks.onDidFinishRenderingMap(fully) }
        val renderErrorListener = MapView.OnRenderErrorListener {
            callbacks.onRenderError("Erro nativo de renderização OpenGL/TextureView.")
        }
        val shaderErrorListener = MapView.OnShaderCompileFailedListener { id, _, _ ->
            callbacks.onRenderError("Falha ao compilar shader OpenGL (id=$id).")
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

    override fun getMapAsync(onReady: (MapLibreControllerPort) -> Unit) {
        mapView.getMapAsync { map ->
            onReady(RealMapLibreControllerPort(map))
        }
    }
}

/**
 * Coordinates clean, ordered MapView session teardown:
 * 1. Cancels active camera transitions and detaches MapView listeners once.
 * 2. Pauses and stops the MapView (`stopIfActive`) BEFORE View detachment.
 * 3. Destroys the MapView (`controller.destroy`) exactly ONCE after View detachment (`onRelease`).
 */
internal class MapViewSessionHandle(
    val screenName: String,
    val sessionId: String,
    val surfacePort: MapViewSurfacePort,
    val controller: MapViewLifecycleController,
    private val onDetachListeners: () -> Unit,
    private val onDetachWindowObserver: () -> Unit = {},
    private val onCancelTransitions: () -> Unit,
) {
    private val listenersDetached = AtomicBoolean(false)
    private val windowObserverDetached = AtomicBoolean(false)
    private val pausedAndStopped = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)

    init {
        com.example.diagnostics.GraphicsSurfaceCoordinator.onMapSurfaceCreated(
            screen = screenName,
            sessionId = sessionId,
            viewIdentity = surfacePort.viewIdentity,
            surfaceInfo = runCatching { surfacePort.inspectSurfaceInfo() }.getOrNull(),
        )
    }

    fun pauseAndStopBeforeDetach(stage: String) {
        val surfaceSnapshot = runCatching { surfacePort.inspectSurfaceInfo() }.getOrNull()
        val firstDisposeTransition = pausedAndStopped.compareAndSet(false, true)
        if (firstDisposeTransition || stage != "release") {
            com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics.logMapViewDisposeRequested(
                screen = screenName,
                sessionId = sessionId,
                viewIdentity = surfacePort.viewIdentity,
                stage = stage,
                surfaceInfo = surfaceSnapshot,
            )
        }
        if (firstDisposeTransition) {
            com.example.diagnostics.GraphicsSurfaceCoordinator.onMapSurfaceDisposing(
                screen = screenName,
                sessionId = sessionId,
                viewIdentity = surfacePort.viewIdentity,
                stage = stage,
                surfaceInfo = surfaceSnapshot,
            )
        }
        runCatching { onCancelTransitions() }
        if (listenersDetached.compareAndSet(false, true)) {
            runCatching { onDetachListeners() }
        }
        controller.stopIfActive()
    }

    fun destroyOnceAfterDetach() {
        pauseAndStopBeforeDetach("release")
        if (windowObserverDetached.compareAndSet(false, true)) {
            runCatching { onDetachWindowObserver() }
        }
        if (destroyed.compareAndSet(false, true)) {
            val surfaceSnapshot = runCatching { surfacePort.inspectSurfaceInfo() }.getOrNull()
            controller.destroy()
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

