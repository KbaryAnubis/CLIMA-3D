package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.DailyForecastPoint
import com.example.data.model.HourlyForecastPoint
import com.example.data.model.UserFacingWeatherFormatter
import com.example.data.model.WeatherUnitConverter
import com.example.media.ClimaUiIcons
import com.example.media.WeatherIconSize
import com.example.ui.state.ClimaActiveModal
import com.example.ui.state.ClimaUiState
import com.example.ui.state.InAppWeatherAlertEngine
import com.example.ui.state.PressureCategory
import com.example.ui.state.WeatherMetricType
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaPressureHigh
import com.example.ui.theme.ClimaPressureLow
import com.example.ui.theme.ClimaPressureNormal
import com.example.ui.theme.ClimaTempMaxCoral
import com.example.ui.theme.ClimaTempMinCyan
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs
import kotlin.math.roundToInt

@Composable
fun ClimaDetailModalHost(
    uiState: ClimaUiState,
    onDismiss: () -> Unit,
    onOpenPressureInfo: () -> Unit,
) {
    val modal = uiState.activeModal
    if (
        modal == ClimaActiveModal.None ||
        modal == ClimaActiveModal.LocationPicker ||
        modal == ClimaActiveModal.MapLocationPicker
    ) return

    val dark = LocalClimaDarkTheme.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .testTag("clima_detail_modal"),
            shape = RoundedCornerShape(22.dp),
            color = if (dark) Color(0xF508213B) else Color(0xF8F2F8FF),
            border = BorderStroke(1.dp, if (dark) Color(0x8852DBFF) else Color(0x660E6CB8)),
            shadowElevation = 16.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (modal) {
                    ClimaActiveModal.None,
                    ClimaActiveModal.LocationPicker,
                    ClimaActiveModal.MapLocationPicker -> Unit
                    is ClimaActiveModal.HourDetail ->
                        HourDetailModalContent(
                            uiState = uiState,
                            index = modal.index,
                            point = modal.point,
                        )

                    is ClimaActiveModal.DayDetail ->
                        DayDetailModalContent(
                            uiState = uiState,
                            index = modal.index,
                            point = modal.point,
                        )

                    is ClimaActiveModal.MetricDetail ->
                        MetricDetailModalContent(
                            uiState = uiState,
                            metric = modal.metric,
                            onOpenPressureInfo = onOpenPressureInfo,
                        )

                    ClimaActiveModal.PressureInfo ->
                        PressureInfoModalContent(uiState = uiState)
                }

                Spacer(modifier = Modifier.height(2.dp))
                V53PrimaryButton(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                    secondary = true,
                    modifier = Modifier.testTag("modal_close_button"),
                )
            }
        }
    }
}

@Composable
private fun HourDetailModalContent(
    uiState: ClimaUiState,
    index: Int,
    point: HourlyForecastPoint,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val forecast = uiState.forecast
    val timeLabel =
        ClimaDateLabels.formatHourLabel(
            index = index,
            isoTime = point.timeIso,
            epochMillis = point.epochMillis,
            timezoneId = forecast?.timezoneId,
            utcOffsetSeconds = forecast?.utcOffsetSeconds ?: 0,
            timeFormat = prefs.timeFormat,
        )

    Row(
        modifier = Modifier.fillMaxWidth().testTag("hour_detail_modal_content"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedWeatherIcon(
            condition = point.visualCondition,
            size = WeatherIconSize.Modal,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "Previsão às $timeLabel",
                color = WeatherGlassSpecs.accentColor(dark),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = WeatherUnitConverter.formatTemperature(point.temperatureC, prefs.temperatureUnit) ?: "—",
                color = WeatherGlassSpecs.primaryTextColor(dark),
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = point.weatherDescriptionPt,
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    HorizontalDivider(color = WeatherGlassSpecs.glassBorderColor(dark))

    ModalMetricRow(
        label = "Sensação térmica",
        value = WeatherUnitConverter.formatTemperature(point.apparentTemperatureC, prefs.temperatureUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Chance de chuva",
        value = point.precipitationProbabilityPercent?.let { "$it%" } ?: "—",
    )
    ModalMetricRow(
        label = "Precipitação",
        value = WeatherUnitConverter.formatPrecipitation(point.precipitationMm, prefs.precipitationUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Vento",
        value =
            buildString {
                append(WeatherUnitConverter.formatWindSpeed(point.windSpeedKmh, prefs.windSpeedUnit) ?: "—")
                val dir = InAppWeatherAlertEngine.windDirectionFullNamePt(point.windDirectionDegrees)
                if (dir != "—") append(" ($dir)")
            },
    )
    ModalMetricRow(
        label = "Rajadas",
        value = WeatherUnitConverter.formatWindSpeed(point.windGustsKmh, prefs.windSpeedUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Umidade relativa",
        value = point.relativeHumidityPercent?.let { "$it%" } ?: "—",
    )
    ModalMetricRow(
        label = "Ponto de orvalho",
        value = WeatherUnitConverter.formatTemperature(point.dewPointC, prefs.temperatureUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Pressão atmosférica",
        value = WeatherUnitConverter.formatPressure(point.pressureMslHpa, prefs.pressureUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Índice UV",
        value = point.uvIndex?.let { UserFacingWeatherFormatter.formatDecimalPt(it, 1) } ?: "—",
    )
    ModalMetricRow(
        label = "Visibilidade",
        value = WeatherUnitConverter.formatVisibility(point.visibilityMeters, prefs.visibilityUnit) ?: "—",
    )
}

@Composable
private fun DayDetailModalContent(
    uiState: ClimaUiState,
    index: Int,
    point: DailyForecastPoint,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val (weekday, dateText) = ClimaDateLabels.formatDailyWeekdayAndDate(index, point.dateIso)

    Row(
        modifier = Modifier.fillMaxWidth().testTag("day_detail_modal_content"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedWeatherIcon(
            condition = point.visualCondition,
            size = WeatherIconSize.Modal,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "$weekday • $dateText",
                color = WeatherGlassSpecs.primaryTextColor(dark),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = point.weatherDescriptionPt,
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "↑ ${WeatherUnitConverter.formatTemperature(point.temperatureMaxC, prefs.temperatureUnit) ?: "—"}",
                    color = ClimaTempMaxCoral,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "↓ ${WeatherUnitConverter.formatTemperature(point.temperatureMinC, prefs.temperatureUnit) ?: "—"}",
                    color = ClimaTempMinCyan,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    HorizontalDivider(color = WeatherGlassSpecs.glassBorderColor(dark))

    ModalMetricRow(
        label = "Sensação (máx. / mín.)",
        value =
            "${WeatherUnitConverter.formatTemperature(point.apparentTemperatureMaxC, prefs.temperatureUnit) ?: "—"} / " +
                (WeatherUnitConverter.formatTemperature(point.apparentTemperatureMinC, prefs.temperatureUnit) ?: "—"),
    )
    ModalMetricRow(
        label = "Probabilidade de chuva",
        value = point.precipitationProbabilityMaxPercent?.let { "$it%" } ?: "—",
    )
    ModalMetricRow(
        label = "Volume acumulado",
        value = WeatherUnitConverter.formatPrecipitation(point.precipitationSumMm, prefs.precipitationUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Vento máximo",
        value =
            buildString {
                append(WeatherUnitConverter.formatWindSpeed(point.windSpeedMaxKmh, prefs.windSpeedUnit) ?: "—")
                val dir = InAppWeatherAlertEngine.windDirectionFullNamePt(point.windDirectionDominantDegrees)
                if (dir != "—") append(" ($dir)")
            },
    )
    ModalMetricRow(
        label = "Rajadas máximas",
        value = WeatherUnitConverter.formatWindSpeed(point.windGustsMaxKmh, prefs.windSpeedUnit) ?: "—",
    )
    ModalMetricRow(
        label = "Índice UV máximo",
        value =
            point.uvIndexMax?.let { uv ->
                val cat = InAppWeatherAlertEngine.classifyUvIndex(uv)?.labelPt.orEmpty()
                "${UserFacingWeatherFormatter.formatDecimalPt(uv, 1)} ($cat)"
            } ?: "—",
    )
    ModalMetricRow(
        label = "Nascer do sol",
        value = ClimaDateLabels.formatSunTime(point.sunriseIso),
    )
    ModalMetricRow(
        label = "Pôr do sol",
        value = ClimaDateLabels.formatSunTime(point.sunsetIso),
    )
}

@Composable
private fun MetricDetailModalContent(
    uiState: ClimaUiState,
    metric: WeatherMetricType,
    onOpenPressureInfo: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val prefs = uiState.preferences
    val current = uiState.forecast?.current
    val today = uiState.forecast?.daily?.firstOrNull()

    Text(
        text = metric.titlePt,
        color = WeatherGlassSpecs.primaryTextColor(dark),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag("metric_detail_modal_title"),
    )

    val (valueSummary, explanation) =
        when (metric) {
            WeatherMetricType.WIND -> {
                val speed = WeatherUnitConverter.formatWindSpeed(current?.windSpeedKmh, prefs.windSpeedUnit) ?: "—"
                val gust = WeatherUnitConverter.formatWindSpeed(current?.windGustsKmh, prefs.windSpeedUnit) ?: "—"
                val dir = InAppWeatherAlertEngine.windDirectionFullNamePt(current?.windDirectionDegrees)
                "$speed • Direção: $dir • Rajadas: $gust" to
                    "Velocidade e direção reais do vento obtidas do Open-Meteo. A direção indica de onde o vento sopra."
            }

            WeatherMetricType.PRESSURE -> {
                val hpa = current?.pressureMslHpa ?: current?.surfacePressureHpa
                val formatted = WeatherUnitConverter.formatPressure(hpa, prefs.pressureUnit) ?: "—"
                val cat = InAppWeatherAlertEngine.classifyPressureHpa(current?.pressureMslHpa)?.labelPt
                val suffix = if (cat != null) " ($cat)" else ""
                "$formatted$suffix" to
                    "Pressão atmosférica reduzida ao nível do mar. Faixas indicativas: baixa abaixo de 1008 hPa, normal de 1008 a 1019 hPa e alta a partir de 1020 hPa."
            }

            WeatherMetricType.PRECIPITATION -> {
                val nowRain = WeatherUnitConverter.formatPrecipitation(current?.precipitationMm, prefs.precipitationUnit) ?: "—"
                val dayRain = WeatherUnitConverter.formatPrecipitation(today?.precipitationSumMm, prefs.precipitationUnit) ?: "—"
                "Agora: $nowRain • Hoje: $dayRain" to
                    "Volume real de precipitação atual e acumulado previsto para hoje. Precipitação (mm/in) e probabilidade de chuva (%) são métricas distintas."
            }

            WeatherMetricType.UV -> {
                val uv = current?.uvIndex ?: today?.uvIndexMax
                val cat = InAppWeatherAlertEngine.classifyUvIndex(uv)
                val uvText = uv?.let { "${UserFacingWeatherFormatter.formatDecimalPt(it, 1)} (${cat?.labelPt.orEmpty()})" } ?: "—"
                uvText to
                    "Índice de radiação ultravioleta atual. Mesmo sob nuvens, parte significativa da radiação UV atinge a superfície."
            }

            WeatherMetricType.HUMIDITY -> {
                val hum = current?.relativeHumidityPercent?.let { "$it%" } ?: "—"
                val dew = WeatherUnitConverter.formatTemperature(current?.dewPointC, prefs.temperatureUnit) ?: "—"
                "$hum • Ponto de orvalho: $dew" to
                    "Percentual relativo de vapor d'água no ar. Quanto mais próximo o ponto de orvalho está da temperatura do ar, maior a sensação de abafamento."
            }

            WeatherMetricType.VISIBILITY -> {
                val vis = WeatherUnitConverter.formatVisibility(current?.visibilityMeters, prefs.visibilityUnit) ?: "—"
                vis to
                    "Distância horizontal estimada de visão clara. Chuva forte, neblina ou alta concentração de partículas reduzem esse valor."
            }
        }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (dark) Color(0x990D3559) else Color(0xFFDCECF9),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = valueSummary,
            color = WeatherGlassSpecs.accentColor(dark),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
    }

    Text(
        text = explanation,
        color = WeatherGlassSpecs.mutedTextColor(dark),
        style = MaterialTheme.typography.bodyMedium,
    )

    if (metric == WeatherMetricType.PRESSURE) {
        V53PrimaryButton(
            text = stringResource(R.string.settings_pressure_info_btn),
            iconRes = ClimaUiIcons.Info,
            onClick = onOpenPressureInfo,
            modifier = Modifier.testTag("metric_modal_pressure_info_button"),
        )
    }
}

@Composable
private fun PressureInfoModalContent(uiState: ClimaUiState) {
    val dark = LocalClimaDarkTheme.current
    val current = uiState.forecast?.current
    val seaLevelHpa = current?.pressureMslHpa
    val surfaceHpa = current?.surfacePressureHpa
    val category = InAppWeatherAlertEngine.classifyPressureHpa(seaLevelHpa)

    Text(
        text = "Pressão atmosférica",
        color = WeatherGlassSpecs.primaryTextColor(dark),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.testTag("pressure_info_modal_title"),
    )

    Text(
        text = "É a força exercida pelo ar sobre a superfície. O valor de referência é a pressão reduzida ao nível do mar, em hectopascais (hPa).",
        color = WeatherGlassSpecs.mutedTextColor(dark),
        style = MaterialTheme.typography.bodyMedium,
    )

    PressureLegendGrid()

    Text(
        text = "Essas faixas são indicativas, não limites meteorológicos universais. A tendência da pressão e o contexto regional importam mais do que um valor isolado; pressão baixa pode acompanhar instabilidade e pressão alta costuma acompanhar estabilidade.",
        color = WeatherGlassSpecs.mutedTextColor(dark),
        style = MaterialTheme.typography.bodySmall,
    )

    val readingNotice =
        when {
            seaLevelHpa != null -> {
                val formatted = WeatherUnitConverter.formatPressure(seaLevelHpa, uiState.preferences.pressureUnit) ?: "${seaLevelHpa.roundToInt()} hPa"
                "Leitura atual em ${uiState.activeLocationName}: $formatted (${category?.labelPt ?: "—"}) — pressão reduzida ao nível do mar (Open-Meteo)."
            }

            surfaceHpa != null -> {
                val formatted = WeatherUnitConverter.formatPressure(surfaceHpa, uiState.preferences.pressureUnit) ?: "${surfaceHpa.roundToInt()} hPa"
                "Apenas pressão na altitude local disponível no momento: $formatted."
            }

            else -> "Aguardando leitura de pressão atmosférica do Open-Meteo para ${uiState.activeLocationName}."
        }

    V53NoticeBanner(text = readingNotice, iconRes = ClimaUiIcons.Pressure)
}

@Composable
fun PressureLegendGrid(modifier: Modifier = Modifier) {
    val dark = LocalClimaDarkTheme.current
    val bands =
        listOf(
            Triple("Baixa", "< 1008 hPa", ClimaPressureLow),
            Triple("Normal", "1008–1019 hPa", ClimaPressureNormal),
            Triple("Alta", "≥ 1020 hPa", ClimaPressureHigh),
        )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bands.forEach { (title, range, color) ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = if (dark) Color(0x8C0A2945) else Color(0xFFE0EEF9),
                border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = title,
                        color = if (dark) color else Color(0xFF102A43),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = range,
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun PressureCategoryPill(
    category: PressureCategory,
    modifier: Modifier = Modifier,
) {
    val tint =
        when (category) {
            PressureCategory.LOW -> ClimaPressureLow
            PressureCategory.NORMAL -> ClimaPressureNormal
            PressureCategory.HIGH -> ClimaPressureHigh
        }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(99.dp),
        color = tint.copy(alpha = 0.20f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.72f)),
    ) {
        Text(
            text = category.labelPt,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ModalMetricRow(
    label: String,
    value: String,
) {
    val dark = LocalClimaDarkTheme.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = WeatherGlassSpecs.mutedTextColor(dark),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = value,
            color = WeatherGlassSpecs.primaryTextColor(dark),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
