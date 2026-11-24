package com.example.data.model

import com.squareup.moshi.JsonClass

/**
 * Air quality observation returned by Open-Meteo Air Quality API.
 * All indicators are nullable so missing metrics from the provider are never fabricated.
 */
@JsonClass(generateAdapter = true)
data class AirQualitySnapshot(
    val latitude: Double,
    val longitude: Double,
    val timezoneId: String,
    val utcOffsetSeconds: Int,
    val timeIso: String?,
    val europeanAqi: Int?,
    val usAqi: Int?,
    val pm25UgM3: Double?,
    val pm10UgM3: Double?,
    val nitrogenDioxideUgM3: Double?,
    val ozoneUgM3: Double?,
    val carbonMonoxideUgM3: Double?,
    val sulphurDioxideUgM3: Double?,
    val dustUgM3: Double?,
    val aerosolOpticalDepth: Double?,
    val uvIndex: Double?,
    val fetchedAtEpochMillis: Long,
) {
    fun hasAnyIndicator(): Boolean =
        listOf(
            europeanAqi,
            usAqi,
            pm25UgM3,
            pm10UgM3,
            nitrogenDioxideUgM3,
            ozoneUgM3,
            carbonMonoxideUgM3,
            sulphurDioxideUgM3,
            dustUgM3,
            aerosolOpticalDepth,
            uvIndex,
        ).any { it != null }

    /**
     * Classifies the US AQI (or European AQI if US AQI is absent) into a Portuguese category.
     * Returns null when neither index is provided by the API.
     */
    fun qualityCategoryPt(): String? {
        usAqi?.let { value ->
            return when {
                value <= 50 -> "Boa"
                value <= 100 -> "Moderada"
                value <= 150 -> "Ruim para grupos sensíveis"
                value <= 200 -> "Ruim"
                value <= 300 -> "Muito ruim"
                else -> "Perigosa"
            }
        }
        europeanAqi?.let { value ->
            return when {
                value <= 20 -> "Boa"
                value <= 40 -> "Razoável"
                value <= 60 -> "Moderada"
                value <= 80 -> "Ruim"
                value <= 100 -> "Muito ruim"
                else -> "Extremamente ruim"
            }
        }
        return null
    }
}
