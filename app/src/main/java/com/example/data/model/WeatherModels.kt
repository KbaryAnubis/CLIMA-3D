package com.example.data.model

import com.example.media.WeatherCondition
import com.squareup.moshi.JsonClass

/**
 * Current weather observation in canonical SI/metric units (°C, km/h, hPa, mm, meters, %).
 * Nullable fields represent values that the remote provider may omit without causing failures.
 */
@JsonClass(generateAdapter = true)
data class CurrentWeather(
    val timeIso: String?,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val relativeHumidityPercent: Int?,
    val dewPointC: Double?,
    val precipitationMm: Double?,
    val rainMm: Double?,
    val showersMm: Double?,
    val snowfallCm: Double?,
    val weatherCode: Int?,
    val weatherDescriptionPt: String,
    val visualConditionKey: String,
    val isDay: Boolean,
    val cloudCoverPercent: Int?,
    val pressureMslHpa: Double?,
    val surfacePressureHpa: Double?,
    val windSpeedKmh: Double?,
    val windDirectionDegrees: Int?,
    val windGustsKmh: Double?,
    val uvIndex: Double?,
    val visibilityMeters: Double?,
) {
    val visualCondition: WeatherCondition
        get() = WeatherCondition.fromKey(visualConditionKey)
}

/**
 * Single hourly forecast entry (Clima 3D exposes the next 37 hours starting at the city's current hour).
 */
@JsonClass(generateAdapter = true)
data class HourlyForecastPoint(
    val timeIso: String,
    val epochMillis: Long?,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val relativeHumidityPercent: Int?,
    val dewPointC: Double?,
    val precipitationProbabilityPercent: Int?,
    val precipitationMm: Double?,
    val weatherCode: Int?,
    val weatherDescriptionPt: String,
    val visualConditionKey: String,
    val isDay: Boolean,
    val pressureMslHpa: Double?,
    val visibilityMeters: Double?,
    val windSpeedKmh: Double?,
    val windDirectionDegrees: Int?,
    val windGustsKmh: Double?,
    val uvIndex: Double?,
) {
    val visualCondition: WeatherCondition
        get() = WeatherCondition.fromKey(visualConditionKey)
}

/**
 * Single daily forecast entry (Clima 3D requests up to 16 days from Open-Meteo).
 */
@JsonClass(generateAdapter = true)
data class DailyForecastPoint(
    val dateIso: String,
    val weatherCode: Int?,
    val weatherDescriptionPt: String,
    val visualConditionKey: String,
    val temperatureMaxC: Double?,
    val temperatureMinC: Double?,
    val apparentTemperatureMaxC: Double?,
    val apparentTemperatureMinC: Double?,
    val sunriseIso: String?,
    val sunsetIso: String?,
    val uvIndexMax: Double?,
    val precipitationSumMm: Double?,
    val precipitationProbabilityMaxPercent: Int?,
    val windSpeedMaxKmh: Double?,
    val windGustsMaxKmh: Double?,
    val windDirectionDominantDegrees: Int?,
) {
    val visualCondition: WeatherCondition
        get() = WeatherCondition.fromKey(visualConditionKey)
}

/**
 * Complete meteorological bundle returned by Open-Meteo Forecast API and stored in local Room cache.
 */
@JsonClass(generateAdapter = true)
data class WeatherForecastBundle(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timezoneId: String,
    val timezoneAbbreviation: String?,
    val utcOffsetSeconds: Int,
    val current: CurrentWeather,
    val hourly: List<HourlyForecastPoint>,
    val daily: List<DailyForecastPoint>,
    val fetchedAtEpochMillis: Long,
) {
    val todayMinTempC: Double?
        get() = daily.firstOrNull()?.temperatureMinC

    val todayMaxTempC: Double?
        get() = daily.firstOrNull()?.temperatureMaxC

    val todaySunriseIso: String?
        get() = daily.firstOrNull()?.sunriseIso

    val todaySunsetIso: String?
        get() = daily.firstOrNull()?.sunsetIso

    companion object {
        const val HOURLY_WINDOW_HOURS: Int = 37
        const val DAILY_WINDOW_DAYS: Int = 16
    }
}
