package com.example.data

import android.content.Context
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.AirQualitySnapshot
import com.example.data.model.CachePolicy
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.TimeFormatPreference
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherResult
import com.example.data.network.AndroidConnectivityObserver
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.NetworkClientFactory
import com.example.data.remote.OpenMeteoAirQualityApi
import com.example.data.remote.OpenMeteoForecastApi
import com.example.data.remote.OpenMeteoGeocodingApi
import com.example.data.remote.RainViewerApi
import com.example.data.repository.AirQualityRepository
import com.example.data.repository.CityLocationRepository
import com.example.data.repository.RadarRepository
import com.example.data.repository.WeatherRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

/**
 * Combined result produced when refreshing the active location upon app startup or reconnect.
 */
data class AppOpenRefreshReport(
    val forecast: WeatherResult<WeatherForecastBundle>,
    val airQuality: WeatherResult<AirQualitySnapshot>,
    val radar: WeatherResult<RadarTimelineSnapshot>,
)

/**
 * Lightweight Constructor-Injection container assembling Clima 3D's real meteorological data layer
 * (Retrofit, OkHttp, Moshi, Room, DataStore, and offline-first repositories).
 */
class ClimaDataContainer(
    val database: Clima3DDatabase,
    val preferencesRepository: UserPreferencesRepository,
    val connectivityObserver: ConnectivityObserver,
    val moshi: Moshi,
    val okHttpClient: OkHttpClient,
    val forecastApi: OpenMeteoForecastApi,
    val airQualityApi: OpenMeteoAirQualityApi,
    val geocodingApi: OpenMeteoGeocodingApi,
    val rainViewerApi: RainViewerApi,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val weatherRepository: WeatherRepository = WeatherRepository(
        forecastApi = forecastApi,
        cacheDao = database.weatherCacheDao(),
        connectivityObserver = connectivityObserver,
        moshi = moshi,
        clock = clock,
    )

    val airQualityRepository: AirQualityRepository = AirQualityRepository(
        airQualityApi = airQualityApi,
        cacheDao = database.weatherCacheDao(),
        connectivityObserver = connectivityObserver,
        moshi = moshi,
        clock = clock,
    )

    val cityLocationRepository: CityLocationRepository = CityLocationRepository(
        geocodingApi = geocodingApi,
        savedLocationDao = database.savedLocationDao(),
        connectivityObserver = connectivityObserver,
        clock = clock,
    )

    val radarRepository: RadarRepository = RadarRepository(
        rainViewerApi = rainViewerApi,
        cacheDao = database.weatherCacheDao(),
        connectivityObserver = connectivityObserver,
        moshi = moshi,
        clock = clock,
    )

    /**
     * Automatically refreshes forecast, air quality, and radar concurrently when the app is opened.
     * Falls back transparently to Room cache if offline or if any individual provider is unavailable.
     */
    suspend fun refreshLocationOnAppOpen(
        latitude: Double,
        longitude: Double,
        displayTimezoneId: String? = null,
        displayUtcOffsetSeconds: Int = 0,
        timeFormat: TimeFormatPreference = TimeFormatPreference.H24,
        policy: CachePolicy = CachePolicy.NetworkFirst,
    ): AppOpenRefreshReport = coroutineScope {
        val forecastDeferred = async {
            weatherRepository.getForecast(latitude, longitude, policy)
        }
        val airQualityDeferred = async {
            airQualityRepository.getAirQuality(latitude, longitude, policy)
        }
        val radarDeferred = async {
            radarRepository.getRadarTimeline(
                displayTimezoneId = displayTimezoneId,
                displayUtcOffsetSeconds = displayUtcOffsetSeconds,
                timeFormat = timeFormat,
                policy = policy,
            )
        }
        AppOpenRefreshReport(
            forecast = forecastDeferred.await(),
            airQuality = airQualityDeferred.await(),
            radar = radarDeferred.await(),
        )
    }

    companion object {
        @Volatile
        private var instance: ClimaDataContainer? = null

        fun getInstance(context: Context): ClimaDataContainer =
            instance ?: synchronized(this) {
                instance ?: buildDefault(context.applicationContext).also { instance = it }
            }

        private fun buildDefault(appContext: Context): ClimaDataContainer {
            val moshi = NetworkClientFactory.createMoshi()
            val okHttpClient = NetworkClientFactory.createOkHttpClient()
            val database = Clima3DDatabase.getInstance(appContext)
            val preferencesRepository = UserPreferencesRepository(appContext)
            val connectivityObserver = AndroidConnectivityObserver(appContext)
            return ClimaDataContainer(
                database = database,
                preferencesRepository = preferencesRepository,
                connectivityObserver = connectivityObserver,
                moshi = moshi,
                okHttpClient = okHttpClient,
                forecastApi = NetworkClientFactory.createForecastApi(okHttpClient, moshi),
                airQualityApi = NetworkClientFactory.createAirQualityApi(okHttpClient, moshi),
                geocodingApi = NetworkClientFactory.createGeocodingApi(okHttpClient, moshi),
                rainViewerApi = NetworkClientFactory.createRainViewerApi(okHttpClient, moshi),
            )
        }
    }
}
