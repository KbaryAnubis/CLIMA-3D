package com.example.data.model

import com.example.maps.MeteorologicalRadarTileProvider
import com.example.maps.RadarBaseStyle
import com.example.maps.WeatherRadarLayerSpec
import com.squareup.moshi.JsonClass

/**
 * Single historical RainViewer radar frame with real UTC timestamp and tile URL template.
 * Integrates directly with the preserved, cartography-independent [MeteorologicalRadarTileProvider].
 */
@JsonClass(generateAdapter = true)
data class RainViewerRadarFrame(
    val timestampSeconds: Long,
    val path: String,
    val tileUrlTemplate: String,
    val tileSizePx: Int = DEFAULT_TILE_SIZE_PX,
    val colorScheme: Int = UNIVERSAL_BLUE_COLOR_SCHEME,
    val options: String = DEFAULT_OPTIONS,
    val formattedLocalTime: String,
) {
    val epochMillis: Long
        get() = timestampSeconds * 1000L

    fun toTileProvider(): MeteorologicalRadarTileProvider =
        MeteorologicalRadarTileProvider(
            tileUrlTemplate = tileUrlTemplate,
            tileWidthPx = tileSizePx,
            tileHeightPx = tileSizePx,
        )

    fun toLayerSpec(
        transparency: Float = 0.35f,
        baseStyle: RadarBaseStyle = RadarBaseStyle.PRECIPITATION_MAP,
    ): WeatherRadarLayerSpec =
        WeatherRadarLayerSpec(
            tileUrlTemplate = tileUrlTemplate,
            transparency = transparency,
            baseStyle = baseStyle,
            attribution = ATTRIBUTION,
        )

    companion object {
        const val DEFAULT_TILE_SIZE_PX: Int = 512
        const val UNIVERSAL_BLUE_COLOR_SCHEME: Int = 2
        const val DEFAULT_OPTIONS: String = "1_1"
        const val ATTRIBUTION: String = "Radar meteorológico: RainViewer"
    }
}

/**
 * Snapshot of real RainViewer radar metadata and historical frames (capped to 9 frames per V53 spec).
 * Respects RainViewer's current public API limitations (historical past frames only; no invented nowcast).
 */
@JsonClass(generateAdapter = true)
data class RadarTimelineSnapshot(
    val apiVersion: String,
    val generatedEpochSeconds: Long,
    val host: String,
    val frames: List<RainViewerRadarFrame>,
    val totalPastFramesAvailable: Int,
    val maxSupportedZoom: Int = PUBLIC_MAX_ZOOM,
    val nowcastAvailable: Boolean = false,
    val fetchedAtEpochMillis: Long,
) {
    val latestFrame: RainViewerRadarFrame?
        get() = frames.lastOrNull()

    fun frameAt(index: Int): RainViewerRadarFrame? =
        frames.getOrNull(index)

    companion object {
        const val V53_TARGET_FRAME_COUNT: Int = 9
        const val PUBLIC_MAX_ZOOM: Int = 7
    }
}
