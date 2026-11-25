package com.example.ui.state

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ClimaDataContainer
import com.example.diagnostics.ClimaGraphicsLifecycleDiagnostics
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
import com.example.maps.LocationPermissionCoordinator
import com.example.maps.LocationPermissionSnapshot
import com.example.maps.LocationPermissionState
import com.example.maps.LocationPrecisionLevel
import com.example.maps.OpenStreetMapTileConfig
import com.example.maps.RadarBaseStyle
import com.example.ui.theme.ClimaTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primary MVVM ViewModel coordinating Clima 3D's complete V53 native UI with
 * the Etapa 3A meteorological repositories (Open-Meteo, RainViewer, Room, DataStore).
 */
class ClimaMainViewModel(
    private val container: ClimaDataContainer,
    private val autoBootstrapOnInit: Boolean = true,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClimaUiState())
    val uiState: StateFlow<ClimaUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var citiesRefreshJob: Job? = null
    private var searchJob: Job? = null
    private var pickerSearchJob: Job? = null
    private var mapPickerSearchJob: Job? = null
    private var pickerActivationJob: Job? = null
    private var radarPlaybackJob: Job? = null
    private var toastJob: Job? = null
    private var initialLocationSeeded = false
    private var lastLoadedLocationId: Long? = null
    private var pickerSearchSequence: Long = 0L
    private var mapPickerSearchSequence: Long = 0L
    private var pickerGpsRequestSeq: Long = 0L
    private var lastCompletedPickerQuery: String? = null
    private var lastCompletedMapPickerQuery: String? = null

    init {
        observePreferences()
        observeConnectivity()
        observeSavedLocations()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            container.preferencesRepository.preferencesFlow.collect { prefs ->
                _uiState.update { state ->
                    val updatedSnapshot =
                        state.locationPermissionSnapshot.copy(
                            initialPromptShown = prefs.locationPermissionPromptShown || state.locationPermissionSnapshot.initialPromptShown,
                        )
                    state.copy(
                        preferences = prefs,
                        preferencesLoaded = true,
                        locationPermissionSnapshot = updatedSnapshot,
                    )
                }
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
        if (locations.isEmpty()) return
        viewModelScope.launch {
            val summaries =
                coroutineScope {
                    locations
                        .map { loc ->
                            async {
                                val cached =
                                    container.weatherRepository.getForecast(
                                        latitude = loc.latitude,
                                        longitude = loc.longitude,
                                        policy = CachePolicy.CacheOnly,
                                    )
                                if (cached is WeatherResult.Success) {
                                    val bundle = cached.data
                                    loc.id to
                                        SavedLocationWeatherSummary(
                                            temperatureC = bundle.current.temperatureC,
                                            descriptionPt = bundle.current.weatherDescriptionPt,
                                            condition = bundle.current.visualCondition,
                                            isFromCache = true,
                                        )
                                } else {
                                    null
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                        .toMap()
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
     * Reused across app open, foreground resume, and Home/Radar pull-to-refresh gestures.
     */
    fun refreshActiveLocation(
        policy: CachePolicy = CachePolicy.NetworkFirst,
        preventDuplicate: Boolean = false,
        preserveRadarPlayback: Boolean = true,
        showFeedbackOnFailure: Boolean = false,
    ) {
        if (preventDuplicate && (refreshJob?.isActive == true || _uiState.value.isRefreshing)) {
            return
        }
        val location = _uiState.value.activeLocation
        if (location == null) {
            if (showFeedbackOnFailure) {
                showToast("Nenhuma localização selecionada para atualizar.")
            }
            return
        }
        refreshJob?.cancel()
        val refreshStartMs = SystemClock.elapsedRealtime()
        val refreshReason = if (showFeedbackOnFailure) "manual_pull_refresh" else "auto_or_resume_refresh"
        ClimaGraphicsLifecycleDiagnostics.logRefreshStarted(refreshReason, location.id)
        refreshJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        isRefreshing = true,
                        forecastErrorMessage = null,
                        radarErrorMessage = null,
                    )
                }

                var outcome = "canceled"
                try {
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

                    val snapshotBeforeUpdate = _uiState.value
                    val resolvedForecast =
                        when (val res = report.forecast) {
                            is WeatherResult.Success -> res.data
                            is WeatherResult.Failure -> snapshotBeforeUpdate.forecast
                        }
                    val resolvedAirQuality =
                        when (val res = report.airQuality) {
                            is WeatherResult.Success -> res.data
                            is WeatherResult.Failure -> snapshotBeforeUpdate.airQuality
                        }
                    val evaluatedAlerts =
                        withContext(cpuDispatcher) {
                            InAppWeatherAlertEngine.evaluateAlerts(
                                locationName = location.name,
                                forecast = resolvedForecast,
                                airQuality = resolvedAirQuality,
                            )
                        }

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

                        val previousFrames = state.radarTimeline?.frames.orEmpty()
                        val previousSafeIndex = state.currentRadarFrameIndexSafe
                        val previousSelectedTimestamp = previousFrames.getOrNull(previousSafeIndex)?.timestampSeconds

                        val nextFrameIndex =
                            when {
                                newRadar == null || newRadar.frames.isEmpty() -> 0
                                report.radar is WeatherResult.Failure ->
                                    previousSafeIndex.coerceIn(0, newRadar.frames.lastIndex)
                                previousFrames.isEmpty() -> newRadar.frames.lastIndex
                                !state.isRadarPlaying && previousSafeIndex >= previousFrames.lastIndex ->
                                    newRadar.frames.lastIndex
                                else -> {
                                    val matchingIndex =
                                        previousSelectedTimestamp?.let { ts ->
                                            newRadar.frames.indexOfFirst { it.timestampSeconds == ts }
                                        } ?: -1
                                    when {
                                        matchingIndex >= 0 -> matchingIndex
                                        preserveRadarPlayback && state.isRadarPlaying -> 0
                                        else -> newRadar.frames.lastIndex
                                    }
                                }
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
                            radarSelectedFrameIndex = nextFrameIndex,
                            alerts = evaluatedAlerts,
                            savedLocationSummaries = updatedSummaries,
                        )
                    }

                    if (showFeedbackOnFailure) {
                        val forecastErr =
                            when (val res = report.forecast) {
                                is WeatherResult.Failure -> res.error.userMessagePt
                                is WeatherResult.Success -> res.staleReason?.userMessagePt
                            }
                        val radarErr =
                            when (val res = report.radar) {
                                is WeatherResult.Failure -> res.error.userMessagePt
                                is WeatherResult.Success -> res.staleReason?.userMessagePt
                            }
                        val airErr =
                            when (val res = report.airQuality) {
                                is WeatherResult.Failure -> res.error.userMessagePt
                                is WeatherResult.Success -> res.staleReason?.userMessagePt
                            }
                        val firstError = forecastErr ?: radarErr ?: airErr
                        if (firstError != null) {
                            val hasCache = _uiState.value.forecast != null || _uiState.value.radarTimeline != null
                            val feedback =
                                when {
                                    !_uiState.value.isOnline && hasCache ->
                                        "Sem conexão. Exibindo dados salvos em cache."
                                    hasCache -> "$firstError Exibindo dados em cache."
                                    else -> firstError
                                }
                            showToast(feedback)
                        }
                    }
                    outcome = "completed"
                } finally {
                    _uiState.update { it.copy(isRefreshing = false) }
                    val elapsedMs = (SystemClock.elapsedRealtime() - refreshStartMs).coerceAtLeast(0L)
                    ClimaGraphicsLifecycleDiagnostics.logRefreshFinished(
                        reason = refreshReason,
                        locationId = location.id,
                        durationMs = elapsedMs,
                        outcome = outcome,
                    )
                }
            }
    }

    /**
     * Pull-to-Refresh for Home: updates forecast, air quality, and weather data without duplicating in-flight calls.
     */
    fun onPullRefreshHome() {
        refreshActiveLocation(
            policy = CachePolicy.NetworkFirst,
            preventDuplicate = true,
            preserveRadarPlayback = true,
            showFeedbackOnFailure = true,
        )
    }

    /**
     * Pull-to-Refresh for Radar: updates RainViewer historical frames and weather summary without
     * reinitializing MapLibre or interrupting active timeline playback.
     */
    fun onPullRefreshRadar() {
        refreshActiveLocation(
            policy = CachePolicy.NetworkFirst,
            preventDuplicate = true,
            preserveRadarPlayback = true,
            showFeedbackOnFailure = true,
        )
    }

    /**
     * Pull-to-Refresh for Cities: refreshes weather summaries for all saved cities (and active location)
     * concurrently without reinitializing MapLibre, preserving cache when offline.
     */
    fun onPullRefreshCities() {
        if (citiesRefreshJob?.isActive == true || _uiState.value.isRefreshingCities) {
            return
        }
        val locations = _uiState.value.savedLocations
        if (locations.isEmpty()) {
            showToast(
                if (_uiState.value.isOnline) {
                    "Nenhuma cidade salva para atualizar."
                } else {
                    "Sem conexão com a internet."
                },
            )
            return
        }

        val citiesStartMs = SystemClock.elapsedRealtime()
        ClimaGraphicsLifecycleDiagnostics.logRefreshStarted("cities_pull_refresh", _uiState.value.activeLocation?.id)
        citiesRefreshJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isRefreshingCities = true) }
                var citiesOutcome = "canceled"
                try {
                    val activeId = _uiState.value.activeLocation?.id
                    var activeForecastResult: WeatherResult<com.example.data.model.WeatherForecastBundle>? = null
                    var firstErrorOrStale: String? = null
                    val updatedSummaries = mutableMapOf<Long, SavedLocationWeatherSummary>()

                    coroutineScope {
                        val deferredCalls =
                            locations.map { loc ->
                                loc to
                                    async {
                                        container.weatherRepository.getForecast(
                                            latitude = loc.latitude,
                                            longitude = loc.longitude,
                                            policy = CachePolicy.NetworkFirst,
                                        )
                                    }
                            }
                        deferredCalls.forEach { (loc, deferred) ->
                            val result = deferred.await()
                            if (loc.id == activeId) {
                                activeForecastResult = result
                            }
                            when (result) {
                                is WeatherResult.Success -> {
                                    updatedSummaries[loc.id] =
                                        SavedLocationWeatherSummary(
                                            temperatureC = result.data.current.temperatureC,
                                            descriptionPt = result.data.current.weatherDescriptionPt,
                                            condition = result.data.current.visualCondition,
                                            isFromCache = result.origin == DataSourceOrigin.LOCAL_CACHE,
                                        )
                                    if (firstErrorOrStale == null && result.staleReason != null) {
                                        firstErrorOrStale = result.staleReason.userMessagePt
                                    }
                                }
                                is WeatherResult.Failure -> {
                                    if (firstErrorOrStale == null) {
                                        firstErrorOrStale = result.error.userMessagePt
                                    }
                                }
                            }
                        }
                    }

                    val snapshotBeforeUpdate = _uiState.value
                    val activeLocation = snapshotBeforeUpdate.activeLocation
                    val resolvedForecast =
                        when (val activeResult = activeForecastResult) {
                            is WeatherResult.Success -> activeResult.data
                            else -> snapshotBeforeUpdate.forecast
                        }
                    val evaluatedAlerts =
                        withContext(cpuDispatcher) {
                            if (activeLocation != null && resolvedForecast != null) {
                                InAppWeatherAlertEngine.evaluateAlerts(
                                    locationName = activeLocation.name,
                                    forecast = resolvedForecast,
                                    airQuality = snapshotBeforeUpdate.airQuality,
                                )
                            } else {
                                snapshotBeforeUpdate.alerts
                            }
                        }

                    _uiState.update { state ->
                        val mergedSummaries = state.savedLocationSummaries + updatedSummaries
                        val activeResult = activeForecastResult
                        val updatedForecast =
                            when (activeResult) {
                                is WeatherResult.Success -> activeResult.data
                                else -> state.forecast
                            }
                        val updatedOrigin =
                            when (activeResult) {
                                is WeatherResult.Success -> activeResult.origin
                                else -> state.forecastOrigin
                            }
                        val updatedFetchedAt =
                            when (activeResult) {
                                is WeatherResult.Success -> activeResult.fetchedAtEpochMillis
                                else -> state.forecastFetchedAtMillis
                            }
                        val updatedStaleNotice =
                            when (activeResult) {
                                is WeatherResult.Success -> activeResult.staleReason?.userMessagePt
                                else -> state.forecastStaleNotice
                            }

                        state.copy(
                            isRefreshingCities = false,
                            savedLocationSummaries = mergedSummaries,
                            forecast = updatedForecast,
                            forecastOrigin = updatedOrigin,
                            forecastFetchedAtMillis = updatedFetchedAt,
                            forecastStaleNotice = updatedStaleNotice,
                            alerts = evaluatedAlerts,
                        )
                    }

                    val errorMessage = firstErrorOrStale
                    if (errorMessage != null) {
                        val hasCachedCities = _uiState.value.savedLocationSummaries.isNotEmpty()
                        val feedback =
                            when {
                                !_uiState.value.isOnline && hasCachedCities ->
                                    "Sem conexão. Resumos das cidades mantidos em cache."
                                hasCachedCities -> "$errorMessage Resumos em cache mantidos."
                                else -> errorMessage
                            }
                        showToast(feedback)
                    }
                    citiesOutcome = "completed"
                } finally {
                    _uiState.update { it.copy(isRefreshingCities = false) }
                    val elapsedMs = (SystemClock.elapsedRealtime() - citiesStartMs).coerceAtLeast(0L)
                    ClimaGraphicsLifecycleDiagnostics.logRefreshFinished(
                        reason = "cities_pull_refresh",
                        locationId = _uiState.value.activeLocation?.id,
                        durationMs = elapsedMs,
                        outcome = citiesOutcome,
                    )
                }
            }
    }

    fun onAppResumeRefresh() {
        if (_uiState.value.preferences.autoRefreshOnOpen && _uiState.value.activeLocation != null) {
            refreshActiveLocation(
                policy = CachePolicy.NetworkFirst,
                preventDuplicate = true,
            )
        }
    }

    // --- Navigation & Back Handling ---

    fun selectTab(tab: ClimaTab) {
        val previousTab = _uiState.value.activeTab
        if (previousTab != tab) {
            ClimaGraphicsLifecycleDiagnostics.logTabChanged(previousTab, tab)
        }
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

    fun openLocationPickerModal() {
        pickerSearchJob?.cancel()
        pickerSearchJob = null
        pickerSearchSequence++
        pickerGpsRequestSeq++
        lastCompletedPickerQuery = null
        ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "OPEN")
        _uiState.update {
            it.copy(
                activeModal = ClimaActiveModal.LocationPicker,
                locationPickerState = LocationPickerUiState(),
            )
        }
        hydrateCachedSummariesForSavedLocations(_uiState.value.savedLocations)
    }

    fun closeLocationPickerModal() {
        pickerSearchJob?.cancel()
        pickerSearchJob = null
        pickerSearchSequence++
        pickerGpsRequestSeq++
        lastCompletedPickerQuery = null
        ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("LocationPicker", "CLOSE")
        _uiState.update {
            it.copy(
                activeModal =
                    if (it.activeModal == ClimaActiveModal.LocationPicker) {
                        ClimaActiveModal.None
                    } else {
                        it.activeModal
                    },
                locationPickerState = LocationPickerUiState(),
            )
        }
    }

    fun openMapLocationPickerModal() {
        pickerSearchJob?.cancel()
        pickerSearchJob = null
        mapPickerSearchJob?.cancel()
        mapPickerSearchJob = null
        pickerSearchSequence++
        mapPickerSearchSequence++
        pickerGpsRequestSeq++
        lastCompletedPickerQuery = null
        lastCompletedMapPickerQuery = null
        ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "OPEN")
        _uiState.update { state ->
            val initialCoord = state.activeLocationCoordinate
            val initialName = state.activeLocationName
            val initialSubtitle = state.activeLocationSubtitle
            val resetMapState =
                state.cityMapState
                    .copy(
                        isLocatingUser = false,
                        locationStatusNotice = null,
                    ).selectMarkerAt(
                        coordinate = initialCoord,
                        label = initialName,
                        moveCameraToMarker = true,
                    )
            state.copy(
                activeModal = ClimaActiveModal.MapLocationPicker,
                locationPickerState = LocationPickerUiState(),
                mapLocationPickerState =
                    MapLocationPickerUiState(
                        selectedPlaceName = initialName,
                        selectedRegionSubtitle = initialSubtitle,
                    ),
                customPointNameInput = "",
                cityMapState = resetMapState,
            )
        }
    }

    fun closeMapLocationPickerModal() {
        mapPickerSearchJob?.cancel()
        mapPickerSearchJob = null
        mapPickerSearchSequence++
        pickerGpsRequestSeq++
        lastCompletedMapPickerQuery = null
        ClimaGraphicsLifecycleDiagnostics.logModalLifecycle("MapLocationPicker", "CLOSE")
        _uiState.update { state ->
            val activeCoord = state.activeLocationCoordinate
            val activeName = state.activeLocationName
            state.copy(
                activeModal =
                    if (state.activeModal == ClimaActiveModal.MapLocationPicker) {
                        ClimaActiveModal.None
                    } else {
                        state.activeModal
                    },
                mapLocationPickerState = MapLocationPickerUiState(),
                customPointNameInput = "",
                cityMapState =
                    state.cityMapState
                        .copy(
                            isLocatingUser = false,
                            locationStatusNotice = null,
                        ).selectMarkerAt(
                            coordinate = activeCoord,
                            label = activeName,
                            moveCameraToMarker = true,
                        ),
            )
        }
    }

    fun closeModal() {
        when (_uiState.value.activeModal) {
            ClimaActiveModal.LocationPicker -> {
                closeLocationPickerModal()
                return
            }
            ClimaActiveModal.MapLocationPicker -> {
                closeMapLocationPickerModal()
                return
            }
            else -> {
                _uiState.update { it.copy(activeModal = ClimaActiveModal.None) }
            }
        }
    }

    // --- On-Demand Map Location Picker Actions ---

    fun updateMapPickerSearchQuery(query: String) {
        val trimmed = query.trim()
        val currentMapPicker = _uiState.value.mapLocationPickerState
        if (
            trimmed.length >= 2 &&
            trimmed == lastCompletedMapPickerQuery &&
            !currentMapPicker.isSearching &&
            currentMapPicker.hasSearchedCurrentQuery &&
            currentMapPicker.searchErrorMessage == null
        ) {
            _uiState.update { state ->
                state.copy(mapLocationPickerState = state.mapLocationPickerState.copy(searchQuery = query))
            }
            return
        }

        mapPickerSearchJob?.cancel()
        mapPickerSearchJob = null
        val seq = ++mapPickerSearchSequence
        if (trimmed != lastCompletedMapPickerQuery) {
            lastCompletedMapPickerQuery = null
        }

        if (trimmed.length < 2) {
            _uiState.update { state ->
                state.copy(
                    mapLocationPickerState =
                        state.mapLocationPickerState.copy(
                            searchQuery = query,
                            searchResults = emptyList(),
                            isSearching = false,
                            searchErrorMessage = null,
                            hasSearchedCurrentQuery = false,
                        ),
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                mapLocationPickerState =
                    state.mapLocationPickerState.copy(
                        searchQuery = query,
                        searchErrorMessage = null,
                        hasSearchedCurrentQuery = false,
                    ),
            )
        }

        mapPickerSearchJob =
            viewModelScope.launch {
                delay(PICKER_SEARCH_DEBOUNCE_MS)
                executeMapPickerCitySearch(trimmed, seq)
            }
    }

    fun submitMapPickerSearch() {
        val currentMapPicker = _uiState.value.mapLocationPickerState
        val trimmed = currentMapPicker.searchQuery.trim()
        if (trimmed.length < 2) return
        if (
            trimmed == lastCompletedMapPickerQuery &&
            !currentMapPicker.isSearching &&
            currentMapPicker.hasSearchedCurrentQuery &&
            currentMapPicker.searchErrorMessage == null
        ) {
            return
        }
        mapPickerSearchJob?.cancel()
        val seq = ++mapPickerSearchSequence
        mapPickerSearchJob = viewModelScope.launch { executeMapPickerCitySearch(trimmed, seq) }
    }

    private suspend fun executeMapPickerCitySearch(
        query: String,
        requestSeq: Long,
    ) {
        if (!currentCoroutineContext().isActive || requestSeq != mapPickerSearchSequence) return
        _uiState.update { state ->
            state.copy(
                mapLocationPickerState =
                    state.mapLocationPickerState.copy(
                        isSearching = true,
                        searchErrorMessage = null,
                    ),
            )
        }
        when (val result = container.cityLocationRepository.searchCities(query = query)) {
            is WeatherResult.Success -> {
                if (!currentCoroutineContext().isActive ||
                    requestSeq != mapPickerSearchSequence ||
                    _uiState.value.activeModal != ClimaActiveModal.MapLocationPicker
                ) {
                    return
                }
                lastCompletedMapPickerQuery = query
                _uiState.update { state ->
                    state.copy(
                        mapLocationPickerState =
                            state.mapLocationPickerState.copy(
                                isSearching = false,
                                searchResults = result.data,
                                searchErrorMessage = null,
                                hasSearchedCurrentQuery = true,
                            ),
                    )
                }
            }
            is WeatherResult.Failure -> {
                if (!currentCoroutineContext().isActive ||
                    requestSeq != mapPickerSearchSequence ||
                    _uiState.value.activeModal != ClimaActiveModal.MapLocationPicker
                ) {
                    return
                }
                lastCompletedMapPickerQuery = null
                _uiState.update { state ->
                    state.copy(
                        mapLocationPickerState =
                            state.mapLocationPickerState.copy(
                                isSearching = false,
                                searchResults = emptyList(),
                                searchErrorMessage = result.error.userMessagePt,
                                hasSearchedCurrentQuery = true,
                            ),
                    )
                }
            }
        }
    }

    fun reopenMapPickerSearch() {
        _uiState.update { state ->
            state.copy(
                mapLocationPickerState =
                    state.mapLocationPickerState.copy(
                        isSearchExpanded = true,
                        searchErrorMessage = null,
                    ),
            )
        }
    }

    /**
     * Centers the on-demand map on a selected geocoding suggestion without immediately closing the map,
     * while collapsing the search box and suggestion list so the map stays unobstructed.
     * The user can fine-tune the position or confirm immediately via "Confirmar localização".
     */
    fun selectSearchResultInMapPicker(result: CitySearchResult) {
        mapPickerSearchJob?.cancel()
        mapPickerSearchJob = null
        mapPickerSearchSequence++
        lastCompletedMapPickerQuery = null
        val targetCoord = result.toCityMapCoordinate()
        val regionSubtitle = result.displayRegionLabel()
        _uiState.update { state ->
            val updatedMapState =
                state.cityMapState
                    .selectMarkerAt(
                        coordinate = targetCoord,
                        label = result.name,
                        moveCameraToMarker = true,
                    ).copy(
                        zoom = 12.5.coerceIn(CityMapUiState.MIN_ZOOM, CityMapUiState.MAX_ZOOM),
                        locationStatusNotice = null,
                    )
            state.copy(
                cityMapState = updatedMapState,
                customPointNameInput = result.name,
                mapLocationPickerState =
                    state.mapLocationPickerState.copy(
                        searchQuery = result.name,
                        searchResults = emptyList(),
                        isSearching = false,
                        searchErrorMessage = null,
                        hasSearchedCurrentQuery = false,
                        isSearchExpanded = false,
                        selectedSearchResult = result,
                        isGpsSelection = false,
                        selectedPlaceName = result.name,
                        selectedRegionSubtitle = regionSubtitle,
                    ),
            )
        }
    }

    /**
     * Confirms the currently selected point on the on-demand map picker, persists it in Room and DataStore,
     * updates the weather forecast for the newly chosen location, and closes the map screen (destroying MapView).
     */
    fun confirmMapLocationSelection() {
        val state = _uiState.value
        val picker = state.mapLocationPickerState
        val selectedCoord = state.cityMapState.selectedMarker.coordinate
        val customName = state.customPointNameInput.trim()

        pickerActivationJob?.cancel()
        pickerActivationJob =
            viewModelScope.launch {
                val searchCandidate = picker.selectedSearchResult
                val matchesSearchCandidate =
                    searchCandidate != null &&
                        kotlin.math.abs(searchCandidate.latitude - selectedCoord.latitude) <= 0.0005 &&
                        kotlin.math.abs(searchCandidate.longitude - selectedCoord.longitude) <= 0.0005 &&
                        (customName.isEmpty() || customName.equals(searchCandidate.name, ignoreCase = true))

                val saved =
                    when {
                        matchesSearchCandidate && searchCandidate != null -> {
                            container.cityLocationRepository.saveCityFromSearch(
                                result = searchCandidate,
                                setAsPrimary = true,
                            )
                        }
                        picker.isGpsSelection &&
                            (customName.isEmpty() || customName.equals("Minha localização", ignoreCase = true)) -> {
                            container.cityLocationRepository.upsertCurrentLocation(
                                coordinate = selectedCoord,
                                name = "Minha localização",
                                setAsPrimary = true,
                            )
                        }
                        else -> {
                            val label =
                                customName.ifEmpty {
                                    picker.selectedPlaceName
                                        .takeIf { it.isNotBlank() && !it.startsWith("Ponto ") }
                                        ?: "Ponto ${selectedCoord.formatted()}"
                                }
                            container.cityLocationRepository.saveCustomMapPoint(
                                label = label,
                                coordinate = selectedCoord,
                                setAsPrimary = true,
                            )
                        }
                    }

                activateSavedLocationOnHome(
                    location = saved,
                    toastMessage = "Localização confirmada: ${saved.name}",
                    gpsCoordinateForMapState = if (picker.isGpsSelection) selectedCoord else null,
                )
            }
    }

    // --- Home Quick Location Picker Actions ---

    fun updateLocationPickerSearchQuery(query: String) {
        val trimmed = query.trim()
        val currentPicker = _uiState.value.locationPickerState
        if (
            trimmed.length >= 2 &&
            trimmed == lastCompletedPickerQuery &&
            !currentPicker.isSearching &&
            currentPicker.hasSearchedCurrentQuery &&
            currentPicker.searchErrorMessage == null
        ) {
            _uiState.update { state ->
                state.copy(locationPickerState = state.locationPickerState.copy(searchQuery = query))
            }
            return
        }

        pickerSearchJob?.cancel()
        pickerSearchJob = null
        val seq = ++pickerSearchSequence
        if (trimmed != lastCompletedPickerQuery) {
            lastCompletedPickerQuery = null
        }

        if (trimmed.length < 2) {
            _uiState.update { state ->
                state.copy(
                    locationPickerState =
                        state.locationPickerState.copy(
                            searchQuery = query,
                            searchResults = emptyList(),
                            isSearching = false,
                            searchErrorMessage = null,
                            hasSearchedCurrentQuery = false,
                        ),
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                locationPickerState =
                    state.locationPickerState.copy(
                        searchQuery = query,
                        searchErrorMessage = null,
                        hasSearchedCurrentQuery = false,
                    ),
            )
        }

        pickerSearchJob =
            viewModelScope.launch {
                delay(PICKER_SEARCH_DEBOUNCE_MS)
                executePickerCitySearch(trimmed, seq)
            }
    }

    fun submitLocationPickerSearch() {
        val currentPicker = _uiState.value.locationPickerState
        val trimmed = currentPicker.searchQuery.trim()
        if (trimmed.length < 2) return
        if (
            trimmed == lastCompletedPickerQuery &&
            !currentPicker.isSearching &&
            currentPicker.hasSearchedCurrentQuery &&
            currentPicker.searchErrorMessage == null
        ) {
            return
        }
        pickerSearchJob?.cancel()
        val seq = ++pickerSearchSequence
        pickerSearchJob = viewModelScope.launch { executePickerCitySearch(trimmed, seq) }
    }

    private suspend fun executePickerCitySearch(
        query: String,
        requestSeq: Long,
    ) {
        if (!currentCoroutineContext().isActive || requestSeq != pickerSearchSequence) return
        _uiState.update { state ->
            state.copy(
                locationPickerState =
                    state.locationPickerState.copy(
                        isSearching = true,
                        searchErrorMessage = null,
                    ),
            )
        }
        when (val result = container.cityLocationRepository.searchCities(query = query)) {
            is WeatherResult.Success -> {
                if (!currentCoroutineContext().isActive ||
                    requestSeq != pickerSearchSequence ||
                    _uiState.value.activeModal != ClimaActiveModal.LocationPicker
                ) {
                    return
                }
                lastCompletedPickerQuery = query
                _uiState.update { state ->
                    state.copy(
                        locationPickerState =
                            state.locationPickerState.copy(
                                isSearching = false,
                                searchResults = result.data,
                                searchErrorMessage = null,
                                hasSearchedCurrentQuery = true,
                            ),
                    )
                }
            }

            is WeatherResult.Failure -> {
                if (!currentCoroutineContext().isActive ||
                    requestSeq != pickerSearchSequence ||
                    _uiState.value.activeModal != ClimaActiveModal.LocationPicker
                ) {
                    return
                }
                lastCompletedPickerQuery = null
                _uiState.update { state ->
                    state.copy(
                        locationPickerState =
                            state.locationPickerState.copy(
                                isSearching = false,
                                searchResults = emptyList(),
                                searchErrorMessage = result.error.userMessagePt,
                                hasSearchedCurrentQuery = true,
                            ),
                    )
                }
            }
        }
    }

    fun beginLocationPickerGpsLookup(): Long {
        val seq = ++pickerGpsRequestSeq
        _uiState.update { state ->
            state.copy(
                locationPickerState =
                    state.locationPickerState.copy(
                        isLocatingUser = true,
                        gpsErrorMessage = null,
                        pendingGpsActionAfterPermission = false,
                    ),
            )
        }
        return seq
    }

    fun markPendingPickerGpsAfterPermission(pending: Boolean) {
        _uiState.update { state ->
            state.copy(
                locationPickerState =
                    state.locationPickerState.copy(
                        pendingGpsActionAfterPermission = pending,
                        gpsErrorMessage = if (pending) null else state.locationPickerState.gpsErrorMessage,
                    ),
            )
        }
    }

    fun finishLocationPickerGpsLookupWithError(
        message: String,
        requestSeq: Long? = null,
    ) {
        if (requestSeq != null && requestSeq != pickerGpsRequestSeq) return
        _uiState.update { state ->
            state.copy(
                locationPickerState =
                    state.locationPickerState.copy(
                        isLocatingUser = false,
                        gpsErrorMessage = message,
                        pendingGpsActionAfterPermission = false,
                    ),
            )
        }
    }

    fun activateCurrentGpsCoordinatesFromPicker(
        coordinate: CityMapCoordinate,
        requestSeq: Long? = null,
    ) {
        if (requestSeq != null && requestSeq != pickerGpsRequestSeq) return
        if (!LocationPermissionCoordinator.isValidCoordinate(coordinate.latitude, coordinate.longitude)) {
            finishLocationPickerGpsLookupWithError(
                message = "Coordenadas de localização inválidas recebidas do aparelho.",
                requestSeq = requestSeq,
            )
            return
        }
        pickerActivationJob?.cancel()
        pickerActivationJob =
            viewModelScope.launch {
                val saved =
                    container.cityLocationRepository.upsertCurrentLocation(
                        coordinate = coordinate,
                        name = "Minha localização",
                        setAsPrimary = true,
                    )
                activateSavedLocationOnHome(
                    location = saved,
                    toastMessage = "Localização atual ativada",
                    gpsCoordinateForMapState = coordinate,
                )
            }
    }

    fun selectSavedLocationFromPicker(location: SavedLocation) {
        pickerActivationJob?.cancel()
        pickerActivationJob =
            viewModelScope.launch {
                container.cityLocationRepository.setPrimaryLocation(location.id)
                activateSavedLocationOnHome(
                    location = location.copy(isPrimary = true),
                    toastMessage = "Localização ativa: ${location.name}",
                )
            }
    }

    fun selectSearchResultFromPicker(result: CitySearchResult) {
        pickerActivationJob?.cancel()
        pickerActivationJob =
            viewModelScope.launch {
                val saved =
                    container.cityLocationRepository.saveCityFromSearch(
                        result = result,
                        setAsPrimary = true,
                    )
                activateSavedLocationOnHome(
                    location = saved,
                    toastMessage = "Localização ativa: ${saved.name}",
                )
            }
    }

    private suspend fun activateSavedLocationOnHome(
        location: SavedLocation,
        toastMessage: String,
        gpsCoordinateForMapState: CityMapCoordinate? = null,
    ) {
        pickerSearchJob?.cancel()
        pickerSearchJob = null
        mapPickerSearchJob?.cancel()
        mapPickerSearchJob = null
        pickerSearchSequence++
        mapPickerSearchSequence++
        pickerGpsRequestSeq++
        lastCompletedPickerQuery = null
        lastCompletedMapPickerQuery = null

        lastLoadedLocationId = location.id
        container.preferencesRepository.setSelectedLocationId(location.id)

        // Consult Room cache immediately so cached forecast/air quality renders right away (including offline)
        val cachedForecast =
            container.weatherRepository.getForecast(
                latitude = location.latitude,
                longitude = location.longitude,
                policy = CachePolicy.CacheOnly,
            ) as? WeatherResult.Success
        val cachedAir =
            container.airQualityRepository.getAirQuality(
                latitude = location.latitude,
                longitude = location.longitude,
                policy = CachePolicy.CacheOnly,
            ) as? WeatherResult.Success
        val cachedAlerts =
            if (cachedForecast != null) {
                withContext(cpuDispatcher) {
                    InAppWeatherAlertEngine.evaluateAlerts(
                        locationName = location.name,
                        forecast = cachedForecast.data,
                        airQuality = cachedAir?.data,
                    )
                }
            } else {
                emptyList()
            }

        ClimaGraphicsLifecycleDiagnostics.logModalLifecycle(
            modalName = "LocationPicker",
            action = "SELECTED locationId=${location.id}",
        )

        _uiState.update { state ->
            val updatedMapState =
                (if (gpsCoordinateForMapState != null) {
                    state.cityMapState.withCurrentLocation(
                        coordinate = gpsCoordinateForMapState,
                        label = location.name,
                        precision = state.locationPermissionSnapshot.precisionLevel,
                    )
                } else {
                    state.cityMapState.selectMarkerAt(
                        coordinate = location.toCityMapCoordinate(),
                        label = location.name,
                        moveCameraToMarker = true,
                    )
                })
            val updatedSummaries =
                if (cachedForecast != null) {
                    state.savedLocationSummaries +
                        (location.id to
                            SavedLocationWeatherSummary(
                                temperatureC = cachedForecast.data.current.temperatureC,
                                descriptionPt = cachedForecast.data.current.weatherDescriptionPt,
                                condition = cachedForecast.data.current.visualCondition,
                                isFromCache = true,
                            ))
                } else {
                    state.savedLocationSummaries
                }
            state.copy(
                activeTab = ClimaTab.HOME,
                activeLocation = location,
                activeModal = ClimaActiveModal.None,
                locationPickerState = LocationPickerUiState(),
                mapLocationPickerState = MapLocationPickerUiState(),
                customPointNameInput = "",
                cityMapState = updatedMapState,
                forecast = cachedForecast?.data,
                forecastOrigin = cachedForecast?.origin,
                forecastFetchedAtMillis = cachedForecast?.fetchedAtEpochMillis,
                forecastErrorMessage = null,
                forecastStaleNotice = cachedForecast?.staleReason?.userMessagePt,
                airQuality = cachedAir?.data,
                airQualityOrigin = cachedAir?.origin,
                airQualityErrorMessage = null,
                alerts = cachedAlerts,
                savedLocationSummaries = updatedSummaries,
            )
        }

        refreshActiveLocation(policy = CachePolicy.NetworkFirst)
        showToast(toastMessage)
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

    fun markLocationPromptHandled() {
        _uiState.update { state ->
            state.copy(
                preferences = state.preferences.copy(locationPermissionPromptShown = true),
                locationPermissionSnapshot = state.locationPermissionSnapshot.copy(initialPromptShown = true),
            )
        }
        viewModelScope.launch {
            container.preferencesRepository.setLocationPermissionPromptShown(true)
        }
    }

    fun syncLocationPermissionSnapshot(snapshot: LocationPermissionSnapshot) {
        _uiState.update { state ->
            val effectivePromptShown =
                snapshot.initialPromptShown || state.preferences.locationPermissionPromptShown
            val normalizedSnapshot = snapshot.copy(initialPromptShown = effectivePromptShown)
            val updatedMapState =
                if (normalizedSnapshot.hasAnyPermission) {
                    val serviceNotice =
                        if (normalizedSnapshot.isLocationServiceEnabled) {
                            state.cityMapState.locationStatusNotice
                        } else {
                            "Localização do aparelho desativada no sistema."
                        }
                    state.cityMapState.withPermissionState(
                        state = LocationPermissionState.GRANTED,
                        precision = normalizedSnapshot.precisionLevel,
                        clearCurrentLocationIfDenied = false,
                        notice = serviceNotice,
                    )
                } else {
                    val mapPermState =
                        if (effectivePromptShown) {
                            LocationPermissionState.DENIED
                        } else {
                            LocationPermissionState.NOT_REQUESTED
                        }
                    state.cityMapState.withPermissionState(
                        state = mapPermState,
                        precision = LocationPrecisionLevel.NONE,
                        clearCurrentLocationIfDenied = true,
                    )
                }
            state.copy(
                locationPermissionSnapshot = normalizedSnapshot,
                cityMapState = updatedMapState,
            )
        }
    }

    fun onLocationPermissionResult(snapshot: LocationPermissionSnapshot) {
        val withPromptFlag = snapshot.copy(initialPromptShown = true)
        markLocationPromptHandled()
        syncLocationPermissionSnapshot(withPromptFlag)
    }

    fun selectCitiesSubTab(subTab: CitiesSubTab) {
        if (subTab == CitiesSubTab.MAP) {
            openMapLocationPickerModal()
            return
        }
        _uiState.update { it.copy(citiesSubTab = subTab) }
    }

    fun updateCityMapState(newState: CityMapUiState) {
        val current = _uiState.value
        val previousState = current.cityMapState
        val previousCurrent = previousState.currentUserLocation
        val updatedCurrent = newState.currentUserLocation
        val isMapPickerModal = current.activeModal == ClimaActiveModal.MapLocationPicker

        if (isMapPickerModal) {
            val isGpsUpdate =
                updatedCurrent != null &&
                    (updatedCurrent != previousCurrent || newState.locationFixVersion != previousState.locationFixVersion)
            val previousCoord = previousState.selectedMarker.coordinate
            val newCoord = newState.selectedMarker.coordinate
            val coordChanged = previousCoord != newCoord

            var nextCustomName = current.customPointNameInput
            val updatedPicker =
                when {
                    isGpsUpdate -> {
                        if (
                            nextCustomName == current.mapLocationPickerState.selectedPlaceName ||
                            nextCustomName == current.mapLocationPickerState.selectedSearchResult?.name
                        ) {
                            nextCustomName = ""
                        }
                        current.mapLocationPickerState.copy(
                            selectedSearchResult = null,
                            isGpsSelection = true,
                            selectedPlaceName = "Minha localização",
                            selectedRegionSubtitle = "",
                        )
                    }
                    coordChanged -> {
                        val candidate = current.mapLocationPickerState.selectedSearchResult
                        val matchesCandidate =
                            candidate != null &&
                                kotlin.math.abs(candidate.latitude - newCoord.latitude) <= 0.0005 &&
                                kotlin.math.abs(candidate.longitude - newCoord.longitude) <= 0.0005
                        if (matchesCandidate && candidate != null) {
                            current.mapLocationPickerState.copy(
                                isGpsSelection = false,
                                selectedPlaceName = candidate.name,
                                selectedRegionSubtitle = candidate.displayRegionLabel(),
                            )
                        } else {
                            val matchingSaved =
                                current.savedLocations.firstOrNull {
                                    kotlin.math.abs(it.latitude - newCoord.latitude) <= 0.0005 &&
                                        kotlin.math.abs(it.longitude - newCoord.longitude) <= 0.0005
                                }
                            if (
                                nextCustomName == current.mapLocationPickerState.selectedPlaceName ||
                                nextCustomName == current.mapLocationPickerState.selectedSearchResult?.name
                            ) {
                                nextCustomName = matchingSaved?.name.orEmpty()
                            }
                            current.mapLocationPickerState.copy(
                                selectedSearchResult = null,
                                isGpsSelection = false,
                                selectedPlaceName = matchingSaved?.name ?: "Ponto ${newCoord.formatted()}",
                                selectedRegionSubtitle = matchingSaved?.displaySubtitle().orEmpty(),
                            )
                        }
                    }
                    else -> current.mapLocationPickerState
                }
            _uiState.update {
                it.copy(
                    cityMapState = newState,
                    mapLocationPickerState = updatedPicker,
                    customPointNameInput = nextCustomName,
                )
            }
            return
        }

        _uiState.update { it.copy(cityMapState = newState) }

        if (
            updatedCurrent != null &&
            (updatedCurrent != previousCurrent || newState.locationFixVersion != previousState.locationFixVersion)
        ) {
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
        if (_uiState.value.activeModal == ClimaActiveModal.MapLocationPicker) {
            confirmMapLocationSelection()
            return
        }
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
        pickerSearchJob?.cancel()
        mapPickerSearchJob?.cancel()
        pickerActivationJob?.cancel()
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
        private const val PICKER_SEARCH_DEBOUNCE_MS = 280L
        private const val DEFAULT_SAO_PAULO_PROVIDER_ID = 3448439L
    }
}
