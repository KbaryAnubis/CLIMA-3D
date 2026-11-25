package com.example.data.model

import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapMarker
import java.util.Locale

/**
 * Single city candidate returned by Open-Meteo Geocoding API.
 * Preserves administrative regions and country fields to disambiguate same-name cities.
 */
data class CitySearchResult(
    val providerId: Long,
    val name: String,
    val admin1: String?,
    val admin2: String?,
    val country: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timezoneId: String?,
    val population: Int?,
) {
    /**
     * Builds a disambiguation subtitle combining municipality/district, state/region and country
     * (e.g. "Rio de Janeiro, Brasil" for "Copacabana" or "São Paulo, Brasil").
     */
    fun displayRegionLabel(): String {
        val parts = buildList {
            val cleanAdmin2 = admin2?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals(name, ignoreCase = true)
            }
            val cleanAdmin1 = admin1?.trim()?.takeIf {
                it.isNotEmpty() &&
                    !it.equals(name, ignoreCase = true) &&
                    !it.equals(cleanAdmin2, ignoreCase = true)
            }
            cleanAdmin2?.let { add(it) }
            cleanAdmin1?.let { add(it) }
            country?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                ?: countryCode?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        return parts.joinToString(separator = ", ")
    }

    /**
     * Stable deduplication key combining normalized locality name, region, country and 2-decimal coordinates.
     * Prevents duplicate entries from the geocoding provider while preserving distinct cities with the same name.
     */
    fun deduplicationKey(): String {
        val normName = name.trim().lowercase(Locale.ROOT)
        val normAdmin1 = admin1?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normAdmin2 = admin2?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normCountry = (countryCode ?: country)?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val coordBucket = String.format(Locale.US, "%.2f_%.2f", latitude, longitude)
        return "$normName|$normAdmin2|$normAdmin1|$normCountry|$coordBucket"
    }

    /**
     * Full human-readable label differentiating cities with identical names.
     */
    fun fullDisambiguatedLabel(): String {
        val subtitle = displayRegionLabel()
        return if (subtitle.isNotBlank()) "$name — $subtitle" else name
    }

    fun toCityMapCoordinate(): CityMapCoordinate =
        CityMapCoordinate(latitude = latitude, longitude = longitude)

    fun toCityMapMarker(): CityMapMarker =
        CityMapMarker(
            id = "geo_$providerId",
            label = fullDisambiguatedLabel(),
            coordinate = toCityMapCoordinate(),
            isCurrentLocation = false,
        )
}

/**
 * Persisted user city, current GPS location, or custom map point stored in Room.
 */
data class SavedLocation(
    val id: Long = 0L,
    val providerId: Long? = null,
    val name: String,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezoneId: String? = null,
    val isCurrentLocation: Boolean = false,
    val isPrimary: Boolean = false,
    val isCustomMapPin: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val coordinateKey: String
        get() = buildCoordinateKey(latitude, longitude)

    fun displaySubtitle(): String {
        val parts = listOfNotNull(
            region?.takeIf { it.isNotBlank() },
            country?.takeIf { it.isNotBlank() } ?: countryCode?.takeIf { it.isNotBlank() },
        )
        return parts.joinToString(separator = ", ")
    }

    fun toCityMapCoordinate(): CityMapCoordinate =
        CityMapCoordinate(latitude = latitude, longitude = longitude)

    fun toCityMapMarker(): CityMapMarker =
        CityMapMarker(
            id = if (id > 0L) "saved_$id" else "saved_$coordinateKey",
            label = name,
            coordinate = toCityMapCoordinate(),
            isCurrentLocation = isCurrentLocation,
        )

    companion object {
        /**
         * Stable 3-decimal coordinate key (~110m precision) shared between SavedLocation and Room caches.
         */
        fun buildCoordinateKey(latitude: Double, longitude: Double): String =
            String.format(Locale.US, "%.3f_%.3f", latitude, longitude)
    }
}
