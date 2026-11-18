package com.example.safar20.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "safar_preferences")

data class EmergencyContact(
    val name: String = "",
    val number: String = ""
)

class PreferencesDataStore(private val context: Context) {

    companion object {
        val KEY_IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val KEY_SPEED_UNIT = stringPreferencesKey("speed_unit")
        val KEY_MQTT_BROKER_URI = stringPreferencesKey("mqtt_broker_uri")
        val KEY_MQTT_USERNAME = stringPreferencesKey("mqtt_username")
        val KEY_MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
        val KEY_IS_DEV_MODE = booleanPreferencesKey("is_dev_mode")
        val KEY_HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val KEY_EMERGENCY_NAME_1 = stringPreferencesKey("emergency_name_1")
        val KEY_EMERGENCY_NUM_1 = stringPreferencesKey("emergency_num_1")
        val KEY_EMERGENCY_NAME_2 = stringPreferencesKey("emergency_name_2")
        val KEY_EMERGENCY_NUM_2 = stringPreferencesKey("emergency_num_2")
        val KEY_CUSTOM_TRIP_NAMES = stringPreferencesKey("custom_trip_names")

        const val UNIT_KMH = "km/h"
        const val UNIT_MPH = "mph"

        fun isValidEmergencyNumber(number: String): Boolean {
            val clean = number.trim()
            return clean.isEmpty() || clean.matches(Regex("^\\+?[1-9]\\d{7,14}$"))
        }

        fun parseTripNames(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.lines()
                .mapNotNull { line ->
                    val idx = line.indexOf('\t')
                    if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
                }
                .toMap()
        }

        fun serializeTripNames(map: Map<String, String>): String {
            return map.entries.joinToString("\n") { (k, v) ->
                "${k.replace("\n", "").replace("\t", "")}\t${v.replace("\n", " ").replace("\t", " ")}"
            }
        }
    }

    val isDarkTheme: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_IS_DARK_THEME] ?: true
        }

    val speedUnit: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_SPEED_UNIT] ?: UNIT_KMH
        }

    val mqttBrokerUri: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_MQTT_BROKER_URI] ?: ""
        }

    val mqttUsername: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_MQTT_USERNAME] ?: ""
        }

    val mqttPassword: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_MQTT_PASSWORD] ?: ""
        }

    val isDevModeEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_IS_DEV_MODE] ?: false
        }

    val hasCompletedOnboarding: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_HAS_COMPLETED_ONBOARDING] ?: false
        }

    val emergencyContacts: Flow<List<EmergencyContact>> = context.dataStore.data
        .map { preferences ->
            listOf(
                EmergencyContact(
                    name = preferences[KEY_EMERGENCY_NAME_1] ?: "",
                    number = preferences[KEY_EMERGENCY_NUM_1] ?: ""
                ),
                EmergencyContact(
                    name = preferences[KEY_EMERGENCY_NAME_2] ?: "",
                    number = preferences[KEY_EMERGENCY_NUM_2] ?: ""
                )
            )
        }

    val customTripNames: Flow<Map<String, String>> = context.dataStore.data
        .map { preferences ->
            parseTripNames(preferences[KEY_CUSTOM_TRIP_NAMES] ?: "")
        }

    suspend fun setDarkTheme(isDark: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_DARK_THEME] = isDark
        }
    }

    suspend fun setSpeedUnit(unit: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SPEED_UNIT] = if (unit == UNIT_MPH) UNIT_MPH else UNIT_KMH
        }
    }

    suspend fun toggleSpeedUnit() {
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_SPEED_UNIT] ?: UNIT_KMH
            preferences[KEY_SPEED_UNIT] = if (current == UNIT_KMH) UNIT_MPH else UNIT_KMH
        }
    }

    suspend fun setMqttBrokerUri(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MQTT_BROKER_URI] = uri.trim()
        }
    }

    suspend fun setMqttUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MQTT_USERNAME] = username.trim()
        }
    }

    suspend fun setMqttPassword(password: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MQTT_PASSWORD] = password
        }
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_DEV_MODE] = enabled
        }
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_HAS_COMPLETED_ONBOARDING] = completed
        }
    }

    suspend fun setEmergencyContacts(contact1: EmergencyContact, contact2: EmergencyContact) {
        context.dataStore.edit { preferences ->
            preferences[KEY_EMERGENCY_NAME_1] = contact1.name.trim()
            preferences[KEY_EMERGENCY_NUM_1] = contact1.number.trim()
            preferences[KEY_EMERGENCY_NAME_2] = contact2.name.trim()
            preferences[KEY_EMERGENCY_NUM_2] = contact2.number.trim()
        }
    }

    suspend fun setCustomTripName(tripId: String, name: String) {
        context.dataStore.edit { preferences ->
            val current = parseTripNames(preferences[KEY_CUSTOM_TRIP_NAMES] ?: "").toMutableMap()
            val clean = name.trim()
            if (clean.isEmpty()) {
                current.remove(tripId)
            } else {
                current[tripId] = clean
            }
            preferences[KEY_CUSTOM_TRIP_NAMES] = serializeTripNames(current)
        }
    }

    suspend fun removeCustomTripName(tripId: String) {
        setCustomTripName(tripId, "")
    }
}
