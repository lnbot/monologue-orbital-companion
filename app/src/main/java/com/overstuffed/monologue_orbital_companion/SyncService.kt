package com.overstuffed.monologue_orbital_companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps Monologue Orbital Companion syncing in the background while at
 * least one sync feature (alarm or calendar) is enabled.
 *
 * The service runs only when [SyncCoordinator.isAlarmSyncEnabled] OR
 * [SyncCoordinator.isCalendarSyncEnabled] is `true` — [SyncCoordinator] starts it when a sync
 * feature is turned on and stops it when both are off. Wrapping sync in a foreground service
 * (type `connectedDevice`) lets the app keep listening for Pebble sync requests and reacting to
 * alarm/calendar changes even after the UI is destroyed.
 *
 * **Why connectedDevice and not dataSync:** starting with Android 15 (API 35), `dataSync`
 * foreground services are limited to 6 hours per day. `connectedDevice` has no such cap, and this
 * is a wearable companion app whose entire job is communicating with an attached device, so
 * `connectedDevice` is the correct type.
 *
 * [SyncService.start] / [SyncService.stop] are the only entry points; the coordinator calls them
 * when sync features are toggled or loaded from persisted settings. Starting an already-running
 * service just re-posts the notification (harmless); stopping a non-running service is a no-op.
 */
class SyncService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "SyncService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: promoting to foreground with notification.")
        val notification = buildNotification()
        // Pass the explicit type flag alongside the manifest declaration so the system knows
        // exactly which type is in use (required for correct behavior on API 34+).
        // @Suppress("InlinedApi"): ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE is a
        // compile-time constant inlined at build time, so it is safe on all API levels (minSdk
        // 26); on API < 29 ServiceCompat ignores the type and uses the legacy path.
        @Suppress("InlinedApi")
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // Defense in depth: if no sync feature is enabled the service has no reason to run.
        // SyncCoordinator normally keeps this in sync, but this guards against edge cases (e.g.
        // a stale START_STICKY restart after both features were turned off).
        if (!SyncCoordinator.isAlarmSyncEnabled() && !SyncCoordinator.isCalendarSyncEnabled()) {
            Log.w(TAG, "onStartCommand: no sync feature enabled; stopping self.")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "SyncService stopped.")
        // Intentionally do NOT call SyncCoordinator.shutdown() here: the coordinator is
        // Application-scoped now and the UI still needs it. Monitoring is gated by the enabled
        // flags — the monitors register/unregister their receivers on enable/disable.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Creates the low-importance notification channel used by the persistent sync notification. */
    private fun createNotificationChannel() {
        // minSdk is 26 (API 26 = O), so the channel API is always available.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.sync_service_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tapping the notification opens the companion app's configuration screen.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.sync_service_notification_title))
            .setContentText(getString(R.string.sync_service_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG

        /** Foreground notification id — any positive int that doesn't collide with other notifications. */
        const val NOTIF_ID = 1001

        /** Notification channel id for the sync-service channel. */
        const val CHANNEL_ID = "sync_service"

        /**
         * Starts the foreground sync service (via [Context.startForegroundService]).
         *
         * Safe to call repeatedly — if the service is already running the intent is merely
         * re-delivered to [onStartCommand], which re-posts the notification. Exceptions (e.g. the
         * foreground-service start restriction on API 34+ when the app is in the background) are
         * caught and logged so sync toggling never crashes the UI.
         */
        fun start(context: Context) {
            try {
                val appContext = context.applicationContext
                appContext.startForegroundService(Intent(appContext, SyncService::class.java))
                Log.i(TAG, "SyncService.start: startForegroundService called.")
            } catch (e: Exception) {
                Log.e(TAG, "SyncService.start: failed to start foreground service.", e)
            }
        }

        /** Stops the sync service if it is running. Safe to call at any time. */
        fun stop(context: Context) {
            try {
                val appContext = context.applicationContext
                appContext.stopService(Intent(appContext, SyncService::class.java))
                Log.i(TAG, "SyncService.stop: stop requested.")
            } catch (e: Exception) {
                Log.e(TAG, "SyncService.stop: failed to stop service.", e)
            }
        }
    }
}