package com.example.maps

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * OpenStreetMap tile source configuration adhering to OSM Tile Usage Policy
 * (descriptive User-Agent, explicit © OpenStreetMap contributors attribution,
 * no billing key required).
 */
object OpenStreetMapTileConfig {
    const val OSM_SOURCE_ID = "clima-osm-raster-source"
    const val OSM_LAYER_ID = "clima-osm-raster-layer"

    const val SAVED_MARKERS_SOURCE_ID = "clima-saved-markers-source"
    const val SAVED_MARKERS_LAYER_ID = "clima-saved-markers-layer"

    const val SELECTED_MARKER_SOURCE_ID = "clima-selected-marker-source"
    const val SELECTED_MARKER_HALO_LAYER_ID = "clima-selected-marker-halo-layer"
    const val SELECTED_MARKER_CORE_LAYER_ID = "clima-selected-marker-core-layer"

    const val TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val ATTRIBUTION = "© OpenStreetMap contributors"
    const val TILE_SIZE_PX = 256
    const val MIN_ZOOM = 2.0
    const val MAX_ZOOM = 19.0

    val userAgent: String
        get() = "Clima3D-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

    fun buildStyleBuilder(
        savedMarkersGeoJson: String,
        selectedMarkerGeoJson: String,
    ): org.maplibre.android.maps.Style.Builder {
        val tileSet =
            TileSet("2.2", TILE_URL).apply {
                minZoom = 0f
                maxZoom = MAX_ZOOM.toFloat()
                attribution = ATTRIBUTION
            }

        return org.maplibre.android.maps.Style.Builder()
            .withSource(RasterSource(OSM_SOURCE_ID, tileSet, TILE_SIZE_PX))
            .withLayer(RasterLayer(OSM_LAYER_ID, OSM_SOURCE_ID))
            .withSource(GeoJsonSource(SAVED_MARKERS_SOURCE_ID, savedMarkersGeoJson))
            .withLayer(
                CircleLayer(SAVED_MARKERS_LAYER_ID, SAVED_MARKERS_SOURCE_ID).withProperties(
                    circleRadius(7f),
                    circleColor("#53BDFF"),
                    circleStrokeWidth(2f),
                    circleStrokeColor("#FFFFFF"),
                    circleOpacity(0.92f),
                )
            )
            .withSource(GeoJsonSource(SELECTED_MARKER_SOURCE_ID, selectedMarkerGeoJson))
            .withLayer(
                CircleLayer(SELECTED_MARKER_HALO_LAYER_ID, SELECTED_MARKER_SOURCE_ID).withProperties(
                    circleRadius(18f),
                    circleColor("#53BDFF"),
                    circleOpacity(0.28f),
                )
            )
            .withLayer(
                CircleLayer(SELECTED_MARKER_CORE_LAYER_ID, SELECTED_MARKER_SOURCE_ID).withProperties(
                    circleRadius(9f),
                    circleColor("#FF7186"),
                    circleStrokeWidth(2.5f),
                    circleStrokeColor("#FFFFFF"),
                    circleOpacity(1.0f),
                )
            )
    }
}

/**
 * Thread-safe singleton initializer for MapLibre Native Android SDK with a custom
 * OkHttpClient that attaches an OpenStreetMap-compliant User-Agent header.
 */
object MapLibreInitializer {
    @Volatile
    private var initialized = false

    fun isRobolectricEnvironment(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            return try {
                val appContext = context.applicationContext
                MapLibre.getInstance(appContext)
                val osmHttpClient =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val request =
                                chain.request()
                                    .newBuilder()
                                    .header("User-Agent", OpenStreetMapTileConfig.userAgent)
                                    .build()
                            chain.proceed(request)
                        }
                        .build()
                HttpRequestUtil.setOkHttpClient(osmHttpClient)
                initialized = true
                true
            } catch (e: UnsatisfiedLinkError) {
                if (isRobolectricEnvironment()) false else throw e
            }
        }
    }
}
