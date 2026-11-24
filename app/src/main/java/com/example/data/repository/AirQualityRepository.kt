package com.example.data.repository

import com.example.data.local.db.AirQualityCacheEntity
import com.example.data.local.db.WeatherCacheDao
import com.example.data.model.AirQualitySnapshot
import com.example.data.model.CachePolicy
import com.example.data.model.CachedRecord
import com.example.data.model.DataSourceOrigin
import com.example.data.model.SavedLocation
import com.example.data.model.WeatherError
import com.example.data.model.WeatherResult
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.OpenMeteoAirQualityApi
import com.example.data.remote.OpenMeteoAirQualityMapper
import com.example.data.remote.RemoteCallOutcome
import com.example.data.remote.SafeApiExecutor
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Offline-first repository for Open-Meteo Air Quality data.
 * Preserves nullable metrics exactly as supplied by the API and falls back to Room cache when offline.
 */
class AirQualityRepository(
    private val airQualityApi: OpenMeteoAirQualityApi,
    private val cacheDao: WeatherCacheDao,
    private val connectivityObserver: ConnectivityObserver,
    private val moshi: Moshi,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val snapshotAdapter = moshi.adapter(AirQualitySnapshot::class.java)

    fun observeCachedAirQuality(
        latitude: Double,
        longitude: Double,
    ): Flow<CachedRecord<AirQualitySnapshot>?> {
        val key = SavedLocation.buildCoordinateKey(latitude, longitude)
        return cacheDao.observeAirQuality(key).map { entity ->
            entity?.let { withContext(cpuDispatcher) { decodeEntity(it) } }
        }
    }

    suspend fun getAirQuality(
        latitude: Double,
        longitude: Double,
        policy: CachePolicy = CachePolicy.NetworkFirst,
    ): WeatherResult<AirQualitySnapshot> {
        val locationKey = SavedLocation.buildCoordinateKey(latitude, longitude)
        val cachedEntity = withContext(ioDispatcher) { cacheDao.getAirQuality(locationKey) }
        val cachedRecord = cachedEntity?.let { withContext(cpuDispatcher) { decodeEntity(it) } }
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
                        WeatherError.EmptyResponseError(OpenMeteoAirQualityApi.PROVIDER_NAME),
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
            val offlineError = WeatherError.NetworkUnavailableError(OpenMeteoAirQualityApi.PROVIDER_NAME)
            return fallbackToCacheOrFail(cachedRecord, offlineError)
        }

        val remoteOutcome = SafeApiExecutor.execute(OpenMeteoAirQualityApi.PROVIDER_NAME) {
            airQualityApi.getAirQuality(
                latitude = latitude,
                longitude = longitude,
            )
        }

        return when (remoteOutcome) {
            is RemoteCallOutcome.Success -> {
                val mapped = withContext(cpuDispatcher) {
                    OpenMeteoAirQualityMapper.toDomain(
                        dto = remoteOutcome.body,
                        requestedLatitude = latitude,
                        requestedLongitude = longitude,
                        fetchedAtEpochMillis = now,
                    )
                }
                if (mapped == null) {
                    fallbackToCacheOrFail(
                        cachedRecord,
                        WeatherError.EmptyResponseError(OpenMeteoAirQualityApi.PROVIDER_NAME),
                    )
                } else {
                    val serialized = withContext(cpuDispatcher) { snapshotAdapter.toJson(mapped) }
                    withContext(ioDispatcher) {
                        cacheDao.upsertAirQuality(
                            AirQualityCacheEntity(
                                locationKey = locationKey,
                                latitude = mapped.latitude,
                                longitude = mapped.longitude,
                                payloadJson = serialized,
                                fetchedAtEpochMillis = now,
                            ),
                        )
                    }
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

    private fun fallbackToCacheOrFail(
        cachedRecord: CachedRecord<AirQualitySnapshot>?,
        error: WeatherError,
    ): WeatherResult<AirQualitySnapshot> {
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

    private fun decodeEntity(entity: AirQualityCacheEntity): CachedRecord<AirQualitySnapshot>? {
        val decoded = runCatching { snapshotAdapter.fromJson(entity.payloadJson) }.getOrNull() ?: return null
        return CachedRecord(
            data = decoded,
            fetchedAtEpochMillis = entity.fetchedAtEpochMillis,
        )
    }
}
