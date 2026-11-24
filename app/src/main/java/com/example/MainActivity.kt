package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ClimaDataContainer
import com.example.data.local.preferences.AppThemeMode
import com.example.ui.components.ClimaDetailModalHost
import com.example.ui.components.WeatherPersistentShell
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.CitiesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RadarScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.state.ClimaActiveModal
import com.example.ui.state.ClimaMainViewModel
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaTab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dataContainer = ClimaDataContainer.getInstance(applicationContext)
        setContent {
            Clima3DApp(dataContainer = dataContainer)
        }
    }
}

/**
 * Full CLIMA 3D V53 native Jetpack Compose application host wired to the Etapa 3A
 * meteorological repositories, Room cache, DataStore preferences, Media3 backgrounds,
 * animated WebP icons, and MapLibre + OpenStreetMap + RainViewer maps.
 */
@Composable
fun Clima3DApp(
    dataContainer: ClimaDataContainer? = null,
    viewModel: ClimaMainViewModel? = null,
) {
    val context = LocalContext.current
    val resolvedContainer = remember(dataContainer, context) {
        dataContainer ?: ClimaDataContainer.getInstance(context.applicationContext)
    }
    val vm: ClimaMainViewModel =
        viewModel
            ?: viewModel(
                factory = ClimaMainViewModel.Factory(container = resolvedContainer),
            )

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme =
        when (uiState.preferences.themeMode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.AUTO -> systemDark
        }

    // Automatically refresh active location when returning to foreground if enabled in Settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        var firstResumeSkipped = false
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (firstResumeSkipped) {
                        vm.onAppResumeRefresh()
                    } else {
                        firstResumeSkipped = true
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle system Back press cleanly when a detail modal is open or on the Alerts sub-screen
    BackHandler(enabled = uiState.activeModal != ClimaActiveModal.None || uiState.activeTab == ClimaTab.ALERTS) {
        vm.navigateBackFromSecondary()
    }

    Clima3DTheme(darkTheme = useDarkTheme, dynamicColor = false) {
        WeatherPersistentShell(
            condition = uiState.activeWeatherCondition,
            activeTab = uiState.activeTab,
            animatedBackgroundEnabled = uiState.preferences.animatedBackgroundEnabled,
            onSelectTab = vm::selectTab,
            toastMessage = uiState.toastMessage,
        ) { contentPadding ->
            when (uiState.activeTab) {
                ClimaTab.HOME ->
                    HomeScreen(
                        uiState = uiState,
                        contentPadding = contentPadding,
                        onOpenCities = { vm.selectTab(ClimaTab.CITIES) },
                        onOpenSettings = { vm.selectTab(ClimaTab.SETTINGS) },
                        onOpenAlerts = { vm.selectTab(ClimaTab.ALERTS) },
                        onRefresh = { vm.refreshActiveLocation() },
                        onToggleDaysExpanded = vm::toggleDaysExpanded,
                        onHourClick = vm::openHourModal,
                        onDayClick = vm::openDayModal,
                        onMetricClick = vm::openMetricModal,
                    )

                ClimaTab.RADAR ->
                    RadarScreen(
                        uiState = uiState,
                        contentPadding = contentPadding,
                        onToggleLayerMenu = vm::toggleRadarLayerMenu,
                        onSelectLayer = vm::selectRadarLayer,
                        onZoomIn = vm::zoomRadarIn,
                        onZoomOut = vm::zoomRadarOut,
                        onSelectFrame = vm::selectRadarFrame,
                        onTogglePlay = vm::toggleRadarPlayback,
                        onRefreshRadar = { vm.refreshActiveLocation() },
                    )

                ClimaTab.CITIES ->
                    CitiesScreen(
                        uiState = uiState,
                        contentPadding = contentPadding,
                        onSelectSubTab = vm::selectCitiesSubTab,
                        onMapStateChanged = vm::updateCityMapState,
                        onPointNameChanged = vm::updateCustomPointNameInput,
                        onSaveSelectedPoint = vm::saveSelectedMapPoint,
                        onSearchQueryChanged = vm::updateCitySearchQuery,
                        onSubmitSearch = vm::submitCitySearch,
                        onAddCityResult = vm::addCityFromSearch,
                        onSelectSavedLocation = vm::selectSavedLocation,
                        onRemoveSavedLocation = vm::removeSavedLocation,
                    )

                ClimaTab.SETTINGS ->
                    SettingsScreen(
                        uiState = uiState,
                        contentPadding = contentPadding,
                        onSetThemeMode = vm::setThemeMode,
                        onSetTemperatureUnit = vm::setTemperatureUnit,
                        onSetWindSpeedUnit = vm::setWindSpeedUnit,
                        onSetPrecipitationUnit = vm::setPrecipitationUnit,
                        onSetPressureUnit = vm::setPressureUnit,
                        onSetVisibilityUnit = vm::setVisibilityUnit,
                        onSetTimeFormat = vm::setTimeFormat,
                        onSetShowPressureStatus = vm::setShowPressureStatus,
                        onOpenPressureInfo = vm::openPressureInfoModal,
                        onSetWidgetPreviewSize = vm::setWidgetPreviewSize,
                        onSetAnimatedBackground = vm::setAnimatedBackgroundEnabled,
                        onSetAutoRefreshOnOpen = vm::setAutoRefreshOnOpen,
                        onOpenCities = { vm.selectTab(ClimaTab.CITIES) },
                        onResetPreferences = vm::resetPreferencesToDefaults,
                    )

                ClimaTab.ALERTS ->
                    AlertsScreen(
                        uiState = uiState,
                        contentPadding = contentPadding,
                        onSelectSubTab = vm::selectAlertsSubTab,
                        onBackToHome = { vm.selectTab(ClimaTab.HOME) },
                    )
            }
        }

        ClimaDetailModalHost(
            uiState = uiState,
            onDismiss = vm::closeModal,
            onOpenPressureInfo = vm::openPressureInfoModal,
        )
    }
}

/**
 * Backward-compatible entry point preserved for Etapa 2B / MapLibreAndRadarTest verification,
 * delegating directly to the full V53 native [Clima3DApp].
 */
@Composable
fun Clima3DNativeAssetsFoundationApp() {
    Clima3DApp()
}
