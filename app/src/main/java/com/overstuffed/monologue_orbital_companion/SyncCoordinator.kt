package com.overstuffed.monologue_orbital_companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Central coordinator for synchronizing phone-side data (alarms, calendar events) with the
 * Monologue Orbital watchface.
 *
 * [requestSync] is the single entry point called by [PebbleListenerService] whenever the watch asks
 * for a re-sync. When an individual data source ([AlarmMonitor], [CalendarMonitor]) has been
 * initialized and enabled, a re-sync will re-read the current data and push it to the watch.
 *
 * Enable/disable for alarm sync is exposed via [setAlarmSyncEnabled] / [isAlarmSyncEnabled], and for
 * calendar sync via [setCalendarSyncEnabled] / [isCalendarSyncEnabled] so the UI layer (Task 4) can
 * wire them to user toggles. Per-calendar filtering is exposed via [getAvailableCalendars] and
 * [setCalendarSelected].
 */
object SyncCoordinator {

    private const val TAG = PebbleMessageKeys.LOG_TAG

    /** Epoch millis of the last SyncRequest received from the watch, or null if never. */
    private val _lastSyncRequestTime = MutableStateFlow<Long?>(null)

    /** Public read-only snapshot of [lastSyncRequestTime]. */
    val lastSyncRequestTime: StateFlow<Long?> = _lastSyncRequestTime.asStateFlow()

    /**
     * Called by [PebbleListenerService] when the watch sends a SyncRequest (message key 110).
     * Records the current time and then triggers [requestSync].
     */
    fun onWatchSyncRequested() {
        _lastSyncRequestTime.value = System.currentTimeMillis()
        requestSync()
    }

    /**
     * A hook the listener invokes when the watch requests a re-sync. Later tasks may point this at
     * additional sync routines; until then it only logs.
     */
    var onSyncRequested: (() -> Unit)? = null

    var pebbleCommunicationManager: PebbleCommunicationManager? = null
        private set

    var alarmMonitor: AlarmMonitor? = null
        private set

    var calendarMonitor: CalendarMonitor? = null
        private set

    private var appContext: Context? = null

    private val _alarmSyncEnabled = MutableStateFlow(false)

    /** Whether alarm syncing is currently active (reactive — UI collects this). */
    val alarmSyncEnabled: StateFlow<Boolean> = _alarmSyncEnabled.asStateFlow()

    private val _calendarSyncEnabled = MutableStateFlow(false)

    /** Whether calendar syncing is currently active (reactive — UI collects this). */
    val calendarSyncEnabled: StateFlow<Boolean> = _calendarSyncEnabled.asStateFlow()

    /** Backing store for persisted settings (created during [initialize]). */
    private var settingsRepository: SettingsRepository? = null

    /** Scope used for best-effort (async) persistence and initial settings load. */
    private var syncScope: CoroutineScope? = null

    private var initialized = false

    /**
     * Initializes the sync coordinator and its shared [PebbleCommunicationManager], [AlarmMonitor],
     * and [CalendarMonitor] using the given (application) context. Idempotent — subsequent calls are
     * ignored.
     *
     * The UI layer should call this once (e.g. from the Activity/Application) before toggling any sync
     * toggle. The application context is retained so the coordinator outlives any single Activity.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settingsRepository = SettingsRepository(appContext!!)
        syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = PebbleCommunicationManager(appContext!!)
        pebbleCommunicationManager = manager
        alarmMonitor = AlarmMonitor(appContext!!, manager)
        calendarMonitor = CalendarMonitor(appContext!!, manager)
        Log.i(TAG, "initialize: SyncCoordinator ready (AlarmMonitor + CalendarMonitor created).")
        loadPersistedSettings()
    }

    /**
     * Asynchronously loads the persisted settings from DataStore and applies them.
     *
     * Runs off the main thread so it never blocks UI. Any DataStore read failure is caught and
     * logged; the app simply falls back to defaults.
     */
    private fun loadPersistedSettings() {
        val repo = settingsRepository
        val scope = syncScope
        if (repo == null || scope == null) return
        scope.launch {
            try {
                val alarmEnabled = repo.alarmSyncEnabled.first()
                val calendarEnabled = repo.calendarSyncEnabled.first()
                if (repo.wasCalendarSelectionStored()) {
                    calendarMonitor?.applyPersistedSelection(repo.selectedCalendarIds.first())
                }
                applyAlarmSyncEnabled(alarmEnabled)
                applyCalendarSyncEnabled(calendarEnabled)
                Log.i(
                    TAG,
                    "loadPersistedSettings: applied alarm=$alarmEnabled, calendar=$calendarEnabled.",
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadPersistedSettings: failed to load persisted settings.", e)
            }
        }
    }

    /** Updates in-memory alarm state (and its flow) without persisting — used when loading. */
    private fun applyAlarmSyncEnabled(enabled: Boolean) {
        alarmMonitor?.enabled = enabled
        _alarmSyncEnabled.value = enabled
    }

    /** Updates in-memory calendar state (and its flow) without persisting — used when loading. */
    private fun applyCalendarSyncEnabled(enabled: Boolean) {
        calendarMonitor?.enabled = enabled
        _calendarSyncEnabled.value = enabled
    }

    /** Best-effort, async persistence. Failures are logged and never propagated to the caller. */
    private fun persist(block: suspend (SettingsRepository) -> Unit) {
        val repo = settingsRepository
        val scope = syncScope
        if (repo == null || scope == null) return
        scope.launch {
            try {
                block(repo)
            } catch (e: Exception) {
                Log.e(TAG, "persist: failed to save setting.", e)
            }
        }
    }

    // ---------------------------------------------------------------
    // Alarm sync
    // ---------------------------------------------------------------

    /**
     * Enables or disables alarm syncing. When enabling, the [AlarmMonitor] registers its receiver and
     * performs an initial read + send; when disabling it unregisters and stops sending.
     */
    fun setAlarmSyncEnabled(enabled: Boolean) {
        val monitor = alarmMonitor
        if (monitor == null) {
            Log.w(TAG, "setAlarmSyncEnabled($enabled): AlarmMonitor not initialized; call initialize(context) first.")
            return
        }
        monitor.enabled = enabled
        _alarmSyncEnabled.value = enabled
        persist { it.setAlarmSyncEnabled(enabled) }
    }

    /** Whether alarm syncing is currently active. */
    fun isAlarmSyncEnabled(): Boolean = alarmMonitor?.enabled == true

    // ---------------------------------------------------------------
    // Calendar sync
    // ---------------------------------------------------------------

    /**
     * Enables or disables calendar syncing. When enabling, the [CalendarMonitor] registers its
     * [android.database.ContentObserver] and performs an initial query + send; when disabling it
     * unregisters the observer and stops sending.
     */
    fun setCalendarSyncEnabled(enabled: Boolean) {
        val monitor = calendarMonitor
        if (monitor == null) {
            Log.w(TAG, "setCalendarSyncEnabled($enabled): CalendarMonitor not initialized; call initialize(context) first.")
            return
        }
        monitor.enabled = enabled
        _calendarSyncEnabled.value = enabled
        persist { it.setCalendarSyncEnabled(enabled) }
    }

    /** Whether calendar syncing is currently active. */
    fun isCalendarSyncEnabled(): Boolean = calendarMonitor?.enabled == true

    /**
     * Returns the list of currently visible system calendars with their selected state.
     *
     * If [android.Manifest.permission.READ_CALENDAR] is not granted an empty list is returned.
     */
    fun getAvailableCalendars(): List<CalendarInfo> {
        val ctx = appContext
        val monitor = calendarMonitor
        if (ctx == null || monitor == null) {
            Log.w(TAG, "getAvailableCalendars: SyncCoordinator not initialized.")
            return emptyList()
        }
        return monitor.getAvailableCalendars()
    }

    /**
     * Toggles the selected state of a specific calendar by its [calendarId].
     *
     * If calendar syncing is currently enabled, the event list is re-queried and pushed immediately.
     */
    fun setCalendarSelected(calendarId: Long, selected: Boolean) {
        val monitor = calendarMonitor
        if (monitor == null) {
            Log.w(TAG, "setCalendarSelected: CalendarMonitor not initialized.")
            return
        }
        monitor.setCalendarSelected(calendarId, selected)
        persist { it.setSelectedCalendarIds(monitor.currentSelectedIds()) }
    }

    // ---------------------------------------------------------------
    // Request sync
    // ---------------------------------------------------------------

    /**
     * Requests a full re-sync of phone data to the watch.
     *
     * Triggered by [PebbleListenerService] whenever the watch sends a
     * [PebbleMessageKeys.KEY_SYNC_REQUEST]. Re-reads and pushes current alarm time (if alarm sync
     * enabled) and calendar events (if calendar sync enabled + permission granted). A log message is
     * emitted if the calendar permission is missing so the user knows to grant it.
     */
    fun requestSync() {
        Log.i(TAG, "requestSync: re-sync requested by watch.")
        alarmMonitor?.syncCurrentAlarm()

        val calMonitor = calendarMonitor
        if (calMonitor != null && calMonitor.enabled) {
            val ctx = appContext
            if (ctx != null && calMonitor.checkPermission(ctx)) {
                calMonitor.syncCurrentEvents()
            } else {
                Log.i(TAG, "Calendar permission not granted, skipping sync.")
            }
        }

        onSyncRequested?.invoke()
            ?: Log.d(TAG, "requestSync: no onSyncRequested hook set; skipping.")
    }

    // ---------------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------------

    /** Releases the alarm monitor, calendar monitor, and shared pebble manager. Call when shutting down. */
    fun shutdown() {
        alarmMonitor?.cleanup()
        calendarMonitor?.cleanup()
        pebbleCommunicationManager?.close()
        syncScope?.cancel()
        syncScope = null
        settingsRepository = null
        _alarmSyncEnabled.value = false
        _calendarSyncEnabled.value = false
        initialized = false
        appContext = null
        alarmMonitor = null
        calendarMonitor = null
        pebbleCommunicationManager = null
        Log.i(TAG, "shutdown: SyncCoordinator released.")
    }
}