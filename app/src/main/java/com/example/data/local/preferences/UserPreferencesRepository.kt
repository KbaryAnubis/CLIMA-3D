package com.example.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.PrecipitationUnit
import com.example.data.model.PressureUnit
import com.example.data.model.TemperatureUnit
import com.example.data.model.TimeFormatPreference
import com.example.data.model.VisibilityUnit
import com.example.data.model.WindSpeedUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.clima3DPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "clima3d_user_preferences",
)

/**
 * User-configurable display units and in-app settings persisted via Jetpack DataStore.
 * Note: Clima 3D alerts remain strictly in-app (no push, status bar, or lock-screen notifications).
 */
data class UserPreferences(
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val windSpeedUnit: WindSpeedUnit = WindSpeedUnit.KMH,
    val pressureUnit: PressureUnit = PressureUnit.HPA,
    val precipitationUnit: PrecipitationUnit = PrecipitationUnit.MM,
    val visibilityUnit: VisibilityUnit = VisibilityUnit.KM,
    val timeFormat: TimeFormatPreference = TimeFormatPreference.H24,
    val animatedBackgroundEnabled: Boolean = true,
    val inAppAlertsEnabled: Boolean = true,
    val autoRefreshOnOpen: Boolean = true,
    val selectedLocationId: Long? = null,
)

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.clima3DPreferencesDataStore)

    val preferencesFlow: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferences(
                temperatureUnit = parseEnum(prefs[Keys.TEMPERATURE_UNIT], TemperatureUnit.CELSIUS),
                windSpeedUnit = parseEnum(prefs[Keys.WIND_SPEED_UNIT], WindSpeedUnit.KMH),
                pressureUnit = parseEnum(prefs[Keys.PRESSURE_UNIT], PressureUnit.HPA),
                precipitationUnit = parseEnum(prefs[Keys.PRECIPITATION_UNIT], PrecipitationUnit.MM),
                visibilityUnit = parseEnum(prefs[Keys.VISIBILITY_UNIT], VisibilityUnit.KM),
                timeFormat = parseEnum(prefs[Keys.TIME_FORMAT], TimeFormatPreference.H24),
                animatedBackgroundEnabled = prefs[Keys.ANIMATED_BACKGROUND] ?: true,
                inAppAlertsEnabled = prefs[Keys.IN_APP_ALERTS] ?: true,
                autoRefreshOnOpen = prefs[Keys.AUTO_REFRESH_ON_OPEN] ?: true,
                selectedLocationId = prefs[Keys.SELECTED_LOCATION_ID],
            )
        }

    suspend fun getCurrentPreferences(): UserPreferences = preferencesFlow.first()

    suspend fun setTemperatureUnit(unit: TemperatureUnit) {
        dataStore.edit { it[Keys.TEMPERATURE_UNIT] = unit.name }
    }

    suspend fun setWindSpeedUnit(unit: WindSpeedUnit) {
        dataStore.edit { it[Keys.WIND_SPEED_UNIT] = unit.name }
    }

    suspend fun setPressureUnit(unit: PressureUnit) {
        dataStore.edit { it[Keys.PRESSURE_UNIT] = unit.name }
    }

    suspend fun setPrecipitationUnit(unit: PrecipitationUnit) {
        dataStore.edit { it[Keys.PRECIPITATION_UNIT] = unit.name }
    }

    suspend fun setVisibilityUnit(unit: VisibilityUnit) {
        dataStore.edit { it[Keys.VISIBILITY_UNIT] = unit.name }
    }

    suspend fun setTimeFormat(format: TimeFormatPreference) {
        dataStore.edit { it[Keys.TIME_FORMAT] = format.name }
    }

    suspend fun setAnimatedBackgroundEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ANIMATED_BACKGROUND] = enabled }
    }

    suspend fun setInAppAlertsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.IN_APP_ALERTS] = enabled }
    }

    suspend fun setAutoRefreshOnOpen(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_REFRESH_ON_OPEN] = enabled }
    }

    suspend fun setSelectedLocationId(locationId: Long?) {
        dataStore.edit { prefs ->
            if (locationId == null) {
                prefs.remove(Keys.SELECTED_LOCATION_ID)
            } else {
                prefs[Keys.SELECTED_LOCATION_ID] = locationId
            }
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    private object Keys {
        val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
        val WIND_SPEED_UNIT = stringPreferencesKey("wind_speed_unit")
        val PRESSURE_UNIT = stringPreferencesKey("pressure_unit")
        val PRECIPITATION_UNIT = stringPreferencesKey("precipitation_unit")
        val VISIBILITY_UNIT = stringPreferencesKey("visibility_unit")
        val TIME_FORMAT = stringPreferencesKey("time_format")
        val ANIMATED_BACKGROUND = booleanPreferencesKey("animated_background_enabled")
        val IN_APP_ALERTS = booleanPreferencesKey("in_app_alerts_enabled")
        val AUTO_REFRESH_ON_OPEN = booleanPreferencesKey("auto_refresh_on_open")
        val SELECTED_LOCATION_ID = longPreferencesKey("selected_location_id")
    }
}
