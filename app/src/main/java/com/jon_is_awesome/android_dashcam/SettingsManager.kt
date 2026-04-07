package com.jon_is_awesome.android_dashcam

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val SEGMENT_LENGTH_MINUTES = intPreferencesKey("segment_length_minutes")
        val STORAGE_LOCATION = stringPreferencesKey("storage_location")
        val SHOW_SPEED = booleanPreferencesKey("show_speed")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val SHOW_ALTITUDE = booleanPreferencesKey("show_altitude")
        val SHOW_TIMESTAMP = booleanPreferencesKey("show_timestamp")
        val USE_METRIC = booleanPreferencesKey("use_metric")
        val MAX_STORAGE_GB = intPreferencesKey("max_storage_gb")
    }

    val segmentLengthMinutes: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[SEGMENT_LENGTH_MINUTES] ?: 5
        }

    val storageLocation: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[STORAGE_LOCATION]
        }

    val showSpeed: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_SPEED] ?: true }

    val showCoordinates: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_COORDINATES] ?: true }

    val showAltitude: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_ALTITUDE] ?: true }

    val showTimestamp: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_TIMESTAMP] ?: true }

    val useMetric: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[USE_METRIC] ?: true }

    val maxStorageGb: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[MAX_STORAGE_GB] ?: 10 }

    suspend fun setSegmentLength(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[SEGMENT_LENGTH_MINUTES] = minutes
        }
    }

    suspend fun setStorageLocation(path: String) {
        context.dataStore.edit { preferences ->
            preferences[STORAGE_LOCATION] = path
        }
    }

    suspend fun setShowSpeed(show: Boolean) {
        context.dataStore.edit { it[SHOW_SPEED] = show }
    }

    suspend fun setShowCoordinates(show: Boolean) {
        context.dataStore.edit { it[SHOW_COORDINATES] = show }
    }

    suspend fun setShowAltitude(show: Boolean) {
        context.dataStore.edit { it[SHOW_ALTITUDE] = show }
    }

    suspend fun setShowTimestamp(show: Boolean) {
        context.dataStore.edit { it[SHOW_TIMESTAMP] = show }
    }

    suspend fun setUseMetric(metric: Boolean) {
        context.dataStore.edit { it[USE_METRIC] = metric }
    }

    suspend fun setMaxStorageGb(gb: Int) {
        context.dataStore.edit { it[MAX_STORAGE_GB] = gb }
    }
}
