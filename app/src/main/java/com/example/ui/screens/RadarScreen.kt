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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.DataSourceOrigin
import com.example.data.model.RadarTimelineSnapshot
import com.example.data.model.WeatherUnitConverter
import com.example.maps.RadarBaseStyle
import com.example.maps.RadarLayerStatusCard
import com.example.media.ClimaUiIcons
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.V53NoticeBanner
import com.example.ui.components.V53PrimaryButton
import com.example.ui.components.V53PullToRefreshBox
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaRadarHeaderBottom
import com.example.ui.theme.ClimaRadarHeaderTop
import com.example.ui.theme.ClimaRadarPlayBg
import com.example.ui.theme.ClimaRadarPlayBorder
import com.example.ui.theme.ClimaRadarRampColors
import com.example.ui.theme.ClimaRadarSummaryBottom
import com.example.ui.theme.ClimaRadarSummaryTop
import com.example.ui.theme.ClimaRadarTimelineBottom
import com.example.ui.theme.ClimaRadarTimelineTop
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs
import kotlin.math.roundToInt

/**
 * Native reproduction of CLIMA 3D V53 `wx28-radar` screen without a permanent MapLibre `MapView` (Etapa 3P):
 * Header -> Location pill -> Summary + 8-stop precipitation intensity ramp ->
 * Native precipitation evolution & hourly rain intensity monitor (Open-Meteo + RainViewer historical scans) ->
 * Real historical RainViewer timeline scrubber & play/pause ->
 * Preserved MeteorologicalRadarTileProvider transparency status card.
 */
@Composable
fun RadarScreen(
    uiState: ClimaUiState,
    contentPadding: PaddingValues,
    onToggleLayerMenu: () -> Unit,
    onSelectLayer: (RadarBaseStyle) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onSelectFrame: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onRefreshRadar: () -> Unit,
) {
    val radar = uiState.radarTimeline
    val frames = radar?.frames.orEmpty()
    val safeIndex = uiState.currentRadarFrameIndexSafe
    val activeFrame = frames.getOrNull(safeIndex)
    val maxRadarZoom = radar?.maxSupportedZoom ?: RadarTimelineSnapshot.PUBLIC_MAX_ZOOM

    V53PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefreshRadar,
        testTag = "radar_pull_refresh",
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .testTag("radar_screen_list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadarHeaderCard(
                isRefreshing = uiState.isRefreshing,
                activeLocationName = uiState.activeLocationName,
                activeLocationSubtitle = uiState.activeLocationSubtitle,
                framesCount = frames.size,
                radarOrigin = uiState.radarOrigin,
                maxRadarZoom = maxRadarZoom,
                radarErrorMessage = uiState.radarErrorMessage,
                onRefreshRadar = onRefreshRadar,
            )

            RadarSummaryCard(
                forecast = uiState.forecast,
                forecastOrigin = uiState.forecastOrigin,
                activeWeatherCondition = uiState.activeWeatherCondition,
                temperatureUnit = uiState.preferences.temperatureUnit,
                precipitationUnit = uiState.preferences.precipitationUnit,
            )

            RadarPrecipitationEvolutionCard(
                forecast = uiState.forecast,
                precipitationUnit = uiState.preferences.precipitationUnit,
                frames = frames,
                safeIndex = safeIndex,
                activeFrame = activeFrame,
                isOnline = uiState.isOnline,
                radarErrorMessage = uiState.radarErrorMessage,
                onSelectFrame = onSelectFrame,
                onRefreshRadar = onRefreshRadar,
            )

            RadarTimelineCard(
                frames = frames,
                safeIndex = safeIndex,
                activeFrame = activeFrame,
                isRadarPlaying = uiState.isRadarPlaying,
                onTogglePlay = onTogglePlay,
                onSelectFrame = onSelectFrame,
            )

            RadarLayerStatusCard()

            V53NoticeBanner(
                text = "A API pública do RainViewer fornece quadros históricos reais de precipitação (sem previsão futura/nowcast) e o Open-Meteo fornece a evolução horária real de chuva para a localização ativa.",
                iconRes = ClimaUiIcons.Info,
            )
        }
    }
}

@Composable
private fun RadarHeaderCard(
    isRefreshing: Boolean,
    activeLocationName: String,
    activeLocationSubtitle: String,
    framesCount: Int,
    radarOrigin: DataSourceOrigin?,
    maxRadarZoom: Int,
    radarErrorMessage: String?,
    onRefreshRadar: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x4A4B9EF8)),
        modifier = Modifier.fillMaxWidth().testTag("radar_header_card"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (dark) {
                            Brush.linearGradient(listOf(ClimaRadarHeaderTop, ClimaRadarHeaderBottom))
                        } else {
                            Brush.linearGradient(listOf(Color(0xEE185E9C), Color(0xEE0F4478)))
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painter = painterResource(ClimaUiIcons.Radar),
                    contentDescription = null,
                    tint = Color(0xFF20DDFF),
                    modifier = Modifier.size(40.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.radar_title),
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = stringResource(R.string.radar_subtitle),
                        color = Color(0xFFBFE3FB),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Color(0x55082B4B),
                    border = BorderStroke(1.dp, Color(0x6670CBF6)),
                ) {
                    IconButton(
                        onClick = onRefreshRadar,
                        modifier = Modifier.size(44.dp).testTag("radar_refresh_button"),
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                color = ClimaAccentCyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                painter = painterResource(ClimaUiIcons.Refresh),
                                contentDescription = stringResource(R.string.home_refresh_cd),
                                tint = ClimaTextOnDark,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Color(0x7A70CBF6)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.linearGradient(listOf(Color(0xBB1C659F), Color(0xC40D385D))))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painter = painterResource(ClimaUiIcons.Pin),
                            contentDescription = null,
                            tint = ClimaAccentCyan,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text =
                                if (activeLocationSubtitle.isNotBlank()) {
                                    "$activeLocationName • $activeLocationSubtitle"
                                } else {
                                    activeLocationName
                                },
                            color = ClimaTextOnDark,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text =
                            when {
                                framesCount > 0 -> {
                                    val originLabel =
                                        if (radarOrigin == DataSourceOrigin.LOCAL_CACHE) {
                                            "Metadados em cache"
                                        } else {
                                            "RainViewer (histórico)"
                                        }
                                    "$originLabel • $framesCount quadros históricos • Zoom máx. camada $maxRadarZoom"
                                }

                                radarErrorMessage != null -> radarErrorMessage
                                else -> "Carregando quadros históricos reais do RainViewer…"
                            },
                        color = Color(0xFFBDDBEB),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarSummaryCard(
    forecast: com.example.data.model.WeatherForecastBundle?,
    forecastOrigin: DataSourceOrigin?,
    activeWeatherCondition: com.example.media.WeatherCondition,
    temperatureUnit: com.example.data.model.TemperatureUnit,
    precipitationUnit: com.example.data.model.PrecipitationUnit,
) {
    Surface(
        shape = RoundedCornerShape(17.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x9166BEEE)),
        modifier = Modifier.fillMaxWidth().testTag("radar_summary_card"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(ClimaRadarSummaryTop, ClimaRadarSummaryBottom)))
                    .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnimatedWeatherIcon(
                        condition = activeWeatherCondition,
                        size = WeatherIconSize.Radar,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = WeatherUnitConverter.formatTemperature(forecast?.current?.temperatureC, temperatureUnit) ?: "—",
                            color = ClimaTextOnDark,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = forecast?.current?.weatherDescriptionPt ?: stringResource(activeWeatherCondition.labelRes),
                            color = Color(0xFFE4F6FF),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 8.dp)
                            .width(1.dp)
                            .height(48.dp)
                            .background(Color(0x6695D9FA)),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.radar_precip_now),
                        color = Color(0xFFBDDDEE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val rainRate =
                        WeatherUnitConverter.formatPrecipitation(
                            forecast?.current?.precipitationMm,
                            precipitationUnit,
                        ) ?: "0,0 ${precipitationUnit.symbol}"
                    Text(
                        text = "$rainRate/h",
                        color = ClimaTextOnDark,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = if (forecastOrigin == DataSourceOrigin.LOCAL_CACHE) "Cache local" else "Open-Meteo",
                        color = Color(0xFFBDDDEE),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            HorizontalDivider(color = Color(0x468FE9FF))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.radar_rain_intensity),
                    color = ClimaTextOnDark,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(82.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(11.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.horizontalGradient(ClimaRadarRampColors)),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("Fraca", "Moderada", "Forte", "Muito forte").forEach { label ->
                            Text(
                                text = label,
                                color = Color(0xFFC7E2F4),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadarPrecipitationEvolutionCard(
    forecast: com.example.data.model.WeatherForecastBundle?,
    precipitationUnit: com.example.data.model.PrecipitationUnit,
    frames: List<com.example.data.model.RainViewerRadarFrame>,
    safeIndex: Int,
    activeFrame: com.example.data.model.RainViewerRadarFrame?,
    isOnline: Boolean,
    radarErrorMessage: String?,
    onSelectFrame: (Int) -> Unit,
    onRefreshRadar: () -> Unit,
) {
    val hourlySlice = remember(forecast?.hourly) { forecast?.hourly.orEmpty().take(12) }
    val currentPrecipMm = forecast?.current?.precipitationMm ?: 0.0
    val currentIntensityLabel = remember(currentPrecipMm) { classifyPrecipitationIntensityPt(currentPrecipMm) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x8858C4FF)),
        modifier = Modifier.fillMaxWidth().testTag("radar_precipitation_evolution_card"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(ClimaRadarSummaryTop, ClimaRadarTimelineBottom)))
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Evolução da precipitação e varredura",
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Intensidade atual: $currentIntensityLabel • Dados reais Open-Meteo e RainViewer",
                        color = Color(0xFFC2DFF1),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = ClimaAccentCyan.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.55f)),
                ) {
                    Text(
                        text = currentIntensityLabel,
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            if (!isOnline && frames.isEmpty() && hourlySlice.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xAA08243E),
                    border = BorderStroke(1.dp, ClimaAlertAttentionAmber.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().testTag("radar_status_offline"),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Sem conexão com a internet para consultar as varreduras históricas do RainViewer.",
                            color = ClimaTextOnDark,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        V53PrimaryButton(
                            text = "Tentar novamente",
                            iconRes = ClimaUiIcons.Refresh,
                            secondary = true,
                            onClick = onRefreshRadar,
                            modifier = Modifier.testTag("radar_retry_button"),
                        )
                    }
                }
            } else if (radarErrorMessage != null && frames.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xAA08243E),
                    border = BorderStroke(1.dp, ClimaAlertAttentionAmber.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().testTag("radar_status_provider_error"),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = radarErrorMessage,
                            color = ClimaTextOnDark,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        V53PrimaryButton(
                            text = "Tentar novamente",
                            iconRes = ClimaUiIcons.Refresh,
                            secondary = true,
                            onClick = onRefreshRadar,
                            modifier = Modifier.testTag("radar_retry_button"),
                        )
                    }
                }
            }

            if (hourlySlice.isNotEmpty()) {
                Text(
                    text = "EVOLUÇÃO HORÁRIA DE CHUVA (OPEN-METEO)",
                    color = ClimaAccentCyan,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().testTag("radar_hourly_rain_list"),
                ) {
                    val maxMm = remember(hourlySlice) {
                        hourlySlice.maxOfOrNull { it.precipitationMm ?: 0.0 }?.coerceAtLeast(2.0) ?: 2.0
                    }
                    hourlySlice.take(6).forEachIndexed { idx, hour ->
                        val mm = (hour.precipitationMm ?: 0.0).coerceAtLeast(0.0)
                        val prob = (hour.precipitationProbabilityPercent ?: 0).coerceIn(0, 100)
                        val barFraction = (mm / maxMm).toFloat().coerceIn(0.04f, 1f)
                        val intensityPt = classifyPrecipitationIntensityPt(mm)
                        val formattedAmount =
                            WeatherUnitConverter.formatPrecipitation(mm, precipitationUnit)
                                ?: "0,0 ${precipitationUnit.symbol}"

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("radar_hourly_rain_row_$idx"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = hour.formattedTime,
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(52.dp),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Color(0x441B4F79)),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(barFraction)
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(99.dp))
                                            .background(Brush.horizontalGradient(ClimaRadarRampColors)),
                                )
                            }
                            Text(
                                text = "$prob% • $formattedAmount ($intensityPt)",
                                color = Color(0xFFD7EEFC),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (frames.isNotEmpty()) {
                HorizontalDivider(color = Color(0x468FE9FF))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "VARREDURAS HISTÓRICAS RAINVIEWER",
                        color = ClimaAccentCyan,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = activeFrame?.formattedLocalTime?.let { "Selecionado: $it" }.orEmpty(),
                        color = ClimaTextMutedOnDark,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    frames.forEachIndexed { idx, frame ->
                        val selected = idx == safeIndex
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) Color(0xFF0F6BA5) else Color(0x660A3354),
                            border =
                                BorderStroke(
                                    1.dp,
                                    if (selected) Color(0xFF80D6FD) else Color(0x55388DCD),
                                ),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectFrame(idx) }
                                    .testTag("radar_historical_frame_chip_$idx"),
                        ) {
                            Text(
                                text = frame.formattedLocalTime,
                                color = if (selected) ClimaTextOnDark else Color(0xFFC7E2F4),
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun classifyPrecipitationIntensityPt(precipitationMmPerHour: Double): String =
    when {
        precipitationMmPerHour <= 0.05 -> "Sem chuva"
        precipitationMmPerHour < 2.5 -> "Fraca"
        precipitationMmPerHour < 7.6 -> "Moderada"
        precipitationMmPerHour < 25.0 -> "Forte"
        else -> "Muito forte"
    }

@Composable
private fun RadarTimelineCard(
    frames: List<com.example.data.model.RainViewerRadarFrame>,
    safeIndex: Int,
    activeFrame: com.example.data.model.RainViewerRadarFrame?,
    isRadarPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSelectFrame: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x9C58C4FF)),
        modifier = Modifier.fillMaxWidth().testTag("radar_timeline_card"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(ClimaRadarTimelineTop, ClimaRadarTimelineBottom)))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = ClimaRadarPlayBg,
                    border = BorderStroke(2.dp, ClimaRadarPlayBorder),
                    modifier = Modifier.size(48.dp).testTag("radar_play_button"),
                ) {
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            painter = painterResource(if (isRadarPlaying) ClimaUiIcons.Pause else ClimaUiIcons.Play),
                            contentDescription =
                                stringResource(
                                    if (isRadarPlaying) R.string.radar_pause_cd else R.string.radar_play_cd,
                                ),
                            tint = ClimaTextOnDark,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.radar_animation_title),
                        color = ClimaTextOnDark,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text =
                            if (frames.isNotEmpty()) {
                                "Quadro ${safeIndex + 1} de ${frames.size} • ${activeFrame?.formattedLocalTime.orEmpty()}"
                            } else {
                                "Nenhum quadro disponível no momento"
                            },
                        color = Color(0xFFC2DFF1),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("radar_frame_counter"),
                    )
                }

                val isLatest = frames.isNotEmpty() && safeIndex == frames.lastIndex
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, ClimaRadarPlayBorder),
                ) {
                    Text(
                        text = if (isLatest) "Mais recente" else activeFrame?.formattedLocalTime ?: "—",
                        color = Color(0xFF61E8FF),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).testTag("radar_frame_badge"),
                    )
                }
            }

            if (frames.size > 1) {
                Slider(
                    value = safeIndex.toFloat(),
                    onValueChange = { onSelectFrame(it.roundToInt()) },
                    valueRange = 0f..frames.lastIndex.toFloat(),
                    steps = (frames.size - 2).coerceAtLeast(0),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = ClimaAccentCyan,
                            activeTrackColor = Color(0xFF00CBFF),
                            inactiveTrackColor = Color(0x5558C4FF),
                        ),
                    modifier = Modifier.fillMaxWidth().testTag("radar_timeline_slider"),
                )

                val axisIndices = remember(frames.size) {
                    listOf(0, frames.size / 4, frames.size / 2, (frames.size * 3) / 4, frames.lastIndex).distinct()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    axisIndices.forEach { idx ->
                        Text(
                            text = frames[idx].formattedLocalTime,
                            color = Color(0xFFCCE9F9),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
