package com.overstuffed.monologue_orbital_companion

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Identifies the data channel being synchronized to the watchface.
 */
enum class SyncChannel { ALARM, CALENDAR }

/**
 * Reactive per-channel status of the most recent transmit attempt and the last successful sync.
 */
data class ChannelSyncStatus(
    /** Human-readable outcome of the most recent transmit attempt, or null if none has occurred yet. */
    val lastTransmitStatus: String? = null,
    /** Epoch millis of the last *successful* transmit, or null if never successfully synced. */
    val lastSuccessfulSyncTime: Long? = null,
)

/**
 * Coroutine-friendly wrapper around the classic PebbleKit API ([PebbleKit.sendDataToPebble]) for
 * pushing data to the Monologue Orbital watchface.
 *
 * This uses the **legacy/classic PebbleKit** broadcast-based path
 * (`com.getpebble.action.app.SEND`) which has no active-app gate and works reliably on Core
 * Devices (`coredevices.coreapp`), unlike PebbleKit2 whose `DefaultPebbleSender` is rejected
 * with `FailedDifferentAppOpen` on every send.
 *
 * Sends are serialized through a [Mutex] to avoid races while building/broadcasting the shared
 * dictionary. All send methods launch on a background [CoroutineScope] and update the reactive
 * status flows.
 *
 * Core handles the classic `SEND` broadcast through its runtime-registered receiver; it is
 * dynamically registered so it does not show up in `pm query-receivers` (manifest-only) — the
 * earlier "zero recipients" reading was that artifact, not an absent receiver.
 */
class PebbleCommunicationManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    // Reactive status of the most recent transmit for each channel.
    private val _alarmSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val alarmSyncStatus: StateFlow<ChannelSyncStatus> = _alarmSyncStatus.asStateFlow()

    private val _calendarSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val calendarSyncStatus: StateFlow<ChannelSyncStatus> = _calendarSyncStatus.asStateFlow()

    /**
     * Returns whether a Pebble/Rebble watch is currently connected.
     *
     * Two paths, tried in order:
     * 1. Legacy [PebbleKit.isWatchConnected] — accurate on API < 34. On Android 14+ (API 34+) its
     *    internal `registerReceiver(null, ...)` call throws [IllegalArgumentException], so the
     *    result is discarded and we fall back to the provider.
     * 2. Direct query of the Core Devices Pebble app content provider
     *    (`content://coredevices.coreapp.pebblekit/connectedWatches` — the same table PebbleKit2's
     *    `DefaultPebbleInfoRetriever.getConnectedWatches()` queried). The provider only lists
     *    watches that are currently connected, so a non-empty cursor means "connected".
     *
     * Every failure (no provider installed, [SecurityException], etc.) is logged and yields false —
     * this method never crashes.
     *
     */
    suspend fun isWatchConnected(context: Context): Boolean {
        // Path 1: legacy classic PebbleKit broadcast-based check (reliable before Android 14).
        try {
            val legacyResult = PebbleKit.isWatchConnected(context)
            Log.d(TAG, "isWatchConnected: legacy path succeeded -> $legacyResult")
            return legacyResult
        } catch (e: Exception) {
            Log.d(
                TAG,
                "isWatchConnected: legacy path failed (${e::class.java.simpleName}: ${e.message}); " +
                    "falling back to Core content provider.",
            )
        }

        // Path 2: Core Devices content provider (the working check on Android 14+).
        return withContext(Dispatchers.IO) {
            try {
                val watchesUri = Uri.withAppendedPath(coreAuthorityUri(), CONNECTED_WATCHES_PATH)
                context.contentResolver
                    .query(watchesUri, arrayOf(COLUMN_WATCH_ID), null, null, null)
                    ?.use { cursor ->
                        val connected = cursor.count > 0
                        Log.d(
                            TAG,
                            "isWatchConnected: provider query -> connected=$connected " +
                                "(${cursor.count} watch(es))",
                        )
                        connected
                    }
                    ?: run {
                        Log.w(TAG, "isWatchConnected: provider query returned a null cursor.")
                        false
                    }
            } catch (e: Exception) {
                Log.e(TAG, "isWatchConnected: provider query failed: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Sends a single alarm epoch-second value to the watchface as key 111 (`uint32`).
     *
     * The [epochSeconds] value is truncated to 32 bits ([Int]) for compatibility with the
     * watchface's C uint32_t receiver.
     *
     * @param context application context for the PebbleKit broadcast.
     * @param epochSeconds alarm timestamp as Unix epoch seconds.
     */
    fun sendAlarmSync(context: Context, epochSeconds: Long) {
        launch("alarm-sync") {
            Log.d(TAG, "sendAlarmSync: sending alarm epoch=$epochSeconds")
            val dict = PebbleDictionary()
            dict.addUint32(PebbleMessageKeys.KEY_SYNC_ALARM, epochSeconds.toInt())
            performSend(context, dict, "alarm-sync")
            _alarmSyncStatus.value = _alarmSyncStatus.value.copy(
                lastTransmitStatus = "Sent",
                lastSuccessfulSyncTime = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Sends a list of calendar event epoch-second values as key 112 (`uint8[]`, little-endian uint32).
     *
     * @param context application context for the PebbleKit broadcast.
     * @param epochSecondList list of event timestamps as Unix epoch seconds.
     */
    fun sendCalendarSync(context: Context, epochSecondList: List<Long>) {
        val bytes = encodeEpochsAsUint8LittleEndian(epochSecondList)
        Log.d(TAG, "sendCalendarSync: sending ${epochSecondList.size} events (${bytes.size} bytes)")
        launch("calendar-sync") {
            val dict = PebbleDictionary()
            dict.addBytes(PebbleMessageKeys.KEY_SYNC_CALENDAR, bytes)
            performSend(context, dict, "calendar-sync")
            _calendarSyncStatus.value = _calendarSyncStatus.value.copy(
                lastTransmitStatus = "Sent",
                lastSuccessfulSyncTime = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Serializes one classic PebbleKit send through the [Mutex], executed on [Dispatchers.IO].
     * Fire-and-forget broadcast — the classic path has no ACK/retry mechanism.
     */
    private fun performSend(context: Context, dict: PebbleDictionary, label: String) {
        try {
            PebbleKit.sendDataToPebble(context, PebbleMessageKeys.WATCHFACE_UUID, dict)
            Log.i(TAG, "$label -> sent(classic)")
        } catch (e: Exception) {
            Log.e(TAG, "$label send failed: ${e.message}", e)
        }
    }

    /**
     * Launches a send on the shared background scope, serialized through the [sendMutex].
     */
    private fun launch(label: String, block: suspend () -> Unit) {
        scope.launch {
            sendMutex.withLock { block() }
        }
    }

    /** Cancels the background coroutine scope. Call when done using this manager. */
    fun close() {
        scope.cancel()
        Log.d(TAG, "close: PebbleCommunicationManager released.")
    }

    companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG

        /** Package of the Core Devices Pebble app hosting the PebbleKit content provider. */
        private const val CORE_APP_PACKAGE = "coredevices.coreapp"

        /** Provider authority: `<package>.pebblekit`. */
        private const val CORE_PROVIDER_AUTHORITY = "$CORE_APP_PACKAGE.pebblekit"

        /** Path of the connected-watches table (PebbleKit2 ConnectedWatch.CONTENT_PATH). */
        private const val CONNECTED_WATCHES_PATH = "connectedWatches"

        /** ConnectedWatch.ID column (watch id string). */
        private const val COLUMN_WATCH_ID = "ID"

        /** Builds the provider authority URI: `content://coredevices.coreapp.pebblekit`. */
        private fun coreAuthorityUri(): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(CORE_PROVIDER_AUTHORITY)
            .build()
    }
}