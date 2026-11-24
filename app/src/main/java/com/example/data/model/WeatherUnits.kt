package com.example.data.model

import java.util.Locale
import kotlin.math.roundToInt

enum class TemperatureUnit(val symbol: String) {
    CELSIUS("°C"),
    FAHRENHEIT("°F"),
}

enum class WindSpeedUnit(val symbol: String) {
    KMH("km/h"),
    MS("m/s"),
    MPH("mph"),
    KNOTS("kn"),
}

enum class PressureUnit(val symbol: String) {
    HPA("hPa"),
    MMHG("mmHg"),
    INHG("inHg"),
}

enum class PrecipitationUnit(val symbol: String) {
    MM("mm"),
    INCH("in"),
}

enum class VisibilityUnit(val symbol: String) {
    KM("km"),
    MILES("mi"),
}

enum class TimeFormatPreference {
    H24,
    H12,
}

/**
 * Pure unit conversions from Open-Meteo canonical metric values (°C, km/h, hPa, mm, meters)
 * into user-configured display units.
 */
object WeatherUnitConverter {
    private const val KMH_PER_MS = 3.6
    private const val KMH_PER_MPH = 1.609344
    private const val KMH_PER_KNOT = 1.852
    private const val MMHG_PER_HPA = 0.75006156
    private const val INHG_PER_HPA = 0.029529983
    private const val MM_PER_INCH = 25.4
    private const val METERS_PER_KM = 1000.0
    private const val METERS_PER_MILE = 1609.344

    fun convertTemperature(celsius: Double, target: TemperatureUnit): Double =
        when (target) {
            TemperatureUnit.CELSIUS -> celsius
            TemperatureUnit.FAHRENHEIT -> (celsius * 9.0 / 5.0) + 32.0
        }

    fun convertWindSpeed(kmh: Double, target: WindSpeedUnit): Double =
        when (target) {
            WindSpeedUnit.KMH -> kmh
            WindSpeedUnit.MS -> kmh / KMH_PER_MS
            WindSpeedUnit.MPH -> kmh / KMH_PER_MPH
            WindSpeedUnit.KNOTS -> kmh / KMH_PER_KNOT
        }

    fun convertPressure(hpa: Double, target: PressureUnit): Double =
        when (target) {
            PressureUnit.HPA -> hpa
            PressureUnit.MMHG -> hpa * MMHG_PER_HPA
            PressureUnit.INHG -> hpa * INHG_PER_HPA
        }

    fun convertPrecipitation(mm: Double, target: PrecipitationUnit): Double =
        when (target) {
            PrecipitationUnit.MM -> mm
            PrecipitationUnit.INCH -> mm / MM_PER_INCH
        }

    fun convertVisibilityFromMeters(meters: Double, target: VisibilityUnit): Double =
        when (target) {
            VisibilityUnit.KM -> meters / METERS_PER_KM
            VisibilityUnit.MILES -> meters / METERS_PER_MILE
        }

    fun formatTemperature(celsius: Double?, target: TemperatureUnit): String? {
        if (celsius == null) return null
        val converted = convertTemperature(celsius, target).roundToInt()
        return "$converted${target.symbol}"
    }

    fun formatWindSpeed(kmh: Double?, target: WindSpeedUnit, locale: Locale = Locale("pt", "BR")): String? {
        if (kmh == null) return null
        val converted = convertWindSpeed(kmh, target)
        return when (target) {
            WindSpeedUnit.MS -> String.format(locale, "%.1f %s", converted, target.symbol)
            WindSpeedUnit.KMH, WindSpeedUnit.MPH, WindSpeedUnit.KNOTS ->
                "${converted.roundToInt()} ${target.symbol}"
        }
    }

    fun formatPressure(hpa: Double?, target: PressureUnit, locale: Locale = Locale("pt", "BR")): String? {
        if (hpa == null) return null
        val converted = convertPressure(hpa, target)
        return when (target) {
            PressureUnit.INHG -> String.format(locale, "%.2f %s", converted, target.symbol)
            PressureUnit.HPA, PressureUnit.MMHG -> "${converted.roundToInt()} ${target.symbol}"
        }
    }

    fun formatPrecipitation(mm: Double?, target: PrecipitationUnit, locale: Locale = Locale("pt", "BR")): String? {
        if (mm == null) return null
        val converted = convertPrecipitation(mm, target)
        return when (target) {
            PrecipitationUnit.MM -> String.format(locale, "%.1f %s", converted, target.symbol)
            PrecipitationUnit.INCH -> String.format(locale, "%.2f %s", converted, target.symbol)
        }
    }

    fun formatVisibility(meters: Double?, target: VisibilityUnit, locale: Locale = Locale("pt", "BR")): String? {
        if (meters == null) return null
        val converted = convertVisibilityFromMeters(meters, target)
        return String.format(locale, "%.1f %s", converted, target.symbol)
    }

    /**
     * Converts meteorological wind direction degrees (0..360) into Portuguese 8-point cardinal direction.
     */
    fun windDirectionCardinalPt(degrees: Int?): String? {
        if (degrees == null) return null
        val normalized = ((degrees % 360) + 360) % 360
        val directions = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")
        val index = ((normalized + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }
}
