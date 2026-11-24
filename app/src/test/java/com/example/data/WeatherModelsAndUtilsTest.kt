package com.example.data

import com.example.data.model.AirQualitySnapshot
import com.example.data.model.CitySearchResult
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.TemperatureUnit
import com.example.data.model.TimeFormatPreference
import com.example.data.model.VisibilityUnit
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherTimeUtils
import com.example.data.model.WeatherUnitConverter
import com.example.data.model.WindSpeedUnit
import com.example.data.model.WmoWeatherCodeMapper
import com.example.data.model.PrecipitationUnit
import com.example.data.model.PressureUnit
import com.example.data.remote.OpenMeteoAirQualityCurrentDto
import com.example.data.remote.OpenMeteoAirQualityMapper
import com.example.data.remote.OpenMeteoAirQualityResponseDto
import com.example.data.remote.OpenMeteoCurrentDto
import com.example.data.remote.OpenMeteoDailyDto
import com.example.data.remote.OpenMeteoForecastMapper
import com.example.data.remote.OpenMeteoForecastResponseDto
import com.example.data.remote.OpenMeteoHourlyDto
import com.example.data.remote.RainViewerFrameDto
import com.example.data.remote.RainViewerMapper
import com.example.data.remote.RainViewerMapsResponseDto
import com.example.data.remote.RainViewerRadarSectionDto
import com.example.media.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherModelsAndUtilsTest {

    @Test
    fun `WeatherUnitConverter accurately converts temperature, wind, pressure, precipitation, visibility, and wind direction`() {
        assertEquals(32.0, WeatherUnitConverter.convertTemperature(0.0, TemperatureUnit.FAHRENHEIT), 0.001)
        assertEquals(77.0, WeatherUnitConverter.convertTemperature(25.0, TemperatureUnit.FAHRENHEIT), 0.001)
        assertEquals(25.0, WeatherUnitConverter.convertTemperature(25.0, TemperatureUnit.CELSIUS), 0.001)

        assertEquals(10.0, WeatherUnitConverter.convertWindSpeed(36.0, WindSpeedUnit.MS), 0.001)
        assertEquals(22.369, WeatherUnitConverter.convertWindSpeed(36.0, WindSpeedUnit.MPH), 0.01)
        assertEquals(19.438, WeatherUnitConverter.convertWindSpeed(36.0, WindSpeedUnit.KNOTS), 0.01)

        assertEquals(760.0, WeatherUnitConverter.convertPressure(1013.25, PressureUnit.MMHG), 0.05)
        assertEquals(29.92, WeatherUnitConverter.convertPressure(1013.25, PressureUnit.INHG), 0.01)

        assertEquals(1.0, WeatherUnitConverter.convertPrecipitation(25.4, PrecipitationUnit.INCH), 0.001)
        assertEquals(10.0, WeatherUnitConverter.convertVisibilityFromMeters(10_000.0, VisibilityUnit.KM), 0.001)
        assertEquals(1.0, WeatherUnitConverter.convertVisibilityFromMeters(1609.344, VisibilityUnit.MILES), 0.001)

        assertEquals("N", WeatherUnitConverter.windDirectionCardinalPt(0))
        assertEquals("NE", WeatherUnitConverter.windDirectionCardinalPt(45))
        assertEquals("L", WeatherUnitConverter.windDirectionCardinalPt(90))
        assertEquals("S", WeatherUnitConverter.windDirectionCardinalPt(180))
        assertEquals("O", WeatherUnitConverter.windDirectionCardinalPt(270))
        assertEquals("NO", WeatherUnitConverter.windDirectionCardinalPt(-45))
        assertNull(WeatherUnitConverter.windDirectionCardinalPt(null))
        assertNull(WeatherUnitConverter.formatTemperature(null, TemperatureUnit.CELSIUS))
    }

    @Test
    fun `WmoWeatherCodeMapper distinguishes day, night, storm, hail, snow, and unknown codes`() {
        assertEquals(WeatherCondition.SOL, WmoWeatherCodeMapper.toVisualCondition(0, isDay = true))
        assertEquals(WeatherCondition.NOITE, WmoWeatherCodeMapper.toVisualCondition(0, isDay = false))
        assertEquals("Céu limpo", WmoWeatherCodeMapper.descriptionPt(0, isDay = true))
        assertEquals("Noite limpa", WmoWeatherCodeMapper.descriptionPt(0, isDay = false))

        assertEquals(WeatherCondition.CHUVA, WmoWeatherCodeMapper.toVisualCondition(63, isDay = true))
        assertEquals(WeatherCondition.NOITE_CHUVA, WmoWeatherCodeMapper.toVisualCondition(63, isDay = false))
        assertEquals(WeatherCondition.CHUVA, WmoWeatherCodeMapper.toVisualCondition(65, isDay = true))
        assertEquals(WeatherCondition.NOITE_CHUVA, WmoWeatherCodeMapper.toVisualCondition(82, isDay = false))
        assertEquals(WeatherCondition.TEMPESTADE, WmoWeatherCodeMapper.toVisualCondition(95, isDay = true))
        assertEquals(WeatherCondition.TEMPESTADE, WmoWeatherCodeMapper.toVisualCondition(95, isDay = false))
        assertEquals(WeatherCondition.GRANIZO, WmoWeatherCodeMapper.toVisualCondition(96, isDay = true))
        assertEquals(WeatherCondition.GRANIZO, WmoWeatherCodeMapper.toVisualCondition(96, isDay = false))
        assertEquals(WeatherCondition.GRANIZO, WmoWeatherCodeMapper.toVisualCondition(99, isDay = true))
        assertEquals(WeatherCondition.GRANIZO, WmoWeatherCodeMapper.toVisualCondition(99, isDay = false))
        assertEquals("Tempestade com granizo leve", WmoWeatherCodeMapper.descriptionPt(96, isDay = true))
        assertEquals("Tempestade com granizo forte", WmoWeatherCodeMapper.descriptionPt(99, isDay = false))
        assertEquals(WeatherCondition.NEVE_NOITE, WmoWeatherCodeMapper.toVisualCondition(73, isDay = false))

        assertEquals("Condição indisponível", WmoWeatherCodeMapper.descriptionPt(null, isDay = true))
        assertEquals("Condição indisponível", WmoWeatherCodeMapper.descriptionPt(999, isDay = true))
    }

    @Test
    fun `WeatherTimeUtils parses local Open-Meteo timestamps and formats across Sao Paulo and Tokyo timezones`() {
        // 2026-06-01T15:00 in UTC is 12:00 in America/Sao_Paulo (-03:00) and 00:00 (next day) in Asia/Tokyo (+09:00)
        val utcMillis = WeatherTimeUtils.parseOpenMeteoDateTimeToEpochMillis(
            isoLocalDateTime = "2026-06-01T15:00",
            timezoneId = "UTC",
            utcOffsetSeconds = 0,
        )
        assertNotNull(utcMillis)
        assertEquals(
            "12:00",
            WeatherTimeUtils.formatLocalTime(utcMillis!!, "America/Sao_Paulo", -10800, TimeFormatPreference.H24),
        )
        assertEquals(
            "00:00",
            WeatherTimeUtils.formatLocalTime(utcMillis, "Asia/Tokyo", 32400, TimeFormatPreference.H24),
        )

        // Unknown IANA timezone falls back cleanly to utcOffsetSeconds (-04:00 => 11:00)
        assertEquals(
            "11:00",
            WeatherTimeUtils.formatLocalTime(utcMillis, "Invalid/Zone_Name", -14400, TimeFormatPreference.H24),
        )
    }

    @Test
    fun `OpenMeteoForecastMapper aligns 37-hour window to current city hour, caps 16 days, and handles missing fields`() {
        val hourlyTimes = (0 until 72).map { hour ->
            val day = 1 + (hour / 24)
            val h = hour % 24
            "2026-06-${day.toString().padStart(2, '0')}T${h.toString().padStart(2, '0')}:00"
        }
        val dailyDates = (1..20).map { day -> "2026-06-${day.toString().padStart(2, '0')}" }

        val dto = OpenMeteoForecastResponseDto(
            latitude = -23.55,
            longitude = -46.63,
            timezone = "America/Sao_Paulo",
            utcOffsetSeconds = -10800,
            current = OpenMeteoCurrentDto(
                time = "2026-06-01T14:15", // 15-minute current timestamp must align to 14:00 hour
                temperature2m = 24.5,
                weatherCode = 2,
                isDay = 1,
                // Omit pressure, wind gusts, etc. to test missing-field resilience
            ),
            hourly = OpenMeteoHourlyDto(
                time = hourlyTimes,
                temperature2m = List(72) { 20.0 + (it % 10) },
                dewPoint2m = List(72) { if (it == 14) 15.2 else null },
                visibility = List(72) { if (it == 14) 9500.0 else null },
                uvIndex = List(72) { if (it == 14) 6.4 else null },
                isDay = List(72) { if ((it % 24) in 6..18) 1 else 0 },
            ),
            daily = OpenMeteoDailyDto(
                time = dailyDates,
                temperature2mMax = List(20) { 28.0 },
                temperature2mMin = List(20) { 16.0 },
                sunrise = List(20) { "2026-06-01T06:42" },
                sunset = List(20) { "2026-06-01T17:28" },
            ),
        )

        val bundle = OpenMeteoForecastMapper.toDomain(
            dto = dto,
            requestedLatitude = -23.55,
            requestedLongitude = -46.63,
            fetchedAtEpochMillis = 1_780_334_100_000L,
        )

        assertNotNull(bundle)
        bundle!!
        assertEquals(WeatherForecastBundle.HOURLY_WINDOW_HOURS, bundle.hourly.size)
        assertEquals("2026-06-01T14:00", bundle.hourly.first().timeIso)
        assertEquals("2026-06-03T02:00", bundle.hourly.last().timeIso)

        assertEquals(WeatherForecastBundle.DAILY_WINDOW_DAYS, bundle.daily.size)
        assertEquals(16.0, bundle.todayMinTempC!!, 0.001)
        assertEquals(28.0, bundle.todayMaxTempC!!, 0.001)
        assertEquals("2026-06-01T06:42", bundle.todaySunriseIso)
        assertEquals("2026-06-01T17:28", bundle.todaySunsetIso)

        // Current pulls dew point, visibility, UV from aligned current hour while keeping omitted fields null
        assertEquals(15.2, bundle.current.dewPointC!!, 0.001)
        assertEquals(9500.0, bundle.current.visibilityMeters!!, 0.001)
        assertEquals(6.4, bundle.current.uvIndex!!, 0.001)
        assertNull(bundle.current.windGustsKmh)
        assertNull(bundle.current.pressureMslHpa)
    }

    @Test
    fun `OpenMeteoAirQualityMapper never fabricates omitted indicators`() {
        val partialDto = OpenMeteoAirQualityResponseDto(
            latitude = -23.55,
            longitude = -46.63,
            timezone = "America/Sao_Paulo",
            utcOffsetSeconds = -10800,
            current = OpenMeteoAirQualityCurrentDto(
                time = "2026-06-01T14:00",
                usAqi = 42,
                pm25 = 8.4,
                pm10 = null,
                nitrogenDioxide = null,
                ozone = 54.0,
                carbonMonoxide = null,
            ),
        )

        val snapshot = OpenMeteoAirQualityMapper.toDomain(partialDto, -23.55, -46.63, 1_000L)
        assertNotNull(snapshot)
        assertEquals(42, snapshot!!.usAqi)
        assertEquals("Boa", snapshot.qualityCategoryPt())
        assertEquals(8.4, snapshot.pm25UgM3!!, 0.001)
        assertNull(snapshot.pm10UgM3)
        assertNull(snapshot.nitrogenDioxideUgM3)
        assertNull(snapshot.carbonMonoxideUgM3)
        assertEquals(54.0, snapshot.ozoneUgM3!!, 0.001)

        // Completely empty indicators must return null instead of fake zeros
        val allNullSnapshot = OpenMeteoAirQualityMapper.toDomain(
            OpenMeteoAirQualityResponseDto(current = OpenMeteoAirQualityCurrentDto(time = "2026-06-01T14:00")),
            -23.55,
            -46.63,
            1_000L,
        )
        assertNull(allNullSnapshot)
    }

    @Test
    fun `RainViewerMapper selects latest 9 real historical frames, never fabricates nowcast, and bridges MeteorologicalRadarTileProvider`() {
        val baseTime = 1_780_300_000L
        // 13 historical frames (10-minute spacing), out of order + 1 duplicate to verify sorting & deduplication
        val pastFrames = (0 until 13).map { idx ->
            val ts = baseTime + idx * 600L
            RainViewerFrameDto(time = ts, path = "/v2/radar/$ts")
        }.reversed() + RainViewerFrameDto(time = baseTime, path = "/v2/radar/$baseTime")

        val dto = RainViewerMapsResponseDto(
            version = "2.0",
            generated = baseTime + 12 * 600L,
            host = "https://tilecache.rainviewer.com",
            radar = RainViewerRadarSectionDto(
                past = pastFrames,
                nowcast = null, // Discontinued on public free tier (2026)
            ),
        )

        val snapshot = RainViewerMapper.toDomain(
            dto = dto,
            fetchedAtEpochMillis = 1_780_310_000_000L,
            displayTimezoneId = "America/Sao_Paulo",
            displayUtcOffsetSeconds = -10800,
        )

        assertNotNull(snapshot)
        snapshot!!
        assertEquals(RadarTimelineSnapshot.V53_TARGET_FRAME_COUNT, snapshot.frames.size)
        assertEquals(13, snapshot.totalPastFramesAvailable)
        assertFalse(snapshot.nowcastAvailable)
        assertEquals(RadarTimelineSnapshot.PUBLIC_MAX_ZOOM, snapshot.maxSupportedZoom)

        // Verify strictly ascending chronological order of the 9 most recent frames (indices 4..12)
        assertEquals(baseTime + 4 * 600L, snapshot.frames.first().timestampSeconds)
        assertEquals(baseTime + 12 * 600L, snapshot.frames.last().timestampSeconds)

        // Verify direct compatibility with preserved MeteorologicalRadarTileProvider
        val tileProvider = snapshot.latestFrame!!.toTileProvider()
        assertTrue(tileProvider.isValidTemplate())
        val tileUrl = tileProvider.getTileUrl(x = 38, y = 55, zoom = 6)
        assertNotNull(tileUrl)
        assertEquals(
            "https://tilecache.rainviewer.com/v2/radar/${baseTime + 12 * 600L}/512/6/38/55/2/1_1.png",
            tileUrl.toString(),
        )
    }

    @Test
    fun `CitySearchResult disambiguates cities with identical names using region and country`() {
        val springfieldIl = CitySearchResult(
            providerId = 1L,
            name = "Springfield",
            admin1 = "Illinois",
            admin2 = "Sangamon",
            country = "Estados Unidos",
            countryCode = "US",
            latitude = 39.7817,
            longitude = -89.6501,
            elevationMeters = 182.0,
            timezoneId = "America/Chicago",
            population = 114_394,
        )
        val springfieldMo = CitySearchResult(
            providerId = 2L,
            name = "Springfield",
            admin1 = "Missouri",
            admin2 = "Greene",
            country = "Estados Unidos",
            countryCode = "US",
            latitude = 37.2089,
            longitude = -93.2923,
            elevationMeters = 396.0,
            timezoneId = "America/Chicago",
            population = 169_176,
        )

        assertEquals("Springfield — Illinois, Estados Unidos", springfieldIl.fullDisambiguatedLabel())
        assertEquals("Springfield — Missouri, Estados Unidos", springfieldMo.fullDisambiguatedLabel())
        assertTrue(springfieldIl.fullDisambiguatedLabel() != springfieldMo.fullDisambiguatedLabel())
    }
}
