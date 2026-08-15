package com.overstuffed.monologue_orbital_companion

import android.app.Application
import android.util.Log

/**
 * Application subclass that initializes [SyncCoordinator] once for the lifetime of the process.
 *
 * Previously the coordinator was initialized (and shut down) from [MainActivity]'s
 * `onCreate`/`onDestroy`, which tied background monitoring to the Activity lifecycle: finishing
 * the UI would tear down the alarm receiver, the calendar observer, and the Pebble broadcast
 * listener, breaking background sync. By initializing here with the application context, all
 * monitoring outlives any single Activity and keeps running even when the UI is destroyed.
 */
class MonologueApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i(
            PebbleMessageKeys.LOG_TAG,
            "MonologueApplication: initializing SyncCoordinator for the process lifetime.",
        )
        // Idempotent — safe even if some component also calls it.
        SyncCoordinator.initialize(this)
    }
}