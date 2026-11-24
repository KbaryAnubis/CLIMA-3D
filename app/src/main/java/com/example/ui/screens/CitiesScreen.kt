package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.example.R
import com.example.data.model.CitySearchResult
import com.example.data.model.SavedLocation
import com.example.data.model.WeatherUnitConverter
import com.example.maps.CityMapLibreSection
import com.example.maps.CityMapUiState
import com.example.media.ClimaUiIcons
import com.example.ui.components.AnimatedWeatherIcon
import com.example.ui.components.V53GlassCard
import com.example.ui.components.V53NoticeBanner
import com.example.ui.components.V53PageHeading
import com.example.ui.components.V53PrimaryButton
import com.example.ui.components.V53SectionHeader
import com.example.ui.components.V53SegmentedOptions
import com.example.ui.state.CitiesSubTab
import com.example.ui.state.ClimaUiState
import com.example.ui.state.SavedLocationWeatherSummary
import com.example.ui.theme.ClimaAccentCyan
import com.example.ui.theme.ClimaAlertHighRed
import com.example.ui.theme.ClimaTextOnDark
import com.example.ui.theme.ClimaToastEmerald
import com.example.ui.theme.LocalClimaDarkTheme
import com.example.ui.theme.WeatherGlassSpecs

/**
 * Complete native reproduction of CLIMA 3D V53 `citiesScreen` (Cidades):
 * Header -> 3-tab toolbar (Minhas localizações / Selecionar no mapa / Pesquisar cidade) ->
 * Interactive MapLibre + OSM map + custom point saver + saved locations with cached weather ->
 * Real Open-Meteo Geocoding search with disambiguation of same-name cities.
 */
@Composable
fun CitiesScreen(
    uiState: ClimaUiState,
    contentPadding: PaddingValues,
    onSelectSubTab: (CitiesSubTab) -> Unit,
    onMapStateChanged: (CityMapUiState) -> Unit,
    onPointNameChanged: (String) -> Unit,
    onSaveSelectedPoint: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onAddCityResult: (CitySearchResult) -> Unit,
    onSelectSavedLocation: (SavedLocation) -> Unit,
    onRemoveSavedLocation: (SavedLocation) -> Unit,
) {
    val tabSpec = remember { WeatherGlassSpecs.forTab(com.example.ui.theme.ClimaTab.CITIES) }
    val dark = LocalClimaDarkTheme.current

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp).testTag("cities_screen_list"),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "cities_heading") {
                V53PageHeading(
                    eyebrow = stringResource(R.string.cities_eyebrow),
                    title = stringResource(R.string.cities_title),
                    subtitle = stringResource(R.string.cities_subtitle),
                )
            }

            item(key = "cities_toolbar") {
                V53SegmentedOptions(
                    options =
                        listOf(
                            CitiesSubTab.SAVED to "Salvas",
                            CitiesSubTab.MAP to "Mapa",
                            CitiesSubTab.SEARCH to "Pesquisar",
                        ),
                    selected = uiState.citiesSubTab,
                    onSelect = onSelectSubTab,
                    testTagPrefix = "cities_subtab",
                )
            }

            if (uiState.citiesSubTab == CitiesSubTab.SEARCH) {
                item(key = "cities_search_panel") {
                    CitySearchPanel(
                        uiState = uiState,
                        cardAlpha = tabSpec.cardAlpha,
                        onQueryChanged = onSearchQueryChanged,
                        onSubmit = onSubmitSearch,
                        onAddResult = onAddCityResult,
                        onBackToMap = { onSelectSubTab(CitiesSubTab.MAP) },
                    )
                }
            } else {
                // Interactive MapLibre + OpenStreetMap section (preserves test tags for MapLibreAndRadarTest)
                item(key = "cities_map_libre") {
                    CityMapLibreSection(
                        initialState = uiState.cityMapState,
                        isOnline = uiState.isOnline,
                        onStateChanged = onMapStateChanged,
                    )
                }

                // V53 .map-selected custom pin saver card
                item(key = "cities_save_point_card") {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, if (dark) Color(0xFF5ABFFF) else Color(0x990E6CB8)),
                        modifier = Modifier.fillMaxWidth().testTag("cities_save_point_card"),
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (dark) {
                                            Brush.verticalGradient(listOf(Color(0xE8123E6B), Color(0xEE061E3B)))
                                        } else {
                                            Brush.verticalGradient(listOf(Color(0xF0E6F2FC), Color(0xF0D5E8F9)))
                                        },
                                    )
                                    .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Ponto selecionado no mapa",
                                        color = WeatherGlassSpecs.primaryTextColor(dark),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = uiState.cityMapState.selectedMarker.coordinate.formatted(),
                                        color = WeatherGlassSpecs.accentColor(dark),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(99.dp),
                                    color = ClimaAccentCyan.copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.5f)),
                                ) {
                                    Text(
                                        text = "Escolha livre",
                                        color = WeatherGlassSpecs.primaryTextColor(dark),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = uiState.customPointNameInput,
                                onValueChange = onPointNameChanged,
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.cities_point_name_placeholder),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                colors = cityInputColors(dark),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().testTag("cities_point_name_input"),
                            )

                            V53PrimaryButton(
                                text = stringResource(R.string.cities_save_point),
                                iconRes = ClimaUiIcons.Plus,
                                onClick = onSaveSelectedPoint,
                                modifier = Modifier.testTag("cities_save_point_button"),
                            )

                            Text(
                                text = "O zoom mostra ruas e terrenos no OpenStreetMap; as previsões usam a grade meteorológica real do Open-Meteo.",
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // Saved locations list section
                item(key = "cities_saved_header") {
                    V53SectionHeader(
                        title = "Minhas localizações (${uiState.savedLocations.size})",
                        iconRes = ClimaUiIcons.Pin,
                    )
                }

                if (uiState.savedLocations.isEmpty()) {
                    item(key = "cities_empty_saved") {
                        V53GlassCard(alpha = tabSpec.cardAlpha) {
                            Text(
                                text = "Nenhuma localização salva ainda. Pesquise uma cidade ou marque um ponto no mapa acima.",
                                color = WeatherGlassSpecs.mutedTextColor(dark),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    items(uiState.savedLocations, key = { it.id }) { loc ->
                        SavedLocationCard(
                            location = loc,
                            isActive = loc.id == uiState.activeLocation?.id,
                            summary = uiState.savedLocationSummaries[loc.id],
                            uiState = uiState,
                            onSelect = { onSelectSavedLocation(loc) },
                            onRemove = { onRemoveSavedLocation(loc) },
                        )
                    }
                }

                item(key = "cities_add_other_btn") {
                    V53PrimaryButton(
                        text = stringResource(R.string.cities_add_other),
                        iconRes = ClimaUiIcons.Search,
                        onClick = { onSelectSubTab(CitiesSubTab.SEARCH) },
                        modifier = Modifier.testTag("cities_open_search_button"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CitySearchPanel(
    uiState: ClimaUiState,
    cardAlpha: Float,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onAddResult: (CitySearchResult) -> Unit,
    onBackToMap: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        V53GlassCard(
            alpha = cardAlpha,
            modifier = Modifier.testTag("cities_search_panel"),
        ) {
            Text(
                text = "PESQUISAR CIDADES (OPEN-METEO GEOCODING)",
                color = WeatherGlassSpecs.accentColor(dark),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.citySearchQuery,
                onValueChange = onQueryChanged,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.cities_search_placeholder),
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
                    if (uiState.isSearchingCities) {
                        CircularProgressIndicator(
                            color = ClimaAccentCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                colors = cityInputColors(dark),
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.fillMaxWidth().testTag("cities_search_input"),
            )

            Spacer(modifier = Modifier.height(10.dp))

            when {
                uiState.citySearchErrorMessage != null -> {
                    V53NoticeBanner(
                        text = uiState.citySearchErrorMessage,
                        iconRes = ClimaUiIcons.Alert,
                        accentColor = ClimaAlertHighRed,
                    )
                }

                uiState.citySearchQuery.trim().length < 2 -> {
                    Text(
                        text = "Digite ao menos 2 letras para buscar cidades em todo o mundo com desambiguação de estado e país.",
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                !uiState.isSearchingCities && uiState.citySearchResults.isEmpty() -> {
                    Text(
                        text = "Nenhuma cidade encontrada para \"${uiState.citySearchQuery.trim()}\".",
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("cities_search_empty"),
                    )
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.citySearchResults.forEachIndexed { idx, result ->
                            Surface(
                                shape = RoundedCornerShape(13.dp),
                                color = if (dark) Color(0x9E0B2F52) else Color(0xFFE4F1FC),
                                border = BorderStroke(1.dp, WeatherGlassSpecs.glassBorderColor(dark)),
                                modifier = Modifier.fillMaxWidth().testTag("city_search_result_$idx"),
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onAddResult(result) }
                                            .padding(horizontal = 12.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(ClimaUiIcons.Pin),
                                        contentDescription = null,
                                        tint = WeatherGlassSpecs.accentColor(dark),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                                        Text(
                                            text = result.toCityMapCoordinate().formatted(),
                                            color = WeatherGlassSpecs.mutedTextColor(dark).copy(alpha = 0.8f),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = ClimaAccentCyan.copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, ClimaAccentCyan.copy(alpha = 0.6f)),
                                    ) {
                                        Text(
                                            text = "Adicionar",
                                            color = if (dark) ClimaTextOnDark else Color(0xFF0E6CB8),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        V53PrimaryButton(
            text = stringResource(R.string.cities_back_to_map),
            iconRes = ClimaUiIcons.Map,
            secondary = true,
            onClick = onBackToMap,
            modifier = Modifier.testTag("cities_back_to_map_button"),
        )
    }
}

@Composable
private fun SavedLocationCard(
    location: SavedLocation,
    isActive: Boolean,
    summary: SavedLocationWeatherSummary?,
    uiState: ClimaUiState,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val dark = LocalClimaDarkTheme.current
    val bgBrush =
        if (dark) {
            Brush.linearGradient(
                if (isActive) {
                    listOf(Color(0xE6154B7E), Color(0xE60B2F54))
                } else {
                    listOf(Color(0xCC123E6C), Color(0xCC082746))
                },
            )
        } else {
            Brush.linearGradient(
                if (isActive) {
                    listOf(Color(0xFFD5EBF9), Color(0xFFC2E1F7))
                } else {
                    listOf(Color(0xFFEAF4FC), Color(0xFFDCEDF9))
                },
            )
        }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border =
            BorderStroke(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) ClimaAccentCyan else WeatherGlassSpecs.glassBorderColor(dark),
            ),
        modifier = Modifier.fillMaxWidth().testTag("saved_location_card_${location.id}"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(bgBrush)
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (summary != null) {
                AnimatedWeatherIcon(
                    condition = summary.condition,
                    size = 44.dp,
                )
            } else {
                Icon(
                    painter = painterResource(if (location.isCurrentLocation) ClimaUiIcons.Location else ClimaUiIcons.Pin),
                    contentDescription = null,
                    tint = if (location.isCurrentLocation) ClimaToastEmerald else WeatherGlassSpecs.accentColor(dark),
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = location.name,
                        color = WeatherGlassSpecs.primaryTextColor(dark),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isActive) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = ClimaAccentCyan.copy(alpha = 0.22f),
                        ) {
                            Text(
                                text = "Ativa",
                                color = if (dark) ClimaAccentCyan else Color(0xFF0E6CB8),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (location.isCurrentLocation) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = ClimaToastEmerald.copy(alpha = 0.2f),
                        ) {
                            Text(
                                text = "GPS",
                                color = ClimaToastEmerald,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else if (location.isCustomMapPin) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = Color(0x3370CBF6),
                        ) {
                            Text(
                                text = "Mapa",
                                color = WeatherGlassSpecs.accentColor(dark),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                val subtitle = location.displaySubtitle().ifBlank { location.toCityMapCoordinate().formatted() }
                Text(
                    text = subtitle,
                    color = WeatherGlassSpecs.mutedTextColor(dark),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (summary != null) {
                    Text(
                        text = summary.descriptionPt,
                        color = WeatherGlassSpecs.mutedTextColor(dark),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (summary?.temperatureC != null) {
                Text(
                    text = WeatherUnitConverter.formatTemperature(summary.temperatureC, uiState.preferences.temperatureUnit) ?: "—",
                    color = WeatherGlassSpecs.primaryTextColor(dark),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(44.dp).testTag("remove_location_${location.id}"),
            ) {
                Icon(
                    painter = painterResource(ClimaUiIcons.Trash),
                    contentDescription = "Remover ${location.name}",
                    tint = WeatherGlassSpecs.mutedTextColor(dark),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun cityInputColors(dark: Boolean) =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = WeatherGlassSpecs.primaryTextColor(dark),
        unfocusedTextColor = WeatherGlassSpecs.primaryTextColor(dark),
        focusedBorderColor = WeatherGlassSpecs.accentColor(dark),
        unfocusedBorderColor = WeatherGlassSpecs.glassBorderColor(dark),
        focusedContainerColor = if (dark) Color(0x88072038) else Color(0xFFF6FAFF),
        unfocusedContainerColor = if (dark) Color(0x66072038) else Color(0xFFF0F7FE),
        cursorColor = WeatherGlassSpecs.accentColor(dark),
    )
