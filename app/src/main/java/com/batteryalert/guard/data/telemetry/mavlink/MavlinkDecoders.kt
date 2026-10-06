package com.batteryalert.guard.data.telemetry.mavlink

/**
 * Decoded payloads, in the units the rest of the app uses.
 *
 * Every field is nullable and every null means "the vehicle did not say", matching the rule
 * the domain models already follow. A decoder that quietly substituted a zero for a missing
 * field would turn an unsent temperature into a real 0 °C reading and an unsent cell into a
 * dead one.
 *
 * MAVLink's own sentinel values are translated here too, at the boundary, so nothing
 * downstream ever has to know that `battery_remaining = -1` means "not estimated" rather than
 * "flat".
 */
internal data class HeartbeatMessage(
    /** `base_mode` bit 7 — MAV_MODE_FLAG_SAFETY_ARMED. */
    val armed: Boolean,
    /** MAV_STATE. 3 is STANDBY, 4 is ACTIVE. */
    val systemStatus: Int?,
    val vehicleType: Int?,
)

internal data class SysStatusMessage(
    val packVolts: Double?,
    val currentAmps: Double?,
    val remainingPercent: Int?,
)

internal data class BatteryStatusMessage(
    /** Summed from [cellVolts] — this message carries no pack total of its own. */
    val packVolts: Double?,
    val cellVolts: List<Double>,
    val currentAmps: Double?,
    val temperatureCelsius: Double?,
    val consumedMah: Double?,
    val remainingPercent: Int?,
    val timeRemainingSeconds: Int?,
)

internal data class GlobalPositionMessage(
    val latitudeDegrees: Double?,
    val longitudeDegrees: Double?,
    /** Above mean sea level. GLOBAL_POSITION_INT reports this in millimetres. */
    val altitudeMslMeters: Double?,
    /** Above the home point. This is the figure the RTL calculation wants. */
    val altitudeAboveHomeMeters: Double?,
    val headingDegrees: Double?,
)

internal data class VfrHudMessage(
    val groundSpeedMps: Double?,
    val headingDegrees: Double?,
    val throttlePercent: Int?,
    val altitudeMeters: Double?,
)

/**
 * The point the vehicle has set as home.
 *
 * Both coordinates are non-null, unlike every other decoded message here, because this type is
 * only produced when both were present — see [MavlinkDecoders.homePosition]. A "home position"
 * with a missing half is not a position, and modelling it as one would push a null check down
 * onto every caller that wants a distance.
 *
 * Only the two coordinates are decoded. `HOME_POSITION` also carries a local-frame offset and
 * a landing-approach vector; this app computes a ground distance to a point and has no use for
 * either, and decoding fields nothing reads is how a wrong offset goes unnoticed.
 */
internal data class HomePositionMessage(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
)

/**
 * Payload to [decoded message], one function per message type.
 *
 * Each one reads by fixed offset from a table worked out from the dialect's field ordering —
 * see [BatteryStatusLayout] for the one case where that ordering is not fixed. None of them
 * validate anything: by the time a payload gets here its frame has already passed its
 * checksum, so these are pure translations from wire units to app units.
 */
internal object MavlinkDecoders {

    // --- HEARTBEAT (id 0) ----------------------------------------------------------------
    // custom_mode u32 @0; type u8 @4; autopilot u8 @5; base_mode u8 @6;
    // system_status u8 @7; mavlink_version u8 @8.

    private const val HEARTBEAT_BASE_MODE_OFFSET = 6
    private const val HEARTBEAT_SYSTEM_STATUS_OFFSET = 7
    private const val HEARTBEAT_TYPE_OFFSET = 4

    /** base_mode bit 7. Named rather than inlined because `and 0x80` explains nothing. */
    private const val MAV_MODE_FLAG_SAFETY_ARMED = 0x80

    fun heartbeat(payload: ByteArray): HeartbeatMessage {
        val reader = MavlinkPayloadReader(payload)
        val baseMode = reader.uint8(HEARTBEAT_BASE_MODE_OFFSET)
        return HeartbeatMessage(
            armed = baseMode != null && (baseMode and MAV_MODE_FLAG_SAFETY_ARMED) != 0,
            systemStatus = reader.uint8(HEARTBEAT_SYSTEM_STATUS_OFFSET),
            vehicleType = reader.uint8(HEARTBEAT_TYPE_OFFSET),
        )
    }

    // --- SYS_STATUS (id 1) ---------------------------------------------------------------
    // three u32 @0/@4/@8; load u16 @12; voltage_battery u16 @14 (mV);
    // current_battery i16 @16 (cA); drop/errors u16 @18..@28; battery_remaining i8 @30.

    private const val SYS_STATUS_VOLTAGE_OFFSET = 14
    private const val SYS_STATUS_CURRENT_OFFSET = 16
    private const val SYS_STATUS_REMAINING_OFFSET = 30

    /** MAVLink's "no value" sentinel for signed fields: -1, not zero. */
    private const val MAVLINK_UNKNOWN = -1

    fun sysStatus(payload: ByteArray): SysStatusMessage {
        val reader = MavlinkPayloadReader(payload)
        return SysStatusMessage(
            packVolts = reader.uint16(SYS_STATUS_VOLTAGE_OFFSET)
                ?.takeIf { it != 0 }
                ?.let { it / MILLIVOLTS_PER_VOLT },
            currentAmps = reader.int16(SYS_STATUS_CURRENT_OFFSET)
                ?.takeIf { it != MAVLINK_UNKNOWN }
                ?.let { it / CENTIAMPS_PER_AMP },
            remainingPercent = reader.int8(SYS_STATUS_REMAINING_OFFSET)
                ?.takeIf { it in 0..100 },
        )
    }

    // --- BATTERY_STATUS (id 147) ---------------------------------------------------------
    // Offsets come from the layout, not from constants here — see BatteryStatusLayout.

    fun batteryStatus(
        payload: ByteArray,
        layout: BatteryStatusLayout = BatteryStatusLayout.EXTENDED,
    ): BatteryStatusMessage {
        val reader = MavlinkPayloadReader(payload)

        // Voltages arrive in millivolts, split across a ten-slot array and an overflow array
        // for packs with more than ten cells — a 12S pack fills both. Zero entries are padding
        // for slots this pack does not have, and are dropped rather than reported as dead
        // cells. The cost of that is that a genuinely dead cell in the last filled slot is
        // indistinguishable from padding, which is why the assembler corroborates the count
        // and the total against SYS_STATUS.
        val overflow = layout.voltagesExtOffset
            ?.let { reader.uint16Run(it, BatteryStatusLayout.VOLTAGE_EXT_SLOTS) }
            .orEmpty()

        val cells = (reader.uint16Run(layout.voltagesOffset, layout.voltageSlots) + overflow)
            .filter { it > 0 }
            .map { it / MILLIVOLTS_PER_VOLT }

        return BatteryStatusMessage(
            packVolts = cells.takeIf { it.isNotEmpty() }?.sum(),
            cellVolts = cells,
            currentAmps = reader.int16(layout.currentOffset)
                ?.takeIf { it != MAVLINK_UNKNOWN }
                ?.let { it / CENTIAMPS_PER_AMP },
            temperatureCelsius = reader.int16(layout.temperatureOffset)
                ?.takeIf { it != 0 }
                ?.let { it / CENTIDEGREES_PER_DEGREE },
            consumedMah = reader.int32(layout.consumedMahOffset)
                ?.takeIf { it != MAVLINK_UNKNOWN }
                ?.toDouble(),
            remainingPercent = reader.int8(layout.remainingPercentOffset)
                ?.takeIf { it in 0..100 },
            timeRemainingSeconds = reader.int32(layout.timeRemainingOffset)
                ?.takeIf { it != MAVLINK_UNKNOWN },
        )
    }

    // --- GLOBAL_POSITION_INT (id 33) -----------------------------------------------------
    // time_boot_ms u32 @0; lat i32 @4; lon i32 @8; alt i32 @12; relative_alt i32 @16;
    // vx/vy/vz i16 @20/@22/@24; hdg u16 @26.

    private const val GLOBAL_POSITION_LAT_OFFSET = 4
    private const val GLOBAL_POSITION_LON_OFFSET = 8
    private const val GLOBAL_POSITION_ALT_OFFSET = 12
    private const val GLOBAL_POSITION_RELATIVE_ALT_OFFSET = 16
    private const val GLOBAL_POSITION_HEADING_OFFSET = 26

    /** hdg is 65535 when the vehicle has no heading to report. */
    private const val MAVLINK_UNKNOWN_HEADING = 65535

    fun globalPosition(payload: ByteArray): GlobalPositionMessage {
        val reader = MavlinkPayloadReader(payload)
        return GlobalPositionMessage(
            latitudeDegrees = reader.int32(GLOBAL_POSITION_LAT_OFFSET)
                ?.takeIf { it != 0 }
                ?.let { it / DEGREES_E7 },
            longitudeDegrees = reader.int32(GLOBAL_POSITION_LON_OFFSET)
                ?.takeIf { it != 0 }
                ?.let { it / DEGREES_E7 },
            altitudeMslMeters = reader.int32(GLOBAL_POSITION_ALT_OFFSET)
                ?.let { it / MILLIMETRES_PER_METRE },
            altitudeAboveHomeMeters = reader.int32(GLOBAL_POSITION_RELATIVE_ALT_OFFSET)
                ?.let { it / MILLIMETRES_PER_METRE },
            headingDegrees = reader.uint16(GLOBAL_POSITION_HEADING_OFFSET)
                ?.takeIf { it != MAVLINK_UNKNOWN_HEADING }
                ?.let { it / CENTIDEGREES_PER_DEGREE },
        )
    }

    // --- VFR_HUD (id 74) -----------------------------------------------------------------
    // airspeed f32 @0; groundspeed f32 @4; alt f32 @8; climb f32 @12;
    // heading i16 @16 (degrees); throttle u16 @18 (%).

    private const val VFR_HUD_GROUNDSPEED_OFFSET = 4
    private const val VFR_HUD_ALTITUDE_OFFSET = 8
    private const val VFR_HUD_HEADING_OFFSET = 16
    private const val VFR_HUD_THROTTLE_OFFSET = 18

    fun vfrHud(payload: ByteArray): VfrHudMessage {
        val reader = MavlinkPayloadReader(payload)
        return VfrHudMessage(
            groundSpeedMps = reader.float32(VFR_HUD_GROUNDSPEED_OFFSET)?.toDouble(),
            headingDegrees = reader.int16(VFR_HUD_HEADING_OFFSET)?.toDouble(),
            throttlePercent = reader.uint16(VFR_HUD_THROTTLE_OFFSET),
            altitudeMeters = reader.float32(VFR_HUD_ALTITUDE_OFFSET)?.toDouble(),
        )
    }

    // --- HOME_POSITION (id 242) ----------------------------------------------------------
    // lat i32 @0; lon i32 @4; alt i32 @8; then ten floats — x, y, z, q[4], approach_x/y/z —
    // none of which this app reads. All three int32 fields are the same size, so the stable
    // sort leaves the two coordinates exactly where the definition declares them.

    private const val HOME_POSITION_LAT_OFFSET = 0
    private const val HOME_POSITION_LON_OFFSET = 4

    /**
     * The home point, or null when the vehicle has not set one.
     *
     * Null rather than a zeroed pair, because a latitude and longitude of *exactly* zero is
     * the Gulf of Guinea. MAVLink's convention is that an unset home point reads as zeroes,
     * and taking that at face value would put every aircraft's home several thousand
     * kilometres out to sea — which FR 3.1 would act on by demanding an immediate return from
     * the moment the pack passed the safety margin. The same sentinel is already applied to
     * `GLOBAL_POSITION_INT` above; this is the identical rule, and it has to be, or a fix and
     * a home would be judged by two different standards.
     */
    fun homePosition(payload: ByteArray): HomePositionMessage? {
        val reader = MavlinkPayloadReader(payload)
        val latitude = reader.int32(HOME_POSITION_LAT_OFFSET)?.takeIf { it != 0 } ?: return null
        val longitude = reader.int32(HOME_POSITION_LON_OFFSET)?.takeIf { it != 0 } ?: return null

        return HomePositionMessage(
            latitudeDegrees = latitude / DEGREES_E7,
            longitudeDegrees = longitude / DEGREES_E7,
        )
    }

    // --- Unit conversions ----------------------------------------------------------------
    // Written as divisions of an Int by a Double so the result is a Double without a cast, and
    // named so the wire units are obvious at the call site rather than inferred from a magic
    // number.

    private const val MILLIVOLTS_PER_VOLT = 1_000.0
    private const val CENTIAMPS_PER_AMP = 100.0
    private const val CENTIDEGREES_PER_DEGREE = 100.0
    private const val MILLIMETRES_PER_METRE = 1_000.0
    private const val DEGREES_E7 = 10_000_000.0
}
