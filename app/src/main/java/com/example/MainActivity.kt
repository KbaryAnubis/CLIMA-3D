package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.maps.CityMapLibreSection
import com.example.maps.RadarLayerStatusCard
import com.example.media.AnimatedWeatherWebp
import com.example.media.ClimaUiIcons
import com.example.media.WeatherCondition
import com.example.media.WeatherIconSize
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.AnimatedWeatherWebpImage
import com.example.ui.components.WeatherPersistentShell
import com.example.ui.theme.Clima3DTheme
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.WeatherGlassSpecs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Clima3DTheme(darkTheme = true, dynamicColor = false) {
                Clima3DNativeAssetsFoundationApp()
            }
        }
    }
}

/**
 * Etapa 2A host: demonstrates the persistent full-screen Media3/WebP weather background
 * across all tabs, the 10 animated WebP icons without artificial movement, fallback & UI
 * vector drawables, and the prepared Google Maps SDK + Radar overlay layer — without
 * inventing fake weather forecast data or prematurely building all screens.
 */
@Composable
fun Clima3DNativeAssetsFoundationApp() {
    var activeTabName by rememberSaveable { mutableStateOf(ClimaTab.HOME.name) }
    var activeConditionName by rememberSaveable { mutableStateOf(WeatherCondition.SOL_NUVENS.name) }
    var animatedBackgroundEnabled by rememberSaveable { mutableStateOf(true) }

    val activeTab = remember(activeTabName) { ClimaTab.valueOf(activeTabName) }
    val activeCondition = remember(activeConditionName) { WeatherCondition.valueOf(activeConditionName) }

    WeatherPersistentShell(
        condition = activeCondition,
        activeTab = activeTab,
        animatedBackgroundEnabled = animatedBackgroundEnabled,
        onSelectTab = { activeTabName = it.name },
    ) { contentPadding ->
        NativeAssetsFoundationContent(
            activeTab = activeTab,
            activeCondition = activeCondition,
            animatedBackgroundEnabled = animatedBackgroundEnabled,
            onToggleAnimatedBackground = { animatedBackgroundEnabled = it },
            onSelectCondition = { activeConditionName = it.name },
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun NativeAssetsFoundationContent(
    activeTab: ClimaTab,
    activeCondition: WeatherCondition,
    animatedBackgroundEnabled: Boolean,
    onToggleAnimatedBackground: (Boolean) -> Unit,
    onSelectCondition: (WeatherCondition) -> Unit,
    contentPadding: PaddingValues,
) {
    val tabSpec = remember(activeTab) { WeatherGlassSpecs.forTab(activeTab) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                GlassCard(alpha = tabSpec.cardAlpha) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.stage2a_title),
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.stage2a_subtitle),
                                color = ClimaTextMutedOnDark,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        AnimatedWeatherIcon(
                            condition = activeCondition,
                            size = WeatherIconSize.Modal,
                            modifier = Modifier.testTag("active_condition_icon"),
                        )
                    }
                }
            }

            item {
                GlassCard(alpha = tabSpec.cardAlpha) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.stage2a_video_toggle),
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${stringResource(activeCondition.labelRes)} • ${activeCondition.backgroundVideo.key}.mp4 (${activeCondition.videoPlaybackSpeed}x)",
                                color = ClimaTextMutedOnDark,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = animatedBackgroundEnabled,
                            onCheckedChange = onToggleAnimatedBackground,
                            modifier = Modifier.testTag("toggle_animated_background"),
                            colors = SwitchDefaults.colors(checkedThumbColor = ClimaTextOnDark, checkedTrackColor = ClimaAccentCyan),
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("background_scenario_selector"),
                    ) {
                        items(WeatherCondition.primaryBackgroundScenarios) { scenario ->
                            val selected = scenario == activeCondition
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectCondition(scenario) },
                                label = {
                                    Text(
                                        text = stringResource(scenario.labelRes),
                                        maxLines = 1,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ClimaAccentCyan.copy(alpha = 0.28f),
                                    selectedLabelColor = ClimaTextOnDark,
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    labelColor = ClimaTextMutedOnDark,
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) ClimaAccentCyan else Color.White.copy(alpha = 0.18f),
                                ),
                            )
                        }
                    }
                }
            }

            when (activeTab) {
                ClimaTab.HOME -> {
                    item {
                        GlassCard(alpha = tabSpec.cardAlpha) {
                            Text(
                                text = stringResource(R.string.stage2a_animated_icons_section),
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().testTag("animated_webp_gallery"),
                            ) {
                                items(AnimatedWeatherWebp.entries) { webp ->
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color.White.copy(alpha = 0.08f),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                                        modifier = Modifier
                                            .clickable {
                                                val match =
                                                    WeatherCondition.entries.firstOrNull {
                                                        it.animatedWebp == webp
                                                    }
                                                if (match != null) onSelectCondition(match)
                                            }
                                            .padding(2.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            AnimatedWeatherWebpImage(
                                                webp = webp,
                                                size = WeatherIconSize.Daily,
                                            )
                                            Text(
                                                text = stringResource(webp.labelRes),
                                                color = ClimaTextOnDark,
                                                style = MaterialTheme.typography.labelSmall,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 88.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        GlassCard(alpha = tabSpec.cardAlpha) {
                            Text(
                                text = stringResource(R.string.stage2a_fallback_icons_section),
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(WeatherCondition.fallbackVectorConditions) { fallback ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        AnimatedWeatherIcon(
                                            condition = fallback,
                                            size = WeatherIconSize.Hourly,
                                        )
                                        Text(
                                            text = stringResource(fallback.labelRes),
                                            color = ClimaTextMutedOnDark,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(ClimaUiIcons.all) { iconRes ->
                                    Icon(
                                        painter = painterResource(id = iconRes),
                                        contentDescription = null,
                                        tint = ClimaTextOnDark,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                ClimaTab.CITIES -> {
                    item {
                        CityMapLibreSection()
                    }
                }

                ClimaTab.RADAR -> {
                    item {
                        RadarLayerStatusCard()
                    }
                }

                ClimaTab.SETTINGS, ClimaTab.ALERTS -> {
                    item {
                        GlassCard(alpha = tabSpec.cardAlpha) {
                            Text(
                                text = stringResource(R.string.stage2a_tab_placeholder),
                                color = ClimaTextOnDark,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCard(
    alpha: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
