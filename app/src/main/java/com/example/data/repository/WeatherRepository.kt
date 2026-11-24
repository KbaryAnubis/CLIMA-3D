package com.example.data.repository

import com.example.data.local.db.WeatherCacheDao
import com.example.data.local.db.WeatherForecastCacheEntity
import com.example.data.model.CachePolicy
import com.example.data.model.CachedRecord
import com.example.data.model.DataSourceOrigin
import com.example.data.model.SavedLocation
import com.example.data.model.WeatherError
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherResult
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.OpenMeteoForecastApi
import com.example.data.remote.OpenMeteoForecastMapper
import com.example.data.remote.RemoteCallOutcome
import com.example.data.remote.SafeApiExecutor
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Offline-first repository for Open-Meteo Forecast data (current conditions, 37h hourly, 16d daily).
 * Distinguishes live network responses from Room-cached snapshots and never fabricates demo data.
 */
class WeatherRepository(
    private val forecastApi: OpenMeteoForecastApi,
    private val cacheDao: WeatherCacheDao,
    private val connectivityObserver: ConnectivityObserver,
    private val moshi: Moshi,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val bundleAdapter = moshi.adapter(WeatherForecastBundle::class.java)

    fun observeCachedForecast(
        latitude: Double,
        longitude: Double,
    ): Flow<CachedRecord<WeatherForecastBundle>?> {
        val key = SavedLocation.buildCoordinateKey(latitude, longitude)
        return cacheDao.observeForecast(key).map { entity ->
            entity?.let { decodeEntity(it) }
        }
    }

    suspend fun getForecast(
        latitude: Double,
        longitude: Double,
        policy: CachePolicy = CachePolicy.NetworkFirst,
    ): WeatherResult<WeatherForecastBundle> {
        val locationKey = SavedLocation.buildCoordinateKey(latitude, longitude)
        val cachedRecord = cacheDao.getForecast(locationKey)?.let { decodeEntity(it) }
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
                        WeatherError.EmptyResponseError(OpenMeteoForecastApi.PROVIDER_NAME),
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
            val offlineError = WeatherError.NetworkUnavailableError(OpenMeteoForecastApi.PROVIDER_NAME)
            return fallbackToCacheOrFail(cachedRecord, offlineError)
        }

        val remoteOutcome = SafeApiExecutor.execute(OpenMeteoForecastApi.PROVIDER_NAME) {
            forecastApi.getForecast(
                latitude = latitude,
                longitude = longitude,
            )
        }

        return when (remoteOutcome) {
            is RemoteCallOutcome.Success -> {
                val mapped = OpenMeteoForecastMapper.toDomain(
                    dto = remoteOutcome.body,
                    requestedLatitude = latitude,
                    requestedLongitude = longitude,
                    fetchedAtEpochMillis = now,
                )
                if (mapped == null) {
                    fallbackToCacheOrFail(
                        cachedRecord,
                        WeatherError.EmptyResponseError(OpenMeteoForecastApi.PROVIDER_NAME),
                    )
                } else {
                    val serialized = bundleAdapter.toJson(mapped)
                    cacheDao.upsertForecast(
                        WeatherForecastCacheEntity(
                            locationKey = locationKey,
                            latitude = mapped.latitude,
                            longitude = mapped.longitude,
                            timezoneId = mapped.timezoneId,
                            utcOffsetSeconds = mapped.utcOffsetSeconds,
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
                fallbackToCacheOrFail(cachedRecord, remoteOutcome.error)
            }
        }
    }

    /**
     * Automatically fetches fresh data when the app opens and network is available;
     * transparently returns Room cache when offline.
     */
    suspend fun refreshOnAppOpen(
        latitude: Double,
        longitude: Double,
    ): WeatherResult<WeatherForecastBundle> =
        getForecast(
            latitude = latitude,
            longitude = longitude,
            policy = CachePolicy.NetworkFirst,
        )

    private fun fallbackToCacheOrFail(
        cachedRecord: CachedRecord<WeatherForecastBundle>?,
        error: WeatherError,
    ): WeatherResult<WeatherForecastBundle> {
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

    private fun decodeEntity(entity: WeatherForecastCacheEntity): CachedRecord<WeatherForecastBundle>? {
        val decoded = runCatching { bundleAdapter.fromJson(entity.payloadJson) }.getOrNull() ?: return null
        return CachedRecord(
            data = decoded,
            fetchedAtEpochMillis = entity.fetchedAtEpochMillis,
        )
    }
}
