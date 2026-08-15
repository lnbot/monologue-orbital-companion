package com.overstuffed.monologue_orbital_companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.getpebble.android.kit.util.PebbleDictionary
import java.util.UUID

/**
 * Receives AppMessages from the Monologue Orbital watchface via the classic PebbleKit broadcast
 * path.
 *
 * Instead of the PebbleKit2 service-based listener (which required a manifest-declared exported
 * service and had no Android 14+ compatibility issues), this class registers a [BroadcastReceiver]
 * manually using [IntentFilter] for the classic PebbleKit `com.getpebble.action.app.RECEIVE`
 * action.
 *
 * **Why manual registration?** The classic PebbleKit provides
 * [com.getpebble.android.kit.PebbleKit.registerReceivedDataHandler] which internally calls
 * `context.registerReceiver(receiver, filter)`, but on API 33+ (Android 13+) it does NOT set the
 * required `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` flag, causing the registration to silently
 * fail. We work around this by registering the receiver ourselves with the correct flags.
 *
 * On API 33+ we use [Context.RECEIVER_EXPORTED] because the broadcast originates from the Pebble
 * Core app (a separate app), so it must be exported to receive it.
 */
object PebbleListenerService {

    private const val TAG = PebbleMessageKeys.LOG_TAG

    /** Intent action broadcast by the Pebble Core app when the watch sends data to the phone. */
    private const val ACTION_RECEIVE_FROM_WATCH = "com.getpebble.action.app.RECEIVE"

    /**
     * Extra key for the sender watchapp UUID in the incoming broadcast.
     * Value type: [UUID] (Serializable).
     */
    private const val EXTRA_UUID = "uuid"

    /**
     * Extra key for the incoming [PebbleDictionary] in the broadcast.
     * Value type: [PebbleDictionary] (Serializable).
     */
    private const val EXTRA_DATA = "data"

    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false

    /**
     * Registers the BroadcastReceiver for incoming Pebble AppMessages.
     *
     * Safe to call multiple times — subsequent calls are idempotent.
     *
     * @param context Application or Activity context used for registration.
     */
    fun register(context: Context) {
        if (isRegistered) {
            Log.d(TAG, "register: already registered; skipping.")
            return
        }
        val appContext = context.applicationContext
        val intentFilter = IntentFilter(ACTION_RECEIVE_FROM_WATCH)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleReceive(intent)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // On API 33+ we must explicitly declare the receiver's exported state.
                // RECEIVER_EXPORTED because the Pebble Core app (a separate app) sends this broadcast.
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, intentFilter)
            }
            isRegistered = true
            Log.i(TAG, "register: PebbleListenerService receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "register: failed to register receiver: ${e.message}", e)
            receiver = null
        }
    }

    /**
     * Unregisters the BroadcastReceiver.
     *
     * Safe to call multiple times — subsequent calls are idempotent.
     *
     * @param context Application or Activity context used for unregistration.
     */
    fun unregister(context: Context) {
        if (!isRegistered || receiver == null) {
            Log.d(TAG, "unregister: not registered; skipping.")
            return
        }
        try {
            context.applicationContext.unregisterReceiver(receiver)
            Log.i(TAG, "unregister: PebbleListenerService receiver unregistered.")
        } catch (e: Exception) {
            Log.w(TAG, "unregister: failed to unregister receiver: ${e.message}", e)
        } finally {
            isRegistered = false
            receiver = null
        }
    }

    /**
     * Handles an incoming Pebble AppMessage broadcast.
     *
     * Extracts the UUID and [PebbleDictionary] from the intent extras and dispatches sync requests.
     */
    private fun handleReceive(intent: Intent) {
        val uuid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_UUID, UUID::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_UUID) as? UUID
        }

        // PebbleDictionary is stored as a Serializable extra. On API 33+ the typed overload
        // requires the class to conform to Serializable, but the compiler may not see
        // PebbleDictionary's Serializable hierarchy, so we use the raw approach with a cast.
        @Suppress("DEPRECATION")
        val data = intent.getSerializableExtra(EXTRA_DATA) as? PebbleDictionary

        if (uuid == null || data == null) {
            Log.w(TAG, "handleReceive: received broadcast with null uuid or data.")
            return
        }

        Log.d(TAG, "handleReceive: from watchappUUID=$uuid")

        // Only handle messages for our watchface; silently ignore others.
        if (uuid != PebbleMessageKeys.WATCHFACE_UUID) {
            Log.w(TAG, "handleReceive: ignoring message from foreign watchapp $uuid.")
            return
        }

        Log.d(TAG, "handleReceive: watchapp=$uuid data keys=${describeKeys(data)}")

        // Watch -> phone request to re-sync. Any numeric value present acts as the trigger flag.
        // Classic PebbleDictionary uses contains(int key) — NOT containsKey(Object).
        if (data.contains(PebbleMessageKeys.KEY_SYNC_REQUEST)) {
            Log.i(TAG, "handleReceive: sync request (key ${PebbleMessageKeys.KEY_SYNC_REQUEST}) from watch.")
            SyncCoordinator.onWatchSyncRequested()
        } else {
            Log.d(TAG, "handleReceive: received message with unknown keys (no sync request).")
        }
    }

    /**
     * Returns a description of the keys in a [PebbleDictionary] for debug logging.
     */
    private fun describeKeys(data: PebbleDictionary): String {
        val knownKeys = listOf(
            PebbleMessageKeys.KEY_SYNC_REQUEST to "SYNC_REQUEST",
            PebbleMessageKeys.KEY_SYNC_ALARM to "SYNC_ALARM",
            PebbleMessageKeys.KEY_SYNC_CALENDAR to "SYNC_CALENDAR",
        )
        return knownKeys
            .filter { (key, _) -> data.contains(key) }
            .joinToString(", ", "[", "]") { (_, name) -> name }
    }
}