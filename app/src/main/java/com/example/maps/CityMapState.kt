package com.example.maps

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlin.math.roundToInt

@Immutable
data class CityMapCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    fun formatted(): String =
        String.format(Locale.US, "%.4f, %.4f", latitude, longitude)
}

@Immutable
data class CityMapMarker(
    val id: String,
    val label: String,
    val coordinate: CityMapCoordinate,
    val isCurrentLocation: Boolean = false,
)

enum class LocationPermissionState {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
}

@Immutable
data class CityMapUiState(
    val cameraCenter: CityMapCoordinate = CityMapCoordinate(-23.5505, -46.6333),
    val zoom: Double = 10.0,
    val selectedMarker: CityMapMarker =
        CityMapMarker(
            id = "selected_point",
            label = "Ponto selecionado",
            coordinate = CityMapCoordinate(-23.5505, -46.6333),
        ),
    val savedMarkers: List<CityMapMarker> = emptyList(),
    val currentUserLocation: CityMapCoordinate? = null,
    val permissionState: LocationPermissionState = LocationPermissionState.NOT_REQUESTED,
    val locationPrecision: LocationPrecisionLevel = LocationPrecisionLevel.NONE,
    val locationStatusNotice: String? = null,
    val isLocatingUser: Boolean = false,
    val cameraMoveVersion: Int = 0,
) {
    fun zoomIn(step: Double = 1.0): CityMapUiState {
        val nextZoom = (zoom + step).coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, OpenStreetMapTileConfig.MAX_ZOOM)
        if (nextZoom == zoom) return this
        return copy(zoom = nextZoom, cameraMoveVersion = cameraMoveVersion + 1)
    }

    fun zoomOut(step: Double = 1.0): CityMapUiState {
        val nextZoom = (zoom - step).coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, OpenStreetMapTileConfig.MAX_ZOOM)
        if (nextZoom == zoom) return this
        return copy(zoom = nextZoom, cameraMoveVersion = cameraMoveVersion + 1)
    }

    fun moveCamera(coordinate: CityMapCoordinate, newZoom: Double = zoom): CityMapUiState {
        val clampedZoom = newZoom.coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, OpenStreetMapTileConfig.MAX_ZOOM)
        return copy(
            cameraCenter = coordinate,
            zoom = clampedZoom,
            cameraMoveVersion = cameraMoveVersion + 1,
        )
    }

    fun syncCameraFromGesture(coordinate: CityMapCoordinate, gestureZoom: Double): CityMapUiState {
        val clampedZoom = gestureZoom.coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, OpenStreetMapTileConfig.MAX_ZOOM)
        return copy(cameraCenter = coordinate, zoom = clampedZoom)
    }

    fun selectMarkerAt(
        coordinate: CityMapCoordinate,
        label: String = selectedMarker.label,
        moveCameraToMarker: Boolean = true,
    ): CityMapUiState {
        val updatedMarker =
            CityMapMarker(
                id = "selected_point",
                label = label,
                coordinate = coordinate,
            )
        return if (moveCameraToMarker) {
            copy(
                cameraCenter = coordinate,
                selectedMarker = updatedMarker,
                cameraMoveVersion = cameraMoveVersion + 1,
            )
        } else {
            copy(selectedMarker = updatedMarker)
        }
    }

    fun withSavedMarkers(markers: List<CityMapMarker>): CityMapUiState =
        copy(savedMarkers = markers)

    fun withLocatingUser(locating: Boolean): CityMapUiState =
        copy(
            isLocatingUser = locating,
            locationStatusNotice = if (locating) null else locationStatusNotice,
        )

    fun withCurrentLocation(
        coordinate: CityMapCoordinate,
        label: String = "Minha localização",
        targetZoom: Double = 13.0,
        precision: LocationPrecisionLevel =
            if (locationPrecision == LocationPrecisionLevel.NONE) {
                LocationPrecisionLevel.PRECISE
            } else {
                locationPrecision
            },
    ): CityMapUiState {
        val clampedZoom = targetZoom.coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, OpenStreetMapTileConfig.MAX_ZOOM)
        return copy(
            cameraCenter = coordinate,
            zoom = clampedZoom,
            currentUserLocation = coordinate,
            selectedMarker =
                CityMapMarker(
                    id = "current_location",
                    label = label,
                    coordinate = coordinate,
                    isCurrentLocation = true,
                ),
            permissionState = LocationPermissionState.GRANTED,
            locationPrecision = precision,
            locationStatusNotice = null,
            isLocatingUser = false,
            cameraMoveVersion = cameraMoveVersion + 1,
        )
    }

    fun withPermissionState(
        state: LocationPermissionState,
        precision: LocationPrecisionLevel =
            when (state) {
                LocationPermissionState.GRANTED ->
                    if (locationPrecision == LocationPrecisionLevel.NONE) {
                        LocationPrecisionLevel.APPROXIMATE
                    } else {
                        locationPrecision
                    }
                LocationPermissionState.NOT_REQUESTED,
                LocationPermissionState.DENIED -> LocationPrecisionLevel.NONE
            },
        clearCurrentLocationIfDenied: Boolean = state != LocationPermissionState.GRANTED,
        notice: String? = locationStatusNotice,
    ): CityMapUiState =
        copy(
            permissionState = state,
            locationPrecision = precision,
            currentUserLocation = if (clearCurrentLocationIfDenied) null else currentUserLocation,
            locationStatusNotice = if (state == LocationPermissionState.GRANTED) notice else null,
            isLocatingUser = if (state == LocationPermissionState.GRANTED) isLocatingUser else false,
        )

    fun withLocationNotice(notice: String?): CityMapUiState =
        copy(
            locationStatusNotice = notice,
            isLocatingUser = false,
        )

    fun formattedZoomLabel(): String = "${(zoom * 10.0).roundToInt() / 10.0}x"
}

/**
 * Generates standard RFC 7946 GeoJSON FeatureCollection strings consumed by MapLibre GeoJsonSource.
 */
object CityMapGeoJsonBuilder {
    fun buildFeatureCollection(markers: List<CityMapMarker>): String {
        val features =
            markers.joinToString(separator = ",") { marker ->
                val escapedLabel = escapeJson(marker.label)
                val escapedId = escapeJson(marker.id)
                String.format(
                    Locale.US,
                    """{"type":"Feature","properties":{"id":"%s","label":"%s","isCurrent":%b},"geometry":{"type":"Point","coordinates":[%.6f,%.6f]}}""",
                    escapedId,
                    escapedLabel,
                    marker.isCurrentLocation,
                    marker.coordinate.longitude,
                    marker.coordinate.latitude,
                )
            }
        return """{"type":"FeatureCollection","features":[$features]}"""
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
