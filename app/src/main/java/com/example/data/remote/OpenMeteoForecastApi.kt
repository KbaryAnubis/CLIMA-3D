package com.example.data.remote

import com.example.data.model.CurrentWeather
import com.example.data.model.DailyForecastPoint
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherTimeUtils
import com.example.data.model.WmoWeatherCodeMapper
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit contract for the official Open-Meteo Forecast API v1.
 * Documentation: https://open-meteo.com/en/docs
 */
interface OpenMeteoForecastApi {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT_VARIABLES,
        @Query("hourly") hourly: String = HOURLY_VARIABLES,
        @Query("daily") daily: String = DAILY_VARIABLES,
        @Query("forecast_days") forecastDays: Int = WeatherForecastBundle.DAILY_WINDOW_DAYS,
        @Query("timezone") timezone: String = "auto",
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        @Query("wind_speed_unit") windSpeedUnit: String = "kmh",
        @Query("precipitation_unit") precipitationUnit: String = "mm",
    ): OpenMeteoForecastResponseDto

    companion object {
        const val PROVIDER_NAME: String = "Open-Meteo Forecast"

        const val CURRENT_VARIABLES: String =
            "temperature_2m,relative_humidity_2m,apparent_temperature,is_day," +
                "precipitation,rain,showers,snowfall,weather_code,cloud_cover," +
                "pressure_msl,surface_pressure,wind_speed_10m,wind_direction_10m,wind_gusts_10m"

        const val HOURLY_VARIABLES: String =
            "temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
                "precipitation_probability,precipitation,weather_code,pressure_msl," +
                "visibility,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index,is_day"

        const val DAILY_VARIABLES: String =
            "weather_code,temperature_2m_max,temperature_2m_min," +
                "apparent_temperature_max,apparent_temperature_min," +
                "sunrise,sunset,uv_index_max,precipitation_sum," +
                "precipitation_probability_max,wind_speed_10m_max," +
                "wind_gusts_10m_max,wind_direction_10m_dominant"
    }
}

@JsonClass(generateAdapter = true)
data class OpenMeteoForecastResponseDto(
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "elevation") val elevation: Double? = null,
    @Json(name = "utc_offset_seconds") val utcOffsetSeconds: Int? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "timezone_abbreviation") val timezoneAbbreviation: String? = null,
    @Json(name = "current") val current: OpenMeteoCurrentDto? = null,
    @Json(name = "hourly") val hourly: OpenMeteoHourlyDto? = null,
    @Json(name = "daily") val daily: OpenMeteoDailyDto? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoCurrentDto(
    @Json(name = "time") val time: String? = null,
    @Json(name = "temperature_2m") val temperature2m: Double? = null,
    @Json(name = "relative_humidity_2m") val relativeHumidity2m: Int? = null,
    @Json(name = "apparent_temperature") val apparentTemperature: Double? = null,
    @Json(name = "is_day") val isDay: Int? = null,
    @Json(name = "precipitation") val precipitation: Double? = null,
    @Json(name = "rain") val rain: Double? = null,
    @Json(name = "showers") val showers: Double? = null,
    @Json(name = "snowfall") val snowfall: Double? = null,
    @Json(name = "weather_code") val weatherCode: Int? = null,
    @Json(name = "cloud_cover") val cloudCover: Int? = null,
    @Json(name = "pressure_msl") val pressureMsl: Double? = null,
    @Json(name = "surface_pressure") val surfacePressure: Double? = null,
    @Json(name = "wind_speed_10m") val windSpeed10m: Double? = null,
    @Json(name = "wind_direction_10m") val windDirection10m: Int? = null,
    @Json(name = "wind_gusts_10m") val windGusts10m: Double? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoHourlyDto(
    @Json(name = "time") val time: List<String?>? = null,
    @Json(name = "temperature_2m") val temperature2m: List<Double?>? = null,
    @Json(name = "relative_humidity_2m") val relativeHumidity2m: List<Int?>? = null,
    @Json(name = "dew_point_2m") val dewPoint2m: List<Double?>? = null,
    @Json(name = "apparent_temperature") val apparentTemperature: List<Double?>? = null,
    @Json(name = "precipitation_probability") val precipitationProbability: List<Int?>? = null,
    @Json(name = "precipitation") val precipitation: List<Double?>? = null,
    @Json(name = "weather_code") val weatherCode: List<Int?>? = null,
    @Json(name = "pressure_msl") val pressureMsl: List<Double?>? = null,
    @Json(name = "visibility") val visibility: List<Double?>? = null,
    @Json(name = "wind_speed_10m") val windSpeed10m: List<Double?>? = null,
    @Json(name = "wind_direction_10m") val windDirection10m: List<Int?>? = null,
    @Json(name = "wind_gusts_10m") val windGusts10m: List<Double?>? = null,
    @Json(name = "uv_index") val uvIndex: List<Double?>? = null,
    @Json(name = "is_day") val isDay: List<Int?>? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoDailyDto(
    @Json(name = "time") val time: List<String?>? = null,
    @Json(name = "weather_code") val weatherCode: List<Int?>? = null,
    @Json(name = "temperature_2m_max") val temperature2mMax: List<Double?>? = null,
    @Json(name = "temperature_2m_min") val temperature2mMin: List<Double?>? = null,
    @Json(name = "apparent_temperature_max") val apparentTemperatureMax: List<Double?>? = null,
    @Json(name = "apparent_temperature_min") val apparentTemperatureMin: List<Double?>? = null,
    @Json(name = "sunrise") val sunrise: List<String?>? = null,
    @Json(name = "sunset") val sunset: List<String?>? = null,
    @Json(name = "uv_index_max") val uvIndexMax: List<Double?>? = null,
    @Json(name = "precipitation_sum") val precipitationSum: List<Double?>? = null,
    @Json(name = "precipitation_probability_max") val precipitationProbabilityMax: List<Int?>? = null,
    @Json(name = "wind_speed_10m_max") val windSpeed10mMax: List<Double?>? = null,
    @Json(name = "wind_gusts_10m_max") val windGusts10mMax: List<Double?>? = null,
    @Json(name = "wind_direction_10m_dominant") val windDirection10mDominant: List<Int?>? = null,
)

/**
 * Safely converts [OpenMeteoForecastResponseDto] into [WeatherForecastBundle], handling
 * absent/nullable fields, aligning the 37-hour hourly window to the city's local timezone,
 * and preserving up to 16 daily entries.
 */
object OpenMeteoForecastMapper {
    fun toDomain(
        dto: OpenMeteoForecastResponseDto,
        requestedLatitude: Double,
        requestedLongitude: Double,
        fetchedAtEpochMillis: Long,
    ): WeatherForecastBundle? {
        if (dto.current == null && dto.hourly?.time.isNullOrEmpty() && dto.daily?.time.isNullOrEmpty()) {
            return null
        }

        val timezoneId = dto.timezone?.takeIf { it.isNotBlank() } ?: "UTC"
        val utcOffsetSeconds = dto.utcOffsetSeconds ?: 0

        val allHourlyTimes = dto.hourly?.time.orEmpty().map { it.orEmpty() }
        val startHourIndex = WeatherTimeUtils.findCurrentHourStartIndex(
            hourlyTimes = allHourlyTimes,
            currentTimeIso = dto.current?.time,
            timezoneId = timezoneId,
            utcOffsetSeconds = utcOffsetSeconds,
            fallbackNowEpochMillis = fetchedAtEpochMillis,
        )

        val hourlyList = buildHourlyWindow(
            hourlyDto = dto.hourly,
            startIndex = startHourIndex,
            maxHours = WeatherForecastBundle.HOURLY_WINDOW_HOURS,
            timezoneId = timezoneId,
            utcOffsetSeconds = utcOffsetSeconds,
        )

        val dailyList = buildDailyWindow(
            dailyDto = dto.daily,
            maxDays = WeatherForecastBundle.DAILY_WINDOW_DAYS,
        )

        val currentHourFallback = hourlyList.firstOrNull()
        val currentDto = dto.current
        val currentIsDay = when (currentDto?.isDay) {
            0 -> false
            1 -> true
            else -> currentHourFallback?.isDay ?: true
        }
        val currentCode = currentDto?.weatherCode ?: currentHourFallback?.weatherCode
        val currentVisual = WmoWeatherCodeMapper.toVisualCondition(currentCode, currentIsDay)

        val currentWeather = CurrentWeather(
            timeIso = currentDto?.time ?: currentHourFallback?.timeIso,
            temperatureC = currentDto?.temperature2m ?: currentHourFallback?.temperatureC,
            apparentTemperatureC = currentDto?.apparentTemperature ?: currentHourFallback?.apparentTemperatureC,
            relativeHumidityPercent = currentDto?.relativeHumidity2m ?: currentHourFallback?.relativeHumidityPercent,
            dewPointC = currentHourFallback?.dewPointC,
            precipitationMm = currentDto?.precipitation ?: currentHourFallback?.precipitationMm,
            rainMm = currentDto?.rain,
            showersMm = currentDto?.showers,
            snowfallCm = currentDto?.snowfall,
            weatherCode = currentCode,
            weatherDescriptionPt = WmoWeatherCodeMapper.descriptionPt(currentCode, currentIsDay),
            visualConditionKey = currentVisual.key,
            isDay = currentIsDay,
            cloudCoverPercent = currentDto?.cloudCover,
            pressureMslHpa = currentDto?.pressureMsl ?: currentHourFallback?.pressureMslHpa,
            surfacePressureHpa = currentDto?.surfacePressure,
            windSpeedKmh = currentDto?.windSpeed10m ?: currentHourFallback?.windSpeedKmh,
            windDirectionDegrees = currentDto?.windDirection10m ?: currentHourFallback?.windDirectionDegrees,
            windGustsKmh = currentDto?.windGusts10m ?: currentHourFallback?.windGustsKmh,
            uvIndex = currentHourFallback?.uvIndex,
            visibilityMeters = currentHourFallback?.visibilityMeters,
        )

        return WeatherForecastBundle(
            latitude = dto.latitude ?: requestedLatitude,
            longitude = dto.longitude ?: requestedLongitude,
            elevationMeters = dto.elevation,
            timezoneId = timezoneId,
            timezoneAbbreviation = dto.timezoneAbbreviation,
            utcOffsetSeconds = utcOffsetSeconds,
            current = currentWeather,
            hourly = hourlyList,
            daily = dailyList,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
    }

    private fun buildHourlyWindow(
        hourlyDto: OpenMeteoHourlyDto?,
        startIndex: Int,
        maxHours: Int,
        timezoneId: String,
        utcOffsetSeconds: Int,
    ): List<HourlyForecastPoint> {
        val times = hourlyDto?.time ?: return emptyList()
        if (times.isEmpty()) return emptyList()

        val safeStart = startIndex.coerceIn(0, times.lastIndex)
        val endExclusive = (safeStart + maxHours).coerceAtMost(times.size)

        return buildList(endExclusive - safeStart) {
            for (i in safeStart until endExclusive) {
                val timeIso = times.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
                val isDay = hourlyDto.isDay?.getOrNull(i)?.let { it == 1 } ?: true
                val code = hourlyDto.weatherCode?.getOrNull(i)
                val visual = WmoWeatherCodeMapper.toVisualCondition(code, isDay)
                add(
                    HourlyForecastPoint(
                        timeIso = timeIso,
                        epochMillis = WeatherTimeUtils.parseOpenMeteoDateTimeToEpochMillis(
                            isoLocalDateTime = timeIso,
                            timezoneId = timezoneId,
                            utcOffsetSeconds = utcOffsetSeconds,
                        ),
                        temperatureC = hourlyDto.temperature2m?.getOrNull(i),
                        apparentTemperatureC = hourlyDto.apparentTemperature?.getOrNull(i),
                        relativeHumidityPercent = hourlyDto.relativeHumidity2m?.getOrNull(i),
                        dewPointC = hourlyDto.dewPoint2m?.getOrNull(i),
                        precipitationProbabilityPercent = hourlyDto.precipitationProbability?.getOrNull(i),
                        precipitationMm = hourlyDto.precipitation?.getOrNull(i),
                        weatherCode = code,
                        weatherDescriptionPt = WmoWeatherCodeMapper.descriptionPt(code, isDay),
                        visualConditionKey = visual.key,
                        isDay = isDay,
                        pressureMslHpa = hourlyDto.pressureMsl?.getOrNull(i),
                        visibilityMeters = hourlyDto.visibility?.getOrNull(i),
                        windSpeedKmh = hourlyDto.windSpeed10m?.getOrNull(i),
                        windDirectionDegrees = hourlyDto.windDirection10m?.getOrNull(i),
                        windGustsKmh = hourlyDto.windGusts10m?.getOrNull(i),
                        uvIndex = hourlyDto.uvIndex?.getOrNull(i),
                    ),
                )
            }
        }
    }

    private fun buildDailyWindow(
        dailyDto: OpenMeteoDailyDto?,
        maxDays: Int,
    ): List<DailyForecastPoint> {
        val dates = dailyDto?.time ?: return emptyList()
        if (dates.isEmpty()) return emptyList()

        val limit = dates.size.coerceAtMost(maxDays)
        return buildList(limit) {
            for (i in 0 until limit) {
                val dateIso = dates.getOrNull(i)?.takeIf { it.isNotBlank() } ?: continue
                val code = dailyDto.weatherCode?.getOrNull(i)
                val visual = WmoWeatherCodeMapper.toVisualCondition(code, isDay = true)
                add(
                    DailyForecastPoint(
                        dateIso = dateIso,
                        weatherCode = code,
                        weatherDescriptionPt = WmoWeatherCodeMapper.descriptionPt(code, isDay = true),
                        visualConditionKey = visual.key,
                        temperatureMaxC = dailyDto.temperature2mMax?.getOrNull(i),
                        temperatureMinC = dailyDto.temperature2mMin?.getOrNull(i),
                        apparentTemperatureMaxC = dailyDto.apparentTemperatureMax?.getOrNull(i),
                        apparentTemperatureMinC = dailyDto.apparentTemperatureMin?.getOrNull(i),
                        sunriseIso = dailyDto.sunrise?.getOrNull(i),
                        sunsetIso = dailyDto.sunset?.getOrNull(i),
                        uvIndexMax = dailyDto.uvIndexMax?.getOrNull(i),
                        precipitationSumMm = dailyDto.precipitationSum?.getOrNull(i),
                        precipitationProbabilityMaxPercent = dailyDto.precipitationProbabilityMax?.getOrNull(i),
                        windSpeedMaxKmh = dailyDto.windSpeed10mMax?.getOrNull(i),
                        windGustsMaxKmh = dailyDto.windGusts10mMax?.getOrNull(i),
                        windDirectionDominantDegrees = dailyDto.windDirection10mDominant?.getOrNull(i),
                    ),
                )
            }
        }
    }
}
