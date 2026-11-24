package com.example.maps

import android.content.Context
import android.graphics.Color
import android.os.Build
import com.example.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Dispatcher
import okhttp3.Interceptor
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
     * - The official OpenStreetMap raster tile source (`{z}/{x}/{y}.png`, 256px, xyz, zoom 0..19).
     * - The base raster layer referencing the OSM source.
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
                  "scheme": "xyz",
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
 * OkHttpClient explicitly bound via [HttpRequestUtil.setOkHttpClient] so every OSM & RainViewer
 * request uses the compliant User-Agent, concurrency limits, timeouts, and diagnostic telemetry.
 */
object MapLibreInitializer {
    @Volatile
    private var initialized = false

    @Volatile
    var lastInitError: String? = null
        private set

    private val osmRequestCounter = AtomicInteger(0)
    private val osmSuccessCounter = AtomicInteger(0)
    private val rainViewerRequestCounter = AtomicInteger(0)
    private val rainViewerSuccessCounter = AtomicInteger(0)

    fun isRobolectricEnvironment(): Boolean =
        Build.FINGERPRINT.equals("robolectric", ignoreCase = true)

    fun isInitialized(): Boolean = initialized

    /**
     * Creates [MapLibreMapOptions] configured with `textureMode(true)` (TextureView instead of SurfaceView)
     * and dark ocean foreground load color so MapLibre composites reliably inside Jetpack Compose
     * `AndroidView`, rounded clips, scrollable containers, and over `WeatherVideoBackground`.
     */
    fun createTextureMapOptions(context: Context): MapLibreMapOptions =
        MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .foregroundLoadColor(Color.parseColor(OpenStreetMapTileConfig.MAP_BACKGROUND_HEX))
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)

    /**
     * Interceptor explicitly wired into MapLibre's `HttpRequestImpl` via `HttpRequestUtil.setOkHttpClient`.
     * Enforces the OpenStreetMap User-Agent header, logs non-spammy tile milestones and all errors,
     * and publishes real transport metrics into [TileTransportDiagnostics].
     */
    fun createTileTransportInterceptor(): Interceptor =
        Interceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val sanitizedUrl = "${original.url.scheme}://$host${original.url.encodedPath}"
            val isRainViewer = host.contains("rainviewer", ignoreCase = true)
            val logTag = classifyTileLogTag(host)

            val request =
                original
                    .newBuilder()
                    .header("User-Agent", OpenStreetMapTileConfig.userAgent)
                    .build()

            val reqSeq =
                if (isRainViewer) rainViewerRequestCounter.incrementAndGet()
                else osmRequestCounter.incrementAndGet()
            TileTransportDiagnostics.recordRequestStart(isRainViewer, sanitizedUrl)

            if (reqSeq == 1 || reqSeq % 20 == 0) {
                ClimaMapLogger.i(logTag, "Requisição de tile #$reqSeq -> $sanitizedUrl")
            }

            val startNs = System.nanoTime()
            try {
                val response = chain.proceed(request)
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                TileTransportDiagnostics.recordHttpResponse(
                    isRainViewer = isRainViewer,
                    statusCode = response.code,
                    durationMs = durationMs,
                    sanitizedUrl = sanitizedUrl,
                )

                if (!response.isSuccessful) {
                    ClimaMapLogger.w(
                        logTag,
                        "HTTP ${response.code} ao buscar tile em $sanitizedUrl (${durationMs}ms)",
                    )
                } else {
                    val okSeq =
                        if (isRainViewer) rainViewerSuccessCounter.incrementAndGet()
                        else osmSuccessCounter.incrementAndGet()
                    if (okSeq == 1 || okSeq % 20 == 0) {
                        ClimaMapLogger.i(
                            logTag,
                            "Tile recebido com sucesso #$okSeq (HTTP ${response.code}, ${durationMs}ms) <- $sanitizedUrl",
                        )
                    }
                }
                response
            } catch (ioe: IOException) {
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                val callCanceled = runCatching { chain.call().isCanceled() }.getOrDefault(false)
                if (TileTransportDiagnostics.isCancellationThrowable(ioe, callCanceled)) {
                    TileTransportDiagnostics.recordTransportCanceled(
                        isRainViewer = isRainViewer,
                        durationMs = durationMs,
                        sanitizedUrl = sanitizedUrl,
                    )
                    ClimaMapLogger.i(
                        logTag,
                        "Requisição de tile cancelada por navegação, zoom ou ciclo de vida ($sanitizedUrl, ${durationMs}ms).",
                    )
                } else {
                    TileTransportDiagnostics.recordTransportFailure(
                        isRainViewer = isRainViewer,
                        error = ioe,
                        durationMs = durationMs,
                        sanitizedUrl = sanitizedUrl,
                        isCallCanceled = false,
                    )
                    ClimaMapLogger.e(
                        logTag,
                        "Falha de rede/DNS/TLS ao buscar tile ($sanitizedUrl, ${durationMs}ms): ${ioe.javaClass.simpleName}: ${ioe.message}",
                        ioe,
                    )
                }
                throw ioe
            }
        }

    /**
     * Builds the OSM-compliant OkHttpClient shared with MapLibre's HTTP layer (`HttpRequestUtil`).
     */
    fun buildTileOkHttpClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = 20
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(createTileTransportInterceptor())
            .build()
    }

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
            val startNs = System.nanoTime()
            ClimaMapLogger.i(
                ClimaMapLogTags.MAPLIBRE_INIT,
                "Inicializando MapLibre Native (OpenGL ES 13.7.1) com User-Agent=${OpenStreetMapTileConfig.userAgent}",
            )
            return try {
                MapLibre.getInstance(appContext)
                HttpRequestUtil.setOkHttpClient(buildTileOkHttpClient())
                HttpRequestUtil.setLogEnabled(BuildConfig.DEBUG)
                HttpRequestUtil.setPrintRequestUrlOnFailure(BuildConfig.DEBUG)
                val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                lastInitError = null
                initialized = true
                ClimaMapLogger.i(
                    ClimaMapLogTags.MAPLIBRE_INIT,
                    "MapLibre inicializado em ${durationMs}ms (HttpRequestUtil.setOkHttpClient ativo).",
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
