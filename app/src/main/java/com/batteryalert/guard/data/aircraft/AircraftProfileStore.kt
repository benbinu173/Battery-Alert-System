package com.batteryalert.guard.data.aircraft

import kotlinx.coroutines.flow.StateFlow

/**
 * What the app knows about the aircraft it is flying, that the aircraft does not tell it.
 *
 * There is exactly one such thing today — the pack's total capacity — and this exists as its
 * own seam rather than as a field on the telemetry source, because it is not telemetry. It does
 * not arrive over the wire, it does not expire, and it does not change when the link drops. It
 * is what the operator told the app before takeoff, and it outlives every flight the app takes
 * part in.
 *
 * A `StateFlow` rather than a getter because two screens read it and one writes it. The
 * dashboard turns it into a flight-time estimate; the configuration screen sets it. Without a
 * flow the dashboard would keep rendering the figure it computed at startup until something
 * else happened to redraw it.
 */
interface AircraftProfileStore {

    /**
     * The configured pack capacity in mAh, or null when the operator has not set one.
     *
     * Null is not a default and is not treated as one anywhere downstream: it means the
     * airframe's capacity is genuinely unknown, and FR 2.3 and FR 3.1 both withhold their
     * figures rather than assuming a pack.
     */
    val packCapacityMah: StateFlow<Double?>

    /**
     * Records the pack capacity, or clears it when given null.
     *
     * Takes a nullable rather than a separate `clear()` because "the operator removed the
     * configuration" and "the operator configured it to nothing" are the same state, and two
     * ways of expressing one state is how they drift apart.
     */
    fun setPackCapacityMah(capacityMah: Double?)
}
