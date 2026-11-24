package com.example.data.model

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * API 24+ compatible timezone and ISO-8601 date/time utilities for Open-Meteo and RainViewer.
 * Avoids java.time.* APIs that require API 26+ without core library desugaring.
 */
object WeatherTimeUtils {
    private const val PATTERN_ISO_LOCAL_DATE_TIME = "yyyy-MM-dd'T'HH:mm"
    private const val PATTERN_ISO_LOCAL_DATE_TIME_SEC = "yyyy-MM-dd'T'HH:mm:ss"
    private const val PATTERN_ISO_LOCAL_DATE = "yyyy-MM-dd"

    /**
     * Resolves a city's local [TimeZone] from Open-Meteo's IANA [timezoneId] (e.g. "America/Sao_Paulo"),
     * falling back to a fixed offset [SimpleTimeZone] using [utcOffsetSeconds] if the IANA ID is
     * missing or unrecognized.
     */
    fun resolveTimeZone(
        timezoneId: String?,
        utcOffsetSeconds: Int = 0,
        abbreviation: String? = null,
    ): TimeZone {
        val cleaned = timezoneId?.trim().orEmpty()
        if (cleaned.isNotEmpty()) {
            val candidate = TimeZone.getTimeZone(cleaned)
            val isExplicitGmt = cleaned.equals("GMT", ignoreCase = true) ||
                cleaned.equals("UTC", ignoreCase = true) ||
                cleaned.startsWith("GMT", ignoreCase = true) ||
                cleaned.startsWith("Etc/GMT", ignoreCase = true)
            if (isExplicitGmt || candidate.id != "GMT") {
                return candidate
            }
        }
        return SimpleTimeZone(utcOffsetSeconds * 1000, abbreviation?.takeIf { it.isNotBlank() } ?: "UTC")
    }

    /**
     * Parses an Open-Meteo local timestamp (e.g. "2026-06-01T14:00") using the city's local timezone
     * and returns UTC epoch milliseconds.
     */
    fun parseOpenMeteoDateTimeToEpochMillis(
        isoLocalDateTime: String?,
        timezoneId: String?,
        utcOffsetSeconds: Int = 0,
    ): Long? {
        val raw = isoLocalDateTime?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val tz = resolveTimeZone(timezoneId, utcOffsetSeconds)

        val patterns = listOf(
            PATTERN_ISO_LOCAL_DATE_TIME,
            PATTERN_ISO_LOCAL_DATE_TIME_SEC,
            PATTERN_ISO_LOCAL_DATE,
        )
        for (pattern in patterns) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = tz
            }
            try {
                val parsed = parser.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: ParseException) {
                // Try next pattern
            }
        }
        return null
    }

    /**
     * Formats a UTC epoch timestamp (in milliseconds) in the given city timezone.
     */
    fun formatLocalTime(
        epochMillis: Long,
        timezoneId: String?,
        utcOffsetSeconds: Int = 0,
        timeFormat: TimeFormatPreference = TimeFormatPreference.H24,
        locale: Locale = Locale("pt", "BR"),
    ): String {
        val pattern = when (timeFormat) {
            TimeFormatPreference.H24 -> "HH:mm"
            TimeFormatPreference.H12 -> "hh:mm a"
        }
        val formatter = SimpleDateFormat(pattern, locale).apply {
            timeZone = resolveTimeZone(timezoneId, utcOffsetSeconds)
        }
        return formatter.format(Date(epochMillis))
    }

    /**
     * Formats a UTC epoch timestamp (in milliseconds) as local date and time (e.g. "24/11 14:20").
     */
    fun formatLocalDateTime(
        epochMillis: Long,
        timezoneId: String?,
        utcOffsetSeconds: Int = 0,
        locale: Locale = Locale("pt", "BR"),
    ): String {
        val formatter = SimpleDateFormat("dd/MM HH:mm", locale).apply {
            timeZone = resolveTimeZone(timezoneId, utcOffsetSeconds)
        }
        return formatter.format(Date(epochMillis))
    }

    /**
     * Finds the starting index in an Open-Meteo `hourly.time` array corresponding to the city's
     * current local hour so that the next 37 hours window begins at the active hour.
     */
    fun findCurrentHourStartIndex(
        hourlyTimes: List<String>,
        currentTimeIso: String?,
        timezoneId: String?,
        utcOffsetSeconds: Int = 0,
        fallbackNowEpochMillis: Long = System.currentTimeMillis(),
    ): Int {
        if (hourlyTimes.isEmpty()) return 0

        // Open-Meteo current.time may be at a 15-minute interval (e.g., "2026-06-01T14:15"),
        // whereas hourly.time is aligned to ":00" (e.g., "2026-06-01T14:00").
        val currentHourPrefix = currentTimeIso?.takeIf { it.length >= 13 }?.substring(0, 13)
        if (currentHourPrefix != null) {
            val exactHourIdx = hourlyTimes.indexOfFirst { it.startsWith(currentHourPrefix) }
            if (exactHourIdx >= 0) return exactHourIdx
        }

        val referenceMillis =
            parseOpenMeteoDateTimeToEpochMillis(currentTimeIso, timezoneId, utcOffsetSeconds)
                ?: fallbackNowEpochMillis
        // Floor reference to the start of its hour so we include the ongoing hour.
        val flooredReferenceMillis = referenceMillis - (referenceMillis % 3_600_000L)

        val firstUpcoming = hourlyTimes.indexOfFirst { iso ->
            val millis = parseOpenMeteoDateTimeToEpochMillis(iso, timezoneId, utcOffsetSeconds)
            millis != null && millis >= flooredReferenceMillis
        }
        return if (firstUpcoming >= 0) firstUpcoming else 0
    }
}
