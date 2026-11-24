package com.example.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.SavedLocation

/**
 * Persisted city, current GPS point, or custom map marker saved by the user.
 */
@Entity(
    tableName = "saved_locations",
    indices = [
        Index(value = ["coordinateKey"]),
        Index(value = ["isPrimary"]),
    ],
)
data class SavedLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val providerId: Long? = null,
    val coordinateKey: String,
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezoneId: String? = null,
    val isCurrentLocation: Boolean = false,
    val isPrimary: Boolean = false,
    val isCustomMapPin: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    fun toDomain(): SavedLocation =
        SavedLocation(
            id = id,
            providerId = providerId,
            name = name,
            region = region,
            country = country,
            countryCode = countryCode,
            latitude = latitude,
            longitude = longitude,
            timezoneId = timezoneId,
            isCurrentLocation = isCurrentLocation,
            isPrimary = isPrimary,
            isCustomMapPin = isCustomMapPin,
            sortOrder = sortOrder,
            createdAtEpochMillis = createdAtEpochMillis,
        )

    companion object {
        fun fromDomain(location: SavedLocation): SavedLocationEntity =
            SavedLocationEntity(
                id = location.id,
                providerId = location.providerId,
                coordinateKey = location.coordinateKey,
                name = location.name,
                region = location.region,
                country = location.country,
                countryCode = location.countryCode,
                latitude = location.latitude,
                longitude = location.longitude,
                timezoneId = location.timezoneId,
                isCurrentLocation = location.isCurrentLocation,
                isPrimary = location.isPrimary,
                isCustomMapPin = location.isCustomMapPin,
                sortOrder = location.sortOrder,
                createdAtEpochMillis = location.createdAtEpochMillis,
            )
    }
}

/**
 * Cached Open-Meteo Forecast snapshot stored per rounded coordinate key for offline viewing.
 */
@Entity(tableName = "weather_forecast_cache")
data class WeatherForecastCacheEntity(
    @PrimaryKey val locationKey: String,
    val latitude: Double,
    val longitude: Double,
    val timezoneId: String,
    val utcOffsetSeconds: Int,
    val payloadJson: String,
    val fetchedAtEpochMillis: Long,
)

/**
 * Cached Open-Meteo Air Quality snapshot stored per rounded coordinate key for offline viewing.
 */
@Entity(tableName = "air_quality_cache")
data class AirQualityCacheEntity(
    @PrimaryKey val locationKey: String,
    val latitude: Double,
    val longitude: Double,
    val payloadJson: String,
    val fetchedAtEpochMillis: Long,
)

/**
 * Cached RainViewer radar metadata and historical frames for offline recovery.
 */
@Entity(tableName = "radar_timeline_cache")
data class RadarTimelineCacheEntity(
    @PrimaryKey val cacheKey: String = DEFAULT_CACHE_KEY,
    val payloadJson: String,
    val fetchedAtEpochMillis: Long,
) {
    companion object {
        const val DEFAULT_CACHE_KEY: String = "rainviewer_global"
    }
}
