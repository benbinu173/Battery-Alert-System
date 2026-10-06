package com.batteryalert.guard.data.telemetry.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoders, against payloads built from the message definitions rather than from offsets.
 *
 * Every payload here comes from [MavlinkTestFrames], which lays fields out by the rule the
 * MAVLink generator uses — largest field first, declaration order within a size. The decoders
 * read fixed offsets. Those two facts have to agree, and if a decoder's offsets drift the
 * tests below stop agreeing with the builder.
 *
 * The one thing this cannot check is the field *list* itself. If a message declares a field
 * that neither the builder nor the decoder knows about, they are wrong together and these
 * tests stay green. Only the aircraft settles that.
 */
class MavlinkDecodersTest {

    // --- SYS_STATUS -----------------------------------------------------------------------

    @Test
    fun `sys_status converts millivolts and centiamps to volts and amps`() {
        val payload = MavlinkTestFrames.sysStatus(
            voltageMillivolts = 50_400,
            currentCentiamps = 1_850,
            remainingPercent = 74,
        )

        val message = MavlinkDecoders.sysStatus(payload)

        assertEquals(50.4, message.packVolts!!, 0.0001)
        assertEquals(18.5, message.currentAmps!!, 0.0001)
        assertEquals(74, message.remainingPercent)
    }

    @Test
    fun `sys_status treats a minus one current as no current sensor`() {
        // MAVLink's sentinel for "unknown" on a signed field. Reading it as -0.01 A would
        // report a healthy pack at rest as one drawing almost nothing, which is a reading.
        val payload = MavlinkTestFrames.sysStatus(50_400, currentCentiamps = -1, remainingPercent = 74)

        assertNull(MavlinkDecoders.sysStatus(payload).currentAmps)
    }

    @Test
    fun `sys_status treats a minus one remaining as no estimate`() {
        val payload = MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = -1)

        assertNull(MavlinkDecoders.sysStatus(payload).remainingPercent)
    }

    @Test
    fun `sys_status rejects a remaining percentage outside zero to a hundred`() {
        val payload = MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 127)

        assertNull(MavlinkDecoders.sysStatus(payload).remainingPercent)
    }

    @Test
    fun `sys_status reports no pack voltage when the field says zero`() {
        val payload = MavlinkTestFrames.sysStatus(0, 1_850, remainingPercent = 74)

        assertNull(MavlinkDecoders.sysStatus(payload).packVolts)
    }

    // --- HEARTBEAT ------------------------------------------------------------------------

    @Test
    fun `heartbeat reads the armed flag out of base_mode`() {
        assertTrue(MavlinkDecoders.heartbeat(MavlinkTestFrames.heartbeat(armed = true)).armed)
        assertFalse(MavlinkDecoders.heartbeat(MavlinkTestFrames.heartbeat(armed = false)).armed)
    }

    @Test
    fun `heartbeat reports the vehicle and autopilot type`() {
        val message = MavlinkDecoders.heartbeat(MavlinkTestFrames.heartbeat(armed = true))

        assertEquals(2, message.vehicleType) // MAV_TYPE_QUADROTOR
        assertEquals(4, message.systemStatus) // MAV_STATE_ACTIVE
    }

    // --- BATTERY_STATUS -------------------------------------------------------------------

    @Test
    fun `battery_status reads cells current temperature and remaining`() {
        val payload = MavlinkTestFrames.batteryStatus(
            cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
            temperatureCentidegrees = 3_150,
            currentCentiamps = 2_100,
            consumedMah = 3_400,
            remainingPercent = 62,
            timeRemainingSeconds = 940,
        )

        val message = MavlinkDecoders.batteryStatus(payload)

        assertEquals(6, message.cellVolts.size)
        assertEquals(4.050, message.cellVolts[0], 0.0001)
        assertEquals(24.295, message.packVolts!!, 0.001)
        assertEquals(31.5, message.temperatureCelsius!!, 0.0001)
        assertEquals(21.0, message.currentAmps!!, 0.0001)
        assertEquals(3_400.0, message.consumedMah!!, 0.0001)
        assertEquals(62, message.remainingPercent)
        assertEquals(940, message.timeRemainingSeconds)
    }

    @Test
    fun `battery_status drops the empty slots a six cell pack leaves in a ten slot array`() {
        val payload = MavlinkTestFrames.batteryStatus(
            cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
        )

        assertEquals(6, MavlinkDecoders.batteryStatus(payload).cellVolts.size)
    }

    @Test
    fun `battery_status reads the legacy layout when told to`() {
        // The same numbers, described for the older definition. If the decoder ignored the
        // layout this would come back as the wrong cells at the wrong voltages — which is
        // exactly the failure the assembler's corroboration exists to catch.
        val payload = MavlinkTestFrames.batteryStatus(
            cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
            temperatureCentidegrees = 3_150,
            currentCentiamps = 2_100,
            remainingPercent = 62,
            timeRemainingSeconds = 940,
            includeExtendedFields = false,
        )

        val message = MavlinkDecoders.batteryStatus(payload, BatteryStatusLayout.LEGACY)

        assertEquals(6, message.cellVolts.size)
        assertEquals(4.050, message.cellVolts[0], 0.0001)
        assertEquals(31.5, message.temperatureCelsius!!, 0.0001)
        assertEquals(21.0, message.currentAmps!!, 0.0001)
        assertEquals(62, message.remainingPercent)
        assertEquals(940, message.timeRemainingSeconds)
    }

    @Test
    fun `reading a legacy payload as extended gives different answers`() {
        // Not a bug being asserted — a demonstration that the layout choice is load-bearing.
        // The extended reader looks for cells 22 bytes further in, so it finds the trailing
        // region of the legacy payload instead.
        val legacy = MavlinkTestFrames.batteryStatus(
            cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
            temperatureCentidegrees = 3_150,
            remainingPercent = 62,
            includeExtendedFields = false,
        )

        val correct = MavlinkDecoders.batteryStatus(legacy, BatteryStatusLayout.LEGACY)
        val wrong = MavlinkDecoders.batteryStatus(legacy, BatteryStatusLayout.EXTENDED)

        assertTrue(
            "the two layouts must not read the same payload identically",
            correct.cellVolts != wrong.cellVolts,
        )
    }

    @Test
    fun `battery_status reports nothing for fields past the end of a short payload`() {
        // What v2 truncation produces when a vehicle fills in only the first few fields. The
        // temperature is set to a real value first, so what is being tested is the bounds
        // check rather than the separate rule that drops a zero temperature.
        val truncated = MavlinkTestFrames
            .batteryStatus(
                cellMillivolts = listOf(4_050, 4_048),
                temperatureCentidegrees = 3_150,
                remainingPercent = 62,
            )
            .copyOfRange(0, 8)

        val message = MavlinkDecoders.batteryStatus(truncated)

        assertTrue(message.cellVolts.isEmpty())
        assertNull(message.packVolts)
        assertNull(message.currentAmps)
        assertNull(message.temperatureCelsius)
        assertNull(message.remainingPercent)
    }

    @Test
    fun `battery_status reports nothing from an empty payload`() {
        val message = MavlinkDecoders.batteryStatus(ByteArray(0))

        assertTrue(message.cellVolts.isEmpty())
        assertNull(message.packVolts)
        assertNull(message.temperatureCelsius)
    }

    @Test
    fun `battery_status reports no temperature when the vehicle sent zero`() {
        val payload = MavlinkTestFrames.batteryStatus(
            cellMillivolts = listOf(4_050, 4_048),
            temperatureCentidegrees = 0,
        )

        assertNull(MavlinkDecoders.batteryStatus(payload).temperatureCelsius)
    }

    // --- GLOBAL_POSITION_INT ---------------------------------------------------------------

    @Test
    fun `global position converts scaled integers to degrees and metres`() {
        val payload = MavlinkTestFrames.globalPosition(
            latitudeE7 = 538_114_500,
            longitudeE7 = -14_321_000,
            altitudeMillimetres = 123_450,
            relativeAltitudeMillimetres = 50_000,
            headingCentidegrees = 27_500,
        )

        val message = MavlinkDecoders.globalPosition(payload)

        assertEquals(53.81145, message.latitudeDegrees!!, 0.000001)
        assertEquals(-1.4321, message.longitudeDegrees!!, 0.000001)
        assertEquals(123.45, message.altitudeMslMeters!!, 0.0001)
        assertEquals(50.0, message.altitudeAboveHomeMeters!!, 0.0001)
        assertEquals(275.0, message.headingDegrees!!, 0.0001)
    }

    @Test
    fun `global position treats the all ones heading as no heading`() {
        val payload = MavlinkTestFrames.globalPosition(100, 100, headingCentidegrees = 65_535)

        assertNull(MavlinkDecoders.globalPosition(payload).headingDegrees)
    }

    @Test
    fun `global position treats a zero latitude as no fix`() {
        // MAVLink uses (0, 0) for "no position". Reading it as a real fix would put the
        // aircraft in the Gulf of Guinea and have the RTL calculation measure from there.
        val payload = MavlinkTestFrames.globalPosition(latitudeE7 = 0, longitudeE7 = 0)

        val message = MavlinkDecoders.globalPosition(payload)

        assertNull(message.latitudeDegrees)
        assertNull(message.longitudeDegrees)
    }

    // --- VFR_HUD --------------------------------------------------------------------------

    @Test
    fun `vfr_hud reads ground speed and throttle`() {
        val payload = MavlinkTestFrames.vfrHud(groundSpeedMps = 12.5f, throttlePercent = 64)

        val message = MavlinkDecoders.vfrHud(payload)

        assertEquals(12.5, message.groundSpeedMps!!, 0.0001)
        assertEquals(64, message.throttlePercent)
    }

    // --- HOME_POSITION --------------------------------------------------------------------

    @Test
    fun `home_position reads the two coordinates the distance needs`() {
        val payload = MavlinkTestFrames.homePosition(
            latitudeE7 = 538_114_500,
            longitudeE7 = -14_321_000,
        )

        val home = MavlinkDecoders.homePosition(payload)!!

        assertEquals(53.81145, home.latitudeDegrees, 0.000001)
        assertEquals(-1.4321, home.longitudeDegrees, 0.000001)
    }

    @Test
    fun `home_position reports no home rather than a home in the gulf of guinea`() {
        // MAVLink's convention for an unset home point. Taken at face value it would put every
        // aircraft's home thousands of kilometres out to sea, and FR 3.1 would respond to the
        // resulting distance by demanding an immediate return.
        val payload = MavlinkTestFrames.homePosition(latitudeE7 = 0, longitudeE7 = 0)

        assertNull(MavlinkDecoders.homePosition(payload))
    }

    @Test
    fun `home_position rejects a half-set home`() {
        // A latitude with no longitude is not a position, and offering one would push the
        // decision onto every caller that wants a distance.
        val payload = MavlinkTestFrames.homePosition(latitudeE7 = 538_114_500, longitudeE7 = 0)

        assertNull(MavlinkDecoders.homePosition(payload))
    }

    // --- The reader itself -----------------------------------------------------------------

    @Test
    fun `reader returns null rather than zero for anything outside the payload`() {
        val reader = MavlinkPayloadReader(ByteArray(4))

        assertNull(reader.uint8(4))
        assertNull(reader.uint16(3))
        assertNull(reader.int32(1))
        assertNull(reader.float32(-1))
        assertEquals(0, reader.uint8(3))
    }

    @Test
    fun `reader widens an unsigned thirty two bit value without sign extending it`() {
        // 0xFFFFFFFF must arrive as 4294967295, not as -1. The blackbox and the consumed
        // charge counter both use this path.
        val reader = MavlinkPayloadReader(byteArrayOf(-1, -1, -1, -1))

        assertEquals(4_294_967_295L, reader.uint32(0))
        assertEquals(-1, reader.int32(0))
    }

    @Test
    fun `reader returns only the array elements that are actually present`() {
        val reader = MavlinkPayloadReader(ByteArray(6))

        assertEquals(3, reader.uint16Run(offset = 0, count = 10).size)
        assertTrue(reader.uint16Run(offset = 100, count = 10).isEmpty())
    }
}
