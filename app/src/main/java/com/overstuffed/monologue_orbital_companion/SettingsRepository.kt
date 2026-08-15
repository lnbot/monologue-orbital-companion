package com.overstuffed.monologue_orbital_companion

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single DataStore instance shared across the app for persisted settings.
 *
 * Declared once at file top-level so that every [SettingsRepository] (and any other consumer) reads
 * and writes the same underlying DataStore file ("settings.preferences_pb").
 */
val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Persists the configuration-screen settings — the alarm/calendar sync toggles and the set of
 * selected calendar IDs — using Jetpack DataStore Preferences.
 *
 * All reads are exposed as cold [Flow]s that emit the latest persisted value (with a sensible
 * default when nothing has been written yet); all writes are `suspend` functions so they can be
 * driven off the calling coroutine and are inherently crash-safe (DataStore writes atomically).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val AlarmSyncEnabled = booleanPreferencesKey("alarm_sync_enabled")
        val CalendarSyncEnabled = booleanPreferencesKey("calendar_sync_enabled")
        val TimerSyncEnabled = booleanPreferencesKey("timer_sync_enabled")
        val SelectedCalendarIds = stringSetPreferencesKey("selected_calendar_ids")
    }

    /** Whether alarm syncing is enabled. Defaults to `false` when never set. */
    val alarmSyncEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AlarmSyncEnabled] ?: false }

    /** Whether calendar syncing is enabled. Defaults to `false` when never set. */
    val calendarSyncEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.CalendarSyncEnabled] ?: false }

    /** Whether timer syncing is enabled. Defaults to `false` when never set. */
    val timerSyncEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TimerSyncEnabled] ?: false }

    /**
     * The set of calendar IDs (as strings) the user has explicitly selected.
     *
     * Defaults to an empty set when never written. An empty set is ambiguous between "first launch,
     * no choice made yet" and "user unselected every calendar", so callers that need to distinguish
     * those cases should use [wasCalendarSelectionStored] before interpreting this.
     */
    val selectedCalendarIds: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.SelectedCalendarIds] ?: emptySet() }

    /**
     * `true` when a calendar selection has ever been persisted.
     *
     * This distinguishes the "first launch, never chosen" case (nothing stored → default to *all*
     * calendars selected, without writing) from "user explicitly chose none" (stored as an empty
     * set → no calendars selected).
     */
    suspend fun wasCalendarSelectionStored(): Boolean =
        context.dataStore.data.first().contains(Keys.SelectedCalendarIds)

    suspend fun setAlarmSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AlarmSyncEnabled] = enabled }
    }

    suspend fun setCalendarSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CalendarSyncEnabled] = enabled }
    }

    suspend fun setTimerSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TimerSyncEnabled] = enabled }
    }

    suspend fun setSelectedCalendarIds(ids: Set<String>) {
        context.dataStore.edit { it[Keys.SelectedCalendarIds] = ids }
    }
}
