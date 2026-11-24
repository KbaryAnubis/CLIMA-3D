package com.example.data.repository

import com.example.data.local.db.RadarTimelineCacheEntity
import com.example.data.local.db.WeatherCacheDao
import com.example.data.model.CachePolicy
import com.example.data.model.CachedRecord
import com.example.data.model.DataSourceOrigin
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.TimeFormatPreference
import com.example.data.model.WeatherError
import com.example.data.model.WeatherResult
import com.example.data.model.WeatherTimeUtils
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerMapper
import com.example.data.remote.RemoteCallOutcome
import com.example.data.remote.SafeApiExecutor
import com.example.maps.ClimaMapLogTags
import com.example.maps.ClimaMapLogger
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline-first repository for RainViewer historical radar metadata and 9-frame animation timeline.
 * Produces real tile templates compatible with the independent [com.example.maps.MeteorologicalRadarTileProvider]
 * and never fabricates future/nowcast frames.
 */
class RadarRepository(
    private val rainViewerApi: RainViewerApi,
    private val cacheDao: WeatherCacheDao,
    private val connectivityObserver: ConnectivityObserver,
    private val moshi: Moshi,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val snapshotAdapter = moshi.adapter(RadarTimelineSnapshot::class.java)

    fun observeCachedRadar(): Flow<CachedRecord<RadarTimelineSnapshot>?> =
        cacheDao.observeRadar().map { entity ->
            entity?.let { decodeEntity(it) }
        }

    suspend fun getRadarTimeline(
        displayTimezoneId: String? = null,
        displayUtcOffsetSeconds: Int = 0,
        timeFormat: TimeFormatPreference = TimeFormatPreference.H24,
        policy: CachePolicy = CachePolicy.NetworkFirst,
    ): WeatherResult<RadarTimelineSnapshot> {
        val cachedRecord = cacheDao.getRadar()?.let { decodeEntity(it) }?.let { record ->
            record.copy(
                data = reformatFrameTimes(
                    snapshot = record.data,
                    displayTimezoneId = displayTimezoneId,
                    displayUtcOffsetSeconds = displayUtcOffsetSeconds,
                    timeFormat = timeFormat,
                ),
            )
        }
        val now = clock()

        when (policy) {
            CachePolicy.CacheOnly -> {
                return if (cachedRecord != null) {
                    WeatherResult.Success(
                        data = cachedRecord.data,
                        origin = DataSourceOrigin.LOCAL_CACHE,
                        fetchedAtEpochMillis = cachedRecord.fetchedAtEpochMillis,
                        staleReason = null,
                    )
                } else {
                    WeatherResult.Failure(
                        WeatherError.EmptyResponseError(RainViewerApi.PROVIDER_NAME),
                    )
                }
            }

            is CachePolicy.CacheIfFresh -> {
                if (cachedRecord != null && (now - cachedRecord.fetchedAtEpochMillis) <= policy.maxAgeMillis) {
                    return WeatherResult.Success(
                        data = cachedRecord.data,
                        origin = DataSourceOrigin.LOCAL_CACHE,
                        fetchedAtEpochMillis = cachedRecord.fetchedAtEpochMillis,
                        staleReason = null,
                    )
                }
            }

            CachePolicy.NetworkFirst -> Unit
        }

        if (!connectivityObserver.isOnline()) {
            val offlineError = WeatherError.NetworkUnavailableError(RainViewerApi.PROVIDER_NAME)
            ClimaMapLogger.w(
                ClimaMapLogTags.RADAR_FRAMES,
                "Sem conexão ao buscar frames RainViewer (cacheDisponivel=${cachedRecord != null}).",
            )
            return fallbackToCacheOrFail(cachedRecord, offlineError)
        }

        val outcome = SafeApiExecutor.execute(RainViewerApi.PROVIDER_NAME) {
            rainViewerApi.getWeatherMaps()
        }

        return when (outcome) {
            is RemoteCallOutcome.Success -> {
                val mapped = RainViewerMapper.toDomain(
                    dto = outcome.body,
                    fetchedAtEpochMillis = now,
                    displayTimezoneId = displayTimezoneId,
                    displayUtcOffsetSeconds = displayUtcOffsetSeconds,
                    timeFormat = timeFormat,
                    maxFrames = RadarTimelineSnapshot.V53_TARGET_FRAME_COUNT,
                )
                if (mapped == null || mapped.frames.isEmpty()) {
                    ClimaMapLogger.w(
                        ClimaMapLogTags.RADAR_FRAMES,
                        "Resposta RainViewer sem frames históricos válidos (cacheDisponivel=${cachedRecord != null}).",
                    )
                    fallbackToCacheOrFail(
                        cachedRecord,
                        WeatherError.EmptyResponseError(RainViewerApi.PROVIDER_NAME),
                    )
                } else {
                    ClimaMapLogger.i(
                        ClimaMapLogTags.RADAR_FRAMES,
                        "RainViewer retornou ${mapped.frames.size} frames históricos reais (host=${mapped.host}, maxZoom=${mapped.maxSupportedZoom}).",
                    )
                    val serialized = snapshotAdapter.toJson(mapped)
                    cacheDao.upsertRadar(
                        RadarTimelineCacheEntity(
                            cacheKey = RadarTimelineCacheEntity.DEFAULT_CACHE_KEY,
                            payloadJson = serialized,
                            fetchedAtEpochMillis = now,
                        ),
                    )
                    WeatherResult.Success(
                        data = mapped,
                        origin = DataSourceOrigin.LIVE_NETWORK,
                        fetchedAtEpochMillis = now,
                        staleReason = null,
                    )
                }
            }

            is RemoteCallOutcome.Failure -> {
                ClimaMapLogger.e(
                    ClimaMapLogTags.RADAR_FRAMES,
                    "Falha ao obter frames históricos do RainViewer: ${outcome.error.userMessagePt}",
                )
                fallbackToCacheOrFail(cachedRecord, outcome.error)
            }
        }
    }

    private fun reformatFrameTimes(
        snapshot: RadarTimelineSnapshot,
        displayTimezoneId: String?,
        displayUtcOffsetSeconds: Int,
        timeFormat: TimeFormatPreference,
    ): RadarTimelineSnapshot {
        val updatedFrames = snapshot.frames.map { frame ->
            frame.copy(
                formattedLocalTime = WeatherTimeUtils.formatLocalTime(
                    epochMillis = frame.epochMillis,
                    timezoneId = displayTimezoneId,
                    utcOffsetSeconds = displayUtcOffsetSeconds,
                    timeFormat = timeFormat,
                ),
            )
        }
        return snapshot.copy(frames = updatedFrames)
    }

    private fun fallbackToCacheOrFail(
        cachedRecord: CachedRecord<RadarTimelineSnapshot>?,
        error: WeatherError,
    ): WeatherResult<RadarTimelineSnapshot> {
        return if (cachedRecord != null) {
            WeatherResult.Success(
                data = cachedRecord.data,
                origin = DataSourceOrigin.LOCAL_CACHE,
                fetchedAtEpochMillis = cachedRecord.fetchedAtEpochMillis,
                staleReason = error,
            )
        } else {
            WeatherResult.Failure(error)
        }
    }

    private fun decodeEntity(entity: RadarTimelineCacheEntity): CachedRecord<RadarTimelineSnapshot>? {
        val decoded = runCatching { snapshotAdapter.fromJson(entity.payloadJson) }.getOrNull() ?: return null
        return CachedRecord(
            data = decoded,
            fetchedAtEpochMillis = entity.fetchedAtEpochMillis,
        )
    }
}
