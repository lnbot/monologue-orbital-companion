package com.overstuffed.monologue_orbital_companion

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.time.Instant

class ClockTimerNotificationListener : NotificationListenerService() {

    interface TimerCallback {
        fun onTimerUpdate(source: String, finishEpochSeconds: Long)
        fun onTimerFinished(source: String)
    }

    companion object {
        private const val TAG = "MonologueClockListener";
        var callback: TimerCallback? = null

        private val CLOCK_PACKAGES = setOf(
            "com.google.android.deskclock",
//            "com.sec.android.app.clockpackage",
//            "com.oneplus.deskclock",
//            "com.android.deskclock"
        )

        // Stores last known finish time per clock app
        private val lastFinishTimes = mutableMapOf<String, Long>()

        /**
         * Returns the finish epoch-seconds of the timer that will finish soonest, or `null` when no
         * timer is currently running (i.e. no known finish time is still in the future).
         *
         * The companion stores one finish time per clock package, so if several timer apps are
         * running simultaneously this returns the earliest expiry. This is a best-effort read used
         * for the initial sync when timer sync is enabled; a single active timer is the supported
         * case, and multiple timers are handled without crashing.
         */
        fun activeFinishEpochSeconds(): Long? =
            lastFinishTimes.values
                .filter { it > Instant.now().epochSecond }
                .minOrNull()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in CLOCK_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        val remainingSeconds : Int

        if (title == "" && text == "") {
            remainingSeconds = parseRemainingTime(pkg, sbn.notification.sortKey) ?: return
        } else if (title != "Timer" && !text.contains("Timer")) {
            return
        } else {
            remainingSeconds = parseRemainingTime(pkg, text) ?: return
        }

        val finishEpochSeconds = Instant.now().epochSecond + remainingSeconds
        val last = lastFinishTimes[pkg]
        if (last == null || last != finishEpochSeconds) {
            lastFinishTimes[pkg] = finishEpochSeconds
            Log.d(TAG, "[$pkg] Finish time changed → $finishEpochSeconds")
            callback?.onTimerUpdate(pkg, finishEpochSeconds)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in CLOCK_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""

        if (title == "Timer" || text.contains("Timer") ||
            (title == "" && text == "" && sbn.notification.sortKey?.contains('⏳') ?: false)) {
            Log.d(TAG, "[$pkg] Timer finished")
            lastFinishTimes.remove(pkg)
            callback?.onTimerFinished(pkg)
        }
    }

    private fun parseRemainingTime(pkg: String, text: String): Int? {
        return when (pkg) {

            // Google Clock: "0|↺11|RUNNING|▶14:50:59.561|Σ0:05:00|Δ0:00:08|⏳0:04:52"
            "com.google.android.deskclock" -> {
                if (text.contains('|'))
                    parseHHMMSSFromText(text.split('|').last())
                else
                    null
            }

            // Samsung Clock: "03:12"
            "com.sec.android.app.clockpackage" -> {
                parseMMSSExact(text)
            }

            // OnePlus Clock: "3m 12s left" or "03:12 left"
            "com.oneplus.deskclock" -> {
                parseFlexible(text)
            }

            // AOSP / Xiaomi: "03:12 remaining" or "03:12"
            "com.android.deskclock" -> {
                parseMMSSFromText(text) ?: parseMMSSExact(text)
            }

            else -> null
        }
    }

    private fun parseHHMMSSFromText(text: String): Int? {
        val regex = Regex("""(\d{1,2}):(\d{1,2}):(\d{2})""")
        val match = regex.find(text) ?: return null
        val (h, m, s) = match.destructured
        return h.toInt() * 60 * 60 + m.toInt() * 60 + s.toInt()
    }

    private fun parseMMSSFromText(text: String): Int? {
        val regex = Regex("""(\d{1,2}):(\d{2})""")
        val match = regex.find(text) ?: return null
        val (m, s) = match.destructured
        return m.toInt() * 60 + s.toInt()
    }

    private fun parseMMSSExact(text: String): Int? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return m * 60 + s
    }

    private fun parseFlexible(text: String): Int? {
        val m = Regex("""(\d+)m""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val s = Regex("""(\d+)s""").find(text)?.groupValues?.get(1)?.toInt() ?: 0

        if (m == 0 && s == 0) {
            return parseMMSSFromText(text)
        }

        return m * 60 + s
    }
}
