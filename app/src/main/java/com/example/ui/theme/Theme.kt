package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = ClimaAccentCyan,
        onPrimary = ClimaDeepSky,
        secondary = ClimaTempMinCyan,
        onSecondary = ClimaDeepSky,
        tertiary = ClimaTempMaxCoral,
        background = ClimaDeepSky,
        onBackground = ClimaTextOnDark,
        surface = ClimaNightSurface,
        onSurface = ClimaTextOnDark,
        surfaceVariant = ClimaGlassDarkBase,
        onSurfaceVariant = ClimaTextMutedOnDark,
        outline = ClimaGlassBorderDark,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF0E6CB8),
        onPrimary = Color.White,
        secondary = Color(0xFF2A85D2),
        onSecondary = Color.White,
        tertiary = Color(0xFFD95B66),
        background = Color(0xFFEAF3FC),
        onBackground = ClimaTextOnLight,
        surface = ClimaGlassLightBase,
        onSurface = ClimaTextOnLight,
        surfaceVariant = Color(0xFFDCEBF9),
        onSurfaceVariant = ClimaTextMutedOnLight,
        outline = ClimaGlassBorderLight,
    )

enum class ClimaTab(val route: String) {
    HOME("home"),
    RADAR("radar"),
    CITIES("cities"),
    SETTINGS("settings"),
    ALERTS("alerts"),
}

/**
 * Approved V39/V53 per-tab glass surface alphas.
 * In V53 HTML, transparency values are expressed as 0% opaque .. 100% transparent,
 * so Compose surface alpha = (100 - transparencyPercent) / 100f.
 */
@Immutable
data class TabGlassSpec(
    val wallpaperShadeAlpha: Float,
    val cardAlpha: Float,
    val navBarAlpha: Float,
    val hourlyPanelAlpha: Float,
    val dailyPanelAlpha: Float,
    val noticeAlpha: Float,
)

object WeatherGlassSpecs {
    fun forTab(tab: ClimaTab): TabGlassSpec =
        when (tab) {
            ClimaTab.HOME ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f, // 100% transparent shade
                    cardAlpha = 0.20f, // 80% transparent
                    navBarAlpha = 0.20f, // 80% transparent
                    hourlyPanelAlpha = 0.15f, // 85% transparent
                    dailyPanelAlpha = 0.10f, // 90% transparent
                    noticeAlpha = 0.19f, // 81% transparent
                )

            ClimaTab.RADAR ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.20f, // 80% transparent
                    navBarAlpha = 0.20f,
                    hourlyPanelAlpha = 0.20f,
                    dailyPanelAlpha = 0.20f,
                    noticeAlpha = 0.20f,
                )

            ClimaTab.CITIES ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.25f, // 75% transparent
                    navBarAlpha = 0.25f,
                    hourlyPanelAlpha = 0.25f,
                    dailyPanelAlpha = 0.25f,
                    noticeAlpha = 0.25f,
                )

            ClimaTab.SETTINGS ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.25f, // 75% transparent
                    navBarAlpha = 0.25f,
                    hourlyPanelAlpha = 0.25f,
                    dailyPanelAlpha = 0.25f,
                    noticeAlpha = 0.25f,
                )

            ClimaTab.ALERTS ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.40f, // 60% transparent
                    navBarAlpha = 0.30f,
                    hourlyPanelAlpha = 0.40f,
                    dailyPanelAlpha = 0.40f,
                    noticeAlpha = 0.40f,
                )
        }

    fun glassContainerColor(alpha: Float, darkSurface: Boolean = true): Color {
        val base = if (darkSurface) ClimaGlassDarkBase else ClimaGlassLightBase
        return base.copy(alpha = alpha.coerceIn(0f, 1f))
    }

    fun glassBorderColor(darkSurface: Boolean = true): Color =
        if (darkSurface) ClimaGlassBorderDark else ClimaGlassBorderLight
}

@Composable
fun Clima3DTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    Clima3DTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
