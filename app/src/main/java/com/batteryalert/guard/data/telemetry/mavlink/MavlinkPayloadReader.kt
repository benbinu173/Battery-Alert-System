package com.batteryalert.guard.data.telemetry.mavlink

/**
 * Bounds-checked, little-endian reads over a MAVLink payload.
 *
 * Every accessor returns null when the payload is too short to contain the field. That is not
 * a convenience — it is the behaviour the whole app depends on. MAVLink v2 strips trailing
 * zero bytes from a payload, so a truncated payload is normal and its absence of a field is
 * genuinely "this vehicle did not send that", not "that value is zero". The domain models
 * already hold that distinction; this is where it is first established. Reporting a missing
 * temperature as 0 °C would be a false reading, and reporting a missing cell as 0 V would
 * trip a cell-fault alert on a healthy pack.
 *
 * Reads are little-endian because MAVLink is, on every architecture.
 */
internal class MavlinkPayloadReader(private val payload: ByteArray) {

    val size: Int get() = payload.size

    /** True when the payload is long enough to contain [count] bytes at [offset]. */
    fun has(offset: Int, count: Int): Boolean =
        offset >= 0 && count >= 0 && offset + count <= payload.size

    fun uint8(offset: Int): Int? =
        if (has(offset, 1)) payload[offset].toInt() and 0xFF else null

    fun int8(offset: Int): Int? =
        if (has(offset, 1)) payload[offset].toInt() else null

    fun uint16(offset: Int): Int? =
        if (has(offset, 2)) {
            (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            null
        }

    /** 16-bit, sign-extended. */
    fun int16(offset: Int): Int? =
        if (has(offset, 2)) {
            val raw = (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8)
            raw.toShort().toInt()
        } else {
            null
        }

    /**
     * 32-bit, widened to [Long] so a value above `Int.MAX_VALUE` — which MAVLink does use for
     * unsigned fields — does not arrive negative.
     */
    fun uint32(offset: Int): Long? =
        if (has(offset, 4)) {
            (payload[offset].toLong() and 0xFF) or
                ((payload[offset + 1].toLong() and 0xFF) shl 8) or
                ((payload[offset + 2].toLong() and 0xFF) shl 16) or
                ((payload[offset + 3].toLong() and 0xFF) shl 24)
        } else {
            null
        }

    fun int32(offset: Int): Int? =
        if (has(offset, 4)) {
            (payload[offset].toInt() and 0xFF) or
                ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                ((payload[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            null
        }

    fun float32(offset: Int): Float? =
        if (has(offset, 4)) Float.fromBits(int32(offset)!!) else null

    /**
     * A run of `uint16`, stopping early at the end of the payload.
     *
     * Returns however many values are actually present rather than null for the whole array,
     * because a partially-present array is meaningful here: `BATTERY_STATUS.voltages` is
     * declared with ten slots and a six-cell pack fills six of them, with the rest arriving
     * as zeros that v2 drops.
     *
     * That truncation is worth knowing about. A genuinely dead cell reading 0 mV in the last
     * populated slot is also a zero byte, and it is dropped with the padding — so the array
     * can come back *shorter*, not merely wrong. The assembler reconciles the count it gets
     * against the pack configuration rather than trusting it; see
     * `MavlinkTelemetryAssembler`.
     */
    fun uint16Run(offset: Int, count: Int): List<Int> {
        val available = ((payload.size - offset).coerceAtLeast(0)) / 2
        return (0 until minOf(count, available)).mapNotNull { index ->
            uint16(offset + index * 2)
        }
    }
}
