package com.overstuffed.monologue_orbital_companion

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Monitors the phone's system calendar events and pushes their timestamps to the Monologue Orbital
 * watchface whenever changes are detected.
 *
 * Behaviour:
 * - Queries [CalendarContract.Events] for events whose [CalendarContract.Events.DTSTART] falls
 *   within the next 24 hours. The **nearest 16** events (fewer if there are fewer within 24h) are
 *   converted to epoch-seconds and sent to the watch.
 * - Supports per-calendar filtering: the user can select which visible calendars contribute events.
 * - Registers a [ContentObserver] on both `CalendarContract.Events.CONTENT_URI` and
 *   `CalendarContract.Calendars.CONTENT_URI` so that any calendar change triggers an automatic
 *   re-sync while calendar syncing is enabled.
 * - **Permission handling:**  [checkPermission] must return `true` before any queries are executed.
 *   The class never requests the permission itself — the UI layer (Task 4) handles that. If the
 *   permission is not granted, all query methods silently return empty results and log accordingly.
 *
 * Enable/disable is controlled via [enabled]. When enabled the [ContentObserver] is registered and
 * an initial query + send is performed so the watch always gets the current set of events.
 *
 * @param context used to access [ContentResolver] and [CalendarContract].
 * @param pebbleCommunicationManager used to push calendar epoch-seconds to the watchface.
 */
class CalendarMonitor(
    context: Context,
    private val pebbleCommunicationManager: PebbleCommunicationManager,
) {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tracks whether we currently hold a ContentObserver registration. */
    private var observerRegistered: Boolean = false

    /**
     * The set of explicitly-selected calendar IDs (as strings), or `null` when the user has never
     * made a choice. `null` means *every* visible calendar is selected by default; an explicit
     * (possibly empty) set overrides that default.
     */
    private var selectedIds: Set<String>? = null

    /** The latest list of visible calendars, cached for the UI. */
    private val availableCalendars = mutableListOf<CalendarInfo>()

    /**
     * Whether calendar syncing is active.
     *
     * When set to `true` the [ContentObserver] is registered and an initial read + send is performed.
     * When set to `false` the observer is unregistered and no further sends happen.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                registerObserver()
                syncCurrentEvents()
            } else {
                unregisterObserver()
            }
            Log.i(TAG, "Calendar sync ${if (value) "enabled" else "disabled"}.")
        }

    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            if (!enabled) return
            Log.i(TAG, "Calendar changed: events collected for sync.")
            syncCurrentEvents()
        }
    }

    // ---------------------------------------------------------------
    // Permission
    // ---------------------------------------------------------------

    /**
     * Returns `true` when [Manifest.permission.READ_CALENDAR] is currently granted.
     *
     * This is a **read-only check** — the permission is never requested here. The UI layer (Task 4)
     * should request it when the user enables calendar sync.
     */
    fun checkPermission(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "checkPermission: READ_CALENDAR not granted.")
        }
        return granted
    }

    // ---------------------------------------------------------------
    // Calendar selection (per-calendar filtering)
    // ---------------------------------------------------------------

    /**
     * Returns the list of currently visible system calendars with their selected state.
     *
     * If [Manifest.permission.READ_CALENDAR] is not granted an empty list is returned and the
     * reason is logged.
     */
    fun getAvailableCalendars(): List<CalendarInfo> {
        if (!checkPermission(appContext)) {
            Log.i(TAG, "getAvailableCalendars: READ_CALENDAR not granted; returning empty list.")
            return emptyList()
        }
        refreshAvailableCalendars()
        return availableCalendars.toList()
    }

    /**
     * Toggles the selected state of a specific calendar.
     *
     * If calendar syncing is currently [enabled], the event list is re-queried and pushed to the
     * watch immediately.
     */
    fun setCalendarSelected(calendarId: Long, selected: Boolean) {
        val index = availableCalendars.indexOfFirst { it.calendarId == calendarId }
        if (index == -1) {
            Log.w(TAG, "setCalendarSelected: unknown calendar id=$calendarId.")
            return
        }
        // Start from the current explicit selection, or (before the first choice) every visible
        // calendar, then add/remove the toggled calendar.
        val current = selectedIds ?: availableCalendars.map { it.calendarId.toString() }.toSet()
        selectedIds = if (selected) current + calendarId.toString() else current - calendarId.toString()
        val updated = availableCalendars[index].copy(selected = selected)
        availableCalendars[index] = updated
        Log.i(
            TAG,
            "setCalendarSelected: calendar '${updated.displayName}' id=$calendarId selected=$selected.",
        )
        if (enabled) {
            syncCurrentEvents()
        }
    }

    /**
     * The set of currently-selected calendar IDs (as strings). Returns the explicit selection, or
     * — when the user has never chosen — every visible calendar.
     */
    fun currentSelectedIds(): Set<String> =
        selectedIds ?: availableCalendars.map { it.calendarId.toString() }.toSet()

    /**
     * Applies a persisted selection (calendar IDs as strings) loaded from settings.
     *
     * Calendars present in [ids] are selected; visible calendars absent from [ids] are unselected.
     * A non-empty [ids] that was loaded after a prior write reflects the user's previous choice
     * exactly (including the empty set meaning "none selected").
     */
    fun applyPersistedSelection(ids: Set<String>) {
        selectedIds = ids
        Log.i(TAG, "applyPersistedSelection: ${ids.size} selected calendar ID(s) loaded.")
        // If a list is already shown, re-derive it so the UI reflects the persisted selection.
        if (availableCalendars.isNotEmpty() && checkPermission(appContext)) {
            refreshAvailableCalendars()
        }
    }

    // ---------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------

    /**
     * Re-queries upcoming events and pushes them to the watchface.
     *
     * No-op while [enabled] is `false`. If [Manifest.permission.READ_CALENDAR] is not granted the
     * query returns an empty list and nothing is sent.
     */
    fun syncCurrentEvents() {
        if (!enabled) {
            Log.d(TAG, "syncCurrentEvents: calendar sync disabled; skipping.")
            return
        }
        val epochSeconds = queryUpcomingEventEpochSeconds()
        pebbleCommunicationManager.sendCalendarSync(appContext, epochSeconds)
        Log.i(
            TAG,
            "syncCurrentEvents: ${epochSeconds.size} events sent to watch.",
        )
    }

    // ---------------------------------------------------------------
    // Internal: event query
    // ---------------------------------------------------------------

    /**
     * Queries [CalendarContract.Events] for the nearest upcoming events within the next 24 hours
     * (up to [MAX_EVENTS] total).
     *
     * Only events whose [CalendarContract.Events.CALENDAR_ID] belongs to a selected calendar are
     * included. Returns the list of epoch-seconds (millis / 1000) sorted chronologically.
     *
     * Returns an empty list if [READ_CALENDAR] is not granted, no calendars are selected, or the
     * query fails.
     */
    private fun queryUpcomingEventEpochSeconds(): List<Long> {
        if (!checkPermission(appContext)) {
            Log.i(TAG, "queryUpcomingEvents: READ_CALENDAR not granted; returning empty.")
            return emptyList()
        }

        refreshAvailableCalendars()

        val selectedIds = availableCalendars
            .filter { it.selected }
            .map { it.calendarId }

        if (selectedIds.isEmpty()) {
            Log.i(TAG, "queryUpcomingEvents: no selected calendars; returning empty.")
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val end = now + SYNC_WINDOW_MS

        // Build the selection string with an IN clause for selected calendar IDs.
        // The IDs are Longs so this is SQL-injection-safe.
        val placeholders = selectedIds.joinToString(",") { "?" }
        val selection = """
            ${CalendarContract.Events.DTSTART} >= ? AND
            ${CalendarContract.Events.DTSTART} <= ? AND
            ${CalendarContract.Events.CALENDAR_ID} IN ($placeholders) AND
            ${CalendarContract.Events.ALL_DAY} != 1
        """.trimIndent().replace("\n", " ")
        val selectionArgs = arrayOf(
            now.toString(),
            end.toString(),
            *selectedIds.map { it.toString() }.toTypedArray(),
        )

        val projection = arrayOf(CalendarContract.Events.DTSTART)
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val epochSeconds = mutableListOf<Long>()
        try {
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val dtStartCol = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                if (dtStartCol < 0) {
                    Log.w(TAG, "queryUpcomingEvents: DTSTART column not found in cursor.")
                    return@use
                }
                while (cursor.moveToNext() && epochSeconds.size < MAX_EVENTS) {
                    val dtStartMillis = cursor.getLong(dtStartCol)
                    epochSeconds.add(dtStartMillis / 1000L)
                }
            } ?: Log.w(TAG, "queryUpcomingEvents: cursor was null (content provider not available).")
        } catch (e: SecurityException) {
            Log.w(TAG, "queryUpcomingEvents: SecurityException (permission revoked mid-operation?).", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "queryUpcomingEvents: failed to query calendar: ${e.message}", e)
            return emptyList()
        }

        Log.i(TAG, "queryUpcomingEvents: collected ${epochSeconds.size} upcoming events.")
        return epochSeconds
    }

    // ---------------------------------------------------------------
    // Internal: calendar list refresh
    // ---------------------------------------------------------------

    /**
     * Re-reads the list of visible calendars from [CalendarContract.Calendars] while preserving
     * any user-selectable state stored in [selectedIds].
     */
    private fun refreshAvailableCalendars() {
        if (!checkPermission(appContext)) {
            availableCalendars.clear()
            return
        }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1"

        val newCalendars = mutableListOf<CalendarInfo>()
        try {
            contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val nameCol = cursor.getColumnIndex(CalendarContract.Calendars.NAME)
                val accountCol = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                if (idCol < 0) {
                    Log.w(TAG, "refreshAvailableCalendars: _ID column not found.")
                    return@use
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Calendar $id"
                    val account = if (accountCol >= 0) cursor.getString(accountCol) else null
                    val selected = selectedIds?.contains(id.toString()) ?: true
                    newCalendars.add(CalendarInfo(id, name, account, selected))
                }
            } ?: Log.w(TAG, "refreshAvailableCalendars: cursor was null.")
        } catch (e: SecurityException) {
            Log.w(TAG, "refreshAvailableCalendars: SecurityException.", e)
            availableCalendars.clear()
            return
        } catch (e: Exception) {
            Log.e(TAG, "refreshAvailableCalendars: failed: ${e.message}", e)
            availableCalendars.clear()
            return
        }

        availableCalendars.clear()
        availableCalendars.addAll(newCalendars)
        Log.d(TAG, "refreshAvailableCalendars: ${newCalendars.size} visible calendars.")
    }

    // ---------------------------------------------------------------
    // ContentObserver lifecycle
    // ---------------------------------------------------------------

    private fun registerObserver() {
        if (observerRegistered) return
        try {
            contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,   // notifyForDescendants
                calendarObserver,
            )
            contentResolver.registerContentObserver(
                CalendarContract.Calendars.CONTENT_URI,
                true,
                calendarObserver,
            )
            observerRegistered = true
            Log.d(TAG, "Registered ContentObserver for calendar events/calendars.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register calendar ContentObserver.", e)
        }
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        try {
            contentResolver.unregisterContentObserver(calendarObserver)
            observerRegistered = false
            Log.d(TAG, "Unregistered calendar ContentObserver.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister calendar ContentObserver.", e)
        }
    }

    /** Releases the internal coroutine scope and unregisters any active observer. */
    fun cleanup() {
        enabled = false
        scope.cancel()
        Log.d(TAG, "cleanup: CalendarMonitor released.")
    }

    private companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG

        /** Maximum number of upcoming calendar events to push to the watch. */
        private const val MAX_EVENTS = 16

        /** Window (in milliseconds) for fetching upcoming events (24 hours). */
        private const val SYNC_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}