package com.batteryalert.guard.data.telemetry.mavlink

/**
 * Builds MAVLink payloads and frames for tests, from the schema rather than from offsets.
 *
 * ### Why this is written the way it is
 *
 * The obvious test helper writes `payload[14] = 5040` to place a pack voltage, and that helper
 * is worthless: it repeats the decoder's belief about where the field lives, so a wrong offset
 * in the decoder is mirrored by the same wrong offset here and every test passes. The
 * assumption is never tested because it is on both sides of the assertion.
 *
 * So payloads are described the way the MAVLink definition describes them — a list of named
 * fields with types — and the offsets are *derived* here from the rule the wire actually
 * follows: fields are ordered largest first, and fields of equal size keep their declared
 * order. A decoder that reads `voltage_battery` from the wrong place now disagrees with a
 * builder that computed the right place, and the test fails.
 *
 * What this still cannot check is whether the *field list* is right. If `BATTERY_STATUS`
 * really declares a field this helper does not know about, both sides of the test are wrong
 * together. Only the aircraft can settle that, which is exactly why the runtime corroboration
 * in `MavlinkTelemetryAssembler` exists.
 */
internal object MavlinkTestFrames {

    /** Wire types, sized as MAVLink sizes them. */
    enum class Type(val bytes: Int) {
        UINT8(1), INT8(1), UINT16(2), INT16(2), UINT32(4), INT32(4), FLOAT32(4),
    }

    /** One named field with its declared value. */
    class Field(val name: String, val type: Type, val value: Number)

    fun u8(name: String, value: Int) = Field(name, Type.UINT8, value)
    fun i8(name: String, value: Int) = Field(name, Type.INT8, value)
    fun u16(name: String, value: Int) = Field(name, Type.UINT16, value)
    fun i16(name: String, value: Int) = Field(name, Type.INT16, value)
    fun u32(name: String, value: Long) = Field(name, Type.UINT32, value)
    fun i32(name: String, value: Int) = Field(name, Type.INT32, value)
    fun f32(name: String, value: Float) = Field(name, Type.FLOAT32, value)

    /** A run of identically typed values, as a MAVLink array field. */
    fun array(name: String, type: Type, values: List<Number>): List<Field> =
        values.mapIndexed { index, value -> Field("$name[$index]", type, value) }

    /**
     * Lays the fields out the way MAVLink does and returns the payload.
     *
     * Trailing bytes are left as zeros, matching what a v1 sender emits and what a v2 receiver
     * zero-extends to. The decoders see the same thing either way.
     */
    fun payload(vararg fields: Field): ByteArray = payload(fields.toList())

    fun payload(fields: List<Field>): ByteArray {
        // `sortedByDescending` is a stable sort, so equal-sized fields keep declaration order —
        // which is the rule MAVLink's generator uses.
        val ordered = fields.sortedByDescending { it.type.bytes }
        val payload = ByteArray(ordered.sumOf { it.type.bytes })

        var offset = 0
        for (field in ordered) {
            write(payload, offset, field)
            offset += field.type.bytes
        }
        return payload
    }

    /**
     * Wraps a payload in a v1 or v2 frame, checksum and all.
     *
     * The checksum comes from the production [MavlinkCrc], which is pinned independently
     * against the published CRC-16/MCRF4XX catalogue value in `MavlinkCrcTest`. Without that
     * pin, a broken CRC would break the builder and the parser identically and every test
     * here would still pass.
     */
    fun frame(
        messageId: Int,
        payload: ByteArray,
        crcExtra: Int = MavlinkMessageSpec.seedCrcExtra(messageId) ?: 0,
        sequence: Int = 0,
        systemId: Int = 1,
        componentId: Int = 1,
        version: Int = 2,
        signed: Boolean = false,
    ): ByteArray {
        val headerBytes =
            if (version == 2) MavlinkFrame.V2_HEADER_BYTES else MavlinkFrame.V1_HEADER_BYTES
        // The signature trailer sits after the checksum and is not covered by it, so the
        // checksum is computed over the same bytes either way and only the length changes.
        val signatureBytes = if (signed) MavlinkFrame.SIGNATURE_BYTES else 0
        val frame = ByteArray(headerBytes + payload.size + MavlinkFrame.CHECKSUM_BYTES + signatureBytes)

        frame[0] = (if (version == 2) MavlinkFrame.STX_V2 else MavlinkFrame.STX_V1).toByte()
        frame[1] = payload.size.toByte()

        if (version == 2) {
            // incompat_flags bit 0 means a signature follows. This byte is inside the checksum
            // range, which is why it has to be set before the checksum is computed.
            frame[2] = if (signed) 0x01 else 0
            frame[3] = 0 // compat_flags
            frame[4] = sequence.toByte()
            frame[5] = systemId.toByte()
            frame[6] = componentId.toByte()
            frame[7] = (messageId and 0xFF).toByte()
            frame[8] = ((messageId shr 8) and 0xFF).toByte()
            frame[9] = ((messageId shr 16) and 0xFF).toByte()
        } else {
            frame[2] = sequence.toByte()
            frame[3] = systemId.toByte()
            frame[4] = componentId.toByte()
            frame[5] = messageId.toByte()
        }

        payload.copyInto(frame, headerBytes)

        val crc = MavlinkCrc.of(frame, 1, headerBytes - 1 + payload.size, crcExtra)
        frame[headerBytes + payload.size] = (crc and 0xFF).toByte()
        frame[headerBytes + payload.size + 1] = ((crc shr 8) and 0xFF).toByte()
        // A real signature is an ed25519 prefix and would have to be computed; nothing in this
        // app checks it, so any bytes will do to prove the parser skips the trailer.
        for (index in 0 until signatureBytes) {
            frame[headerBytes + payload.size + MavlinkFrame.CHECKSUM_BYTES + index] = 0x5A
        }
        return frame
    }

    /** Convenience: a full frame from a field list. */
    fun frame(
        messageId: Int,
        fields: List<Field>,
        crcExtra: Int = MavlinkMessageSpec.seedCrcExtra(messageId) ?: 0,
        version: Int = 2,
    ): ByteArray = frame(messageId, payload(fields), crcExtra = crcExtra, version = version)

    // --- Message-specific builders --------------------------------------------------------
    // These describe each message the way its MAVLink definition does, in declaration order.
    // They are the reference the decoder's offsets are checked against.

    /** A `SYS_STATUS` with only the three fields this app reads given meaningful values. */
    fun sysStatus(
        voltageMillivolts: Int,
        currentCentiamps: Int,
        remainingPercent: Int,
    ): ByteArray = payload(
        listOf(
            u32("onboard_control_sensors_present", 0L),
            u32("onboard_control_sensors_enabled", 0L),
            u32("onboard_control_sensors_health", 0L),
            u16("load", 0),
            u16("voltage_battery", voltageMillivolts),
            i16("current_battery", currentCentiamps),
            u16("drop_rate_comm", 0),
            u16("errors_comm", 0),
            u16("errors_count1", 0),
            u16("errors_count2", 0),
            u16("errors_count3", 0),
            u16("errors_count4", 0),
            i8("battery_remaining", remainingPercent),
        ),
    )

    /**
     * A `BATTERY_STATUS` payload.
     *
     * Fields are listed in the order the MAVLink definition declares them, *not* in the order
     * they land on the wire — the sort in [payload] is what decides that, and the whole point
     * is that this function does not know the answer. `fault_bitmask` really is declared after
     * `mode`, at the end of the definition, which is why it lands at offset 12 and does not
     * displace `time_remaining`.
     *
     * [includeExtendedFields] selects which of the two definitions is being described.
     */
    fun batteryStatus(
        cellMillivolts: List<Int>,
        temperatureCentidegrees: Int = 0,
        currentCentiamps: Int = -1,
        consumedMah: Int = -1,
        remainingPercent: Int = -1,
        timeRemainingSeconds: Int = -1,
        includeExtendedFields: Boolean = true,
    ): ByteArray {
        val slots = BatteryStatusLayout.EXTENDED.voltageSlots
        val declared = List(slots) { index -> cellMillivolts.getOrElse(index) { 0 } }
        // Cells past the tenth go in the overflow array, which is where a 12S pack's last two
        // cells actually live. A builder that dropped them would make a ten-cell reading look
        // correct and hide the bug this array exists to fix.
        val overflow = List(BatteryStatusLayout.VOLTAGE_EXT_SLOTS) { index ->
            cellMillivolts.getOrElse(slots + index) { 0 }
        }

        val fields = mutableListOf(
            u8("id", 0),
            u8("battery_function", 0),
            u8("type", 0),
            i16("temperature", temperatureCentidegrees),
        )
        fields += array("voltages", Type.UINT16, declared)
        fields += i16("current_battery", currentCentiamps)
        fields += i32("current_consumed", consumedMah)
        fields += i32("energy_consumed", -1)
        fields += i8("battery_remaining", remainingPercent)
        fields += i32("time_remaining", timeRemainingSeconds)
        fields += u8("charge_state", 0)
        if (includeExtendedFields) {
            fields += array("voltages_ext", Type.UINT16, overflow)
            fields += u8("mode", 0)
            fields += u32("fault_bitmask", 0L)
        }

        return payload(fields)
    }

    /** A `GLOBAL_POSITION_INT` payload. */
    fun globalPosition(
        latitudeE7: Int,
        longitudeE7: Int,
        altitudeMillimetres: Int = 0,
        relativeAltitudeMillimetres: Int = 0,
        headingCentidegrees: Int = 65_535,
    ): ByteArray = payload(
        u32("time_boot_ms", 0L),
        i32("lat", latitudeE7),
        i32("lon", longitudeE7),
        i32("alt", altitudeMillimetres),
        i32("relative_alt", relativeAltitudeMillimetres),
        i16("vx", 0),
        i16("vy", 0),
        i16("vz", 0),
        u16("hdg", headingCentidegrees),
    )

    /** A `VFR_HUD` payload. */
    fun vfrHud(groundSpeedMps: Float, throttlePercent: Int = 0): ByteArray = payload(
        f32("airspeed", groundSpeedMps),
        f32("groundspeed", groundSpeedMps),
        f32("alt", 0f),
        f32("climb", 0f),
        i16("heading", 0),
        u16("throttle", throttlePercent),
    )

    /**
     * A `HOME_POSITION` payload.
     *
     * The real definition declares twelve base fields and then a `time_usec` extension, and all
     * three int32 fields sit at the front of the wire order because the ten floats that follow
     * them are the same size — so the two coordinates land at offsets 0 and 4 and everything
     * behind them can be left as the zero padding a v2 sender would strip anyway. Modelling the
     * whole definition here would add ten fields no decoder reads, which is ten more places for
     * this builder and the decoder to agree on something nobody checks.
     */
    fun homePosition(latitudeE7: Int, longitudeE7: Int): ByteArray = payload(
        i32("latitude", latitudeE7),
        i32("longitude", longitudeE7),
        i32("altitude", 0),
    )

    /** A `HEARTBEAT` payload. */
    fun heartbeat(armed: Boolean, systemStatus: Int = 4): ByteArray = payload(
        u32("custom_mode", 0L),
        u8("type", 2), // MAV_TYPE_QUADROTOR
        u8("autopilot", 3), // MAV_AUTOPILOT_ARDUPILOTMEGA
        u8("base_mode", if (armed) 0x80 else 0x00),
        u8("system_status", systemStatus),
        u8("mavlink_version", 3),
    )

    // --- Writing -------------------------------------------------------------------------

    private fun write(payload: ByteArray, offset: Int, field: Field) = when (field.type) {
        Type.UINT8, Type.INT8 -> payload[offset] = field.value.toByte()

        Type.UINT16, Type.INT16 -> {
            val raw = field.value.toInt()
            payload[offset] = (raw and 0xFF).toByte()
            payload[offset + 1] = ((raw shr 8) and 0xFF).toByte()
        }

        Type.UINT32, Type.INT32 -> {
            val raw = field.value.toLong()
            payload[offset] = (raw and 0xFF).toByte()
            payload[offset + 1] = ((raw shr 8) and 0xFF).toByte()
            payload[offset + 2] = ((raw shr 16) and 0xFF).toByte()
            payload[offset + 3] = ((raw shr 24) and 0xFF).toByte()
        }

        Type.FLOAT32 -> {
            val raw = field.value.toFloat().toRawBits().toLong()
            payload[offset] = (raw and 0xFF).toByte()
            payload[offset + 1] = ((raw shr 8) and 0xFF).toByte()
            payload[offset + 2] = ((raw shr 16) and 0xFF).toByte()
            payload[offset + 3] = ((raw shr 24) and 0xFF).toByte()
        }
    }
}
