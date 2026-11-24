package com.example.data.remote

import com.example.data.model.AirQualitySnapshot
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit contract for the official Open-Meteo Air Quality API v1.
 * Documentation: https://open-meteo.com/en/docs/air-quality-api
 */
interface OpenMeteoAirQualityApi {
    @GET("v1/air-quality")
    suspend fun getAirQuality(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT_VARIABLES,
        @Query("timezone") timezone: String = "auto",
    ): OpenMeteoAirQualityResponseDto

    companion object {
        const val PROVIDER_NAME: String = "Open-Meteo Air Quality"

        const val CURRENT_VARIABLES: String =
            "european_aqi,us_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide," +
                "sulphur_dioxide,ozone,dust,aerosol_optical_depth,uv_index"
    }
}

@JsonClass(generateAdapter = true)
data class OpenMeteoAirQualityResponseDto(
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "utc_offset_seconds") val utcOffsetSeconds: Int? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "current") val current: OpenMeteoAirQualityCurrentDto? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoAirQualityCurrentDto(
    @Json(name = "time") val time: String? = null,
    @Json(name = "european_aqi") val europeanAqi: Int? = null,
    @Json(name = "us_aqi") val usAqi: Int? = null,
    @Json(name = "pm2_5") val pm25: Double? = null,
    @Json(name = "pm10") val pm10: Double? = null,
    @Json(name = "nitrogen_dioxide") val nitrogenDioxide: Double? = null,
    @Json(name = "ozone") val ozone: Double? = null,
    @Json(name = "carbon_monoxide") val carbonMonoxide: Double? = null,
    @Json(name = "sulphur_dioxide") val sulphurDioxide: Double? = null,
    @Json(name = "dust") val dust: Double? = null,
    @Json(name = "aerosol_optical_depth") val aerosolOpticalDepth: Double? = null,
    @Json(name = "uv_index") val uvIndex: Double? = null,
)

object OpenMeteoAirQualityMapper {
    fun toDomain(
        dto: OpenMeteoAirQualityResponseDto,
        requestedLatitude: Double,
        requestedLongitude: Double,
        fetchedAtEpochMillis: Long,
    ): AirQualitySnapshot? {
        val current = dto.current ?: return null
        val snapshot = AirQualitySnapshot(
            latitude = dto.latitude ?: requestedLatitude,
            longitude = dto.longitude ?: requestedLongitude,
            timezoneId = dto.timezone?.takeIf { it.isNotBlank() } ?: "UTC",
            utcOffsetSeconds = dto.utcOffsetSeconds ?: 0,
            timeIso = current.time,
            europeanAqi = current.europeanAqi,
            usAqi = current.usAqi,
            pm25UgM3 = current.pm25,
            pm10UgM3 = current.pm10,
            nitrogenDioxideUgM3 = current.nitrogenDioxide,
            ozoneUgM3 = current.ozone,
            carbonMonoxideUgM3 = current.carbonMonoxide,
            sulphurDioxideUgM3 = current.sulphurDioxide,
            dustUgM3 = current.dust,
            aerosolOpticalDepth = current.aerosolOpticalDepth,
            uvIndex = current.uvIndex,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
        return snapshot.takeIf { it.hasAnyIndicator() }
    }
}
