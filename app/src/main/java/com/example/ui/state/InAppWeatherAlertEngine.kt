package com.example.ui.state

import androidx.compose.runtime.Immutable
import com.example.data.model.AirQualitySnapshot
import com.example.data.model.UserFacingWeatherFormatter
import com.example.data.model.WeatherForecastBundle
import com.example.data.model.WeatherUnitConverter
import com.example.media.WeatherCondition
import kotlin.math.roundToInt

enum class PressureCategory(val labelPt: String) {
    LOW("Baixa"),
    NORMAL("Normal"),
    HIGH("Alta"),
}

enum class UvCategory(val labelPt: String, val advicePt: String) {
    LOW("Baixo", "Risco UV baixo no momento."),
    MODERATE("Moderado", "Use óculos de sol e protetor solar em exposição prolongada."),
    HIGH("Alto", "Use proteção solar e evite sol direto nas horas de pico."),
    VERY_HIGH("Muito alto", "Proteção solar reforçada recomendada; procure sombra."),
    EXTREME("Extremo", "Evite exposição direta ao sol; proteção máxima necessária."),
}

enum class AlertSeverity(val badgePt: String) {
    HIGH("SEVERIDADE ALTA"),
    ATTENTION("ATENÇÃO"),
    INFO("INFORMATIVO"),
}

@Immutable
data class InAppWeatherAlert(
    val id: String,
    val title: String,
    val description: String,
    val severity: AlertSeverity,
    val condition: WeatherCondition,
    val locationName: String,
    val isActive: Boolean,
    val observedMetricSummary: String,
    val sourceLabel: String = "Derivado de dados reais Open-Meteo",
)

/**
 * Pure meteorological classification and strictly in-app alert derivation based exclusively
 * on real Open-Meteo Forecast & Air Quality data. Never fabricates demo alerts as active warnings.
 */
object InAppWeatherAlertEngine {

    fun classifyPressureHpa(pressureHpa: Double?): PressureCategory? {
        if (pressureHpa == null) return null
        return when {
            pressureHpa < 1008.0 -> PressureCategory.LOW
            pressureHpa >= 1020.0 -> PressureCategory.HIGH
            else -> PressureCategory.NORMAL
        }
    }

    fun classifyUvIndex(uv: Double?): UvCategory? {
        if (uv == null) return null
        return when {
            uv < 3.0 -> UvCategory.LOW
            uv < 6.0 -> UvCategory.MODERATE
            uv < 8.0 -> UvCategory.HIGH
            uv < 11.0 -> UvCategory.VERY_HIGH
            else -> UvCategory.EXTREME
        }
    }

    fun uvProgressFraction(uv: Double?): Float {
        if (uv == null) return 0f
        return (uv / 11.0).toFloat().coerceIn(0f, 1f)
    }

    fun windDirectionFullNamePt(degrees: Int?): String {
        if (degrees == null) return "—"
        val normalized = ((degrees % 360) + 360) % 360
        val names =
            listOf(
                "Norte",
                "Nordeste",
                "Leste",
                "Sudeste",
                "Sul",
                "Sudoeste",
                "Oeste",
                "Noroeste",
            )
        val index = ((normalized + 22.5) / 45.0).toInt() % 8
        return names[index]
    }

    fun visibilityNotePt(meters: Double?): String {
        if (meters == null) return "Dados de visibilidade indisponíveis no momento."
        val km = meters / 1000.0
        return when {
            km < 1.0 -> "Visibilidade muito reduzida (neblina densa ou chuva intensa)."
            km < 4.0 -> "Visibilidade reduzida pela nebulosidade, neblina ou precipitação."
            km < 10.0 -> "Visibilidade moderada na região."
            else -> "Boa visibilidade atmosférica prevista."
        }
    }

    /**
     * Derives strictly in-app alerts from real meteorological thresholds.
     * Returns an empty list whenever conditions are within normal ranges.
     */
    fun evaluateAlerts(
        locationName: String,
        forecast: WeatherForecastBundle?,
        airQuality: AirQualitySnapshot?,
    ): List<InAppWeatherAlert> {
        val alerts = mutableListOf<InAppWeatherAlert>()
        if (forecast != null) {
            val current = forecast.current
            val today = forecast.daily.firstOrNull()
            val next12Hours = forecast.hourly.take(12)

            // 1. Thunderstorm / Heavy rain (active now or within next 12h)
            val stormCodes = setOf(65, 82, 95, 96, 99)
            val currentIsStorm = current.weatherCode in stormCodes || (current.precipitationMm ?: 0.0) >= 7.5
            val upcomingStormHour =
                next12Hours.firstOrNull { (it.weatherCode in stormCodes) || (it.precipitationMm ?: 0.0) >= 7.5 }
            val heavyDailyRainMm = today?.precipitationSumMm ?: 0.0

            if (currentIsStorm || upcomingStormHour != null || heavyDailyRainMm >= 25.0) {
                val peakMm =
                    maxOf(
                        current.precipitationMm ?: 0.0,
                        upcomingStormHour?.precipitationMm ?: 0.0,
                        heavyDailyRainMm,
                    )
                alerts +=
                    InAppWeatherAlert(
                        id = "rain_storm_${forecast.fetchedAtEpochMillis}",
                        title = if (current.weatherCode in setOf(95, 96, 99) || upcomingStormHour?.weatherCode in setOf(95, 96, 99)) {
                            "Tempestade com raios ou chuva intensa"
                        } else {
                            "Chuva forte prevista"
                        },
                        description =
                            "Os dados atuais do Open-Meteo indicam precipitação elevada ou tempestade para $locationName. " +
                                "Evite áreas alagáveis e acompanhe a evolução no Radar.",
                        severity = AlertSeverity.HIGH,
                        condition = WeatherCondition.TEMPESTADE,
                        locationName = locationName,
                        isActive = true,
                        observedMetricSummary = "Acumulado/pico: ${UserFacingWeatherFormatter.formatDecimalPt(peakMm)} mm",
                    )
            }

            // 2. Strong wind gusts (>= 50 km/h)
            val maxGustKmh =
                listOfNotNull(
                    current.windGustsKmh,
                    today?.windGustsMaxKmh,
                    next12Hours.mapNotNull { it.windGustsKmh }.maxOrNull(),
                ).maxOrNull()
            if (maxGustKmh != null && maxGustKmh >= 50.0) {
                alerts +=
                    InAppWeatherAlert(
                        id = "wind_gusts_${forecast.fetchedAtEpochMillis}",
                        title = "Rajadas de vento fortes",
                        description =
                            "Rajadas acima de 50 km/h identificadas na previsão de $locationName. " +
                                "Redobre a atenção próximo a árvores e estruturas leves.",
                        severity = if (maxGustKmh >= 75.0) AlertSeverity.HIGH else AlertSeverity.ATTENTION,
                        condition = WeatherCondition.VENTO,
                        locationName = locationName,
                        isActive = true,
                        observedMetricSummary = "Rajada máx.: ${maxGustKmh.roundToInt()} km/h",
                    )
            }

            // 3. Very High / Extreme UV (>= 8.0)
            val maxUv = listOfNotNull(current.uvIndex, today?.uvIndexMax).maxOrNull()
            if (maxUv != null && maxUv >= 8.0) {
                alerts +=
                    InAppWeatherAlert(
                        id = "uv_high_${forecast.fetchedAtEpochMillis}",
                        title = if (maxUv >= 11.0) "Índice UV extremo" else "Índice UV muito alto",
                        description =
                            "Radiação ultravioleta elevada em $locationName. " +
                                "Utilize protetor solar, óculos UV e procure sombra nos horários de pico.",
                        severity = AlertSeverity.ATTENTION,
                        condition = WeatherCondition.SOL,
                        locationName = locationName,
                        isActive = true,
                        observedMetricSummary = "UV ${UserFacingWeatherFormatter.formatDecimalPt(maxUv)}",
                    )
            }

            // 4. Extreme heat (>= 37°C) or severe cold (<= 5°C)
            val maxTemp = listOfNotNull(current.temperatureC, today?.temperatureMaxC).maxOrNull()
            val minTemp = listOfNotNull(current.temperatureC, today?.temperatureMinC).minOrNull()
            if (maxTemp != null && maxTemp >= 37.0) {
                alerts +=
                    InAppWeatherAlert(
                        id = "heat_${forecast.fetchedAtEpochMillis}",
                        title = "Calor intenso",
                        description =
                            "Temperaturas elevadas previstas em $locationName. Hidrate-se com frequência e evite esforço físico sob sol forte.",
                        severity = AlertSeverity.ATTENTION,
                        condition = WeatherCondition.TERMOMETRO,
                        locationName = locationName,
                        isActive = true,
                        observedMetricSummary = "Máxima: ${maxTemp.roundToInt()}°C",
                    )
            } else if (minTemp != null && minTemp <= 5.0) {
                alerts +=
                    InAppWeatherAlert(
                        id = "cold_${forecast.fetchedAtEpochMillis}",
                        title = "Frio acentuado",
                        description =
                            "Temperaturas baixas previstas para $locationName. Proteja-se contra queda brusca de temperatura.",
                        severity = AlertSeverity.INFO,
                        condition = WeatherCondition.NEVE,
                        locationName = locationName,
                        isActive = true,
                        observedMetricSummary = "Mínima: ${minTemp.roundToInt()}°C",
                    )
            }

            // 5. Recent/monitoring informational notice when sea-level pressure is low (<1008 hPa)
            val pressure = current.pressureMslHpa
            if (pressure != null && pressure < 1008.0) {
                alerts +=
                    InAppWeatherAlert(
                        id = "low_pressure_${forecast.fetchedAtEpochMillis}",
                        title = "Pressão atmosférica baixa",
                        description =
                            "Pressão reduzida ao nível do mar abaixo de 1008 hPa em $locationName, " +
                                "frequentemente associada a maior instabilidade atmosférica.",
                        severity = AlertSeverity.INFO,
                        condition = WeatherCondition.NUBLADO,
                        locationName = locationName,
                        isActive = false,
                        observedMetricSummary = "${pressure.roundToInt()} hPa (Baixa)",
                    )
            }
        }

        // 6. Air Quality alert (US AQI > 100 or European AQI > 60)
        if (airQuality != null) {
            val usAqi = airQuality.usAqi
            val euAqi = airQuality.europeanAqi
            val poorAir = (usAqi != null && usAqi > 100) || (euAqi != null && euAqi > 60)
            if (poorAir) {
                val category = airQuality.qualityCategoryPt() ?: "Reduzida"
                val indexText = usAqi?.let { "US AQI $it" } ?: euAqi?.let { "EAQI $it" } ?: ""
                alerts +=
                    InAppWeatherAlert(
                        id = "aqi_${airQuality.fetchedAtEpochMillis}",
                        title = "Qualidade do ar $category",
                        description =
                            "Índices elevados de poluentes detectados pelo Open-Meteo Air Quality em $locationName. " +
                                "Grupos sensíveis devem reduzir atividades intensas ao ar livre.",
                        severity = if ((usAqi ?: 0) > 150 || (euAqi ?: 0) > 80) AlertSeverity.HIGH else AlertSeverity.ATTENTION,
                        condition = WeatherCondition.NEBLINA,
                        locationName = locationName,
                        isActive = true,
                        observedMetricSummary = indexText,
                        sourceLabel = "Derivado de dados reais Open-Meteo Air Quality",
                    )
            }
        }

        return alerts
    }
}
