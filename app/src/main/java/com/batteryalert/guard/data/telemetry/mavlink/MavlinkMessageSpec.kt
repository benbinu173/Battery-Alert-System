package com.batteryalert.guard.data.telemetry.mavlink

/**
 * The messages this app knows how to read, and the shipped constants needed to read them.
 *
 * ### These constants are dialect data, and they are not verifiable from here
 *
 * A MAVLink frame's checksum is seeded with a per-message value — the **CRC extra** — and
 * there is no way to derive it from the frame itself. A parser given the wrong one rejects
 * every frame of that message type, and the failure is quiet: the link looks alive, the
 * dashboard simply never updates.
 *
 * The values below are the published ones for the `common` dialect, recorded from
 * `MAVLINK_MSG_ID_<NAME>_CRC` in `common/mavlink_msg_<name>.h`. They are **seeds**, not
 * truths: [MavlinkDialect] holds them, watches the wire for evidence that one is wrong, and
 * can replace one after it has been seen consistently. That machinery exists precisely
 * because this table cannot be confirmed from inside this repository.
 *
 * ### Why the failure direction is acceptable
 *
 * The parser cannot silently misinterpret a frame it has no extra for. It has no choice but
 * to reject it, and rejected frames are counted rather than ignored. A wrong constant
 * produces a link that is visibly dead — the direction to fail in.
 */
internal object MavlinkMessageSpec {

    // --- Message ids ---------------------------------------------------------------
    const val ID_HEARTBEAT = 0
    const val ID_SYS_STATUS = 1
    const val ID_GLOBAL_POSITION_INT = 33
    const val ID_VFR_HUD = 74
    const val ID_BATTERY_STATUS = 147
    const val ID_HOME_POSITION = 242

    /** Message ids this app decodes. Anything else is structurally parsed and discarded. */
    val SUPPORTED: Set<Int> = setOf(
        ID_HEARTBEAT,
        ID_SYS_STATUS,
        ID_GLOBAL_POSITION_INT,
        ID_VFR_HUD,
        ID_BATTERY_STATUS,
        ID_HOME_POSITION,
    )

    /**
     * Seed CRC extras for the `common` dialect.
     *
     * Source: `MAVLINK_MSG_ID_<NAME>_CRC` in `mavlink/c_library_v2`. A divergence the dialect
     * detects at runtime is reported rather than hidden; see [MavlinkDialect].
     *
     * `HOME_POSITION` is the entry to be suspicious of. The other four were recorded from the
     * generated headers; this one is the least verifiable of the five, and it is also the one
     * the runtime correction recovers from most slowly — the vehicle sends it once when it
     * sets its home, not several times a second, so three consistent sightings can take a
     * whole flight. The failure direction is still the safe one: a wrong extra means the frame
     * is rejected and counted, never misread. If the diagnostics screen reports a correction
     * for message 242, that value is the real one and belongs here.
     */
    private val CRC_EXTRA: Map<Int, Int> = mapOf(
        ID_HEARTBEAT to 50,
        ID_SYS_STATUS to 124,
        ID_GLOBAL_POSITION_INT to 104,
        ID_VFR_HUD to 20,
        ID_BATTERY_STATUS to 154,
        ID_HOME_POSITION to 104,
    )

    /**
     * Reference payload lengths, in bytes, for the dialect above.
     *
     * Reference only. These are **not** used to accept or reject a frame, and they must not
     * be: MAVLink v2 strips trailing zero bytes from a payload and the receiver zero-extends
     * it back, so a short payload is normal and its length carries no information about which
     * fields are present. They are here to bound the parser's length sanity check and to give
     * a diagnostic something concrete to print next to the observed length.
     *
     * `BATTERY_STATUS` is the longest of its two common definitions; see [BatteryStatusLayout]
     * for why there are two.
     */
    private val PAYLOAD_LENGTH: Map<Int, Int> = mapOf(
        ID_HEARTBEAT to 9,
        ID_SYS_STATUS to 31,
        ID_GLOBAL_POSITION_INT to 28,
        ID_VFR_HUD to 20,
        ID_BATTERY_STATUS to 54,
        // Twelve base fields: three int32 and ten floats, then the `time_usec` extension.
        ID_HOME_POSITION to 60,
    )

    fun seedCrcExtra(messageId: Int): Int? = CRC_EXTRA[messageId]

    fun referencePayloadLength(messageId: Int): Int? = PAYLOAD_LENGTH[messageId]

    fun isSupported(messageId: Int): Boolean = messageId in SUPPORTED

    /** The shipped constants, as a map, for the dialect to take as its starting point. */
    fun seedTable(): Map<Int, Int> = CRC_EXTRA
}
