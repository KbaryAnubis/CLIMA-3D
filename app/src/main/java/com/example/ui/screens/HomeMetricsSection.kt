package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AirQualitySnapshot
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.UserFacingWeatherFormatter
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherUnitConverter
import com.example.media.ClimaUiIcons
import com.example.media.WeatherCondition
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.ClimaDateLabels
import com.example.ui.components.V53GlassCard
import com.example.ui.components.V53SectionHeader
import com.example.ui.state.ClimaUiState
import com.example.ui.state.InAppWeatherAlertEngine
import com.example.ui.state.WeatherMetricType
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaAlertHighRed
import com.example.ui.theme.ClimaGoodAirGreen
import com.example.ui.theme.ClimaMetricCardDark
import com.example.ui.theme.ClimaMetricCardLight
import com.example.ui.theme.ClimaRainCyan
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaUvGradientColors
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * V53 Temperature Evolution curve rendered natively with Compose Canvas using real
 * Open-Meteo hourly forecast points.
 */
@Composable
fun HomeTemperatureEvolutionSection(
    forecast: WeatherForecastBundle,
    uiState: ClimaUiState,
    cardAlpha: Float,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val points =
        remember(forecast.hourly) {
            forecast.hourly.take(12).filter { it.temperatureC != null }
        }

    V53GlassCard(
        alpha = cardAlpha,
        modifier = Modifier.testTag("home_temp_evolution_section"),
    ) {
        V53SectionHeader(
            title = stringResource(R.string.home_temp_evolution_title),
            iconRes = ClimaUiIcons.Thermo,
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (points.size < 2) {
            Text(
                text = "Pontos horários insuficientes para desenhar a curva de temperatura.",
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodySmall,
            )
            return@V53GlassCard
        }

        val temps =
            remember(points, prefs.temperatureUnit) {
                points.map { point ->
                    WeatherUnitConverter.convertTemperature(point.temperatureC!!, prefs.temperatureUnit)
                }
            }
        val minT = remember(temps) { temps.minOrNull() ?: 0.0 }
        val maxT = remember(temps, minT) { temps.maxOrNull() ?: (minT + 1.0) }
        val range = remember(minT, maxT) { (maxT - minT).coerceAtLeast(1.0) }

        Surface(
            shape = RoundedCornerShape(15.dp),
            color = if (dark) Color(0x8808233F) else Color(0xDDE5F1FC),
            border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Temperatura e probabilidade de chuva • próximas ${points.size} horas",
                    color = WeatherGlassSpecs.mutedTextColor(dark),
                    style = MaterialTheme.typography.labelMedium,
                )

                val lineColor = if (dark) ClimaAccentCyan else Color(0xFF0E6CB8)
                val gridColor = if (dark) Color(0x3375C2ED) else Color(0x331B598A)
                val nodeFill = if (dark) Color(0xFFB0F6FF) else Color(0xFF0E6CB8)
                val nodeStroke = if (dark) Color(0xFF0B6FB6) else Color.White

                Canvas(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(132.dp)
                            .testTag("home_temp_evolution_canvas"),
                ) {
                    val leftPad = 14.dp.toPx()
                    val rightPad = 14.dp.toPx()
                    val topPad = 14.dp.toPx()
                    val bottomPad = 14.dp.toPx()
                    val chartW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                    val chartH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                    // Horizontal guide lines
                    for (i in 0..3) {
                        val y = topPad + (chartH * i / 3f)
                        drawLine(
                            color = gridColor,
                            start = Offset(leftPad, y),
                            end = Offset(size.width - rightPad, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    val coords =
                        temps.mapIndexed { idx, temp ->
                            val x = leftPad + (chartW * idx / (temps.size - 1).coerceAtLeast(1))
                            val norm = ((temp - minT) / range).toFloat().coerceIn(0f, 1f)
                            val y = topPad + chartH * (1f - norm)
                            Offset(x, y)
                        }

                    val linePath = Path().apply {
                        moveTo(coords.first().x, coords.first().y)
                        for (i in 0 until coords.lastIndex) {
                            val p0 = coords[i]
                            val p1 = coords[i + 1]
                            val midX = (p0.x + p1.x) / 2f
                            cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                        }
                    }

                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(coords.last().x, topPad + chartH)
                        lineTo(coords.first().x, topPad + chartH)
                        close()
                    }

                    drawPath(
                        path = fillPath,
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(lineColor.copy(alpha = 0.28f), Color.Transparent),
                                startY = topPad,
                                endY = topPad + chartH,
                            ),
                    )
                    drawPath(
                        path = linePath,
                        color = lineColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                    )

                    coords.forEach { offset ->
                        drawCircle(color = nodeStroke, radius = 5.dp.toPx(), center = offset)
                        drawCircle(color = nodeFill, radius = 3.2.dp.toPx(), center = offset)
                    }
                }

                // Sampled time & temperature axis labels (start, midpoints, end)
                val axisLabels =
                    remember(
                        points,
                        forecast.timezoneId,
                        forecast.utcOffsetSeconds,
                        prefs.temperatureUnit,
                        prefs.timeFormat,
                    ) {
                        val labelIndices = listOf(0, points.size / 3, (points.size * 2) / 3, points.lastIndex).distinct()
                        labelIndices.map { idx ->
                            val p = points[idx]
                            val timeText =
                                ClimaDateLabels.formatHourLabel(
                                    index = idx,
                                    isoTime = p.timeIso,
                                    epochMillis = p.epochMillis,
                                    timezoneId = forecast.timezoneId,
                                    utcOffsetSeconds = forecast.utcOffsetSeconds,
                                    timeFormat = prefs.timeFormat,
                                )
                            val tempText =
                                WeatherUnitConverter.formatTemperature(p.temperatureC, prefs.temperatureUnit) ?: "—"
                            tempText to timeText
                        }
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    axisLabels.forEach { (tempText, timeText) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = tempText,
                                color = WeatherGlassSpecs.primaryTextColor(dark),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = timeText,
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Exact V53 `.wx27-details` 2-column meteorological metrics grid with native Compose Canvas
 * radial dials for Wind and Pressure, UV gradient scale bar, Precipitation, Humidity, and Visibility.
 */
@Composable
fun HomeMetricsGridSection(
    forecast: WeatherForecastBundle,
    uiState: ClimaUiState,
    cardAlpha: Float,
    onOpenMetric: (WeatherMetricType) -> Unit,
) {
    val prefs = uiState.preferences
    val current = forecast.current
    val today = forecast.daily.firstOrNull()

    V53GlassCard(
        alpha = cardAlpha,
        modifier = Modifier.testTag("home_metrics_section"),
    ) {
        V53SectionHeader(
            title = stringResource(R.string.home_metrics_title),
            iconRes = ClimaUiIcons.Info,
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Row 1: Wind Dial + Pressure Dial
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val windText = WeatherUnitConverter.formatWindSpeed(current.windSpeedKmh, prefs.windSpeedUnit) ?: "—"
                val windDir = InAppWeatherAlertEngine.windDirectionFullNamePt(current.windDirectionDegrees)
                val windAngle = (current.windDirectionDegrees ?: 0).toFloat()

                MetricDialCard(
                    title = "Vento",
                    iconRes = ClimaUiIcons.Wind,
                    valueText = windText,
                    subText = windDir,
                    needleDegrees = windAngle,
                    testTag = "metric_card_wind",
                    onClick = { onOpenMetric(WeatherMetricType.WIND) },
                    modifier = Modifier.weight(1f),
                )

                val pressureHpa = current.pressureMslHpa ?: current.surfacePressureHpa
                val pressureFormatted = WeatherUnitConverter.formatPressure(pressureHpa, prefs.pressureUnit) ?: "—"
                val pressureCategory = InAppWeatherAlertEngine.classifyPressureHpa(current.pressureMslHpa)
                val pressureSub =
                    if (prefs.showPressureStatus && pressureCategory != null) {
                        pressureCategory.labelPt
                    } else if (current.pressureMslHpa != null) {
                        "Nível do mar"
                    } else {
                        "Superfície"
                    }
                val pressureNormalized =
                    pressureHpa?.let { ((it - 980.0) / 60.0).coerceIn(0.0, 1.0) } ?: 0.5
                val pressureAngle = (-135.0 + pressureNormalized * 270.0).toFloat()

                MetricDialCard(
                    title = "Pressão",
                    iconRes = ClimaUiIcons.Pressure,
                    valueText = pressureFormatted,
                    subText = pressureSub,
                    needleDegrees = pressureAngle,
                    testTag = "metric_card_pressure",
                    onClick = { onOpenMetric(WeatherMetricType.PRESSURE) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Row 2: Precipitation + UV Index
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val precipToday = WeatherUnitConverter.formatPrecipitation(today?.precipitationSumMm, prefs.precipitationUnit) ?: "0,0 ${prefs.precipitationUnit.symbol}"
                val precipNow = WeatherUnitConverter.formatPrecipitation(current.precipitationMm, prefs.precipitationUnit) ?: "—"
                val precipProb = today?.precipitationProbabilityMaxPercent?.let { "$it%" } ?: "—"

                MetricSimpleCard(
                    title = "Precipitação",
                    iconRes = ClimaUiIcons.Drop,
                    valueText = precipToday,
                    subtitle = "Previsto para hoje",
                    note = "Agora: $precipNow • Chance máx.: $precipProb",
                    testTag = "metric_card_rain",
                    onClick = { onOpenMetric(WeatherMetricType.PRECIPITATION) },
                    modifier = Modifier.weight(1f),
                )

                val uvValue = current.uvIndex ?: today?.uvIndexMax
                val uvCat = InAppWeatherAlertEngine.classifyUvIndex(uvValue)
                val uvFraction = InAppWeatherAlertEngine.uvProgressFraction(uvValue)

                MetricSimpleCard(
                    title = "Índice UV",
                    iconRes = ClimaUiIcons.Sun,
                    valueText = uvValue?.let { UserFacingWeatherFormatter.formatDecimalPt(it, 1) } ?: "—",
                    subtitle = uvCat?.labelPt ?: "Indisponível",
                    note = uvCat?.advicePt ?: "Sem medição UV disponível no momento.",
                    uvProgress = if (uvValue != null) uvFraction else null,
                    testTag = "metric_card_uv",
                    onClick = { onOpenMetric(WeatherMetricType.UV) },
                    modifier = Modifier.weight(1f),
                )
            }

            // Row 3: Humidity + Visibility
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val humidityText = current.relativeHumidityPercent?.let { "$it%" } ?: "—"
                val dewText = WeatherUnitConverter.formatTemperature(current.dewPointC, prefs.temperatureUnit) ?: "—"

                MetricSimpleCard(
                    title = "Umidade",
                    iconRes = ClimaUiIcons.Drop,
                    valueText = humidityText,
                    subtitle = "Umidade relativa",
                    note = "Ponto de orvalho: $dewText.",
                    testTag = "metric_card_humidity",
                    onClick = { onOpenMetric(WeatherMetricType.HUMIDITY) },
                    modifier = Modifier.weight(1f),
                )

                val visText = WeatherUnitConverter.formatVisibility(current.visibilityMeters, prefs.visibilityUnit) ?: "—"
                val visNote = InAppWeatherAlertEngine.visibilityNotePt(current.visibilityMeters)

                MetricSimpleCard(
                    title = "Visibilidade",
                    iconRes = ClimaUiIcons.Eye,
                    valueText = visText,
                    subtitle = "Alcance visual",
                    note = visNote,
                    testTag = "metric_card_visibility",
                    onClick = { onOpenMetric(WeatherMetricType.VISIBILITY) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetricDialCard(
    title: String,
    iconRes: Int,
    valueText: String,
    subText: String,
    needleDegrees: Float,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalClimaDarkTheme.current
    val tickColor = if (dark) Color(0x95B9C5D0) else Color(0x8838628A)
    val needleColor = if (dark) Color(0xFFF9FDFF) else Color(0xFF0E6CB8)

    Surface(
        modifier = modifier.height(216.dp).testTag(testTag),
        shape = RoundedCornerShape(19.dp),
        color = if (dark) ClimaMetricCardDark else ClimaMetricCardLight,
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick)
                    .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricCardTitleRow(title = title, iconRes = iconRes)

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.size(136.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outerRadius = size.minDimension / 2f - 4.dp.toPx()
                    val innerRadius = outerRadius - 7.dp.toPx()

                    // Repeating radial tick marks reproducing V53 .wx27-dial::before
                    val majorWidth = 2.2.dp.toPx()
                    val minorWidth = 1.2.dp.toPx()
                    DIAL_UNIT_TICKS.forEach { tick ->
                        val start =
                            Offset(
                                x = center.x + innerRadius * tick.cos,
                                y = center.y + innerRadius * tick.sin,
                            )
                        val end =
                            Offset(
                                x = center.x + outerRadius * tick.cos,
                                y = center.y + outerRadius * tick.sin,
                            )
                        drawLine(
                            color = tickColor,
                            start = start,
                            end = end,
                            strokeWidth = if (tick.isMajor) majorWidth else minorWidth,
                            cap = StrokeCap.Round,
                        )
                    }

                    // Pointer needle
                    val needleRad = (needleDegrees - 90f) * PI / 180.0
                    val tip =
                        Offset(
                            x = center.x + ((outerRadius - 10.dp.toPx()) * cos(needleRad)).toFloat(),
                            y = center.y + ((outerRadius - 10.dp.toPx()) * sin(needleRad)).toFloat(),
                        )
                    val tail =
                        Offset(
                            x = center.x + ((outerRadius - 30.dp.toPx()) * cos(needleRad)).toFloat(),
                            y = center.y + ((outerRadius - 30.dp.toPx()) * sin(needleRad)).toFloat(),
                        )
                    drawLine(
                        color = needleColor,
                        start = tail,
                        end = tip,
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = valueText,
                        color = WeatherGlassSpecs.primaryTextColor(dark),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = subText,
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricSimpleCard(
    title: String,
    iconRes: Int,
    valueText: String,
    subtitle: String,
    note: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    uvProgress: Float? = null,
) {
    val dark = LocalClimaDarkTheme.current
    Surface(
        modifier = modifier.height(216.dp).testTag(testTag),
        shape = RoundedCornerShape(19.dp),
        color = if (dark) ClimaMetricCardDark else ClimaMetricCardLight,
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick)
                    .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            MetricCardTitleRow(title = title, iconRes = iconRes)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = valueText,
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.8).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = WeatherGlassSpecs.primaryTextColor(dark).copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )

                if (uvProgress != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(Brush.horizontalGradient(ClimaUvGradientColors)),
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            val clamped = uvProgress.coerceIn(0.02f, 0.96f)
                            Spacer(modifier = Modifier.weight(clamped))
                            Box(
                                modifier =
                                    Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                            )
                            Spacer(modifier = Modifier.weight((1f - clamped).coerceAtLeast(0.02f)))
                        }
                    }
                }
            }

            Text(
                text = note,
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetricCardTitleRow(
    title: String,
    iconRes: Int,
) {
    val dark = LocalClimaDarkTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (dark) Color(0xFF9BC6DA) else Color(0xFF0E6CB8),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = title,
            color = if (dark) Color(0xFFC1CBD5) else Color(0xFF243B53),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            color = if (dark) Color(0xFFC9D2DE) else Color(0xFF486581),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * V53 `Destaques de hoje` 2x2 grid.
 */
@Composable
fun HomeTodayHighlightsSection(
    forecast: WeatherForecastBundle,
    uiState: ClimaUiState,
    cardAlpha: Float,
) {
    val prefs = uiState.preferences
    val today = forecast.daily.firstOrNull()
    val current = forecast.current

    V53GlassCard(
        alpha = cardAlpha,
        modifier = Modifier.testTag("home_highlights_section"),
    ) {
        V53SectionHeader(
            title = stringResource(R.string.home_highlights_title),
            iconRes = ClimaUiIcons.Info,
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HighlightTile(
                    iconRes = ClimaUiIcons.Drop,
                    label = "Chuva prevista hoje",
                    value = WeatherUnitConverter.formatPrecipitation(today?.precipitationSumMm, prefs.precipitationUnit) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                HighlightTile(
                    iconRes = ClimaUiIcons.Drop,
                    label = "Chance de chuva",
                    value = today?.precipitationProbabilityMaxPercent?.let { "$it%" } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HighlightTile(
                    iconRes = ClimaUiIcons.Sun,
                    label = "Nascer do sol",
                    value = ClimaDateLabels.formatSunTime(today?.sunriseIso),
                    modifier = Modifier.weight(1f),
                )
                HighlightTile(
                    iconRes = ClimaUiIcons.Moon,
                    label = "Pôr do sol",
                    value = ClimaDateLabels.formatSunTime(today?.sunsetIso),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HighlightTile(
                    iconRes = ClimaUiIcons.Thermo,
                    label = "Ponto de orvalho",
                    value = WeatherUnitConverter.formatTemperature(current.dewPointC, prefs.temperatureUnit) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                HighlightTile(
                    iconRes = ClimaUiIcons.Wind,
                    label = "Rajada máxima",
                    value = WeatherUnitConverter.formatWindSpeed(today?.windGustsMaxKmh ?: current.windGustsKmh, prefs.windSpeedUnit) ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HighlightTile(
    iconRes: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val dark = LocalClimaDarkTheme.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        color = if (dark) Color(0x880B2D4E) else Color(0xDDE4F1FC),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = WeatherGlassSpecs.accentColor(dark),
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    color = WeatherGlassSpecs.mutedTextColor(dark),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * V53 `Qualidade do ar` section driven by real Open-Meteo Air Quality API data.
 */
@Composable
fun HomeAirQualitySection(
    airQuality: AirQualitySnapshot?,
    cardAlpha: Float,
) {
    val dark = LocalClimaDarkTheme.current
    V53GlassCard(
        alpha = cardAlpha,
        modifier = Modifier.testTag("home_air_quality_section"),
    ) {
        V53SectionHeader(
            title = stringResource(R.string.home_air_quality_title),
            iconRes = ClimaUiIcons.Cloud,
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (airQuality == null || !airQuality.hasAnyIndicator()) {
            Text(
                text = "Qualidade do ar indisponível no momento para esta localização.",
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodySmall,
            )
            return@V53GlassCard
        }

        val category = airQuality.qualityCategoryPt() ?: "Disponível"
        val primaryScore = airQuality.usAqi ?: airQuality.europeanAqi
        val scoreLabel =
            when {
                airQuality.usAqi != null -> "$category (US AQI ${airQuality.usAqi})"
                airQuality.europeanAqi != null -> "$category (EAQI ${airQuality.europeanAqi})"
                else -> category
            }
        val statusColor =
            when {
                primaryScore == null -> ClimaGoodAirGreen
                primaryScore <= 50 -> ClimaGoodAirGreen
                primaryScore <= 100 -> ClimaAlertAttentionAmber
                else -> ClimaAlertHighRed
            }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (dark) Color(0xB30D385F) else Color(0xDDE4F1FC),
            border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = scoreLabel,
                    color = if (dark) statusColor else Color(0xFF102A43),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                )

                // Colored quality bar
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Brush.horizontalGradient(ClimaUvGradientColors)),
                )

                val chips =
                    listOfNotNull(
                        airQuality.pm25UgM3?.let { "PM2.5: ${UserFacingWeatherFormatter.formatDecimalPt(it)} µg/m³" },
                        airQuality.pm10UgM3?.let { "PM10: ${UserFacingWeatherFormatter.formatDecimalPt(it)} µg/m³" },
                        airQuality.ozoneUgM3?.let { "O₃: ${UserFacingWeatherFormatter.formatDecimalPt(it)} µg/m³" },
                        airQuality.nitrogenDioxideUgM3?.let { "NO₂: ${UserFacingWeatherFormatter.formatDecimalPt(it)} µg/m³" },
                    )
                if (chips.isNotEmpty()) {
                    Text(
                        text = chips.joinToString(separator = "  •  "),
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * V53 `Alertas meteorológicos` summary card on Home linking to the internal Alerts screen.
 */
@Composable
fun HomeAlertsSummarySection(
    uiState: ClimaUiState,
    cardAlpha: Float,
    onOpenAlerts: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val activeAlerts = uiState.activeAlerts
    val primaryAlert = activeAlerts.firstOrNull()

    V53GlassCard(
        alpha = cardAlpha,
        modifier = Modifier.testTag("home_alerts_section"),
    ) {
        V53SectionHeader(
            title = stringResource(R.string.home_alerts_title),
            iconRes = ClimaUiIcons.Alert,
            actionLabel = stringResource(R.string.home_open_action),
            actionTestTag = "home_open_alerts_header_button",
            onActionClick = onOpenAlerts,
        )
        Spacer(modifier = Modifier.height(10.dp))

        val borderColor =
            when {
                primaryAlert != null -> ClimaAlertAttentionAmber.copy(alpha = 0.65f)
                else -> WeatherGlassSpecs.glassBorderColor(dark)
            }

        Surface(
            shape = RoundedCornerShape(15.dp),
            color =
                if (primaryAlert != null) {
                    if (dark) Color(0x8842260F) else Color(0xFFFEEFD8)
                } else {
                    if (dark) Color(0x880B2D4E) else Color(0xFFE4F1FC)
                },
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth().testTag("home_alert_summary_card"),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAlerts)
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedWeatherIcon(
                    condition = primaryAlert?.condition ?: uiState.activeWeatherCondition,
                    size = WeatherIconSize.Alert,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text =
                            if (primaryAlert != null) {
                                if (activeAlerts.size == 1) {
                                    primaryAlert.title
                                } else {
                                    "${activeAlerts.size} alertas ativos • ${primaryAlert.title}"
                                }
                            } else {
                                "Nenhum alerta meteorológico ativo"
                            },
                        color = WeatherGlassSpecs.primaryTextColor(dark),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text =
                            primaryAlert?.observedMetricSummary
                                ?: "Avisos exibidos apenas dentro do aplicativo, com base nos dados reais.",
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painter = painterResource(ClimaUiIcons.Right),
                    contentDescription = stringResource(R.string.home_open_action),
                    tint = WeatherGlassSpecs.accentColor(dark),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private data class DialUnitTick(
    val cos: Float,
    val sin: Float,
    val isMajor: Boolean,
)

private val DIAL_UNIT_TICKS: List<DialUnitTick> =
    (0 until 360 step 10).map { deg ->
        val rad = deg * PI / 180.0
        DialUnitTick(
            cos = cos(rad).toFloat(),
            sin = sin(rad).toFloat(),
            isMajor = deg % 30 == 0,
        )
    }

