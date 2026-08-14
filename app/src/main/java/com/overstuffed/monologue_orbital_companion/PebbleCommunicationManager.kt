package com.overstuffed.monologue_orbital_companion

import android.content.Context
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.model.Watchapp
import kotlinx.coroutines.flow.first

/**
 * Coroutine-friendly wrapper around [DefaultPebbleSender] for pushing data to the Monologue Orbital
 * watchface and for querying watch connectivity / running-watchface status.
 *
 * All send/query methods are [suspend] functions and must be called from a coroutine. The underlying
 * [io.rebble.pebblekit2.client.PebbleSender] is created lazily on first use and can be released with
 * [close] when the owning lifecycle ends.
 *
 * Connectivity info via [DefaultPebbleInfoRetriever] only works while the app is in the foreground;
 * background detection would require observing the Pebble content provider (out of scope here).
 */
class PebbleCommunicationManager(context: Context) {

    private val appContext = context.applicationContext

    private val sender: DefaultPebbleSender by lazy { DefaultPebbleSender(appContext) }
    private val infoRetriever: DefaultPebbleInfoRetriever by lazy { DefaultPebbleInfoRetriever(appContext) }

    /**
     * Sends a single alarm epoch-second value to the watchface as key 111 (`uint32`).
     *
     * @param epochSeconds alarm timestamp as Unix epoch seconds.
     * @return true if the message was acknowledged by the watch, false otherwise.
     */
    suspend fun sendAlarmSync(epochSeconds: Long): Boolean {
        val payload: PebbleDictionary = mapOf(
            PebbleMessageKeys.KEY_SYNC_ALARM to PebbleDictionaryItem.UInt32(epochSeconds),
        )
        Log.d(TAG, "sendAlarmSync: sending alarm epoch=$epochSeconds")
        return transmit(payload, "alarm-sync")
    }

    /**
     * Sends a list of calendar event epoch-second values as key 112 (`uint8[]`, little-endian uint32).
     *
     * @param epochSecondList list of event timestamps as Unix epoch seconds.
     * @return true if the message was acknowledged by the watch, false otherwise.
     */
    suspend fun sendCalendarSync(epochSecondList: List<Long>): Boolean {
        val bytes = encodeEpochsAsUint8LittleEndian(epochSecondList)
        Log.d(
            TAG,
            "sendCalendarSync: sending ${epochSecondList.size} events (${bytes.size} bytes)",
        )
        val payload: PebbleDictionary = mapOf(
            PebbleMessageKeys.KEY_SYNC_CALENDAR to PebbleDictionaryItem.Bytes(bytes),
        )
        return transmit(payload, "calendar-sync")
    }

    /**
     * Returns whether at least one Pebble/Rebble watch is currently connected.
     *
     * Note: this relies on [DefaultPebbleInfoRetriever], which only reports accurate results while the
     * app is in the foreground.
     */
    suspend fun isWatchConnected(): Boolean {
        return try {
            val watches = infoRetriever.getConnectedWatches().first()
            Log.d(TAG, "isWatchConnected: ${watches.size} connected watch(es): $watches")
            watches.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "isWatchConnected: failed to query connected watches (foreground only).", e)
            false
        }
    }

    /**
     * Returns whether the Monologue Orbital watchface is currently the active app (WATCHFACE type
     * with our UUID) on the given watch, or on the first connected watch when [watch] is null.
     *
     * Note: this relies on [DefaultPebbleInfoRetriever], which only reports accurate results while the
     * app is in the foreground.
     */
    suspend fun isWatchfaceRunning(watch: WatchIdentifier? = null): Boolean {
        val target = watch ?: infoRetriever.getConnectedWatches().first().firstOrNull()?.id
            ?: run {
                Log.d(TAG, "isWatchfaceRunning: no connected watch found.")
                return false
            }
        return try {
            val active = infoRetriever.getActiveApp(target).first()
            val isRunning = active != null &&
                active.id == PebbleMessageKeys.WATCHFACE_UUID &&
                active.type == Watchapp.Type.WATCHFACE
            Log.d(TAG, "isWatchfaceRunning: active app = ${active?.name} (running=$isRunning)")
            isRunning
        } catch (e: Exception) {
            Log.w(TAG, "isWatchfaceRunning: failed to query active app (foreground only).", e)
            false
        }
    }

    private suspend fun transmit(payload: PebbleDictionary, label: String): Boolean {
        return try {
            val results = sender.sendDataToPebble(PebbleMessageKeys.WATCHFACE_UUID, payload)
            if (results == null) {
                Log.w(
                    TAG,
                    "$label: transmission failed, Pebble app is not reachable (not installed or not selected).",
                )
                return false
            }
            if (results.isEmpty()) {
                Log.w(TAG, "$label: no connected watches to send to.")
                return false
            }
            val allSucceeded = results.all { (watch, result) ->
                val ok = result == TransmissionResult.Success
                Log.d(
                    TAG,
                    "$label: watch=$watch result=$result ${if (ok) "(ACK)" else "(FAILED)"}",
                )
                ok
            }
            if (allSucceeded) {
                Log.i(TAG, "$label: successfully acknowledged by watch.")
            }
            allSucceeded
        } catch (e: Exception) {
            Log.e(TAG, "$label: exception while sending: ${e.message}", e)
            false
        }
    }

    /** Releases the underlying sender service connection. Call when done using this manager. */
    fun close() {
        try {
            sender.close()
        } catch (e: Exception) {
            Log.w(TAG, "close: failed to close sender: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG
    }
}
