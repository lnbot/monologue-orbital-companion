package com.overstuffed.monologue_orbital_companion

import java.util.UUID

/**
 * Shared constants for the Monologue Orbital Pebble watchface companion.
 *
 * These keys must agree exactly with the values defined on the watchface (C) side of
 * the AppMessage protocol.
 */
object PebbleMessageKeys {

    /** Log tag used across all Monologue Orbital Companion components. */
    const val LOG_TAG = "MonologueCompanion"

    /** UUID of the "Monologue Orbital" watchface this app talks to. */
    val WATCHFACE_UUID: UUID = UUID.fromString("c4b040f4-ea4c-481c-8050-355006f5804d")

    /** `uint32`: Next alarm epoch seconds, phone -> watch. */
    const val KEY_SYNC_ALARM: UInt = 111u

    /** `uint8[]`: Encoded list of calendar event epoch seconds (little-endian uint32), phone -> watch. */
    const val KEY_SYNC_CALENDAR: UInt = 112u

    /** `uint8`: Flag sent by the watch -> phone to request a re-sync. */
    const val KEY_SYNC_REQUEST: UInt = 110u
}

/**
 * Encodes a list of epoch-second values into a single [ByteArray] of 32-bit unsigned integers stored
 * in **little-endian** byte order (least-significant byte first), exactly as the watchface expects
 * for [PebbleMessageKeys.KEY_SYNC_CALENDAR].
 *
 * For each timestamp only the lower 32 bits are kept ([uint32] semantics), so values beyond the year
 * 2106 wrap as the C watchface expects.
 *
 * Example: `epochSeconds = [1700000000L]` (0x655B9A00) produces `[0x00, 0x9A, 0x5B, 0x65]`.
 */
fun encodeEpochsAsUint8LittleEndian(epochSeconds: List<Long>): ByteArray {
    return ByteArray(epochSeconds.size * 4).also { out ->
        epochSeconds.forEachIndexed { index, epoch ->
            val u32 = epoch.toInt() // keep only the lower 32 bits (uint32 semantics)
            val base = index * 4
            out[base] = (u32 and 0xFF).toByte()
            out[base + 1] = ((u32 ushr 8) and 0xFF).toByte()
            out[base + 2] = ((u32 ushr 16) and 0xFF).toByte()
            out[base + 3] = ((u32 ushr 24) and 0xFF).toByte()
        }
    }
}
