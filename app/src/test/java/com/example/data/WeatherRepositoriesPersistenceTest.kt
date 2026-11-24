package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.Clima3DDatabase
import com.example.data.local.preferences.UserPreferencesRepository
import com.example.data.model.CachePolicy
import com.example.data.model.DataSourceOrigin
import com.example.data.model.PrecipitationUnit
import com.example.data.model.PressureUnit
import com.example.data.model.TemperatureUnit
import com.example.data.model.TimeFormatPreference
import com.example.data.model.VisibilityUnit
import com.example.data.model.WeatherError
import com.example.data.model.WeatherResult
import com.example.data.model.WindSpeedUnit
import com.example.data.network.ConnectivityObserver
import com.example.data.remote.NetworkClientFactory
import com.example.maps.CityMapCoordinate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WeatherRepositoriesPersistenceTest {

    private lateinit var context: Context
    private lateinit var database: Clima3DDatabase
    private lateinit var tempPrefsFile: File
    private lateinit var connectivity: FakeConnectivityObserver
    private var fakeClockMillis: Long = 1_780_300_000_000L

    private var forecastHttpCode: Int = 200
    private var forecastBody: String = SAMPLE_FORECAST_JSON
    private var airQualityHttpCode: Int = 200
    private var airQualityBody: String = SAMPLE_AIR_QUALITY_JSON
    private var geocodingBody: String = SAMPLE_GEOCODING_JSON
    private var rainViewerHttpCode: Int = 200
    private var rainViewerBody: String = SAMPLE_RAINVIEWER_JSON

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Clima3DDatabase.createInMemory(context)
        tempPrefsFile = File.createTempFile("clima3d_prefs_test_", ".preferences_pb", context.cacheDir)
        connectivity = FakeConnectivityObserver(initialOnline = true)
    }

    @After
    fun tearDown() {
        database.close()
        tempPrefsFile.delete()
    }

    private fun buildContainer(): ClimaDataContainer {
        val moshi = NetworkClientFactory.createMoshi()
        val okHttp = NetworkClientFactory.createOkHttpClient(
            additionalInterceptors = listOf(
                Interceptor { chain ->
                    val req = chain.request()
                    when (req.url.encodedPath) {
                        "/v1/forecast" -> jsonResponse(req, forecastHttpCode, forecastBody)
                        "/v1/air-quality" -> jsonResponse(req, airQualityHttpCode, airQualityBody)
                        "/v1/search" -> jsonResponse(req, 200, geocodingBody)
                        "/public/weather-maps.json" -> jsonResponse(req, rainViewerHttpCode, rainViewerBody)
                        else -> jsonResponse(req, 404, "{}")
                    }
                },
            ),
            enableLogging = false,
        )
        val testDataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempPrefsFile },
        )
        return ClimaDataContainer(
            database = database,
            preferencesRepository = UserPreferencesRepository(testDataStore),
            connectivityObserver = connectivity,
            moshi = moshi,
            okHttpClient = okHttp,
            forecastApi = NetworkClientFactory.createForecastApi(okHttp, moshi),
            airQualityApi = NetworkClientFactory.createAirQualityApi(okHttp, moshi),
            geocodingApi = NetworkClientFactory.createGeocodingApi(okHttp, moshi),
            rainViewerApi = NetworkClientFactory.createRainViewerApi(okHttp, moshi),
            clock = { fakeClockMillis },
        )
    }

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    @Test
    fun `WeatherRepository persists live forecast, serves cache offline with timestamp, and refreshes automatically upon reconnect`() = runTest {
        val container = buildContainer()
        val repo = container.weatherRepository

        // 1. Live fetch populates Room cache
        val live = repo.getForecast(-23.5505, -46.6333, CachePolicy.NetworkFirst)
        assertTrue(live is WeatherResult.Success)
        val liveSuccess = live as WeatherResult.Success
        assertEquals(DataSourceOrigin.LIVE_NETWORK, liveSuccess.origin)
        assertFalse(liveSuccess.isFromCache)
        assertNull(liveSuccess.staleReason)
        assertEquals(fakeClockMillis, liveSuccess.fetchedAtEpochMillis)
        assertEquals(26.4, liveSuccess.data.current.temperatureC!!, 0.001)

        // 2. Device goes offline -> serves Room cache clearly flagged as LOCAL_CACHE with original timestamp
        val cachedTimestamp = fakeClockMillis
        fakeClockMillis += 600_000L
        connectivity.setOnline(false)

        val offline = repo.getForecast(-23.5505, -46.6333, CachePolicy.NetworkFirst)
        assertTrue(offline is WeatherResult.Success)
        val offlineSuccess = offline as WeatherResult.Success
        assertEquals(DataSourceOrigin.LOCAL_CACHE, offlineSuccess.origin)
        assertTrue(offlineSuccess.isFromCache)
        assertTrue(offlineSuccess.staleReason is WeatherError.NetworkUnavailableError)
        assertEquals(cachedTimestamp, offlineSuccess.fetchedAtEpochMillis)
        assertEquals(26.4, offlineSuccess.data.current.temperatureC!!, 0.001)

        // 3. Connection returns -> refreshOnAppOpen fetches updated live data
        connectivity.setOnline(true)
        forecastBody = SAMPLE_FORECAST_JSON.replace("26.4", "28.9")
        fakeClockMillis += 300_000L

        val refreshed = repo.refreshOnAppOpen(-23.5505, -46.6333)
        assertTrue(refreshed is WeatherResult.Success)
        val refreshedSuccess = refreshed as WeatherResult.Success
        assertEquals(DataSourceOrigin.LIVE_NETWORK, refreshedSuccess.origin)
        assertFalse(refreshedSuccess.isFromCache)
        assertEquals(fakeClockMillis, refreshedSuccess.fetchedAtEpochMillis)
        assertEquals(28.9, refreshedSuccess.data.current.temperatureC!!, 0.001)
    }

    @Test
    fun `Offline repository without prior cache returns Failure instead of fake demo data`() = runTest {
        val container = buildContainer()
        connectivity.setOnline(false)

        val forecastResult = container.weatherRepository.getForecast(-22.9068, -43.1729)
        assertTrue(forecastResult is WeatherResult.Failure)
        assertTrue((forecastResult as WeatherResult.Failure).error is WeatherError.NetworkUnavailableError)

        val airResult = container.airQualityRepository.getAirQuality(-22.9068, -43.1729)
        assertTrue(airResult is WeatherResult.Failure)
        assertTrue((airResult as WeatherResult.Failure).error is WeatherError.NetworkUnavailableError)

        val radarResult = container.radarRepository.getRadarTimeline()
        assertTrue(radarResult is WeatherResult.Failure)
        assertTrue((radarResult as WeatherResult.Failure).error is WeatherError.NetworkUnavailableError)
    }

    @Test
    fun `HTTP failure falls back gracefully to previously cached Air Quality and RainViewer Radar frames`() = runTest {
        val container = buildContainer()

        // Seed cache while healthy
        val aqLive = container.airQualityRepository.getAirQuality(-23.5505, -46.6333)
        assertEquals(DataSourceOrigin.LIVE_NETWORK, (aqLive as WeatherResult.Success).origin)
        assertEquals(38, aqLive.data.usAqi)

        val radarLive = container.radarRepository.getRadarTimeline(displayTimezoneId = "America/Sao_Paulo")
        assertEquals(DataSourceOrigin.LIVE_NETWORK, (radarLive as WeatherResult.Success).origin)
        assertEquals(9, radarLive.data.frames.size)

        // Now remote services return HTTP 503
        airQualityHttpCode = 503
        rainViewerHttpCode = 503

        val aqFallback = container.airQualityRepository.getAirQuality(-23.5505, -46.6333)
        assertTrue(aqFallback is WeatherResult.Success)
        assertEquals(DataSourceOrigin.LOCAL_CACHE, (aqFallback as WeatherResult.Success).origin)
        assertTrue(aqFallback.staleReason is WeatherError.HttpError)
        assertEquals(38, aqFallback.data.usAqi)

        val radarFallback = container.radarRepository.getRadarTimeline(displayTimezoneId = "UTC")
        assertTrue(radarFallback is WeatherResult.Success)
        assertEquals(DataSourceOrigin.LOCAL_CACHE, (radarFallback as WeatherResult.Success).origin)
        assertTrue(radarFallback.staleReason is WeatherError.HttpError)
        assertEquals(9, radarFallback.data.frames.size)
    }

    @Test
    fun `CityLocationRepository searches cities, saves multiple locations and map pins, switches primary, and bridges MapLibre markers`() = runTest {
        val container = buildContainer()
        val repo = container.cityLocationRepository

        val searchResult = repo.searchCities("Curitiba")
        assertTrue(searchResult is WeatherResult.Success)
        val candidates = (searchResult as WeatherResult.Success).data
        assertEquals(2, candidates.size)

        val curitiba = repo.saveCityFromSearch(candidates[0], setAsPrimary = true)
        val pin = repo.saveCustomMapPoint("Serra do Mar", CityMapCoordinate(-25.4300, -48.9200))
        val gps = repo.upsertCurrentLocation(CityMapCoordinate(-23.5505, -46.6333), setAsPrimary = false)

        val allSaved = repo.savedLocations.first()
        assertEquals(3, allSaved.size)
        assertEquals(curitiba.id, repo.getPrimaryLocation()?.id)

        // Switch primary to custom map pin and verify Room persistence
        repo.setPrimaryLocation(pin.id)
        assertEquals(pin.id, repo.getPrimaryLocation()?.id)

        // Verify direct MapLibre marker bridge preserves current-location flag and coordinates
        val markers = repo.toMapMarkers(repo.getSavedLocations())
        assertEquals(3, markers.size)
        assertTrue(markers.any { it.isCurrentLocation && it.coordinate.latitude == gps.latitude })
        assertTrue(markers.any { it.label == "Serra do Mar" })

        // Removing primary promotes next remaining location automatically
        repo.removeLocation(pin.id)
        assertEquals(2, repo.getSavedLocations().size)
        assertNotNull(repo.getPrimaryLocation())
    }

    @Test
    fun `UserPreferencesRepository persists all unit and in-app settings via DataStore`() = runTest {
        val container = buildContainer()
        val prefsRepo = container.preferencesRepository

        val defaults = prefsRepo.getCurrentPreferences()
        assertEquals(TemperatureUnit.CELSIUS, defaults.temperatureUnit)
        assertEquals(WindSpeedUnit.KMH, defaults.windSpeedUnit)
        assertEquals(PressureUnit.HPA, defaults.pressureUnit)
        assertTrue(defaults.inAppAlertsEnabled)
        assertTrue(defaults.autoRefreshOnOpen)

        prefsRepo.setTemperatureUnit(TemperatureUnit.FAHRENHEIT)
        prefsRepo.setWindSpeedUnit(WindSpeedUnit.MPH)
        prefsRepo.setPressureUnit(PressureUnit.INHG)
        prefsRepo.setPrecipitationUnit(PrecipitationUnit.INCH)
        prefsRepo.setVisibilityUnit(VisibilityUnit.MILES)
        prefsRepo.setTimeFormat(TimeFormatPreference.H12)
        prefsRepo.setAnimatedBackgroundEnabled(false)
        prefsRepo.setInAppAlertsEnabled(false)
        prefsRepo.setSelectedLocationId(42L)

        val updated = prefsRepo.getCurrentPreferences()
        assertEquals(TemperatureUnit.FAHRENHEIT, updated.temperatureUnit)
        assertEquals(WindSpeedUnit.MPH, updated.windSpeedUnit)
        assertEquals(PressureUnit.INHG, updated.pressureUnit)
        assertEquals(PrecipitationUnit.INCH, updated.precipitationUnit)
        assertEquals(VisibilityUnit.MILES, updated.visibilityUnit)
        assertEquals(TimeFormatPreference.H12, updated.timeFormat)
        assertFalse(updated.animatedBackgroundEnabled)
        assertFalse(updated.inAppAlertsEnabled)
        assertEquals(42L, updated.selectedLocationId)
    }

    @Test
    fun `ClimaDataContainer refreshes forecast, air quality, and radar concurrently on app open`() = runTest {
        val container = buildContainer()
        val report = container.refreshLocationOnAppOpen(
            latitude = -23.5505,
            longitude = -46.6333,
            displayTimezoneId = "America/Sao_Paulo",
            displayUtcOffsetSeconds = -10800,
        )

        assertEquals(DataSourceOrigin.LIVE_NETWORK, (report.forecast as WeatherResult.Success).origin)
        assertEquals(DataSourceOrigin.LIVE_NETWORK, (report.airQuality as WeatherResult.Success).origin)
        assertEquals(DataSourceOrigin.LIVE_NETWORK, (report.radar as WeatherResult.Success).origin)
        assertEquals(9, report.radar.data.frames.size)
    }

    private class FakeConnectivityObserver(initialOnline: Boolean) : ConnectivityObserver {
        private val state = MutableStateFlow(initialOnline)
        fun setOnline(online: Boolean) {
            state.value = online
        }

        override fun isOnline(): Boolean = state.value
        override fun observeOnlineStatus(): Flow<Boolean> = state
    }

    companion object {
        private val SAMPLE_FORECAST_JSON = """
            {
              "latitude": -23.5505,
              "longitude": -46.6333,
              "elevation": 760.0,
              "utc_offset_seconds": -10800,
              "timezone": "America/Sao_Paulo",
              "timezone_abbreviation": "GMT-3",
              "current": {
                "time": "2026-06-01T14:00",
                "temperature_2m": 26.4,
                "relative_humidity_2m": 58,
                "apparent_temperature": 27.1,
                "is_day": 1,
                "precipitation": 0.0,
                "weather_code": 1,
                "cloud_cover": 25,
                "pressure_msl": 1016.0,
                "surface_pressure": 933.0,
                "wind_speed_10m": 11.5,
                "wind_direction_10m": 90,
                "wind_gusts_10m": 20.0
              },
              "hourly": {
                "time": ["2026-06-01T14:00", "2026-06-01T15:00"],
                "temperature_2m": [26.4, 25.8],
                "dew_point_2m": [17.4, 17.2],
                "visibility": [15000.0, 14500.0],
                "uv_index": [7.1, 5.2],
                "is_day": [1, 1]
              },
              "daily": {
                "time": ["2026-06-01"],
                "weather_code": [1],
                "temperature_2m_max": [27.5],
                "temperature_2m_min": [16.8],
                "sunrise": ["2026-06-01T06:42"],
                "sunset": ["2026-06-01T17:28"],
                "uv_index_max": [7.4]
              }
            }
        """.trimIndent()

        private val SAMPLE_AIR_QUALITY_JSON = """
            {
              "latitude": -23.5505,
              "longitude": -46.6333,
              "utc_offset_seconds": -10800,
              "timezone": "America/Sao_Paulo",
              "current": {
                "time": "2026-06-01T14:00",
                "european_aqi": 28,
                "us_aqi": 38,
                "pm2_5": 9.2,
                "pm10": 17.4,
                "nitrogen_dioxide": 14.1,
                "ozone": 62.0,
                "carbon_monoxide": 210.0,
                "sulphur_dioxide": 3.2
              }
            }
        """.trimIndent()

        private val SAMPLE_GEOCODING_JSON = """
            {
              "results": [
                {
                  "id": 3464975,
                  "name": "Curitiba",
                  "latitude": -25.4284,
                  "longitude": -49.2733,
                  "country": "Brasil",
                  "country_code": "BR",
                  "admin1": "Paraná",
                  "timezone": "America/Sao_Paulo",
                  "population": 1773718
                },
                {
                  "id": 999999,
                  "name": "Curitibanos",
                  "latitude": -27.2828,
                  "longitude": -50.5844,
                  "country": "Brasil",
                  "country_code": "BR",
                  "admin1": "Santa Catarina",
                  "timezone": "America/Sao_Paulo"
                }
              ]
            }
        """.trimIndent()

        private val SAMPLE_RAINVIEWER_JSON = """
            {
              "version": "2.0",
              "generated": 1780306600,
              "host": "https://tilecache.rainviewer.com",
              "radar": {
                "past": [
                  { "time": 1780300000, "path": "/v2/radar/1780300000" },
                  { "time": 1780300600, "path": "/v2/radar/1780300600" },
                  { "time": 1780301200, "path": "/v2/radar/1780301200" },
                  { "time": 1780301800, "path": "/v2/radar/1780301800" },
                  { "time": 1780302400, "path": "/v2/radar/1780302400" },
                  { "time": 1780303000, "path": "/v2/radar/1780303000" },
                  { "time": 1780303600, "path": "/v2/radar/1780303600" },
                  { "time": 1780304200, "path": "/v2/radar/1780304200" },
                  { "time": 1780304800, "path": "/v2/radar/1780304800" },
                  { "time": 1780305400, "path": "/v2/radar/1780305400" },
                  { "time": 1780306000, "path": "/v2/radar/1780306000" }
                ]
              }
            }
        """.trimIndent()
    }
}
