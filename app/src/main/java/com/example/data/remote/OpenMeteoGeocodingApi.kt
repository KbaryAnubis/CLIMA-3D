package com.example.data.remote

import com.example.data.model.CitySearchResult
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit contract for the official Open-Meteo Geocoding API v1.
 * Documentation: https://open-meteo.com/en/docs/geocoding-api
 */
interface OpenMeteoGeocodingApi {
    @GET("v1/search")
    suspend fun searchCities(
        @Query("name") name: String,
        @Query("count") count: Int = DEFAULT_RESULT_COUNT,
        @Query("language") language: String = DEFAULT_LANGUAGE,
        @Query("format") format: String = "json",
    ): OpenMeteoGeocodingResponseDto

    companion object {
        const val PROVIDER_NAME: String = "Open-Meteo Geocoding"
        const val DEFAULT_RESULT_COUNT: Int = 10
        const val DEFAULT_LANGUAGE: String = "pt"
    }
}

@JsonClass(generateAdapter = true)
data class OpenMeteoGeocodingResponseDto(
    @Json(name = "results") val results: List<OpenMeteoGeocodingResultDto>? = null,
    @Json(name = "generationtime_ms") val generationTimeMs: Double? = null,
)

@JsonClass(generateAdapter = true)
data class OpenMeteoGeocodingResultDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "elevation") val elevation: Double? = null,
    @Json(name = "country_code") val countryCode: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "admin1") val admin1: String? = null,
    @Json(name = "admin2") val admin2: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "population") val population: Int? = null,
)

object OpenMeteoGeocodingMapper {
    fun toDomain(dto: OpenMeteoGeocodingResponseDto): List<CitySearchResult> {
        val rawResults = dto.results ?: return emptyList()
        val mapped = rawResults.mapNotNull { item ->
            val name = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val lat = item.latitude ?: return@mapNotNull null
            val lon = item.longitude ?: return@mapNotNull null
            val id = item.id ?: ((lat * 10_000).toLong() * 31L + (lon * 10_000).toLong())
            CitySearchResult(
                providerId = id,
                name = name,
                admin1 = item.admin1?.trim()?.takeIf { it.isNotEmpty() },
                admin2 = item.admin2?.trim()?.takeIf { it.isNotEmpty() },
                country = item.country?.trim()?.takeIf { it.isNotEmpty() },
                countryCode = item.countryCode?.trim()?.takeIf { it.isNotEmpty() },
                latitude = lat,
                longitude = lon,
                elevationMeters = item.elevation,
                timezoneId = item.timezone?.trim()?.takeIf { it.isNotEmpty() },
                population = item.population,
            )
        }
        return deduplicate(mapped)
    }

    /**
     * Removes duplicate geocoding results (by providerId or normalized locality + region + coordinate bucket)
     * while preserving distinct cities with identical names in different states/countries.
     */
    fun deduplicate(results: List<CitySearchResult>): List<CitySearchResult> {
        if (results.size <= 1) return results
        val seenProviderIds = HashSet<Long>(results.size)
        val seenKeys = HashSet<String>(results.size)
        val out = ArrayList<CitySearchResult>(results.size)
        for (item in results) {
            val key = item.deduplicationKey()
            val isNewId = seenProviderIds.add(item.providerId)
            val isNewKey = seenKeys.add(key)
            if (isNewId && isNewKey) {
                out.add(item)
            }
        }
        return out
    }
}
