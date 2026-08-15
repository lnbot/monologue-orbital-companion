package com.overstuffed.monologue_orbital_companion

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Monitors the phone's active timer finish time and pushes its timestamp to the Monologue Orbital
 * watchface (message key 113, `uint32` epoch seconds) whenever it changes.
 *
 * Unlike [AlarmMonitor] (which polls [android.app.AlarmManager]) and [CalendarMonitor] (which uses a
 * [ContentObserver](android.database.ContentObserver)), the timer is sourced from **notifications**
 * posted by the system clock apps. [ClockTimerNotificationListener] is a
 * [NotificationListenerService](android.service.notification.NotificationListenerService) that
 * parses timer notifications from Google/Samsung/OnePlus/AOSP deskclocks and reports changes through
 * its [ClockTimerNotificationListener.TimerCallback]. [TimerMonitor] registers itself as that
 * callback while [enabled] and forwards updates to the watch.
 *
 * **Permission:** timer reads go through notification-listener access. [checkNotificationAccess]
 * returns whether the user has enabled this app in
 * `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`; the UI layer (Task 5) prompts for that before
 * enabling timer sync. [TimerMonitor] itself never crashes if access is missing — the
 * notification listener simply receives no callbacks and [checkNotificationAccess] returns `false`.
 *
 * **Multiple timers:** [ClockTimerNotificationListener] tracks one finish time per clock package. If
 * several timer apps are running at once, the earliest-finishing timer is reported (see
 * [ClockTimerNotificationListener.activeFinishEpochSeconds]) and every `onTimerUpdate` is forwarded
 * verbatim. This may briefly "jump" between apps, but it is safe and never crashes.
 *
 * @param context used to resolve notification access state.
 * @param pebbleCommunicationManager used to push the timer finish epoch-seconds to the watchface.
 */
class TimerMonitor(
    context: Context,
    private val pebbleCommunicationManager: PebbleCommunicationManager,
) {
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Serializes concurrent callback invocations so we never interleave two sends mid-flight. */
    private val sending = AtomicBoolean(false)

    /**
     * Whether timer syncing is active.
     *
     * When set to `true` we register as the [ClockTimerNotificationListener] callback and perform an
     * initial read + send (using whatever active-timer the listener already knows about, or `0`).
     * When set to `false` we unregister the callback and stop sending.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                registerCallback()
                syncCurrentTimer()
            } else {
                unregisterCallback()
            }
            Log.i(
                TAG,
                "Timer sync ${if (value) "enabled" else "disabled"}; " +
                    "notification access granted=${checkNotificationAccess(appContext)}.",
            )
        }

    private val timerCallback = object : ClockTimerNotificationListener.TimerCallback {
        override fun onTimerUpdate(source: String, finishEpochSeconds: Long) {
            Log.d(TAG, "onTimerUpdate (source=$source): finish=$finishEpochSeconds.")
            sendTimer(finishEpochSeconds)
        }

        override fun onTimerFinished(source: String) {
            Log.i(TAG, "onTimerFinished (source=$source): no active timer; clearing watch.")
            sendTimer(0L)
        }
    }

    // ---------------------------------------------------------------
    // Permission
    // ---------------------------------------------------------------

    /**
     * Returns `true` when this app is present in the system's enabled notification-listener set.
     *
     * This is a **read-only** check — nothing is requested here. The UI layer (Task 5) launches
     * `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` when the user enables timer sync without this
     * access. The check reads `Settings.Secure.enabled_notification_listeners`, which contains a
     * colon-separated list of `package/component` entries. A `String.contains` check is deliberately
     * used (rather than splitting) to stay robust against the varying formatting.
     */
    fun checkNotificationAccess(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val granted = enabled.contains(appContext.packageName)
            if (!granted) {
                Log.i(TAG, "checkNotificationAccess: notification access not granted for this app.")
            }
            granted
        } catch (e: Exception) {
            // Settings.Secure read can't realistically throw, but stay defensive — never crash.
            Log.e(TAG, "checkNotificationAccess: failed to query enabled_notification_listeners.", e)
            false
        }
    }

    // ---------------------------------------------------------------
    // Sync
    // ---------------------------------------------------------------

    /**
     * Re-reads the currently-active timer (if any) and pushes it to the watchface.
     *
     * When a timer is running its finish epoch-seconds are sent as uint32; when none is running `0`
     * is sent so the watch clears any stale timer display. No-op while [enabled] is `false`.
     */
    fun syncCurrentTimer() {
        if (!enabled) {
            Log.d(TAG, "syncCurrentTimer: timer sync disabled; skipping.")
            return
        }
        if (!checkNotificationAccess(appContext)) {
            Log.i(TAG, "syncCurrentTimer: notification access not granted; skipping send.")
            return
        }
        val finishEpochSeconds = try {
            ClockTimerNotificationListener.activeFinishEpochSeconds()
        } catch (e: Exception) {
            // Defensive: the listener's internal state should never throw, but if it does we treat
            // that as "no known timer" and clear the watch rather than crash.
            Log.e(TAG, "syncCurrentTimer: failed to read active timer; clearing watch.", e)
            null
        } ?: 0L
        sendTimer(finishEpochSeconds)
    }

    /**
     * Sends [finishEpochSeconds] to the watchface via [PebbleCommunicationManager.sendTimerSync].
     *
     * The [sending] flag dedupes bursty callback invocations (e.g. a countdown notification that
     * posts every second) into a single in-flight send. Only the *latest* value is tracked, so if
     * updates arrive faster than the (serialized) send loop can drain them the most recent finish
     * time wins — exactly what we want for a countdown.
     */
    private fun sendTimer(finishEpochSeconds: Long) {
        if (!enabled) {
            Log.d(TAG, "sendTimer: timer sync disabled; skipping.")
            return
        }
        if (!sending.compareAndSet(false, true)) {
            Log.d(TAG, "sendTimer: a send is already in flight; skipping duplicate.")
            return
        }
        scope.launch {
            try {
                pebbleCommunicationManager.sendTimerSync(appContext, finishEpochSeconds)
                Log.i(
                    TAG,
                    "sendTimer: timer epoch=$finishEpochSeconds " +
                        "${if (finishEpochSeconds == 0L) "cleared on watch" else "sent to watch"}.",
                )
            } finally {
                sending.set(false)
            }
        }
    }

    // ---------------------------------------------------------------
    // Callback lifecycle
    // ---------------------------------------------------------------

    /** Registers [timerCallback] as the [ClockTimerNotificationListener] callback. */
    private fun registerCallback() {
        ClockTimerNotificationListener.callback = timerCallback
        Log.d(TAG, "registerCallback: TimerCallback registered.")
    }

    /** Clears the [ClockTimerNotificationListener] callback (only if it is ours). */
    private fun unregisterCallback() {
        // Guard against clobbering a callback installed by a newer instance after we were disabled.
        if (ClockTimerNotificationListener.callback === timerCallback) {
            ClockTimerNotificationListener.callback = null
        }
        Log.d(TAG, "unregisterCallback: TimerCallback removed.")
    }

    /** Releases the internal coroutine scope and unregisters the timer callback. */
    fun cleanup() {
        enabled = false
        unregisterCallback()
        scope.cancel()
        Log.d(TAG, "cleanup: TimerMonitor released.")
    }

    private companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG
    }
}