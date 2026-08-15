package com.overstuffed.monologue_orbital_companion

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Monitors the phone's next scheduled system alarm and pushes its timestamp to the Monologue Orbital
 * watchface whenever it changes.
 *
 * It reads the next alarm via [AlarmManager.getNextAlarmClock] (available since API 21; our minSdk is
 * 26) and listens for [AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED] broadcasts (added in API 22, and
 * exempted from Android 8+ implicit-broadcast restrictions) so it can re-sync the timestamp whenever
 * the user creates, edits, or clears an alarm.
 *
 * **Note on permissions:** reading the next alarm clock is unrestricted on API 21+ — no special alarm
 * permission is required because we only *read* the alarm, we never schedule one. If
 * [getNextAlarmEpochSeconds] returns null it usually just means no alarm is set (or the clock app
 * doesn't report one); this is logged, never thrown.
 *
 * Enable/disable is controlled via [enabled]. The UI (a later task) wires this to a user toggle.
 *
 * @param context used to access [AlarmManager] and register the broadcast receiver.
 * @param pebbleCommunicationManager used to push the alarm epoch-seconds to the watchface.
 */
class AlarmMonitor(
    context: Context,
    private val pebbleCommunicationManager: PebbleCommunicationManager,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tracks whether we currently hold a receiver registration, so unregistering can be a safe no-op. */
    private var receiverRegistered: Boolean = false

    /**
     * Whether alarm syncing is active.
     *
     * When set to `true` the [AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED] receiver is registered and
     * an initial read + send is performed so the watch always gets the current value. When set to
     * `false` the receiver is unregistered and no further sends happen.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                registerReceiver()
                syncCurrentAlarm()
            } else {
                unregisterReceiver()
            }
            Log.i(TAG, "Alarm sync ${if (value) "enabled" else "disabled"}.")
        }

    private val alarmChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
            Log.d(TAG, "Alarm changed broadcast received (ACTION_NEXT_ALARM_CLOCK_CHANGED).")
            syncCurrentAlarm()
        }
    }

    /**
     * Reads the next system alarm time as Unix epoch seconds, or `null` when no alarm is set.
     *
     * No permission is required for this read on API 21+. A `null` result logs a note rather than
     * throwing, since it can simply mean the clock app doesn't report/support a next alarm.
     */
    fun getNextAlarmEpochSeconds(): Long? {
        val alarmClockInfo = alarmManager.nextAlarmClock
            ?: run {
                Log.i(TAG, "No alarm set (getNextAlarmClock returned null; clock app may not report one).")
                return null
            }
        val epochSeconds = alarmClockInfo.triggerTime / 1000L
        Log.d(TAG, "Next alarm time: epoch=$epochSeconds (triggerMillis=${alarmClockInfo.triggerTime}).")
        return epochSeconds
    }

    /**
     * Re-reads the current alarm time and pushes it to the watchface.
     *
     * When a valid alarm exists it is sent as uint32 epoch seconds via
     * [PebbleCommunicationManager.sendAlarmSync]. When no alarm is set, `0` is sent so the watch can
     * clear any stale alarm display. No-op while [enabled] is false.
     */
    fun syncCurrentAlarm() {
        if (!enabled) {
            Log.d(TAG, "syncCurrentAlarm: alarm sync disabled; skipping.")
            return
        }
        val epochSeconds = getNextAlarmEpochSeconds() ?: 0L
        scope.launch {
            pebbleCommunicationManager.sendAlarmSync(appContext, epochSeconds)
            Log.i(
                TAG,
                "syncCurrentAlarm: alarm epoch=$epochSeconds " +
                    "${if (epochSeconds == 0L) "cleared on watch" else "sent to watch"}.",
            )
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The alarm-changed broadcast is sent by the system, so the receiver does not need to
                // be exported to other apps (required flag semantics on API 33+).
                appContext.registerReceiver(alarmChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(alarmChangedReceiver, filter)
            }
            receiverRegistered = true
            Log.d(TAG, "Registered receiver for ACTION_NEXT_ALARM_CLOCK_CHANGED.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register alarm-changed receiver.", e)
        }
    }

    private fun unregisterReceiver() {
        // unregisterReceiver throws IllegalArgumentException if the receiver was never registered.
        if (!receiverRegistered) {
            Log.d(TAG, "unregisterReceiver: receiver was not registered; skipping.")
            return
        }
        try {
            appContext.unregisterReceiver(alarmChangedReceiver)
            receiverRegistered = false
            Log.d(TAG, "Unregistered receiver for ACTION_NEXT_ALARM_CLOCK_CHANGED.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister alarm-changed receiver.", e)
        }
    }

    /** Releases the internal coroutine scope and unregisters any active receiver. */
    fun cleanup() {
        enabled = false
        scope.cancel()
        Log.d(TAG, "cleanup: AlarmMonitor released.")
    }

    private companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG
    }
}
