package com.example.maps

import android.content.Context
import android.os.Build
import com.example.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * OpenStreetMap tile source and valid MapLibre Style Specification v8 JSON builder adhering
 * to OSM Tile Usage Policy (descriptive User-Agent, explicit © OpenStreetMap contributors
 * attribution, no billing key required).
 */
object OpenStreetMapTileConfig {
    const val OSM_SOURCE_ID = "clima-osm-raster-source"
    const val OSM_LAYER_ID = "clima-osm-raster-layer"

    const val RADAR_OSM_SOURCE_ID = "clima-radar-osm-source"
    const val RADAR_OSM_LAYER_ID = "clima-radar-osm-layer"

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
    const val MAP_BACKGROUND_HEX = "#082035"

    val userAgent: String
        get() = "Clima3D-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

    /**
     * Builds a complete, valid MapLibre Style Specification v8 JSON containing:
     * - A dark cartographic background layer (prevents white/blank flash before first tile arrives).
     * - The official OpenStreetMap raster tile source (`{z}/{x}/{y}.png`, 256px, zoom 0..19).
     * - The base raster layer referencing the OSM source.
     *
     * Supplying a valid v8 JSON via [Style.Builder.fromJson] is mandatory in MapLibre Native Android;
     * calling `Style.Builder()` without `.fromJson(...)` or `.fromUri(...)` fails to initialize the
     * style document and can leave the map blank with `OnStyleLoaded` never firing.
     */
    fun buildBaseOsmStyleJson(
        sourceId: String = OSM_SOURCE_ID,
        layerId: String = OSM_LAYER_ID,
        backgroundColorHex: String = MAP_BACKGROUND_HEX,
    ): String {
        return """
            {
              "version": 8,
              "name": "Clima 3D OpenStreetMap Base",
              "sources": {
                "$sourceId": {
                  "type": "raster",
                  "tiles": ["$TILE_URL"],
                  "tileSize": $TILE_SIZE_PX,
                  "minzoom": 0,
                  "maxzoom": ${MAX_ZOOM.toInt()},
                  "attribution": "$ATTRIBUTION"
                }
              },
              "layers": [
                {
                  "id": "clima-map-background",
                  "type": "background",
                  "paint": {
                    "background-color": "$backgroundColorHex"
                  }
                },
                {
                  "id": "$layerId",
                  "type": "raster",
                  "source": "$sourceId",
                  "minzoom": 0,
                  "maxzoom": 22
                }
              ]
            }
        """.trimIndent()
    }

    /**
     * Builds the Cities map style from a valid MapLibre Style v8 JSON with OpenStreetMap base tiles,
     * plus dynamic GeoJSON marker sources and CircleLayers for saved locations and the active pin.
     */
    fun buildStyleBuilder(
        savedMarkersGeoJson: String,
        selectedMarkerGeoJson: String,
    ): Style.Builder {
        val baseJson =
            buildBaseOsmStyleJson(
                sourceId = OSM_SOURCE_ID,
                layerId = OSM_LAYER_ID,
            )
        return Style.Builder()
            .fromJson(baseJson)
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

    /**
     * Builds the Radar base map style strictly separated from the RainViewer overlay.
     * The RainViewer precipitation layer is attached dynamically only after this base OSM style loads.
     */
    fun buildRadarBaseStyleBuilder(): Style.Builder {
        val baseJson =
            buildBaseOsmStyleJson(
                sourceId = RADAR_OSM_SOURCE_ID,
                layerId = RADAR_OSM_LAYER_ID,
            )
        return Style.Builder().fromJson(baseJson)
    }
}

/**
 * Thread-safe singleton initializer for MapLibre Native Android SDK (OpenGL ES backend) with a custom
 * OkHttpClient that attaches an OpenStreetMap-compliant User-Agent header, enforces timeouts, and logs
 * tile transport errors under [ClimaMapLogTags.OSM_TILES] and [ClimaMapLogTags.RAINVIEWER_TILES].
 */
object MapLibreInitializer {
    @Volatile
    private var initialized = false

    @Volatile
    var lastInitError: String? = null
        private set

    private val loggedFirstOsmTile = AtomicBoolean(false)
    private val loggedFirstRainViewerTile = AtomicBoolean(false)

    fun isRobolectricEnvironment(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    fun isInitialized(): Boolean = initialized

    /**
     * Creates [MapLibreMapOptions] configured with `textureMode(true)` (TextureView instead of SurfaceView)
     * so MapLibre composites reliably inside Jetpack Compose `AndroidView`, rounded clips, scrollable
     * containers (`LazyColumn`/`verticalScroll`), and over `WeatherVideoBackground` without blank frames.
     */
    fun createTextureMapOptions(context: Context): MapLibreMapOptions =
        MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)

    /**
     * Builds the OSM-compliant OkHttpClient shared with MapLibre's HTTP layer.
     */
    fun buildTileOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host
                val request =
                    original
                        .newBuilder()
                        .header("User-Agent", OpenStreetMapTileConfig.userAgent)
                        .build()
                val logTag = classifyTileLogTag(host)
                try {
                    val response = chain.proceed(request)
                    if (!response.isSuccessful) {
                        ClimaMapLogger.w(
                            logTag,
                            "HTTP ${response.code} ao buscar tile em $host (${original.url.encodedPath})",
                        )
                    } else if (logTag == ClimaMapLogTags.OSM_TILES && loggedFirstOsmTile.compareAndSet(false, true)) {
                        ClimaMapLogger.i(logTag, "Primeiro tile OpenStreetMap recebido com sucesso (HTTP ${response.code}).")
                    } else if (logTag == ClimaMapLogTags.RAINVIEWER_TILES && loggedFirstRainViewerTile.compareAndSet(false, true)) {
                        ClimaMapLogger.i(logTag, "Primeiro tile RainViewer recebido com sucesso (HTTP ${response.code}).")
                    }
                    response
                } catch (ioe: IOException) {
                    ClimaMapLogger.e(
                        logTag,
                        "Falha de rede/TLS/timeout ao buscar tile em $host: ${ioe.javaClass.simpleName}: ${ioe.message}",
                        ioe,
                    )
                    throw ioe
                }
            }
            .build()

    internal fun classifyTileLogTag(host: String): String =
        if (host.contains("rainviewer", ignoreCase = true)) {
            ClimaMapLogTags.RAINVIEWER_TILES
        } else {
            ClimaMapLogTags.OSM_TILES
        }

    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (initialized) return true
            val appContext = context.applicationContext ?: context
            ClimaMapLogger.i(
                ClimaMapLogTags.MAPLIBRE_INIT,
                "Inicializando MapLibre Native (OpenGL ES 13.7.1) com User-Agent=${OpenStreetMapTileConfig.userAgent}",
            )
            return try {
                MapLibre.getInstance(appContext)
                HttpRequestUtil.setOkHttpClient(buildTileOkHttpClient())
                lastInitError = null
                initialized = true
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAPLIBRE_INIT,
                    "MapLibre inicializado com sucesso (OkHttp + User-Agent OSM configurados).",
                )
                true
            } catch (e: UnsatisfiedLinkError) {
                lastInitError = "Biblioteca nativa MapLibre indisponível neste ambiente: ${e.message}"
                if (isRobolectricEnvironment()) {
                    ClimaMapLogger.i(
                        ClimaMapLogTags.MAPLIBRE_INIT,
                        "Ambiente JVM/Robolectric detectado sem binários nativos GL; usando superfície segura de teste.",
                    )
                    false
                } else {
                    ClimaMapLogger.e(ClimaMapLogTags.MAPLIBRE_INIT, lastInitError.orEmpty(), e)
                    false
                }
            } catch (t: Throwable) {
                lastInitError = "Erro ao inicializar MapLibre: ${t.javaClass.simpleName}: ${t.message}"
                ClimaMapLogger.e(ClimaMapLogTags.MAPLIBRE_INIT, lastInitError.orEmpty(), t)
                false
            }
        }
    }
}
