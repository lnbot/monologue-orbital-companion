package com.overstuffed.monologue_orbital_companion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
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
 * - a "Sync Now" [Button] that performs a force sync when the watch is reachable,
 * - a "Cannot Sync" [AlertDialog] when the watch isn't connected / watchface isn't running.
 *
 * All user actions are logged to Logcat via [TAG].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Reactive sync-enabled state collected from the coordinator, which is populated from
    // DataStore during initialize(). Toggles update automatically when persisted values load.
    val alarmSyncEnabled by SyncCoordinator.alarmSyncEnabled.collectAsState()
    val calendarSyncEnabled by SyncCoordinator.calendarSyncEnabled.collectAsState()
    var calendars by remember { mutableStateOf(SyncCoordinator.getAvailableCalendars()) }
    var showCannotSyncDialog by remember { mutableStateOf(false) }

    // Reactive transmit status from the shared manager (null-safe; defaults shown until a manager exists).
    val manager = SyncCoordinator.pebbleCommunicationManager
    val alarmSyncStatus by manager?.alarmSyncStatus
        ?.collectAsState(initial = ChannelSyncStatus())
        ?: remember { mutableStateOf(ChannelSyncStatus()) }
    val calendarSyncStatus by manager?.calendarSyncStatus
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

    fun onAlarmToggle(checked: Boolean) {
        Log.i(TAG, "UI: alarm sync toggle -> $checked")
        SyncCoordinator.setAlarmSyncEnabled(checked)
        if (checked) {
            Log.i(TAG, "Alarm sync enabled.")
        } else {
            Log.i(TAG, "Alarm sync disabled.")
        }
    }

    fun onCalendarToggle(checked: Boolean) {
        Log.i(TAG, "UI: calendar sync toggle -> $checked")
        if (checked) {
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
        Log.i(TAG, "Force sync button pressed.")
        scope.launch {
            val manager = SyncCoordinator.pebbleCommunicationManager
            val connected = manager?.isWatchConnected() ?: false
            val watchfaceRunning = if (connected) {
                manager?.isWatchfaceRunning() ?: false
            } else {
                false
            }
            if (connected && watchfaceRunning) {
                Log.i(TAG, "Watch connected ($connected) and watchface running ($watchfaceRunning); requesting sync.")
                SyncCoordinator.requestSync()
                snackbarHostState.showSnackbar("Sync requested.")
            } else {
                Log.w(
                    TAG,
                    "Sync dialog shown (watch disconnected or watchface not running). connected=$connected watchfaceRunning=$watchfaceRunning.",
                )
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

            SwitchRow(
                title = "Sync Alarm",
                subtitle = "Push the next scheduled phone alarm to the watchface.",
                icon = { Icon(Icons.Rounded.Alarm, contentDescription = null) },
                checked = alarmSyncEnabled,
                onCheckedChange = ::onAlarmToggle,
            )

            SwitchRow(
                title = "Sync Calendar",
                subtitle = "Push upcoming calendar events to the watchface.",
                icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
                checked = calendarSyncEnabled,
                onCheckedChange = ::onCalendarToggle,
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

    if (showCannotSyncDialog) {
        CannotSyncDialog(
            onDismiss = {
                showCannotSyncDialog = false
                Log.i(TAG, "Cannot sync dialog dismissed.")
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
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * Modal shown when a force sync can't proceed because the watch is unreachable or the
 * Monologue watchface isn't running on it.
 */
@Composable
private fun CannotSyncDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cannot Sync") },
        text = {
            Text(
                "Your watch is not connected or the Monologue watchface is not currently " +
                    "running. Please open the watchface on your Pebble and try again.",
                textAlign = TextAlign.Start,
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
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