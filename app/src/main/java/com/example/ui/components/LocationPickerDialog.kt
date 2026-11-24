package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.CitySearchResult
import com.example.data.model.SavedLocation
import com.example.data.model.TemperatureUnit
import com.example.data.model.WeatherUnitConverter
import com.example.media.ClimaUiIcons
import com.example.ui.state.ClimaUiState
import com.example.ui.state.SavedLocationWeatherSummary
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertAttentionAmber
import com.example.ui.theme.ClimaAlertHighRed
import com.example.ui.theme.ClimaButtonGradientEnd
import com.example.ui.theme.ClimaButtonGradientStart
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs

/**
 * Lightweight quick location picker modal presented directly over HomeScreen.
 *
 * Intentionally avoids initializing MapLibre or ExoPlayer, reusing the shared
 * Open-Meteo Geocoding search, Android location permission/GPS flow, and Room saved locations.
 */
@Composable
fun LocationPickerDialog(
    uiState: ClimaUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSelectSearchResult: (CitySearchResult) -> Unit,
    onSelectSavedLocation: (SavedLocation) -> Unit,
    onUseMyLocationClick: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenAppLocationSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val pickerState = uiState.locationPickerState
    val permissionSnapshot = uiState.locationPermissionSnapshot
    val savedLocations = uiState.savedLocations

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = 660.dp)
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .testTag("location_picker_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = if (dark) Color(0xF508213B) else Color(0xF8F2F8FF),
            border = BorderStroke(1.dp, if (dark) Color(0x8852DBFF) else Color(0x660E6CB8)),
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header (always pinned)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.location_picker_title),
                            color = WeatherGlassSpecs.primaryTextColor(dark),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("location_picker_title"),
                        )
                        Text(
                            text = stringResource(R.string.location_picker_subtitle),
                            color = WeatherGlassSpecs.mutedTextColor(dark),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = if (dark) Color(0x8810365A) else Color(0xFFDCECF9),
                        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp).testTag("location_picker_close_button"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_wx_close),
                                contentDescription = stringResource(R.string.action_close),
                                tint = WeatherGlassSpecs.primaryTextColor(dark),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .testTag("location_picker_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // "Use my location" quick action + permission/GPS feedback
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.65f)),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("location_picker_use_my_location_card"),
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (dark) {
                                                Brush.linearGradient(listOf(Color(0xD9124678), Color(0xD90A2E52)))
                                            } else {
                                                Brush.linearGradient(listOf(Color(0xFFDBEBFD), Color(0xFFCEE4FA)))
                                            },
                                        )
                                        .minimumInteractiveComponentSize()
                                        .clickable(
                                            enabled = !pickerState.isLocatingUser,
                                            onClick = onUseMyLocationClick,
                                        )
                                        .padding(horizontal = 13.dp, vertical = 11.dp)
                                        .testTag("location_picker_use_my_location_button"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (pickerState.isLocatingUser) {
                                    CircularProgressIndicator(
                                        color = ClimaAccentCyan,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp).testTag("location_picker_gps_loading"),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(ClimaUiIcons.Location),
                                        contentDescription = stringResource(R.string.location_picker_use_my_location),
                                        tint = WeatherGlassSpecs.accentColor(dark),
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text =
                                            if (pickerState.isLocatingUser) {
                                                stringResource(R.string.location_picker_locating)
                                            } else {
                                                stringResource(R.string.location_picker_use_my_location)
                                            },
                                        color = WeatherGlassSpecs.primaryTextColor(dark),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = permissionSnapshot.statusSummaryPt,
                                        color = WeatherGlassSpecs.mutedTextColor(dark),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        if (!permissionSnapshot.hasAnyPermission) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (dark) Color(0x99062641) else Color(0xFFE4F0FC),
                                border = BorderStroke(1.dp, ClimaAlertAttentionAmber.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth().testTag("location_picker_permission_banner"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text =
                                            if (permissionSnapshot.permanentlyDenied) {
                                                "Permissão de localização bloqueada pelo Android. Libere nas configurações do aparelho ou selecione uma cidade manualmente."
                                            } else {
                                                "Conceda permissão de localização aproximada ou precisa para usar o GPS, ou pesquise abaixo."
                                            },
                                        color = WeatherGlassSpecs.mutedTextColor(dark),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (permissionSnapshot.permanentlyDenied) {
                                        V53PrimaryButton(
                                            text = stringResource(R.string.location_picker_open_settings),
                                            iconRes = ClimaUiIcons.Settings,
                                            secondary = true,
                                            onClick = onOpenAppLocationSettings,
                                            modifier = Modifier.testTag("location_picker_open_settings_button"),
                                        )
                                    } else {
                                        V53PrimaryButton(
                                            text = stringResource(R.string.location_picker_grant_permission),
                                            iconRes = ClimaUiIcons.Location,
                                            secondary = true,
                                            onClick = onRequestLocationPermission,
                                            modifier = Modifier.testTag("location_picker_grant_permission_button"),
                                        )
                                    }
                                }
                            }
                        }

                        if (pickerState.gpsErrorMessage != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (dark) Color(0xAD062641) else Color(0xE8DFEEF9),
                                border = BorderStroke(1.dp, ClimaAlertAttentionAmber.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth().testTag("location_picker_gps_error"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = pickerState.gpsErrorMessage,
                                        color = WeatherGlassSpecs.primaryTextColor(dark),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    V53PrimaryButton(
                                        text = stringResource(R.string.location_picker_retry),
                                        iconRes = ClimaUiIcons.Refresh,
                                        secondary = true,
                                        onClick = onUseMyLocationClick,
                                        modifier = Modifier.testTag("location_picker_gps_retry_button"),
                                    )
                                }
                            }
                        }
                    }

                    // Search Open-Meteo Geocoding input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.location_picker_search_label).uppercase(),
                            color = WeatherGlassSpecs.accentColor(dark),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        OutlinedTextField(
                            value = pickerState.searchQuery,
                            onValueChange = onSearchQueryChanged,
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.location_picker_search_placeholder),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(ClimaUiIcons.Search),
                                    contentDescription = null,
                                    tint = WeatherGlassSpecs.accentColor(dark),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = {
                                when {
                                    pickerState.isSearching -> {
                                        CircularProgressIndicator(
                                            color = ClimaAccentCyan,
                                            strokeWidth = 2.dp,
                                            modifier =
                                                Modifier
                                                    .size(18.dp)
                                                    .testTag("location_picker_search_loading"),
                                        )
                                    }

                                    pickerState.searchQuery.isNotEmpty() -> {
                                        IconButton(
                                            onClick = { onSearchQueryChanged("") },
                                            modifier =
                                                Modifier
                                                    .size(40.dp)
                                                    .testTag("location_picker_clear_search_button"),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_wx_close),
                                                contentDescription = "Limpar busca",
                                                tint = WeatherGlassSpecs.mutedTextColor(dark),
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch() }),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = WeatherGlassSpecs.primaryTextColor(dark),
                                    unfocusedTextColor = WeatherGlassSpecs.primaryTextColor(dark),
                                    focusedBorderColor = ClimaAccentCyan,
                                    unfocusedBorderColor = WeatherGlassSpecs.glassBorderColor(dark),
                                    focusedContainerColor = if (dark) Color(0x8808233C) else Color(0xFFEAF4FD),
                                    unfocusedContainerColor = if (dark) Color(0x7708233C) else Color(0xFFE4F1FC),
                                ),
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.fillMaxWidth().testTag("location_picker_search_input"),
                        )
                    }

                    // Search feedback / results
                    when {
                        pickerState.searchErrorMessage != null -> {
                            V53NoticeBanner(
                                text = pickerState.searchErrorMessage,
                                iconRes = ClimaUiIcons.Alert,
                                accentColor = ClimaAlertHighRed,
                                modifier = Modifier.testTag("location_picker_search_error"),
                            )
                        }

                        pickerState.hasSearchedCurrentQuery &&
                            !pickerState.isSearching &&
                            pickerState.searchResults.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.location_picker_empty_results),
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("location_picker_search_empty"),
                            )
                        }

                        pickerState.searchResults.isNotEmpty() -> {
                            Text(
                                text = stringResource(R.string.location_picker_search_results_title),
                                color = WeatherGlassSpecs.accentColor(dark),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            pickerState.searchResults.forEachIndexed { idx, result ->
                                PickerSearchResultRow(
                                    result = result,
                                    dark = dark,
                                    onSelect = { onSelectSearchResult(result) },
                                    modifier = Modifier.testTag("location_picker_search_result_$idx"),
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = WeatherGlassSpecs.glassBorderColor(dark))

                    // Compact saved locations section
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("location_picker_saved_section"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.location_picker_saved_title),
                            color = WeatherGlassSpecs.primaryTextColor(dark),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${savedLocations.size}",
                            color = WeatherGlassSpecs.accentColor(dark),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (savedLocations.isEmpty()) {
                        Text(
                            text = stringResource(R.string.location_picker_no_saved),
                            color = WeatherGlassSpecs.mutedTextColor(dark),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("location_picker_saved_empty"),
                        )
                    } else {
                        savedLocations.forEach { saved ->
                            PickerSavedLocationRow(
                                location = saved,
                                isActive = saved.id == uiState.activeLocation?.id,
                                summary = uiState.savedLocationSummaries[saved.id],
                                temperatureUnit = uiState.preferences.temperatureUnit,
                                dark = dark,
                                onSelect = { onSelectSavedLocation(saved) },
                            )
                        }
                    }
                }

                // Footer close button (always pinned)
                V53PrimaryButton(
                    text = stringResource(R.string.action_close),
                    onClick = onDismiss,
                    secondary = true,
                    modifier = Modifier.testTag("location_picker_footer_close_button"),
                )
            }
        }
    }
}

@Composable
private fun PickerSearchResultRow(
    result: CitySearchResult,
    dark: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = if (dark) Color(0x9E0B2F52) else Color(0xFFE4F1FC),
        border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(ClimaUiIcons.Pin),
                contentDescription = null,
                tint = WeatherGlassSpecs.accentColor(dark),
                modifier = Modifier.size(17.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = result.name,
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val region = result.displayRegionLabel()
                if (region.isNotBlank()) {
                    Text(
                        text = region,
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ClimaAccentCyan.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.55f)),
            ) {
                Text(
                    text = "Selecionar",
                    color = if (dark) ClimaTextOnDark else Color(0xFF0E6CB8),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerSavedLocationRow(
    location: SavedLocation,
    isActive: Boolean,
    summary: SavedLocationWeatherSummary?,
    temperatureUnit: TemperatureUnit,
    dark: Boolean,
    onSelect: () -> Unit,
) {
    val bgBrush =
        when {
            isActive -> Brush.linearGradient(listOf(ClimaButtonGradientStart, ClimaButtonGradientEnd))
            dark -> Brush.linearGradient(listOf(Color(0x9E0D345B), Color(0x9E08233F)))
            else -> Brush.linearGradient(listOf(Color(0xFFE5F2FC), Color(0xFFD7EAFB)))
        }
    val borderColor =
        if (isActive) {
            ClimaAccentCyan
        } else {
            WeatherGlassSpecs.glassBorderColor(dark)
        }
    val primaryColor = if (isActive || dark) ClimaTextOnDark else WeatherGlassSpecs.primaryTextColor(false)
    val subtitleColor = if (isActive) Color(0xFFD9EFFD) else WeatherGlassSpecs.mutedTextColor(dark)

    Surface(
        shape = RoundedCornerShape(13.dp),
        color = Color.Transparent,
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("location_picker_saved_item_${location.id}"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(bgBrush)
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(if (location.isCurrentLocation) ClimaUiIcons.Location else ClimaUiIcons.Pin),
                contentDescription = null,
                tint = if (isActive) ClimaTextOnDark else WeatherGlassSpecs.accentColor(dark),
                modifier = Modifier.size(17.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = location.name,
                    color = primaryColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    location.displaySubtitle().ifBlank { location.toCityMapCoordinate().formatted() }
                Text(
                    text = subtitle,
                    color = subtitleColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (summary?.temperatureC != null) {
                Text(
                    text = WeatherUnitConverter.formatTemperature(summary.temperatureC, temperatureUnit) ?: "—",
                    color = primaryColor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            if (isActive) {
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = Color(0x33FFFFFF),
                    border = BorderStroke(1.dp, Color(0x66FFFFFF)),
                    modifier = Modifier.testTag("location_picker_active_badge_${location.id}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(ClimaUiIcons.Check),
                            contentDescription = null,
                            tint = ClimaTextOnDark,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(R.string.location_picker_active_badge),
                            color = ClimaTextOnDark,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
