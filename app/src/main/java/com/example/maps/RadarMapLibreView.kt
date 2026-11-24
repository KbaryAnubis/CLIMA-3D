package com.example.maps

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.RainViewerRadarFrame
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaTextMutedOnDark
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

private const val RADAR_OSM_SOURCE_ID = "clima-radar-osm-source"
private const val RADAR_OSM_LAYER_ID = "clima-radar-osm-layer"
private const val RADAR_RAIN_SOURCE_PREFIX = "clima-radar-rainviewer-source-"
private const val RADAR_RAIN_LAYER_ID = "clima-radar-rainviewer-layer"

/**
 * Native MapLibre + OpenStreetMap map host for the Radar screen that dynamically overlays
 * real RainViewer precipitation tiles validated via [MeteorologicalRadarTileProvider].
 */
@Composable
fun RadarMapLibreView(
    center: CityMapCoordinate,
    zoom: Double,
    activeFrame: RainViewerRadarFrame?,
    layerStyle: RadarBaseStyle,
    maxRadarZoom: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val tileProvider = remember(activeFrame?.tileUrlTemplate) {
        activeFrame?.toTileProvider()
    }
    val layerSpec = remember(activeFrame?.tileUrlTemplate, layerStyle) {
        activeFrame?.toLayerSpec(
            transparency = 0.28f,
            baseStyle = layerStyle,
        )
    }

    val mapLibreReady = remember(context) { MapLibreInitializer.ensureInitialized(context) }
    if (!mapLibreReady) {
        Box(
            modifier = modifier.fillMaxSize().padding(16.dp).testTag("radar_maplibre_jvm_surface"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
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
        }
        return
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyleRef by remember { mutableStateOf<Style?>(null) }
    var activeRainSourceId by remember { mutableStateOf<String?>(null) }

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

    // Dynamically swap RainViewer raster layer whenever activeFrame or layerStyle changes
    LaunchedEffect(loadedStyleRef, activeFrame?.tileUrlTemplate, layerStyle, maxRadarZoom) {
        val style = loadedStyleRef ?: return@LaunchedEffect
        style.removeLayer(RADAR_RAIN_LAYER_ID)
        activeRainSourceId?.let { previousId -> style.removeSource(previousId) }
        activeRainSourceId = null

        if (layerStyle == RadarBaseStyle.PRECIPITATION_MAP && tileProvider != null && tileProvider.isValidTemplate() && layerSpec != null) {
            val sourceId = "$RADAR_RAIN_SOURCE_PREFIX${activeFrame?.timestampSeconds ?: 0L}"
            val tileSet =
                TileSet("2.2", tileProvider.tileUrlTemplate).apply {
                    minZoom = 0f
                    maxZoom = maxRadarZoom.toFloat()
                    attribution = RainViewerRadarFrame.ATTRIBUTION
                }
            style.addSource(RasterSource(sourceId, tileSet, tileProvider.tileWidthPx))
            style.addLayer(
                RasterLayer(RADAR_RAIN_LAYER_ID, sourceId).withProperties(
                    rasterOpacity(layerSpec.opacity),
                ),
            )
            activeRainSourceId = sourceId
        }
    }

    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mapView = mapViewRef
        val observer =
            LifecycleEventObserver { _, event ->
                if (mapView == null) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize().testTag("radar_maplibre_native_view"),
        factory = { viewContext ->
            MapView(viewContext).apply {
                onCreate(null)
                onStart()
                onResume()
                mapViewRef = this

                getMapAsync { map ->
                    mapLibreMapRef = map
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

                    val osmTiles =
                        TileSet("2.2", OpenStreetMapTileConfig.TILE_URL).apply {
                            minZoom = 0f
                            maxZoom = OpenStreetMapTileConfig.MAX_ZOOM.toFloat()
                            attribution = OpenStreetMapTileConfig.ATTRIBUTION
                        }
                    val styleBuilder =
                        Style.Builder()
                            .withSource(RasterSource(RADAR_OSM_SOURCE_ID, osmTiles, OpenStreetMapTileConfig.TILE_SIZE_PX))
                            .withLayer(RasterLayer(RADAR_OSM_LAYER_ID, RADAR_OSM_SOURCE_ID))

                    map.setStyle(styleBuilder) { style ->
                        loadedStyleRef = style
                    }
                }
            }
        },
    )
}
