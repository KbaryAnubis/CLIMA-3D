package com.example.data

import com.example.data.model.WeatherError
import com.example.data.remote.NetworkClientFactory
import com.example.data.remote.OpenMeteoAirQualityApi
import com.example.data.remote.OpenMeteoForecastApi
import com.example.data.remote.OpenMeteoForecastMapper
import com.example.data.remote.OpenMeteoGeocodingApi
import com.example.data.remote.OpenMeteoGeocodingMapper
import com.example.data.remote.RainViewerApi
import com.example.data.remote.RainViewerMapper
import com.example.data.remote.RemoteCallOutcome
import com.example.data.remote.SafeApiExecutor
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Verifies the real Retrofit + OkHttp + Moshi serialization pipeline and SafeApiExecutor error handling
 * against simulated HTTP responses without requiring external internet connectivity.
 */
class WeatherApisAndErrorHandlingTest {

    private val moshi = NetworkClientFactory.createMoshi()

    private fun stubClient(
        handler: (Request) -> Response,
    ) = NetworkClientFactory.createOkHttpClient(
        additionalInterceptors = listOf(Interceptor { chain -> handler(chain.request()) }),
        enableLogging = false,
    )

    private fun jsonResponse(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    @Test
    fun `OpenMeteoForecastApi parses real JSON contract and sends verified query parameters`() = runTest {
        var capturedRequest: Request? = null
        val client = stubClient { req ->
            capturedRequest = req
            jsonResponse(
                req,
                200,
                """
                {
                  "latitude": -23.55,
                  "longitude": -46.63,
                  "elevation": 760.0,
                  "utc_offset_seconds": -10800,
                  "timezone": "America/Sao_Paulo",
                  "timezone_abbreviation": "GMT-3",
                  "current": {
                    "time": "2026-06-01T15:00",
                    "temperature_2m": 22.8,
                    "relative_humidity_2m": 64,
                    "apparent_temperature": 23.1,
                    "is_day": 1,
                    "precipitation": 0.4,
                    "weather_code": 61,
                    "cloud_cover": 78,
                    "pressure_msl": 1015.4,
                    "surface_pressure": 932.1,
                    "wind_speed_10m": 14.2,
                    "wind_direction_10m": 135,
                    "wind_gusts_10m": 26.0
                  },
                  "hourly": {
                    "time": ["2026-06-01T15:00", "2026-06-01T16:00"],
                    "temperature_2m": [22.8, 21.9],
                    "relative_humidity_2m": [64, 68],
                    "dew_point_2m": [15.6, 15.8],
                    "apparent_temperature": [23.1, 22.0],
                    "precipitation_probability": [60, 75],
                    "precipitation": [0.4, 1.2],
                    "weather_code": [61, 63],
                    "pressure_msl": [1015.4, 1015.0],
                    "visibility": [12000.0, 9000.0],
                    "wind_speed_10m": [14.2, 16.0],
                    "wind_direction_10m": [135, 140],
                    "wind_gusts_10m": [26.0, 30.0],
                    "uv_index": [4.2, 2.5],
                    "is_day": [1, 1]
                  },
                  "daily": {
                    "time": ["2026-06-01"],
                    "weather_code": [61],
                    "temperature_2m_max": [24.0],
                    "temperature_2m_min": [16.5],
                    "sunrise": ["2026-06-01T06:42"],
                    "sunset": ["2026-06-01T17:28"],
                    "uv_index_max": [6.8],
                    "precipitation_sum": [5.4],
                    "precipitation_probability_max": [80],
                    "wind_speed_10m_max": [18.5],
                    "wind_gusts_10m_max": [32.0],
                    "wind_direction_10m_dominant": [135]
                  }
                }
                """.trimIndent(),
            )
        }

        val api = NetworkClientFactory.createForecastApi(client, moshi)
        val outcome = SafeApiExecutor.execute(OpenMeteoForecastApi.PROVIDER_NAME) {
            api.getForecast(-23.5505, -46.6333)
        }

        assertTrue(outcome is RemoteCallOutcome.Success)
        val url = capturedRequest!!.url
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals("auto", url.queryParameter("timezone"))
        assertEquals("16", url.queryParameter("forecast_days"))
        assertEquals("celsius", url.queryParameter("temperature_unit"))
        assertEquals("kmh", url.queryParameter("wind_speed_unit"))
        assertEquals("mm", url.queryParameter("precipitation_unit"))
        assertTrue(capturedRequest!!.header("User-Agent")!!.contains("Clima3D-Android"))

        val bundle = OpenMeteoForecastMapper.toDomain(
            dto = (outcome as RemoteCallOutcome.Success).body,
            requestedLatitude = -23.5505,
            requestedLongitude = -46.6333,
            fetchedAtEpochMillis = 123_456L,
        )
        assertNotNull(bundle)
        assertEquals(22.8, bundle!!.current.temperatureC!!, 0.001)
        assertEquals(64, bundle.current.relativeHumidityPercent)
        assertEquals(15.6, bundle.current.dewPointC!!, 0.001)
        assertEquals(12000.0, bundle.current.visibilityMeters!!, 0.001)
        assertEquals(4.2, bundle.current.uvIndex!!, 0.001)
        assertEquals(1015.4, bundle.current.pressureMslHpa!!, 0.001)
        assertEquals("Chuva fraca", bundle.current.weatherDescriptionPt)
        assertEquals(2, bundle.hourly.size)
        assertEquals(1, bundle.daily.size)
    }

    @Test
    fun `OpenMeteoGeocodingApi parses multi-city results and empty zero-match payload`() = runTest {
        val client = stubClient { req ->
            val query = req.url.queryParameter("name")
            if (query == "Campinas") {
                jsonResponse(
                    req,
                    200,
                    """
                    {
                      "results": [
                        {
                          "id": 3467865,
                          "name": "Campinas",
                          "latitude": -22.9056,
                          "longitude": -47.0608,
                          "elevation": 685.0,
                          "country_code": "BR",
                          "country": "Brasil",
                          "admin1": "São Paulo",
                          "timezone": "America/Sao_Paulo",
                          "population": 1139047
                        },
                        {
                          "id": 3467866,
                          "name": "Campinas do Sul",
                          "latitude": -27.7150,
                          "longitude": -52.6269,
                          "country_code": "BR",
                          "country": "Brasil",
                          "admin1": "Rio Grande do Sul",
                          "timezone": "America/Sao_Paulo"
                        }
                      ],
                      "generationtime_ms": 0.42
                    }
                    """.trimIndent(),
                )
            } else {
                // Open-Meteo omits "results" when no match is found
                jsonResponse(req, 200, """{"generationtime_ms": 0.11}""")
            }
        }

        val api = NetworkClientFactory.createGeocodingApi(client, moshi)
        val found = OpenMeteoGeocodingMapper.toDomain(api.searchCities("Campinas"))
        assertEquals(2, found.size)
        assertEquals("São Paulo", found[0].admin1)
        assertEquals("Rio Grande do Sul", found[1].admin1)

        val empty = OpenMeteoGeocodingMapper.toDomain(api.searchCities("CidadeInexistenteXYZ"))
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `RainViewerApi parses real weather-maps json and rejects empty past array cleanly`() = runTest {
        val client = stubClient { req ->
            jsonResponse(
                req,
                200,
                """
                {
                  "version": "2.0",
                  "generated": 1780303600,
                  "host": "https://tilecache.rainviewer.com",
                  "radar": {
                    "past": [
                      { "time": 1780300000, "path": "/v2/radar/1780300000" },
                      { "time": 1780300600, "path": "/v2/radar/1780300600" },
                      { "time": 1780301200, "path": "/v2/radar/1780301200" }
                    ]
                  }
                }
                """.trimIndent(),
            )
        }

        val api = NetworkClientFactory.createRainViewerApi(client, moshi)
        val dto = api.getWeatherMaps()
        val snapshot = RainViewerMapper.toDomain(dto, fetchedAtEpochMillis = 999L, displayTimezoneId = "UTC")
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.frames.size)
        assertEquals(false, snapshot.nowcastAvailable)

        val emptySnapshot = RainViewerMapper.toDomain(
            dto.copy(radar = dto.radar?.copy(past = emptyList())),
            fetchedAtEpochMillis = 999L,
        )
        assertNull(emptySnapshot)
    }

    @Test
    fun `SafeApiExecutor maps HTTP 429, HTTP 503, SocketTimeoutException, UnknownHostException, and malformed JSON`() = runTest {
        // HTTP 429 Too Many Requests
        val http429Api = NetworkClientFactory.createAirQualityApi(
            stubClient { req -> jsonResponse(req, 429, """{"error":true,"reason":"Rate limit exceeded"}""") },
            moshi,
        )
        val rateLimitOutcome = SafeApiExecutor.execute(OpenMeteoAirQualityApi.PROVIDER_NAME) {
            http429Api.getAirQuality(-23.55, -46.63)
        }
        assertTrue(rateLimitOutcome is RemoteCallOutcome.Failure)
        val httpErr = (rateLimitOutcome as RemoteCallOutcome.Failure).error as WeatherError.HttpError
        assertEquals(429, httpErr.code)

        // HTTP 503 Service Unavailable
        val http503Api = NetworkClientFactory.createRainViewerApi(
            stubClient { req -> jsonResponse(req, 503, """Service unavailable""") },
            moshi,
        )
        val unavailableOutcome = SafeApiExecutor.execute(RainViewerApi.PROVIDER_NAME) {
            http503Api.getWeatherMaps()
        }
        assertEquals(503, ((unavailableOutcome as RemoteCallOutcome.Failure).error as WeatherError.HttpError).code)

        // Timeout
        val timeoutApi = NetworkClientFactory.createForecastApi(
            stubClient { throw SocketTimeoutException("Read timed out") },
            moshi,
        )
        val timeoutOutcome = SafeApiExecutor.execute(OpenMeteoForecastApi.PROVIDER_NAME) {
            timeoutApi.getForecast(0.0, 0.0)
        }
        assertTrue((timeoutOutcome as RemoteCallOutcome.Failure).error is WeatherError.TimeoutError)

        // Network unavailable (DNS failure)
        val offlineApi = NetworkClientFactory.createForecastApi(
            stubClient { throw UnknownHostException("Unable to resolve host") },
            moshi,
        )
        val offlineOutcome = SafeApiExecutor.execute(OpenMeteoForecastApi.PROVIDER_NAME) {
            offlineApi.getForecast(0.0, 0.0)
        }
        assertTrue((offlineOutcome as RemoteCallOutcome.Failure).error is WeatherError.NetworkUnavailableError)

        // Malformed JSON
        val badJsonApi = NetworkClientFactory.createForecastApi(
            stubClient { req -> jsonResponse(req, 200, "{not_valid_json") },
            moshi,
        )
        val badJsonOutcome = SafeApiExecutor.execute(OpenMeteoForecastApi.PROVIDER_NAME) {
            badJsonApi.getForecast(0.0, 0.0)
        }
        assertTrue((badJsonOutcome as RemoteCallOutcome.Failure).error is WeatherError.SerializationError)
    }
}
