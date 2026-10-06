package com.batteryalert.guard.data.telemetry.mavlink

import com.batteryalert.guard.domain.model.BatteryTelemetry
import com.batteryalert.guard.domain.model.FlightState
import com.batteryalert.guard.domain.model.GpsData
import com.batteryalert.guard.domain.usecase.GeoDistance
import kotlin.math.abs

/**
 * Whether the per-cell voltages on offer can be believed.
 *
 * `BATTERY_STATUS` is the only message here whose field layout is not fixed, and a layout
 * error does not produce an obviously broken reading — it produces plausible-looking numbers
 * from the wrong bytes. So the app does not publish cell data on the strength of having
 * parsed it. It publishes it when the cells agree with the pack total the autopilot reports
 * separately, and says so plainly when they do not.
 *
 * Public because it is part of [LinkHealth], which the diagnostics screen reads.
 */
enum class CellDataTrust {
    /** No `BATTERY_STATUS` seen. Nothing to corroborate and nothing to report. */
    NOT_REPORTED,

    /** Cells and pack total agree. Per-cell figures are safe to act on. */
    TRUSTED,

    /**
     * Cells and pack total disagree, so the cell figures are withheld.
     *
     * FR 2.2's cell-imbalance alert cannot fire while this holds — deliberately. A delta
     * computed from misread bytes would either miss a real imbalance or invent one, and an
     * alert that is sometimes imaginary is one the operator learns to ignore.
     */
    UNCORROBORATED,
}

/**
 * What the assembler currently believes, after staleness has been applied.
 *
 * [battery] and [gps] are nulled out wholesale once their source messages go quiet, rather
 * than each field being nulled individually. A partially stale snapshot is the most dangerous
 * thing this class could produce: a frozen voltage next to a live current reads as a healthy
 * pack holding steady.
 */
internal data class AssemblerSnapshot(
    val battery: BatteryTelemetry,
    val gps: GpsData,
    val flight: FlightState,
    /** Null until a heartbeat arrives. */
    val armed: Boolean?,
    val cellDataTrust: CellDataTrust,
    /** Set when the evidence points at the other [BatteryStatusLayout]. Diagnostic only. */
    val suspectedLayout: BatteryStatusLayout?,
    val batteryIsFresh: Boolean,
    val positionIsFresh: Boolean,
)

/**
 * Merges decoded MAVLink messages into the domain models the rest of the app reads.
 *
 * ### Two sources for one battery
 *
 * Pack voltage and current are reported twice, once by `SYS_STATUS` and once by
 * `BATTERY_STATUS`. Where they conflict this prefers `SYS_STATUS`, because that is the
 * measurement the *autopilot itself* arms its own failsafes from. An app that disagrees with
 * the aircraft about the pack is an app that will one day be ignored at the wrong moment.
 *
 * `BATTERY_STATUS` is preferred for the things `SYS_STATUS` does not carry: per-cell
 * voltages, the consumed count and the pack temperature.
 *
 * ### Staleness
 *
 * The link dropping is handled upstream, but a link that is up while one *message type* stops
 * arriving is a quieter failure and a more dangerous one. Each message type is timed
 * separately and its contribution disappears when it goes quiet, so nothing in this app can
 * ever show a value the aircraft stopped sending.
 *
 * ### Threading
 *
 * Deliberately not thread-safe; driven from the single coroutine reading the transport.
 */
internal class MavlinkTelemetryAssembler(
    private val batteryLayout: BatteryStatusLayout = BatteryStatusLayout.EXTENDED,
    private val batteryStaleAfterMillis: Long = DEFAULT_BATTERY_STALE_MILLIS,
    private val positionStaleAfterMillis: Long = DEFAULT_POSITION_STALE_MILLIS,
) {

    private var sysStatus: SysStatusMessage? = null
    private var batteryStatus: BatteryStatusMessage? = null
    private var lastBatteryStatusPayload: ByteArray? = null
    private var heartbeat: HeartbeatMessage? = null
    private var position: GlobalPositionMessage? = null
    private var hud: VfrHudMessage? = null

    /**
     * The home point, deliberately **not** timestamped.
     *
     * Every other message here is dropped when it goes quiet, and this one must not be.
     * `HOME_POSITION` is sent once, when the vehicle sets its home — usually at arming — and
     * never streamed. Running it through the staleness window would delete the home point a
     * few seconds into the flight, which is exactly when a distance to it starts to be worth
     * having, and would leave FR 3.1's RTL alert unable to fire at the moment it matters most.
     *
     * It is a property of the flight rather than a reading from it, like the dialect constants,
     * so it lives until [reset] — a new link means a new flight means a new home.
     */
    private var home: HomePositionMessage? = null

    private var sysStatusAt: Long = Long.MIN_VALUE
    private var batteryStatusAt: Long = Long.MIN_VALUE
    private var heartbeatAt: Long = Long.MIN_VALUE
    private var positionAt: Long = Long.MIN_VALUE
    private var hudAt: Long = Long.MIN_VALUE

    private var trust: CellDataTrust = CellDataTrust.NOT_REPORTED
    private var suspected: BatteryStatusLayout? = null

    /**
     * Folds one validated frame in.
     *
     * Frames of message types this app does not decode are ignored here — the parser has
     * already counted them, and there is nothing this class could do with them.
     */
    fun onFrame(frame: MavlinkFrame, nowMillis: Long) {
        when (frame.messageId) {
            MavlinkMessageSpec.ID_SYS_STATUS -> {
                sysStatus = MavlinkDecoders.sysStatus(frame.payload)
                sysStatusAt = nowMillis
            }

            MavlinkMessageSpec.ID_BATTERY_STATUS -> {
                // Corroboration needs the pack total from SYS_STATUS, so it is resolved here,
                // when both are in hand, rather than at publish time when they might not be.
                lastBatteryStatusPayload = frame.payload
                batteryStatus = MavlinkDecoders.batteryStatus(frame.payload, batteryLayout)
                batteryStatusAt = nowMillis
                resolveCellTrust()
            }

            MavlinkMessageSpec.ID_HEARTBEAT -> {
                heartbeat = MavlinkDecoders.heartbeat(frame.payload)
                heartbeatAt = nowMillis
            }

            MavlinkMessageSpec.ID_GLOBAL_POSITION_INT -> {
                position = MavlinkDecoders.globalPosition(frame.payload)
                positionAt = nowMillis
            }

            MavlinkMessageSpec.ID_VFR_HUD -> {
                hud = MavlinkDecoders.vfrHud(frame.payload)
                hudAt = nowMillis
            }

            MavlinkMessageSpec.ID_HOME_POSITION -> {
                // The guard is not redundant: a vehicle with no home set reports zeroes, and
                // keeping the last known good home is better than trading it for a point in
                // the Atlantic. A vehicle that genuinely moves its home re-sends this, which
                // is why it overwrites rather than latching only once.
                MavlinkDecoders.homePosition(frame.payload)?.let { home = it }
            }
        }
    }

    /** The current view, with anything whose source has gone quiet dropped. */
    fun snapshot(nowMillis: Long): AssemblerSnapshot {
        val batteryFresh = isFresh(batteryStatusAt, nowMillis, batteryStaleAfterMillis) ||
            isFresh(sysStatusAt, nowMillis, batteryStaleAfterMillis)
        val positionFresh = isFresh(positionAt, nowMillis, positionStaleAfterMillis)

        val liveBattery = if (isFresh(batteryStatusAt, nowMillis, batteryStaleAfterMillis)) {
            batteryStatus
        } else {
            null
        }
        val liveSys = if (isFresh(sysStatusAt, nowMillis, batteryStaleAfterMillis)) sysStatus else null
        val liveHud = if (isFresh(hudAt, nowMillis, positionStaleAfterMillis)) hud else null
        val livePosition = if (positionFresh) position else null
        val liveHeartbeat = if (isFresh(heartbeatAt, nowMillis, positionStaleAfterMillis)) {
            heartbeat
        } else {
            null
        }

        return AssemblerSnapshot(
            battery = mergeBattery(liveSys, liveBattery, trust),
            gps = mergeGps(livePosition, home),
            flight = mergeFlight(liveHud),
            armed = liveHeartbeat?.armed,
            cellDataTrust = if (liveBattery == null) CellDataTrust.NOT_REPORTED else trust,
            suspectedLayout = suspected,
            batteryIsFresh = batteryFresh,
            positionIsFresh = positionFresh,
        )
    }

    /** Forgets everything, keeping no configuration. Called when the link is torn down. */
    fun reset() {
        sysStatus = null
        batteryStatus = null
        lastBatteryStatusPayload = null
        heartbeat = null
        position = null
        hud = null
        home = null
        sysStatusAt = Long.MIN_VALUE
        batteryStatusAt = Long.MIN_VALUE
        heartbeatAt = Long.MIN_VALUE
        positionAt = Long.MIN_VALUE
        hudAt = Long.MIN_VALUE
        trust = CellDataTrust.NOT_REPORTED
        suspected = null
    }

    // --- Trust ---------------------------------------------------------------------------

    /**
     * Decides whether the per-cell figures agree with the pack total.
     *
     * Two things have to hold, and the second is not redundant. The cells must describe a
     * physically possible pack, *and* their total must match what the autopilot measured. A
     * sum can agree by coincidence on a payload read at the wrong offset — six "cells" of
     * 8.4 V each add up to a perfectly ordinary 12S pack voltage — and a coincidence that
     * passes is worse than a mismatch, because a mismatch is at least visible.
     *
     * The tolerance is a floor plus a fraction: small packs need an absolute allowance because
     * two independently rounded millivolt readings can differ by a few tens of millivolts
     * legitimately, and large packs need a proportional one because the rounding error scales
     * with cell count.
     *
     * When the cells do not fit, the *other* layout is tried before giving up. If the
     * alternative both sums to the right total and describes a plausible pack, that is worth
     * saying out loud — it means the autopilot is running the older dialect and the fix is one
     * enum constant, not a rewrite.
     */
    private fun resolveCellTrust() {
        val cells = batteryStatus?.cellVolts.orEmpty()
        val packTotal = sysStatus?.packVolts

        if (cells.isEmpty()) {
            trust = CellDataTrust.NOT_REPORTED
            suspected = null
            return
        }
        if (packTotal == null) {
            // Nothing to check the cells against yet. Withheld until there is: this is the
            // one case where the safe answer and the cautious answer are the same answer.
            trust = CellDataTrust.UNCORROBORATED
            return
        }

        if (fits(cells, packTotal)) {
            trust = CellDataTrust.TRUSTED
            suspected = null
            return
        }

        trust = CellDataTrust.UNCORROBORATED
        suspected = alternativeLayoutIfItFits(packTotal)
    }

    /** A physically possible pack *and* a total that matches what the autopilot measured. */
    private fun fits(cells: List<Double>, packTotal: Double): Boolean =
        cells.all { it in PLAUSIBLE_CELL_VOLTS } && agrees(cells.sum(), packTotal)

    private fun agrees(cellSum: Double, packTotal: Double): Boolean {
        val tolerance = maxOf(CORROBORATION_FLOOR_VOLTS, packTotal * CORROBORATION_FRACTION)
        return abs(cellSum - packTotal) <= tolerance
    }

    /** Re-reads the last payload under the other layout, and reports it only if it fits. */
    private fun alternativeLayoutIfItFits(packTotal: Double): BatteryStatusLayout? {
        val payload = lastBatteryStatusPayload ?: return null
        val alternative = batteryLayout.alternative()
        val cells = MavlinkDecoders.batteryStatus(payload, alternative).cellVolts

        return if (cells.isNotEmpty() && fits(cells, packTotal)) alternative else null
    }

    // --- Merging -------------------------------------------------------------------------

    private fun mergeBattery(
        sys: SysStatusMessage?,
        status: BatteryStatusMessage?,
        cellTrust: CellDataTrust,
    ): BatteryTelemetry {
        if (sys == null && status == null) return BatteryTelemetry.EMPTY

        return BatteryTelemetry(
            // The autopilot's own estimate first: it is the one the aircraft acts on.
            batteryPercentage = status?.remainingPercent ?: sys?.remainingPercent,
            totalVoltage = sys?.packVolts ?: status?.packVolts,
            current = sys?.currentAmps ?: status?.currentAmps,
            // Withheld unless corroborated, so no cell alert can fire on a misread layout.
            cellVoltages = if (cellTrust == CellDataTrust.TRUSTED) {
                status?.cellVolts.orEmpty()
            } else {
                emptyList()
            },
            temperature = status?.temperatureCelsius,
            // current_consumed is charge used since boot, not capacity remaining, so it is
            // not a substitute for remainingCapacityMah and is not offered as one. Nothing
            // here sets remainingCapacityMah: the pack's total capacity is a property of the
            // airframe, and no MAVLink message in this set reports it. FR 3.1 therefore has
            // no RTL figure over MAVLink until a pack capacity is configured; see the README.
            remainingCapacityMah = null,
            dischargeRateMahPerMin = null,
        )
    }

    private fun mergeGps(position: GlobalPositionMessage?, home: HomePositionMessage?): GpsData {
        if (position == null) return GpsData.EMPTY

        val latitude = position.latitudeDegrees
        val longitude = position.longitudeDegrees
        val hasFix = latitude != null && longitude != null

        return GpsData(
            latitude = latitude,
            longitude = longitude,
            altitude = position.altitudeAboveHomeMeters ?: position.altitudeMslMeters,
            // Both ends of the measurement have to be known, and each goes missing for its own
            // reason: the home point until the vehicle reports one, and the current position
            // the moment GLOBAL_POSITION_INT stops arriving. A distance with only one of them
            // is not a rough answer, it is a fabricated one — and FR 3.1 turns this number
            // into a demand to land, so a fabricated one is worse than none at all.
            distanceToHomeMeters = if (home != null && latitude != null && longitude != null) {
                GeoDistance.metresBetween(
                    fromLatitudeDegrees = latitude,
                    fromLongitudeDegrees = longitude,
                    toLatitudeDegrees = home.latitudeDegrees,
                    toLongitudeDegrees = home.longitudeDegrees,
                )
            } else {
                null
            },
            hasFix = hasFix,
        )
    }

    private fun mergeFlight(hud: VfrHudMessage?): FlightState = FlightState(
        // Groundspeed, not airspeed: this is what the vehicle will actually cover ground at
        // on the way home, and FR 3.1's time-to-home is a ground distance over a ground speed.
        cruisingSpeedMps = hud?.groundSpeedMps?.takeIf { it > 0.0 },
        payloadActive = false,
        sprayingActive = false,
    )

    private fun isFresh(recordedAt: Long, nowMillis: Long, windowMillis: Long): Boolean =
        recordedAt != Long.MIN_VALUE &&
            nowMillis >= recordedAt &&
            nowMillis - recordedAt <= windowMillis

    companion object {
        /** Battery messages normally arrive several times a second; this is generous. */
        const val DEFAULT_BATTERY_STALE_MILLIS = 3_000L

        /** Position arrives more slowly, especially when the vehicle is not moving. */
        const val DEFAULT_POSITION_STALE_MILLIS = 5_000L

        /** Absolute allowance when comparing a summed cell voltage against a pack total. */
        private const val CORROBORATION_FLOOR_VOLTS = 0.15

        /** Proportional allowance for the same comparison. 1% of a 50 V pack is 0.5 V. */
        private const val CORROBORATION_FRACTION = 0.01

        /** A cell outside this band is not a cell; it is a misread byte. */
        private val PLAUSIBLE_CELL_VOLTS = 2.0..5.0
    }
}
