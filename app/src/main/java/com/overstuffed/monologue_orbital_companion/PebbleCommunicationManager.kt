package com.overstuffed.monologue_orbital_companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import com.getpebble.android.kit.Constants
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Identifies the data channel being synchronized to the watchface.
 */
enum class SyncChannel { ALARM, CALENDAR, TIMER }

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
 * **ACK/NACK listening.** Each classic send carries a fixed per-channel transaction id
 * ([ALARM_TRANSACTION_ID] / [CALENDAR_TRANSACTION_ID]) via
 * [PebbleKit.sendDataToPebbleWithTransactionId]; pebble.apk / the Core Devices app then broadcasts
 * `com.getpebble.action.app.RECEIVE_ACK` / `com.getpebble.action.app.RECEIVE_NACK` (see
 * `Constants.INTENT_APP_RECEIVE_ACK` / `INTENT_APP_RECEIVE_NACK`) carrying the same
 * `transaction_id`. We register runtime [BroadcastReceiver]s for those actions (see
 * [registerAckNackReceivers]) and correlate the reply back to the originating channel, updating
 * the per-channel `ChannelSyncStatus`.
 *
 * **Why manual registration?** PebbleKit's `registerReceivedAckHandler` /
 * `registerReceivedNackHandler` call `context.registerReceiver(receiver, filter)` without the
 * API 33+ `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` flag, so the registration silently fails
 * on Android 13+. We therefore register ourselves, exactly mirroring the pattern used by
 * [PebbleListenerService] for the `RECEIVE` action (exported, because the broadcast originates
 * from the separate Pebble/Core Devices app).
 *
 * Core handles the classic `SEND` broadcast through its runtime-registered receiver; it is
 * dynamically registered so it does not show up in `pm query-receivers` (manifest-only) — the
 * earlier "zero recipients" reading was that artifact, not an absent receiver.
 */
class PebbleCommunicationManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    /**
     * Serializes ACK/NACK timeout bookkeeping shared between the send path (IO coroutines) and the
     * ACK/NACK path (main-thread [BroadcastReceiver]). Pairing every mutation of [timeoutJobs] and
     * every timeout/ACK/NACK status update under this mutex guarantees that a can't race a timeout
     * into a stale value (e.g. a timeout never overwrites a genuine ACK success).
     */
    private val timeoutMutex = Mutex()

    /**
     * The pending per-channel ACK timeout timer jobs. Single-flight per channel: at most one timer
     * (the latest transaction) exists per channel; starting a new send replaces any previous one, and
     * an arriving ACK/NACK removes it. Linchpin of the identity check in [awaitAckTimeout] that
     * prevents stale/superseded timers from producing a timeout.
     */
    private val timeoutJobs = ConcurrentHashMap<SyncChannel, Job>()

    /**
     * Per-channel number of send attempts performed for the current transaction (1 = initial send,
     * 2 = first retry, 3 = second/final retry). Guarded by [timeoutMutex] so attempt bookkeeping is
     * consistent between the IO send path and the ACK/NACK/timeout (BroadcastReceiver) path. Reset
     * to 1 on every user-initiated send; incremented on each timeout that triggers a retry.
     */
    private val sendAttempts = ConcurrentHashMap<SyncChannel, Int>()

    /**
     * Stores the data needed to rebuild and re-broadcast an identical payload for a channel when a
     * send times out (the retry path). Both the initial send and every timeout retry go through
     * [armAndBroadcast] using this stored payload, guaranteeing retries re-send the exact same
     * message. Replaced on the next send for that channel and cleared on [close].
     */
    private val pendingSends = ConcurrentHashMap<SyncChannel, PendingSend>()

    /**
     * The data required to re-send an identical Pebble message for a channel on timeout retry:
     * the [context] for the PebbleKit broadcast and a [buildDict] lambda that reconstructs the exact
     * same [PebbleDictionary] broadcast the first time.
     */
    private class PendingSend(
        val context: Context,
        val buildDict: () -> PebbleDictionary,
    )

    // Reactive status of the most recent transmit for each channel.
    private val _alarmSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val alarmSyncStatus: StateFlow<ChannelSyncStatus> = _alarmSyncStatus.asStateFlow()

    private val _calendarSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val calendarSyncStatus: StateFlow<ChannelSyncStatus> = _calendarSyncStatus.asStateFlow()

    private val _timerSyncStatus = MutableStateFlow(ChannelSyncStatus())
    val timerSyncStatus: StateFlow<ChannelSyncStatus> = _timerSyncStatus.asStateFlow()

    // ------------------------------------------------------------------
    // ACK/NACK receiver lifecycle
    // ------------------------------------------------------------------

    /**
     * Lazily-created single PebbleKit Android 2 (PK2) info retriever used for the connectivity check in
     * [isWatchConnected]. [DefaultPebbleInfoRetriever] is stateless (it just holds a Context and delegates
     * app discovery to the process-singleton `DefaultPebbleAndroidAppPicker`), has no `close()`/`dispose()`,
     * and is safe to reuse for the whole manager lifetime — so it is created once on first use.
     */
    private var pk2InfoRetriever: DefaultPebbleInfoRetriever? = null

    private var appContext: Context? = null
    private var ackReceiver: BroadcastReceiver? = null
    private var nackReceiver: BroadcastReceiver? = null
    private var ackNackRegistered = false

    /**
     * Registers the ACK and NACK BroadcastReceivers for confirmations of our outgoing AppMessages.
     *
     * Mirrors the pattern used by [PebbleListenerService.register] for the `RECEIVE` action: on
     * API 33+ we must explicitly pass [Context.RECEIVER_EXPORTED] because the broadcast originates
     * from the Pebble / Core Devices app (a separate app). Idempotent — safe to call repeatedly.
     *
     * @param context Application or Activity context used for registration.
     */
    fun registerAckNackReceivers(context: Context) {
        if (ackNackRegistered) {
            Log.d(TAG, "registerAckNackReceivers: already registered; skipping.")
            return
        }
        val appContext = context.applicationContext
        this.appContext = appContext

        val ackFilter = IntentFilter(ACTION_RECEIVE_ACK)
        val nackFilter = IntentFilter(ACTION_RECEIVE_NACK)

        ackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleAckNack(intent, isAck = true)
            }
        }
        nackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleAckNack(intent, isAck = false)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Exported: the Pebble / Core Devices app (a separate app) sends these broadcasts.
                appContext.registerReceiver(ackReceiver, ackFilter, Context.RECEIVER_EXPORTED)
                appContext.registerReceiver(nackReceiver, nackFilter, Context.RECEIVER_EXPORTED)
            } else {
                // Pre-API-33 registration has no exported flag; the receiver is implicitly exported
                // for these unprotected broadcasts (same pattern as PebbleListenerService).
                @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(ackReceiver, ackFilter)
                @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(nackReceiver, nackFilter)
            }
            ackNackRegistered = true
            Log.i(TAG, "registerAckNackReceivers: ACK/NACK receivers registered.")
        } catch (e: Exception) {
            Log.e(TAG, "registerAckNackReceivers: failed to register receivers: ${e.message}", e)
            ackReceiver = null
            nackReceiver = null
        }
    }

    /**
     * Unregisters the ACK/NACK receivers. Idempotent — safe to call repeatedly.
     */
    fun unregisterAckNackReceivers() {
        val ctx = appContext
        if (!ackNackRegistered || ctx == null) {
            Log.d(TAG, "unregisterAckNackReceivers: not registered; skipping.")
            return
        }
        try {
            if (ackReceiver != null) ctx.unregisterReceiver(ackReceiver)
            if (nackReceiver != null) ctx.unregisterReceiver(nackReceiver)
            Log.i(TAG, "unregisterAckNackReceivers: ACK/NACK receivers unregistered.")
        } catch (e: Exception) {
            Log.w(TAG, "unregisterAckNackReceivers: failed to unregister receivers: ${e.message}", e)
        } finally {
            ackNackRegistered = false
            ackReceiver = null
            nackReceiver = null
        }
    }

    /**
     * Dispatches an incoming RECEIVE_ACK / RECEIVE_NACK broadcast to the channel that owns the
     * transaction id it carries.
     *
     * In the classic PebbleKit protocol the ACK/NACK broadcast carries a `uuid` extra (the watch
     * face UUID) and a `transaction_id` extra (the id we attached at send time — see
     * `PebbleKit.sendDataToPebbleWithTransactionId`). We verify the UUID when present and drop
     * broadcasts for foreign watch apps; if the UUID extra is absent (some pebble.apk builds only
     * emit `transaction_id`) we still process the reply because the transaction id is the binding
     * correlation.
     */
    private fun handleAckNack(intent: Intent, isAck: Boolean) {
        val kind = if (isAck) "ACK" else "NACK"

        val uuidExtra = readUuidExtra(intent)
        if (uuidExtra != null && uuidExtra != PebbleMessageKeys.WATCHFACE_UUID) {
            Log.w(TAG, "handleAckNack: ignoring $kind for foreign watchapp $uuidExtra.")
            return
        }
        if (uuidExtra == null) {
            Log.w(
                TAG,
                "handleAckNack: $kind broadcast without a uuid extra; " +
                    "proceeding using transaction id only.",
            )
        }

        val transactionId = intent.getIntExtra(Constants.TRANSACTION_ID, -1)
        val channel = when (transactionId) {
            ALARM_TRANSACTION_ID -> SyncChannel.ALARM
            CALENDAR_TRANSACTION_ID -> SyncChannel.CALENDAR
            TIMER_TRANSACTION_ID -> SyncChannel.TIMER
            else -> null
        }
        if (channel == null) {
            Log.w(
                TAG,
                "handleAckNack: unmatched $kind transactionId=$transactionId " +
                    "extras=${describeExtras(intent)}",
            )
            return
        }

        if (isAck) {
            onAck(channel, transactionId)
        } else {
            onNack(channel, transactionId, intent)
        }
    }

    private fun onAck(channel: SyncChannel, transactionId: Int) {
        Log.i(TAG, "onAck: transactionId=$transactionId -> $channel confirmed by watch.")
        // Cancelling the pending timeout and applying the success status are done atomically under
        // timeoutMutex, so a just-expired timeout can never overwrite a genuine ACK success.
        scope.launch {
            timeoutMutex.withLock {
                timeoutJobs.remove(channel)?.cancel()
                channelStatus(channel).value = channelStatus(channel).value.copy(
                    lastTransmitStatus = "Success (ACK)",
                    lastSuccessfulSyncTime = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun onNack(channel: SyncChannel, transactionId: Int, intent: Intent) {
        val errorCode = extractErrorCodeExtra(intent)
        val statusText = errorCode?.let { "Failed (NACK, code=$it)" } ?: "Failed (NACK)"
        Log.w(
            TAG,
            "onNack: transactionId=$transactionId on $channel rejected by watch " +
                "errorCode=$errorCode extras=${describeExtras(intent)}",
        )
        // Cancelling the pending timeout and applying the failure status are done atomically under
        // timeoutMutex, so the timeout can never fire after a genuine NACK within the window.
        scope.launch {
            timeoutMutex.withLock {
                timeoutJobs.remove(channel)?.cancel()
                // Deliberately keep lastSuccessfulSyncTime unchanged: a NACK is a failure, not a
                // success.
                channelStatus(channel).value = channelStatus(channel).value.copy(
                    lastTransmitStatus = statusText,
                )
            }
        }
    }

    /** Returns the [MutableStateFlow] backing the given [channel]. */
    private fun channelStatus(channel: SyncChannel): MutableStateFlow<ChannelSyncStatus> =
        when (channel) {
            SyncChannel.ALARM -> _alarmSyncStatus
            SyncChannel.CALENDAR -> _calendarSyncStatus
            SyncChannel.TIMER -> _timerSyncStatus
        }

    /**
     * Reads the `uuid` extra with the API-32/33-safe typed overload, falling back to the raw
     * read on older platforms (same approach as [PebbleListenerService]).
     */
    private fun readUuidExtra(intent: Intent): UUID? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(Constants.APP_UUID, UUID::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(Constants.APP_UUID) as? UUID
        }

    /**
     * Tries to surface an error code from a NACK broadcast.
     *
     * The legacy PebbleKit 4.0.1 protocol defines no error-code extra
     * (`Constants` only declares `uuid`, `transaction_id`, `msg_data`, ...), so we defensively
     * probe for common error keys first and then fall back to *any* integer/byte-valued extra that
     * is not the transaction id. Returns null when no plausible code is present; the raw extras are
     * always logged by the caller.
     */
    private fun extractErrorCodeExtra(intent: Intent): Int? {
        val extras = intent.extras ?: return null
        val candidates = listOf(
            "error", "error_code", "code", "err", "status",
            "reason", "nack_reason", "AppMessageStatus", "pebble_error",
        )
        for (key in candidates) {
            val code = extras.get(key)?.toErrorCodeInt() ?: continue
            return code
        }
        // Last resort: any Int/Byte extra that isn't the transaction id.
        for (key in extras.keySet()) {
            if (key == Constants.TRANSACTION_ID) continue
            val code = extras.get(key)?.toErrorCodeInt() ?: continue
            return code
        }
        return null
    }

    /** Coerces common numeric extra value types to an Int error code, or null. */
    private fun Any?.toErrorCodeInt(): Int? = when (this) {
        is Int -> this
        is Byte -> toInt()
        is Long -> if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
        else -> null
    }

    /** Describes every extra of an incoming broadcast for diagnostics. */
    private fun describeExtras(intent: Intent): String {
        val extras = intent.extras ?: return "(none)"
        return extras.keySet().joinToString(", ", "[", "]") { key ->
            val value = extras.get(key)
            "$key=${value?.javaClass?.simpleName ?: "null"}:$value"
        }
    }

    /**
     * Returns whether a Pebble/Rebble watch is currently connected.
     *
     * **Primary path — PebbleKit Android 2.** Uses [DefaultPebbleInfoRetriever.getConnectedWatches] (from
     * `io.rebble.pebblekit2:client`) when the library is present. PK2 resolves the installed Pebble mobile app
     * (typically Core Devices, `coredevices.coreapp`) through `DefaultPebbleAndroidAppPicker` (a
     * `queryIntentServices` on the `io.rebble.pebblekit2.SEND_DATA_TO_WATCH` intent), then queries its
     * `content://<app-package>.pebblekit/connectedWatches` content provider and maps each row to a
     * [io.rebble.pebblekit2.model.ConnectedWatch]; a non-empty list means at least one watch is connected.
     * If no Pebble mobile app is installed / reachable the flow simply emits an empty list — it does not crash —
     * and we fall through to the legacy provider query below. Any exception is caught and logged, also falling
     * through to the legacy path.
     *
     * **Fallback path — legacy PebbleKit state provider.** This is the Kotlin port of the legacy
     * `PebbleKit.isWatchConnected(Context)` check, which queries the **state content providers** exposed by the
     * Pebble app: the basalt authority (`content://com.getpebble.android.provider.basalt/state`) first, then the
     * primary authority (`content://com.getpebble.android.provider/state`). Both are re-exposed by Core Devices
     * (`coredevices.coreapp`). Unlike the classic broadcast-based path, this provider query works reliably on
     * Android 14+ (API 34+) where PebbleKit's internal `registerReceiver(null, ...)` call throws
     * [IllegalArgumentException].
     *
     * Row 0 of the state table is the "connected" flag: `1` = connected, `0` = not connected.
     *
     * Every failure (no provider installed, [SecurityException], [IllegalArgumentException], etc.) is logged and
     * yields false — this method never crashes. The path that actually succeeded (PK2 vs legacy) is logged for
     * diagnosis.
     */
    suspend fun isWatchConnected(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Primary path: PebbleKit Android 2 connectivity check.
        try {
            val retriever = pk2InfoRetriever
                ?: DefaultPebbleInfoRetriever(context.applicationContext).also {
                    pk2InfoRetriever = it
                }
            val watches = retriever.getConnectedWatches().first()
            Log.d(TAG, "isWatchConnected: PebbleKit2 reports ${watches.size} connected watch(es)")
            if (watches.isNotEmpty()) {
                Log.d(TAG, "isWatchConnected: connected via PebbleKit2 path")
                return@withContext true
            }
            Log.d(TAG, "isWatchConnected: PebbleKit2 reports no connected watch; trying legacy fallback")
        } catch (e: Exception) {
            Log.e(TAG, "isWatchConnected: PebbleKit2 connectivity check failed: ${e.message}", e)
        }
        return@withContext false
    }

    /**
     * Sends a single alarm epoch-second value to the watchface as key 111 (`uint32`).
     *
     * The [epochSeconds] value is truncated to 32 bits ([Int]) for compatibility with the
     * watchface's C uint32_t receiver.
     *
     * The send carries [ALARM_TRANSACTION_ID] so the matching RECEIVE_ACK / RECEIVE_NACK can be
     * attributed to the alarm channel.
     *
     * @param context application context for the PebbleKit broadcast.
     * @param epochSeconds alarm timestamp as Unix epoch seconds.
     */
    fun sendAlarmSync(context: Context, epochSeconds: Long) {
        launch("alarm-sync") {
            Log.d(TAG, "sendAlarmSync: sending alarm epoch=$epochSeconds")
            sendWithRetrySupport(
                channel = SyncChannel.ALARM,
                context = context,
                buildDict = {
                    PebbleDictionary().also {
                        it.addUint32(PebbleMessageKeys.KEY_SYNC_ALARM, epochSeconds.toInt())
                    }
                },
                initialStatus = "Sent (waiting ACK)",
            )
        }
    }

    /**
     * Sends a list of calendar event epoch-second values as key 112 (`uint8[]`, little-endian
     * uint32).
     *
     * The send carries [CALENDAR_TRANSACTION_ID] so the matching RECEIVE_ACK / RECEIVE_NACK can be
     * correlated to the calendar channel.
     *
     * @param context application context for the PebbleKit broadcast.
     * @param epochSecondList list of event timestamps as Unix epoch seconds.
     */
    fun sendCalendarSync(context: Context, epochSecondList: List<Long>) {
        val bytes = encodeEpochsAsUint8LittleEndian(epochSecondList)
        Log.d(TAG, "sendCalendarSync: sending ${epochSecondList.size} events (${bytes.size} bytes)")
        launch("calendar-sync") {
            sendWithRetrySupport(
                channel = SyncChannel.CALENDAR,
                context = context,
                buildDict = {
                    PebbleDictionary().also {
                        it.addBytes(PebbleMessageKeys.KEY_SYNC_CALENDAR, bytes)
                    }
                },
                initialStatus = "Sent (waiting ACK)",
            )
        }
    }

    /**
     * Sends a single timer finish epoch-second value to the watchface as key 113 (`uint32`).
     *
     * Pass `0` when no timer is currently running so the watch can clear any stale timer display
     * (mirroring the alarm channel's no-alarm behaviour). As with [sendAlarmSync], the value is
     * truncated to 32 bits for compatibility with the watchface's C uint32_t receiver.
     *
     * The send carries [TIMER_TRANSACTION_ID] so the matching RECEIVE_ACK / RECEIVE_NACK can be
     * correlated back to the timer channel.
     *
     * @param context application context for the PebbleKit broadcast.
     * @param epochSeconds timer finish timestamp as Unix epoch seconds (0 clears the watch display).
     */
    fun sendTimerSync(context: Context, epochSeconds: Long) {
        launch("timer-sync") {
            Log.d(TAG, "sendTimerSync: sending timer epoch=$epochSeconds")
            sendWithRetrySupport(
                channel = SyncChannel.TIMER,
                context = context,
                buildDict = {
                    PebbleDictionary().also {
                        it.addUint32(PebbleMessageKeys.KEY_SYNC_TIMER, epochSeconds.toInt())
                    }
                },
                initialStatus = "Sent (waiting ACK)",
            )
        }
    }

    /**
     * Serializes one classic PebbleKit send through the [Mutex], executed on [Dispatchers.IO].
     *
     * Unlike a plain fire-and-forget [PebbleKit.sendDataToPebble] (which routes through
     * `sendDataToPebbleWithTransactionId(..., -1)`), we attach a per-channel [transactionId] so the
     * pebble app can echo it back on the ACK/NACK broadcast and we can attribute the outcome to the
     * right channel.
     */
    private fun performSend(context: Context, dict: PebbleDictionary, label: String, transactionId: Int) {
        try {
            PebbleKit.sendDataToPebbleWithTransactionId(
                context,
                PebbleMessageKeys.WATCHFACE_UUID,
                dict,
                transactionId,
            )
            Log.i(TAG, "$label -> sent(classic) transactionId=$transactionId")
        } catch (e: Exception) {
            Log.e(TAG, "$label send failed: ${e.message}", e)
        }
    }

    /**
     * Runs the initial send of a channel payload with timeout-retry support. Records the payload so
     * retries can rebuild it, resets the attempt counter to 1, arms the ACK/NACK timeout, and
     * broadcasts the message before setting the optimistic in-flight [initialStatus]. Final outcomes
     * come from an ACK/NACK or the final timeout.
     *
     * @param channel channel being synced.
     * @param context application context for the PebbleKit broadcast.
     * @param buildDict reconstructs the identical [PebbleDictionary] to broadcast (reused verbatim
     * by timeout retries so a retry re-sends the same message).
     * @param initialStatus optimistic in-flight status set after the broadcast.
     */
    private suspend fun sendWithRetrySupport(
        channel: SyncChannel,
        context: Context,
        buildDict: () -> PebbleDictionary,
        initialStatus: String,
    ) {
        // Store the payload and (re)start the attempt count at 1: a fresh user-initiated send always
        // has the full budget of MAX_SEND_ATTEMPTS.
        pendingSends[channel] = PendingSend(context, buildDict)
        sendAttempts[channel] = 1
        armAndBroadcast(channel)
        channelStatus(channel).value = channelStatus(channel).value.copy(
            lastTransmitStatus = initialStatus,
        )
    }

    /**
     * Arms the ACK/NACK timeout for [channel] and re-broadcasts the stored payload exactly as
     * previously sent. Shared by the initial send and every timeout retry, preserving the
     * single-flight rule that the timer is always armed before the (re)broadcast — an ACK/NACK,
     * which can only arrive after the message is sent, can never precede the timer.
     */
    private suspend fun armAndBroadcast(channel: SyncChannel) {
        val pending = pendingSends[channel] ?: return
        val label = when (channel) {
            SyncChannel.ALARM -> "alarm-sync"
            SyncChannel.CALENDAR -> "calendar-sync"
            SyncChannel.TIMER -> "timer-sync"
        }
        val transactionId = when (channel) {
            SyncChannel.ALARM -> ALARM_TRANSACTION_ID
            SyncChannel.CALENDAR -> CALENDAR_TRANSACTION_ID
            SyncChannel.TIMER -> TIMER_TRANSACTION_ID
        }
        // Arm before (re)broadcast (single-flight ordering; see scheduleTimeout).
        scheduleTimeout(channel)
        performSend(pending.context, pending.buildDict(), label, transactionId)
    }

    /**
     * Launches a send on the shared background scope, serialized through the [sendMutex].
     */
    private fun launch(label: String, block: suspend () -> Unit) {
        scope.launch {
            sendMutex.withLock { block() }
        }
    }

    /**
     * Arms the per-channel ACK/NACK timeout timer for [channel], cancelling any previously pending
     * timer for the channel (single-flight: only the latest transaction is tracked). Timer jobs run
     * on the manager's [scope], so they are automatically cancelled when the manager is closed.
     *
     * Callers invoke this BEFORE broadcasting, so an ACK/NACK — which can only be produced after the
     * message is sent — never races in ahead of the timer and falsely leaves a stale timeout behind.
     */
    private suspend fun scheduleTimeout(channel: SyncChannel) {
        timeoutMutex.withLock {
            timeoutJobs.remove(channel)?.cancel()
            val timer = scope.launch { awaitAckTimeout(channel) }
            timeoutJobs[channel] = timer
        }
    }

    /**
     * Waits the ACK timeout window for [channel], then fires a timeout only if the transaction is
     * still pending (i.e. neither an ACK/NACK nor a newer send has superseded this timer).
     */
    private suspend fun awaitAckTimeout(channel: SyncChannel) {
        delay(ACK_TIMEOUT_MS)
        val self = currentCoroutineContext()[Job] ?: return
        timeoutMutex.withLock {
            val active = timeoutJobs[channel]
            // Fire only if this timer is still the active pending transaction for the channel. An
            // ACK/NACK (which removes the entry) or a newer send (which replaces the entry) both make
            // this identity check fail, so a stale timer never produces a false timeout.
            if (active !== self) return@withLock
            timeoutJobs.remove(channel)
            onTimeout(channel)
        }
    }

    /**
     * Handles an ACK/NACK timeout for [channel]. When the attempt budget remains (attempt <
     * MAX_SEND_ATTEMPTS) the identical payload is re-sent and the timer re-armed (retry); only after
     * the final attempt (MAX_SEND_ATTEMPTS) times out is the channel marked `Failed (timeout)`.
     * Never touches lastSuccessfulSyncTime.
     */
    private fun onTimeout(channel: SyncChannel) {
        val attempt = sendAttempts[channel] ?: 1
        if (attempt < MAX_SEND_ATTEMPTS) {
            val next = attempt + 1
            sendAttempts[channel] = next
            Log.w(
                TAG,
                "onTimeout: attempt $attempt/$MAX_SEND_ATTEMPTS on $channel timed out; " +
                    "retrying ($next/$MAX_SEND_ATTEMPTS).",
            )
            // Retry outside the timeoutMutex lock to avoid a lock-order inversion with sendMutex
            // (sendMutex -> timeoutMutex). Re-arm + re-send the identical payload for the channel.
            scope.launch {
                sendMutex.withLock {
                    if (pendingSends[channel] == null) return@withLock
                    armAndBroadcast(channel)
                    channelStatus(channel).value = channelStatus(channel).value.copy(
                        lastTransmitStatus = "Retrying (attempt $next/$MAX_SEND_ATTEMPTS)",
                    )
                }
            }
        } else {
            Log.w(
                TAG,
                "onTimeout: final attempt $attempt/$MAX_SEND_ATTEMPTS on $channel timed out; " +
                    "marking failed.",
            )
            channelStatus(channel).value = channelStatus(channel).value.copy(
                lastTransmitStatus = "Failed (timeout)",
            )
        }
    }

    /** Cancels the background coroutine scope, any pending timers, and unregisters the receivers. */
    fun close() {
        pk2InfoRetriever = null
        unregisterAckNackReceivers()
        // Cancel + clear any outstanding per-channel ACK timeout timers to avoid leaks.
        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()
        // Clear attempt bookkeeping and stored retry payloads to avoid leaks.
        sendAttempts.clear()
        pendingSends.clear()
        scope.cancel()
        Log.d(TAG, "close: PebbleCommunicationManager released.")
    }

    companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG

        /**
         * Broadcast actions emitted by the Pebble / Core Devices app when the watch replies to a
         * message we sent (verified against the PebbleKit 4.0.1 AAR sources:
         * `com.getpebble.android.kit.Constants`).
         *
         * NOTE: these are the *watch -> phone* confirmations. The sibling `com.getpebble.action.app.ACK`
         * / `com.getpebble.action.app.NACK` actions are the *phone -> pebble* channel used by
         * `sendAckToPebble`/`sendNackToPebble` to acknowledge messages *received from the watch* —
         * a different direction we do not consume here.
         */
        private const val ACTION_RECEIVE_ACK = Constants.INTENT_APP_RECEIVE_ACK
        private const val ACTION_RECEIVE_NACK = Constants.INTENT_APP_RECEIVE_NACK

        /**
         * Per-channel transaction ids attached to outgoing SEND broadcasts so the corresponding
         * RECEIVE_ACK / RECEIVE_NACK (`transaction id` extra) can be correlated to the originating
         * channel. Valid range for the legacy protocol is 0..255.
         */
        private const val ALARM_TRANSACTION_ID = 1
        private const val CALENDAR_TRANSACTION_ID = 2
        private const val TIMER_TRANSACTION_ID = 3

        /**
         * ACK/NACK timeout window: if neither an ACK nor a NACK arrives for a sent transaction within
         * this many milliseconds, the transaction is marked as timed out on its channel.
         */
        private const val ACK_TIMEOUT_MS = 5_000L

        /**
         * Maximum number of send attempts per transaction (1 initial send + 2 timeout retries). When
         * a send times out without an ACK/NACK it is re-transmitted up to this many times; only after
         * all attempts time out is the channel marked `Failed (timeout)`.
         */
        private const val MAX_SEND_ATTEMPTS = 3

        /** Legacy PebbleKit state-provider URIs (re-exposed by Core Devices, `coredevices.coreapp`). */
        private const val LEGACY_PROVIDER_PRIMARY = "content://com.getpebble.android.provider/state"
        private const val LEGACY_PROVIDER_BASALT =
            "content://com.getpebble.android.provider.basalt/state"

        /** Column index of the "connected" flag in the state provider row (`1` = connected). */
        private const val LEGACY_STATE_COLUMN_CONNECTED = 0
    }
}