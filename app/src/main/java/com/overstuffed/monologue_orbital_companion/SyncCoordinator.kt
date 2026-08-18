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

    var timerMonitor: TimerMonitor? = null
        private set

    private var appContext: Context? = null

    private val _alarmSyncEnabled = MutableStateFlow(false)

    /** Whether alarm syncing is currently active (reactive — UI collects this). */
    val alarmSyncEnabled: StateFlow<Boolean> = _alarmSyncEnabled.asStateFlow()

    private val _calendarSyncEnabled = MutableStateFlow(false)

    /** Whether calendar syncing is currently active (reactive — UI collects this). */
    val calendarSyncEnabled: StateFlow<Boolean> = _calendarSyncEnabled.asStateFlow()

    private val _timerSyncEnabled = MutableStateFlow(false)

    /** Whether timer syncing is currently active (reactive — UI collects this). */
    val timerSyncEnabled: StateFlow<Boolean> = _timerSyncEnabled.asStateFlow()

    /** Backing store for persisted settings (created during [initialize]). */
    private var settingsRepository: SettingsRepository? = null

    /** Master sync gate: when off, every per-channel monitor is disabled and the service stops. */
    private val _masterSyncEnabled = MutableStateFlow(true)

    /** Whether the master sync gate is currently enabled (reactive — UI collects this). */
    val masterSyncEnabled: StateFlow<Boolean> = _masterSyncEnabled.asStateFlow()

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
        val manager = PebbleCommunicationManager()
        pebbleCommunicationManager = manager
        alarmMonitor = AlarmMonitor(appContext!!, manager)
        calendarMonitor = CalendarMonitor(appContext!!, manager)
        timerMonitor = TimerMonitor(appContext!!, manager)
        // Start listening for incoming messages from the watch via classic PebbleKit broadcast,
        // and for the watch's ACK/NACK confirmations of our outgoing sends. All Pebble broadcast
        // receivers are registered here in one place so their lifecycles stay consistent.
        PebbleListenerService.register(appContext!!)
        manager.registerAckNackReceivers(appContext!!)
        Log.i(TAG, "initialize: SyncCoordinator ready (AlarmMonitor + CalendarMonitor + TimerMonitor created).")
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
                val masterEnabled = repo.masterSyncEnabled.first()
                _masterSyncEnabled.value = masterEnabled
                var alarmEnabled = false
                var calendarEnabled = false
                var timerEnabled = false
                // Per-channel settings are only applied when the master gate is on; otherwise the
                // monitors stay disabled (their listeners unregistered) and the service stops.
                if (masterEnabled) {
                    alarmEnabled = repo.alarmSyncEnabled.first()
                    calendarEnabled = repo.calendarSyncEnabled.first()
                    timerEnabled = repo.timerSyncEnabled.first()
                    if (repo.wasCalendarSelectionStored()) {
                        calendarMonitor?.applyPersistedSelection(repo.selectedCalendarIds.first())
                    }
                    applyAlarmSyncEnabled(alarmEnabled)
                    applyCalendarSyncEnabled(calendarEnabled)
                    applyTimerSyncEnabled(timerEnabled)
                }
                Log.i(
                    TAG,
                    "loadPersistedSettings: master=$masterEnabled, applied alarm=$alarmEnabled, " +
                        "calendar=$calendarEnabled, timer=$timerEnabled.",
                )
                // Persisted settings are now authoritative — start/stop the sync service to match.
                updateServiceState()
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

    /** Updates in-memory timer state (and its flow) without persisting — used when loading. */
    private fun applyTimerSyncEnabled(enabled: Boolean) {
        timerMonitor?.enabled = enabled
        _timerSyncEnabled.value = enabled
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
    // Master sync gate
    // ---------------------------------------------------------------

    /**
     * Enables or disables the master sync gate.
     *
     * Disabling disables every per-channel monitor — unregistering the alarm receiver, the
     * calendar observer, and the timer callback — and stops the foreground [SyncService]. The
     * individual per-channel *preferences* are left untouched so they are restored when the
     * master gate is turned back on.
     *
     * Enabling re-applies the persisted per-channel settings, re-registering any listeners that
     * were previously on and restarting the service if needed.
     */
    fun setMasterSyncEnabled(enabled: Boolean) {
        if (enabled) {
            Log.i(TAG, "setMasterSyncEnabled(true): master sync enabled; re-applying persisted settings.")
            _masterSyncEnabled.value = true
            persist { it.setMasterSyncEnabled(true) }
            reloadIndividualSettings()
            updateServiceState()
        } else {
            Log.i(TAG, "setMasterSyncEnabled(false): master sync disabled; stopping all sync.")
            _masterSyncEnabled.value = false
            persist { it.setMasterSyncEnabled(false) }
            applyAlarmSyncEnabled(false)
            applyCalendarSyncEnabled(false)
            applyTimerSyncEnabled(false)
            updateServiceState()
        }
    }

    /** Whether the master sync gate is currently enabled. */
    fun isMasterSyncEnabled(): Boolean = _masterSyncEnabled.value

    /**
     * Re-applies the persisted per-channel settings to the monitors. Used when the master gate
     * is turned back on so previously-enabled channels resume immediately.
     */
    private fun reloadIndividualSettings() {
        val repo = settingsRepository
        val scope = syncScope
        if (repo == null || scope == null) return
        scope.launch {
            try {
                val alarmEnabled = repo.alarmSyncEnabled.first()
                val calendarEnabled = repo.calendarSyncEnabled.first()
                val timerEnabled = repo.timerSyncEnabled.first()
                applyAlarmSyncEnabled(alarmEnabled)
                applyCalendarSyncEnabled(calendarEnabled)
                applyTimerSyncEnabled(timerEnabled)
                updateServiceState()
                Log.i(
                    TAG,
                    "reloadIndividualSettings: applied alarm=$alarmEnabled, calendar=$calendarEnabled, " +
                        "timer=$timerEnabled.",
                )
            } catch (e: Exception) {
                Log.e(TAG, "reloadIndividualSettings: failed to re-apply settings.", e)
            }
        }
    }

    /**
     * Starts or stops the foreground [SyncService] based on the master gate and whether any
     * sync feature is enabled.
     *
     * The service runs only while the master gate is on AND at least one of alarm/calendar/timer
     * syncing is on, and is stopped when the master gate is off or all three channels are off.
     * Safe to call repeatedly — starting an already-running service merely re-posts the
     * notification, and stopping a non-running service is a no-op.
     */
    private fun updateServiceState() {
        val ctx = appContext ?: return
        if (isMasterSyncEnabled() &&
            (isAlarmSyncEnabled() || isCalendarSyncEnabled() || isTimerSyncEnabled())
        ) {
            SyncService.start(ctx)
        } else {
            SyncService.stop(ctx)
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
        updateServiceState()
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
        updateServiceState()
    }

    /** Whether calendar syncing is currently active. */
    fun isCalendarSyncEnabled(): Boolean = calendarMonitor?.enabled == true

    // ---------------------------------------------------------------
    // Timer sync
    // ---------------------------------------------------------------

    /**
     * Enables or disables timer syncing. When enabling, the [TimerMonitor] registers as the
     * [ClockTimerNotificationListener] callback and performs an initial read + send; when disabling
     * it unregisters the callback and stops sending.
     */
    fun setTimerSyncEnabled(enabled: Boolean) {
        val monitor = timerMonitor
        if (monitor == null) {
            Log.w(TAG, "setTimerSyncEnabled($enabled): TimerMonitor not initialized; call initialize(context) first.")
            return
        }
        monitor.enabled = enabled
        _timerSyncEnabled.value = enabled
        persist { it.setTimerSyncEnabled(enabled) }
        updateServiceState()
    }

    /** Whether timer syncing is currently active. */
    fun isTimerSyncEnabled(): Boolean = timerMonitor?.enabled == true

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

        // Timer sync is a re-read of the currently active timer when enabled.
        timerMonitor?.syncCurrentTimer()

        onSyncRequested?.invoke()
            ?: Log.d(TAG, "requestSync: no onSyncRequested hook set; skipping.")
    }

    // ---------------------------------------------------------------
    // Shutdown
    // ---------------------------------------------------------------

    /** Releases the alarm monitor, calendar monitor, shared pebble manager, and listener. Call when shutting down. */
    fun shutdown() {
        alarmMonitor?.cleanup()
        calendarMonitor?.cleanup()
        timerMonitor?.cleanup()
        pebbleCommunicationManager?.unregisterAckNackReceivers()
        PebbleListenerService.unregister(appContext ?: return)
        pebbleCommunicationManager?.close()
        syncScope?.cancel()
        syncScope = null
        settingsRepository = null
        _alarmSyncEnabled.value = false
        _calendarSyncEnabled.value = false
        _timerSyncEnabled.value = false
        initialized = false
        appContext = null
        alarmMonitor = null
        calendarMonitor = null
        timerMonitor = null
        pebbleCommunicationManager = null
        Log.i(TAG, "shutdown: SyncCoordinator released.")
    }
}