package com.example.media

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Official V50 approved icon sizes for Clima 3D.
 * Animated WebP icons always preserve 1:1 aspect ratio and transparent background.
 */
object WeatherIconSize {
    val Hero: Dp = 132.dp
    val Hourly: Dp = 54.dp
    val Daily: Dp = 61.dp
    val Radar: Dp = 54.dp
    val Alert: Dp = 48.dp
    val Modal: Dp = 64.dp
    val Widget: Dp = 64.dp
}

/**
 * Represents the 11 official 420x420 animated VP8X WebP icons for CLIMA 3D V53.
 */
enum class AnimatedWeatherWebp(
    val key: String,
    @DrawableRes val drawableRes: Int,
    @StringRes val labelRes: Int,
) {
    SOL("sol", R.drawable.wx_icon_sol, R.string.wx_sol),
    PARCIALMENTE_NUBLADO(
        "parcialmente_nublado",
        R.drawable.wx_icon_parcialmente_nublado,
        R.string.wx_sol_nuvens,
    ),
    NUBLADO("nublado", R.drawable.wx_icon_nublado, R.string.wx_nublado),
    NEBLINA("neblina", R.drawable.wx_icon_neblina, R.string.wx_neblina),
    CHUVA_MODERADA("chuva_moderada", R.drawable.wx_icon_chuva_moderada, R.string.wx_chuva),
    TEMPESTADE_COM_CHUVA(
        "tempestade_com_chuva",
        R.drawable.wx_icon_tempestade_com_chuva,
        R.string.wx_tempestade,
    ),
    TEMPESTADE_COM_GRANIZO(
        "tempestade_com_granizo",
        R.drawable.wx_icon_tempestade_com_granizo,
        R.string.wx_granizo,
    ),
    TROVOADA("trovoada", R.drawable.wx_icon_trovoada, R.string.wx_trovoada),
    TEMPESTADE_FORTE(
        "tempestade_forte",
        R.drawable.wx_icon_tempestade_forte,
        R.string.wx_tempestade_forte,
    ),
    NOITE_ESTRELADA("noite_estrelada", R.drawable.wx_icon_noite_estrelada, R.string.wx_noite),
    NOITE_ENTRE_NUVENS(
        "noite_entre_nuvens",
        R.drawable.wx_icon_noite_entre_nuvens,
        R.string.wx_noite_nuvens,
    ),
}

/**
 * Represents all 11 looping H.264 MP4 weather background clips extracted from CLIMA 3D V53.
 */
enum class WeatherBackgroundVideo(
    val key: String,
    @RawRes val rawRes: Int,
) {
    SUN_DAY("sun_day", R.raw.wx_bg_sun_day),
    FEW_CLOUDY_DAY("few_cloudy_day", R.raw.wx_bg_few_cloudy_day),
    CLOUDY_DAY("cloudy_day", R.raw.wx_bg_cloudy_day),
    RAIN_DAY("rain_day", R.raw.wx_bg_rain_day),
    THUNDER("thunder", R.raw.wx_bg_thunder),
    FOG("fog", R.raw.wx_bg_fog),
    SUN_NIGHT("sun_night", R.raw.wx_bg_sun_night),
    FEW_CLOUDY_NIGHT("few_cloudy_night", R.raw.wx_bg_few_cloudy_night),
    CLOUDY_NIGHT("cloudy_night", R.raw.wx_bg_cloudy_night),
    SNOW_DAY("snow_day", R.raw.wx_bg_snow_day),
    SNOW_NIGHT("snow_night", R.raw.wx_bg_snow_night),
}

/**
 * Complete catalog of V53 weather conditions, mapping each condition to:
 * - its animated WebP icon (from WX47_MAP) OR fallback vector drawable (from wxIconOriginal)
 * - its Media3 looping background MP4 (from CLIMA_V40_MAP / CLIMA_V44_CLIPS)
 * - its static WebP fallback wallpaper (from wx37-*)
 * - playback speed and night-rain overlay rules preserved verbatim from V53.
 */
enum class WeatherCondition(
    val key: String,
    @StringRes val labelRes: Int,
    val animatedWebp: AnimatedWeatherWebp?,
    @DrawableRes val fallbackVectorRes: Int?,
    val backgroundVideo: WeatherBackgroundVideo,
    @DrawableRes val staticWallpaperRes: Int,
    val videoPlaybackSpeed: Float = 1.0f,
    val needsNightRainOverlay: Boolean = false,
) {
    SOL(
        key = "sol",
        labelRes = R.string.wx_sol,
        animatedWebp = AnimatedWeatherWebp.SOL,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.SUN_DAY,
        staticWallpaperRes = R.drawable.wx_wall_sol,
    ),
    SOL_NUVENS(
        key = "sol_nuvens",
        labelRes = R.string.wx_sol_nuvens,
        animatedWebp = AnimatedWeatherWebp.PARCIALMENTE_NUBLADO,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.FEW_CLOUDY_DAY,
        staticWallpaperRes = R.drawable.wx_wall_sol_nuvens,
    ),
    NUBLADO(
        key = "nublado",
        labelRes = R.string.wx_nublado,
        animatedWebp = AnimatedWeatherWebp.NUBLADO,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.CLOUDY_DAY,
        staticWallpaperRes = R.drawable.wx_wall_nublado,
    ),
    CHUVA_FRACA(
        key = "chuva_fraca",
        labelRes = R.string.wx_chuva_fraca,
        animatedWebp = AnimatedWeatherWebp.CHUVA_MODERADA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.RAIN_DAY,
        staticWallpaperRes = R.drawable.wx_wall_chuva_fraca,
        videoPlaybackSpeed = 0.85f,
    ),
    CHUVA(
        key = "chuva",
        labelRes = R.string.wx_chuva,
        animatedWebp = AnimatedWeatherWebp.CHUVA_MODERADA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.RAIN_DAY,
        staticWallpaperRes = R.drawable.wx_wall_chuva,
    ),
    TEMPESTADE(
        key = "tempestade",
        labelRes = R.string.wx_tempestade,
        animatedWebp = AnimatedWeatherWebp.TEMPESTADE_COM_CHUVA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.THUNDER,
        staticWallpaperRes = R.drawable.wx_wall_tempestade,
    ),
    TROVOADA(
        key = "trovoada",
        labelRes = R.string.wx_trovoada,
        animatedWebp = AnimatedWeatherWebp.TROVOADA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.THUNDER,
        staticWallpaperRes = R.drawable.wx_wall_tempestade,
    ),
    TEMPESTADE_FORTE(
        key = "tempestade_forte",
        labelRes = R.string.wx_tempestade_forte,
        animatedWebp = AnimatedWeatherWebp.TEMPESTADE_FORTE,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.THUNDER,
        staticWallpaperRes = R.drawable.wx_wall_tempestade,
    ),
    NEBLINA(
        key = "neblina",
        labelRes = R.string.wx_neblina,
        animatedWebp = AnimatedWeatherWebp.NEBLINA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.FOG,
        staticWallpaperRes = R.drawable.wx_wall_neblina,
    ),
    NOITE(
        key = "noite",
        labelRes = R.string.wx_noite,
        animatedWebp = AnimatedWeatherWebp.NOITE_ESTRELADA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.SUN_NIGHT,
        staticWallpaperRes = R.drawable.wx_wall_noite,
    ),
    NOITE_PARCIAL(
        key = "noite_parcial",
        labelRes = R.string.wx_noite_parcial,
        animatedWebp = AnimatedWeatherWebp.NOITE_ENTRE_NUVENS,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.FEW_CLOUDY_NIGHT,
        staticWallpaperRes = R.drawable.wx_wall_noite_nuvens,
    ),
    NOITE_NUVENS(
        key = "noite_nuvens",
        labelRes = R.string.wx_noite_nuvens,
        animatedWebp = AnimatedWeatherWebp.NOITE_ENTRE_NUVENS,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.CLOUDY_NIGHT,
        staticWallpaperRes = R.drawable.wx_wall_noite_nuvens,
    ),
    NOITE_ENCOBERTA(
        key = "noite_encoberta",
        labelRes = R.string.wx_noite_encoberta,
        animatedWebp = AnimatedWeatherWebp.NOITE_ENTRE_NUVENS,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.CLOUDY_NIGHT,
        staticWallpaperRes = R.drawable.wx_wall_noite_nuvens,
    ),
    NOITE_CHUVA(
        key = "noite_chuva",
        labelRes = R.string.wx_noite_chuva,
        animatedWebp = AnimatedWeatherWebp.TEMPESTADE_COM_CHUVA,
        fallbackVectorRes = null,
        backgroundVideo = WeatherBackgroundVideo.CLOUDY_NIGHT,
        staticWallpaperRes = R.drawable.wx_wall_noite_chuva,
        needsNightRainOverlay = true,
    ),
    NEVE(
        key = "neve",
        labelRes = R.string.wx_neve,
        animatedWebp = null,
        fallbackVectorRes = R.drawable.wx_fallback_neve,
        backgroundVideo = WeatherBackgroundVideo.SNOW_DAY,
        staticWallpaperRes = R.drawable.wx_wall_nublado,
    ),
    NEVE_NOITE(
        key = "neve_noite",
        labelRes = R.string.wx_neve_noite,
        animatedWebp = null,
        fallbackVectorRes = R.drawable.wx_fallback_neve_noite,
        backgroundVideo = WeatherBackgroundVideo.SNOW_NIGHT,
        staticWallpaperRes = R.drawable.wx_wall_noite_nuvens,
    ),
    GRANIZO(
        key = "granizo",
        labelRes = R.string.wx_granizo,
        animatedWebp = AnimatedWeatherWebp.TEMPESTADE_COM_GRANIZO,
        fallbackVectorRes = R.drawable.wx_fallback_granizo,
        backgroundVideo = WeatherBackgroundVideo.THUNDER,
        staticWallpaperRes = R.drawable.wx_wall_tempestade,
    ),
    VENTO(
        key = "vento",
        labelRes = R.string.wx_vento,
        animatedWebp = null,
        fallbackVectorRes = R.drawable.wx_fallback_vento,
        backgroundVideo = WeatherBackgroundVideo.CLOUDY_DAY,
        staticWallpaperRes = R.drawable.wx_wall_nublado,
    ),
    TORNADO(
        key = "tornado",
        labelRes = R.string.wx_tornado,
        animatedWebp = null,
        fallbackVectorRes = R.drawable.wx_fallback_tornado,
        backgroundVideo = WeatherBackgroundVideo.THUNDER,
        staticWallpaperRes = R.drawable.wx_wall_tempestade,
    ),
    ARCO_IRIS(
        key = "arco_iris",
        labelRes = R.string.wx_arco_iris,
        animatedWebp = null,
        fallbackVectorRes = R.drawable.wx_fallback_arco_iris,
        backgroundVideo = WeatherBackgroundVideo.FEW_CLOUDY_DAY,
        staticWallpaperRes = R.drawable.wx_wall_sol_nuvens,
    ),
    TERMOMETRO(
        key = "termometro",
        labelRes = R.string.wx_termometro,
        animatedWebp = null,
        fallbackVectorRes = R.drawable.wx_fallback_termometro,
        backgroundVideo = WeatherBackgroundVideo.SUN_DAY,
        staticWallpaperRes = R.drawable.wx_wall_sol,
    );

    companion object {
        val primaryBackgroundScenarios: List<WeatherCondition> =
            listOf(
                SOL,
                SOL_NUVENS,
                NUBLADO,
                CHUVA_FRACA,
                CHUVA,
                TEMPESTADE,
                NEBLINA,
                NOITE,
                NOITE_PARCIAL,
                NOITE_NUVENS,
                NOITE_CHUVA,
                NEVE,
                NEVE_NOITE,
            )

        val fallbackVectorConditions: List<WeatherCondition> =
            listOf(NEVE, NEVE_NOITE, GRANIZO, VENTO, TORNADO, ARCO_IRIS, TERMOMETRO)

        fun fromKey(key: String?): WeatherCondition =
            entries.firstOrNull { it.key == key } ?: SOL_NUVENS
    }
}

/**
 * Strongly typed references to all 30 extracted V53 UI vector drawables.
 */
object ClimaUiIcons {
    @DrawableRes val Home = R.drawable.ic_wx_home
    @DrawableRes val Calendar = R.drawable.ic_wx_calendar
    @DrawableRes val Radar = R.drawable.ic_wx_radar
    @DrawableRes val Pin = R.drawable.ic_wx_pin
    @DrawableRes val Settings = R.drawable.ic_wx_settings
    @DrawableRes val Alert = R.drawable.ic_wx_alert
    @DrawableRes val Menu = R.drawable.ic_wx_menu
    @DrawableRes val Search = R.drawable.ic_wx_search
    @DrawableRes val Chevron = R.drawable.ic_wx_chevron
    @DrawableRes val Right = R.drawable.ic_wx_right
    @DrawableRes val Drop = R.drawable.ic_wx_drop
    @DrawableRes val Wind = R.drawable.ic_wx_wind
    @DrawableRes val Sun = R.drawable.ic_wx_sun
    @DrawableRes val Cloud = R.drawable.ic_wx_cloud
    @DrawableRes val Pressure = R.drawable.ic_wx_pressure
    @DrawableRes val Eye = R.drawable.ic_wx_eye
    @DrawableRes val Thermo = R.drawable.ic_wx_thermo
    @DrawableRes val Clock = R.drawable.ic_wx_clock
    @DrawableRes val Location = R.drawable.ic_wx_location
    @DrawableRes val List = R.drawable.ic_wx_list
    @DrawableRes val Map = R.drawable.ic_wx_map
    @DrawableRes val Plus = R.drawable.ic_wx_plus
    @DrawableRes val Trash = R.drawable.ic_wx_trash
    @DrawableRes val Refresh = R.drawable.ic_wx_refresh
    @DrawableRes val Check = R.drawable.ic_wx_check
    @DrawableRes val Info = R.drawable.ic_wx_info
    @DrawableRes val Moon = R.drawable.ic_wx_moon
    @DrawableRes val Pause = R.drawable.ic_wx_pause
    @DrawableRes val Play = R.drawable.ic_wx_play
    @DrawableRes val Layers = R.drawable.ic_wx_layers

    val all: List<Int> =
        listOf(
            Home, Calendar, Radar, Pin, Settings, Alert, Menu, Search, Chevron, Right,
            Drop, Wind, Sun, Cloud, Pressure, Eye, Thermo, Clock, Location, List,
            Map, Plus, Trash, Refresh, Check, Info, Moon, Pause, Play, Layers,
        )
}
