package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.media.ClimaUiIcons
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.V53GlassCard
import com.example.ui.components.V53NoticeBanner
import com.example.ui.components.V53PageHeading
import com.example.ui.components.V53PrimaryButton
import com.example.ui.components.V53SegmentedOptions
import com.example.ui.state.AlertSeverity
import com.example.ui.state.AlertsSubTab
import com.example.ui.state.ClimaUiState
import com.example.ui.state.InAppWeatherAlert
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaAlertHighRed
import com.example.ui.theme.ClimaAlertInfoBlue
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs

/**
 * Complete native reproduction of CLIMA 3D V53 `alertsScreen` (Alertas meteorológicos).
 * Strictly in-app only — zero push, status-bar, or lock-screen notifications.
 * Displays transparent real alerts derived from Open-Meteo Forecast + Air Quality
 * or an honest empty state when no active severe condition is detected.
 */
@Composable
fun AlertsScreen(
    uiState: ClimaUiState,
    contentPadding: PaddingValues,
    onSelectSubTab: (AlertsSubTab) -> Unit,
    onBackToHome: () -> Unit,
) {
    BackHandler(onBack = onBackToHome)

    val tabSpec = remember { WeatherGlassSpecs.forTab(ClimaTab.ALERTS) }
    val dark = LocalClimaDarkTheme.current
    val itemsToShow =
        if (uiState.alertsSubTab == AlertsSubTab.ACTIVE) {
            uiState.activeAlerts
        } else {
            uiState.recentAlerts
        }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp).testTag("alerts_screen_list"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "alerts_back_bar") {
                V53PrimaryButton(
                    text = "← Voltar para Início",
                    secondary = true,
                    onClick = onBackToHome,
                    modifier = Modifier.testTag("alerts_back_button"),
                )
            }

            item(key = "alerts_heading") {
                V53PageHeading(
                    eyebrow = stringResource(R.string.alerts_eyebrow),
                    title = stringResource(R.string.alerts_title),
                    subtitle = stringResource(R.string.alerts_subtitle),
                )
            }

            item(key = "alerts_source_notice") {
                V53NoticeBanner(
                    text = "Os avisos abaixo são avaliados automaticamente a partir das leituras meteorológicas reais do Open-Meteo e qualidade do ar para ${uiState.activeLocationName}.",
                    iconRes = ClimaUiIcons.Info,
                )
            }

            item(key = "alerts_filter_tabs") {
                V53SegmentedOptions(
                    options =
                        listOf(
                            AlertsSubTab.ACTIVE to "${stringResource(R.string.alerts_tab_active)} (${uiState.activeAlerts.size})",
                            AlertsSubTab.RECENT to "${stringResource(R.string.alerts_tab_recent)} (${uiState.recentAlerts.size})",
                        ),
                    selected = uiState.alertsSubTab,
                    onSelect = onSelectSubTab,
                    testTagPrefix = "alerts_tab",
                )
            }

            if (itemsToShow.isEmpty()) {
                item(key = "alerts_empty_state") {
                    V53GlassCard(
                        alpha = tabSpec.cardAlpha,
                        modifier = Modifier.testTag("alerts_empty_card"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                painter = painterResource(ClimaUiIcons.Check),
                                contentDescription = null,
                                tint = ClimaToastEmerald,
                                modifier = Modifier.size(26.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text =
                                        if (uiState.alertsSubTab == AlertsSubTab.ACTIVE) {
                                            "Nenhum alerta meteorológico ativo"
                                        } else {
                                            "Nenhum aviso informativo recente"
                                        },
                                    color = WeatherGlassSpecs.primaryTextColor(dark),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "As condições atuais e previstas para ${uiState.activeLocationName} estão dentro dos limites regulares monitorados.",
                                    color = WeatherGlassSpecs.mutedTextColor(dark),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            } else {
                items(itemsToShow, key = { it.id }) { alert ->
                    InAppAlertCard(
                        alert = alert,
                        alpha = tabSpec.cardAlpha,
                    )
                }
            }

            item(key = "alerts_no_push_guarantee") {
                V53NoticeBanner(
                    text = stringResource(R.string.alerts_no_push_notice),
                    iconRes = ClimaUiIcons.Check,
                    accentColor = ClimaToastEmerald,
                    modifier = Modifier.testTag("alerts_no_push_notice"),
                )
            }
        }
    }
}

@Composable
private fun InAppAlertCard(
    alert: InAppWeatherAlert,
    alpha: Float,
) {
    val dark = LocalClimaDarkTheme.current
    val accent =
        when (alert.severity) {
            AlertSeverity.HIGH -> ClimaAlertHighRed
            AlertSeverity.ATTENTION -> ClimaAlertAttentionAmber
            AlertSeverity.INFO -> ClimaAlertInfoBlue
        }

    V53GlassCard(
        alpha = alpha,
        borderColor = accent.copy(alpha = 0.72f),
        modifier = Modifier.testTag("in_app_alert_card_${alert.id}"),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = accent.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        ) {
            Text(
                text = alert.severity.badgePt,
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedWeatherIcon(
                condition = alert.condition,
                size = WeatherIconSize.Alert,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = alert.title,
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = alert.observedMetricSummary,
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = alert.description,
            color = WeatherGlassSpecs.mutedTextColor(dark),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Local: ${alert.locationName} • ${alert.sourceLabel}",
            color = if (dark) Color(0xFF92CBE7) else Color(0xFF334E68),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
