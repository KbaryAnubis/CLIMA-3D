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
import com.example.maps.RadarMapLibreView
import com.example.media.ClimaUiIcons
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.V53NoticeBanner
import com.example.ui.state.ClimaUiState
import com.example.ui.theme.ClimaAccentCyan
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
 * Complete native reproduction of CLIMA 3D V53 `wx28-radar` screen:
 * Header -> Location pill -> Summary + 8-stop precipitation intensity ramp ->
 * Interactive MapLibre + OSM + RainViewer map with layer selector & zoom buttons ->
 * Real historical RainViewer 9-frame timeline scrubber & play/pause ->
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
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val forecast = uiState.forecast
    val radar = uiState.radarTimeline
    val frames = radar?.frames.orEmpty()
    val safeIndex = uiState.currentRadarFrameIndexSafe
    val activeFrame = frames.getOrNull(safeIndex)
    val maxRadarZoom = radar?.maxSupportedZoom ?: RadarTimelineSnapshot.PUBLIC_MAX_ZOOM

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
            // 1. V53 .wx28-radar-head
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
                                    Icon(
                                        painter = painterResource(ClimaUiIcons.Refresh),
                                        contentDescription = stringResource(R.string.home_refresh_cd),
                                        tint = ClimaTextOnDark,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        // Location pill (.wx28-radar-location)
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
                                            if (uiState.activeLocationSubtitle.isNotBlank()) {
                                                "${uiState.activeLocationName} • ${uiState.activeLocationSubtitle}"
                                            } else {
                                                uiState.activeLocationName
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
                                            frames.isNotEmpty() -> {
                                                val originLabel =
                                                    if (uiState.radarOrigin == DataSourceOrigin.LOCAL_CACHE) {
                                                        "Metadados em cache"
                                                    } else {
                                                        "RainViewer ao vivo"
                                                    }
                                                "$originLabel • ${frames.size} quadros históricos • Zoom máx. camada $maxRadarZoom"
                                            }

                                            uiState.radarErrorMessage != null -> uiState.radarErrorMessage
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
            // 2. V53 .wx28-radar-summary + 8-stop intensity ramp
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
                            // Left: current icon + temp + condition
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AnimatedWeatherIcon(
                                    condition = uiState.activeWeatherCondition,
                                    size = WeatherIconSize.Radar,
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = WeatherUnitConverter.formatTemperature(forecast?.current?.temperatureC, prefs.temperatureUnit) ?: "—",
                                        color = ClimaTextOnDark,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = forecast?.current?.weatherDescriptionPt ?: stringResource(uiState.activeWeatherCondition.labelRes),
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

                            // Right: current precipitation rate
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
                                        prefs.precipitationUnit,
                                    ) ?: "0,0 ${prefs.precipitationUnit.symbol}"
                                Text(
                                    text = "$rainRate/h",
                                    color = ClimaTextOnDark,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = if (uiState.forecastOrigin == DataSourceOrigin.LOCAL_CACHE) "Cache local" else "Open-Meteo",
                                    color = Color(0xFFBDDDEE),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0x468FE9FF))

                        // V53 .wx28-radar-scale (8-stop intensity gradient ramp)
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
            // 3. V53 .wx28-radar-map (Interactive MapLibre + OSM + RainViewer overlay)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF062941),
                    border = BorderStroke(1.dp, Color(0x6E5F9ED1)),
                    modifier = Modifier.fillMaxWidth().height(380.dp).testTag("radar_map_container"),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RadarMapLibreView(
                            center = uiState.activeLocationCoordinate,
                            zoom = uiState.radarZoom,
                            activeFrame = activeFrame,
                            layerStyle = uiState.radarLayer,
                            maxRadarZoom = maxRadarZoom,
                            isOnline = uiState.isOnline,
                            radarErrorMessage = uiState.radarErrorMessage,
                            onRetryReload = onRefreshRadar,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Top-left layer selector pill + popover menu
                        Column(
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(13.dp),
                                color = Color(0xE806233D),
                                border = BorderStroke(1.dp, Color(0xFF73CAFD)),
                                modifier = Modifier.testTag("radar_layer_button"),
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .minimumInteractiveComponentSize()
                                            .clickable(onClick = onToggleLayerMenu)
                                            .padding(horizontal = 11.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(ClimaUiIcons.Layers),
                                        contentDescription = stringResource(R.string.radar_layers_label),
                                        tint = ClimaAccentCyan,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text =
                                            if (uiState.radarLayer == RadarBaseStyle.PRECIPITATION_MAP) {
                                                stringResource(R.string.radar_layer_precip)
                                            } else {
                                                stringResource(R.string.radar_layer_base)
                                            },
                                        color = ClimaTextOnDark,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }

                            if (uiState.radarLayerMenuExpanded) {
                                Surface(
                                    shape = RoundedCornerShape(13.dp),
                                    color = Color(0xF506233D),
                                    border = BorderStroke(1.dp, Color(0xFF73CAFD)),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier.testTag("radar_layer_menu"),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp).width(190.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.radar_layers_label),
                                            color = Color(0xFFE1F6FF),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        RadarLayerMenuItem(
                                            label = stringResource(R.string.radar_layer_precip),
                                            selected = uiState.radarLayer == RadarBaseStyle.PRECIPITATION_MAP,
                                            testTag = "radar_layer_option_precip",
                                            onClick = { onSelectLayer(RadarBaseStyle.PRECIPITATION_MAP) },
                                        )
                                        RadarLayerMenuItem(
                                            label = stringResource(R.string.radar_layer_base),
                                            selected = uiState.radarLayer == RadarBaseStyle.SATELLITE,
                                            testTag = "radar_layer_option_base",
                                            onClick = { onSelectLayer(RadarBaseStyle.SATELLITE) },
                                        )
                                    }
                                }
                            }
                        }

                        // Top-right Zoom +/- controls
                        Column(
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadarZoomButton(
                                glyph = "+",
                                contentDescription = stringResource(R.string.cities_map_zoom_in),
                                testTag = "radar_zoom_in",
                                onClick = onZoomIn,
                            )
                            RadarZoomButton(
                                glyph = "−",
                                contentDescription = stringResource(R.string.cities_map_zoom_out),
                                testTag = "radar_zoom_out",
                                onClick = onZoomOut,
                            )
                        }

                        // Bottom-left attribution badge
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).testTag("radar_attribution_badge"),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xC9001B31),
                            border = BorderStroke(1.dp, Color(0x5570CBF6)),
                        ) {
                            Text(
                                text = "© OpenStreetMap • RainViewer • Zoom ${uiState.radarZoom.roundToInt()}",
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            // 4. V53 .wx28-radar-timeline (Play/Pause + Slider + real historical timestamps)
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
                            // 43dp circular play/pause button
                            Surface(
                                shape = CircleShape,
                                color = ClimaRadarPlayBg,
                                border = BorderStroke(2.dp, ClimaRadarPlayBorder),
                                modifier = Modifier.size(48.dp).testTag("radar_play_button"),
                            ) {
                                IconButton(onClick = onTogglePlay) {
                                    Icon(
                                        painter = painterResource(if (uiState.isRadarPlaying) ClimaUiIcons.Pause else ClimaUiIcons.Play),
                                        contentDescription =
                                            stringResource(
                                                if (uiState.isRadarPlaying) R.string.radar_pause_cd else R.string.radar_play_cd,
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
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
            // 5. Preserved RadarLayerStatusCard + honest RainViewer public coverage notice
            RadarLayerStatusCard()

            V53NoticeBanner(
                text = "A API pública do RainViewer fornece quadros históricos reais de precipitação (sem previsão futura/nowcast) com zoom máximo de camada $maxRadarZoom.",
                iconRes = ClimaUiIcons.Info,
            )
        }
    }
}

@Composable
private fun RadarLayerMenuItem(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Color(0xFF0F6BA5) else Color(0xFF0A3354),
        border = BorderStroke(1.dp, if (selected) Color(0xFF80D6FD) else Color(0xFF388DCD)),
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Text(
            text = label,
            color = if (selected) ClimaTextOnDark else Color(0xFFD8E9F5),
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun RadarZoomButton(
    glyph: String,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = ClimaDeepSky.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, Color(0x8870CBF6)),
        modifier = Modifier.size(48.dp).testTag(testTag),
    ) {
        IconButton(onClick = onClick) {
            Text(
                text = glyph,
                color = ClimaTextOnDark,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
