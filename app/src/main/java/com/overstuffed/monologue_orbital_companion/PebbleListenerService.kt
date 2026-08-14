package com.overstuffed.monologue_orbital_companion

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/**
 * Receives AppMessages from the Monologue Orbital watchface.
 *
 * This service is registered in [AndroidManifest.xml] with the
 * `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` intent filter and an exported=true flag so PebbleKit2
 * can deliver inbound messages from the watch.
 */
class PebbleListenerService : BasePebbleListenerService() {

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        // Only handle messages for our watchface; silently ignore others.
        if (watchappUUID != PebbleMessageKeys.WATCHFACE_UUID) {
            Log.w(
                TAG,
                "onMessageReceived: ignoring message from foreign watchapp $watchappUUID (watch=$watch).",
            )
            return ReceiveResult.Nack
        }

        Log.d(TAG, "onMessageReceived: watchapp=$watchappUUID watch=$watch data=$data")

        // Watch -> phone request to re-sync. Any numeric value present acts as the trigger flag.
        if (data.containsKey(PebbleMessageKeys.KEY_SYNC_REQUEST)) {
            Log.i(TAG, "onMessageReceived: sync request (key ${PebbleMessageKeys.KEY_SYNC_REQUEST}) from watch=$watch.")
            SyncCoordinator.requestSync()
        } else {
            Log.d(TAG, "onMessageReceived: received message with keys ${data.keys} (no sync request).")
        }

        // Acknowledge so the watch knows the message was received and handled.
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != PebbleMessageKeys.WATCHFACE_UUID) return
        Log.i(TAG, "onAppOpened: Monologue Orbital opened on watch=$watch.")
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != PebbleMessageKeys.WATCHFACE_UUID) return
        Log.i(TAG, "onAppClosed: Monologue Orbital closed on watch=$watch.")
    }

    companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG
    }
}
