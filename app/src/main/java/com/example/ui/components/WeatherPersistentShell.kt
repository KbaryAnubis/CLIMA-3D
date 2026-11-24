package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.media.ClimaUiIcons
import com.example.media.WeatherCondition
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaNavActiveBorder
import com.example.ui.theme.ClimaNavActiveBottom
import com.example.ui.theme.ClimaNavActiveTop
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs

private data class ClimaNavItem(
    val tab: ClimaTab,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val testTag: String,
)

private val primaryTabs =
    listOf(
        ClimaNavItem(ClimaTab.HOME, R.string.nav_home, ClimaUiIcons.Home, "nav_tab_home"),
        ClimaNavItem(ClimaTab.RADAR, R.string.nav_radar, ClimaUiIcons.Radar, "nav_tab_radar"),
        ClimaNavItem(ClimaTab.CITIES, R.string.nav_cities, ClimaUiIcons.Pin, "nav_tab_cities"),
        ClimaNavItem(ClimaTab.SETTINGS, R.string.nav_settings, ClimaUiIcons.Settings, "nav_tab_settings"),
    )

/**
 * Persistent visual shell that keeps `WeatherBackgroundLayer` continuously mounted
 * behind all tabs (Início, Radar, Cidades, Ajustes, Alertas), reproducing V53's
 * `.phone-bar` active cyan/blue gradient pill on compact screens and adapting to a
 * glass navigation rail on expanded screens.
 */
@Composable
fun WeatherPersistentShell(
    condition: WeatherCondition,
    activeTab: ClimaTab,
    animatedBackgroundEnabled: Boolean,
    onSelectTab: (ClimaTab) -> Unit,
    modifier: Modifier = Modifier,
    toastMessage: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val tabSpec = remember(activeTab) { WeatherGlassSpecs.forTab(activeTab) }
    val dark = LocalClimaDarkTheme.current

    Box(modifier = modifier.fillMaxSize()) {
        // Persistent full-screen animated weather background behind all tabs
        WeatherBackgroundLayer(
            condition = condition,
            activeTab = activeTab,
            animatedEnabled = animatedBackgroundEnabled,
            modifier = Modifier.fillMaxSize(),
        )

        // Subtle light-theme veil when user explicitly picks Light appearance in Settings
        if (!dark) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xA8EBF4FC)),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 700.dp

            if (useNavigationRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    GlassNavigationRail(
                        activeTab = activeTab,
                        navAlpha = tabSpec.navBarAlpha,
                        onSelectTab = onSelectTab,
                    )
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .windowInsetsPadding(WindowInsets.statusBars)
                                .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        content(PaddingValues(horizontal = 20.dp, vertical = 14.dp))
                        V53ToastOverlay(
                            message = toastMessage,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        content(PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp))
                    }

                    V53ToastOverlay(
                        message = toastMessage,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(bottom = 90.dp),
                    )

                    GlassBottomNavigationBar(
                        activeTab = activeTab,
                        navAlpha = tabSpec.navBarAlpha,
                        onSelectTab = onSelectTab,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassBottomNavigationBar(
    activeTab: ClimaTab,
    navAlpha: Float,
    onSelectTab: (ClimaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalClimaDarkTheme.current
    Surface(
        modifier = modifier.testTag("v53_bottom_nav_bar"),
        shape = RoundedCornerShape(22.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha = navAlpha, darkSurface = dark),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            primaryTabs.forEach { item ->
                val selected = activeTab == item.tab || (activeTab == ClimaTab.ALERTS && item.tab == ClimaTab.HOME)
                val shape = RoundedCornerShape(16.dp)
                val pillModifier =
                    if (selected) {
                        Modifier
                            .background(
                                Brush.verticalGradient(listOf(ClimaNavActiveTop, ClimaNavActiveBottom)),
                                shape = shape,
                            )
                            .border(1.dp, ClimaNavActiveBorder, shape)
                    } else {
                        Modifier
                    }

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(shape)
                            .then(pillModifier)
                            .minimumInteractiveComponentSize()
                            .clickable { onSelectTab(item.tab) }
                            .padding(vertical = 6.dp)
                            .testTag(item.testTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = stringResource(item.labelRes),
                        tint =
                            when {
                                selected -> ClimaAccentCyan
                                dark -> Color(0xFFAECAE0)
                                else -> Color(0xFF334E68)
                            },
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = stringResource(item.labelRes),
                        color =
                            when {
                                selected -> ClimaTextOnDark
                                dark -> Color(0xFFAECAE0)
                                else -> Color(0xFF334E68)
                            },
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavigationRail(
    activeTab: ClimaTab,
    navAlpha: Float,
    onSelectTab: (ClimaTab) -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    Surface(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(88.dp)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha = navAlpha, darkSurface = dark),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            primaryTabs.forEach { item ->
                val selected = activeTab == item.tab || (activeTab == ClimaTab.ALERTS && item.tab == ClimaTab.HOME)
                val shape = RoundedCornerShape(16.dp)
                val pillModifier =
                    if (selected) {
                        Modifier
                            .background(
                                Brush.verticalGradient(listOf(ClimaNavActiveTop, ClimaNavActiveBottom)),
                                shape = shape,
                            )
                            .border(1.dp, ClimaNavActiveBorder, shape)
                    } else {
                        Modifier
                    }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(pillModifier)
                            .minimumInteractiveComponentSize()
                            .clickable { onSelectTab(item.tab) }
                            .padding(vertical = 8.dp)
                            .testTag(item.testTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = stringResource(item.labelRes),
                        tint =
                            when {
                                selected -> ClimaAccentCyan
                                dark -> Color(0xFFAECAE0)
                                else -> Color(0xFF334E68)
                            },
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = stringResource(item.labelRes),
                        color =
                            when {
                                selected -> ClimaTextOnDark
                                dark -> Color(0xFFAECAE0)
                                else -> Color(0xFF334E68)
                            },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
