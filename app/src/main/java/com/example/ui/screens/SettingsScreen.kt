package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.preferences.AppThemeMode
import com.example.data.model.PrecipitationUnit
import com.example.data.model.PressureUnit
import com.example.data.model.TemperatureUnit
import com.example.data.model.TimeFormatPreference
import com.example.data.model.VisibilityUnit
import com.example.data.model.WeatherUnitConverter
import com.example.data.model.WindSpeedUnit
import com.example.maps.LocationPrecisionLevel
import com.example.media.ClimaUiIcons
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.PressureCategoryPill
import com.example.ui.components.PressureLegendGrid
import com.example.ui.components.V53GlassCard
import com.example.ui.components.V53NoticeBanner
import com.example.ui.components.V53PageHeading
import com.example.ui.components.V53PrimaryButton
import com.example.ui.components.V53SegmentedOptions
import com.example.ui.components.V53SwitchRow
import com.example.ui.state.ClimaUiState
import com.example.ui.state.InAppWeatherAlertEngine
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaButtonGradientEnd
import com.example.ui.theme.ClimaButtonGradientStart
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs

private val WIDGET_SIZES = listOf("1×1", "2×1", "4×1", "4×2", "4×3")

/**
 * Complete native reproduction of CLIMA 3D V53 `settingsScreen` (Ajustes)
 * with all 10 glass setting cards persisted in Jetpack DataStore via UserPreferencesRepository.
 */
@Composable
fun SettingsScreen(
    uiState: ClimaUiState,
    contentPadding: PaddingValues,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetTemperatureUnit: (TemperatureUnit) -> Unit,
    onSetWindSpeedUnit: (WindSpeedUnit) -> Unit,
    onSetPrecipitationUnit: (PrecipitationUnit) -> Unit,
    onSetPressureUnit: (PressureUnit) -> Unit,
    onSetVisibilityUnit: (VisibilityUnit) -> Unit,
    onSetTimeFormat: (TimeFormatPreference) -> Unit,
    onSetShowPressureStatus: (Boolean) -> Unit,
    onOpenPressureInfo: () -> Unit,
    onSetWidgetPreviewSize: (String) -> Unit,
    onSetAnimatedBackground: (Boolean) -> Unit,
    onSetAutoRefreshOnOpen: (Boolean) -> Unit,
    onOpenCities: () -> Unit,
    onResetPreferences: () -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    onOpenAppLocationSettings: () -> Unit = {},
) {
    val tabSpec = remember { WeatherGlassSpecs.forTab(ClimaTab.SETTINGS) }
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val current = uiState.forecast?.current

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp).testTag("settings_screen_list"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "settings_heading") {
                V53PageHeading(
                    eyebrow = stringResource(R.string.settings_eyebrow),
                    title = stringResource(R.string.settings_title),
                    subtitle = stringResource(R.string.settings_subtitle),
                )
            }

            // 1. Aparência
            item(key = "setting_appearance") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_appearance),
                    description = "Escolha o estilo da interface do aplicativo.",
                    iconRes = ClimaUiIcons.Sun,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53SegmentedOptions(
                        options =
                            listOf(
                                AppThemeMode.DARK to "Escuro",
                                AppThemeMode.LIGHT to "Claro",
                                AppThemeMode.AUTO to "Automático",
                            ),
                        selected = prefs.themeMode,
                        onSelect = onSetThemeMode,
                        testTagPrefix = "setting_theme",
                    )
                }
            }

            // 2. Temperatura
            item(key = "setting_temperature") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_temperature),
                    description = "Selecione a unidade utilizada em todas as previsões.",
                    iconRes = ClimaUiIcons.Thermo,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53SegmentedOptions(
                        options =
                            listOf(
                                TemperatureUnit.CELSIUS to "Celsius (°C)",
                                TemperatureUnit.FAHRENHEIT to "Fahrenheit (°F)",
                            ),
                        selected = prefs.temperatureUnit,
                        onSelect = onSetTemperatureUnit,
                        testTagPrefix = "setting_temp",
                    )
                }
            }

            // 3. Vento, precipitação, visibilidade e formato de hora
            item(key = "setting_wind_rain") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_wind_rain),
                    description = "Ajuste as unidades de velocidade do vento, chuva, visibilidade e relógio.",
                    iconRes = ClimaUiIcons.Wind,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53SegmentedOptions(
                        options =
                            listOf(
                                WindSpeedUnit.KMH to "km/h",
                                WindSpeedUnit.MPH to "mph",
                                WindSpeedUnit.MS to "m/s",
                                WindSpeedUnit.KNOTS to "kn",
                            ),
                        selected = prefs.windSpeedUnit,
                        onSelect = onSetWindSpeedUnit,
                        testTagPrefix = "setting_wind",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    V53SegmentedOptions(
                        options =
                            listOf(
                                PrecipitationUnit.MM to "Milímetros (mm)",
                                PrecipitationUnit.INCH to "Polegadas (in)",
                            ),
                        selected = prefs.precipitationUnit,
                        onSelect = onSetPrecipitationUnit,
                        testTagPrefix = "setting_rain",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            V53SegmentedOptions(
                                options =
                                    listOf(
                                        VisibilityUnit.KM to "km",
                                        VisibilityUnit.MILES to "mi",
                                    ),
                                selected = prefs.visibilityUnit,
                                onSelect = onSetVisibilityUnit,
                                testTagPrefix = "setting_vis",
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            V53SegmentedOptions(
                                options =
                                    listOf(
                                        TimeFormatPreference.H24 to "24h",
                                        TimeFormatPreference.H12 to "12h",
                                    ),
                                selected = prefs.timeFormat,
                                onSelect = onSetTimeFormat,
                                testTagPrefix = "setting_time",
                            )
                        }
                    }
                }
            }

            // 4. Pressão atmosférica
            item(key = "setting_pressure") {
                val pressureHpa = current?.pressureMslHpa ?: current?.surfacePressureHpa
                val pressureFormatted = WeatherUnitConverter.formatPressure(pressureHpa, prefs.pressureUnit) ?: "—"
                val pressureCat = InAppWeatherAlertEngine.classifyPressureHpa(current?.pressureMslHpa)

                SettingCardBlock(
                    title = stringResource(R.string.settings_pressure),
                    description = "Entenda a pressão do ar e veja quando ela está baixa, normal ou alta.",
                    iconRes = ClimaUiIcons.Pressure,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53SegmentedOptions(
                        options =
                            listOf(
                                PressureUnit.HPA to "hPa",
                                PressureUnit.MMHG to "mmHg",
                                PressureUnit.INHG to "inHg",
                            ),
                        selected = prefs.pressureUnit,
                        onSelect = onSetPressureUnit,
                        testTagPrefix = "setting_pressure_unit",
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (dark) Color(0x8808233E) else Color(0xFFE3F1FC),
                        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = pressureFormatted,
                                    color = WeatherGlassSpecs.primaryTextColor(dark),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                if (pressureCat != null) {
                                    PressureCategoryPill(category = pressureCat)
                                }
                            }
                            Text(
                                text = "Interpretação aproximada da pressão reduzida ao nível do mar (não da pressão local em altitude).",
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            PressureLegendGrid()
                            V53PrimaryButton(
                                text = stringResource(R.string.settings_pressure_info_btn),
                                iconRes = ClimaUiIcons.Info,
                                secondary = true,
                                onClick = onOpenPressureInfo,
                                modifier = Modifier.testTag("setting_pressure_info_button"),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    V53SwitchRow(
                        description = "Mostrar a classificação nos detalhes",
                        checked = prefs.showPressureStatus,
                        onCheckedChange = onSetShowPressureStatus,
                        switchTestTag = "setting_switch_pressure_status",
                    )
                }
            }

            // 5. Widgets da tela inicial (V53 interactive live preview 1×1..4×3)
            item(key = "setting_widgets") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_widgets),
                    description = "Prévia interativa dos formatos de widget com os dados reais da localização ativa.",
                    iconRes = ClimaUiIcons.Layers,
                    alpha = tabSpec.cardAlpha,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WIDGET_SIZES.forEach { sizeLabel ->
                            val selected = prefs.widgetPreviewSize == sizeLabel
                            val shape = RoundedCornerShape(10.dp)
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clip(shape)
                                        .background(
                                            if (selected) {
                                                Brush.linearGradient(listOf(ClimaButtonGradientStart, ClimaButtonGradientEnd))
                                            } else if (dark) {
                                                Brush.linearGradient(listOf(Color(0x88082540), Color(0x88061C33)))
                                            } else {
                                                Brush.linearGradient(listOf(Color(0xFFD9E9F7), Color(0xFFCDE2F4)))
                                            },
                                        )
                                        .clickable { onSetWidgetPreviewSize(sizeLabel) }
                                        .minimumInteractiveComponentSize()
                                        .padding(vertical = 8.dp)
                                        .testTag("setting_widget_$sizeLabel"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = sizeLabel,
                                    color = if (selected || dark) ClimaTextOnDark else Color(0xFF102A43),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (dark) Color(0x9E0A2F54) else Color(0xFFE3F1FC),
                        border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().testTag("setting_widget_preview"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            AnimatedWeatherIcon(
                                condition = uiState.activeWeatherCondition,
                                size = WeatherIconSize.Widget,
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = uiState.activeLocationName,
                                    color = WeatherGlassSpecs.mutedTextColor(dark),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = WeatherUnitConverter.formatTemperature(current?.temperatureC, prefs.temperatureUnit) ?: "—",
                                    color = WeatherGlassSpecs.primaryTextColor(dark),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = "${current?.weatherDescriptionPt ?: stringResource(uiState.activeWeatherCondition.labelRes)} • ${prefs.widgetPreviewSize}",
                                    color = WeatherGlassSpecs.mutedTextColor(dark),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 6. Cenários dinâmicos
            item(key = "setting_animated_bg") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_dynamic_scenes),
                    description = null,
                    iconRes = ClimaUiIcons.Sun,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53SwitchRow(
                        description = "Fundos em vídeo (Media3) e cenários visuais conforme o clima; desative para usar wallpapers estáticos leves.",
                        checked = prefs.animatedBackgroundEnabled,
                        onCheckedChange = onSetAnimatedBackground,
                        switchTestTag = "setting_switch_animated_bg",
                    )
                }
            }

            // 7. Atualização dos dados
            item(key = "setting_auto_refresh") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_data_refresh),
                    description = null,
                    iconRes = ClimaUiIcons.Refresh,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53SwitchRow(
                        description = "Atualizar automaticamente ao abrir o aplicativo e reaproveitar cache local Room quando offline, sem exigir notificações.",
                        checked = prefs.autoRefreshOnOpen,
                        onCheckedChange = onSetAutoRefreshOnOpen,
                        switchTestTag = "setting_switch_auto_refresh",
                    )
                }
            }

            // 8. Localizações
            item(key = "setting_locations") {
                val permSnapshot = uiState.locationPermissionSnapshot
                SettingCardBlock(
                    title = stringResource(R.string.settings_locations),
                    description = "Gerencie suas ${uiState.savedLocations.size} localizações salvas e pontos marcados no mapa.",
                    iconRes = ClimaUiIcons.Location,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53PrimaryButton(
                        text = "Abrir minhas localizações",
                        iconRes = ClimaUiIcons.Pin,
                        onClick = onOpenCities,
                        modifier = Modifier.testTag("setting_open_cities_button"),
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (dark) Color(0x8808233E) else Color(0xFFE3F1FC),
                        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
                        modifier = Modifier.fillMaxWidth().testTag("setting_location_permission_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_location_permission_title),
                                color = WeatherGlassSpecs.primaryTextColor(dark),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = permSnapshot.statusSummaryPt,
                                color =
                                    when {
                                        permSnapshot.precisionLevel != LocationPrecisionLevel.NONE && permSnapshot.isLocationServiceEnabled ->
                                            if (dark) ClimaToastEmerald else Color(0xFF0E7C56)
                                        else ->
                                            if (dark) ClimaAlertAttentionAmber else Color(0xFF9C5700)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("setting_location_permission_status"),
                            )

                            if (!permSnapshot.hasAnyPermission) {
                                if (permSnapshot.permanentlyDenied) {
                                    V53PrimaryButton(
                                        text = stringResource(R.string.settings_location_permission_open_settings),
                                        iconRes = ClimaUiIcons.Settings,
                                        secondary = true,
                                        onClick = onOpenAppLocationSettings,
                                        modifier = Modifier.testTag("setting_open_app_settings_button"),
                                    )
                                } else {
                                    V53PrimaryButton(
                                        text = stringResource(R.string.settings_location_permission_request),
                                        iconRes = ClimaUiIcons.Location,
                                        secondary = true,
                                        onClick = onRequestLocationPermission,
                                        modifier = Modifier.testTag("setting_request_location_permission_button"),
                                    )
                                }
                            } else if (!permSnapshot.isLocationServiceEnabled) {
                                Text(
                                    text = "A localização do Android está desativada nas configurações do aparelho.",
                                    color = WeatherGlassSpecs.mutedTextColor(dark),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("setting_location_service_disabled_hint"),
                                )
                            }
                        }
                    }
                }
            }

            // 9. Fontes e privacidade
            item(key = "setting_sources") {
                SettingCardBlock(
                    title = stringResource(R.string.settings_sources),
                    description = "Previsões e qualidade do ar: Open-Meteo. Busca geográfica: Open-Meteo Geocoding. Radar meteorológico: RainViewer. Mapa cartográfico: MapLibre + OpenStreetMap.",
                    iconRes = ClimaUiIcons.Info,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53NoticeBanner(
                        text = "Nenhum cadastro, login ou chave de faturamento é exigido. Alertas são exibidos apenas dentro do aplicativo.",
                        iconRes = ClimaUiIcons.Check,
                    )
                }
            }

            // 10. Restaurar preferências
            item(key = "setting_reset") {
                SettingCardBlock(
                    title = "Restaurar preferências",
                    description = "Voltar as unidades, aparência e opções de exibição para o padrão inicial do Clima 3D.",
                    iconRes = ClimaUiIcons.Refresh,
                    alpha = tabSpec.cardAlpha,
                ) {
                    V53PrimaryButton(
                        text = stringResource(R.string.settings_reset),
                        secondary = true,
                        onClick = onResetPreferences,
                        modifier = Modifier.testTag("setting_reset_button"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingCardBlock(
    title: String,
    description: String?,
    iconRes: Int,
    alpha: Float,
    content: @Composable () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    V53GlassCard(alpha = alpha) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = WeatherGlassSpecs.accentColor(dark),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                color = WeatherGlassSpecs.primaryTextColor(dark),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (description != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}
