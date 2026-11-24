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
     * Builds a disambiguation subtitle combining region and country (e.g. "São Paulo, Brasil").
     */
    fun displayRegionLabel(): String {
        val parts = buildList {
            admin1?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }?.let { add(it) }
            if (isEmpty()) {
                admin2?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }?.let { add(it) }
            }
            country?.takeIf { it.isNotBlank() }?.let { add(it) }
                ?: countryCode?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return parts.joinToString(separator = ", ")
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
