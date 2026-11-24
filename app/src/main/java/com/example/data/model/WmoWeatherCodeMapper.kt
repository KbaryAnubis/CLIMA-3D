package com.example.data.model

import com.example.media.WeatherCondition

/**
 * Maps official WMO 4677 weather interpretation codes used by Open-Meteo into
 * Brazilian Portuguese descriptions and Clima 3D's native [WeatherCondition] visual catalog.
 */
object WmoWeatherCodeMapper {

    fun descriptionPt(weatherCode: Int?, isDay: Boolean = true): String =
        when (weatherCode) {
            0 -> if (isDay) "Céu limpo" else "Noite limpa"
            1 -> if (isDay) "Predominantemente limpo" else "Noite com poucas nuvens"
            2 -> "Parcialmente nublado"
            3 -> "Nublado"
            45 -> "Nevoeiro"
            48 -> "Nevoeiro com geada"
            51 -> "Garoa fraca"
            53 -> "Garoa moderada"
            55 -> "Garoa intensa"
            56 -> "Garoa congelante fraca"
            57 -> "Garoa congelante intensa"
            61 -> "Chuva fraca"
            63 -> "Chuva moderada"
            65 -> "Chuva forte"
            66 -> "Chuva congelante fraca"
            67 -> "Chuva congelante forte"
            71 -> "Neve fraca"
            73 -> "Neve moderada"
            75 -> "Neve forte"
            77 -> "Grãos de neve"
            80 -> "Pancadas de chuva fracas"
            81 -> "Pancadas de chuva moderadas"
            82 -> "Pancadas de chuva fortes"
            85 -> "Pancadas de neve fracas"
            86 -> "Pancadas de neve fortes"
            95 -> "Tempestade com trovoadas"
            96 -> "Tempestade com granizo leve"
            99 -> "Tempestade com granizo forte"
            else -> "Condição indisponível"
        }

    fun toVisualCondition(weatherCode: Int?, isDay: Boolean = true): WeatherCondition =
        when (weatherCode) {
            0 -> if (isDay) WeatherCondition.SOL else WeatherCondition.NOITE
            1 -> if (isDay) WeatherCondition.SOL_NUVENS else WeatherCondition.NOITE_PARCIAL
            2 -> if (isDay) WeatherCondition.SOL_NUVENS else WeatherCondition.NOITE_NUVENS
            3 -> if (isDay) WeatherCondition.NUBLADO else WeatherCondition.NOITE_ENCOBERTA
            45, 48 -> WeatherCondition.NEBLINA
            51, 53, 55, 56, 57, 61, 80 ->
                if (isDay) WeatherCondition.CHUVA_FRACA else WeatherCondition.NOITE_CHUVA
            63, 65, 66, 67, 81, 82 ->
                if (isDay) WeatherCondition.CHUVA else WeatherCondition.NOITE_CHUVA
            71, 73, 75, 77, 85, 86 ->
                if (isDay) WeatherCondition.NEVE else WeatherCondition.NEVE_NOITE
            95 -> WeatherCondition.TEMPESTADE
            96, 99 -> WeatherCondition.GRANIZO
            else -> if (isDay) WeatherCondition.SOL_NUVENS else WeatherCondition.NOITE_PARCIAL
        }
}
