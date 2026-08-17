package com.overstuffed.monologue_orbital_companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
     * Extra key for the incoming AppMessage data in the broadcast.
     *
     * The Pebble Core app delivers the dictionary as a **String** whose value is the JSON
     * serialization of the message (e.g. `[{"key":110,"type":"uint","length":4,"value":1}]`),
     * so it must be decoded via [PebbleDictionary.fromJson] before use.
     */
    private const val EXTRA_DATA = "msg_data"

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
            // RECEIVER_EXPORTED because the Pebble Core app (a separate app) sends this broadcast
            // (ContextCompat.registerReceiver applies the exported flag on all API levels).
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )
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
        val uuid = IntentCompat.getSerializableExtra(intent, EXTRA_UUID, UUID::class.java)

        // The Pebble Core app broadcasts the message data as a String (the JSON serialization of
        // the PebbleDictionary), not as a PebbleDictionary object, so we read it as a raw string
        // and decode it before use.
        val json = intent.getStringExtra(EXTRA_DATA)

        if (uuid == null || json.isNullOrBlank()) {
            Log.w(TAG, "handleReceive: received broadcast with null uuid or data.")
            return
        }

        val data: PebbleDictionary? = try {
            PebbleDictionary.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleReceive: failed to decode message data: ${e.message}", e)
            null
        }

        if (data == null) {
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