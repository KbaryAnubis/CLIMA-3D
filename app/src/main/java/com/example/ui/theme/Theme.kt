package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LocalClimaDarkTheme = staticCompositionLocalOf { true }

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
        secondary = Color(0xFF1677C5),
        onSecondary = Color.White,
        tertiary = Color(0xFFD95B66),
        background = Color(0xFFEDF5FC),
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
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.56f,
                    navBarAlpha = 0.78f,
                    hourlyPanelAlpha = 0.74f,
                    dailyPanelAlpha = 0.58f,
                    noticeAlpha = 0.52f,
                )

            ClimaTab.RADAR ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.72f,
                    navBarAlpha = 0.80f,
                    hourlyPanelAlpha = 0.72f,
                    dailyPanelAlpha = 0.72f,
                    noticeAlpha = 0.65f,
                )

            ClimaTab.CITIES ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.68f,
                    navBarAlpha = 0.80f,
                    hourlyPanelAlpha = 0.68f,
                    dailyPanelAlpha = 0.68f,
                    noticeAlpha = 0.62f,
                )

            ClimaTab.SETTINGS ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.68f,
                    navBarAlpha = 0.80f,
                    hourlyPanelAlpha = 0.68f,
                    dailyPanelAlpha = 0.68f,
                    noticeAlpha = 0.62f,
                )

            ClimaTab.ALERTS ->
                TabGlassSpec(
                    wallpaperShadeAlpha = 0.0f,
                    cardAlpha = 0.74f,
                    navBarAlpha = 0.82f,
                    hourlyPanelAlpha = 0.74f,
                    dailyPanelAlpha = 0.74f,
                    noticeAlpha = 0.68f,
                )
        }

    fun glassContainerColor(alpha: Float, darkSurface: Boolean = true): Color {
        return if (darkSurface) {
            ClimaGlassDarkBase.copy(alpha = alpha.coerceIn(0f, 1f))
        } else {
            ClimaGlassLightBase.copy(alpha = (alpha + 0.22f).coerceIn(0.76f, 0.94f))
        }
    }

    fun glassBorderColor(darkSurface: Boolean = true): Color =
        if (darkSurface) ClimaGlassBorderDark else ClimaGlassBorderLight

    fun primaryTextColor(darkSurface: Boolean): Color =
        if (darkSurface) ClimaTextOnDark else ClimaTextOnLight

    fun mutedTextColor(darkSurface: Boolean): Color =
        if (darkSurface) ClimaTextMutedOnDark else ClimaTextMutedOnLight

    fun accentColor(darkSurface: Boolean): Color =
        if (darkSurface) ClimaAccentCyan else Color(0xFF0E6CB8)
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

    CompositionLocalProvider(LocalClimaDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    Clima3DTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
}
