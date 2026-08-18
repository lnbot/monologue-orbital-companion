package com.overstuffed.monologue_orbital_companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.getpebble.android.kit.Constants
import com.getpebble.android.kit.PebbleKit
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
    private const val EXTRA_UUID = Constants.APP_UUID

    /**
     * Extra key for the incoming AppMessage data in the broadcast.
     *
     * The Pebble Core app delivers the dictionary as a **String** whose value is the JSON
     * serialization of the message (e.g. `[{"key":110,"type":"uint","length":4,"value":1}]`),
     * so it must be decoded via [PebbleDictionary.fromJson] before use.
     */
    private const val EXTRA_DATA = Constants.MSG_DATA

    /**
     * Extra key for the transaction id of the incoming AppMessage.
     *
     * Every message received from the watch must be acknowledged (ACK/NACK) with this id so the
     * watch never hits its AppMessage protocol timeout.
     */
    private const val EXTRA_TRANSACTION_ID = Constants.TRANSACTION_ID

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
                handleReceive(context, intent)
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
     * Extracts the UUID, transaction id, and [PebbleDictionary] from the intent extras and
     * dispatches sync requests.
     *
     * Per the PebbleKit protocol, every AppMessage received from the watch must be acknowledged,
     * otherwise the watch times out and drops the message. We therefore send an **ACK** as soon
     * as the message is successfully read/decoded and a **NACK** when reading or decoding fails
     * — but only when the message comes from [PebbleMessageKeys.WATCHFACE_UUID]. Messages from
     * other watch apps (or broadcasts with no identifiable sender) are ignored entirely.
     */
    private fun handleReceive(context: Context, intent: Intent) {
        val uuid = IntentCompat.getSerializableExtra(intent, EXTRA_UUID, UUID::class.java)

        // The Pebble Core app broadcasts the message data as a JSON String (the serialization of
        // the PebbleDictionary), not as a PebbleDictionary object, so we read it as a raw string
        // and decode it before use.
        val json = intent.getStringExtra(EXTRA_DATA)

        // The transaction id ties our ACK/NACK back to the specific message on the watch.
        // Valid ids are in (0, 255); outside that range no reply can be sent.
        val transactionId = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1)
        val canRespond = transactionId in 0..255

        // Only ACK/NACK messages from our watchface; silently ignore everything else.
        if (uuid != PebbleMessageKeys.WATCHFACE_UUID) {
            Log.w(
                TAG,
                if (uuid == null) "handleReceive: received broadcast with null uuid; ignoring."
                else "handleReceive: ignoring message from foreign watchapp $uuid.",
            )
            return
        }

        // From here on the message is from our watchface: read/parse it, then ACK on success or
        // NACK on failure so the watch never hits its protocol timeout.
        if (json.isNullOrBlank()) {
            Log.w(TAG, "handleReceive: message from watchface with null/blank data; sending NACK.")
            sendNack(context, transactionId, canRespond)
            return
        }

        val data: PebbleDictionary? = try {
            PebbleDictionary.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleReceive: failed to decode message data: ${e.message}", e)
            null
        }

        if (data == null) {
            Log.w(TAG, "handleReceive: failed to parse message data; sending NACK.")
            sendNack(context, transactionId, canRespond)
            return
        }

        // Successfully read the request — acknowledge receipt before dispatching.
        sendAck(context, transactionId, canRespond)

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
     * Sends an ACK to the watch for the given [transactionId].
     *
     * When no valid transaction id is available the reply is skipped (nothing to correlate the
     * ACK to) and the failure is logged. Exceptions from PebbleKit are caught and logged so a
     * reply problem never crashes the receiver.
     */
    private fun sendAck(context: Context, transactionId: Int, canRespond: Boolean) {
        if (!canRespond) {
            Log.w(TAG, "Cannot ACK: transactionId=$transactionId is missing or out of the valid range.")
            return
        }
        try {
            PebbleKit.sendAckToPebble(context.applicationContext, transactionId)
            Log.i(TAG, "ACK sent for transactionId=$transactionId.")
        } catch (e: Exception) {
            Log.e(TAG, "sendAck: failed to send ACK for transactionId=$transactionId.", e)
        }
    }

    /**
     * Sends a NACK to the watch for the given [transactionId].
     *
     * Mirrors [sendAck] — skips (with a log) when no valid transaction id is available.
     */
    private fun sendNack(context: Context, transactionId: Int, canRespond: Boolean) {
        if (!canRespond) {
            Log.w(TAG, "Cannot NACK: transactionId=$transactionId is missing or out of the valid range.")
            return
        }
        try {
            PebbleKit.sendNackToPebble(context.applicationContext, transactionId)
            Log.i(TAG, "NACK sent for transactionId=$transactionId.")
        } catch (e: Exception) {
            Log.e(TAG, "sendNack: failed to send NACK for transactionId=$transactionId.", e)
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