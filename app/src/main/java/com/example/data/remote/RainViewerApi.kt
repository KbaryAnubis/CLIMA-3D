package com.example.data.remote

import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.RainViewerRadarFrame
import com.example.data.model.TimeFormatPreference
import com.example.data.model.WeatherTimeUtils
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET

/**
 * Retrofit contract for the official RainViewer Weather Maps API metadata endpoint.
 * Documentation: https://www.rainviewer.com/api.html
 *
 * Current public API constraints (2026):
 * - Endpoint: https://api.rainviewer.com/public/weather-maps.json
 * - Provides historical `radar.past` frames only (nowcast discontinued on public tier).
 * - Universal Blue color scheme (2) and max zoom 7 on free tier.
 */
interface RainViewerApi {
    @GET("public/weather-maps.json")
    suspend fun getWeatherMaps(): RainViewerMapsResponseDto

    companion object {
        const val PROVIDER_NAME: String = "RainViewer"
    }
}

@JsonClass(generateAdapter = true)
data class RainViewerMapsResponseDto(
    @Json(name = "version") val version: String? = null,
    @Json(name = "generated") val generated: Long? = null,
    @Json(name = "host") val host: String? = null,
    @Json(name = "radar") val radar: RainViewerRadarSectionDto? = null,
)

@JsonClass(generateAdapter = true)
data class RainViewerRadarSectionDto(
    @Json(name = "past") val past: List<RainViewerFrameDto>? = null,
    @Json(name = "nowcast") val nowcast: List<RainViewerFrameDto>? = null,
)

@JsonClass(generateAdapter = true)
data class RainViewerFrameDto(
    @Json(name = "time") val time: Long? = null,
    @Json(name = "path") val path: String? = null,
)

/**
 * Maps RainViewer's official metadata JSON into [RadarTimelineSnapshot]:
 * - Uses ONLY real historical frames from `radar.past` (never fabricates future/nowcast frames).
 * - Selects up to 9 most recent chronological frames to match Clima 3D V53's 9-frame timeline.
 * - Builds tile templates directly usable by [com.example.maps.MeteorologicalRadarTileProvider].
 */
object RainViewerMapper {

    fun buildTileUrlTemplate(
        host: String,
        path: String,
        tileSizePx: Int = RainViewerRadarFrame.DEFAULT_TILE_SIZE_PX,
        colorScheme: Int = RainViewerRadarFrame.UNIVERSAL_BLUE_COLOR_SCHEME,
        options: String = RainViewerRadarFrame.DEFAULT_OPTIONS,
    ): String {
        val cleanHost = host.trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$cleanHost$cleanPath/$tileSizePx/{z}/{x}/{y}/$colorScheme/$options.png"
    }

    fun toDomain(
        dto: RainViewerMapsResponseDto,
        fetchedAtEpochMillis: Long,
        displayTimezoneId: String? = null,
        displayUtcOffsetSeconds: Int = 0,
        timeFormat: TimeFormatPreference = TimeFormatPreference.H24,
        maxFrames: Int = RadarTimelineSnapshot.V53_TARGET_FRAME_COUNT,
    ): RadarTimelineSnapshot? {
        val host = dto.host?.trim()?.takeIf { it.startsWith("http") } ?: return null
        val validPast = dto.radar?.past.orEmpty()
            .mapNotNull { frame ->
                val time = frame.time ?: return@mapNotNull null
                val path = frame.path?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                time to path
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (validPast.isEmpty()) return null

        val selectedPast = if (maxFrames > 0 && validPast.size > maxFrames) {
            validPast.takeLast(maxFrames)
        } else {
            validPast
        }

        val mappedFrames = selectedPast.map { (timestampSeconds, path) ->
            RainViewerRadarFrame(
                timestampSeconds = timestampSeconds,
                path = path,
                tileUrlTemplate = buildTileUrlTemplate(host = host, path = path),
                tileSizePx = RainViewerRadarFrame.DEFAULT_TILE_SIZE_PX,
                colorScheme = RainViewerRadarFrame.UNIVERSAL_BLUE_COLOR_SCHEME,
                options = RainViewerRadarFrame.DEFAULT_OPTIONS,
                formattedLocalTime = WeatherTimeUtils.formatLocalTime(
                    epochMillis = timestampSeconds * 1000L,
                    timezoneId = displayTimezoneId,
                    utcOffsetSeconds = displayUtcOffsetSeconds,
                    timeFormat = timeFormat,
                ),
            )
        }

        val hasRealNowcast = !dto.radar?.nowcast.isNullOrEmpty()

        return RadarTimelineSnapshot(
            apiVersion = dto.version?.takeIf { it.isNotBlank() } ?: "2.0",
            generatedEpochSeconds = dto.generated ?: mappedFrames.last().timestampSeconds,
            host = host,
            frames = mappedFrames,
            totalPastFramesAvailable = validPast.size,
            maxSupportedZoom = RadarTimelineSnapshot.PUBLIC_MAX_ZOOM,
            nowcastAvailable = hasRealNowcast,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
    }
}
