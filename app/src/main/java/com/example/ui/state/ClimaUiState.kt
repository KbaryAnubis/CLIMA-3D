package com.example.ui.state

import androidx.compose.runtime.Immutable
import com.example.data.local.preferences.UserPreferences
import com.example.data.model.AirQualitySnapshot
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.DailyForecastPoint
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.SavedLocation
import com.example.data.model.WeatherForecastBundle
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapUiState
import com.example.maps.RadarBaseStyle
import com.example.media.WeatherCondition
import com.example.ui.theme.ClimaTab

enum class CitiesSubTab {
    SAVED,
    MAP,
    SEARCH,
}

enum class AlertsSubTab {
    ACTIVE,
    RECENT,
}

enum class WeatherMetricType(val titlePt: String) {
    WIND("Vento"),
    PRESSURE("Pressão atmosférica"),
    PRECIPITATION("Precipitação"),
    UV("Índice ultravioleta"),
    HUMIDITY("Umidade do ar"),
    VISIBILITY("Visibilidade"),
}

sealed interface ClimaActiveModal {
    data object None : ClimaActiveModal
    data class HourDetail(val index: Int, val point: HourlyForecastPoint) : ClimaActiveModal
    data class DayDetail(val index: Int, val point: DailyForecastPoint) : ClimaActiveModal
    data class MetricDetail(val metric: WeatherMetricType) : ClimaActiveModal
    data object PressureInfo : ClimaActiveModal
}

@Immutable
data class SavedLocationWeatherSummary(
    val temperatureC: Double?,
    val descriptionPt: String,
    val condition: WeatherCondition,
    val isFromCache: Boolean,
)

@Immutable
data class ClimaUiState(
    val activeTab: ClimaTab = ClimaTab.HOME,
    val preferences: UserPreferences = UserPreferences(),
    val isOnline: Boolean = true,
    val isRefreshing: Boolean = false,
    val isRefreshingCities: Boolean = false,
    val savedLocations: List<SavedLocation> = emptyList(),
    val activeLocation: SavedLocation? = null,
    val savedLocationSummaries: Map<Long, SavedLocationWeatherSummary> = emptyMap(),
    // Forecast state
    val forecast: WeatherForecastBundle? = null,
    val forecastOrigin: DataSourceOrigin? = null,
    val forecastFetchedAtMillis: Long? = null,
    val forecastErrorMessage: String? = null,
    val forecastStaleNotice: String? = null,
    // Air quality state
    val airQuality: AirQualitySnapshot? = null,
    val airQualityOrigin: DataSourceOrigin? = null,
    val airQualityErrorMessage: String? = null,
    // Radar state
    val radarTimeline: RadarTimelineSnapshot? = null,
    val radarOrigin: DataSourceOrigin? = null,
    val radarErrorMessage: String? = null,
    val radarSelectedFrameIndex: Int = 0,
    val isRadarPlaying: Boolean = false,
    val radarLayer: RadarBaseStyle = RadarBaseStyle.PRECIPITATION_MAP,
    val radarLayerMenuExpanded: Boolean = false,
    val radarZoom: Double = 6.0,
    // Home UI interaction state
    val daysExpanded: Boolean = false,
    // Cities UI state
    val citiesSubTab: CitiesSubTab = CitiesSubTab.SAVED,
    val citySearchQuery: String = "",
    val citySearchResults: List<CitySearchResult> = emptyList(),
    val isSearchingCities: Boolean = false,
    val citySearchErrorMessage: String? = null,
    val customPointNameInput: String = "",
    val cityMapState: CityMapUiState = CityMapUiState(),
    // Alerts UI state
    val alertsSubTab: AlertsSubTab = AlertsSubTab.ACTIVE,
    val alerts: List<InAppWeatherAlert> = emptyList(),
    // Modals & transient feedback
    val activeModal: ClimaActiveModal = ClimaActiveModal.None,
    val toastMessage: String? = null,
) {
    val activeWeatherCondition: WeatherCondition
        get() = forecast?.current?.visualCondition ?: WeatherCondition.SOL_NUVENS

    val activeAlerts: List<InAppWeatherAlert>
        get() = alerts.filter { it.isActive }

    val recentAlerts: List<InAppWeatherAlert>
        get() = alerts.filter { !it.isActive }

    val currentRadarFrameIndexSafe: Int
        get() {
            val frames = radarTimeline?.frames.orEmpty()
            if (frames.isEmpty()) return 0
            return radarSelectedFrameIndex.coerceIn(0, frames.lastIndex)
        }

    val activeLocationName: String
        get() = activeLocation?.name ?: "São Paulo"

    val activeLocationSubtitle: String
        get() = activeLocation?.displaySubtitle().orEmpty()

    val activeLocationCoordinate: CityMapCoordinate
        get() = activeLocation?.toCityMapCoordinate() ?: CityMapCoordinate(-23.5505, -46.6333)
}
