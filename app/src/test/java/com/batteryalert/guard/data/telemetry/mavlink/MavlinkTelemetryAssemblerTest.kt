package com.batteryalert.guard.data.telemetry.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge from MAVLink messages into the domain models.
 *
 * Three things are being defended here, and they are the three ways a decoder can be wrong
 * without looking wrong: preferring the autopilot's own measurement, refusing to publish cell
 * voltages the pack total does not support, and forgetting values whose messages have stopped
 * arriving.
 */
class MavlinkTelemetryAssemblerTest {

    private var now = 1_000_000L

    private fun frame(messageId: Int, payload: ByteArray) =
        MavlinkFrame(
            messageId = messageId,
            payload = payload,
            sequence = 0,
            systemId = 1,
            componentId = 1,
            version = 2,
        )

    private fun MavlinkTelemetryAssembler.accept(messageId: Int, payload: ByteArray) =
        onFrame(frame(messageId, payload), now)

    private fun MavlinkTelemetryAssembler.report() = snapshot(now)

    // --- Preferred sources ------------------------------------------------------------------

    @Test
    fun `pack voltage and current come from the flight controller's own measurement`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        // The same pack measured twice, disagreeing slightly: the autopilot reports 18.5 A and
        // the battery monitor 18.0 A. The autopilot's figure is the one its own failsafes are
        // armed on, so it is the one the dashboard must agree with.
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = List(12) { 4_200 },
                currentCentiamps = 1_800,
            ),
        )

        val battery = assembler.report().battery

        assertEquals(50.4, battery.totalVoltage!!, 0.0001)
        assertEquals(18.5, battery.current!!, 0.0001)
        assertEquals(CellDataTrust.TRUSTED, assembler.report().cellDataTrust)
    }

    @Test
    fun `pack voltage falls back to the cells when sys_status has not arrived`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
                currentCentiamps = 2_100,
            ),
        )

        val battery = assembler.report().battery

        assertEquals(24.295, battery.totalVoltage!!, 0.001)
        assertEquals(21.0, battery.current!!, 0.0001)
    }

    @Test
    fun `remaining percentage comes from battery_status when both report it`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = listOf(4_200, 4_200, 4_200, 4_200, 4_200, 4_200),
                remainingPercent = 71,
            ),
        )

        // The cells sum to 25.2 V, which is not 50.4 V, so the trust check also fails here —
        // but the percentage is not part of the corroboration and still resolves.
        assertEquals(71, assembler.report().battery.batteryPercentage)
    }

    // --- Cell corroboration -----------------------------------------------------------------

    @Test
    fun `cells are published when their sum agrees with the pack total`() {
        val assembler = MavlinkTelemetryAssembler()
        val cells = listOf(4_200, 4_198, 4_202, 4_196, 4_200, 4_199, 4_200, 4_198, 4_201, 4_196, 4_200, 4_199)
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_389, 1_850, remainingPercent = 74),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(cellMillivolts = cells),
        )

        val report = assembler.report()

        assertEquals(CellDataTrust.TRUSTED, report.cellDataTrust)
        assertEquals(12, report.battery.cellVoltages.size)
        assertEquals(4.200, report.battery.cellVoltages[0], 0.0001)
        assertNull(report.suspectedLayout)
    }

    @Test
    fun `cells are withheld when their sum disagrees with the pack total`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        // Cells describing a 12S pack while SYS_STATUS describes something at 50.4 V: the two
        // cannot both be true, and an imbalance alert computed from the wrong one would either
        // miss a real fault or invent one.
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
            ),
        )

        val report = assembler.report()

        assertEquals(CellDataTrust.UNCORROBORATED, report.cellDataTrust)
        assertTrue("no cell figures may reach the alert engine", report.battery.cellVoltages.isEmpty())
    }

    @Test
    fun `cells are withheld until something corroborates them`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(cellMillivolts = listOf(4_050, 4_048, 4_052)),
        )

        assertEquals(CellDataTrust.UNCORROBORATED, assembler.report().cellDataTrust)
    }

    @Test
    fun `a pack total outside the packed cell range is rejected`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        // Cells at 8.4 V each are not any lithium cell; the total agrees with the pack only
        // because there are six of them.
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = listOf(8_400, 8_400, 8_400, 8_400, 8_400, 8_400),
            ),
        )

        val report = assembler.report()

        assertEquals(CellDataTrust.UNCORROBORATED, report.cellDataTrust)
        assertTrue(report.battery.cellVoltages.isEmpty())
    }

    @Test
    fun `a legacy payload read as extended is caught and the other layout is named`() {
        val assembler = MavlinkTelemetryAssembler()
        // A 10S pack, which is the largest that fits the legacy definition's ten slots.
        val cells = listOf(4_200, 4_198, 4_202, 4_196, 4_200, 4_199, 4_200, 4_198, 4_201, 4_196)
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(41_990, 1_850, remainingPercent = 74),
        )
        // Built for the older definition but presented to an assembler configured for the
        // newer one. Nothing about the frame says which it is; only the arithmetic does.
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(cellMillivolts = cells, includeExtendedFields = false),
        )

        val report = assembler.report()

        assertEquals(CellDataTrust.UNCORROBORATED, report.cellDataTrust)
        assertEquals(BatteryStatusLayout.LEGACY, report.suspectedLayout)
    }

    @Test
    fun `a twelve cell pack reports all twelve cells`() {
        // Ten cells fit the main array and two spill into the overflow array. A decoder that
        // read only the first array would report ten of twelve and quietly stop checking the
        // other two for imbalance.
        val assembler = MavlinkTelemetryAssembler()
        val cells = listOf(
            4_200, 4_198, 4_202, 4_196, 4_200, 4_199,
            4_200, 4_198, 4_201, 4_196, 4_199, 4_200,
        )
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_389, 1_850, remainingPercent = 74),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(cellMillivolts = cells),
        )

        val report = assembler.report()

        assertEquals(CellDataTrust.TRUSTED, report.cellDataTrust)
        assertEquals(12, report.battery.cellVoltages.size)
        assertEquals(4.199, report.battery.cellVoltages[10], 0.0001)
        assertEquals(4.200, report.battery.cellVoltages[11], 0.0001)
    }

    @Test
    fun `no layout is named when neither one fits`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = listOf(4_050, 4_048, 4_052, 4_046, 4_050, 4_049),
            ),
        )

        assertNull(assembler.report().suspectedLayout)
    }

    // --- Staleness --------------------------------------------------------------------------

    @Test
    fun `battery data disappears when its messages stop arriving`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        assertTrue(assembler.report().batteryIsFresh)

        // Past the window with nothing arriving. A frozen 50.4 V next to a live current is
        // the most dangerous thing this class could show.
        now += MavlinkTelemetryAssembler.DEFAULT_BATTERY_STALE_MILLIS + 1

        val report = assembler.report()

        assertFalse(report.batteryIsFresh)
        assertNull(report.battery.totalVoltage)
        assertNull(report.battery.batteryPercentage)
        assertEquals(CellDataTrust.NOT_REPORTED, report.cellDataTrust)
    }

    @Test
    fun `position disappears when its message stops arriving`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )
        assertTrue(assembler.report().positionIsFresh)

        now += MavlinkTelemetryAssembler.DEFAULT_POSITION_STALE_MILLIS + 1

        val report = assembler.report()

        assertFalse(report.positionIsFresh)
        assertNull(report.gps.latitude)
        assertFalse(report.gps.hasFix)
    }

    @Test
    fun `a slow position update does not expire the faster battery messages`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )
        // Position arriving once a second is normal; battery messages arriving several times
        // a second is also normal. They must not expire each other.
        // Long enough for the position to actually expire, with a battery message arriving each
        // second so the battery stays fresh the whole time. Position arrives about once a second
        // on a real link and battery messages several times a second; neither may expire the
        // other. Advancing less than the position window would make the assertion below vacuous.
        val seconds = (MavlinkTelemetryAssembler.DEFAULT_POSITION_STALE_MILLIS / 1_000 + 1).toInt()
        repeat(seconds) {
            now += 1_000
            assembler.accept(
                MavlinkMessageSpec.ID_SYS_STATUS,
                MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
            )
        }

        assertEquals(50.4, assembler.report().battery.totalVoltage!!, 0.0001)
        assertFalse("the position, not the battery, should have gone stale", assembler.report().positionIsFresh)
    }

    // --- Position, speed and arming ---------------------------------------------------------

    @Test
    fun `a position with a fix sets the fix flag`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(
                latitudeE7 = 538_114_500,
                longitudeE7 = -14_321_000,
                relativeAltitudeMillimetres = 42_000,
            ),
        )

        val gps = assembler.report().gps

        assertTrue(gps.hasFix)
        assertEquals(53.81145, gps.latitude!!, 0.000001)
        assertEquals(42.0, gps.altitude!!, 0.0001)
    }

    @Test
    fun `a distance to home is measured once the vehicle reports one`() {
        val assembler = MavlinkTelemetryAssembler()
        // Home a thousandth of a degree north of the aircraft, on the same meridian. Two
        // points sharing a longitude are exactly `radius x latitude difference` apart, so this
        // pins the units as well as the arithmetic: 0.00105 degrees is about 117 metres, and
        // an implementation treating degrees as metres would say 0.00105.
        assembler.accept(
            MavlinkMessageSpec.ID_HOME_POSITION,
            MavlinkTestFrames.homePosition(538_125_000, -14_321_000),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )

        assertEquals(116.75, assembler.report().gps.distanceToHomeMeters!!, 0.5)
    }

    @Test
    fun `the home point outlives the message that carried it`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_HOME_POSITION,
            MavlinkTestFrames.homePosition(538_125_000, -14_321_000),
        )

        // Far past the window that expires every other message type. HOME_POSITION is sent
        // once, when the vehicle sets its home, and never streamed — so running it through the
        // same staleness rule would delete the home point seconds into the flight, which is
        // exactly when a distance to it becomes worth having and when FR 3.1 needs it.
        now += MavlinkTelemetryAssembler.DEFAULT_POSITION_STALE_MILLIS * 100
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )

        assertEquals(116.75, assembler.report().gps.distanceToHomeMeters!!, 0.5)
    }

    @Test
    fun `no distance is offered before the vehicle reports a home`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )

        // A position with nothing to measure it against. FR 3.1 turns this number into a
        // demand to land, so the dashboard's "no RTL figure" state is the only honest answer.
        assertNull(assembler.report().gps.distanceToHomeMeters)
    }

    @Test
    fun `a home reported as zeroes does not move the aircraft to the atlantic`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_HOME_POSITION,
            MavlinkTestFrames.homePosition(latitudeE7 = 0, longitudeE7 = 0),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )

        assertNull(assembler.report().gps.distanceToHomeMeters)
    }

    @Test
    fun `the distance goes when the position does`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_HOME_POSITION,
            MavlinkTestFrames.homePosition(538_125_000, -14_321_000),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )
        assertTrue(assembler.report().gps.distanceToHomeMeters != null)

        // The home point is latched, but the aircraft's own position is a reading and expires
        // like one. A distance computed against a position the aircraft stopped sending would
        // be a stale number wearing a live one's clothes.
        now += MavlinkTelemetryAssembler.DEFAULT_POSITION_STALE_MILLIS + 1

        assertNull(assembler.report().gps.distanceToHomeMeters)
        assertNull(assembler.report().gps.latitude)
    }

    @Test
    fun `the aircraft sitting on its home pad is zero metres from home`() {
        val assembler = MavlinkTelemetryAssembler()
        val latitude = 538_114_500
        val longitude = -14_321_000
        assembler.accept(
            MavlinkMessageSpec.ID_HOME_POSITION,
            MavlinkTestFrames.homePosition(latitude, longitude),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(latitude, longitude),
        )

        // Zero, and reported as zero rather than as null: this is a measurement, and FR 3.1's
        // safety margin alone is the correct requirement for it.
        assertEquals(0.0, assembler.report().gps.distanceToHomeMeters!!, 0.001)
    }

    @Test
    fun `remaining capacity is left unknown rather than inferred from charge used`() {
        // current_consumed is charge used since boot. Treating it as capacity remaining would
        // report a full pack as an empty one on the second flight of the day.
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_BATTERY_STATUS,
            MavlinkTestFrames.batteryStatus(
                cellMillivolts = listOf(4_050, 4_048),
                consumedMah = 3_400,
            ),
        )

        assertNull(assembler.report().battery.remainingCapacityMah)
    }

    @Test
    fun `cruising speed comes from ground speed and not from airspeed`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(MavlinkMessageSpec.ID_VFR_HUD, MavlinkTestFrames.vfrHud(12.5f, 64))

        assertEquals(12.5, assembler.report().flight.cruisingSpeedMps!!, 0.0001)
    }

    @Test
    fun `a stationary aircraft reports no cruising speed`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(MavlinkMessageSpec.ID_VFR_HUD, MavlinkTestFrames.vfrHud(0f, 0))

        // Zero is not a speed to divide a distance by; it is the absence of one.
        assertNull(assembler.report().flight.cruisingSpeedMps)
    }

    @Test
    fun `arming is unknown until a heartbeat arrives`() {
        val assembler = MavlinkTelemetryAssembler()
        assertNull(assembler.report().armed)

        assembler.accept(MavlinkMessageSpec.ID_HEARTBEAT, MavlinkTestFrames.heartbeat(armed = true))

        assertEquals(true, assembler.report().armed)
    }

    @Test
    fun `arming goes unknown again once heartbeats stop`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(MavlinkMessageSpec.ID_HEARTBEAT, MavlinkTestFrames.heartbeat(armed = true))
        now += MavlinkTelemetryAssembler.DEFAULT_POSITION_STALE_MILLIS + 1

        assertNull(assembler.report().armed)
    }

    // --- Housekeeping -----------------------------------------------------------------------

    @Test
    fun `an empty assembler reports nothing at all`() {
        val report = MavlinkTelemetryAssembler().report()

        assertNull(report.battery.totalVoltage)
        assertNull(report.gps.latitude)
        assertNull(report.flight.cruisingSpeedMps)
        assertFalse(report.batteryIsFresh)
        assertFalse(report.positionIsFresh)
        assertEquals(CellDataTrust.NOT_REPORTED, report.cellDataTrust)
    }

    @Test
    fun `reset forgets everything the previous link said`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(
            MavlinkMessageSpec.ID_SYS_STATUS,
            MavlinkTestFrames.sysStatus(50_400, 1_850, remainingPercent = 74),
        )
        assembler.accept(
            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT,
            MavlinkTestFrames.globalPosition(538_114_500, -14_321_000),
        )

        assembler.reset()

        val report = assembler.report()
        assertNull(report.battery.totalVoltage)
        assertNull(report.gps.latitude)
        assertFalse(report.batteryIsFresh)
    }

    @Test
    fun `messages this app does not decode are ignored`() {
        val assembler = MavlinkTelemetryAssembler()
        assembler.accept(MavlinkMessageSpec.ID_HEARTBEAT, MavlinkTestFrames.heartbeat(armed = true))
        // A frame of a type with no decoder must not disturb what is already held.
        assembler.onFrame(frame(9_999, ByteArray(8)), now)

        assertEquals(true, assembler.report().armed)
    }
}
