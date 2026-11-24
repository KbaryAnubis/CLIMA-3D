package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.example.ui.theme.ClimaTab
import com.example.ui.theme.ClimaTextMutedOnDark
import com.example.ui.theme.ClimaTextOnDark
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
 * behind all tabs (Início, Radar, Cidades, Ajustes, Alertas), adapting between a
 * glass bottom navigation bar on compact screens and a glass navigation rail on wide screens.
 */
@Composable
fun WeatherPersistentShell(
    condition: WeatherCondition,
    activeTab: ClimaTab,
    animatedBackgroundEnabled: Boolean,
    onSelectTab: (ClimaTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val tabSpec = remember(activeTab) { WeatherGlassSpecs.forTab(activeTab) }

    Box(modifier = modifier.fillMaxSize()) {
        // Persistent full-screen animated weather background behind all tabs
        WeatherBackgroundLayer(
            condition = condition,
            activeTab = activeTab,
            animatedEnabled = animatedBackgroundEnabled,
            modifier = Modifier.fillMaxSize(),
        )

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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        content(PaddingValues(horizontal = 20.dp, vertical = 16.dp))
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        content(PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp))
                    }

                    GlassBottomNavigationBar(
                        activeTab = activeTab,
                        navAlpha = tabSpec.navBarAlpha,
                        onSelectTab = onSelectTab,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha = navAlpha),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            primaryTabs.forEach { item ->
                val selected = activeTab == item.tab
                val tint = if (selected) ClimaAccentCyan else ClimaTextMutedOnDark
                val pillBg = if (selected) ClimaAccentCyan.copy(alpha = 0.18f) else Color.Transparent

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(pillBg)
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
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(item.labelRes),
                        color = if (selected) ClimaTextOnDark else ClimaTextMutedOnDark,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(88.dp)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha = navAlpha),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor()),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            primaryTabs.forEach { item ->
                val selected = activeTab == item.tab
                val tint = if (selected) ClimaAccentCyan else ClimaTextMutedOnDark
                val pillBg = if (selected) ClimaAccentCyan.copy(alpha = 0.18f) else Color.Transparent

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(pillBg)
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
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(item.labelRes),
                        color = if (selected) ClimaTextOnDark else ClimaTextMutedOnDark,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
