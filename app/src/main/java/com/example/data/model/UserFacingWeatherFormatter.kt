package com.example.data.model

import java.util.Locale

object UserFacingWeatherFormatter {
    fun formatDecimalPt(value: Double, decimals: Int = 1): String =
        String.format(Locale("pt", "BR"), "%.${decimals}f", value)
}
