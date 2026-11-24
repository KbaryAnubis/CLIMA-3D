package com.example.data.repository

import com.example.data.local.db.SavedLocationDao
import com.example.data.local.db.SavedLocationEntity
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.SavedLocation
import com.example.data.model.WeatherError
import com.example.data.model.WeatherResult
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.OpenMeteoGeocodingApi
import com.example.data.remote.OpenMeteoGeocodingMapper
import com.example.data.remote.RemoteCallOutcome
import com.example.data.remote.SafeApiExecutor
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapMarker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages Open-Meteo Geocoding searches and Room persistence for saved cities,
 * GPS locations, and custom MapLibre map pins.
 */
class CityLocationRepository(
    private val geocodingApi: OpenMeteoGeocodingApi,
    private val savedLocationDao: SavedLocationDao,
    private val connectivityObserver: ConnectivityObserver,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val savedLocations: Flow<List<SavedLocation>> =
        savedLocationDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getSavedLocations(): List<SavedLocation> =
        savedLocationDao.getAll().map { it.toDomain() }

    suspend fun getPrimaryLocation(): SavedLocation? =
        savedLocationDao.getPrimary()?.toDomain()
            ?: savedLocationDao.getAll().firstOrNull()?.toDomain()

    suspend fun searchCities(
        query: String,
        count: Int = OpenMeteoGeocodingApi.DEFAULT_RESULT_COUNT,
        language: String = OpenMeteoGeocodingApi.DEFAULT_LANGUAGE,
    ): WeatherResult<List<CitySearchResult>> {
        val trimmed = query.trim()
        val now = clock()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            return WeatherResult.Success(
                data = emptyList(),
                origin = DataSourceOrigin.LIVE_NETWORK,
                fetchedAtEpochMillis = now,
            )
        }

        if (!connectivityObserver.isOnline()) {
            return WeatherResult.Failure(
                WeatherError.NetworkUnavailableError(OpenMeteoGeocodingApi.PROVIDER_NAME),
            )
        }

        val outcome = SafeApiExecutor.execute(OpenMeteoGeocodingApi.PROVIDER_NAME) {
            geocodingApi.searchCities(
                name = trimmed,
                count = count.coerceIn(1, MAX_RESULT_COUNT),
                language = language,
            )
        }

        return when (outcome) {
            is RemoteCallOutcome.Success -> {
                val results = OpenMeteoGeocodingMapper.toDomain(outcome.body)
                WeatherResult.Success(
                    data = results,
                    origin = DataSourceOrigin.LIVE_NETWORK,
                    fetchedAtEpochMillis = now,
                )
            }

            is RemoteCallOutcome.Failure -> WeatherResult.Failure(outcome.error)
        }
    }

    /**
     * Persists a city selected from Open-Meteo Geocoding. Avoids duplicating identical coordinates.
     */
    suspend fun saveCityFromSearch(
        result: CitySearchResult,
        setAsPrimary: Boolean = false,
    ): SavedLocation {
        val coordinateKey = SavedLocation.buildCoordinateKey(result.latitude, result.longitude)
        val existing = savedLocationDao.findByCoordinateKey(coordinateKey)
        if (existing != null) {
            if (setAsPrimary && !existing.isPrimary) {
                savedLocationDao.setPrimaryLocation(existing.id)
                return existing.copy(isPrimary = true).toDomain()
            }
            return existing.toDomain()
        }

        val sortOrder = savedLocationDao.getNextSortOrder()
        val shouldBePrimary = setAsPrimary || savedLocationDao.getPrimary() == null
        val entity = SavedLocationEntity(
            providerId = result.providerId,
            coordinateKey = coordinateKey,
            name = result.name,
            region = result.admin1 ?: result.admin2,
            country = result.country,
            countryCode = result.countryCode,
            latitude = result.latitude,
            longitude = result.longitude,
            timezoneId = result.timezoneId,
            isCurrentLocation = false,
            isPrimary = false,
            isCustomMapPin = false,
            sortOrder = sortOrder,
            createdAtEpochMillis = clock(),
        )
        val insertedId = savedLocationDao.insert(entity)
        if (shouldBePrimary) {
            savedLocationDao.setPrimaryLocation(insertedId)
        }
        return entity.copy(id = insertedId, isPrimary = shouldBePrimary).toDomain()
    }

    /**
     * Persists a user-dropped coordinate pin from the MapLibre Cities map.
     */
    suspend fun saveCustomMapPoint(
        label: String,
        coordinate: CityMapCoordinate,
        setAsPrimary: Boolean = false,
    ): SavedLocation {
        val cleanName = label.trim().ifEmpty { "Ponto ${coordinate.formatted()}" }
        val coordinateKey = SavedLocation.buildCoordinateKey(coordinate.latitude, coordinate.longitude)
        val existing = savedLocationDao.findByCoordinateKey(coordinateKey)
        if (existing != null) {
            val updated = existing.copy(name = cleanName)
            savedLocationDao.update(updated)
            if (setAsPrimary) {
                savedLocationDao.setPrimaryLocation(updated.id)
                return updated.copy(isPrimary = true).toDomain()
            }
            return updated.toDomain()
        }

        val sortOrder = savedLocationDao.getNextSortOrder()
        val shouldBePrimary = setAsPrimary || savedLocationDao.getPrimary() == null
        val entity = SavedLocationEntity(
            providerId = null,
            coordinateKey = coordinateKey,
            name = cleanName,
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            isCurrentLocation = false,
            isPrimary = false,
            isCustomMapPin = true,
            sortOrder = sortOrder,
            createdAtEpochMillis = clock(),
        )
        val insertedId = savedLocationDao.insert(entity)
        if (shouldBePrimary) {
            savedLocationDao.setPrimaryLocation(insertedId)
        }
        return entity.copy(id = insertedId, isPrimary = shouldBePrimary).toDomain()
    }

    /**
     * Saves or updates the single GPS current-location record without creating duplicates.
     */
    suspend fun upsertCurrentLocation(
        coordinate: CityMapCoordinate,
        name: String = "Minha localização",
        region: String? = null,
        country: String? = null,
        setAsPrimary: Boolean = true,
    ): SavedLocation {
        val existing = savedLocationDao.getCurrentLocationEntry()
        val coordinateKey = SavedLocation.buildCoordinateKey(coordinate.latitude, coordinate.longitude)
        if (existing != null) {
            val updated = existing.copy(
                coordinateKey = coordinateKey,
                name = name,
                region = region ?: existing.region,
                country = country ?: existing.country,
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
            )
            savedLocationDao.update(updated)
            if (setAsPrimary) {
                savedLocationDao.setPrimaryLocation(updated.id)
                return updated.copy(isPrimary = true).toDomain()
            }
            return updated.toDomain()
        }

        val entity = SavedLocationEntity(
            coordinateKey = coordinateKey,
            name = name,
            region = region,
            country = country,
            latitude = coordinate.latitude,
            longitude = coordinate.longitude,
            isCurrentLocation = true,
            isPrimary = false,
            isCustomMapPin = false,
            sortOrder = 0,
            createdAtEpochMillis = clock(),
        )
        val insertedId = savedLocationDao.insert(entity)
        if (setAsPrimary) {
            savedLocationDao.setPrimaryLocation(insertedId)
        }
        return entity.copy(id = insertedId, isPrimary = setAsPrimary).toDomain()
    }

    suspend fun setPrimaryLocation(locationId: Long) {
        savedLocationDao.setPrimaryLocation(locationId)
    }

    suspend fun removeLocation(locationId: Long) {
        val removed = savedLocationDao.getById(locationId)
        savedLocationDao.deleteById(locationId)
        if (removed?.isPrimary == true) {
            val nextFallback = savedLocationDao.getAll().firstOrNull()
            if (nextFallback != null) {
                savedLocationDao.setPrimaryLocation(nextFallback.id)
            }
        }
    }

    /**
     * Converts persisted [SavedLocation] entries into [CityMapMarker] instances for [com.example.maps.CityMapLibreHost].
     */
    fun toMapMarkers(locations: List<SavedLocation>): List<CityMapMarker> =
        locations.map { it.toCityMapMarker() }

    companion object {
        private const val MIN_QUERY_LENGTH: Int = 2
        private const val MAX_RESULT_COUNT: Int = 50
    }
}
