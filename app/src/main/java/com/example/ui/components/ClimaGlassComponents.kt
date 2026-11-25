package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TimeFormatPreference
import com.example.data.model.WeatherTimeUtils
import com.example.media.ClimaUiIcons
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaButtonGradientEnd
import com.example.ui.theme.ClimaButtonGradientStart
import com.example.ui.theme.ClimaDeepSky
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastBg
import com.example.ui.theme.ClimaToastBorder
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun V53GlassCard(
    alpha: Float,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 19,
    borderColor: Color? = null,
    contentPaddingHorizontal: Int = 14,
    contentPaddingVertical: Int = 14,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius.dp),
        color = WeatherGlassSpecs.glassContainerColor(alpha = alpha, darkSurface = dark),
        border = BorderStroke(1.dp, borderColor ?: WeatherGlassSpecs.glassBorderColor(dark)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = contentPaddingHorizontal.dp, vertical = contentPaddingVertical.dp),
            content = content,
        )
    }
}

@Composable
fun V53PageHeading(
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val dark = LocalClimaDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = eyebrow.uppercase(Locale("pt", "BR")),
                color = WeatherGlassSpecs.accentColor(dark),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = title,
                color = WeatherGlassSpecs.primaryTextColor(dark),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = subtitle,
                color = WeatherGlassSpecs.mutedTextColor(dark),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun V53SectionHeader(
    title: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    actionLabel: String? = null,
    actionTestTag: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val dark = LocalClimaDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = WeatherGlassSpecs.accentColor(dark),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                color = WeatherGlassSpecs.primaryTextColor(dark),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (trailingText != null) {
            Text(
                text = trailingText,
                color = if (dark) Color(0xFFA0D7FB) else Color(0xFF0E6CB8),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (actionLabel != null && onActionClick != null) {
            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .minimumInteractiveComponentSize()
                        .clickable(onClick = onActionClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .let { mod -> if (actionTestTag != null) mod.testTag(actionTestTag) else mod },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = actionLabel,
                    color = WeatherGlassSpecs.accentColor(dark),
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    painter = painterResource(ClimaUiIcons.Right),
                    contentDescription = null,
                    tint = WeatherGlassSpecs.accentColor(dark),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
fun V53NoticeBanner(
    text: String,
    @DrawableRes iconRes: Int = ClimaUiIcons.Info,
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
) {
    val dark = LocalClimaDarkTheme.current
    val resolvedAccent = accentColor ?: WeatherGlassSpecs.accentColor(dark)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color =
            if (dark) {
                Color(0xAD062641)
            } else {
                Color(0xE8DFEEF9)
            },
        border = BorderStroke(1.dp, resolvedAccent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = resolvedAccent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                color = if (dark) Color(0xFFC7DEEE) else Color(0xFF243B53),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun <T> V53SegmentedOptions(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "seg",
) {
    val dark = LocalClimaDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(
                            brush =
                                if (isSelected) {
                                    Brush.linearGradient(listOf(ClimaButtonGradientStart, ClimaButtonGradientEnd))
                                } else {
                                    Brush.linearGradient(
                                        if (dark) {
                                            listOf(Color(0x88082540), Color(0x88061C33))
                                        } else {
                                            listOf(Color(0xFFD9E9F7), Color(0xFFCDE2F4))
                                        }
                                    )
                                },
                        )
                        .clickable { onSelect(value) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .testTag("${testTagPrefix}_${value.toString().lowercase(Locale.US)}"),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.Transparent,
                    shape = shape,
                ) {
                    Text(
                        text = label,
                        color =
                            when {
                                isSelected -> ClimaTextOnDark
                                dark -> Color(0xFFC9E2F5)
                                else -> Color(0xFF1F3C5A)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun V53PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    secondary: Boolean = false,
) {
    val dark = LocalClimaDarkTheme.current
    val shape = RoundedCornerShape(13.dp)
    val bgBrush =
        if (!secondary) {
            Brush.linearGradient(listOf(ClimaButtonGradientStart, ClimaButtonGradientEnd))
        } else if (dark) {
            Brush.linearGradient(listOf(Color(0xAA0C3254), Color(0xAA07223B)))
        } else {
            Brush.linearGradient(listOf(Color(0xFFDCECF9), Color(0xFFCCE2F5)))
        }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().minimumInteractiveComponentSize(),
        shape = shape,
        color = Color.Transparent,
        border =
            BorderStroke(
                1.dp,
                if (!secondary) Color(0xFF48B5F0) else WeatherGlassSpecs.glassBorderColor(dark),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(bgBrush)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (!secondary || dark) ClimaTextOnDark else Color(0xFF102A43),
                    modifier = Modifier.padding(end = 8.dp).size(17.dp),
                )
            }
            Text(
                text = text,
                color = if (!secondary || dark) ClimaTextOnDark else Color(0xFF102A43),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
fun V53SwitchRow(
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTestTag: String,
    modifier: Modifier = Modifier,
) {
    val dark = LocalClimaDarkTheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = description,
            color = WeatherGlassSpecs.mutedTextColor(dark),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(switchTestTag),
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = ClimaTextOnDark,
                    checkedTrackColor = ClimaAccentCyan,
                ),
        )
    }
}

@Composable
fun V53ToastOverlay(
    message: String?,
    modifier: Modifier = Modifier,
) {
    if (message.isNullOrBlank()) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("clima_toast"),
        shape = RoundedCornerShape(12.dp),
        color = ClimaToastBg,
        border = BorderStroke(1.dp, ClimaToastBorder),
        shadowElevation = 8.dp,
    ) {
        Text(
            text = message,
            color = ClimaTextOnDark,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun V53PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "pull_to_refresh_box",
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier.fillMaxSize().testTag(testTag),
        contentAlignment = Alignment.TopCenter,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                containerColor = ClimaDeepSky.copy(alpha = 0.94f),
                color = ClimaAccentCyan,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}

/**
 * Pure API 24+ formatters for V53 hourly and 16-day labels in pt-BR.
 */
object ClimaDateLabels {
    private val ptBr = Locale("pt", "BR")

    private val isoDateParser = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    }

    private val dayMonthFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("dd/MM", ptBr)
    }

    private val epochTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat("HH:mm", ptBr)
    }

    private val calendarHolder = object : ThreadLocal<Calendar>() {
        override fun initialValue(): Calendar = Calendar.getInstance()
    }

    fun formatHourLabel(
        index: Int,
        isoTime: String,
        epochMillis: Long?,
        timezoneId: String?,
        utcOffsetSeconds: Int,
        timeFormat: TimeFormatPreference,
    ): String {
        if (index == 0) return "Agora"
        if (epochMillis != null) {
            return WeatherTimeUtils.formatLocalTime(
                epochMillis = epochMillis,
                timezoneId = timezoneId,
                utcOffsetSeconds = utcOffsetSeconds,
                timeFormat = timeFormat,
            )
        }
        return isoTime.substringAfter('T', isoTime).take(5)
    }

    fun formatDailyWeekdayAndDate(index: Int, dateIso: String): Pair<String, String> {
        val defaultTz = java.util.TimeZone.getDefault()
        val parser = isoDateParser.get()!!.apply {
            timeZone = defaultTz
            isLenient = false
        }
        val parsed =
            runCatching { parser.parse(dateIso) }.getOrNull()
                ?: return Pair(if (index == 0) "Hoje" else dateIso, dateIso)

        val datePart = dayMonthFormatter.get()!!.apply { timeZone = defaultTz }.format(parsed)
        val weekday =
            when (index) {
                0 -> "Hoje"
                1 -> "Amanhã"
                else -> {
                    val cal = calendarHolder.get()!!.apply {
                        timeZone = defaultTz
                        time = parsed
                    }
                    when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.SUNDAY -> "Domingo"
                        Calendar.MONDAY -> "Segunda"
                        Calendar.TUESDAY -> "Terça"
                        Calendar.WEDNESDAY -> "Quarta"
                        Calendar.THURSDAY -> "Quinta"
                        Calendar.FRIDAY -> "Sexta"
                        Calendar.SATURDAY -> "Sábado"
                        else -> datePart
                    }
                }
            }
        return Pair(weekday, datePart)
    }

    fun formatSunTime(isoDateTime: String?): String {
        if (isoDateTime.isNullOrBlank()) return "—"
        return isoDateTime.substringAfter('T', isoDateTime).take(5)
    }

    fun formatUpdatedTimestamp(
        epochMillis: Long?,
        timezoneId: String?,
        utcOffsetSeconds: Int,
    ): String {
        if (epochMillis == null) return "—"
        return WeatherTimeUtils.formatLocalDateTime(
            epochMillis = epochMillis,
            timezoneId = timezoneId,
            utcOffsetSeconds = utcOffsetSeconds,
        )
    }

    fun formatEpochTimeOnly(
        epochMillis: Long,
        timezoneId: String?,
        utcOffsetSeconds: Int,
    ): String {
        val formatter = epochTimeFormatter.get()!!.apply {
            timeZone = WeatherTimeUtils.resolveTimeZone(timezoneId, utcOffsetSeconds)
        }
        return formatter.format(Date(epochMillis))
    }
}
