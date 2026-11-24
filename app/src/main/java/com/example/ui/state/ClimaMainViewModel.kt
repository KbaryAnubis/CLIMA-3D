package com.example.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ClimaDataContainer
import com.example.data.local.preferences.AppThemeMode
import com.example.data.model.CachePolicy
import com.example.data.model.CitySearchResult
import com.example.data.model.DataSourceOrigin
import com.example.data.model.DailyForecastPoint
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.PrecipitationUnit
import com.example.data.model.PressureUnit
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.SavedLocation
import com.example.data.model.TemperatureUnit
import com.example.data.model.TimeFormatPreference
import com.example.data.model.VisibilityUnit
import com.example.data.model.WeatherResult
import com.example.data.model.WindSpeedUnit
import com.example.maps.CityMapCoordinate
import com.example.maps.CityMapUiState
import com.example.maps.OpenStreetMapTileConfig
import com.example.maps.RadarBaseStyle
import com.example.ui.theme.ClimaTab
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Primary MVVM ViewModel coordinating Clima 3D's complete V53 native UI with
 * the Etapa 3A meteorological repositories (Open-Meteo, RainViewer, Room, DataStore).
 */
class ClimaMainViewModel(
    private val container: ClimaDataContainer,
    private val autoBootstrapOnInit: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClimaUiState())
    val uiState: StateFlow<ClimaUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var radarPlaybackJob: Job? = null
    private var toastJob: Job? = null
    private var initialLocationSeeded = false
    private var lastLoadedLocationId: Long? = null

    init {
        observePreferences()
        observeConnectivity()
        observeSavedLocations()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            container.preferencesRepository.preferencesFlow.collect { prefs ->
                _uiState.update { it.copy(preferences = prefs) }
            }
        }
    }

    private fun observeConnectivity() {
        viewModelScope.launch {
            container.connectivityObserver
                .observeOnlineStatus()
                .distinctUntilChanged()
                .collect { online ->
                    val previouslyOffline = !_uiState.value.isOnline
                    _uiState.update { it.copy(isOnline = online) }
                    if (online && previouslyOffline && _uiState.value.preferences.autoRefreshOnOpen) {
                        refreshActiveLocation(policy = CachePolicy.NetworkFirst)
                    }
                }
        }
    }

    private fun observeSavedLocations() {
        viewModelScope.launch {
            container.cityLocationRepository.savedLocations.collect { locations ->
                if (locations.isEmpty() && !initialLocationSeeded && autoBootstrapOnInit) {
                    initialLocationSeeded = true
                    seedDefaultLocationIfNeeded()
                    return@collect
                }
                initialLocationSeeded = true

                val preferredId = _uiState.value.preferences.selectedLocationId
                val active =
                    locations.firstOrNull { it.id == preferredId }
                        ?: locations.firstOrNull { it.isPrimary }
                        ?: locations.firstOrNull()

                val markers = container.cityLocationRepository.toMapMarkers(locations)
                _uiState.update { current ->
                    val updatedMapState =
                        current.cityMapState
                            .withSavedMarkers(markers)
                            .let { mapState ->
                                if (active != null && lastLoadedLocationId != active.id) {
                                    mapState.selectMarkerAt(
                                        coordinate = active.toCityMapCoordinate(),
                                        label = active.name,
                                        moveCameraToMarker = true,
                                    )
                                } else {
                                    mapState
                                }
                            }
                    current.copy(
                        savedLocations = locations,
                        activeLocation = active,
                        cityMapState = updatedMapState,
                    )
                }

                hydrateCachedSummariesForSavedLocations(locations)

                if (active != null && lastLoadedLocationId != active.id) {
                    lastLoadedLocationId = active.id
                    if (autoBootstrapOnInit) {
                        val policy =
                            if (_uiState.value.preferences.autoRefreshOnOpen) {
                                CachePolicy.NetworkFirst
                            } else {
                                CachePolicy.CacheIfFresh()
                            }
                        refreshActiveLocation(policy = policy)
                    }
                } else if (active == null) {
                    lastLoadedLocationId = null
                    _uiState.update {
                        it.copy(
                            forecast = null,
                            airQuality = null,
                            alerts = emptyList(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun seedDefaultLocationIfNeeded() {
        val current = container.cityLocationRepository.getSavedLocations()
        if (current.isEmpty()) {
            val saved =
                container.cityLocationRepository.saveCityFromSearch(
                    result =
                        CitySearchResult(
                            providerId = DEFAULT_SAO_PAULO_PROVIDER_ID,
                            name = "São Paulo",
                            admin1 = "São Paulo",
                            admin2 = null,
                            country = "Brasil",
                            countryCode = "BR",
                            latitude = -23.5505,
                            longitude = -46.6333,
                            elevationMeters = 760.0,
                            timezoneId = "America/Sao_Paulo",
                            population = 12325232,
                        ),
                    setAsPrimary = true,
                )
            container.preferencesRepository.setSelectedLocationId(saved.id)
        }
    }

    private fun hydrateCachedSummariesForSavedLocations(locations: List<SavedLocation>) {
        viewModelScope.launch {
            val summaries = mutableMapOf<Long, SavedLocationWeatherSummary>()
            locations.forEach { loc ->
                val cached =
                    container.weatherRepository.getForecast(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        policy = CachePolicy.CacheOnly,
                    )
                if (cached is WeatherResult.Success) {
                    val bundle = cached.data
                    summaries[loc.id] =
                        SavedLocationWeatherSummary(
                            temperatureC = bundle.current.temperatureC,
                            descriptionPt = bundle.current.weatherDescriptionPt,
                            condition = bundle.current.visualCondition,
                            isFromCache = true,
                        )
                }
            }
            if (summaries.isNotEmpty()) {
                _uiState.update { state ->
                    state.copy(savedLocationSummaries = state.savedLocationSummaries + summaries)
                }
            }
        }
    }

    /**
     * Refreshes forecast, air quality, and radar timeline for the active location concurrently.
     */
    fun refreshActiveLocation(policy: CachePolicy = CachePolicy.NetworkFirst) {
        val location = _uiState.value.activeLocation ?: return
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        forecastErrorMessage = null,
                        radarErrorMessage = null,
                    )
                }

                val prefs = _uiState.value.preferences
                val report =
                    container.refreshLocationOnAppOpen(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        displayTimezoneId = location.timezoneId,
                        displayUtcOffsetSeconds = _uiState.value.forecast?.utcOffsetSeconds ?: 0,
                        timeFormat = prefs.timeFormat,
                        policy = policy,
                    )

                _uiState.update { state ->
                    val newForecast =
                        when (val res = report.forecast) {
                            is WeatherResult.Success -> res.data
                            is WeatherResult.Failure -> state.forecast
                        }
                    val newForecastOrigin =
                        when (val res = report.forecast) {
                            is WeatherResult.Success -> res.origin
                            is WeatherResult.Failure -> state.forecastOrigin
                        }
                    val newForecastFetchedAt =
                        when (val res = report.forecast) {
                            is WeatherResult.Success -> res.fetchedAtEpochMillis
                            is WeatherResult.Failure -> state.forecastFetchedAtMillis
                        }
                    val newForecastError =
                        when (val res = report.forecast) {
                            is WeatherResult.Success -> null
                            is WeatherResult.Failure -> res.error.userMessagePt
                        }
                    val newForecastStaleNotice =
                        when (val res = report.forecast) {
                            is WeatherResult.Success -> res.staleReason?.userMessagePt
                            is WeatherResult.Failure -> null
                        }

                    val newAirQuality =
                        when (val res = report.airQuality) {
                            is WeatherResult.Success -> res.data
                            is WeatherResult.Failure -> state.airQuality
                        }
                    val newAirQualityOrigin =
                        when (val res = report.airQuality) {
                            is WeatherResult.Success -> res.origin
                            is WeatherResult.Failure -> state.airQualityOrigin
                        }
                    val newAirQualityError =
                        when (val res = report.airQuality) {
                            is WeatherResult.Success -> null
                            is WeatherResult.Failure -> res.error.userMessagePt
                        }

                    val newRadar =
                        when (val res = report.radar) {
                            is WeatherResult.Success -> res.data
                            is WeatherResult.Failure -> state.radarTimeline
                        }
                    val newRadarOrigin =
                        when (val res = report.radar) {
                            is WeatherResult.Success -> res.origin
                            is WeatherResult.Failure -> state.radarOrigin
                        }
                    val newRadarError =
                        when (val res = report.radar) {
                            is WeatherResult.Success -> null
                            is WeatherResult.Failure -> res.error.userMessagePt
                        }

                    val evaluatedAlerts =
                        InAppWeatherAlertEngine.evaluateAlerts(
                            locationName = location.name,
                            forecast = newForecast,
                            airQuality = newAirQuality,
                        )

                    val updatedSummaries =
                        if (newForecast != null) {
                            state.savedLocationSummaries +
                                (location.id to
                                    SavedLocationWeatherSummary(
                                        temperatureC = newForecast.current.temperatureC,
                                        descriptionPt = newForecast.current.weatherDescriptionPt,
                                        condition = newForecast.current.visualCondition,
                                        isFromCache = newForecastOrigin == DataSourceOrigin.LOCAL_CACHE,
                                    ))
                        } else {
                            state.savedLocationSummaries
                        }

                    val latestFrameIndex =
                        if (newRadar != null && newRadar.frames.isNotEmpty()) {
                            newRadar.frames.lastIndex
                        } else {
                            0
                        }

                    state.copy(
                        isRefreshing = false,
                        forecast = newForecast,
                        forecastOrigin = newForecastOrigin,
                        forecastFetchedAtMillis = newForecastFetchedAt,
                        forecastErrorMessage = newForecastError,
                        forecastStaleNotice = newForecastStaleNotice,
                        airQuality = newAirQuality,
                        airQualityOrigin = newAirQualityOrigin,
                        airQualityErrorMessage = newAirQualityError,
                        radarTimeline = newRadar,
                        radarOrigin = newRadarOrigin,
                        radarErrorMessage = newRadarError,
                        radarSelectedFrameIndex = latestFrameIndex,
                        alerts = evaluatedAlerts,
                        savedLocationSummaries = updatedSummaries,
                    )
                }
            }
    }

    fun onAppResumeRefresh() {
        if (_uiState.value.preferences.autoRefreshOnOpen && _uiState.value.activeLocation != null) {
            refreshActiveLocation(policy = CachePolicy.NetworkFirst)
        }
    }

    // --- Navigation & Back Handling ---

    fun selectTab(tab: ClimaTab) {
        if (tab != ClimaTab.RADAR) {
            stopRadarPlayback()
        }
        _uiState.update {
            it.copy(
                activeTab = tab,
                radarLayerMenuExpanded = false,
            )
        }
    }

    fun navigateBackFromSecondary(): Boolean {
        val current = _uiState.value
        if (current.activeModal != ClimaActiveModal.None) {
            closeModal()
            return true
        }
        if (current.activeTab == ClimaTab.ALERTS) {
            selectTab(ClimaTab.HOME)
            return true
        }
        return false
    }

    // --- Home Interactions ---

    fun toggleDaysExpanded() {
        _uiState.update { it.copy(daysExpanded = !it.daysExpanded) }
    }

    fun openHourModal(index: Int, point: HourlyForecastPoint) {
        _uiState.update { it.copy(activeModal = ClimaActiveModal.HourDetail(index, point)) }
    }

    fun openDayModal(index: Int, point: DailyForecastPoint) {
        _uiState.update { it.copy(activeModal = ClimaActiveModal.DayDetail(index, point)) }
    }

    fun openMetricModal(metric: WeatherMetricType) {
        _uiState.update { it.copy(activeModal = ClimaActiveModal.MetricDetail(metric)) }
    }

    fun openPressureInfoModal() {
        _uiState.update { it.copy(activeModal = ClimaActiveModal.PressureInfo) }
    }

    fun closeModal() {
        _uiState.update { it.copy(activeModal = ClimaActiveModal.None) }
    }

    // --- Radar Controls ---

    fun toggleRadarLayerMenu() {
        _uiState.update { it.copy(radarLayerMenuExpanded = !it.radarLayerMenuExpanded) }
    }

    fun selectRadarLayer(layer: RadarBaseStyle) {
        _uiState.update { state ->
            val maxAllowed =
                if (layer == RadarBaseStyle.PRECIPITATION_MAP) {
                    (state.radarTimeline?.maxSupportedZoom ?: RadarTimelineSnapshot.PUBLIC_MAX_ZOOM).toDouble()
                } else {
                    OpenStreetMapTileConfig.MAX_ZOOM
                }
            state.copy(
                radarLayer = layer,
                radarLayerMenuExpanded = false,
                radarZoom = state.radarZoom.coerceAtMost(maxAllowed),
            )
        }
    }

    fun zoomRadarIn() {
        _uiState.update { state ->
            val maxAllowed =
                if (state.radarLayer == RadarBaseStyle.PRECIPITATION_MAP) {
                    (state.radarTimeline?.maxSupportedZoom ?: RadarTimelineSnapshot.PUBLIC_MAX_ZOOM).toDouble()
                } else {
                    OpenStreetMapTileConfig.MAX_ZOOM
                }
            state.copy(radarZoom = (state.radarZoom + 1.0).coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, maxAllowed))
        }
    }

    fun zoomRadarOut() {
        _uiState.update { state ->
            state.copy(radarZoom = (state.radarZoom - 1.0).coerceIn(OpenStreetMapTileConfig.MIN_ZOOM, OpenStreetMapTileConfig.MAX_ZOOM))
        }
    }

    fun selectRadarFrame(index: Int) {
        val frames = _uiState.value.radarTimeline?.frames.orEmpty()
        if (frames.isEmpty()) return
        _uiState.update { it.copy(radarSelectedFrameIndex = index.coerceIn(0, frames.lastIndex)) }
    }

    fun toggleRadarPlayback() {
        val frames = _uiState.value.radarTimeline?.frames.orEmpty()
        if (frames.size <= 1) {
            showToast("Sem quadros suficientes do RainViewer para animar.")
            return
        }
        if (_uiState.value.isRadarPlaying) {
            stopRadarPlayback()
            return
        }
        _uiState.update { it.copy(isRadarPlaying = true) }
        radarPlaybackJob?.cancel()
        radarPlaybackJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(RADAR_FRAME_INTERVAL_MS)
                    val currentFrames = _uiState.value.radarTimeline?.frames.orEmpty()
                    if (currentFrames.size <= 1) {
                        stopRadarPlayback()
                        break
                    }
                    _uiState.update { state ->
                        val nextIndex = (state.currentRadarFrameIndexSafe + 1) % currentFrames.size
                        state.copy(radarSelectedFrameIndex = nextIndex)
                    }
                }
            }
    }

    fun stopRadarPlayback() {
        radarPlaybackJob?.cancel()
        radarPlaybackJob = null
        _uiState.update { it.copy(isRadarPlaying = false) }
    }

    // --- Cities & Map Management ---

    fun selectCitiesSubTab(subTab: CitiesSubTab) {
        _uiState.update { it.copy(citiesSubTab = subTab) }
    }

    fun updateCityMapState(newState: CityMapUiState) {
        val previousCurrent = _uiState.value.cityMapState.currentUserLocation
        _uiState.update { it.copy(cityMapState = newState) }

        val updatedCurrent = newState.currentUserLocation
        if (updatedCurrent != null && updatedCurrent != previousCurrent) {
            saveGpsLocation(updatedCurrent)
        }
    }

    private fun saveGpsLocation(coordinate: CityMapCoordinate) {
        viewModelScope.launch {
            val saved =
                container.cityLocationRepository.upsertCurrentLocation(
                    coordinate = coordinate,
                    name = "Minha localização",
                    setAsPrimary = true,
                )
            container.preferencesRepository.setSelectedLocationId(saved.id)
            showToast("Localização atual salva e ativada")
        }
    }

    fun updateCustomPointNameInput(name: String) {
        _uiState.update { it.copy(customPointNameInput = name) }
    }

    fun saveSelectedMapPoint() {
        val state = _uiState.value
        val coord = state.cityMapState.selectedMarker.coordinate
        val name = state.customPointNameInput.trim().ifEmpty { "Ponto ${coord.formatted()}" }
        viewModelScope.launch {
            val saved =
                container.cityLocationRepository.saveCustomMapPoint(
                    label = name,
                    coordinate = coord,
                    setAsPrimary = true,
                )
            container.preferencesRepository.setSelectedLocationId(saved.id)
            _uiState.update { it.copy(customPointNameInput = "") }
            showToast("Ponto salvo: ${saved.name}")
        }
    }

    fun updateCitySearchQuery(query: String) {
        _uiState.update { it.copy(citySearchQuery = query, citySearchErrorMessage = null) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _uiState.update { it.copy(citySearchResults = emptyList(), isSearchingCities = false) }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(280L)
                executeCitySearch(trimmed)
            }
    }

    fun submitCitySearch() {
        val trimmed = _uiState.value.citySearchQuery.trim()
        if (trimmed.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { executeCitySearch(trimmed) }
    }

    private suspend fun executeCitySearch(query: String) {
        _uiState.update { it.copy(isSearchingCities = true, citySearchErrorMessage = null) }
        when (val result = container.cityLocationRepository.searchCities(query = query)) {
            is WeatherResult.Success -> {
                _uiState.update {
                    it.copy(
                        isSearchingCities = false,
                        citySearchResults = result.data,
                        citySearchErrorMessage = null,
                    )
                }
            }

            is WeatherResult.Failure -> {
                _uiState.update {
                    it.copy(
                        isSearchingCities = false,
                        citySearchResults = emptyList(),
                        citySearchErrorMessage = result.error.userMessagePt,
                    )
                }
            }
        }
    }

    fun addCityFromSearch(result: CitySearchResult) {
        viewModelScope.launch {
            val saved =
                container.cityLocationRepository.saveCityFromSearch(
                    result = result,
                    setAsPrimary = true,
                )
            container.preferencesRepository.setSelectedLocationId(saved.id)
            _uiState.update {
                it.copy(
                    citiesSubTab = CitiesSubTab.SAVED,
                    citySearchQuery = "",
                    citySearchResults = emptyList(),
                    citySearchErrorMessage = null,
                )
            }
            showToast("Cidade adicionada: ${saved.name}")
        }
    }

    fun selectSavedLocation(location: SavedLocation) {
        viewModelScope.launch {
            container.cityLocationRepository.setPrimaryLocation(location.id)
            container.preferencesRepository.setSelectedLocationId(location.id)
            lastLoadedLocationId = null
            _uiState.update { state ->
                state.copy(
                    activeLocation = location,
                    cityMapState =
                        state.cityMapState.selectMarkerAt(
                            coordinate = location.toCityMapCoordinate(),
                            label = location.name,
                            moveCameraToMarker = true,
                        ),
                    activeTab = ClimaTab.HOME,
                )
            }
            refreshActiveLocation(policy = CachePolicy.NetworkFirst)
            showToast("Localização ativa: ${location.name}")
        }
    }

    fun removeSavedLocation(location: SavedLocation) {
        viewModelScope.launch {
            container.cityLocationRepository.removeLocation(location.id)
            val remainingPrimary = container.cityLocationRepository.getPrimaryLocation()
            container.preferencesRepository.setSelectedLocationId(remainingPrimary?.id)
            showToast("Removida: ${location.name}")
        }
    }

    // --- Alerts ---

    fun selectAlertsSubTab(subTab: AlertsSubTab) {
        _uiState.update { it.copy(alertsSubTab = subTab) }
    }

    // --- Settings (DataStore) ---

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { container.preferencesRepository.setThemeMode(mode) }
    }

    fun setTemperatureUnit(unit: TemperatureUnit) {
        viewModelScope.launch { container.preferencesRepository.setTemperatureUnit(unit) }
    }

    fun setWindSpeedUnit(unit: WindSpeedUnit) {
        viewModelScope.launch { container.preferencesRepository.setWindSpeedUnit(unit) }
    }

    fun setPrecipitationUnit(unit: PrecipitationUnit) {
        viewModelScope.launch { container.preferencesRepository.setPrecipitationUnit(unit) }
    }

    fun setPressureUnit(unit: PressureUnit) {
        viewModelScope.launch { container.preferencesRepository.setPressureUnit(unit) }
    }

    fun setVisibilityUnit(unit: VisibilityUnit) {
        viewModelScope.launch { container.preferencesRepository.setVisibilityUnit(unit) }
    }

    fun setTimeFormat(format: TimeFormatPreference) {
        viewModelScope.launch { container.preferencesRepository.setTimeFormat(format) }
    }

    fun setShowPressureStatus(show: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setShowPressureStatus(show) }
    }

    fun setWidgetPreviewSize(size: String) {
        viewModelScope.launch { container.preferencesRepository.setWidgetPreviewSize(size) }
    }

    fun setAnimatedBackgroundEnabled(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setAnimatedBackgroundEnabled(enabled) }
    }

    fun setAutoRefreshOnOpen(enabled: Boolean) {
        viewModelScope.launch { container.preferencesRepository.setAutoRefreshOnOpen(enabled) }
    }

    fun resetPreferencesToDefaults() {
        viewModelScope.launch {
            container.preferencesRepository.resetToDefaults()
            showToast("Preferências restauradas para o padrão")
        }
    }

    fun showToast(message: String) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = message) }
        toastJob =
            viewModelScope.launch {
                delay(2600L)
                _uiState.update { it.copy(toastMessage = null) }
            }
    }

    override fun onCleared() {
        stopRadarPlayback()
        super.onCleared()
    }

    class Factory(
        private val container: ClimaDataContainer,
        private val autoBootstrapOnInit: Boolean = true,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ClimaMainViewModel::class.java)) {
                return ClimaMainViewModel(container, autoBootstrapOnInit) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    companion object {
        private const val RADAR_FRAME_INTERVAL_MS = 650L
        private const val DEFAULT_SAO_PAULO_PROVIDER_ID = 3448439L
    }
}
