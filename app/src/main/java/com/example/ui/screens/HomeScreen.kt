package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DataSourceOrigin
import com.example.data.model.DailyForecastPoint
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherUnitConverter
import com.example.media.ClimaUiIcons
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.ClimaDateLabels
import com.example.ui.components.V53GlassCard
import com.example.ui.components.V53NoticeBanner
import com.example.ui.components.V53PrimaryButton
import com.example.ui.components.V53PullToRefreshBox
import com.example.ui.components.V53SectionHeader
import com.example.ui.state.ClimaUiState
import com.example.ui.state.WeatherMetricType
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaButtonGradientEnd
import com.example.ui.theme.ClimaButtonGradientStart
import com.example.ui.theme.ClimaDayRowBorder
import com.example.ui.theme.ClimaDayRowEnd
import com.example.ui.theme.ClimaDayRowStart
import com.example.ui.theme.ClimaHourActiveBorder
import com.example.ui.theme.ClimaHourActiveBottom
import com.example.ui.theme.ClimaHourActiveTop
import com.example.ui.theme.ClimaHourCardBottom
import com.example.ui.theme.ClimaHourCardTop
import com.example.ui.theme.ClimaRainCyan
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTempMaxCoral
import com.example.ui.theme.ClimaTempMinCyan
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs

/**
 * Complete native reproduction of CLIMA 3D V53 `mainScreen` (Início):
 * Hero (`clima-v36-summary`) -> 37h horizontal carousel (`h21-panel`) ->
 * 7+9=16 days expandable forecast (`days-v27`) -> Temperature curve ->
 * 6 meteorological indicator cards -> Today's highlights -> Air Quality -> Alerts card.
 */
@Composable
fun HomeScreen(
    uiState: ClimaUiState,
    contentPadding: PaddingValues,
    onOpenCities: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAlerts: () -> Unit,
    onRefresh: () -> Unit,
    onToggleDaysExpanded: () -> Unit,
    onHourClick: (Int, HourlyForecastPoint) -> Unit,
    onDayClick: (Int, DailyForecastPoint) -> Unit,
    onMetricClick: (WeatherMetricType) -> Unit,
) {
    val tabSpec = remember { WeatherGlassSpecs.forTab(ClimaTab.HOME) }
    val dark = LocalClimaDarkTheme.current
    val forecast = uiState.forecast

    V53PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        testTag = "home_pull_refresh",
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .testTag("home_screen_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. V53 Hero (`clima-v36-summary`)
            HomeHeroSection(
                uiState = uiState,
                onOpenCities = onOpenCities,
                onOpenSettings = onOpenSettings,
                onRefresh = onRefresh,
            )

            // Offline / Cache or Error status notice when relevant
            if (!uiState.isOnline || uiState.forecastOrigin == DataSourceOrigin.LOCAL_CACHE || uiState.forecastStaleNotice != null) {
                val updated =
                    ClimaDateLabels.formatUpdatedTimestamp(
                        epochMillis = uiState.forecastFetchedAtMillis,
                        timezoneId = forecast?.timezoneId,
                        utcOffsetSeconds = forecast?.utcOffsetSeconds ?: 0,
                    )
                val noticeText =
                    uiState.forecastStaleNotice
                        ?: if (!uiState.isOnline) {
                            "Sem conexão com a internet. Exibindo dados salvos em cache ($updated)."
                        } else {
                            "Exibindo previsão armazenada em cache local ($updated)."
                        }
                V53NoticeBanner(
                    text = noticeText,
                    iconRes = ClimaUiIcons.Info,
                    accentColor = ClimaAlertAttentionAmber,
                    modifier = Modifier.testTag("home_offline_cache_banner"),
                )
            }

            // Empty / Loading / Error states when no forecast is available yet
            if (forecast == null) {
                V53GlassCard(
                    alpha = tabSpec.cardAlpha,
                    modifier = Modifier.testTag("home_empty_state_card"),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                color = ClimaAccentCyan,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "Carregando dados reais do Open-Meteo para ${uiState.activeLocationName}…",
                                color = WeatherGlassSpecs.primaryTextColor(dark),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        } else if (uiState.activeLocation == null) {
                            Text(
                                text = "Nenhuma cidade selecionada",
                                color = WeatherGlassSpecs.primaryTextColor(dark),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Adicione uma cidade ou marque um ponto no mapa para consultar a previsão real.",
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                            V53PrimaryButton(
                                text = "Escolher localização",
                                iconRes = ClimaUiIcons.Pin,
                                onClick = onOpenCities,
                            )
                        } else {
                            Text(
                                text = uiState.forecastErrorMessage ?: "Não foi possível carregar a previsão meteorológica.",
                                color = WeatherGlassSpecs.primaryTextColor(dark),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            V53PrimaryButton(
                                text = stringResource(R.string.home_retry_action),
                                iconRes = ClimaUiIcons.Refresh,
                                onClick = onRefresh,
                                modifier = Modifier.testTag("home_retry_button"),
                            )
                        }
                    }
                }
            } else {
                // 2. V53 Hourly Forecast Panel (`h21-panel`, up to 37h)
                HomeHourlyForecastPanel(
                    forecast = forecast,
                    uiState = uiState,
                    panelAlpha = tabSpec.hourlyPanelAlpha,
                    onHourClick = onHourClick,
                )

                // 3. V53 Daily Forecast Section (`days-v27`, 7 + 9 = 16 days)
                HomeDailyForecastSection(
                    forecast = forecast,
                    uiState = uiState,
                    cardAlpha = tabSpec.dailyPanelAlpha,
                    onToggleExpand = onToggleDaysExpanded,
                    onDayClick = onDayClick,
                )

                // 4. V53 Temperature Evolution Curve
                HomeTemperatureEvolutionSection(
                    forecast = forecast,
                    uiState = uiState,
                    cardAlpha = tabSpec.cardAlpha,
                )

                // 5. V53 Meteorological Indicators 2-Column Grid (6 cards)
                HomeMetricsGridSection(
                    forecast = forecast,
                    uiState = uiState,
                    cardAlpha = tabSpec.cardAlpha,
                    onOpenMetric = onMetricClick,
                )

                // 6. V53 Today's Highlights
                HomeTodayHighlightsSection(
                    forecast = forecast,
                    uiState = uiState,
                    cardAlpha = tabSpec.cardAlpha,
                )

                // 7. V53 Air Quality
                HomeAirQualitySection(
                    airQuality = uiState.airQuality,
                    cardAlpha = tabSpec.cardAlpha,
                )

                // 8. V53 In-App Alerts Summary Card
                HomeAlertsSummarySection(
                    uiState = uiState,
                    cardAlpha = tabSpec.cardAlpha,
                    onOpenAlerts = onOpenAlerts,
                )
            }
        }
    }
}

@Composable
private fun HomeHeroSection(
    uiState: ClimaUiState,
    onOpenCities: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val forecast = uiState.forecast
    val current = forecast?.current

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).testTag("home_hero_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Top action row: Location selector pill + refresh + settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(99.dp),
                color = if (dark) Color(0x9E082A49) else Color(0xDDE0EFFC),
                border = BorderStroke(1.dp, if (dark) Color(0x885BC0F1) else Color(0x880E6CB8)),
                modifier = Modifier.weight(1f, fill = false).testTag("home_location_selector"),
            ) {
                Row(
                    modifier =
                        Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onOpenCities)
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(ClimaUiIcons.Pin),
                        contentDescription = stringResource(R.string.home_select_location_cd),
                        tint = WeatherGlassSpecs.accentColor(dark),
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text =
                            if (uiState.activeLocationSubtitle.isNotBlank()) {
                                "${uiState.activeLocationName}, ${uiState.activeLocationSubtitle.substringBefore(",")}"
                            } else {
                                uiState.activeLocationName
                            },
                        color = WeatherGlassSpecs.primaryTextColor(dark),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        painter = painterResource(ClimaUiIcons.Chevron),
                        contentDescription = null,
                        tint = WeatherGlassSpecs.mutedTextColor(dark),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (dark) Color(0x99082A49) else Color(0xDDE0EFFC),
                    border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
                ) {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(44.dp).testTag("home_refresh_button"),
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                color = ClimaAccentCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                painter = painterResource(ClimaUiIcons.Refresh),
                                contentDescription = stringResource(R.string.home_refresh_cd),
                                tint = WeatherGlassSpecs.primaryTextColor(dark),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = if (dark) Color(0x99082A49) else Color(0xDDE0EFFC),
                    border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
                ) {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(44.dp).testTag("home_settings_button"),
                    ) {
                        Icon(
                            painter = painterResource(ClimaUiIcons.Settings),
                            contentDescription = stringResource(R.string.home_open_settings_cd),
                            tint = WeatherGlassSpecs.primaryTextColor(dark),
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }

        // Source & last updated line (replaces V53 "Prévia animada" with real data origin)
        val updatedLabel =
            when {
                uiState.isRefreshing && forecast == null -> "Consultando Open-Meteo…"
                forecast != null -> {
                    val ts =
                        ClimaDateLabels.formatUpdatedTimestamp(
                            epochMillis = uiState.forecastFetchedAtMillis,
                            timezoneId = forecast.timezoneId,
                            utcOffsetSeconds = forecast.utcOffsetSeconds,
                        )
                    val originText =
                        if (uiState.forecastOrigin == DataSourceOrigin.LOCAL_CACHE) {
                            "Cache local"
                        } else {
                            "Open-Meteo ao vivo"
                        }
                    "$originText • Atualizado $ts"
                }

                else -> "Aguardando dados meteorológicos"
            }
        Text(
            text = updatedLabel,
            color = WeatherGlassSpecs.mutedTextColor(dark),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp).testTag("home_updated_label"),
        )

        // Main Hero Row: Left climate data + Right 132dp animated WebP icon
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = WeatherUnitConverter.formatTemperature(current?.temperatureC, prefs.temperatureUnit) ?: "—",
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.testTag("home_current_temperature"),
                )
                Text(
                    text = current?.weatherDescriptionPt ?: stringResource(uiState.activeWeatherCondition.labelRes),
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    modifier = Modifier.testTag("home_current_condition"),
                )
                Text(
                    text = "Sensação térmica: ${WeatherUnitConverter.formatTemperature(current?.apparentTemperatureC, prefs.temperatureUnit) ?: "—"}",
                    color = WeatherGlassSpecs.mutedTextColor(dark),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "↑ Máx. ${WeatherUnitConverter.formatTemperature(forecast?.todayMaxTempC, prefs.temperatureUnit) ?: "—"}",
                        color = ClimaTempMaxCoral,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = "↓ Mín. ${WeatherUnitConverter.formatTemperature(forecast?.todayMinTempC, prefs.temperatureUnit) ?: "—"}",
                        color = ClimaTempMinCyan,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // Official V50 132dp animated WebP icon without artificial movement
            AnimatedWeatherIcon(
                condition = uiState.activeWeatherCondition,
                size = WeatherIconSize.Hero,
                modifier = Modifier.testTag("home_hero_weather_icon"),
            )
        }
    }
}

@Composable
private fun HomeHourlyForecastPanel(
    forecast: WeatherForecastBundle,
    uiState: ClimaUiState,
    panelAlpha: Float,
    onHourClick: (Int, HourlyForecastPoint) -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val hours = forecast.hourly

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("home_hourly_panel"),
        shape = RoundedCornerShape(17.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha = panelAlpha, darkSurface = dark),
        border = BorderStroke(1.dp, if (dark) Color(0xB3307DB2) else Color(0x771C639F)),
    ) {
        Column {
            // V53 .h21-heading strip
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (dark) {
                                Brush.verticalGradient(listOf(Color(0xB9114974), Color(0xBA0D3154)))
                            } else {
                                Brush.verticalGradient(listOf(Color(0xFFE2F1FC), Color(0xFFD6E9F9)))
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(ClimaUiIcons.Clock),
                        contentDescription = null,
                        tint = if (dark) Color(0xFF9EDFFF) else Color(0xFF0E6CB8),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.home_hourly_title),
                        color = WeatherGlassSpecs.primaryTextColor(dark),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    text = "${hours.size} horas",
                    color = if (dark) Color(0xFFCAE8FE) else Color(0xFF0E6CB8),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // V53 .h21-scroller horizontal 37h track
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("home_hourly_list"),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                itemsIndexed(hours, key = { idx, item -> "${idx}_${item.timeIso}" }) { index, point ->
                    val isCurrent = index == 0
                    val timeLabel =
                        remember(
                            index,
                            point.timeIso,
                            point.epochMillis,
                            forecast.timezoneId,
                            forecast.utcOffsetSeconds,
                            prefs.timeFormat,
                        ) {
                            ClimaDateLabels.formatHourLabel(
                                index = index,
                                isoTime = point.timeIso,
                                epochMillis = point.epochMillis,
                                timezoneId = forecast.timezoneId,
                                utcOffsetSeconds = forecast.utcOffsetSeconds,
                                timeFormat = prefs.timeFormat,
                            )
                        }
                    val tempLabel =
                        remember(point.temperatureC, prefs.temperatureUnit) {
                            WeatherUnitConverter.formatTemperature(point.temperatureC, prefs.temperatureUnit) ?: "—"
                        }
                    val rainLabel =
                        remember(point.precipitationProbabilityPercent) {
                            point.precipitationProbabilityPercent?.let { "$it%" } ?: "—"
                        }
                    val cardBg =
                        when {
                            isCurrent && dark -> Brush.verticalGradient(listOf(ClimaHourActiveTop, ClimaHourActiveBottom))
                            dark -> Brush.verticalGradient(listOf(ClimaHourCardTop, ClimaHourCardBottom))
                            isCurrent -> Brush.verticalGradient(listOf(Color(0xFF1967AB), Color(0xFF10497E)))
                            else -> Brush.verticalGradient(listOf(Color(0xFFE7F2FC), Color(0xFFD9EAF9)))
                        }
                    val cardTextColor = if (dark || isCurrent) ClimaTextOnDark else Color(0xFF102A43)
                    val rainTextColor = if (dark || isCurrent) ClimaRainCyan else Color(0xFF0E6CB8)

                    Surface(
                        modifier =
                            Modifier
                                .width(85.dp)
                                .height(158.dp)
                                .testTag("home_hour_card_$index"),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        border =
                            BorderStroke(
                                width = if (isCurrent) 1.5.dp else 1.dp,
                                color = if (isCurrent) ClimaHourActiveBorder else Color(0xBD3873A1),
                            ),
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(cardBg)
                                    .clickable { onHourClick(index, point) }
                                    .padding(horizontal = 6.dp, vertical = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = timeLabel,
                                color = cardTextColor,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            AnimatedWeatherIcon(
                                condition = point.visualCondition,
                                size = WeatherIconSize.Hourly,
                            )
                            Text(
                                text = tempLabel,
                                color = cardTextColor,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(ClimaUiIcons.Drop),
                                    contentDescription = null,
                                    tint = rainTextColor,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = rainLabel,
                                    color = rainTextColor,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDailyForecastSection(
    forecast: WeatherForecastBundle,
    uiState: ClimaUiState,
    cardAlpha: Float,
    onToggleExpand: () -> Unit,
    onDayClick: (Int, DailyForecastPoint) -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val allDays = forecast.daily
    val totalDays = allDays.size
    val defaultVisible = minOf(7, totalDays)
    val visibleDays =
        remember(allDays, uiState.daysExpanded, defaultVisible) {
            if (uiState.daysExpanded) allDays else allDays.take(defaultVisible)
        }
    val extraCount = (totalDays - defaultVisible).coerceAtLeast(0)
    val chevronRotation by animateFloatAsState(
        targetValue = if (uiState.daysExpanded) 180f else 0f,
        label = "days_chevron_rotation",
    )

    V53GlassCard(
        alpha = cardAlpha,
        modifier = Modifier.testTag("home_daily_section"),
    ) {
        V53SectionHeader(
            title = stringResource(R.string.home_daily_title),
            iconRes = ClimaUiIcons.Calendar,
            trailingText = "${visibleDays.size} dias de $totalDays",
        )
        Spacer(modifier = Modifier.height(10.dp))

        val rowBrush =
            remember(dark) {
                if (dark) {
                    Brush.linearGradient(listOf(ClimaDayRowStart, ClimaDayRowEnd))
                } else {
                    Brush.linearGradient(listOf(Color(0xFFE7F2FC), Color(0xFFD8EAF9)))
                }
            }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleDays.forEachIndexed { index, day ->
                val (weekday, dateText) =
                    remember(index, day.dateIso) {
                        ClimaDateLabels.formatDailyWeekdayAndDate(index, day.dateIso)
                    }
                val maxTempText =
                    remember(day.temperatureMaxC, prefs.temperatureUnit) {
                        "↑ ${WeatherUnitConverter.formatTemperature(day.temperatureMaxC, prefs.temperatureUnit) ?: "—"}"
                    }
                val minTempText =
                    remember(day.temperatureMinC, prefs.temperatureUnit) {
                        "↓ ${WeatherUnitConverter.formatTemperature(day.temperatureMinC, prefs.temperatureUnit) ?: "—"}"
                    }
                val precipSumText =
                    remember(day.precipitationSumMm, prefs.precipitationUnit) {
                        WeatherUnitConverter.formatPrecipitation(day.precipitationSumMm, prefs.precipitationUnit) ?: "—"
                    }

                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("home_day_row_$index"),
                    shape = RoundedCornerShape(13.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, if (dark) ClimaDayRowBorder else Color(0x771C639F)),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(rowBrush)
                                .clickable { onDayClick(index, day) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Col 1: Weekday + Date
                        Column(
                            modifier = Modifier.weight(1.18f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = weekday,
                                color = WeatherGlassSpecs.primaryTextColor(dark),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = dateText,
                                color = if (dark) Color(0xFFD4E9FA) else Color(0xFF334E68),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // Col 2: Animated weather icon (V53 54dp)
                        AnimatedWeatherIcon(
                            condition = day.visualCondition,
                            size = 52.dp,
                        )

                        // Col 3: Max / Min temperatures
                        Column(
                            modifier = Modifier.weight(0.88f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = maxTempText,
                                color = if (dark) ClimaTempMaxCoral else Color(0xFFC0394B),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                text = minTempText,
                                color = if (dark) ClimaTempMinCyan else Color(0xFF0E6CB8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }

                        // Col 4: Rain probability % + volume mm/in
                        Column(
                            modifier = Modifier.weight(0.96f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(ClimaUiIcons.Drop),
                                    contentDescription = null,
                                    tint = if (dark) ClimaRainCyan else Color(0xFF0E6CB8),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = day.precipitationProbabilityMaxPercent?.let { "$it%" } ?: "—",
                                    color = if (dark) ClimaRainCyan else Color(0xFF0E6CB8),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = precipSumText,
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        if (extraCount > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("home_days_expand_button"),
                shape = RoundedCornerShape(14.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color(0xFF48B5F0)),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(ClimaButtonGradientStart, ClimaButtonGradientEnd)))
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onToggleExpand)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        painter = painterResource(ClimaUiIcons.Chevron),
                        contentDescription = null,
                        tint = ClimaTextOnDark,
                        modifier = Modifier.size(16.dp).rotate(chevronRotation),
                    )
                    Text(
                        text =
                            if (uiState.daysExpanded) {
                                stringResource(R.string.home_days_collapse)
                            } else {
                                "Mostrar mais $extraCount dias"
                            },
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text =
                            if (uiState.daysExpanded) {
                                stringResource(R.string.home_days_collapse_hint)
                            } else {
                                "$totalDays dias no total"
                            },
                        color = Color(0xFF96BBD4),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
