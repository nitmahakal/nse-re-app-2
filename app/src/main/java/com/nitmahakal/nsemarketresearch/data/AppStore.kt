package com.nitmahakal.nsemarketresearch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("app_settings")

class AppStore(private val context: Context) {
    private val lastUpdatedKey = stringPreferencesKey("last_updated")
    private val scheduleEnabledKey = booleanPreferencesKey("schedule_enabled")
    private val scheduleHourKey = intPreferencesKey("schedule_hour")
    private val scheduleMinuteKey = intPreferencesKey("schedule_minute")
    private val lastStatusKey = stringPreferencesKey("last_status")

    val lastUpdated: Flow<String> = context.dataStore.data.map { it[lastUpdatedKey] ?: "" }
    val lastStatus: Flow<String> = context.dataStore.data.map { it[lastStatusKey] ?: "" }
    val scheduleEnabled: Flow<Boolean> = context.dataStore.data.map { it[scheduleEnabledKey] ?: false }
    val scheduleHour: Flow<Int> = context.dataStore.data.map { it[scheduleHourKey] ?: 18 }
    val scheduleMinute: Flow<Int> = context.dataStore.data.map { it[scheduleMinuteKey] ?: 30 }

    suspend fun setLastUpdated(value: String) = context.dataStore.edit { it[lastUpdatedKey] = value }
    suspend fun setLastStatus(value: String) = context.dataStore.edit { it[lastStatusKey] = value }
    suspend fun setSchedule(enabled: Boolean, hour: Int, minute: Int) = context.dataStore.edit {
        it[scheduleEnabledKey] = enabled
        it[scheduleHourKey] = hour
        it[scheduleMinuteKey] = minute
    }
}
