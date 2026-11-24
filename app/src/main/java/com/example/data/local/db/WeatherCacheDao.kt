package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherCacheDao {
    @Query("SELECT * FROM weather_forecast_cache WHERE locationKey = :locationKey LIMIT 1")
    fun observeForecast(locationKey: String): Flow<WeatherForecastCacheEntity?>

    @Query("SELECT * FROM weather_forecast_cache WHERE locationKey = :locationKey LIMIT 1")
    suspend fun getForecast(locationKey: String): WeatherForecastCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForecast(entity: WeatherForecastCacheEntity)

    @Query("DELETE FROM weather_forecast_cache WHERE locationKey = :locationKey")
    suspend fun deleteForecast(locationKey: String)

    @Query("SELECT * FROM air_quality_cache WHERE locationKey = :locationKey LIMIT 1")
    fun observeAirQuality(locationKey: String): Flow<AirQualityCacheEntity?>

    @Query("SELECT * FROM air_quality_cache WHERE locationKey = :locationKey LIMIT 1")
    suspend fun getAirQuality(locationKey: String): AirQualityCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAirQuality(entity: AirQualityCacheEntity)

    @Query("DELETE FROM air_quality_cache WHERE locationKey = :locationKey")
    suspend fun deleteAirQuality(locationKey: String)

    @Query("SELECT * FROM radar_timeline_cache WHERE cacheKey = :cacheKey LIMIT 1")
    fun observeRadar(cacheKey: String = RadarTimelineCacheEntity.DEFAULT_CACHE_KEY): Flow<RadarTimelineCacheEntity?>

    @Query("SELECT * FROM radar_timeline_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getRadar(cacheKey: String = RadarTimelineCacheEntity.DEFAULT_CACHE_KEY): RadarTimelineCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRadar(entity: RadarTimelineCacheEntity)
}
