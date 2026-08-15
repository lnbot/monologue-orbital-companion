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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class PebbleCommunicationManager(context: Context) {

    private val appContext = context.applicationContext

    private val sender: DefaultPebbleSender by lazy { DefaultPebbleSender(appContext) }
    private val infoRetriever: DefaultPebbleInfoRetriever by lazy { DefaultPebbleInfoRetriever(appContext) }

    // Reactive status of the most recent transmit for each channel.
    private val _alarmSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val alarmSyncStatus: StateFlow<ChannelSyncStatus> = _alarmSyncStatus.asStateFlow()

    private val _calendarSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val calendarSyncStatus: StateFlow<ChannelSyncStatus> = _calendarSyncStatus.asStateFlow()

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
        return sendAndRecord(SyncChannel.ALARM, payload, "alarm-sync")
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
        return sendAndRecord(SyncChannel.CALENDAR, payload, "calendar-sync")
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
            Log.d(TAG, "isWatchfaceRunning: active app UUID=${active?.id}, type=${active?.type}, expected UUID=${PebbleMessageKeys.WATCHFACE_UUID}")
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

    /**
     * Sends [payload] and records the outcome on the given channel's reactive status, updating the
     * last successful sync time only when the transmit succeeded.
     *
     * @return true if the message was acknowledged by the watch, false otherwise.
     */
    private suspend fun sendAndRecord(
        channel: SyncChannel,
        payload: PebbleDictionary,
        label: String,
    ): Boolean {
        val outcome = transmit(payload, label)
        val now = System.currentTimeMillis()
        when (channel) {
            SyncChannel.ALARM -> {
                _alarmSyncStatus.value = _alarmSyncStatus.value.copy(
                    lastTransmitStatus = outcome.status,
                    lastSuccessfulSyncTime = if (outcome.success) now
                    else _alarmSyncStatus.value.lastSuccessfulSyncTime,
                )
            }
            SyncChannel.CALENDAR -> {
                _calendarSyncStatus.value = _calendarSyncStatus.value.copy(
                    lastTransmitStatus = outcome.status,
                    lastSuccessfulSyncTime = if (outcome.success) now
                    else _calendarSyncStatus.value.lastSuccessfulSyncTime,
                )
            }
        }
        Log.i(
            TAG,
            "$label: transmit status='${outcome.status}' success=${outcome.success}" +
                (if (outcome.success) " lastSuccessfulSync=$now" else ""),
        )
        return outcome.success
    }

    private suspend fun transmit(payload: PebbleDictionary, label: String): TransmitOutcome {
        return try {
            Log.d(TAG, "$label: transmit: sending to UUID=${PebbleMessageKeys.WATCHFACE_UUID}")
            val results = sender.sendDataToPebble(PebbleMessageKeys.WATCHFACE_UUID, payload)
            if (results == null) {
                Log.w(
                    TAG,
                    "$label: transmission failed, Pebble app is not reachable (not installed or not selected).",
                )
                return TransmitOutcome(false, "Pebble app not reachable")
            }
            if (results.isEmpty()) {
                Log.w(TAG, "$label: no connected watches to send to.")
                return TransmitOutcome(false, "Watch not connected")
            }
            val failed = results.values.firstOrNull { it != TransmissionResult.Success }
            if (failed == null) {
                Log.i(TAG, "$label: successfully acknowledged by watch.")
                return TransmitOutcome(true, "Success")
            }
            results.forEach { (watch, result) ->
                Log.d(
                    TAG,
                    "$label: watch=$watch result=$result " +
                        (if (result == TransmissionResult.Success) "(ACK)" else "(FAILED)"),
                )
            }
            TransmitOutcome(false, failed.toStatusString())
        } catch (e: Exception) {
            Log.e(TAG, "$label: exception while sending: ${e.message}", e)
            TransmitOutcome(false, "Unknown")
        }
    }

    /** Outcome of a single [transmit] attempt: whether it succeeded and a human-readable status. */
    private class TransmitOutcome(val success: Boolean, val status: String)

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

/**
 * Maps [TransmissionResult] to a short, human-readable status string for the UI status section.
 */
private fun TransmissionResult.toStatusString(): String = when (this) {
    TransmissionResult.Success -> "Success"
    TransmissionResult.FailedWatchNotConnected -> "Watch not connected"
    TransmissionResult.FailedWatchNacked -> "Nacked"
    TransmissionResult.FailedTimeout -> "Timeout"
    TransmissionResult.FailedDifferentAppOpen -> "Different app open"
    TransmissionResult.FailedNoPermissions -> "No permissions"
    is TransmissionResult.Unknown -> "Unknown"
}
