package com.overstuffed.monologue_orbital_companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The main configuration screen for the Monologue Orbital Companion.
 *
 * Wires the phone-side [SyncCoordinator] to user-facing Material 3 controls:
 * - a [Switch] to enable/disable alarm syncing,
 * - a [Switch] to enable/disable calendar syncing (requesting READ_CALENDAR the first time),
 * - a list of per-calendar [Checkbox]es (visible only while calendar sync is on),
 * - a "Sync Now" [Button] that performs a force sync.
 *
 * All user actions are logged to Logcat via [TAG].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Guards against re-launching the POST_NOTIFICATIONS dialog after permanent denial.
    var notificationPermissionPermanentlyDenied by remember { mutableStateOf(false) }

    // Controls the "Cannot Sync" dialog shown when the user taps "Sync Now" while no watch is connected.
    var showCannotSyncDialog by remember { mutableStateOf(false) }

    // Controls the "Notification access needed" dialog shown when the user enables Timer sync
    // without first granting notification-read access to this app.
    var showNotificationAccessDialog by remember { mutableStateOf(false) }

    // Reactive sync-enabled state collected from the coordinator, which is populated from
    // DataStore during initialize(). Toggles update automatically when persisted values load.
    val masterSyncEnabled by SyncCoordinator.masterSyncEnabled.collectAsState()
    val alarmSyncEnabled by SyncCoordinator.alarmSyncEnabled.collectAsState()
    val calendarSyncEnabled by SyncCoordinator.calendarSyncEnabled.collectAsState()
    val timerSyncEnabled by SyncCoordinator.timerSyncEnabled.collectAsState()
    var calendars by remember { mutableStateOf(SyncCoordinator.getAvailableCalendars()) }

    // Reactive transmit status from the shared manager (null-safe; defaults shown until a manager exists).
    val manager = SyncCoordinator.pebbleCommunicationManager
    val alarmSyncStatus by manager?.alarmSyncStatus
        ?.collectAsState(initial = ChannelSyncStatus())
        ?: remember { mutableStateOf(ChannelSyncStatus()) }
    val calendarSyncStatus by manager?.calendarSyncStatus
        ?.collectAsState(initial = ChannelSyncStatus())
        ?: remember { mutableStateOf(ChannelSyncStatus()) }

    // Reactive transmit status for the timer channel (mirrors alarm/calendar).
    val timerSyncStatus by manager?.timerSyncStatus
        ?.collectAsState(initial = ChannelSyncStatus())
        ?: remember { mutableStateOf(ChannelSyncStatus()) }

    // Reactive timestamp of the last SyncRequest received from the watch.
    val lastWatchSyncRequestTime by SyncCoordinator.lastSyncRequestTime.collectAsState()

    val calendarPermissionGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    // Launcher for the READ_CALENDAR runtime permission request.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Log.i(TAG, "READ_CALENDAR permission granted.")
            SyncCoordinator.setCalendarSyncEnabled(true)
            calendars = SyncCoordinator.getAvailableCalendars()
            Log.i(TAG, "Calendar sync enabled with ${calendars.size} available calendar(s).")
        } else {
            // Never set the toggle on if the user declined.
            val permanentlyDenied = (context as? Activity)?.let {
                !it.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            } ?: false
            val message = if (permanentlyDenied) {
                "Calendar permission was permanently denied. Grant it in system Settings to enable calendar sync."
            } else {
                "Calendar permission is needed to sync calendars."
            }
            Log.w(TAG, "READ_CALENDAR permission denied. permanentlyDenied=$permanentlyDenied")
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // Launcher for the POST_NOTIFICATIONS runtime permission (API 33+). Requested the first time
    // the user enables either sync so the persistent foreground-service notification is visible
    // in the shade. If denied, syncing still works — the notification is simply hidden.
    @Suppress("InlinedApi")
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            Log.i(TAG, "POST_NOTIFICATIONS permission granted.")
            // If a sync feature is running, re-post the foreground notification now that the
            // permission is available. SyncService.start() is idempotent — if the service is
            // already running the intent is re-delivered to onStartCommand, which re-posts the
            // notification in the shade.
            if (SyncCoordinator.isAlarmSyncEnabled() || SyncCoordinator.isCalendarSyncEnabled()) {
                SyncService.start(context.applicationContext)
                Log.i(TAG, "POST_NOTIFICATIONS granted; re-started SyncService to re-post notification.")
            }
        } else {
            val activity = context as? Activity
            val permanentlyDenied = activity?.let {
                !it.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            } ?: false
            if (permanentlyDenied) {
                notificationPermissionPermanentlyDenied = true
            }
            val message = if (permanentlyDenied) {
                "Notification permission was permanently denied. Grant it in system Settings to see sync notifications."
            } else {
                "Notification permission is needed to show the sync status in the notification shade."
            }
            Log.w(TAG, "POST_NOTIFICATIONS permission denied. permanentlyDenied=$permanentlyDenied")
            scope.launch {
                if (permanentlyDenied) {
                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = "Open Settings",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                } else {
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    /** Requests POST_NOTIFICATIONS on API 33+ if it has not been granted yet. Silently skips if
     * the user has permanently denied the permission (checked via a flag set in the launcher
     * callback), avoiding the system auto-deny loop. */
    fun maybeRequestNotificationPermission() {
        if (notificationPermissionPermanentlyDenied) {
            Log.w(TAG, "POST_NOTIFICATIONS permanently denied; skipping dialog launch.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Requesting POST_NOTIFICATIONS permission.")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun onMasterToggle(checked: Boolean) {
        Log.i(TAG, "UI: master sync toggle -> $checked")
        SyncCoordinator.setMasterSyncEnabled(checked)
        Log.i(TAG, if (checked) "Master sync enabled." else "Master sync disabled; all channels stopped.")
    }

    fun onAlarmToggle(checked: Boolean) {
        Log.i(TAG, "UI: alarm sync toggle -> $checked")
        SyncCoordinator.setAlarmSyncEnabled(checked)
        if (checked) {
            maybeRequestNotificationPermission()
            Log.i(TAG, "Alarm sync enabled.")
        } else {
            Log.i(TAG, "Alarm sync disabled.")
        }
    }

    fun onCalendarToggle(checked: Boolean) {
        Log.i(TAG, "UI: calendar sync toggle -> $checked")
        if (checked) {
            maybeRequestNotificationPermission()
            if (calendarPermissionGranted) {
                Log.i(TAG, "Calendar permission already granted; enabling calendar sync.")
                SyncCoordinator.setCalendarSyncEnabled(true)
                calendars = SyncCoordinator.getAvailableCalendars()
                Log.i(TAG, "Calendar sync enabled with ${calendars.size} available calendar(s).")
            } else {
                Log.i(TAG, "Calendar permission not granted; requesting READ_CALENDAR.")
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            }
        } else {
            SyncCoordinator.setCalendarSyncEnabled(false)
            Log.i(TAG, "Calendar sync disabled.")
        }
    }

    fun onTimerToggle(checked: Boolean) {
        Log.i(TAG, "UI: timer sync toggle -> $checked")
        if (checked) {
            maybeRequestNotificationPermission()
            // Reading timer data requires notification-listener access. If it is not granted we do
            // not enable the toggle — instead we prompt the user to open Notification access
            // settings, and the sync activates once they toggle it back on (granted).
            if (SyncCoordinator.timerMonitor?.checkNotificationAccess(context) == true) {
                Log.i(TAG, "Notification access granted; enabling timer sync.")
                SyncCoordinator.setTimerSyncEnabled(true)
            } else {
                Log.w(TAG, "Timer sync requires notification access; showing guidance dialog.")
                showNotificationAccessDialog = true
            }
        } else {
            SyncCoordinator.setTimerSyncEnabled(false)
            Log.i(TAG, "Timer sync disabled.")
        }
    }

    // Re-fetch the calendar list whenever calendar sync becomes enabled (including after the async
    // DataStore load on relaunch), so persisted per-calendar selections show up.
    LaunchedEffect(calendarSyncEnabled) {
        if (calendarSyncEnabled && calendarPermissionGranted) {
            calendars = SyncCoordinator.getAvailableCalendars()
        }
    }

    fun onCalendarChecked(info: CalendarInfo, checked: Boolean) {
        Log.i(TAG, "UI: calendar '${info.displayName}' (id=${info.calendarId}) selected -> $checked")
        SyncCoordinator.setCalendarSelected(info.calendarId, checked)
        calendars = calendars.map {
            if (it.calendarId == info.calendarId) it.copy(selected = checked) else it
        }
    }

    fun onForceSync() {
        Log.i(TAG, "Force sync button pressed; requesting sync.")
        scope.launch {
            val connected = SyncCoordinator.pebbleCommunicationManager
                ?.isWatchConnected(context) ?: false
            Log.i(TAG, "Force sync: watch connected = $connected")
            if (connected) {
                Log.i(TAG, "Force sync triggered (watch connected).")
                SyncCoordinator.requestSync()
                snackbarHostState.showSnackbar("Sync requested.")
            } else {
                Log.w(TAG, "Cannot sync: showing dialog.")
                showCannotSyncDialog = true
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Monologue Orbital Companion") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Sync Preferences",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Master gate: the first toggle on the page. Turning it off unregisters every
            // listener (alarm, calendar, timer) and shuts down the foreground service.
            SwitchRow(
                title = "Enable Sync",
                subtitle = "Master switch for all syncing with your watch.",
                icon = { Icon(Icons.Rounded.CloudSync, contentDescription = null) },
                checked = masterSyncEnabled,
                onCheckedChange = ::onMasterToggle,
            )

            SwitchRow(
                title = "Sync Alarm",
                subtitle = "Push the next scheduled phone alarm to the watchface.",
                icon = { Icon(Icons.Rounded.Alarm, contentDescription = null) },
                checked = alarmSyncEnabled,
                enabled = masterSyncEnabled,
                onCheckedChange = ::onAlarmToggle,
            )

            SwitchRow(
                title = "Sync Calendar",
                subtitle = "Push upcoming calendar events to the watchface.",
                icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                checked = calendarSyncEnabled,
                enabled = masterSyncEnabled,
                onCheckedChange = ::onCalendarToggle,
            )

            SwitchRow(
                title = "Sync Timer",
                subtitle = "Push the active timer's end time to the watchface (needs notification access).",
                icon = { Icon(Icons.Rounded.Timer, contentDescription = null) },
                checked = timerSyncEnabled,
                enabled = masterSyncEnabled,
                onCheckedChange = ::onTimerToggle,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Per-calendar selection (only meaningful while calendar sync is enabled).
            Text(
                text = "Calendars",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (!calendarSyncEnabled) {
                Text(
                    text = "Enable calendar sync to choose which calendars to include.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (calendars.isEmpty()) {
                Text(
                    text = "No calendars available. Grant calendar permission and ensure you have calendars on device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                calendars.forEach { info ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = info.selected,
                            onCheckedChange = { onCalendarChecked(info, it) },
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = info.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (info.accountName != null) {
                                Text(
                                    text = info.accountName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Force sync.
            Button(
                onClick = ::onForceSync,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Sync Now")
            }

            OutlinedButton(
                onClick = { Log.i(TAG, "Refresh calendars requested.") ; calendars = SyncCoordinator.getAvailableCalendars() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.CloudSync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Refresh Calendars")
            }

            // Status section.
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            val selectedCalendarCount = calendars.count { it.selected }
            val statusText = buildString {
                appendLine("Alarm sync: ${if (alarmSyncEnabled) "Enabled" else "Disabled"}")
                appendLine("  Last transmit: ${alarmSyncStatus.lastTransmitStatus ?: "—"}")
                appendLine("  Last successful sync: ${formatSyncTime(alarmSyncStatus.lastSuccessfulSyncTime)}")
                append("Calendar sync: ${if (calendarSyncEnabled) "Enabled" else "Disabled"}")
                if (calendarSyncEnabled) {
                    append(" • $selectedCalendarCount calendar(s) selected")
                }
                appendLine()
                appendLine("  Last transmit: ${calendarSyncStatus.lastTransmitStatus ?: "—"}")
                appendLine("  Last successful sync: ${formatSyncTime(calendarSyncStatus.lastSuccessfulSyncTime)}")
                appendLine("Timer sync: ${if (timerSyncEnabled) "Enabled" else "Disabled"}")
                appendLine("  Last transmit: ${timerSyncStatus.lastTransmitStatus ?: "—"}")
                appendLine("  Last successful sync: ${formatSyncTime(timerSyncStatus.lastSuccessfulSyncTime)}")
                append("Last sync request from watch: ${formatSyncTime(lastWatchSyncRequestTime)}")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // "Cannot Sync" dialog: shown when the user taps "Sync Now" while no watch is connected.
    if (showCannotSyncDialog) {
        AlertDialog(
            onDismissRequest = { showCannotSyncDialog = false },
            title = { Text("Cannot Sync") },
            text = {
                Text(
                    "Your watch is not connected. Please make sure your Pebble is connected " +
                        "and try again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showCannotSyncDialog = false }) {
                    Text("OK")
                }
            },
        )
    }

    // "Notification access needed" dialog: shown when the user enables Timer sync without
    // notification-read access. Directs them to the system Notification access settings;
    // sync activates once they re-toggle Timer sync after granting access.
    if (showNotificationAccessDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationAccessDialog = false },
            title = { Text("Notification access needed") },
            text = {
                Text(
                    "Timer sync reads timer notifications from your clock app. " +
                        "Please enable \"Monologue Orbital Companion\" in Notification access " +
                        "settings, then turn Timer sync back on.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNotificationAccessDialog = false
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                            )
                            Log.i(TAG, "Opened notification listener settings.")
                        } catch (e: Exception) {
                            Log.w(TAG, "No activity for notification listener settings.", e)
                        }
                    },
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationAccessDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * A titled row with an icon, supporting text, and a trailing [Switch].
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(40.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConfigurationScreenPreview() {
    MaterialTheme {
        ConfigurationScreen()
    }
}

private const val TAG = "MonologueConfigUI"

/**
 * Formats an epoch-millis timestamp as a friendly date/time, or returns "Never" when null (i.e. the
 * channel has never successfully synced).
 */
private fun formatSyncTime(epochMillis: Long?): String {
    if (epochMillis == null) return "Never"
    return SimpleDateFormat("EEE, MMM d HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
}